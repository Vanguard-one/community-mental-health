package com.cmh.psycare.dto;

import lombok.Data;

/**
 * 分块列表项。
 *
 * <p>**不带全文**：父块最长 1500 字、子块 400 字，一页 20 条就是几万字，
 * 列表页不需要它们。带 {@code preview}（头部 120 字）足够判断「这条是哪段内容」，
 * 要读全文再单独取。
 */
@Data
public class KbChunkVO {

    private Long id;

    private Long parentId;

    /** PARENT / CHILD */
    private String chunkType;

    private Integer ordinal;

    private String section;

    private Integer charCount;

    private Integer tokenCount;

    private String contentHash;

    /** 正文前 120 字 */
    private String preview;
}
