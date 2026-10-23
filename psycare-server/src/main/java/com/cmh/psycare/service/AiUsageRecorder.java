package com.cmh.psycare.service;

import com.cmh.psycare.ai.LlmErrorCode;
import com.cmh.psycare.ai.LlmException;
import com.cmh.psycare.ai.LlmProperties;
import com.cmh.psycare.ai.LlmUsage;
import com.cmh.psycare.entity.AiUsageLog;
import com.cmh.psycare.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * LLM 用量落库。
 *
 * <h3>唯一职责是「绝不影响主链路」</h3>
 * <p>写入包在 {@code try/catch} 里，失败只打一行 WARN。理由是量级不对等：
 * 一次用量日志写失败只丢一条统计（后续还能从别处补），
 * 而让它把异常抛回对话链路，会让用户看到「AI 不回话」—— 观测数据换来一次故障，是亏的。
 * 这与「MySQL 是事实源、Milvus 是可重建索引层」是同一条原则：
 * <b>旁路组件失败不得拖垮主链路</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorder {

    private static final int DETAIL_MAX = 500;

    /** 主对话调用（SSE 流式，用户可见） */
    public static final String KIND_CHAT = "CHAT";
    /** 会话摘要压缩（后台、非流式） */
    public static final String KIND_SUMMARY = "SUMMARY";
    /** 追问建议生成（后台、非流式） */
    public static final String KIND_FOLLOWUP = "FOLLOWUP";

    private final AiUsageLogMapper usageLogMapper;
    private final LlmProperties props;

    /**
     * 记一次调用（默认按主对话记，{@code kind=CHAT}）。
     *
     * @param usage        上游返回的用量；上游没回 usage 时传 {@link LlmUsage#EMPTY}
     * @param firstTokenMs 首帧延迟；null 表示一帧都没收到（此时 {@code errorCode} 通常非空）
     * @param error        归类后的错误；成功时为 null
     */
    public void record(Long sessionId, Long userId, LlmUsage usage,
                       Integer firstTokenMs, int totalMs, LlmException error) {
        record(sessionId, userId, KIND_CHAT, usage, firstTokenMs, totalMs, error);
    }

    /**
     * 记一次调用，显式指定调用类型。
     *
     * <h3>为什么需要 kind</h3>
     * <p>主对话之外还有两类<b>同样计费</b>的模型调用：会话摘要（SUMMARY）与
     * 追问建议（FOLLOWUP）。它们原先要么不记录（摘要），要么把正文之外的
     * usage 丢掉（{@code complete()} 的旧实现）。没有这一列，
     * 「一轮对话的真实成本」永远只能算到主调用那一段，而实际账单里
     * 后台调用的占比并不小。
     *
     * @param kind CHAT / SUMMARY / FOLLOWUP；为空时按 CHAT 记，避免脏数据
     */
    public void record(Long sessionId, Long userId, String kind, LlmUsage usage,
                       Integer firstTokenMs, int totalMs, LlmException error) {
        try {
            AiUsageLog row = new AiUsageLog();
            row.setSessionId(sessionId);
            row.setUserId(userId);
            row.setKind(kind == null || kind.isBlank() ? KIND_CHAT : kind);
            row.setModel(props.getModel());
            row.setEffort(props.getEffort());
            row.setPromptTokens(usage == null ? 0 : safe(usage.promptTokens()));
            row.setCachedTokens(usage == null ? 0 : safe(usage.cachedTokens()));
            row.setCompletionTokens(usage == null ? 0 : safe(usage.completionTokens()));
            row.setReasoningTokens(usage == null ? 0 : safe(usage.reasoningTokens()));
            row.setFirstTokenMs(firstTokenMs);
            row.setTotalMs(totalMs);
            row.setRetrievedCount(0);
            if (error != null) {
                LlmErrorCode code = error.getCode();
                row.setErrorCode(code == null ? LlmErrorCode.LLM_UNKNOWN.name() : code.name());
                row.setErrorDetail(truncate(error.getMessage()));
            }
            row.setCreatedAt(LocalDateTime.now());
            usageLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[LLM 用量] 写入 ai_usage_log 失败（不影响对话）：{}", e.getMessage());
        }
    }

    /** 无需上游调用的场景（如危机词短路）不记用量：没有产生任何 token 费用 */
    private int safe(Integer v) {
        return v == null ? 0 : v;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= DETAIL_MAX ? oneLine : oneLine.substring(0, DETAIL_MAX);
    }
}
