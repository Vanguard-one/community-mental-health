import { get, post } from './request'
import type {
  Assessment,
  AssessmentRecordVO,
  AssessmentResultResponse,
  AssessmentSubmitRequest,
  LatestReport,
  Scale,
  ScaleDetail,
} from '@/types'

/** 量表列表 */
export function getScaleList(): Promise<Scale[]> {
  return get<Scale[]>('/scale/list')
}

/** 量表详情（量表 + 题目 + 选项） */
export function getScaleDetail(scaleId: number): Promise<ScaleDetail> {
  return get<ScaleDetail>(`/scale/${scaleId}/detail`)
}

/** 提交测评 */
export function submitAssessment(data: AssessmentSubmitRequest): Promise<AssessmentResultResponse> {
  return post<AssessmentResultResponse>('/assessment/submit', data)
}

/** 我的测评历史 */
export function getHistory(): Promise<Assessment[]> {
  return get<Assessment[]>('/assessment/history')
}

/**
 * 最近一次测评报告（AI 问诊页「报告」视图）。
 *
 * 从未做过测评时后端返回 `data: null`（不是 404），调用方需要自己区分
 * 「没测过」与「请求失败」—— 前者渲染空状态卡，后者才是错误提示。
 */
export function getLatestReport(): Promise<LatestReport | null> {
  return get<LatestReport | null>('/assessment/latest-report')
}

/**
 * 按 id 查一次测评的完整做题记录（卷名、逐题作答、得分、完成时间）。
 * 居民只能查自己的；医生/管理员可查任意记录（工单处置用）。
 */
export function getAssessmentRecord(id: number): Promise<AssessmentRecordVO> {
  return get<AssessmentRecordVO>(`/assessment/${id}`)
}