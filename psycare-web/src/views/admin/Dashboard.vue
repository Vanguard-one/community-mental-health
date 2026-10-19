<template>
  <div class="dashboard">
    <!-- 工具条：口径声明 + 刷新 -->
    <div class="toolbar">
      <p class="scope-note">
        <SafetyCertificateOutlined class="scope-icon" />
        本页仅展示聚合统计口径数据，不含任何可定位到个人的字段
      </p>
      <a-button :loading="store.loading" @click="reload">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <a-alert
      v-if="store.failed.length > 0"
      type="warning"
      show-icon
      class="fail-alert"
      :message="`以下统计接口未返回数据：${store.failed.join(' / ')}`"
      description="请确认后端已重启且包含 AdminController（/api/admin/dashboard/*）。失败项在下方显示为空，其余图表不受影响。"
    />

    <!-- ① 四项指标 -->
    <div class="stat-grid">
      <StatCard
        v-for="c in cards"
        :key="c.label"
        :label="c.label"
        :value="c.value"
        :unit="c.unit"
        :hint="c.hint"
        :loading="firstLoading"
      />
    </div>

    <!-- ② 趋势 + 风险分布 -->
    <div class="chart-grid">
      <div class="panel surface">
        <div class="panel-head">
          <h3 class="panel-title font-serif">近 30 日测评趋势</h3>
          <span class="panel-sub mono">合计 {{ store.trendTotal }} 次</span>
        </div>
        <div class="chart-box">
          <VChart v-if="store.trend.length > 0" :option="trendOption" autoresize />
          <div v-else-if="firstLoading" class="chart-skeleton">
            <a-skeleton active :title="false" :paragraph="{ rows: 4 }" />
          </div>
          <a-empty v-else class="chart-empty" description="趋势接口未返回数据" />
        </div>
      </div>

      <div class="panel surface">
        <div class="panel-head">
          <h3 class="panel-title font-serif">风险等级分布</h3>
          <span class="panel-sub">按测评记录计</span>
        </div>
        <div class="chart-box chart-box--donut">
          <VChart v-if="riskTotal > 0" :option="riskOption" autoresize />
          <div v-else-if="firstLoading" class="chart-skeleton">
            <a-skeleton active :title="false" :paragraph="{ rows: 4 }" />
          </div>
          <a-empty v-else class="chart-empty" description="暂无可统计的测评记录" />
          <div v-if="riskTotal > 0" class="donut-center">
            <span class="mono donut-value">{{ riskTotal }}</span>
            <span class="donut-label">测评总量</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ③ 工单状态汇总 -->
    <div class="panel surface">
      <div class="panel-head">
        <h3 class="panel-title font-serif">工单状态汇总</h3>
        <span class="panel-sub">预警工单按状态机 6 态分布</span>
      </div>
      <div class="chart-box chart-box--bar">
        <VChart v-if="store.alertSum.length > 0" :option="alertOption" autoresize />
        <div v-else-if="firstLoading" class="chart-skeleton">
          <a-skeleton active :title="false" :paragraph="{ rows: 4 }" />
        </div>
        <a-empty v-else class="chart-empty" description="工单汇总接口未返回数据" />
      </div>
    </div>

    <!-- 去标识化图注（设计稿要求：图表下方必须声明口径） -->
    <p class="deid-note">
      <SafetyCertificateOutlined class="deid-icon" />
      去标识化说明：以上图表均由后端聚合查询生成，仅返回计数与均值，
      不含居民姓名、手机号、账号 ID 等可定位到个人的信息；管理端不具备查看个案诊疗记录的权限。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import StatCard from '@/components/StatCard.vue'
import { useDashboardStore } from '@/stores/dashboard'
import {
  ALERT_STATUS_DESC,
  RISK_LEVEL_DESC,
  type AlertStatus,
  type RiskLevel,
} from '@/types'

// 按需注册，避免把整个 echarts 打进包里
use([CanvasRenderer, LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

const store = useDashboardStore()

/**
 * 首次加载中（从未成功加载过）。
 *
 * 用来区分「正在加载」与「确实没数据」—— 两者都是空数组，
 * 若不区分，首屏会先把 loading 误报成 a-empty 的「接口未返回数据」，
 * 用户以为坏了，其实只是还没回来。
 */
const firstLoading = computed(() => store.loading && !store.loaded)

/** 调色板统一取自 tokens.css，禁止使用 ECharts 默认配色 */
const palette = ref(readPalette())

function readPalette() {
  const cs = getComputedStyle(document.documentElement)
  const v = (name: string, fallback: string) => cs.getPropertyValue(name).trim() || fallback
  return {
    primary: v('--primary', '#2563eb'),
    riskA: v('--risk-a', '#12b0a0'),
    riskB: v('--risk-b', '#f09a0a'),
    riskC: v('--risk-c', '#f76a1a'),
    riskD: v('--risk-d', '#e43b3b'),
    text2: v('--text-2', '#47536a'),
    text3: v('--text-3', '#64748b'),
    border: v('--card-border', '#d5dfeb'),
    cardBg: v('--card-bg', '#ffffff'),
  }
}

/** #2563eb + 0.18 → rgba(37, 99, 235, 0.18)；ECharts 对 8 位十六进制支持不稳，统一转 rgba */
function alpha(hex: string, a: number): string {
  const h = hex.replace('#', '')
  if (h.length !== 6) return hex
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${a})`
}

/** 工单状态 → 色值，与 AlertStatusChip 的语义映射保持一致 */
function statusColor(s: AlertStatus): string {
  const p = palette.value
  switch (s) {
    case 'PENDING':
      return p.riskB
    case 'CONFIRMED':
      return p.riskC
    case 'PROCESSING':
      return p.primary
    case 'FOLLOWED':
      return p.riskA
    default:
      return p.text3
  }
}

const RISK_COLOR: Record<RiskLevel, () => string> = {
  A: () => palette.value.riskA,
  B: () => palette.value.riskB,
  C: () => palette.value.riskC,
  D: () => palette.value.riskD,
}

const cards = computed(() => [
  {
    label: '服务居民数',
    value: store.stats?.userCount ?? 0,
    unit: '人',
    hint: '仅统计居民账号',
  },
  {
    label: '累计测评量',
    value: store.stats?.assessmentCount ?? 0,
    unit: '次',
    hint: '历史总量',
  },
  {
    label: '待处理预警',
    value: store.stats?.pendingAlertCount ?? 0,
    unit: '件',
    hint: '状态为待确认',
  },
  {
    label: '今日新增测评',
    value: store.stats?.todayNew ?? 0,
    unit: '次',
    hint: '今日零点起算',
  },
])

const riskTotal = computed(() => store.riskDist.reduce((sum, r) => sum + r.count, 0))

/** 坐标轴与网格的公共样式，集中一处避免三张图各写一遍 */
function axisTheme() {
  const p = palette.value
  return {
    axisLabel: { color: p.text3, fontSize: 11 },
    axisLine: { lineStyle: { color: p.border } },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: p.border, type: 'dashed' as const } },
  }
}

const trendOption = computed(() => {
  const p = palette.value
  const t = axisTheme()
  const dates = store.trend.map((d) => d.date.slice(5))
  return {
    color: [p.primary, p.riskB],
    grid: { left: 4, right: 4, top: 44, bottom: 0, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: {
      right: 0,
      top: 0,
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: p.text2, fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLabel: t.axisLabel,
      axisLine: t.axisLine,
      axisTick: t.axisTick,
    },
    yAxis: [
      {
        type: 'value',
        name: '测评量',
        nameTextStyle: { color: p.text3, fontSize: 11 },
        axisLabel: t.axisLabel,
        splitLine: t.splitLine,
      },
      {
        type: 'value',
        name: '平均分',
        nameTextStyle: { color: p.text3, fontSize: 11 },
        axisLabel: t.axisLabel,
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '测评量',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: store.trend.map((d) => d.count),
        lineStyle: { width: 2.5 },
        itemStyle: { color: p.primary },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: alpha(p.primary, 0.2) },
              { offset: 1, color: alpha(p.primary, 0) },
            ],
          },
        },
      },
      {
        name: '平均分',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        /** 无测评的日期 avgScore 为 null，连起来避免折线断裂 */
        connectNulls: true,
        symbol: 'circle',
        symbolSize: 5,
        data: store.trend.map((d) => d.avgScore),
        lineStyle: { width: 2, type: 'dashed' },
        itemStyle: { color: p.riskB },
      },
    ],
  }
})

const riskOption = computed(() => {
  const p = palette.value
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 次（{d}%）' },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: p.text2, fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['56%', '78%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: p.cardBg, borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scale: true, scaleSize: 6 },
        data: store.riskDist.map((r) => ({
          name: `${r.riskLevel} ${RISK_LEVEL_DESC[r.riskLevel]}`,
          value: r.count,
          itemStyle: { color: RISK_COLOR[r.riskLevel]?.() ?? p.text3 },
        })),
      },
    ],
  }
})

const alertOption = computed(() => {
  const p = palette.value
  const t = axisTheme()
  return {
    grid: { left: 4, right: 4, top: 28, bottom: 0, containLabel: true },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'category',
      data: store.alertSum.map((s) => ALERT_STATUS_DESC[s.status]),
      axisLabel: t.axisLabel,
      axisLine: t.axisLine,
      axisTick: t.axisTick,
    },
    yAxis: {
      type: 'value',
      axisLabel: t.axisLabel,
      splitLine: t.splitLine,
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 40,
        itemStyle: { borderRadius: [6, 6, 0, 0] },
        label: {
          show: true,
          position: 'top',
          color: p.text2,
          fontSize: 11,
          fontFamily: 'JetBrains Mono, monospace',
        },
        data: store.alertSum.map((s) => ({
          value: s.count,
          itemStyle: { color: statusColor(s.status) },
        })),
      },
    ],
  }
})

function reload() {
  palette.value = readPalette()
  void store.loadAll(30)
}

onMounted(() => {
  void store.loadAll(30)
})
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---- 工具条 ---- */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.scope-note {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}

.scope-icon {
  color: var(--risk-a);
  font-size: 15px;
}

.fail-alert {
  border-radius: var(--radius-sm);
}

/* ---- 指标栅格 ---- */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

/* ---- 图表栅格 ---- */
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
}

.panel {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--card-border);
}

.panel-title {
  font-size: 16px;
  color: var(--text-1);
  margin: 0;
}

.panel-sub {
  font-size: 12px;
  color: var(--text-3);
}

/* 图表必须给定高度，ECharts 依赖容器尺寸 */
.chart-box {
  position: relative;
  height: 288px;
  min-height: 0;
}

.chart-box--bar {
  height: 236px;
}

.chart-box .echarts {
  width: 100%;
  height: 100%;
}

.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

/* 图表加载占位：撑满 chart-box，保证数据到达前后高度一致不跳动 */
.chart-skeleton {
  display: flex;
  flex-direction: column;
  justify-content: center;
  height: 100%;
  padding: 0 8px;
}

/* ---- 环形图中心文案 ---- */
.donut-center {
  position: absolute;
  left: 50%;
  top: 44%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  pointer-events: none;
}

.donut-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--text-1);
}

.donut-label {
  font-size: 12px;
  color: var(--text-3);
}

/* ---- 去标识化图注 ---- */
.deid-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  border: 1px solid var(--card-border);
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-3);
}

.deid-icon {
  color: var(--risk-a);
  font-size: 14px;
  margin-top: 2px;
  flex: none;
}

@media (max-width: 1180px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .chart-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
