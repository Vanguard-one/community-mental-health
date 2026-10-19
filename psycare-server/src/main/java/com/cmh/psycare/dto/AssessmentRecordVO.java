package com.cmh.psycare.dto;

import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次测评的完整做题记录（医生端 / 管理端展示用）。
 * <p>
 * 需求要的四要素在这里一次给全：评测卷名称（scaleName）、
 * 每题题目与作答（answers）、得分（totalScore / 每题 score）、完成时间（completedAt）。
 * 一次返回避免前端二次请求拼装。
 */
@Data
@Builder
public class AssessmentRecordVO {

    /** assessment.id */
    private Long id;

    private Long scaleId;

    /** PHQ9 / GAD7 */
    private String scaleCode;

    /** 评测卷名称，如「PHQ-9 抑郁筛查量表」 */
    private String scaleName;

    private Integer totalScore;

    /** 原始等级，如「中重度」 */
    private String rawLevel;

    /** A/B/C/D */
    private RiskLevel riskLevel;

    /** 是否危机项阳性 */
    private Integer crisisFlag;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 逐题明细，按题号升序 */
    private List<Answer> answers;

    /** 单题作答 */
    @Data
    @Builder
    public static class Answer {

        private Integer itemNo;

        /** 题干 —— 必须 join scale_item 才有，assessment_answer 只存 option_id */
        private String question;

        /** 作答选项文案 —— 必须 join scale_option */
        private String answer;

        /** 该题得分 */
        private Integer score;

        /** 该题是否危机项（前端据此标红） */
        private Integer crisisFlag;
    }
}
