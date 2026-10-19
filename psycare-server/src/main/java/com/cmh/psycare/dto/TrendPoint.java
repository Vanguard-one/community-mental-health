package com.cmh.psycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 测评趋势的单个数据点（按天聚合）。
 *
 * 无测评的日期由 AdminService 补零返回，保证折线图 X 轴连续，
 * 此时 avgScore 为 null（没有样本算不出均值），前端按断点处理。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendPoint {

    /** 日期，序列化为 yyyy-MM-dd */
    private LocalDate date;

    /** 当日测评量 */
    private Long count;

    /** 当日平均总分，保留 1 位小数；当日无数据为 null */
    private Double avgScore;
}
