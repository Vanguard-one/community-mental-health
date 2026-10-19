package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分块。父块与子块<b>同表</b>，靠 {@code chunkType} 区分。
 *
 * <h3>为什么不是父子两张表</h3>
 * <p>父子各一张表要多一套 CRUD、两套删除逻辑，重建时还要保证两者一致；
 * 同表只多两列（{@code chunkType} + {@code parentId}），而
 * 「删除某文档的全部分块」永远是一句 {@code WHERE doc_id = ?}。
 * 代价是父块也占一行存储，可接受 —— 父块数只有子块的 1/4 左右。
 *
 * <h3>为什么没有 updatedAt</h3>
 * <p>分块是<b>不可变</b>的：内容变了就是一次重建（按 doc_id 先删后插），
 * 不存在「原地改一个分块」。给一个永远不被更新的列，只会让人以为它有意义。
 *
 * <h3>与 Milvus 的关系</h3>
 * <p>只有 {@code CHILD} 块进 Milvus（{@code chunk_id} 就是 Milvus 的主键）；
 * {@code PARENT} 块只留在这里，检索命中子块后用 {@code parentId} 回表取正文。
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docId;

    /** 父块 id；自身即父块时为 null */
    private Long parentId;

    /** PARENT=生成上下文（不进 Milvus） / CHILD=检索单元（进 Milvus） */
    private String chunkType;

    /**
     * 文档内顺序。
     *
     * <p>重建索引时按它推回原文顺序 —— <b>不依赖自增 id 的连续性</b>：
     * 重建是「先删后插」，删过的 id 不会复用，靠 id 排序会在第二次重建后错位。
     */
    private Integer ordinal;

    /** 章节路径，如「第 2 节 条目解读 / 2.3 第 9 题」，用于引用溯源 */
    private String section;

    private String content;

    /** 字符数（中文场景按<b>字</b>算，不用字节 —— 字节数在中文里没有直觉） */
    private Integer charCount;

    /** 估算 token 数（上下文预算控制用） */
    private Integer tokenCount;

    /** 正文 MD5（32 位小写），供 MySQL ↔ Milvus 校验脚本比对与重复导入去重 */
    private String contentHash;

    /** 起始页（PDF 溯源）；合成语料为 null */
    private Integer pageFrom;

    private Integer pageTo;

    private Integer isDeleted;

    private LocalDateTime createdAt;
}
