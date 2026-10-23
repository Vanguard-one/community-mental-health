<template>
  <div class="page-container">
    <PageHeader title="为你推荐的医生" :subtitle="subtitle" />

    <!--
      卡片网格 / 匹配依据 / 空态 / 骨架屏全在 `RecommendedDoctors` 里。
      问诊页顶部「医生」按钮复用同一个组件 —— 同一份推荐在两处必须长得一样，
      所以这里一点都不该再自己画一遍。
    -->
    <RecommendedDoctors :doctors="doctors" :loading="loading" :basis="basis" />

    <p class="footnote">
      推荐由后端 <span class="mono">RecommendationEngine</span> 基于你最近一次测评与你
      在 AI 问诊中描述的情况计算，并已写入 <span class="mono">recommendation_log</span> 便于追溯；
      推荐排序不构成就医建议，最终就诊请以医生判断为准。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import RecommendedDoctors from '@/components/RecommendedDoctors.vue'
import { getRecommendDoctors } from '@/api/recommend'
import { useAssessmentStore } from '@/stores/assessment'
import { formatMatchBasis } from '@/utils/assessment'
import type { RecommendResult } from '@/types'

/**
 * AI 推荐结果页（`/ai-recommend`）。
 *
 * 与「医生咨询」页（`/doctors`）分工不同：
 * 那边是**自己按科室挑**（用于主动找医生），这边是**AI 已经挑好了**（推荐链路的终点）。
 * 两个页面都保留，因为两种意图都真实存在 —— 合并成一个会牺牲其中一边。
 *
 * <p>进入方式有两种，都靠 `?sessionId=` 区分：
 * ① AI 问诊里用户答应接受推荐 → 播完分析动画后跳到这里（带 sessionId，
 *    推荐会优先取该会话关联的那次测评）；
 * ② 从别处（如首页）直接进来（不带 sessionId，退化为「最近一次测评」）。
 *
 * <p>本文件现在只剩「页面壳」：取数 + 页头 + 脚注。卡片怎么画全部交给
 * `RecommendedDoctors`（问诊页的「医生」视图用的是同一个组件）。
 */
const route = useRoute()
const store = useAssessmentStore()

const loading = ref(false)
const doctors = ref<RecommendResult[]>([])

/** 页头副标：把「匹配到几位医生」讲清楚（加载中 / 空结果各有措辞） */
const subtitle = computed(() => {
  if (loading.value && !doctors.value.length) return '正在根据你刚才的倾诉匹配医生…'
  const n = doctors.value.length
  if (!n) return '根据你刚才的倾诉，暂时没有匹配到合适的医生'
  return `根据你刚才的倾诉，为你匹配到 ${n} 位医生`
})

/** 最近一次测评的摘要，用于说明「为什么是这几位医生」 */
const latest = computed(() => store.history[0])
const latestScale = computed(() => {
  const scaleId = latest.value?.scaleId
  return scaleId ? store.scales.find((s) => s.id === scaleId) : undefined
})
const basis = computed(() => formatMatchBasis(latest.value, latestScale.value))

onMounted(async () => {
  loading.value = true
  const sessionId = route.query.sessionId ? Number(route.query.sessionId) : null
  try {
    const [list] = await Promise.all([
      getRecommendDoctors(sessionId, 3),
      store.loadScales(),
      store.loadHistory().catch(() => undefined),
    ])
    doctors.value = list
  } catch {
    // 失败提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ---------- 页脚说明 ---------- */
/* ⚠️ 贴页面底的文字不能用 --text-3（贴底仅 3.60:1，不到 AA）→ 用 --text-2（约 5.4:1） */
.footnote {
  margin: var(--space-block) 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-2);
}
</style>
