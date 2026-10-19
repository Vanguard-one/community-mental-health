import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getAlertAssessment,
  getAlertDetail,
  getAlertLogs,
  getAlertPage,
  getAlertStatusCount,
  handleAlert,
  reopenAlert,
} from '@/api/alert'
import { useAuthStore } from './auth'
import {
  ALERT_GROUPS,
  type AlertAssessmentVO,
  type AlertRecord,
  type AlertStatus,
  type AlertStatusLog,
  type AlertType,
  type StatusCount,
} from '@/types'

/**
 * 预警工单处置台的共享状态。
 *
 * 设计要点：
 * 1. 列表与右栏详情分离：列表只存分页结果，详情独立拉取，支持深链直达。
 * 2. 状态计数来自后端聚合接口，不靠前端遍历列表统计（列表只是当前页）。
 * 3. 处置后局部更新状态，不整页重载 —— 否则工单会因筛选项变化从列表里"跳走"。
 */
export const useAlertStore = defineStore('alert', () => {
  const authStore = useAuthStore()

  // ---- 列表 ----
  const records = ref<AlertRecord[]>([])
  const total = ref(0)
  const current = ref(1)
  const size = ref(10)
  const loading = ref(false)

  // ---- 筛选 ----
  const groupKey = ref(ALERT_GROUPS[0].key)
  const typeFilter = ref<AlertType | ''>('')

  // ---- 状态计数（6 态聚合） ----
  const statusCounts = ref<StatusCount[]>([])

  // ---- 选中工单 ----
  const selectedId = ref<number | null>(null)
  const detail = ref<AlertRecord | null>(null)
  const detailLoading = ref(false)
  const logs = ref<AlertStatusLog[]>([])
  const logsLoading = ref(false)
  const handling = ref(false)
  /** 管理员「重新开启」的请求态，与 handling 分开 —— 两个按钮的 loading 不能互相带动 */
  const reopening = ref(false)

  // ---- 关联评测做题记录（医生端 / 管理端共用的右栏面板） ----
  const assessment = ref<AlertAssessmentVO | null>(null)
  const assessmentLoading = ref(false)

  const activeGroup = computed(
    () => ALERT_GROUPS.find((g) => g.key === groupKey.value) ?? ALERT_GROUPS[0],
  )

  /**
   * 分组角标数：把 6 态计数按列表分组口径合并。
   * 后端未升级（无 /alert/status-count）时返回空对象，UI 退回只显示总数。
   */
  const groupCounts = computed<Record<string, number>>(() => {
    const out: Record<string, number> = {}
    if (statusCounts.value.length === 0) return out
    const byStatus = new Map(statusCounts.value.map((s) => [s.status, s.count]))
    for (const g of ALERT_GROUPS) {
      out[g.key] = g.statuses
        ? g.statuses
            .split(',')
            .reduce((sum, s) => sum + (byStatus.get(s as AlertStatus) ?? 0), 0)
        : statusCounts.value.reduce((sum, s) => sum + s.count, 0)
    }
    return out
  })

  /** 待确认数：医生端最关心的待办量 */
  const pendingCount = computed(
    () => statusCounts.value.find((s) => s.status === 'PENDING')?.count ?? 0,
  )

  function setGroup(key: string) {
    if (groupKey.value === key) return
    groupKey.value = key
    current.value = 1
  }

  function setTypeFilter(t: AlertType | '') {
    if (typeFilter.value === t) return
    typeFilter.value = t
    current.value = 1
  }

  function setPage(page: number, pageSize?: number) {
    current.value = page
    if (pageSize) size.value = pageSize
  }

  async function loadPage() {
    loading.value = true
    try {
      const res = await getAlertPage({
        current: current.value,
        size: size.value,
        status: activeGroup.value.statuses || undefined,
        alertType: typeFilter.value || undefined,
      })
      records.value = res.records
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  async function loadStatusCount() {
    try {
      statusCounts.value = await getAlertStatusCount()
    } catch {
      // 后端尚未升级时静默降级，不阻断列表使用
      statusCounts.value = []
    }
  }

  async function loadLogs(id: number) {
    logsLoading.value = true
    try {
      logs.value = await getAlertLogs(id)
    } finally {
      logsLoading.value = false
    }
  }

  /**
   * 加载工单关联用户的评测做题记录。
   *
   * 失败时**不清空** assessment：后端尚未升级到带 /alert/{id}/assessment 的版本时，
   * 该接口会 404 —— 此时面板应显示「暂不支持」，而不是把上一次的记录留在屏幕上造成误导。
   * 因此失败统一置 null。
   */
  async function loadAssessment(id: number) {
    assessmentLoading.value = true
    assessment.value = null
    try {
      assessment.value = await getAlertAssessment(id)
    } catch {
      assessment.value = null
    } finally {
      assessmentLoading.value = false
    }
  }

  /** 选中工单：先用列表行秒开，再拉详情校正，并加载流转日志与评测记录 */
  async function select(id: number) {
    selectedId.value = id
    const local = records.value.find((r) => r.id === id)
    if (local) detail.value = local

    detailLoading.value = !local
    try {
      detail.value = await getAlertDetail(id)
    } catch {
      // 详情接口不可用时保留列表行数据，右栏仍可读
      if (!local) detail.value = null
    } finally {
      detailLoading.value = false
    }

    await Promise.all([loadLogs(id), loadAssessment(id)])
  }

  /** 处置：成功后局部更新状态与日志，并刷新角标 */
  async function handle(toStatus: AlertStatus, note?: string) {
    const target = detail.value
    if (!target) return
    handling.value = true
    try {
      await handleAlert(target.id, toStatus, note || undefined)

      const updated: AlertRecord = {
        ...target,
        status: toStatus,
        handlerId: authStore.userId ?? target.handlerId,
        handleNote: note || target.handleNote,
      }
      detail.value = updated

      const row = records.value.find((r) => r.id === target.id)
      if (row) {
        row.status = toStatus
        row.handlerId = updated.handlerId
        row.handleNote = updated.handleNote
      }

      await Promise.all([loadLogs(target.id), loadStatusCount()])
    } finally {
      handling.value = false
    }
  }

  /** 清空右栏（切分组时调用，避免展示已不在当前列表里的工单） */
  function clearSelection() {
    selectedId.value = null
    detail.value = null
    logs.value = []
    assessment.value = null
  }

  /**
   * 重新开启工单（仅管理员）：已驳回 / 已关闭 → 待确认，让医生重新核实。
   *
   * 与 handle 的关键差别是**要重新拉列表**：重开后工单通常已不属于当前分组
   *（「已办结」里重开的会跳到「待确认」），只改本行状态会留下一条与分组对不上的幽灵行。
   * 但右栏**不重选** —— 停在这张工单上直接显示新状态，管理员点完能立刻看到结果，
   * 而不是被弹到列表第一条去。
   */
  async function reopen(note?: string) {
    const target = detail.value
    if (!target) return
    reopening.value = true
    try {
      await reopenAlert(target.id, note || undefined)

      // 后端把处置人 / 处置备注一并清空，本地同步清掉，否则会显示「待确认 + 处理人 #2」
      detail.value = { ...target, status: 'PENDING', handlerId: null, handleNote: null }

      await Promise.all([loadPage(), loadLogs(target.id), loadStatusCount()])
    } finally {
      reopening.value = false
    }
  }

  return {
    records,
    total,
    current,
    size,
    loading,
    groupKey,
    typeFilter,
    statusCounts,
    selectedId,
    detail,
    detailLoading,
    logs,
    logsLoading,
    handling,
    reopening,
    assessment,
    assessmentLoading,
    activeGroup,
    groupCounts,
    pendingCount,
    setGroup,
    setTypeFilter,
    setPage,
    loadPage,
    loadStatusCount,
    loadLogs,
    loadAssessment,
    select,
    handle,
    reopen,
    clearSelection,
  }
})
