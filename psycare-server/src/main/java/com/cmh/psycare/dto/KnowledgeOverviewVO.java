package com.cmh.psycare.dto;

import lombok.Data;

/**
 * 知识库概览。
 *
 * <p>设计要点：<b>MySQL 侧与 Milvus 侧的数字分开给</b>。
 * 合成一个「知识块总数」，就再也回答不了「到底哪一边少了数据」——
 * 而这正是索引不一致时唯一需要知道的事（{@code selectCount} 与 Milvus 行数不等，
 * 要么是写入失败、要么是有人直接动了 collection）。
 */
@Data
public class KnowledgeOverviewVO {

    /** 未删除文档数 */
    private long docCount;

    /** status=READY 的文档数 */
    private long readyCount;

    /** status=FAILED 的文档数（这些需要重建） */
    private long failedCount;

    /** status=INDEXING 的文档数（正常应长期为 0；不为 0 说明有任务卡住） */
    private long indexingCount;

    /** MySQL 侧的子块总数（不含父块） */
    private long childChunkCount;

    /** Milvus 侧的向量行数；-1 = 不可用（未启用或连不上） */
    private long milvusRows;

    private boolean milvusEnabled;

    private boolean milvusOk;

    /** 一行可读的状态，如「psycare / psycare_kb_chunk / 1024 维 / 服务端 v3.0.0」 */
    private String milvusState;

    /** 当前配置的向量模型与维度（来自 embed_model 的实时配置，不是文档行上记的） */
    private String embedModel;

    private Integer dim;
}
