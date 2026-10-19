package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医生推荐日志（含推荐理由，供解释与后续评估）
 */
@Data
@TableName("recommendation_log")
public class RecommendationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long residentId;

    /** 来源会话（AI 问诊触发推荐时关联） */
    private Long sessionId;

    private Long doctorId;

    /** 推荐依据（如：风险等级C→精神科；抑郁维度高分→专长匹配） */
    private String reason;

    /** 居民是否采纳：1 是 0 否 */
    private Integer accepted;

    private LocalDateTime createdAt;
}
