package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工单视角的做题记录（医生端 / 管理端共用）。
 * <p>
 * 比需求多给一个 history：医生打开工单时最想知道的是
 * 「这次是不是变差了」，只有单次快照看不出来。数据量小（同一居民的全部测评），
 * 不分页一并返回，前端切换零额外请求。
 * <p>
 * 工单无关联测评时（TREND 趋势类工单的 assessment_id 可为 NULL）：
 * record 为 null，history 仍返回该居民全部记录。
 */
@Data
@Builder
public class AlertAssessmentVO {

    private Long alertId;

    private Long residentId;

    /** 已脱敏 */
    private String residentName;

    /** 触发本工单的那次测评，可为 null */
    private Long assessmentId;

    /** 本次测评明细，可为 null */
    private AssessmentRecordVO record;

    /** 该居民的历史测评（按时间倒序），用于纵向对比 */
    private List<AssessmentHistoryVO> history;
}
