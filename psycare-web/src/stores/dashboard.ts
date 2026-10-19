import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getAlertSummary,
  getDashboardStats,
  getDashboardTrend,
  getRiskDistribution,
} from '@/api/admin'
import type { DashboardStats, RiskCount, StatusCount, TrendPoint } from '@/types'

/** 驾驶舱一次加载的全部数据 */
export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<DashboardStats | null>(null)
  const trend = ref<TrendPoint[]>([])
  const riskDist = ref<RiskCount[]>([])
  const alertSum = ref<StatusCount[]>([])

  const loading = ref(false)
  const loaded = ref(false)
  /** 加载失败的接口名，用于给出准确原因而非笼统报错 */
  const failed = ref<string[]>([])

  const isEmpty = computed(
    () =>
      loaded.value &&
      (stats.value?.assessmentCount ?? 0) === 0 &&
      (stats.value?.userCount ?? 0) === 0,
  )

  /** 近 N 日测评量合计 */
  const trendTotal = computed(() => trend.value.reduce((sum, p) => sum + p.count, 0))

  /**
   * 四个接口并行拉取，用 allSettled 而非 all：
   * 单个接口失败（例如后端尚未重启、没有 AdminController）时，
   * 其余图表仍照常展示，失败项单独提示。
   */
  async function loadAll(days = 30) {
    loading.value = true
    failed.value = []

    const [statsRes, trendRes, riskRes, alertRes] = await Promise.allSettled([
      getDashboardStats(),
      getDashboardTrend(days),
      getRiskDistribution(),
      getAlertSummary(),
    ])

    if (statsRes.status === 'fulfilled') stats.value = statsRes.value
    else failed.value.push('stats')

    if (trendRes.status === 'fulfilled') trend.value = trendRes.value
    else failed.value.push('trend')

    if (riskRes.status === 'fulfilled') riskDist.value = riskRes.value
    else failed.value.push('risk-dist')

    if (alertRes.status === 'fulfilled') alertSum.value = alertRes.value
    else failed.value.push('alert-sum')

    loaded.value = true
    loading.value = false
  }

  return {
    stats,
    trend,
    riskDist,
    alertSum,
    loading,
    loaded,
    failed,
    isEmpty,
    trendTotal,
    loadAll,
  }
})
