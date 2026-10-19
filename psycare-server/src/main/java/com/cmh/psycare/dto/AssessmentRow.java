package com.cmh.psycare.dto;

import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测评做题记录的**扁平查询结果**（一行 = 一道题）。
 * <p>
 * 与 {@link AssessmentRecordVO} 分开的原因：一条测评有 N 道题，
 * 五表 join 回来的是一张宽表（卷头字段在每行重复），
 * Service 层再按 assessmentId 折叠成「卷头 + 明细数组」。
 * <p>
 * 字段来源：
 * assessment（卷头）/ scale（卷名）/ assessment_answer（得分）
 * / scale_item（题干）/ scale_option（选项文案）。
 */
@Data
public class AssessmentRow {

    /** assessment.id */
    private Long assessmentId;

    private Integer totalScore;

    /** 原始等级，如「中重度」 */
    private String rawLevel;

    /** A/B/C/D */
    private RiskLevel riskLevel;

    /** 是否危机项阳性 */
    private Integer crisisFlag;

    private LocalDateTime completedAt;

    private Long scaleId;

    /** PHQ9 / GAD7 */
    private String scaleCode;

    private String scaleName;

    /** 题号（卷面顺序） */
    private Integer itemNo;

    /** 题干 —— 来自 scale_item.content */
    private String question;

    /** 该题是否危机项 —— 来自 scale_item.crisis_flag */
    private Integer itemCrisis;

    /** 作答选项文案 —— 来自 scale_option.content */
    private String answer;

    /** 该题得分 */
    private Integer score;

    private Long optionId;
}
