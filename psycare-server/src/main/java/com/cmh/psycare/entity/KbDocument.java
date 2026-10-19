package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档（事实源）。
 *
 * <p>与 Milvus 的分工：<b>这里是唯一的事实</b>，Milvus 只是它的可重建索引。
 * 整个 collection 被误删也能靠本表 + {@code KnowledgeIndexService.rebuild} 恢复 ——
 * 这是「先 MySQL 后 Milvus」这个写入顺序的全部意义。
 *
 * <p>{@code embedModel} / {@code dim} 两列是给 rebuild 用的：换过向量模型之后，
 * 需要知道<b>哪些文档是用旧模型建的</b>。没有这两列，就只能全量重跑或全量漏跑。
 * 它们由服务端在建索引时从 {@code psycare.embedding.*} 写入，不接受前端传参 ——
 * 「当前配置」与「建库时的配置」是两件事，混在一起这两列就白记了。
 *
 * <p>逻辑删除 {@code isDeleted} 由全局配置接管（{@code logic-delete-field: isDeleted}）。
 * 注意 DDL 里的唯一索引 {@code uk_alive_title} 只约束未删除行 ——
 * 所以「软删后同名重建」是允许的，这也是合成语料重跑幂等的基础。
 */
@Data
@TableName("kb_document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** SYNTHETIC=AI 合成（仅验链路） / AUTHORITATIVE=权威 / UPLOAD=人工上传 */
    private String sourceType;

    /** 原始来源：文件路径，或合成语料的生成脚本 + topics 出处 */
    private String sourcePath;

    private Integer publishYear;

    /** PENDING / INDEXING / READY / FAILED。FAILED 可直接重跑，rebuild 会把它当 PENDING */
    private String status;

    /** 子块数 = 该文档在 Milvus 中的向量条数（校验脚本据此比对两库一致性） */
    private Integer chunkCount;

    /** 建索引时使用的向量模型名 */
    private String embedModel;

    /** 建索引时的向量维度（与 embedModel 成对，缺一不可） */
    private Integer dim;

    /** 失败原因（截断保存；完整堆栈走日志） */
    private String errorMsg;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
