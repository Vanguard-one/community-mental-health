import { get } from './request'
import type { AssessmentHistoryItem, PageResult, ResidentInfo } from '@/types'

/**
 * 医生端「用户信息」接口。后端已限定仅 DOCTOR 角色可访问。
 */

/** 居民分页列表（含测评概览与当前医生对应的会话 id） */
export function pageResidents(params: {
  current?: number
  size?: number
  /** 按姓名或登录名模糊搜 */
  keyword?: string
}): Promise<PageResult<ResidentInfo>> {
  return get<PageResult<ResidentInfo>>('/doctor/resident/page', params)
}

/** 某居民的历次测评 = 他的「所属评测问卷」 */
export function listResidentAssessments(residentId: number): Promise<AssessmentHistoryItem[]> {
  return get<AssessmentHistoryItem[]>(`/doctor/resident/${residentId}/assessments`)
}
