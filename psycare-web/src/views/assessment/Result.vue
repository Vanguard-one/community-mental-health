<template>
  <div class="page-container result-page">
    <a-result
      v-if="!result"
      status="warning"
      title="未找到测评结果"
      sub-title="结果仅在提交后保留于当前浏览器会话，请重新完成一次测评"
    >
      <template #extra>
        <a-button type="primary" @click="goScales">去测评</a-button>
      </template>
    </a-result>

    <template v-else>
      <!--
        报告主体（hero / 风险标尺 / 预警 / 评估构成 / 建议）已抽成组件，
        与 AI 问诊页的「报告」视图共用 —— 那些块在两边必须长得一模一样。
        这里只留页面壳：宽度、出口按钮、脚注。
      -->
      <AssessmentReport v-if="reportData" :report="reportData" />

      <!-- 三个出口 -->
      <div class="actions">
        <a-button type="primary" size="large" @click="goAiChat">去 AI 咨询</a-button>
        <a-button size="large" @click="goRecommend">查看推荐医生</a-button>
        <a-button size="large" type="text" @click="goScales">重新测评</a-button>
      </div>

      <!-- 规则来源声明 -->
      <p class="footnote">
        风险分级由系统按管理端配置的切分规则自动计算，本结果<strong>不构成医学诊断</strong>，不能替代专业医生面诊。
        如你正处于危机状态，请立即拨打 12356。
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AssessmentReport from '@/components/AssessmentReport.vue'
import { useAssessmentStore } from '@/stores/assessment'
import { useChatStore } from '@/stores/chat'
import type { AssessmentResultResponse, ReportData, ReportDimension } from '@/types'

type ResultWithDimensions = AssessmentResultResponse & { dimensions?: ReportDimension[] }

const router = useRouter()
const store = useAssessmentStore()
const chat = useChatStore()

const result = computed(() => store.lastResult as ResultWithDimensions | null)
const scaleMeta = computed(() => store.lastScaleMeta)

/**
 * 把「结果响应 + 量表元信息」两片数据拍平成组件要的那一个对象。
 * 这一层映射是抽组件的代价 —— 但换来的是两个页面共用同一份模板与样式，
 * 比各维护一份、改一处忘一处要划算得多。
 */
const reportData = computed<ReportData | null>(() => {
  const r = result.value
  if (!r) return null
  return {
    totalScore: r.totalScore,
    rawLevel: r.rawLevel,
    riskLevel: r.riskLevel,
    riskLevelDesc: r.riskLevelDesc,
    crisis: r.crisis,
    advice: r.advice,
    scaleName: scaleMeta.value?.name,
    scaleDimension: scaleMeta.value?.dimension,
    maxScore: scaleMeta.value?.maxScore,
    alertTriggered: r.alertTriggered,
    dimensions: r.dimensions,
  }
})

function goScales() {
  router.push('/scales')
}

/**
 * 「查看推荐医生」→ AI 推荐结果页，而不是「医生咨询」页。
 * 这个按钮的语义是「按我刚做的这次测评给我匹配医生」，属于**系统替我挑**；
 * 「医生咨询」（/doctors）是**自己按科室挑**，是另一种意图，走顶部菜单进。
 */
function goRecommend() {
  router.push('/ai-recommend')
}

/**
 * 去 AI 问诊。
 *
 * <p>跳转前**显式丢弃当前会话状态**，有两层理由：
 * ① 清掉 `sessionId`，让 AI 问诊页那侧的 `open()` 真的走到后端 ——
 *    否则它会认为「已经有会话了」直接返回，这次测评就关联不上去；
 * ② 清掉前一段对话的消息，避免「新测评 + 旧消息」混在同一列里显示。
 *
 * <p>注意后端会**复用**这个居民那条进行中的会话并把这次测评关联上
 * （见 `AiConsultService.openSession`），所以「丢弃」丢的是前端的状态，
 * 不是对话记录本身 —— 记录不会因为这个入口断掉。
 */
function goAiChat() {
  chat.reset()
  const id = result.value?.assessmentId
  router.push(id ? `/ai-chat?assessmentId=${id}` : '/ai-chat')
}

onMounted(() => {
  // 刷新后若 store 为空但缓存还在，store 初始化已回读；这里只补量表列表供后续页面复用
  store.loadScales()
})
</script>

<style scoped>
.result-page {
  max-width: 820px;
}

/* ---------- 出口 ---------- */
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: var(--space-inner) 0 var(--space-card);
}

/* 脚注直接落在页面底上 → --text-2（--text-3 在这只有 3.60:1） */
.footnote {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-2);
}

.footnote strong {
  color: var(--text-1);
}
</style>
