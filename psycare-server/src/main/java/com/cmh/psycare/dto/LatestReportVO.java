package com.cmh.psycare.dto;

import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 最近一次测评报告（AI 问诊页「报告」视图专用）。
 *
 * <h3>为什么不复用 {@link AssessmentRecordVO}</h3>
 * 那个 VO 面向「做题记录回看」，重点是逐题明细（answers），且没有满分、没有建议文案；
 * 报告要的是另一组字段：量表名 / 满分 / 等级描述 / 建议。两个用途差得太远，
 * 硬塞进一个 VO 会让两边都拿到一半用不上的字段，还会把 answers 这种重字段
 * 带进一个只需要摘要的接口。
 *
 * <h3>advice 为什么是重算的，而不是从库里读</h3>
 * {@code assessment} 表**没有 advice 列** —— 建议一直是
 * {@code AssessmentService.submit} 返回响应时用 {@code buildAdvice} 现拼的，
 * 从来没落过库。好在 {@code buildAdvice} 是纯函数，入参只有
 * {@code crisis} 与 {@code riskLevel}，而这两个值都持久化在
 * {@code crisis_flag / risk_level} 里，所以按历史记录重算出来的文案
 * **与提交时必然一致**（除非期间改过文案，那是产品层面的口径变更）。
 *
 * <p>这也意味着：本报告是「按当前规则重算」而非「当时的快照」。
 * 若将来要做可归档 / 可导出的报告件，才需要真的加一列把文案冻住。
 */
@Data
@Builder
public class LatestReportVO {

    /** assessment.id */
    private Long assessmentId;

    private Long scaleId;

    /** PHQ9 / GAD7 —— 量表被软删时仍能带出（见 ScaleMapper#selectByIdIncludeDeleted） */
    private String scaleCode;

    /** 评测卷名称，如「PHQ-9 抑郁筛查量表」；量表已被物理删除时为 null */
    private String scaleName;

    /** 维度，如「抑郁」 */
    private String dimension;

    /** 量表满分。null 表示量表已不可查，前端应隐藏「/ 满分」与百分比条 */
    private Integer maxScore;

    private Integer totalScore;

    /** 原始等级，如「无/极轻」 */
    private String rawLevel;

    /** A/B/C/D */
    private RiskLevel riskLevel;

    /** 等级中文描述，与 {@code RiskLevel#getDesc()} 同一来源 */
    private String riskLevelDesc;

    /** 危机项是否阳性（库里是 tinyint，这里转成布尔给前端直接用） */
    private Boolean crisis;

    /** 建议文案，由 {@code buildAdvice} 重算 */
    private String advice;

    private LocalDateTime completedAt;
}
