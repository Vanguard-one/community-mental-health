package com.cmh.psycare.ai;

import lombok.Getter;

/**
 * LLM 调用错误归类（计划 5.8 的「第一道防线」）。
 *
 * <p>为什么需要一套自己的错误码，而不是把上游 HTTP 状态直接透给前端：
 * <ol>
 *   <li><b>同一状态码有多种语义</b>。DeepSeek 的 400 既可能是「模型名不存在」，
 *       也可能是「上下文超长」—— 前者重试一万次都没用，后者压缩历史就能过。
 *       不看 body 一律按「请求错」处理，会把「模型名写错」当成「上下文超长」去压历史，
 *       越压越错，且日志里看不出真因。</li>
 *   <li><b>上游返回的 code 字段没有区分度</b>。实测 401 与 400 的 body 里
 *       {@code code} 都是 {@code invalid_request_error}，只能靠 HTTP 状态 + message 组合判断。</li>
 *   <li><b>是否该重试是业务决策</b>，不该散落在各处 if 里。挂在枚举上，调用方只看
 *       {@link #isRetryable()}。</li>
 * </ol>
 *
 * <p>{@link #getUserMsg()} 是给前端展示的中文文案；技术细节走日志，不往用户脸上糊。
 */
@Getter
public enum LlmErrorCode {

    /** 401/403：Key 错或权限不足。**重试必然还失败**，只会拖延降级时间 */
    LLM_KEY_INVALID(false, "模型服务鉴权失败，请检查 API Key"),

    /** 429：限流。指数退避后重试一次是有效的 */
    LLM_RATE_LIMITED(true, "模型服务繁忙，请稍后重试"),

    /** 400：请求被拒。最常见成因是上下文超长 —— 压缩历史后重试一次值得 */
    LLM_BAD_REQUEST(true, "对话内容过长，已自动精简后重试"),

    /** 400 且 body 指明模型名无效。**旧别名退役就落在这里**，重试无用 */
    LLM_MODEL_NOT_FOUND(false, "模型配置无效或已退役，请检查配置"),

    /** 超时（首帧超时或整体超时） */
    LLM_TIMEOUT(true, "模型服务响应超时"),

    /** 5xx / 连接失败 */
    LLM_UPSTREAM_ERROR(true, "模型服务暂时不可用"),

    /**
     * 上游「正常」结束但没有正文。
     * <p>两种来源：① {@code max_tokens} 被思考过程吃光（推理模型的经典坑）；
     * ② 整体超时被 {@code take()} 截断后一个字都没吐出。
     * 重试同一个请求通常得到同样结果，故不重试，直接给兜底话术。
     */
    LLM_NO_CONTENT(false, "模型未产出回复内容"),

    /** 兜底：配置路径错（404）、余额不足（402）等 */
    LLM_UNKNOWN(false, "模型服务异常");

    private final boolean retryable;
    private final String userMsg;

    LlmErrorCode(boolean retryable, String userMsg) {
        this.retryable = retryable;
        this.userMsg = userMsg;
    }
}
