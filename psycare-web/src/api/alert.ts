import { get, post } from './request'
import type {
  AlertAssessmentVO,
  AlertRecord,
  AlertStatus,
  AlertStatusLog,
  AlertType,
  PageResult,
  StatusCount,
} from '@/types'

export interface AlertPageParams {
  current: number
  size: number
  /** 状态过滤，多值用逗号分隔（如 'CONFIRMED,PROCESSING'）；空则不过滤 */
  status?: string
  /** 预警类型过滤；空则不过滤 */
  alertType?: AlertType | ''
}

/** 预警工单分页 */
export function getAlertPage(params: AlertPageParams): Promise<PageResult<AlertRecord>> {
  return get<PageResult<AlertRecord>>('/alert/page', { ...params })
}

/** 工单详情：深链直达与刷新还原 */
export function getAlertDetail(id: number): Promise<AlertRecord> {
  return get<AlertRecord>(`/alert/${id}`)
}

/** 各状态工单数（固定返回 6 态，含 0 值） */
export function getAlertStatusCount(): Promise<StatusCount[]> {
  return get<StatusCount[]>('/alert/status-count')
}

/** 处置工单（状态机流转，非法流转后端返回 400） */
export function handleAlert(id: number, toStatus: AlertStatus, note?: string): Promise<void> {
  return post<void>(`/alert/${id}/handle`, { toStatus, note })
}

/**
 * 重新开启工单（**仅管理员**）：把已驳回 / 已关闭的工单退回「待确认」，医生端会再次看到它。
 *
 * 不传 toStatus —— 目标状态由后端固定为 PENDING。医生角色调这个接口会被后端 403 拒绝。
 */
export function reopenAlert(id: number, note?: string): Promise<void> {
  return post<void>(`/alert/${id}/reopen`, { note })
}

/** 工单流转日志（按时间正序） */
export function getAlertLogs(id: number): Promise<AlertStatusLog[]> {
  return get<AlertStatusLog[]>(`/alert/${id}/logs`)
}

/**
 * 工单关联的评测做题记录（医生端 / 管理端共用）。
 *
 * 返回里带该居民的历史测评，用于判断「这次是不是比上次更差」。
 * 趋势类工单的 assessmentId 可为 null，此时 record 为 null 但 history 仍有值 ——
 * 调用方必须容忍 record 为空，不能假设一定有卷可看。
 */
export function getAlertAssessment(alertId: number): Promise<AlertAssessmentVO> {
  return get<AlertAssessmentVO>(`/alert/${alertId}/assessment`)
}
