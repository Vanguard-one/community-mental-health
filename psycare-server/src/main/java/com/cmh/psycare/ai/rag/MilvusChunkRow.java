package com.cmh.psycare.ai.rag;

/**
 * 待写入 Milvus 的一行子块数据。
 *
 * <p>刻意独立于 {@code KbChunk} 实体：实体对应 MySQL 的<b>事实</b>（含父块、含 isDeleted），
 * 而这里是<b>索引行</b>（只有子块、且多了一个向量）。两者的字段集合只有一部分重叠 ——
 * 直接用实体当传输对象，就会在「实体新加一列但索引不需要」时让人误以为它会被写进去。
 *
 * @param chunkId    主键 = MySQL {@code kb_chunk.id}（**不 autoID**：双库 id 一致是回表查询的前提）
 * @param docId      所属文档
 * @param parentId   父块 id（检索命中后回表取正文）
 * @param text       子块正文，供 BM25 分析与召回展示
 * @param docTitle   引用卡片展示用
 * @param sourceType 权威度加权与过滤
 * @param publishYear 医疗时效性加权；可为 null
 * @param pageFrom   起始页；可为 null
 * @param pageTo     结束页；可为 null
 * @param tokenCount 预算控制
 * @param dense      稠密向量，**长度必须等于 collection 的 dim**
 */
public record MilvusChunkRow(
        long chunkId,
        long docId,
        Long parentId,
        String text,
        String docTitle,
        String sourceType,
        Integer publishYear,
        Integer pageFrom,
        Integer pageTo,
        Integer tokenCount,
        float[] dense
) {
}
