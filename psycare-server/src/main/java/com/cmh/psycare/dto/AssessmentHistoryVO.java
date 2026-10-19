package com.cmh.psycare.dto;

import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史测评摘要（不含逐题明细，避免响应过大）。
 * <p>
 * 刻意不加 @Builder：它由 MyBatis 直接自动映射填充，
 * 而 @Builder 会吃掉无参构造器，导致 MyBatis 无法实例化。
 * 组件 VO 才用 builder，查询结果 DTO 用 @Data。
 */
@Data
public class AssessmentHistoryVO {

    /** assessment.id */
    private Long id;

    private String scaleCode;

    private String scaleName;

    private Integer totalScore;

    private RiskLevel riskLevel;

    private LocalDateTime completedAt;
}
