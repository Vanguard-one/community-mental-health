<template>
  <div class="page-container">
    <!--
      back 用 PageHeader 内置的返回按钮，但**必须接管 @back**（见 script 里的说明）：
      它默认走 router.back()，而本页在刷新后没有可回退的历史记录。
    -->
    <PageHeader
      title="测评历史"
      subtitle="按量表分栏的测评课表 —— 横轴是量表，纵轴是危机程度"
      back
      @back="goHome"
    >
      <!--
        时间范围切换。用原生 button 而不是 a-radio-group / a-segmented：
        两个组件都要吃 vendor-antd 预算（硬约定 #9），而这里只是三个互斥按钮。
      -->
      <template #extra>
        <div class="range-switch" role="group" aria-label="时间范围">
          <button
            v-for="r in RANGES"
            :key="r"
            type="button"
            class="rs-btn"
            :class="{ on: rangeDays === r }"
            @click="rangeDays = r"
          >
            {{ r }} 天
          </button>
        </div>
      </template>
    </PageHeader>

    <ListSkeleton v-if="loading" :count="3" />

    <!--
      空态：与通知页同一套轻量空态（--panel-bg 圆标 + 一句说明 + 主按钮）。
      换掉 a-empty 的默认插画 —— 那套灰线框图形不随 token 走色，
      是全页唯一一处落在设计系统之外的图形语言。
      动效按元素语义分开：圆标 pop、两行文字 rise、按钮 chrome。
    -->
    <section v-else-if="assessments.length === 0" v-reveal="'stagger'" class="empty-state">
      <span class="empty-mark" data-anim="pop" aria-hidden="true"><FileSearchOutlined /></span>
      <p class="empty-title" data-anim="rise">还没有测评记录</p>
      <p class="empty-desc" data-anim="rise">完成一次自评后，这里会按「量表 × 危机程度」排出一张测评课表。</p>
      <button type="button" class="empty-btn" data-anim="chrome" @click="router.push('/scales')">
        去做第一次测评
      </button>
    </section>

    <!--
      课表区。整块只挂一个 v-reveal：说明句走 rise，课表走 chrome。
      ⚠️ 课表刻意**不用** card / rise 这类带位移与缩放的 kind，只给纯淡入：
        列头是 sticky，给它的祖先挂 transform 等于多插一层坐标系，
        纯淡入（chrome 的 x/y/s 全是恒等值）完全回避这个变量。
    -->
    <section v-else v-reveal="'stagger'" class="board-wrap">
      <p class="board-meta" data-anim="rise">
        最近 <b>{{ rangeDays }}</b> 天共 <b>{{ recentCount }}</b> 次测评
        <span v-if="recentCount === 0" class="bm-hint">
          （这段时间没有记录，可切换到更长的时间范围）
        </span>
      </p>

      <div class="board-card" data-anim="chrome">
        <!--
          列头与数据体拆成两个网格，但共用同一份 grid-template-columns，
          所以列宽永远对齐。这么做是为了让列头能 sticky —— 若把列头和数据行
          放进同一个网格，它就得靠 .board-card 的 overflow:hidden 去收圆角，
          而 overflow 不为 visible 的祖先会让 sticky 失效。
        -->
        <div class="board-head" :style="gridStyle">
          <div class="th-cell th-corner">危机程度</div>
          <!--
            列头 = 后端给的 Scale.code + 量表名。两行堆叠而不是并排：
            量表名形如「抑郁自评量表（PHQ-9）」，并排会把列宽顶出去。
          -->
          <div v-for="c in columns" :key="c.id" class="th-cell th-col" :title="c.name">
            <div class="th-row">
              <span class="th-code mono">{{ c.short }}</span>
              <span class="th-sub mono">共 {{ c.count }} 次</span>
            </div>
            <span class="th-name">{{ c.name }}</span>
          </div>
        </div>

        <div class="board-body" :style="gridStyle">
          <template v-for="row in rows" :key="row.key">
            <div
              class="bd-cell bd-axis"
              :class="`tone-${row.tone}`"
              :title="`本行合并了 ${row.via}`"
            >
              <span class="bd-tier">{{ row.name }}</span>
              <span class="bd-tier-sub">{{ row.count }} 次</span>
            </div>

            <div
              v-for="cell in row.cells"
              :key="cell.colId"
              class="bd-cell bd-slot"
              :class="{ 'is-empty': cell.items.length === 0 }"
            >
              <!--
                整张卡片即按钮：课表里格子很小，再塞一个「查看表信息」小按钮
                会把卡片撑乱，也浪费掉本可以整块点击的面积。
                卡片内只有 span（RiskLevelTag 也渲染成 span），不会踩到
                button 只允许短语内容的限制。
              -->
              <button
                v-for="a in cell.items"
                :key="a.id"
                type="button"
                class="tt-card"
                :class="`lv-${a.riskLevel}`"
                :disabled="detailLoadingId === a.id"
                @click="openDetail(a)"
              >
                <span class="tt-top">
                  <RiskLevelTag :level="a.riskLevel" />
                  <span v-if="a.crisisFlag === 1" class="tt-crisis" title="危机项阳性">危机项</span>
                  <!--
                    右上角是**日期**（月.日）而不是原来的时间：纵轴不再是日期，
                    卡片的位置不再表示「哪天测的」，必须自己带出来。
                    完整时间没丢，挂在 title 上。
                  -->
                  <span class="tt-date mono" :title="`测评时间 ${formatTime(a.createdAt)}`">{{ mdOf(a.createdAt) }}</span>
                </span>
                <span class="tt-score">
                  <b class="mono">{{ a.totalScore }}</b>
                  <i>分</i>
                  <em>{{ detailLoadingId === a.id ? '加载中…' : '查看明细' }}</em>
                </span>
              </button>
            </div>
          </template>
        </div>
      </div>
    </section>

    <!--
      逐题作答弹窗。用 a-modal（组件形式）而不是 Modal.confirm：
      后者是确认框，没有可承载列表的内容区。这里要用**自定义内容**，
      必须用组件。a-modal 由 unplugin-vue-components 按需注入，不进 vendor-antd 预算。
    -->
    <a-modal
      v-model:open="detailOpen"
      :title="detailTitle"
      :footer="null"
      :width="640"
      class="detail-modal"
    >
      <div v-if="detail" class="detail-body">
        <!-- 卷头：与卡片上是同一份数据，避免用户在弹窗里再对一次数 -->
        <div class="detail-head">
          <div class="detail-head-main">
            <span class="detail-scale">{{ detail.scaleName }}</span>
            <RiskLevelTag :level="detail.riskLevel" />
          </div>
          <div class="detail-head-meta mono">
            总分 {{ detail.totalScore }} · {{ detail.rawLevel }} ·
            {{ formatTime(detail.completedAt) }}
          </div>
        </div>

        <p v-if="detail.crisisFlag === 1" class="detail-crisis">
          本次测评存在危机项阳性，系统已为你转接人工，也可随时拨打 12356 心理援助热线。
        </p>

        <!-- 逐题明细：题干 + 用户所选 + 该题得分 -->
        <ol v-if="detail.answers.length" class="detail-list">
          <li
            v-for="ans in detail.answers"
            :key="ans.itemNo"
            class="detail-item"
            :class="{ 'is-crisis': ans.crisisFlag === 1 }"
          >
            <span class="di-no mono">{{ ans.itemNo }}</span>
            <div class="di-body">
              <p class="di-q">{{ ans.question || '（题干缺失）' }}</p>
              <p class="di-a">
                <span class="di-a-label">你的选择</span>
                <span class="di-a-text">{{ ans.answer || '未作答' }}</span>
                <span class="di-score mono">+{{ ans.score ?? 0 }}</span>
              </p>
            </div>
          </li>
        </ol>
        <p v-else class="detail-empty">这条记录没有逐题明细（可能是早期数据）。</p>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { FileSearchOutlined } from '@ant-design/icons-vue'
import { getAssessmentRecord, getHistory, getScaleList } from '@/api/assessment'
import PageHeader from '@/components/PageHeader.vue'
import ListSkeleton from '@/components/ListSkeleton.vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import type { Assessment, AssessmentRecordVO, RiskLevel, Scale } from '@/types'

const router = useRouter()

const loading = ref(true)
const assessments = ref<Assessment[]>([])
const scaleMap = ref<Record<number, string>>({})
/** 有序量表列表 —— 决定课表的列顺序（即后端返回顺序） */
const scaleList = ref<Scale[]>([])

/** 正在拉逐题明细的那条记录 id；非空即表示有请求在飞 */
const detailLoadingId = ref<number | null>(null)
const detailOpen = ref(false)
const detail = ref<AssessmentRecordVO | null>(null)

const detailTitle = ref('测评明细')

/* ============ 课表视图 ============ */

const RANGES = [7, 14, 30]
/** 默认 14 天：够看清两周内的测评节奏，又不至于长到需要频繁滚动 */
const rangeDays = ref(14)

interface Col {
  id: number
  /** 全名，用于 hover 提示 */
  name: string
  /** 列头显示名 —— 优先取量表名里的英文缩写 */
  short: string
  count: number
}

interface Cell {
  colId: number
  items: Assessment[]
}

interface Tier {
  key: string
  name: string
  tone: 'high' | 'mid' | 'low'
  /** 归入本档的原始风险等级 */
  levels: RiskLevel[]
  via: string
}

interface Row {
  key: string
  /** 行标题，如「高风险」 */
  name: string
  /** 行首色条与行标题取色用的语义名，对应下面的 .tone-* 三个类 */
  tone: Tier['tone']
  /** 悬停提示：说清这一行由哪些原始等级合并而来 */
  via: string
  /** 本行在时间范围内的测评条数 */
  count: number
  cells: Cell[]
}

/**
 * 课表纵轴的一档「危机程度」。
 *
 * <p>后端 `RiskLevel` 是 A 低风险 / B 中风险 / C 高风险 / D 危机**四档**，
 * 课表纵轴只给三行 —— 把 C 与 D 合并成「高风险」：两者在处置上是同一类
 * （都要立即人工介入），而精确等级仍由格子里每张卡片上的 `RiskLevelTag` 显示，
 * 信息一点没丢，只是行标签粗一档。
 *
 * <p>顺序按严重度**从高到低**：这张表是用来「一眼扫出该关注的那几条」的，
 * 最需要看的一档放最上面。
 */
const TIERS: Tier[] = [
  { key: 'high', name: '高风险', tone: 'high', levels: ['C', 'D'], via: 'C 高风险 + D 危机' },
  { key: 'mid', name: '中风险', tone: 'mid', levels: ['B'], via: 'B 中风险' },
  { key: 'low', name: '低风险', tone: 'low', levels: ['A'], via: 'A 低风险' },
]

function pad(n: number): string {
  return `${n}`.padStart(2, '0')
}

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function scaleName(scaleId: number): string {
  return scaleMap.value[scaleId] || `量表 #${scaleId}`
}

/**
 * 日期时间串 → 月.日（不带前导零）。例：`2026-06-11T09:32:00` → `6.11`
 *
 * <p>纵轴从「日期」改成「危机程度」之后，卡片在网格里的**位置**不再表示日期，
 * 每张卡必须自己把日期带出来 —— 否则同一格内跨多天的记录分不清先后。
 * 只给到「月日」：横向空间被量表列数分完，卡片右上角放不下年份，
 * 而跨年的记录在这个区间里极罕见；完整时间留在 `title` 上。
 */
function mdOf(s: string): string {
  if (!s || s.length < 10) return ''
  const mo = Number(s.slice(5, 7))
  const day = Number(s.slice(8, 10))
  // 脏数据（空串 / '0000-00-00'）落到这里，别让 'NaN.NaN' 上屏
  if (!mo || !day) return ''
  return `${mo}.${day}`
}

function formatTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

/**
 * 量表列头显示名。
 *
 * <p>量表全名形如「抑郁自评量表（PHQ-9）」，铺在列头上会把列挤变形。
 * 优先用后端给的 `code`（就是 PHQ9 / GAD7 这类短码），没有才从全名里
 * 摘括号中的英文编号，再没有才截断。
 * 不写死任何量表 id 或名称 —— 量表是数据，不是常量。
 */
function shortName(s: Scale): string {
  if (s.code) return s.code
  const m = s.name.match(/[(（]([A-Za-z0-9][A-Za-z0-9-]{0,11})[)）]/)
  if (m) return m[1]
  return s.name.length > 7 ? `${s.name.slice(0, 7)}…` : s.name
}

/**
 * 时间范围内的记录。
 *
 * <p>纵轴从「日期」改成「危机程度」之后，范围过滤必须**显式**做一次 ——
 * 原来的实现是「只生成最近 N 行日期」，过滤是排版的副产物；
 * 现在行不再代表日期，不显式过滤就会把全部历史都铺进格子。
 */
const rangeItems = computed<Assessment[]>(() => {
  const from = new Date()
  from.setHours(0, 0, 0, 0)
  from.setDate(from.getDate() - (rangeDays.value - 1))
  const min = dayKey(from) // ISO 日期串的字典序即时间序，直接比字符串即可
  return assessments.value.filter((a) => {
    const k = (a.createdAt || '').slice(0, 10)
    if (k.length !== 10) return false // 脏数据（createdAt 空/格式异常）直接跳过
    return k >= min
  })
})

const columns = computed<Col[]>(() => {
  const list: Col[] = scaleList.value.map((s) => ({
    id: s.id,
    name: s.name,
    short: shortName(s),
    count: 0,
  }))

  /*
   * 记录里引用了、但量表列表里找不到的 scaleId（量表被删或停用）→ 补一列。
   * 不补的话这些记录会在课表里凭空消失 —— 而它们恰恰是「异常数据」，
   * 最不该被静默丢掉。
   */
  const known = new Set(list.map((c) => c.id))
  for (const a of rangeItems.value) {
    if (known.has(a.scaleId)) continue
    known.add(a.scaleId)
    list.push({
      id: a.scaleId,
      name: scaleName(a.scaleId),
      short: `#${a.scaleId}`,
      count: 0,
    })
  }

  /*
   * 计数只统计**时间范围内**的记录。
   * 整张表本来就只呈现最近 N 天，列头若报全量次数，会出现
   * 「写着共 5 次、格子里一个都没有」的矛盾观感。
   */
  const tally = new Map<number, number>()
  for (const a of rangeItems.value) {
    tally.set(a.scaleId, (tally.get(a.scaleId) ?? 0) + 1)
  }
  for (const c of list) c.count = tally.get(c.id) ?? 0

  return list
})

/*
 * 每行 = 一档危机程度，每格 = 「该档 × 某张量表」在时间范围内的全部记录。
 *
 * ⚠️ 同一格现在可能装下**跨多天**的多条记录（原来一格最多只装一天的量），
 *    所以卡片自己必须带日期；排序按时间倒序，最新的在最上面。
 */
const rows = computed<Row[]>(() =>
  TIERS.map((t) => {
    const items = rangeItems.value.filter((a) => t.levels.includes(a.riskLevel))
    return {
      key: t.key,
      name: t.name,
      tone: t.tone,
      via: t.via,
      count: items.length,
      cells: columns.value.map((c) => ({
        colId: c.id,
        items: items
          .filter((a) => a.scaleId === c.id)
          .sort((x, y) => (y.createdAt || '').localeCompare(x.createdAt || '')),
      })),
    }
  }),
)

/** 当前时间范围内的测评条数 —— 为 0 时给一句提示，避免用户对着一屏空网格发愣 */
const recentCount = computed(() => rangeItems.value.length)

/*
 * 列宽用 JS 拼字符串而不是 CSS 的 repeat(var(--cols), …)：
 * repeat() 的计数位要吃 <integer>，塞 CSS 变量在部分浏览器上解析不稳定，
 * 直接由 computed 产出完整值最省心。
 */
const gridStyle = computed(() => ({
  gridTemplateColumns: `86px repeat(${columns.value.length}, minmax(0, 1fr))`,
}))

/* ============ 交互 ============ */

/**
 * 返回首页。
 *
 * <p>不走 `PageHeader` 默认的 `router.back()`：本页现在是**单入口**页面，
 * 唯一入口是首页的「全部记录 →」。但「唯一入口」不等于「一定是从首页来的」——
 * 用户可以在本页按 F5 刷新，此时浏览器里没有可回退的记录，`router.back()`
 * 会把他带出站点（或原地不动）。刷新后仍然要能回去，所以显式写死跳首页。
 */
function goHome() {
  router.push('/home')
}

/**
 * 打开某条记录的逐题明细。
 *
 * <p>按需拉取而不是列表接口一次带回来：逐题明细包含每一道的题干与选项文案，
 * 一条记录就是几十行文本。列表通常只有几条到几十条，全量预取会让首屏
 * 多背一份几倍于自身的数据 —— 而绝大多数条目用户根本不会展开。
 */
async function openDetail(a: Assessment) {
  if (detailLoadingId.value !== null) return
  detailLoadingId.value = a.id
  detail.value = null
  try {
    detail.value = await getAssessmentRecord(a.id)
    detailTitle.value = `${scaleName(a.scaleId)} · 测评明细`
    detailOpen.value = true
  } catch {
    // 拦截器已统一提示（403 越权 / 404 已删）；这里只负责不弹出一个空弹窗
    message.error('获取测评明细失败，请稍后重试')
  } finally {
    detailLoadingId.value = null
  }
}

onMounted(async () => {
  // 两个请求互不依赖：量表名拿不到不该让整页失败，退化为「量表 #id」即可
  const [history, scales] = await Promise.allSettled([getHistory(), getScaleList()])
  if (scales.status === 'fulfilled') {
    scaleList.value = scales.value ?? []
    scaleMap.value = Object.fromEntries(scaleList.value.map((s) => [s.id, s.name]))
  }
  if (history.status === 'fulfilled') {
    assessments.value = history.value ?? []
  }
  loading.value = false
})
</script>

<style scoped>
/* ---------- 时间范围切换 ---------- */
.range-switch {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border-radius: var(--radius-pill);
  background: var(--panel-bg);
}

.rs-btn {
  padding: 5px 14px;
  /* 常态描边透明、选中态只换色 —— 用边框而不是阴影做选中态：
     分段控件在卡内，卡内再叠阴影会与白卡的投影打架（面分级规则 ②）。
     透明边框同时保证了选中/未选中两种状态下按钮尺寸完全一致，切换不抖动。 */
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 13px;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.rs-btn:hover {
  color: var(--text-1);
}

/* 选中态：白底 + --primary 文字（白面上主色 5.17:1，达 AA）。
   轨道是 --panel-bg，白底与它只差一档亮度，所以补一条 --card-border 描边把边界说清。 */
.rs-btn.on {
  background: var(--card-bg);
  border-color: var(--card-border);
  color: var(--primary);
  font-weight: 600;
}

/* ---------- 概览句 ---------- */
.board-meta {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-2);
}

.board-meta b {
  color: var(--text-1);
}

.bm-hint {
  /* 与 PageHeader 的 subtitle 同级：直接裸落页面底，--text-3 在那只有 3.60:1 */
  color: var(--text-2);
}

/* ---------- 空态 ---------- */
/*
 * 轻量空态：图标圆标（--panel-bg）+ 一句说明 + 一个主按钮。
 * 整块是一张 L1 白卡 —— 直接贴在页面底上的必须是白卡或主色（面分级规则 ①），
 * 所以图标圆标不能直接裸落在页面底上。
 * 说明文字落在白面上，--text-3 的 4.8:1 成立。
 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 44px 22px;
  text-align: center;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.empty-mark {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
  border-radius: 50%;
  background: var(--panel-bg);
  color: var(--text-2);
  font-size: 24px;
}

.empty-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.empty-desc {
  margin: 0;
  max-width: 36em;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-3);
}

/* 主按钮：原生 button 而不是 a-button —— 本批改造不允许新增任何 a-* 标签（预算硬约束）。
   视觉对齐 a-button type=primary：--primary 实底 + --on-primary 白字（5.17:1）+ 控件圆角。 */
.empty-btn {
  margin-top: 16px;
  padding: 9px 22px;
  border: none;
  border-radius: var(--radius-ctrl);
  background: var(--primary);
  color: var(--on-primary);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  cursor: pointer;
  box-shadow: var(--shadow-primary);
  transition: background var(--dur) var(--ease);
}

.empty-btn:hover {
  background: var(--primary-dark);
}

/* ---------- 课表外壳 ---------- */
/*
 * 外层用 L1 白卡收圆角与阴影，内部网格保持直角 —— 表格类内容直角更好扫读，
 * 而圆角由外卡统一提供，与全站的面语言一致。
 * ⚠️ 这里**不能**加 overflow: hidden 去裁圆角：内部列头要 sticky，
 *    而 overflow 不为 visible 的祖先会让 sticky 直接失效。
 */
.board-card {
  padding: 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
}

/* ---------- 列头 ---------- */
.board-head {
  display: grid;
  gap: 1px;
  /* 1px 的 gap 露出底色 = 网格线；下同 */
  background: var(--card-border);
  padding-bottom: 1px;
  position: sticky;
  top: 0;
  z-index: 2;
}

.th-cell {
  background: var(--panel-bg);
  padding: 8px 10px;
  min-width: 0;
}

.th-corner {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  /* 同上：写在 --panel-bg 上的小字一律走 --text-2，--text-3 只有 4.27:1 */
  color: var(--text-2);
}

.th-col {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.th-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.th-code {
  font-size: 14px;
  font-weight: 600;
  /* 表头整条压在 --panel-bg 上，一律走 --text-2（6.7:1）；
     --text-3 落在这个面上只有 4.27:1，不达 AA。层级靠字号与字重拉开，不靠再压深颜色。 */
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.th-name {
  min-width: 0;
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.th-sub {
  flex: none;
  font-size: 11.5px;
  color: var(--text-2);
}

/* ---------- 数据体 ---------- */
.board-body {
  display: grid;
  gap: 1px;
  background: var(--card-border);
}

.bd-cell {
  min-width: 0;
  min-height: 66px;
  background: var(--card-bg);
  padding: 6px;
}

/*
 * 纵轴格 = 一档危机程度。
 * 底色取该档的 --risk-*-soft、标题取同族 --risk-*-ink —— 这正是 token 里那两档
 * 配套色的既定用法（那边注释写明了是「soft 底 + 深一档同色文字」）。
 * 四个原始等级合并成三档后，按该档**最重**的那一档取色：高 = D、中 = B、低 = A。
 * ⚠️ 实色 --risk-d / --risk-b / --risk-a 只出现在左侧 3px 色条上：它是有形状的色块、
 *    不承载文字，用实色才够醒目（与 RiskLevelTag 里圆点用实色是同一判据）。
 */
.bd-axis {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 6px 4px;
  background: var(--panel-bg);
  /* 左侧留一条透明边：它是行首色条的位置。
     所有轴格都留，有档位的格子只换色 —— 否则有色的那几格内容会右移 3px，整列错位。 */
  border-left: 3px solid transparent;
}

.bd-axis.tone-high {
  background: var(--risk-d-soft);
  border-left-color: var(--risk-d);
}

.bd-axis.tone-mid {
  background: var(--risk-b-soft);
  border-left-color: var(--risk-b);
}

.bd-axis.tone-low {
  background: var(--risk-a-soft);
  border-left-color: var(--risk-a);
}

/*
 * 行标题取同族 ink。实测在对应 soft 底上分别是
 * --risk-d-ink 5.77 / --risk-b-ink 4.74 / --risk-a-ink 4.73 —— 都过 AA 的 4.5。
 * ⚠️ 这里刻意**不用** --text-1（虽然它最稳，16.8:1）：三行标题若长得一样，
 *    纵轴的语义就只剩左边那 3px 色条了。这与「卡片里分数取 --text-1」不矛盾 ——
 *    那边颜色已经由边条 + RiskLevelTag 承担，这边颜色需要能被**读出来**。
 */
.bd-axis.tone-high .bd-tier {
  color: var(--risk-d-ink);
}

.bd-axis.tone-mid .bd-tier {
  color: var(--risk-b-ink);
}

.bd-axis.tone-low .bd-tier {
  color: var(--risk-a-ink);
}

.bd-tier {
  /* 兜底：万一行没挂上 tone 类（不该发生），至少还是深色可读的 */
  color: var(--text-1);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
}

.bd-tier-sub {
  font-size: 11px;
  line-height: 1;
  /* 计数是次要信息，走 --text-2：它落在三个 soft 底上都在 6.5:1 以上 */
  color: var(--text-2);
}

.bd-slot {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

/*
 * 空档：极淡的斜线纹理。
 * 参考图里的课表空位就是这种 hatch 填充 —— 它比纯白更能表达「这一格是空的」，
 * 而不是「还没加载出来」。线宽 1px、间隔 7px，色值走 --card-border 的 46%，
 * 落在白卡上大约 1.1:1，有质感但不抢戏。
 */
.bd-slot.is-empty {
  background-image: repeating-linear-gradient(
    45deg,
    transparent 0 6px,
    color-mix(in srgb, var(--card-border) 46%, transparent) 6px 7px
  );
}

/* ---------- 测评卡片 ---------- */
.tt-card {
  /*
   * 三个变量的兜底值。正常情况下必被下面的 .lv-* 覆盖（riskLevel 是
   * 'A'|'B'|'C'|'D' 四值枚举），但一旦后端返回意料之外的等级，
   * 有兜底就只是「颜色不对」，没有兜底会整块卡片失去背景与描边。
   */
  --tt-key: var(--primary);
  --tt-bg: var(--panel-bg);
  --tt-line: var(--card-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 7px 9px;
  text-align: left;
  font-family: inherit;
  border: 1px solid var(--tt-line);
  border-left: 3px solid var(--tt-key);
  border-radius: var(--radius-sm);
  background: var(--tt-bg);
  color: var(--text-1);
  cursor: pointer;
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.tt-card:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: var(--shadow-card);
}

.tt-card:disabled {
  cursor: progress;
  opacity: 0.72;
}

/*
 * 四色阶映射。色值只引 --risk-a/b/c/d 与配套浅底（硬约定 #4/#49），
 * 组件内不出现任何十六进制字面量。
 */
.tt-card.lv-A {
  --tt-key: var(--risk-a);
  --tt-bg: var(--risk-a-soft);
  --tt-line: color-mix(in srgb, var(--risk-a) 22%, transparent);
}
.tt-card.lv-B {
  --tt-key: var(--risk-b);
  --tt-bg: var(--risk-b-soft);
  --tt-line: color-mix(in srgb, var(--risk-b) 22%, transparent);
}
.tt-card.lv-C {
  --tt-key: var(--risk-c);
  --tt-bg: var(--risk-c-soft);
  --tt-line: color-mix(in srgb, var(--risk-c) 22%, transparent);
}
.tt-card.lv-D {
  --tt-key: var(--risk-d);
  --tt-bg: var(--risk-d-soft);
  --tt-line: color-mix(in srgb, var(--risk-d) 26%, transparent);
}

.tt-top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

/*
 * 危机项：实心红字底块，比 RiskLevelTag 更抢眼 —— 它是要立刻看见的那种信息。
 * 底色取 --risk-d-ink（同色阶深一档）而不是 --risk-d：白字压在 --risk-d (#e43b3b) 上
 * 只有 4.21:1，10.5px 的小字达不到 AA 的 4.5；--risk-d-ink 上是 6.23:1。
 * 文字色走 --on-primary —— token 里唯一的「实心底上的文字」档，组件内不写十六进制。
 */
.tt-crisis {
  flex: none;
  padding: 2px 6px;
  border-radius: var(--radius-pill);
  background: var(--risk-d-ink);
  color: var(--on-primary);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.3;
}

/* 卡片右上角的日期（月.日）。原来放的是时分 —— 位置语义变了，见模板里的说明 */
.tt-date {
  flex: none;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-2);
}

.tt-score {
  display: flex;
  align-items: baseline;
  gap: 3px;
}

.tt-score b {
  font-size: 17px;
  line-height: 1.1;
  /*
   * 分数用 --text-1，**不用 --tt-key**（风险色）。
   * 实测：--risk-a 落在 --risk-a-soft 上只有 2.49:1，四个等级里最好的
   * （--risk-d / --risk-d-soft）也只有 3.94:1，17px 粗体够不到「大字体」豁免，
   * 都低于 AA 的 4.5。风险色改由左侧 3px 边条与 RiskLevelTag 承担 ——
   * 边条是纯装饰不承载文字，标签本身是既有组件的既定用法。
   * 这也是参考图的做法：卡片标题深色、颜色只出现在标签上。
   */
  color: var(--text-1);
}

.tt-score i {
  font-style: normal;
  font-size: 11px;
  color: var(--text-2);
}

/* 「查看明细」平时是淡的，悬停在卡片上才提亮 —— 不用单独占一个按钮位 */
.tt-score em {
  margin-left: auto;
  font-style: normal;
  font-size: 11.5px;
  /* --text-2 而不是 --text-3：后者落在 --risk-*-soft 上只有 4.44:1，差一点点 */
  color: var(--text-2);
  transition: color var(--dur) var(--ease);
}

.tt-card:hover:not(:disabled) .tt-score em {
  color: var(--primary);
}

/* ---------- 窄屏 ---------- */
@media (max-width: 900px) {
  /* 列头与轴格收窄，把宽度让给卡片 */
  .board-head,
  .board-body {
    /* 由 JS 产出的 gridTemplateColumns 覆盖不了，这里用 min-width 保底：
       让课表横向可滚，而不是把 4 列压成看不清的窄条 */
    min-width: 620px;
  }

  .board-card {
    overflow-x: auto;
  }
}

/* ---------- 逐题明细弹窗 ---------- */
.detail-head {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--card-border);
}

.detail-head-main {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.detail-head-meta {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-3);
}

.detail-crisis {
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--risk-d-soft);
  color: var(--text-1);
  font-size: 13px;
  line-height: 1.6;
}

.detail-list {
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
  /* 题多时弹窗内滚动，而不是把弹窗撑到超出视口 —— 超出后按钮就点不到了 */
  max-height: 46vh;
  overflow-y: auto;
}

.detail-item {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--card-border);
}

.detail-item:last-child {
  border-bottom: none;
}

/* 危机项左侧加一条竖线，扫视时不用逐字读就能定位到要关注的那几题 */
.detail-item.is-crisis {
  padding-left: 10px;
  border-left: 3px solid var(--risk-d);
}

.di-no {
  flex: none;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--panel-bg);
  /* 圆标底是 --panel-bg，不是白面 —— 这里的字号只有 11.5px，--text-3 落上去 4.09:1 不达 AA */
  color: var(--text-2);
  font-size: 11.5px;
}

.di-body {
  min-width: 0;
  flex: 1;
}

.di-q {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-1);
}

.di-a {
  margin: 5px 0 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-2);
}

.di-a-label {
  flex: none;
  color: var(--text-3);
}

.di-a-text {
  min-width: 0;
}

.di-score {
  flex: none;
  margin-left: auto;
  color: var(--primary);
  font-weight: 600;
}

.detail-empty {
  margin: 16px 0 0;
  color: var(--text-3);
  font-size: 13px;
}
</style>
