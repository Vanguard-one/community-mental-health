package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.DashboardStats;
import com.cmh.psycare.dto.RiskCount;
import com.cmh.psycare.dto.StatusCount;
import com.cmh.psycare.dto.TrendPoint;
import com.cmh.psycare.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端数据驾驶舱接口。
 *
 * 权限：仅 ADMIN。本项目只做了认证没做授权，因此每个接口显式断言角色，
 * 否则任何已登录账号（含居民）都能读走全域统计。
 *
 * 合规：所有返回值均为聚合计数，不含个人标识字段。
 */
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    /** 顶部四项指标 */
    @GetMapping("/stats")
    public Result<DashboardStats> stats() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(adminService.stats());
    }

    /** 测评趋势，默认近 30 天，返回按天连续序列 */
    @GetMapping("/trend")
    public Result<List<TrendPoint>> trend(@RequestParam(defaultValue = "30") Integer days) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(adminService.trend(days));
    }

    /** 风险等级分布，固定 A/B/C/D 四项 */
    @GetMapping("/risk-dist")
    public Result<List<RiskCount>> riskDist() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(adminService.riskDist());
    }

    /** 工单状态汇总，固定 6 态 */
    @GetMapping("/alert-sum")
    public Result<List<StatusCount>> alertSum() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(adminService.alertSum());
    }
}
