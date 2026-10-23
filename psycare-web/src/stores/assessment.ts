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
  /**
   * 历史列表是否已作废（本地缓存比服务端旧）。
   *
   * <p>只为「提交完新测评」一个场景存在：`history` 是 Pinia 里的长生命周期
   * 缓存，`loadHistory()` 见到非空就直接返回，所以提交后的新记录**不会自己出现**。
   * 症状是「刚做完测评进 AI 问诊，右栏还显示上一次的分数」—— 不报错、刷新一下
   * 又好了，最难查。
   *
   * <p>为什么不让 `submit()` 直接把 `history` 清空：那一瞬间右栏会掉回「关联测评」
   * 兜底卡（闪一下）。留旧数据 + 打一个「作废」标记，等新数据到了再整体替换，
   * 界面是原地更新而不是先空再满。
   */
  const historyStale = ref(false)

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
      /*
       * 新记录已落库 → 历史列表作废并立刻重取一次。
       *
       * 这是右栏「最近一次测评」卡的数据源：它读 `history`，而 `loadHistory()`
       * 见到缓存非空就短路。不在这里作废的话，刚提交完进问诊页仍会看到上一次的
       * 分数（本次修的 bug）。
       *
       * 两个细节：
       * ① 不 `await` —— 提交页的按钮状态不该被这次「顺手刷新」拖住；
       * ② 失败要吞掉但**不清 `historyStale`** —— 网络抖一下不该让用户回到旧数据，
       *    标记留着，下一次 `loadHistory()` 会再取（成功时才归 false）。
       */
      historyStale.value = true
      void loadHistory(true).catch(() => undefined)
      return result
    } finally {
      submitting.value = false
    }
  }

  async function loadHistory(force = false) {
    // `historyStale` 与 `force` 是两条独立的路：前者是「数据本身过期了」，
    // 后者是「调用方现在就想重新拉」。任一为真都要真去取，否则提交完
    // 新测评再进问诊页，右栏会一直停在上一次的分数上（缓存短路）。
    if (history.value.length && !force && !historyStale.value) return history.value
    historyLoading.value = true
    try {
      history.value = await getHistory()
      historyStale.value = false
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
