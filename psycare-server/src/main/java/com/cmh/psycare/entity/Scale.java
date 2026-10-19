package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心理量表（PHQ-9 抑郁 / GAD-7 焦虑 / PHQ-15 躯体化 / PSQI 睡眠，配置化驱动）
 */
@Data
@TableName("scale")
public class Scale {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 量表编码：PHQ9 / GAD7 / PHQ15 / PSQI */
    private String code;

    /** 量表名称 */
    private String name;

    /** 测量维度：如 抑郁 / 焦虑 / 躯体化 / 睡眠 */
    private String dimension;

    /** 满分 */
    private Integer maxScore;

    /** 说明 */
    private String description;

    /** 状态：1 启用 0 停用 */
    private Integer status;

    /** 题目数量。非表字段，由列表接口聚合填充，供前端卡片展示 */
    @TableField(exist = false)
    private Integer itemCount;

    private LocalDateTime createdAt;
}
