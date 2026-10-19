package com.cmh.psycare.ai.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 在线检索（RAG 的「查」这一侧）配置。
 *
 * <h3>为什么和 {@link MilvusProperties} 分开</h3>
 * <p>{@code psycare.milvus.*} 描述的是「连哪个库、建什么结构」——属于<b>索引层</b>，
 * 换 Milvus 实例时才动。本类描述的是「怎么查、查到什么程度算数」——属于<b>检索策略</b>，
 * 调阈值、开缓存、改改写开关都会动它。两者变化的理由完全不同，
 * 混在一起会出现「只想调个阈值，却要在一屏 Milvus 连接参数里找」。
 *
 * <h3>阈值为什么必须是「稠密相似度」而不是 RRF 融合分</h3>
 * <p>RRF 融合的是<b>排名</b>（{@code 1/(k+rank)}），它的量级取决于「召回了多少路、
 * 每路取了多少条」，与「这段资料到底像不像」没有映射关系 —— 拿它跟 0.5 比是无意义的。
 * 能跟阈值比的只有稠密那一路的余弦相似度（见 {@link MilvusHit#denseScore()}）。
 * 稀疏路召回、稠密路没召回时该值为 null，此时<b>不能</b>当成 0 直接丢弃：
 * 精确词命中（量表编号、药名）恰恰是稀疏路的强项，一律丢掉等于把混合检索降级成纯向量。
 */
@Data
@Component
@ConfigurationProperties(prefix = "psycare.retrieval")
public class RetrievalProperties {

    /** 总开关。关掉后 L6 恒走「无资料」分支，对话行为与 Phase 3 完全一致 */
    private boolean enabled = true;

    /**
     * 稠密相似度下限。低于它的命中会被丢弃；**稀疏路召回的条目不受此限**（理由见类注释）。
     *
     * <p>初始给 0.5 是一个保守的起点：中文 embedding 的余弦相似度普遍偏高，
     * 0.5 通常只挡掉明显无关的段落。真实值要用 {@code eval_retrieval.py}
     * 在自建评测集上做分离点分析后回填 —— 不要凭感觉调。
     */
    private float minDenseScore = 0.5f;

    /**
     * 回表取父块后，拼进提示词的资料条数上限。
     *
     * <p>这是<b>第二道</b>截断：Milvus 侧已按 {@code psycare.milvus.final-top-k}（默认 5）截过一次。
     * 但一条父块可能 1400 字，5 条就是 7000 字 ≈ 7000 token，会把历史对话挤没。
     * 所以这里按「实际拼进提示词」的条数再收一道。
     */
    private int maxInjected = 3;

    /**
     * 注入上下文的资料总 token 预算。超出时按融合分从高到低丢弃整条
     * （不截断单条 —— 半截段落比没有更容易误导模型）。
     */
    private int maxContextTokens = 2000;

    /** 是否启用查询改写。开则多一次 LLM 调用（延迟 + 成本），多轮指代场景才划得来 */
    private boolean rewriteEnabled = false;

    /** 检索缓存开关 */
    private boolean cacheEnabled = true;

    /** 缓存 TTL（秒）。默认 24h：语料变更频率远低于对话频率 */
    private long cacheTtlSeconds = 86400L;

    /** 单次检索整体超时（毫秒）。超时即降级为「无资料」，不能让对话卡住 */
    private long timeoutMs = 3000L;
}
