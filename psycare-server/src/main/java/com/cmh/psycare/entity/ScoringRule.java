package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 计分/切分规则：总分落在 [minScore, maxScore] 区间映射到某等级
 */
@Data
@TableName("scoring_rule")
public class ScoringRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long scaleId;

    /** 区间下界（含） */
    private Integer minScore;

    /** 区间上界（含） */
    private Integer maxScore;

    /** 原始等级名：如 无/轻度/中度/中重度/重度 */
    private String rawLevel;

    /** 该分数段映射的系统风险等级：A/B/C/D */
    private String riskLevel;

    /** 排序 */
    private Integer sort;
}
