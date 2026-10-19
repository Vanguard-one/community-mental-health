package com.cmh.psycare.dto;

import lombok.Data;

import java.util.List;

/**
 * 切分预览结果。
 *
 * <h3>为什么要带 {@code samples} 而不是只给数字</h3>
 * <p>「切成了 37 个块」这个数字无法回答真正的问题：<b>切得对不对</b>。
 * 中文语料的切分事故（表格被切碎、一个答案被切在边界上、标题被当成正文吸进块里）
 * 只有看见实际文本才能发现。所以预览把每个块的<b>头部若干字</b>带出来 ——
 * 足够看出边界切在哪，又不用把整库文本传到浏览器。
 */
@Data
public class KnowledgePreviewVO {

    private int parentCount;

    private int childCount;

    private int totalChars;

    private int totalTokens;

    /** 全文归一化后的字数（与 totalChars 的差 = 子块 overlap 造成的重复量） */
    private int normalizedChars;

    private List<ChunkSample> samples;

    @Data
    public static class ChunkSample {
        /** PARENT / CHILD */
        private String type;
        private int ordinal;
        private int parentOrdinal;
        private String section;
        private int charCount;
        private int tokenCount;
        /** 正文前若干字，仅用于人工核对切分边界 */
        private String preview;
    }
}
