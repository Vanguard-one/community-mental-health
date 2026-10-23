import { del, get, put } from './request'
import type { AdminScale, Scale } from '@/types'

/**
 * 筛查表管理接口（管理端）。
 *
 * 与 `assessment.ts` 里的 `getScaleList / getScaleDetail` 分开，是因为
 * **权限与可见范围都不同**：居民端那两个只看到"启用中"的，登录即可调用；
 * 这里的三个全是 ADMIN 能力，后端逐个方法断言，前端藏菜单不构成安全边界。
 *
 * 居民端的量表读取不要引本文件 —— 混用会让「管理端全量」误入答题链路。
 */

/** 全量筛查表（含已禁用，不含已删除），带题数与测评次数 */
export function listAdminScales(): Promise<AdminScale[]> {
  return get<AdminScale[]>('/scale/manage/list')
}

/**
 * 启用 / 禁用。传**目标值**而非切换：
 * `setScaleStatus(3, 0)` 无论点几次结果都是"禁用"，
 * 而 toggle 接口在双击场景下会把状态翻回原样。
 */
export function setScaleStatus(id: number, status: 0 | 1): Promise<Scale> {
  return put<Scale>(`/scale/${id}/status?status=${status}`)
}

/**
 * 删除筛查表（后端为逻辑删除）。
 * 删除后居民端不可见、不可提交测评；历史测评记录与逐题明细保留。
 */
export function deleteScale(id: number): Promise<void> {
  return del<void>(`/scale/${id}`)
}
