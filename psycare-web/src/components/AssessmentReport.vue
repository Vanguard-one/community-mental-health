<template>
  <div class="report-body">
    <!--
      ① 主视觉：本次总分（大号数字 + 主色）+ 风险等级 + 复测建议 + ② 风险标尺。
      动效：'stagger' 取容器内所有 [data-anim]，110ms 递进（分数 → 等级 → 解读 → 量表名）。

      这个组件被两个地方用：测评结果页（/assessment/:id/result）与
      AI 问诊页的「报告」视图。**只放展示块，不放页面壳** ——
      出口按钮、脚注、页面宽度都归调用方，否则两处会互相拉扯。
    -->
    <section v-reveal="'stagger'" class="hero">
      <div class="hero-main">
        <!-- 分数是主视觉，规格对齐 Home.vue 的 .score：46px / --primary / .mono -->
        <div class="hero-score" data-anim="pop">
          <span class="score mono" data-count>{{ report.totalScore }}</span>
          <span v-if="hasMax" class="score-max mono">/ {{ report.maxScore }}</span>
        </div>

        <div class="hero-text">
          <!--
            风险等级一律走 RiskLevelTag（四色阶唯一渲染入口）。
            不要在这里另造一个 A/B/C/D 大字、用 --risk-*-实色当文字（不达 AA）。
          -->
          <RiskLevelTag :level="report.riskLevel" size="large" dot data-anim="rise" />

          <div class="hero-desc" data-anim="rise">
            {{ report.riskLevelDesc }}
            <span class="hero-sep">·</span>
            <span class="hero-tip">{{ followUpTip }}</span>
          </div>

          <div v-if="report.scaleName" class="hero-scale" data-anim="rise">
            {{ report.scaleName }}
            <template v-if="report.scaleDimension"> · {{ report.scaleDimension }}</template>
          </div>
        </div>
      </div>

      <RiskScale
        :level="report.riskLevel"
        :score="report.totalScore"
        :max-score="report.maxScore ?? 0"
        :hint="riskScaleHint"
      />
    </section>

    <!--
      危机 / 预警提示（红线前置）。
      L1 白卡 + 左侧 3px 实色风险条：不能用 `--risk-*-soft` 直接贴页面底，
      它与页面底仅约 1.25:1，等于隐形（三级面硬规则①）。实色色阶允许用于描边。

      注意 `alertTriggered` 只有「刚提交完」那次结果拿得到（submit 响应里才有）；
      历史报告来自 assessment 表，那张表没有这个字段，所以永远走不到 is-warn 分支。
    -->
    <section v-if="report.crisis" v-reveal="'rise'" class="notice is-crisis">
      <div class="notice-head">检测到危机信号</div>
      <p class="notice-body">
        请立即拨打<strong>全国 24 小时心理援助热线 12356</strong>，或北京心理危机研究与干预中心
        <strong>010-82951332</strong>。系统已为你转接人工，专业人员会尽快与你联系。
      </p>
    </section>
    <section v-else-if="report.alertTriggered" v-reveal="'rise'" class="notice is-warn">
      <div class="notice-head">已触发预警</div>
      <p class="notice-body">社区工作人员已收到提醒，将尽快与你联系，请保持通讯畅通。</p>
    </section>

    <!-- ③ 维度得分：后端暂未返回维度聚合，仅在数据存在时渲染 -->
    <section v-if="dimensions.length" class="panel">
      <h2 class="panel-title">维度得分</h2>
      <div v-for="d in dimensions" :key="d.name" class="dim-row">
        <div class="dim-head">
          <span class="dim-name">{{ d.name }}</span>
          <span class="dim-score mono">{{ d.score }} / {{ d.maxScore }}</span>
        </div>
        <div class="dim-track">
          <div
            class="dim-fill"
            :class="d.exceeded ? 'is-exceeded' : 'is-normal'"
            :style="{ width: `${Math.min((d.score / d.maxScore) * 100, 100)}%` }"
          />
        </div>
        <p v-if="d.exceeded" class="dim-note">该维度超出参考阈值</p>
      </div>
    </section>

    <!-- 本次评估构成：用确实拿得到的数据，不虚构维度 -->
    <section class="panel">
      <h2 class="panel-title">本次评估构成</h2>
      <div class="fact-grid">
        <div class="fact">
          <div class="fact-label">总分</div>
          <div class="fact-value mono">
            {{ report.totalScore }}<span v-if="hasMax" class="fact-sub"> / {{ report.maxScore }}</span>
          </div>
        </div>
        <div class="fact">
          <div class="fact-label">临床分级</div>
          <div class="fact-value">{{ report.rawLevel || '—' }}</div>
        </div>
        <div class="fact">
          <div class="fact-label">危机项</div>
          <div class="fact-value" :class="{ 'is-crisis': report.crisis }">
            {{ report.crisis ? '阳性' : '未检出' }}
          </div>
        </div>
      </div>
      <div class="ratio-track">
        <div
          class="ratio-fill"
          :class="`is-${report.riskLevel}`"
          :style="{ width: `${ratioPercent}%` }"
        />
      </div>
      <p class="ratio-caption mono">{{ ratioPercent }}% 满分占比</p>
    </section>

    <!-- ④ 建议列表：每条是一块 L2 面（--panel-bg）分区，文字 --text-2 -->
    <section class="panel is-advice">
      <h2 class="panel-title">给您的建议</h2>
      <ul v-reveal="'stagger'" class="advice-list">
        <li v-for="(item, i) in adviceItems" :key="i" data-anim="line">{{ item }}</li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import RiskScale from '@/components/RiskScale.vue'
import type { ReportData, RiskLevel } from '@/types'

const props = defineProps<{ report: ReportData }>()

/**
 * 复测周期属于产品侧提示文案，不是临床标准，取值仅为引导居民关注变化趋势。
 * 临床口径应以医生意见为准。
 */
const FOLLOW_UP: Record<RiskLevel, string> = {
  A: '建议 4 周后复测',
  B: '建议 2 周内复测',
  C: '建议 1 周内复测并预约医生',
  D: '建议立即联系专业人员',
}

const hasMax = computed(() => typeof props.report.maxScore === 'number' && props.report.maxScore > 0)
const dimensions = computed(() => props.report.dimensions ?? [])
const followUpTip = computed(() => FOLLOW_UP[props.report.riskLevel] ?? '')

const ratioPercent = computed(() => {
  const max = props.report.maxScore ?? 0
  if (!max) return 0
  return Math.min(Math.round((props.report.totalScore / max) * 100), 100)
})

/** 复测建议已在上方 hero 里给出，这里只在危机语义下补充额外说明，避免同一句话出现两次 */
const riskScaleHint = computed(() =>
  props.report.crisis ? '本次作答命中危机项，已按最高等级处置' : '',
)

/**
 * 后端 advice 是**一整段文本**，不是数组 —— 按「。」「；」拆成列表更便于阅读。
 * A 级文案本身就是两句（「…积极心态。如需倾诉…」），所以看起来像两条，其实是拆出来的。
 */
function splitAdvice(text: string): string[] {
  const out: string[] = []
  for (const line of text.split(/\n+/)) {
    let buf = ''
    for (const ch of line) {
      buf += ch
      if (ch === '。' || ch === '；') {
        const trimmed = buf.trim()
        if (trimmed) out.push(trimmed)
        buf = ''
      }
    }
    const rest = buf.trim()
    if (rest) out.push(rest)
  }
  return out
}

const adviceItems = computed(() => splitAdvice(props.report.advice ?? ''))
</script>

<style scoped>
/* ---------- 主视觉（L1 白卡 · 页面级主卡 → --radius-lg） ---------- */
.hero {
  padding: 32px 36px 28px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  margin-bottom: var(--space-card);
}

.hero-main {
  display: flex;
  align-items: center;
  gap: 28px;
  margin-bottom: var(--space-block);
}

.hero-score {
  flex: none;
  display: flex;
  align-items: baseline;
  gap: 6px;
}

/* 全页最强的一个数字：白卡上 --primary 是 4.68:1，且 46px 远超大字体门槛，双保险 */
.score {
  font-size: 46px;
  font-weight: 700;
  line-height: 1;
  color: var(--primary);
}

.score-max {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-3);
}

.hero-text {
  min-width: 0;
}

.hero-desc {
  margin-top: 12px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.hero-sep {
  margin: 0 6px;
  color: var(--text-3);
  font-weight: 400;
}

.hero-tip {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-2);
}

.hero-scale {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-3);
}

/* ---------- 提示条（L1 白卡 + 左侧实色风险条） ---------- */
.notice {
  --notice-key: var(--card-border);
  padding: 16px 20px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-left: 3px solid var(--notice-key);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  margin-bottom: var(--space-card);
}

.notice.is-crisis {
  --notice-key: var(--risk-d);
}

.notice.is-warn {
  --notice-key: var(--risk-b);
}

.notice-head {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--text-1);
}

/* 实色 --risk-d 当文字色在白底上只有 3.89:1，压在同族 soft 底上更低 ——
   浅底上的风险文字一律用深一档的 --risk-*-ink */
.notice.is-crisis .notice-head {
  color: var(--risk-d-ink);
}

.notice-body {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-2);
}

.notice-body strong {
  color: var(--text-1);
}

/* ---------- 通用面板（内卡 → --radius-sm） ---------- */
.panel {
  padding: 22px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  margin-bottom: var(--space-card);
}

/* 卡片小标题（16px 级）走 --font-sans + 600，而不是全局 h1-h6 的宋体 */
.panel-title {
  margin: 0 0 18px;
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

/* ---------- 维度条 ---------- */
.dim-row + .dim-row {
  margin-top: 18px;
}

.dim-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.dim-name {
  font-size: 14px;
  color: var(--text-1);
}

.dim-score {
  font-size: 13px;
  color: var(--text-3);
}

/* 轨道是白卡内部的分区，--panel-bg（L2）在这成立 */
.dim-track {
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--panel-bg);
  overflow: hidden;
}

.dim-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  transition: width 0.4s var(--ease);
}

.dim-fill.is-normal {
  background: var(--risk-a);
}

.dim-fill.is-exceeded {
  background: var(--risk-b);
}

/* 白底上的风险文字用 ink 档：实色 --risk-b 直接当文字只有 2.24:1 */
.dim-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--risk-b-ink);
}

/* ---------- 评估构成 ---------- */
.fact-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}

/* 三个指标块是白卡内部的 L2 分区：只用底色分层，不叠阴影 */
.fact {
  padding: 14px 16px;
  background: var(--panel-bg);
  border-radius: var(--radius-sm);
}

/* --text-3 落在 --panel-bg 上只有 4.27:1（不到 AA），卡内浅底一律 --text-2 */
.fact-label {
  font-size: 12.5px;
  color: var(--text-2);
  margin-bottom: 6px;
}

.fact-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-1);
}

/* 同上：实色 --risk-d 压在 --panel-bg 上只有 3.77:1 */
.fact-value.is-crisis {
  color: var(--risk-d-ink);
}

.fact-sub {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-2);
}

.ratio-track {
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--panel-bg);
  overflow: hidden;
}

.ratio-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  transition: width 0.4s var(--ease);
}

.ratio-fill.is-A {
  background: var(--risk-a);
}
.ratio-fill.is-B {
  background: var(--risk-b);
}
.ratio-fill.is-C {
  background: var(--risk-c);
}
.ratio-fill.is-D {
  background: var(--risk-d);
}

.ratio-caption {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

/* ---------- 建议（每条一块 L2 分区） ---------- */
.panel.is-advice {
  border-left: 3px solid var(--primary);
}

.advice-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.advice-list li {
  padding: 12px 16px;
  background: var(--panel-bg);
  border-radius: var(--radius-sm);
  color: var(--text-2);
  font-size: 14.5px;
  line-height: 1.7;
}

@media (max-width: 640px) {
  .hero-main {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }

  .score {
    font-size: 36px;
  }

  .fact-grid {
    grid-template-columns: 1fr;
  }
}
</style>
