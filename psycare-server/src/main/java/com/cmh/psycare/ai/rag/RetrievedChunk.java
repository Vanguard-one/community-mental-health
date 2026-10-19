package com.cmh.psycare.ai.rag;

/**
 * 一条「已回表、可注入提示词」的检索结果。
 *
 * <p>与 {@link MilvusHit} 的区别：hit 是 Milvus 的返回（只有子块摘要、没有正文），
 * 本类是<b>回表 MySQL 取到父块正文之后</b>的成品。分开的理由是两者的字段来源不同 ——
 * 混为一个类会让「哪些字段能在不过滤阈值时使用」变得含糊。
 *
 * @param chunkId     命中的子块 id（引用溯源用）
 * @param docId       所属文档
 * @param parentId    父块 id；为 null 表示该子块没有父块（退化情形）
 * @param docTitle    文档标题（引用卡片展示）
 * @param section     章节路径，如「第 2 节 条目解读 / 2.3 第 9 题」
 * @param content     取回的正文（优先父块；无父块时用子块正文）
 * @param score       RRF 融合分，只用于排序
 * @param denseScore  稠密相似度；null 表示本条由稀疏路召回
 * @param synthetic   是否合成语料（前端要打「合成示例数据」角标，见风险 R5）
 */
public record RetrievedChunk(
        long chunkId,
        long docId,
        Long parentId,
        String docTitle,
        String section,
        String content,
        float score,
        Float denseScore,
        boolean synthetic
) {

    /** 拼进提示词时用的单行文本：带来源，便于模型在正文里引用 */
    public String toPromptLine() {
        String title = docTitle == null || docTitle.isBlank() ? "未命名资料" : docTitle;
        StringBuilder sb = new StringBuilder();
        sb.append("《").append(title).append('》');
        if (section != null && !section.isBlank()) {
            sb.append(' ').append(section);
        }
        sb.append('\n').append(content == null ? "" : content);
        return sb.toString();
    }
}
