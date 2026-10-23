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

    /**
     * 逻辑删除标记：0 未删 1 已删。
     *
     * <h3>为什么量表必须软删，不能物理删除</h3>
     * <p>{@code assessment} 用 {@code scale_id} 引用量表，{@code assessment_answer} 用
     * {@code item_id / option_id} 引用题目与选项。物理删掉量表行后，
     * {@code AssessmentRecordMapper.xml} 里那两条 {@code JOIN scale} 的历史查询
     * 会直接查不出数据 —— 居民的测评历史与工单的纵向对比会**整段消失**，
     * 而这是全系统最不能丢的数据。
     *
     * <h3>软删后历史还能带出卷名吗</h3>
     * <p>能。MyBatis-Plus 的逻辑删除只在 {@code BaseMapper} 生成的方法上注入
     * {@code is_deleted = 0}，XML 里手写的 SQL 原样执行 —— 所以
     * {@code selectAnswerRows / selectHistory} 的 {@code JOIN scale} 仍能取到
     * 已软删行的 {@code name/code}，{@code AssessmentService.getRecord} 的注释
     * （"即便量表被删也能正常回显"）正是按这个前提写的。
     *
     * <h3>代价</h3>
     * <p>{@code scale.code} 上有 UNIQUE 约束，软删行会继续占用编码
     * （如 'PHQ9'）。本项目暂不开放「新增筛查表」，暂无影响；
     * 将来若开放，插入前需先清理软删行或允许编码复用。
     */
    private Integer isDeleted;

    /** 题目数量。非表字段，由列表接口聚合填充，供前端卡片展示 */
    @TableField(exist = false)
    private Integer itemCount;

    private LocalDateTime createdAt;
}
