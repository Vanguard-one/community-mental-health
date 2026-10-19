package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.enums.AlertStatus;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.dto.DashboardStats;
import com.cmh.psycare.dto.RiskCount;
import com.cmh.psycare.dto.StatusCount;
import com.cmh.psycare.dto.TrendPoint;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.AdminDashboardMapper;
import com.cmh.psycare.mapper.AlertRecordMapper;
import com.cmh.psycare.mapper.AssessmentMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端驾驶舱统计服务。
 *
 * 设计约束：
 * 1. 全部返回聚合计数，不返回任何可定位到个人的字段（去标识化）。
 * 2. 固定枚举维度一律「补齐」后返回（A/B/C/D、6 种工单状态），
 *    避免前端为「某等级恰好为 0」这类情况再写补零逻辑。
 * 3. 只有按天分组这种条件构造器表达不了的查询才走 XML，其余用 selectCount。
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    /** 趋势默认天数与上限，防止 days 被传成 100000 拖垮库 */
    private static final int DEFAULT_TREND_DAYS = 30;
    private static final int MAX_TREND_DAYS = 365;

    private final SysUserMapper sysUserMapper;
    private final AssessmentMapper assessmentMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AdminDashboardMapper adminDashboardMapper;
    private final AlertService alertService;

    /** 顶部四项指标 */
    public DashboardStats stats() {
        // 服务居民数：只算 RESIDENT，医生与管理员不属于被服务对象
        Long userCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getRole, "RESIDENT"));

        Long assessmentCount = assessmentMapper.selectCount(null);

        Long pendingAlertCount = alertRecordMapper.selectCount(
                new LambdaQueryWrapper<AlertRecord>().eq(AlertRecord::getStatus, AlertStatus.PENDING));

        // 今日新增测评：用 >= 当日零点做范围比较，避免在列上套 DATE() 导致索引失效
        Long todayNew = assessmentMapper.selectCount(
                new LambdaQueryWrapper<Assessment>()
                        .ge(Assessment::getCreatedAt, LocalDate.now().atStartOfDay()));

        return new DashboardStats(nvl(userCount), nvl(assessmentCount), nvl(pendingAlertCount), nvl(todayNew));
    }

    /**
     * 近 N 天测评趋势。缺失日期补 count=0 / avgScore=null，保证 X 轴连续。
     */
    public List<TrendPoint> trend(Integer days) {
        int d = (days == null || days <= 0) ? DEFAULT_TREND_DAYS : Math.min(days, MAX_TREND_DAYS);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(d - 1L);

        List<TrendPoint> rows = adminDashboardMapper.selectDailyTrend(from.atStartOfDay());
        Map<LocalDate, TrendPoint> byDate = new HashMap<>();
        for (TrendPoint p : rows) {
            if (p != null && p.getDate() != null) {
                byDate.put(p.getDate(), p);
            }
        }

        List<TrendPoint> out = new ArrayList<>(d);
        for (int i = 0; i < d; i++) {
            LocalDate day = from.plusDays(i);
            TrendPoint hit = byDate.get(day);
            out.add(hit != null ? hit : new TrendPoint(day, 0L, null));
        }
        return out;
    }

    /** 风险等级分布，固定 A/B/C/D 四项 */
    public List<RiskCount> riskDist() {
        List<RiskCount> list = new ArrayList<>(RiskLevel.values().length);
        for (RiskLevel level : RiskLevel.values()) {
            Long count = assessmentMapper.selectCount(
                    new LambdaQueryWrapper<Assessment>().eq(Assessment::getRiskLevel, level));
            list.add(new RiskCount(level.name(), nvl(count)));
        }
        return list;
    }

    /** 工单状态汇总，与医生端角标复用同一份聚合逻辑 */
    public List<StatusCount> alertSum() {
        return alertService.statusCount();
    }

    private long nvl(Long v) {
        return v == null ? 0L : v;
    }
}
