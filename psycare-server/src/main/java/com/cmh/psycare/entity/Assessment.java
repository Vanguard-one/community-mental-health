package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cmh.psycare.common.enums.RiskLevel;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心理测评记录（一次完整作答）
 */
@Data
@TableName("assessment")
public class Assessment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long residentId;

    private Long scaleId;

    /** 总分 */
    private Integer totalScore;

    /** 原始等级（按量表切分）：如 中度 */
    private String rawLevel;

    /** 系统风险等级 A/B/C/D */
    private RiskLevel riskLevel;

    /** 危机项阳性：1 是 0 否 */
    private Integer crisisFlag;

    /** 作答明细 JSON（itemNo -> score），便于追溯 */
    private String answerJson;

    private LocalDateTime createdAt;
}
