package com.cmh.psycare.ai.rag;

/**
 * Milvus 不可用（连不上 / 集合建不出来 / RPC 失败）。
 *
 * <h3>为什么单独一个异常类型，而不是直接抛 SDK 的 {@code MilvusClientException}</h3>
 * <p>调用方（{@code KnowledgeIndexService} 与 Phase 4 的检索链路）需要做一个明确的二分：
 * <b>「Milvus 的问题」一律降级，不能阻断业务</b>（计划 5.6：RAG 是增强不是依赖，
 * Milvus 挂了不该让聊天也挂）；而「代码 bug / 数据问题的异常」必须冒出去。
 * 如果两件事共用一个异常类型，catch 块就只能写得含糊，最终把所有异常都吞掉。
 */
public class MilvusUnavailableException extends RuntimeException {

    public MilvusUnavailableException(String message) {
        super(message);
    }

    public MilvusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
