package com.cmh.psycare.dto;

import lombok.Data;

/**
 * 知识库的建档 / 导入 / 预览三合一请求体。
 *
 * <h3>为什么不拆成三个类</h3>
 * <p>三者的字段<b>完全一致</b>，差别只在「哪几个必填」：建档要 title，导入靠语料里的
 * doc_title，预览两者都不要。拆开就要维护三个只差注解的类，然后在改一个字段时
 * 忘改另外两个 —— 而这类不一致的报错是运行时的（字段收不到 = null），编译期看不出来。
 * 用同一个类 + Service 里显式校验必填项，只有一处需要看。
 */
@Data
public class KnowledgeRequest {

    /** 文档标题（建档必填；导入时忽略 —— 标题取自语料的 doc_title） */
    private String title;

    /** 正文；导入时是 JSONL 全文 */
    private String content;

    /**
     * 上传的文件名。**决定按哪种方式解析**：
     * {@code .jsonl} → 按语料逐行切分成多篇文档；其余（含空）→ 整篇作为一篇。
     * 传空即「直接粘贴文本」。
     */
    private String fileName;

    /** SYNTHETIC（AI 合成，仅验链路）/ AUTHORITATIVE（权威）/ UPLOAD（人工上传）。留空按 UPLOAD */
    private String sourceType;

    /** 发布年份，用于医疗时效性加权；可空 */
    private Integer publishYear;

    /* ---- 切分参数：留空走默认档（子 400 / 父 1400 / overlap 50） ---- */

    /** 子块目标字数（检索单元） */
    private Integer childSize;

    /** 父块目标字数（生成上下文） */
    private Integer parentSize;

    /** 相邻子块重叠字数（建议为 childSize 的 10–15%） */
    private Integer overlap;
}
