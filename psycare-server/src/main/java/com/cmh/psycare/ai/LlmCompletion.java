package com.cmh.psycare.ai;

/**
 * 非流式补全的结果：正文 + 上游用量。
 *
 * <h3>为什么从 {@code Mono<String>} 升级成这个 record</h3>
 * <p>原先 {@code complete()} 只返回正文，{@code usage} 在
 * {@code OpenAiCompatClient} 里被 {@code .map(this::extractContent)} 顺手丢掉了。
 * 后果是「所有非流式的后台调用（会话摘要、追问建议）花了多少 token」
 * <b>完全不可观测</b> —— 主对话有 {@code ai_usage_log} 一行一条，
 * 这些后台调用一条记录都没有，账单上却照样计费。
 *
 * <p>加这一个字段的成本只有一次 reshape，收益是「后台调用的成本终于能和主链路放在同一张表里」。
 * 不做成「正文 + 一个 usage 的 getter」的可变类，是因为它只是两层之间的搬运结构，
 * 没有需要修改的状态。
 *
 * @param content 正文；上游未返回可解析内容时为空串（调用方自行判空）
 * @param usage   上游用量；上游没回 {@code usage} 时为 {@link LlmUsage#EMPTY}
 */
public record LlmCompletion(String content, LlmUsage usage) {

    /** 上游没给 usage 时用它兜底，避免调用方到处写 null 判断 */
    public static LlmCompletion of(String content) {
        return new LlmCompletion(content == null ? "" : content, LlmUsage.EMPTY);
    }
}
