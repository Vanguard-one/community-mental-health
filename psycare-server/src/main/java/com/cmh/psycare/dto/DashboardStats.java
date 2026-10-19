package com.cmh.psycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 驾驶舱顶部四项指标。
 *
 * 口径说明（避免前端自行猜测含义）：
 *   userCount          服务居民数，仅统计 role = RESIDENT 的账号
 *   assessmentCount    历史测评总量
 *   pendingAlertCount  待确认（PENDING）的预警工单数
 *   todayNew           今日新增测评数，按 created_at 当日零点起算
 *
 * 去标识化：本对象只含聚合计数，不含任何可定位到个人的字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStats {

    private Long userCount;
    private Long assessmentCount;
    private Long pendingAlertCount;
    private Long todayNew;
}
