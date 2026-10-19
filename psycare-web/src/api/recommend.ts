import { get } from './request'
import type { RecommendResult } from '@/types'

/** 医生推荐 */
export function getRecommendDoctors(sessionId?: number | null, topN = 3): Promise<RecommendResult[]> {
  return get<RecommendResult[]>('/recommend/doctors', {
    sessionId: sessionId ?? undefined,
    topN,
  })
}