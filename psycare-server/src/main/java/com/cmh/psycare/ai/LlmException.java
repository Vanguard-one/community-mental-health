package com.cmh.psycare.ai;

import lombok.Getter;

/**
 * LLM 调用异常。携带归类后的错误码，供重试判定、降级话术与 {@code ai_usage_log.error_code} 使用。
 *
 * <p>继承 {@code RuntimeException} 而非受检异常：整条链路是 Reactor 的，受检异常在
 * 操作符里既不能声明也拿不到，强行包装只会到处 {@code try/catch} 把错误吞掉。
 */
@Getter
public class LlmException extends RuntimeException {

    private final LlmErrorCode code;

    /** 上游 HTTP 状态；超时/连接失败等无状态码的场景为 null */
    private final Integer upstreamStatus;

    public LlmException(LlmErrorCode code, String message) {
        this(code, null, message);
    }

    public LlmException(LlmErrorCode code, Integer upstreamStatus, String message) {
        super(message);
        this.code = code;
        this.upstreamStatus = upstreamStatus;
    }

    public boolean isRetryable() {
        return code.isRetryable();
    }
}
