package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 调用用量日志。
 *
 * <p>写入方：{@code AiUsageRecorder}，由 {@code AiConsultService} 在流终止时调用。
 *
 * <p>与 {@code consult_message.finish_reason} 的分工：那边记「这条消息本身完不完整」，
 * 这边记「这次调用怎么样（token/延迟/错误码）」。两者不重复也不可互相替代 ——
 * 一次调用可能产出多条消息，也可能因为进程被 kill 而根本没产生调用日志。
 *
 * <p>本表<b>只增不改不删</b>，无逻辑删除列：用量数据是成本与稳定性的唯一证据，
 * 让它可被软删，等于让报表可以被悄悄改小。
 */
@Data
@TableName("ai_usage_log")
public class AiUsageLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long userId;

    private String model;

    /** 思考档位 low/high/max；null 表示当时用的是服务端默认 */
    private String effort;

    private Integer promptTokens;

    /** 缓存命中的输入 token：单价约为未命中的 1/10，是唯一可量化的降本指标 */
    private Integer cachedTokens;

    private Integer completionTokens;

    /** 思考 token：推理模型按输出价计费且对用户不可见，必须单独记 */
    private Integer reasoningTokens;

    /** 首帧延迟（毫秒）；null 表示一帧都没收到 */
    private Integer firstTokenMs;

    private Integer totalMs;

    /** RAG 命中的知识块数；Phase 4 起才有意义，当前恒为 0 */
    private Integer retrievedCount;

    /** 归类后的错误码（{@code LlmErrorCode.name()}）；null 表示成功 */
    private String errorCode;

    /** 脱敏截断后的错误摘要 */
    private String errorDetail;

    private LocalDateTime createdAt;
}
