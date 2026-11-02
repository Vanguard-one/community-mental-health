import { get, post } from './request'
import type { AppointmentCreatePayload, AppointmentItem } from '@/types'

/**
 * 预约（居民侧）。
 *
 * 所有调用都不传 residentId —— 后端从 JWT 取。DTO 里没有这个字段，
 * 就不存在「填别人的 id」这条越权路径。
 */

/**
 * 提交预约。
 *
 * 后端的失败文案各不相同，且都是**给用户看**的：
 * · 「你已预约过该时段，请勿重复提交」→ 别改时间，去「我的预约」
 * · 「该时段已约满，请换个时间」    → 必须换时段
 * 调用方要原样展示 `msg`，不要统一改成「预约失败」。
 */
export function createAppointment(payload: AppointmentCreatePayload): Promise<AppointmentItem> {
  return post<AppointmentItem>('/appointment', payload)
}

/** 我的预约（含历史与已取消）。过期与否看 `statusKey`，不要拿 status 自己算 */
export function getMyAppointments(): Promise<AppointmentItem[]> {
  return get<AppointmentItem[]>('/appointment/my')
}

/**
 * 取消预约。`reason` 可不填。
 *
 * 就诊开始时间已过的单子不能自助取消（后端会拒绝），
 * 所以按钮的显示条件用 `item.cancellable`，不要用 `status === 'BOOKED'`。
 */
export function cancelAppointment(id: number, reason?: string): Promise<void> {
  return post<void>(`/appointment/${id}/cancel`, reason ? { reason } : {})
}
