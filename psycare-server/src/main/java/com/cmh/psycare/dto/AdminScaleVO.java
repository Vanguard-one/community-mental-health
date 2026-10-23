package com.cmh.psycare.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端「评测筛查表」列表项。
 *
 * <h3>为什么单独建 VO，而不是直接把 {@code Scale} 返回给管理端</h3>
 * <p>管理端比居民端多需要两个**聚合字段**：
 * <ul>
 *   <li>{@code itemCount} —— 题目数量，让管理员一眼看出"这套表是不是空的"；</li>
 *   <li>{@code assessmentCount} —— 已产生的测评次数，用于在删除前把影响讲清楚
 *       （有历史测评时删除只是下架，历史记录仍保留）。</li>
 * </ul>
 * <p>这两个值都不在 {@code scale} 表里，靠 `scale_item` 与 `assessment` 聚合而来。
 * 直接塞进实体就要长期维护 {@code @TableField(exist = false)}，而实体会被
 * 居民端接口复用 —— 居民端拿到 {@code assessmentCount} 毫无意义，还多暴露一个
 * 与它无关的统计维度。所以管理端走独立 VO。
 */
@Data
@Builder
public class AdminScaleVO {

    private Long id;

    /** 量表编码：PHQ9 / GAD7 / PHQ15 / PSQI */
    private String code;

    private String name;

    /** 测量维度：抑郁 / 焦虑 / 躯体化 / 睡眠 */
    private String dimension;

    /** 满分 */
    private Integer maxScore;

    private String description;

    /** 1 启用 0 停用 */
    private Integer status;

    /** 题目数量（来自 scale_item 聚合） */
    private Integer itemCount;

    /** 已产生的测评次数（来自 assessment 聚合）。>0 时删除只下架、不销毁历史 */
    private Integer assessmentCount;

    private LocalDateTime createdAt;
}
