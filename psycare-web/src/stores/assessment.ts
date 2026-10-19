import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getHistory, getScaleDetail, getScaleList, submitAssessment } from '@/api/assessment'
import type { Assessment, AssessmentResultResponse, Scale, ScaleDetail } from '@/types'

/**
 * 结果页刷新后 Pinia 会清空，而接口没有「按 id 查历史结果」的能力，
 * 所以提交成功后把结果同时写进 sessionStorage，store 初始化时回读。
 */
const RESULT_CACHE_KEY = 'lastResult'
const META_CACHE_KEY = 'lastScaleMeta'

/** 结果页画标尺所需的量表元信息。result 响应里不含 scaleId，只能提交时顺手记下 */
export interface ScaleMeta {
  id: number
  code: string
  name: string
  dimension: string
  maxScore: number
}

function readCache<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

function writeCache(key: string, value: unknown) {
  try {
    sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* 隐私模式等场景写入失败可忽略，不影响主流程 */
  }
}

export const useAssessmentStore = defineStore('assessment', () => {
  // ---- 量表列表 ----
  const scales = ref<Scale[]>([])
  const scalesLoading = ref(false)

  // ---- 当前问卷 ----
  const detail = ref<ScaleDetail | null>(null)
  const detailLoading = ref(false)
  /** itemId -> optionId */
  const answers = ref<Record<number, number>>({})
  const submitting = ref(false)

  // ---- 历史 ----
  const history = ref<Assessment[]>([])
  const historyLoading = ref(false)

  // ---- 最近一次结果 ----
  const lastResult = ref<AssessmentResultResponse | null>(
    readCache<AssessmentResultResponse>(RESULT_CACHE_KEY),
  )
  const lastScaleMeta = ref<ScaleMeta | null>(readCache<ScaleMeta>(META_CACHE_KEY))

  const totalItems = computed(() => detail.value?.items.length ?? 0)
  const answeredCount = computed(() => Object.keys(answers.value).length)
  const progressPercent = computed(() =>
    totalItems.value ? Math.round((answeredCount.value / totalItems.value) * 100) : 0,
  )
  const allAnswered = computed(
    () => totalItems.value > 0 && answeredCount.value >= totalItems.value,
  )

  async function loadScales(force = false) {
    if (scales.value.length && !force) return scales.value
    scalesLoading.value = true
    try {
      scales.value = await getScaleList()
      return scales.value
    } finally {
      scalesLoading.value = false
    }
  }

  async function loadDetail(scaleId: number) {
    // 换量表时清空上一次的作答，避免串题
    if (detail.value && detail.value.scale.id !== scaleId) {
      answers.value = {}
    }
    detailLoading.value = true
    try {
      detail.value = await getScaleDetail(scaleId)
      return detail.value
    } finally {
      detailLoading.value = false
    }
  }

  function setAnswer(itemId: number, optionId: number) {
    answers.value = { ...answers.value, [itemId]: optionId }
  }

  /** 批量覆写作答（问卷草稿恢复用） */
  function restoreAnswers(map: Record<number, number>) {
    answers.value = { ...map }
  }

  function resetAnswers() {
    answers.value = {}
  }

  /** 提交测评，成功后写缓存返回结果；失败向上抛由调用方决定是否提示 */
  async function submit(): Promise<AssessmentResultResponse> {
    if (!detail.value) throw new Error('尚未加载量表')
    submitting.value = true
    try {
      const scale = detail.value.scale
      const result = await submitAssessment({
        scaleId: scale.id,
        answers: detail.value.items.map((item) => ({
          itemId: item.id,
          optionId: answers.value[item.id],
        })),
      })
      lastResult.value = result
      const meta: ScaleMeta = {
        id: scale.id,
        code: scale.code,
        name: scale.name,
        dimension: scale.dimension,
        maxScore: scale.maxScore,
      }
      lastScaleMeta.value = meta
      writeCache(RESULT_CACHE_KEY, result)
      writeCache(META_CACHE_KEY, meta)
      return result
    } finally {
      submitting.value = false
    }
  }

  async function loadHistory(force = false) {
    if (history.value.length && !force) return history.value
    historyLoading.value = true
    try {
      history.value = await getHistory()
      return history.value
    } finally {
      historyLoading.value = false
    }
  }

  /** 在历史里找某条记录；AI 问诊右栏要据此展示关联测评卡 */
  function findHistory(id: number | null | undefined): Assessment | undefined {
    if (!id) return undefined
    return history.value.find((a) => a.id === id)
  }

  function clearLastResult() {
    lastResult.value = null
    lastScaleMeta.value = null
    sessionStorage.removeItem(RESULT_CACHE_KEY)
    sessionStorage.removeItem(META_CACHE_KEY)
  }

  return {
    scales,
    scalesLoading,
    detail,
    detailLoading,
    answers,
    submitting,
    history,
    historyLoading,
    lastResult,
    lastScaleMeta,
    totalItems,
    answeredCount,
    progressPercent,
    allAnswered,
    loadScales,
    loadDetail,
    setAnswer,
    restoreAnswers,
    resetAnswers,
    submit,
    loadHistory,
    findHistory,
    clearLastResult,
  }
})
