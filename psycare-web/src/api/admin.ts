import { get } from './request'
import type { DashboardStats, RiskCount, StatusCount, TrendPoint } from '@/types'

/**
 * 管理端驾驶舱接口。
 * 全部为聚合计数，后端不做任何个人标识字段的返回。
 */

/** 顶部四项指标 */
export function getDashboardStats(): Promise<DashboardStats> {
  return get<DashboardStats>('/admin/dashboard/stats')
}

/** 测评趋势，默认近 30 天，返回按天连续序列（无数据日期补 0） */
export function getDashboardTrend(days = 30): Promise<TrendPoint[]> {
  return get<TrendPoint[]>('/admin/dashboard/trend', { days })
}

/** 风险等级分布，固定 A/B/C/D 四项 */
export function getRiskDistribution(): Promise<RiskCount[]> {
  return get<RiskCount[]>('/admin/dashboard/risk-dist')
}

/** 工单状态汇总，固定 6 态 */
export function getAlertSummary(): Promise<StatusCount[]> {
  return get<StatusCount[]>('/admin/dashboard/alert-sum')
}
