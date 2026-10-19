package com.cmh.psycare.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 上游异常 → {@link LlmErrorCode} 的归类器。
 *
 * <h3>400 的两种语义必须靠 body 区分</h3>
 * <p>实测两条真实的 400 响应体（2026-09-29）：
 * <pre>
 * 模型名失效：{"error":{"message":"The supported API model names are
 *             deepseek-flash, deepseek-v4-pro, but you passed deepseek-chat-retired-probe."}}
 * 上下文超长：{"error":{"message":"This model's maximum context length is 65536 tokens ..."}}
 * </pre>
 * 两者都含「model」字样，所以判据必须是
 * <b>同时命中「模型名类措辞」</b>（supported api model / not exist / unknown model），
 * 只判「含 model」会把上下文超长误判成模型名失效。
 *
 * <p>反过来也危险：若把模型名失效误判成上下文超长，重试时会把历史压掉一半 ——
 * 用户会发现自己刚说过的话被 AI「忘掉」了，而这是为了一个根本不会成功的重试付出的代价。
 */
@Slf4j
@Component
public class LlmErrorMapper {

    /** 模型名类措辞（全部小写后做包含判断） */
    private static final String[] MODEL_NAME_MARKERS = {
            "supported api model",
            "not exist",
            "does not exist",
            "unknown model",
            "invalid model",
            "model not found",
    };

    /** 上下文超长类措辞，仅用于日志补充说明 */
    private static final String[] CONTEXT_MARKERS = {
            "context length",
            "context_length",
            "maximum context",
            "too long",
            "max_tokens",
    };

    /** 归类任意上游异常。已是 {@link LlmException} 则原样返回，保证重试链路幂等 */
    public LlmException map(Throwable e) {
        if (e instanceof LlmException le) {
            return le;
        }
        if (e instanceof WebClientResponseException w) {
            return fromStatus(w.getStatusCode().value(), w.getResponseBodyAsString());
        }
        if (e instanceof TimeoutException || hasCause(e, TimeoutException.class)) {
            return new LlmException(LlmErrorCode.LLM_TIMEOUT, "上游响应超时：" + rootMessage(e));
        }
        if (e instanceof WebClientRequestException) {
            return new LlmException(LlmErrorCode.LLM_UPSTREAM_ERROR,
                    "无法连接上游（检查 base-url 与网络/代理）：" + rootMessage(e));
        }
        return new LlmException(LlmErrorCode.LLM_UNKNOWN, rootMessage(e));
    }

    /** 按 HTTP 状态 + 响应体归类 */
    public LlmException fromStatus(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        String shortBody = abbreviate(body);

        return switch (status) {
            case 400 -> {
                if (looksLikeModelNameProblem(lower)) {
                    yield new LlmException(LlmErrorCode.LLM_MODEL_NOT_FOUND, 400,
                            "模型名无效或已退役：" + shortBody);
                }
                if (looksLikeContextProblem(lower)) {
                    yield new LlmException(LlmErrorCode.LLM_BAD_REQUEST, 400,
                            "上下文超长：" + shortBody);
                }
                yield new LlmException(LlmErrorCode.LLM_BAD_REQUEST, 400, "请求被拒：" + shortBody);
            }
            case 401, 403 -> new LlmException(LlmErrorCode.LLM_KEY_INVALID, status,
                    "鉴权失败：" + shortBody);
            case 408 -> new LlmException(LlmErrorCode.LLM_TIMEOUT, 408, "上游报请求超时");
            case 429 -> new LlmException(LlmErrorCode.LLM_RATE_LIMITED, 429, "上游限流：" + shortBody);
            default -> {
                if (status >= 500) {
                    yield new LlmException(LlmErrorCode.LLM_UPSTREAM_ERROR, status, "上游 " + status + "：" + shortBody);
                }
                yield new LlmException(LlmErrorCode.LLM_UNKNOWN, status, "上游 " + status + "：" + shortBody);
            }
        };
    }

    private boolean looksLikeModelNameProblem(String lowerBody) {
        for (String marker : MODEL_NAME_MARKERS) {
            if (lowerBody.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeContextProblem(String lowerBody) {
        for (String marker : CONTEXT_MARKERS) {
            if (lowerBody.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        Throwable cur = e.getCause();
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (type.isInstance(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && guard++ < 10) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "(空响应体)";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
