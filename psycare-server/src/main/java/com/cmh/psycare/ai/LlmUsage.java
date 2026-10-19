package com.cmh.psycare.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次 LLM 调用的 token 用量。
 *
 * <p>{@code cachedTokens} 必须单独记 —— 缓存命中的输入 token 价格只有未命中的约 1/10，
 * 不拆开记，成本报表会把「提示词前缀是否稳定」这个唯一可量化的降本指标抹平。
 *
 * <p>{@code reasoningTokens} 是本项目的重点观测项：推理模型的思考 token 按输出价计费，
 * 且完全不可见。不记它，就会看到「用户只问了 20 字，账单里 completion 却有 3000 token」而找不到原因。
 */
public record LlmUsage(Integer promptTokens, Integer completionTokens,
                       Integer cachedTokens, Integer reasoningTokens) {

    public static final LlmUsage EMPTY = new LlmUsage(0, 0, 0, 0);

    public int totalTokens() {
        return nz(promptTokens) + nz(completionTokens);
    }

    /**
     * 从响应体的 {@code usage} 节点解析。
     *
     * <p>缓存字段做了双读：DeepSeek 放在顶层 {@code prompt_cache_hit_tokens}，
     * OpenAI 放在 {@code prompt_tokens_details.cached_tokens}。两者都读，
     * 换供应商时日志不会突然出现一片 0。
     */
    public static LlmUsage from(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return EMPTY;
        }
        int cached = intOf(usage, "prompt_cache_hit_tokens");
        if (cached == 0) {
            cached = intOf(usage.path("prompt_tokens_details"), "cached_tokens");
        }
        return new LlmUsage(
                intOf(usage, "prompt_tokens"),
                intOf(usage, "completion_tokens"),
                cached,
                intOf(usage.path("completion_tokens_details"), "reasoning_tokens"));
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isNumber() ? v.asInt() : 0;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
