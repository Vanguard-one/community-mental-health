package com.cmh.psycare.ai;

/**
 * 流式响应的一帧。
 *
 * <h3>为什么要把 {@code reasoning} 单独拎出来，而不是直接丢掉</h3>
 * <p>DeepSeek-V4 是推理模型，流里的帧顺序是：
 * <pre>
 *   delta.reasoning_content（思考）… → delta.content（正文）… → finish_reason
 * </pre>
 * 业务上<b>不把思考过程透给用户</b>（心理问诊场景里，让居民看到「模型在分析他是不是抑郁」
 * 既冒犯也不安全），但如果客户端把它当垃圾直接丢弃，就会丢掉两件重要的事：
 * <ol>
 *   <li><b>首帧延迟的真相</b>。用户看到的「半天不出字」其实是思考阶段在跑；
 *       不区分两者，{@code first_token_ms} 这个指标就无从解释。</li>
 *   <li><b>空回复的成因</b>。{@code max_tokens} 被思考吃光时正文为空，
 *       只看正文会以为是模型故障，看到 reasoning 长度才知道是预算问题。</li>
 * </ol>
 * 所以这里保留字段、由上层决定「不透出但计数」。
 */
public record LlmChunk(String content, String reasoning, String finishReason, LlmUsage usage) {

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isEmpty();
    }

    public boolean hasUsage() {
        return usage != null;
    }

    /** 仅用于日志与调试的一行摘要 */
    public String describe() {
        return "content=" + length(content) + " reasoning=" + length(reasoning)
                + " finish=" + finishReason + (hasUsage() ? " usage=" + usage.totalTokens() : "");
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }
}
