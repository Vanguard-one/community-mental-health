package com.cmh.psycare.ai.rag;

import com.cmh.psycare.ai.LlmErrorCode;
import com.cmh.psycare.ai.LlmException;
import com.cmh.psycare.ai.LlmProbeResult;

import java.util.List;

/**
 * 文本向量化客户端抽象。
 *
 * <h3>为什么从第一天就是接口（计划 R2）</h3>
 * <p>「DeepSeek 没有 embeddings 端点」不是临时的选型偏好，而是<b>确定的架构事实</b> ——
 * 对话与向量化必然由两家供应商提供。既然如此，业务层（索引服务 / 在线检索）
 * 就不该认识任何一家的 SDK 或 HTTP 形状。接口在，换供应商才是改配置；
 * 接口不在，换供应商就是改业务代码。
 *
 * <h3>为什么错误码复用 {@link LlmErrorCode} 而不是另建一套</h3>
 * <p>这些错误码描述的是<b>「OpenAI 兼容上游返回了什么」</b>，而不是「DeepSeek 怎么了」：
 * 401=鉴权、429=限流、400=请求被拒、5xx=上游故障。两家供应商在这一点上完全同构，
 * 另建 {@code EmbeddingErrorCode} 只会得到一份逐条对应的枚举，以及
 * 「同一个 401 在两处被翻译成不同文案」的长期隐患。
 *
 * <h3>为什么没有流式方法</h3>
 * <p>向量化没有「逐字产出」这个形态 —— 请求发出后要么回来一整批向量，要么失败。
 * 与对话共用流式抽象，只会把「逐帧重试闸门」「首帧超时」这些流式专有机制
 * 拖进一条完全用不上它们的路径。
 */
public interface EmbeddingClient {

    /**
     * 单条文本向量化。
     *
     * @throws LlmException 失败时抛出已归类的异常
     */
    float[] embed(String text);

    /**
     * 批量向量化。内部按 {@code psycare.embedding.batch-size} 自动切片。
     *
     * <p>实现约定（调用方可依赖）：
     * <ol>
     *   <li>返回的 {@link EmbeddingBatch#vectors()} 与入参<b>等长同序</b>；</li>
     *   <li>整批重试耗尽后**降级为逐条调用**，把毒数据隔离出来；</li>
     *   <li>单条永久失败只记下标，**不抛异常、不阻断其余条目** —— 这是计划 5.4
     *       阶段四明确要求的「一条坏数据不该让整篇文档索引失败」。</li>
     * </ol>
     * 因此这个方法<b>只在「整批 + 全部逐条都失败」时才可能抛</b>（例如 Key 失效、
     * 网络不通这类与单条内容无关的原因）。实现须自行判断哪些异常属于这一类。
     */
    EmbeddingBatch embedBatch(List<String> texts);

    /**
     * 连通性与配置探活（只调模型列表接口，**不产生 token 费用**）。
     *
     * <p>实现必须自己吞掉异常，不得向外抛 —— 探活失败只应导致日志告警，
     * 不应让应用起不来。
     */
    LlmProbeResult probe();
}
