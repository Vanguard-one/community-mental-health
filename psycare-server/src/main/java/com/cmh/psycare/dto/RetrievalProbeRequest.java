package com.cmh.psycare.dto;

import lombok.Data;

/**
 * 检索端点（{@code POST /knowledge/retrieve}）的请求体。
 *
 * <h3>为什么不复用 {@link KnowledgeRequest}</h3>
 * <p>那个类是「建档 / 导入 / 预览」三合一，字段全是文档元数据与切分参数。
 * 把 {@code query} 塞进去会让它变成四合一，且新字段对原有三个用途毫无意义 ——
 * 正是它自己注释里批评的那种「为了省一个类而让语义变含糊」。
 *
 * <h3>这个端点是干什么的</h3>
 * <p>给 {@code eval_retrieval.py} 做阈值校准用：它必须走<b>真实的那条检索路径</b>
 * （向量化 → Milvus 混合召回 → 阈值 → 回表 → 裁剪），而不是直连 Milvus 自己算相似度。
 * 直连测出来的是「Milvus 的能力」，走接口测出来的才是「用户提问时实际会被注入什么」。
 */
@Data
public class RetrievalProbeRequest {

    /** 查询原文。与用户在对话框里敲的是同一个东西，不预做任何处理 */
    private String query;

    /**
     * 临时覆盖 {@code psycare.retrieval.min-dense-score}，用于阈值网格扫描。
     *
     * <p>留空 = 用配置值。传了只影响<b>本次请求</b>，不改任何全局状态
     * （理由见 {@code RetrievalPipeline#retrieve(String, Float)} 的注释）。
     */
    private Float minDenseScore;

    /** 是否返回正文。评测只看章节命中，关掉可以显著缩小响应体 */
    private Boolean withContent;
}
