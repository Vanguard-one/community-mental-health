package com.cmh.psycare.ai.rag;

/**
 * 一次混合检索的命中结果。
 *
 * <p>{@code score} 是 RRF 融合分（<b>不是</b>余弦相似度）—— 它只用于「谁排前面」，
 * 拿它跟 0.8 之类的阈值比是没有意义的。Phase 4 的阈值过滤必须另取
 * 单路（稠密）的相似度，这也是为什么本类同时保留 {@code denseScore}。
 * 现在就存下来，比等到 Phase 4 再回头改 Milvus 查询要便宜。
 *
 * @param chunkId    命中子块 id
 * @param docId      所属文档
 * @param parentId   父块 id（回表取正文的钥匙）
 * @param docTitle   引用展示
 * @param sourceType 权威度
 * @param publishYear 发布年份
 * @param score      RRF 融合分
 * @param denseScore 稠密那一路的相似度；稀疏路召回、稠密路未召回时为 null
 */
public record MilvusHit(
        long chunkId,
        long docId,
        Long parentId,
        String docTitle,
        String sourceType,
        Integer publishYear,
        float score,
        Float denseScore
) {
}
