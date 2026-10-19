package com.cmh.psycare.dto;

import lombok.Data;

import java.util.List;

/**
 * 一次索引（或导入）的结果。
 *
 * <p>{@code message} 是**给人看的**下一步提示，不是错误堆栈 —— 索引失败最常见的两个
 * 原因是「Milvus 没启动」与「embedding Key 失效」，这两件事用户自己就能解决，
 * 但前提是提示里说清是哪一件。
 */
@Data
public class KnowledgeIndexReportVO {

    private long docId;

    /** READY / FAILED */
    private String status;

    private int parentCount;

    private int childCount;

    /** 实际写入 Milvus 的行数 */
    private int milvusRows;

    /** 向量化永久失败的条数（这些子块入库了但搜不到） */
    private int failedVectors;

    private boolean milvusOk;

    private String message;

    /** 批量导入时，逐篇的结果 */
    private List<KnowledgeIndexReportVO> details;
}
