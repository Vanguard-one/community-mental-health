package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 医生推荐结果（含推荐理由，供解释性展示）
 */
@Data
@Builder
public class RecommendResult {

    private Long doctorId;
    private Long userId;
    private String doctorName;
    private String department;
    private String title;
    private String expertiseTags;
    private Double rating;
    private Integer currentLoad;
    /** 综合得分（排序依据） */
    private Double score;
    /** 推荐理由 */
    private String reason;
}
