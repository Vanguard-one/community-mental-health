import { get, post } from './request'
import type { Notification } from '@/types'

/** 我的通知列表（最多 50 条，按时间倒序） */
export function getMyNotifications(): Promise<Notification[]> {
  return get<Notification[]>('/notification/my')
}

/**
 * 未读条数（导航角标）。
 *
 * 是一条 COUNT，不拉列表：角标按 15 秒轮询，而列表接口带 50 条正文；
 * 更要紧的是列表有 `limit 50`，拿它数未读在「超过 50 条」时会数错。
 */
export function getUnreadNotificationCount(): Promise<number> {
  return get<number>('/notification/unread-count')
}

/**
 * 标记单条已读。
 *
 * 后端是幂等的：已经是已读、或这条不是我的，都返回成功（影响 0 行）。
 * 因此调用方把它当「尽力而为」即可 —— 失败不该阻断「打开详情」，
 * 否则一次网络抖动会让用户点不开通知。
 */
export function markNotificationRead(id: number): Promise<void> {
  return post<void>(`/notification/${id}/read`)
}
