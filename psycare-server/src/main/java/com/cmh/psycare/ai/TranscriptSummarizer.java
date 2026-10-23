package com.cmh.psycare.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.entity.ConsultMessage;
import com.cmh.psycare.entity.ConsultSession;
import com.cmh.psycare.mapper.ConsultMessageMapper;
import com.cmh.psycare.mapper.ConsultSessionMapper;
import com.cmh.psycare.service.AiUsageRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 会话摘要压缩：把「已经滚出上下文窗口的早远对话」压成一段摘要，写进
 * {@code consult_session.summary}（字段本来就有，零 DDL）。
 *
 * <h3>为什么必须异步</h3>
 * <p>摘要要额外调一次模型。若同步做，用户会在每 N 轮遇到一次明显变长的等待 ——
 * 而这个等待对当前这轮回复毫无帮助（摘要只影响<b>下一轮</b>的上下文）。
 * 所以这里提交到独立线程池后立即返回，失败也只告警：
 * 摘要没生成，下一轮靠 token 预算照样能工作，只是上下文短一些。
 *
 * <h3>为什么用独立线程池而不是 commonPool</h3>
 * <p>{@code ForkJoinPool.commonPool()} 是 JVM 全局共享的（并行流、CompletableFuture
 * 的默认执行器都用它）。把「可能阻塞几十秒的 HTTP 调用」放进去，
 * 会拖慢所有依赖它的代码。这里用单线程 + 有界队列 + 守护线程：
 * 同一时刻只压一个会话，队列满了就直接丢弃（丢弃是安全的，见 {@link #maybeCompress}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptSummarizer {

    /** 最近这么多条消息不参与摘要 —— 它们本来就会原样进上下文，压进去是重复劳动 */
    private static final int KEEP_RECENT = 6;
    /** 单条消息喂给摘要模型时的字符上限：摘要不需要原文全文 */
    private static final int PER_MESSAGE_CHARS = 500;
    /** 摘要正文上限，防止模型越写越长（数据库 TEXT 存得下，但上下文里它是每轮都带的） */
    private static final int SUMMARY_MAX_CHARS = 1000;

    private final ConsultMessageMapper messageMapper;
    private final ConsultSessionMapper sessionMapper;
    private final AiChatClient aiChatClient;
    private final LlmProperties props;
    /**
     * 摘要用量的落表入口。
     *
     * <p>⚠️ 这是 {@code ai} 包对 {@code service} 包的一次反向依赖（其余 ai 类都不依赖
     * service）。接受它的理由：摘要是在这里发起的调用，用量只有这里拿得到；
     * 若把记录职责上推给调用方（{@code AiConsultService}），异步任务的结果就永远回不到
     * 已经结束的请求里去。{@code AiUsageRecorder} 只依赖 mapper 与配置，无循环依赖。
     */
    private final AiUsageRecorder usageRecorder;

    /** 同一会话同一时刻只允许一个压缩任务，避免并发把同一批消息压两遍 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "ai-summary");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    /**
     * 判断是否该压缩，该压就异步提交，**立即返回**。
     *
     * <p>触发条件用「消息总数越过阈值后每隔 {@code summary-step-messages} 条压一次」表达。
     * 这个判断只用 {@code totalMessages} 一个数字，因此不需要额外存「上次压到哪儿」的水位 ——
     * 少一个状态就少一处可能不一致的地方。
     *
     * @param session      会话（读它的 {@code summary} 做增量摘要）
     * @param totalMessages 当前会话消息总数（含刚落的这条）
     */
    public void maybeCompress(ConsultSession session, int totalMessages) {
        int trigger = props.getSummaryTriggerMessages() == null ? 24 : props.getSummaryTriggerMessages();
        int step = props.getSummaryStepMessages() == null ? 8 : props.getSummaryStepMessages();
        if (step <= 0 || totalMessages < trigger || (totalMessages - trigger) % step != 0) {
            return;
        }
        Long sessionId = session.getId();
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            pool.submit(() -> {
                try {
                    compress(session);
                } catch (Exception e) {
                    // 摘要失败不影响对话本身：下一轮上下文短一点，但一切照常
                    log.warn("[AI 摘要] 压缩失败（不影响对话）session={} 原因={}", sessionId, e.getMessage());
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满：直接放弃本次压缩。下次消息数再到步长点会重新触发
            inFlight.remove(sessionId);
            log.warn("[AI 摘要] 任务队列已满，跳过本次压缩 session={}", sessionId);
        }
    }

    /** 真正干活：读消息 → 调模型 → 只更新 summary 列 */
    void compress(ConsultSession session) {
        Long sessionId = session.getId();
        long startedAt = System.currentTimeMillis();
        List<ConsultMessage> all = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .orderByAsc(ConsultMessage::getId));
        int olderCount = all.size() - KEEP_RECENT;
        if (olderCount <= 0) {
            return;
        }

        StringBuilder input = new StringBuilder();
        String previous = session.getSummary();
        if (previous != null && !previous.isBlank()) {
            input.append("【已有摘要】\n").append(previous.trim()).append("\n\n");
        }
        input.append("【需要纳入的对话】\n");
        for (ConsultMessage m : all.subList(0, olderCount)) {
            String who = "AI".equals(m.getSender()) ? "助手" : "居民";
            input.append(who).append('：').append(clip(m.getContent(), PER_MESSAGE_CHARS)).append('\n');
        }

        LlmCompletion completion = aiChatClient.complete(List.of(
                ChatMessage.system(PromptAssets.SUMMARY_SYSTEM),
                ChatMessage.user(input.toString()))).block();
        String summary = completion == null ? null : completion.content();
        if (summary == null || summary.isBlank()) {
            log.warn("[AI 摘要] 模型未返回内容，跳过写入 session={}", sessionId);
            return;
        }
        // 摘要同样是计费的模型调用，用量必须落表（kind=SUMMARY）。
        // 首帧对非流式没有意义，传 null；总耗时按整段 HTTP 往返记。
        usageRecorder.record(sessionId, session.getResidentId(), "SUMMARY",
                completion.usage(), null, (int) (System.currentTimeMillis() - startedAt), null);

        // 只更新 summary 列：用 updateById 会把整个实体写回去，
        // 期间若会话已被关闭（status=CLOSED），这次写入会把它改回 OPEN —— 静默的状态回滚
        sessionMapper.update(null, new LambdaUpdateWrapper<ConsultSession>()
                .eq(ConsultSession::getId, sessionId)
                .set(ConsultSession::getSummary, clip(summary.trim(), SUMMARY_MAX_CHARS)));
        log.info("[AI 摘要] 已更新 session={} 覆盖前 {} 条消息 摘要={}字",
                sessionId, olderCount, summary.trim().length());
    }

    private String clip(String text, int max) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
