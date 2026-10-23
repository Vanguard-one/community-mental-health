package com.cmh.psycare.ai;

import com.cmh.psycare.engine.CrisisDetector;
import com.cmh.psycare.service.AiUsageRecorder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 追问建议生成器：在一轮问答之后，让模型给出三条「用户可能接着问」的话。
 *
 * <h3>为什么单独一个类，而不是塞进 {@code AiConsultService}</h3>
 * <p>它是一条<b>独立的、可整体失败的</b>旁路：失败了就什么都不发，主对话早已结束。
 * 把它和主链路放在同一个类里，会让 {@code AiConsultService} 继续膨胀
 * （那个类已经在承担安全护栏、阶段机、推荐判定、落库四件事），
 * 而这条旁路的全部逻辑 —— 提示词、解析、过滤、降级 —— 都与主链路无关。
 *
 * <h3>为什么输入只给「本轮两句」而不是完整历史</h3>
 * <p>三条建议要贴着「刚才那句回答」走，给全量历史既贵又会让模型往早前的话题上偏。
 * 各截断到几百字足够判断语境，同时天然限制了 PII 的传输面。
 *
 * <h3>失败策略：静默、整批丢弃</h3>
 * <p>任何一步失败（超时、非 JSON、全被安全过滤）都返回 {@code Mono.empty()}，
 * 前端继续显示静态兜底的三条。绝不部分采用 —— 两条新的一条旧的混在一起，
 * 用户会以为其中某条是模型特意给出的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupSuggester {

    /** 最多三条：与前端输入框上方的三个槽位一一对应（下标即槽位） */
    public static final int MAX_ITEMS = 3;
    /** 单条字符上限。超过就截断 —— 前端 chip 是单行 nowrap，再长会被裁掉 */
    private static final int MAX_ITEM_CHARS = 26;
    /** 单条最短字数：一个字或两个字的「嗯」「然后呢」没有信息量，但也不禁止，只是不补位 */
    private static final int MIN_ITEM_CHARS = 3;
    /** 喂给模型的正文上限（居民一句 / 助手一段） */
    private static final int USER_CHARS = 300;
    private static final int AI_CHARS = 800;
    /**
     * 硬超时。
     *
     * <p>比主对话的 {@code timeoutSeconds=60} 小一个数量级：这三条是**锦上添花**，
     * 为了它让 SSE 连接多挂 60 秒完全是负收益。4 秒拿不到就当没有。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final AiChatClient aiChatClient;
    private final AiUsageRecorder usageRecorder;
    private final CrisisDetector crisisDetector;
    private final ObjectMapper objectMapper;

    /**
     * 生成三条追问建议。
     *
     * @param sessionId 仅用于用量落表
     * @param userId    仅用于用量落表
     * @param userText  本轮居民原话
     * @param aiText    本轮助手已发出的正文（含被安全护栏改写后的版本）
     * @return 0~3 条；任何异常都返回空 Mono，不抛错
     */
    public Mono<List<String>> suggest(Long sessionId, Long userId, String userText, String aiText) {
        long startedAt = System.currentTimeMillis();
        List<ChatMessage> messages = List.of(
                ChatMessage.system(PromptAssets.FOLLOWUP_SYSTEM),
                ChatMessage.user(buildInput(userText, aiText)));

        return aiChatClient.complete(messages)
                .timeout(TIMEOUT)
                .map(completion -> {
                    // 先记用量再解析：解析失败也照样花了 token，不能因为「结果没用上」就不记账
                    usageRecorder.record(sessionId, userId, AiUsageRecorder.KIND_FOLLOWUP,
                            completion.usage(), null,
                            (int) (System.currentTimeMillis() - startedAt), null);
                    return normalize(completion.content());
                })
                .filter(list -> !list.isEmpty())
                .onErrorResume(e -> {
                    log.warn("[AI 追问] 生成失败，本期不发 followups session={} 原因={}",
                            sessionId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** 组装用户消息：两个小标题 + 截断后的正文 */
    private String buildInput(String userText, String aiText) {
        return "【居民本轮说的】\n" + clip(userText, USER_CHARS)
                + "\n\n【助手本轮的回答】\n" + clip(aiText, AI_CHARS);
    }

    /**
     * 解析 + 过滤 + 截断。
     *
     * <p>顺序不能换：先解析（拿不到数组就整批作废）→ 再去空/去重 → 再过安全过滤
     * → 最后截断条数。若先截三条再过滤，可能因为其中一条被拦而只剩两条，
     * 而第三条本来能用的却被提前丢掉了。
     */
    List<String> normalize(String raw) {
        List<String> parsed = parseArray(raw);
        if (parsed.isEmpty()) {
            return List.of();
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String item : parsed) {
            String t = item == null ? "" : item.replaceAll("\\s+", " ").trim();
            // 去掉模型偶尔加上的序号与引号（"1. 我该……" / "「我该……」"）
            t = t.replaceFirst("^[0-9]+[.、)）]\\s*", "").replaceAll("^[\"'“”「」『』]", "");
            t = t.replaceAll("[\"'“”「」『』]$", "");
            if (t.length() < MIN_ITEM_CHARS) {
                continue;
            }
            // 安全兜底：任一条命中「危机词 + 可操作细节」就整批丢弃。
            // 不做逐条剔除 —— 剩下两条也不知道模型当时在想什么，整批更安全。
            if (crisisDetector.harmful(t)) {
                log.warn("[AI 追问] 生成结果命中安全词表，整批丢弃：{}", t);
                return List.of();
            }
            unique.add(clip(t, MAX_ITEM_CHARS));
            if (unique.size() >= MAX_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 从模型输出里取出 JSON 数组。
     *
     * <p>容忍三种常见包装：纯数组、```json 代码块、数组前后加了解释文字。
     * 都取不到就返回空 —— <b>不猜分隔符</b>，因为猜错会把半句话当成一条建议填进输入框。
     */
    private List<String> parseArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("[AI 追问] 输出里没有 JSON 数组，整批丢弃：{}", clip(text, 120));
            return List.of();
        }
        String json = text.substring(start, end + 1);
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("[AI 追问] JSON 解析失败，整批丢弃：{}", e.getMessage());
            return List.of();
        }
    }

    private String clip(String text, int max) {
        String t = text == null ? "" : text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
