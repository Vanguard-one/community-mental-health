package com.cmh.psycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 状态维度计数（去标识化聚合结果，不含任何个人字段）。
 *
 * 用途：
 *   1. 医生端工单台的状态分组角标（GET /alert/status-count）
 *   2. 管理端驾驶舱的工单状态汇总柱状图（GET /admin/dashboard/alert-sum）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusCount {

    /** 状态名，如 PENDING / CLOSED */
    private String status;

    /** 该状态下的记录数 */
    private Long count;
}
