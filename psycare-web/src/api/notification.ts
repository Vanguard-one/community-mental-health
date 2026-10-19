import { get } from './request'
import type { Notification } from '@/types'

/** 我的通知列表 */
export function getMyNotifications(): Promise<Notification[]> {
  return get<Notification[]>('/notification/my')
}