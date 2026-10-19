package com.cmh.psycare.dto;

import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 测评结果响应
 */
@Data
@Builder
public class AssessmentResultResponse {

    private Long assessmentId;
    private Integer totalScore;
    /** 原始等级：如 中度 */
    private String rawLevel;
    /** 系统风险等级 A/B/C/D */
    private RiskLevel riskLevel;
    private String riskLevelDesc;
    /** 是否危机项阳性 */
    private Boolean crisis;
    /** 是否触发预警 */
    private Boolean alertTriggered;
    /** 给居民的提示文案（高危/危机时含求助信息） */
    private String advice;
}
