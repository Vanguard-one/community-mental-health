<template>
  <div class="page-container">
    <PageHeader title="为你推荐的医生" :subtitle="subtitle" />

    <!--
      匹配依据：AI 推荐不能是黑盒。
      ⚠️ 改版前这里直接贴页面底用了 --panel-bg（与页面底仅约 1.04:1，等于隐形），
         标签又用了 --text-3（贴底 3.60:1，不到 AA）。现在回到 L1 白卡、左侧 3px 主色描边
         表示「与匹配依据相关」；标签落在白面上，--text-3 的 4.8:1 才成立。
    -->
    <section v-if="basis" v-reveal="'rise'" class="basis">
      <span class="basis-label">匹配依据</span>
      <span class="basis-text">{{ basis }}</span>
    </section>

    <!--
      骨架屏：继续复用 CardGridSkeleton（内部样式由统一改造负责）。
      min 必须与下方 .doctor-grid 的列宽下限一致（324 = --grid-col-w），否则列数会跳。
    -->
    <CardGridSkeleton v-if="loading && !doctors.length" :count="3" :min="324" />

    <template v-else-if="doctors.length">
      <!-- 卡片列表：`stagger` recipe —— 每张卡 data-anim="card"，110ms 依次揭示 -->
      <div v-reveal="'stagger'" class="doctor-grid">
        <article v-for="(doc, i) in doctors" :key="doc.doctorId" class="doctor-card" data-anim="card">
          <span v-if="i === 0" class="rank-badge">首选推荐</span>

          <header class="doc-head">
            <RoleAvatar class="avatar" role="doctor" :size="52" />
            <div class="doc-ident">
              <h3 class="doc-name">{{ doc.doctorName }}</h3>
              <p class="doc-dept">{{ doc.department }} · {{ doc.title }}</p>
            </div>
          </header>

          <div class="metrics">
            <div class="metric">
              <span class="metric-value mono">{{ doc.rating?.toFixed(1) ?? '—' }}</span>
              <span class="metric-label">历史评分</span>
            </div>
            <div class="metric">
              <span class="metric-value mono">{{ doc.currentLoad ?? 0 }}</span>
              <span class="metric-label">当前负载</span>
            </div>
            <div class="metric">
              <span class="metric-value mono">{{ doc.score?.toFixed(1) ?? '—' }}</span>
              <span class="metric-label">综合得分</span>
            </div>
          </div>

          <!-- 专长：描边 chip（不用实底，避免「主色文字压浅底」的对比度问题） -->
          <div v-if="tags(doc.expertiseTags).length" class="tags">
            <span v-for="tag in tags(doc.expertiseTags)" :key="tag" class="tag">{{ tag }}</span>
          </div>

          <!--
            推荐理由：L2 --panel-bg 分区，正文 --text-2。
            复用 RevealCard —— 它的底就是 --panel-bg、正文就是 --text-2，与规范一致；
            它不承担页面底，所以不叠阴影（卡内浅色分区只用底色区分，硬规则②）。
          -->
          <RevealCard title="推荐理由" :score="doc.score">
            <ul class="reason-list">
              <li v-for="(r, ri) in reasons(doc.reason)" :key="ri">{{ r }}</li>
              <li v-if="!reasons(doc.reason).length">综合擅长方向与接诊情况排序靠前</li>
            </ul>
          </RevealCard>

          <!--
            咨询入口：本页是推荐链路的终点动作，必须能一键接上医生 ——
            让用户「拿着医生名字去别处找聊天入口」是这条链路最容易断掉的地方。
            跳专有聊天页（/messages/:doctorId），与「医生咨询」「我的消息」
            的入口完全一致：三处只有一个目的地，才不会出现「这里能聊、那里不能」。
            用原生 button：vendor-antd 预算很紧，能不加组件就不加。
          -->
          <button type="button" class="consult-btn" @click="goChat(doc)">立即咨询</button>
        </article>
      </div>
    </template>

    <!--
      空态（AI 没匹配到 / ?sessionId= 失效）：白卡 + --panel-bg 圆标 + 说明 + 次按钮。
      ⚠️ 圆标里的图形落 --panel-bg 上，只能用 --text-2（--text-3 在浅分区只有 4.27:1）。
    -->
    <div v-else-if="!loading" v-reveal="'rise'" class="empty-card">
      <span class="empty-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none">
          <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8" />
          <path d="M16 16 21 21" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
      </span>
      <p class="empty-title">暂时没有匹配到合适的医生</p>
      <p class="empty-desc">可能这次对话没有可用的推荐结果，你可以先按科室自行查找。</p>
      <button type="button" class="btn-ghost" @click="router.push('/doctors')">去按科室找医生</button>
    </div>

    <p class="footnote">
      推荐由后端 <span class="mono">RecommendationEngine</span> 基于你最近一次测评与你
      在 AI 问诊中描述的情况计算，并已写入 <span class="mono">recommendation_log</span> 便于追溯；
      推荐排序不构成就医建议，最终就诊请以医生判断为准。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import RevealCard from '@/components/RevealCard.vue'
import CardGridSkeleton from '@/components/CardGridSkeleton.vue'
import { getRecommendDoctors } from '@/api/recommend'
import { useAssessmentStore } from '@/stores/assessment'
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
 */
const route = useRoute()
const router = useRouter()
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
const basis = computed(() => {
  const a = latest.value
  if (!a) return ''
  const level = `${a.riskLevel} 级（${RISK_LABEL[a.riskLevel] ?? a.riskLevel}）`
  return latestScale.value
    ? `${latestScale.value.name} · ${latestScale.value.dimension} · 风险等级 ${level}`
    : `风险等级 ${level}`
})

const RISK_LABEL: Record<string, string> = {
  A: '低风险',
  B: '中风险',
  C: '高风险',
  D: '危机',
}

function tags(raw: string): string[] {
  return (raw || '')
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
}

/**
 * 后端 reason 为「；」分隔的多段面向用户的句子，逐段成条并去掉句末标点 ——
 * 否则每条后面都挂一个句号，读起来像被截断的日志。
 */
function reasons(raw: string): string[] {
  return (raw || '')
    .split('；')
    .map((s) => s.trim().replace(/[。；;]+$/, ''))
    .filter(Boolean)
}

/**
 * 去和该医生聊天。
 *
 * 只做导航：会话在目标页用幂等的 {@code POST /chat/sessions} 建或取。
 * 本页**不预建会话** —— 否则「点了但没进去」（用户中途返回、页面被刷掉）
 * 会在库里留下一条没有消息的空会话，而它永远不会显示在「我的消息」里。
 */
function goChat(d: RecommendResult) {
  router.push(`/messages/${d.doctorId}`)
}

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
/* ---------- 匹配依据（L1 白卡） ---------- */
/*
 * ⚠️ 贴页面底的「面」只能是白卡或主色。改版前这里是直接贴底的 --panel-bg，
 *    与页面底仅约 1.04:1（等于隐形）。左侧 3px 是实色 --primary 的**描边**，
 *    用来表达「这条说明和匹配有关」（实色只用于色块 / 描边，不用于浅底文字）。
 */
.basis {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 20px;
  margin-bottom: var(--space-card);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-left: 3px solid var(--primary);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/* 落在白面上 → --text-3 的 4.8:1 成立 */
.basis-label {
  flex: none;
  font-size: 12.5px;
  color: var(--text-3);
}

.basis-text {
  font-size: 13.5px;
  color: var(--text-2);
}

/* ---------- 医生卡网格 ---------- */
/*
 * 列宽取 --grid-col-w（324px，居民端卡片单列宽的唯一真源），卡间距取 --space-card。
 * 内容区最宽 1288px（--content-max 1344 − 两侧 --content-pad 28×2），
 * 于是每行 floor((1288 + 20) / (324 + 20)) = 3 列，每列约 416px。
 * ⚠️ 骨架屏 CardGridSkeleton 的 min 必须与这里的下限一致（324），否则列数会跳。
 */
.doctor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--grid-col-w), 1fr));
  gap: var(--space-card);
}

.doctor-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: var(--space-inner);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  /* 网格里的医生卡属于「内卡」→ --radius-sm(16) */
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  transition:
    box-shadow var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.doctor-card:hover {
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}

/* 「首选推荐」徽标。⚠️ --primary-soft 底 + --primary 字只有 4.44:1（不到 AA），
   文字改用同色相深一档的 --primary-dark（5.71:1）。 */
.rank-badge {
  position: absolute;
  top: 16px;
  right: 16px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-size: 11.5px;
  font-weight: 600;
}

.doc-head {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 尺寸与配色由 `RoleAvatar` 自理 */
.avatar {
  flex: none;
}

/* 卡片级小标题：--font-sans + 600（不继承全局 h1-h6 的宋体） */
.doc-name {
  margin: 0;
  font-family: var(--font-sans);
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
}

/* 元信息落在白卡上 → --text-3 的 4.8:1 成立 */
.doc-dept {
  margin-top: 3px;
  font-size: 13px;
  color: var(--text-3);
}

/* 三个指标块：卡内浅色分区（L2）—— 只用底色区分，不叠阴影 */
.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.metric {
  padding: 12px 10px;
  background: var(--panel-bg);
  border-radius: var(--radius-sm);
  text-align: center;
}

.metric-value {
  display: block;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}

/* ⚠️ 标签落 --panel-bg 上 → 必须用 --text-2；--text-3 在浅分区只有 4.27:1，不到 AA */
.metric-label {
  display: block;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--text-2);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 专长标签：描边 chip，不用实底 */
.tag {
  padding: 3px 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 12px;
}

/* 推荐理由正文（RevealCard 的底是 --panel-bg，正文用 --text-2） */
.reason-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-2);
}

/* 主色实底按钮贴底：卡片高度不一致时，按钮仍对齐在同一水平线上 */
.consult-btn {
  margin-top: auto;
  padding: 9px 16px;
  border: 1px solid var(--primary);
  border-radius: var(--radius-pill);
  background: var(--primary);
  color: var(--on-primary);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.consult-btn:hover {
  background: var(--primary-dark);
  border-color: var(--primary-dark);
}

/* ---------- 空态 ---------- */
.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/* --panel-bg 圆标：卡内浅色分区只用底色区分，不叠阴影（硬规则②）；
   图形色用 --text-2（--text-3 落 --panel-bg 上只有 4.27:1） */
.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: 16px;
  border-radius: 50%;
  background: var(--panel-bg);
  color: var(--text-2);
}

.empty-mark svg {
  display: block;
  width: 26px;
  height: 26px;
}

.empty-title {
  margin: 0 0 6px;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.empty-desc {
  margin: 0 0 18px;
  font-size: 13px;
  color: var(--text-2);
}

/* 次按钮：白底 + 控件描边，悬停转主色 */
.btn-ghost {
  height: 36px;
  padding: 0 20px;
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.btn-ghost:hover {
  border-color: var(--primary);
  color: var(--primary);
}

/* ---------- 页脚说明 ---------- */
/* ⚠️ 贴页面底的文字不能用 --text-3（贴底仅 3.60:1，不到 AA）→ 用 --text-2（约 5.4:1） */
.footnote {
  margin: var(--space-block) 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-2);
}
</style>
