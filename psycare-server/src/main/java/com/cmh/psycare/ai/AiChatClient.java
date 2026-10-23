package com.cmh.psycare.ai;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * AI 对话客户端抽象。
 *
 * <p>业务层（{@code AiConsultService}）只依赖本接口。
 *
 * <h3>返回 {@link LlmChunk} 而不是 {@code String}</h3>
 * <p>改造前这里返回 {@code Flux<String>}，即「只有正文文本」。这丢掉三样东西，
 * 而它们分别对应三个必需的诉求：
 * <table border="1">
 *   <caption>为什么必须升级返回值</caption>
 *   <tr><th>丢掉的信息</th><th>导致的后果</th></tr>
 *   <tr><td>{@code reasoning_content}</td><td>首帧延迟与空回复的成因无法解释（见 {@link LlmChunk}）</td></tr>
 *   <tr><td>{@code finish_reason}</td><td>无法区分「模型说完了」与「被整体超时截断」</td></tr>
 *   <tr><td>{@code usage}</td><td>token 成本与缓存命中率无从统计</td></tr>
 * </table>
 *
 * <h3>为 Phase 5（工具调用）预留的扩展位</h3>
 * <p>接入 function calling 时，工具调用帧（{@code delta.tool_calls}）也属于「流里的帧」，
 * 会作为新的字段加进 {@code LlmChunk}，而不是新开一个方法 —— 这样
 * 「工具调用与正文共用一条流、共用同一套重试与超时」这个性质得以保持。
 */
public interface AiChatClient {

    /**
     * 流式对话。
     *
     * <h3>为什么要逐次传「要不要思考」而不是读全局配置</h3>
     * <p>同一个进程里，不同会话、甚至同一会话的不同轮次，对延迟与安全的要求是不一样的
     * （判据见 {@link ThinkingPolicy}）。若把开关做成客户端内的成员状态，就变成
     * 「后一个请求可以改掉前一个请求正在用的值」—— 那种 bug 在并发下只在特定时刻出现，
     * 且表现为「偶尔很快、偶尔很慢」，几乎无法复现。
     * 参数化之后，决策只发生在调用点，客户端保持无状态。
     *
     * @param messages        含 system + 历史 + 当前 user 消息
     * @param thinkingEnabled false 时要求上游切到非思考模式（首帧可快 2.5~5 倍，见
     *                        {@link LlmProperties#getThinking()}）。实现方应同时停发
     *                        {@code effort}，避免两个开关在请求体里语义打架
     * @return 逐帧的 {@link LlmChunk}；上游异常统一转为 {@link LlmException}
     */
    Flux<LlmChunk> chatStream(List<ChatMessage> messages, boolean thinkingEnabled);

    /**
     * 非流式补全：一次拿到完整正文。
     *
     * <p>用于「后台的、用户看不见的」模型调用，目前只有一处：{@code TranscriptSummarizer}
     * 的会话摘要压缩。这类调用没人盯着逐字输出，也不该进 SSE 事件与对话落库。
     *
     * <p>为什么不直接复用 {@link #chatStream} 再自己拼字符串：那会把
     * 「逐帧重试闸门（已下发帧数）」「首帧超时判定」「落库缓冲区」这些
     * <b>专为流式设计</b>的机制全拖进来，而摘要调用一个都用不上；
     * 而且流式方法一帧都不能重放，摘要这种幂等操作反而更适合失败重试。
     *
     * <p><b>本方法固定以「非思考模式」调用</b>：摘要是对已有对话的压缩复述，
     * 不需要推理，而它是要花时间也要花钱的额外请求。这与上面一条一脉相承 ——
     * 「没人等的调用」就该用最快的方式跑完。
     *
     * <p><b>返回值带上 {@link LlmUsage}</b>：这类后台调用同样计费，
     * 不给用量就无法回答「一轮对话之外还花了多少」。调用方负责把它写进
     * {@code ai_usage_log}（{@code kind} 区分 CHAT / SUMMARY / FOLLOWUP）。
     */
    Mono<LlmCompletion> complete(List<ChatMessage> messages);

    /**
     * 连通性与配置探活（不应产生 token 费用）。
     *
     * @return 探活结果；**实现必须自己吞掉异常**，不得向外抛
     */
    LlmProbeResult probe();
}
