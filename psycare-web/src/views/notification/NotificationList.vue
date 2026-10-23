<template>
  <!--
    同一个组件服务两端（居民 `/notifications`、医生 `/admin/notifications`），
    差别只在「谁来承担页面内边距」，所以用 admin 开关切根类名：
    · 居民端：DefaultLayout 的内容区**不加** padding，由 `.page-container` 承担；
    · 医生端：AdminLayout 的 `.content` 已经加了 `var(--content-pad)`，
      再套 `.page-container` 会变成 56px 的双层内边距。
  -->
  <div :class="admin ? 'nt-admin' : 'page-container narrow'">
    <PageHeader title="通知" subtitle="系统消息、预约与提醒" />

    <!-- 筛选：纯前端过滤。列表最多 50 条，为它加一个接口不划算 -->
    <nav v-if="notifications.length" v-reveal="'stagger'" class="filter-bar" aria-label="按类型筛选">
      <button
        v-for="f in FILTERS"
        :key="f.key"
        type="button"
        class="filter-chip"
        :class="{ 'is-on': filter === f.key }"
        data-anim="chrome"
        @click="filter = f.key"
      >
        {{ f.label }}
        <span v-if="countOf(f.key)" class="fc-n mono">{{ countOf(f.key) }}</span>
      </button>
    </nav>

    <ListSkeleton v-if="loading" :count="4" />

    <!--
      空态：白卡 + --panel-bg 圆标 + 一句说明 + 一个主按钮。
      整块做成 L1 白卡是因为图标圆标是浅色面（--panel-bg），
      浅色面不能直接贴页面底（与底仅约 1.04:1，等于隐形）——这是面分级规则 ①。
      动效按元素语义分开：圆标 pop、两行文字 rise、按钮 chrome，不做整块统一 fade-up。
    -->
    <section v-else-if="!notifications.length" v-reveal="'stagger'" class="empty-state">
      <span class="empty-mark" data-anim="pop" aria-hidden="true"><BellOutlined /></span>
      <p class="empty-title" data-anim="rise">还没有通知</p>
      <p class="empty-desc" data-anim="rise">完成一次心理测评或预约医生后，系统的提醒会出现在这里。</p>
      <button type="button" class="empty-btn" data-anim="chrome" @click="router.push('/scales')">
        去做测评
      </button>
    </section>

    <!--
      列表：一张 L1 白卡装若干白面行（照 Home.vue 的 .card + .rec-list li 写法）。
      每一行的结构写死四处：未读点 / 固定宽度类型标签 / 标题+时间 / 单行摘要。
      之所以「写死」，是因为通知类型有四种、摘要长度差三倍 —— 只要有一处跟着内容自适应，
      行高就会参差，列表看起来就是「乱」。详细内容去弹层看，列表只负责「知道有这么件事」。
    -->
    <div v-else v-reveal="'card'" class="notice-card">
      <p v-if="!visible.length" class="filter-empty">这个分类下暂时没有通知</p>

      <ul v-else class="notice-list">
        <li
          v-for="n in visible"
          :key="n.id"
          class="notice-row"
          :class="{ 'is-unread': n.readFlag !== 1 }"
          tabindex="0"
          role="button"
          @click="open(n)"
          @keydown.enter.prevent="open(n)"
          @keydown.space.prevent="open(n)"
        >
          <span class="unread-dot" aria-hidden="true" />

          <span class="nt-tag" :class="`is-${typeKey(n.type).toLowerCase()}`">
            {{ typeDesc(n.type) }}
          </span>

          <div class="notice-main">
            <div class="notice-head">
              <span class="notice-title">{{ n.title }}</span>
              <span class="notice-time mono">{{ formatTime(n.createdAt) }}</span>
            </div>
            <p class="notice-summary">{{ n.summary || n.content }}</p>
          </div>

          <span class="notice-arrow" aria-hidden="true">›</span>
        </li>
      </ul>
    </div>

    <!--
      详情弹层：行内只留摘要，完整正文在这里。点开即已读。
      自己画遮罩，不引 antd 的 Modal —— 整页只有这一个弹层。
    -->
    <div v-if="active" class="overlay" @click.self="close">
      <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="nt-dialog-title">
        <header class="dg-head">
          <span class="nt-tag" :class="`is-${typeKey(active.type).toLowerCase()}`">
            {{ typeDesc(active.type) }}
          </span>
          <span class="dg-time mono">{{ formatTime(active.createdAt) }}</span>
        </header>

        <h2 id="nt-dialog-title" class="dg-title">{{ active.title }}</h2>
        <!-- 后端正文用 \n 分行，pre-line 保留换行；不保留的话「医生：… \n 时间：…」会挤成一段 -->
        <p class="dg-body">{{ active.content }}</p>

        <div class="dg-ops">
          <button type="button" class="dg-btn" @click="close">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BellOutlined } from '@ant-design/icons-vue'
import { getMyNotifications, markNotificationRead } from '@/api/notification'
import PageHeader from '@/components/PageHeader.vue'
import ListSkeleton from '@/components/ListSkeleton.vue'
import { useInboxStore } from '@/stores/inbox'
import type { Notification } from '@/types'

/** 四个筛选档。系统类是兜底档：库里出现过未知 type 时不会「哪一档都看不到」 */
type FilterKey = 'ALL' | 'ALERT' | 'CONSULT' | 'APPOINTMENT' | 'SYSTEM'

const FILTERS: Array<{ key: FilterKey; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'ALERT', label: '预警' },
  { key: 'CONSULT', label: '咨询' },
  { key: 'APPOINTMENT', label: '预约' },
  { key: 'SYSTEM', label: '系统' },
]

const router = useRouter()
const inbox = useInboxStore()

/**
 * admin = true 时由外层 AdminLayout 承担内边距（详见模板顶部注释）。
 * 只有 /admin/notifications 这条路由会传它（路由 props 对象里写死）。
 */
withDefaults(defineProps<{ admin?: boolean }>(), { admin: false })

const loading = ref(true)
const notifications = ref<Notification[]>([])
const filter = ref<FilterKey>('ALL')
const active = ref<Notification | null>(null)

/** 归一化类型：未知值一律归到 SYSTEM，避免出现「既不属于任何分类」的通知 */
function typeKey(type: string | null | undefined): Exclude<FilterKey, 'ALL'> {
  switch (type) {
    case 'ALERT':
      return 'ALERT'
    case 'CONSULT':
      return 'CONSULT'
    case 'APPOINTMENT':
      return 'APPOINTMENT'
    default:
      return 'SYSTEM'
  }
}

function typeDesc(type: string): string {
  switch (typeKey(type)) {
    case 'ALERT':
      return '预警'
    case 'CONSULT':
      return '咨询'
    case 'APPOINTMENT':
      return '预约'
    default:
      return '系统'
  }
}

const visible = computed(() =>
  filter.value === 'ALL'
    ? notifications.value
    : notifications.value.filter((n) => typeKey(n.type) === filter.value),
)

function countOf(key: FilterKey): number {
  if (key === 'ALL') return notifications.value.length
  return notifications.value.filter((n) => typeKey(n.type) === key).length
}

function formatTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

/**
 * 打开详情，并把这条标为已读。
 *
 * <p>乐观更新：先把本地 readFlag 置 1，再发请求。失败**不回滚** ——
 * 用户明明点开看过了，红点又跳回来只会像 bug。
 * 真失败时下次刷新会重新显示未读，那是「能自愈的不一致」，比闪烁好。
 *
 * <p>侧栏角标同一步骤里减一（`inbox.markNoticeRead()`）：它与本行的
 * `readFlag` 是同一件事的两个显示位置，一处变了另一处不变才是真 bug。
 */
async function open(n: Notification) {
  active.value = n
  if (n.readFlag === 1) return
  n.readFlag = 1
  inbox.markNoticeRead()
  try {
    await markNotificationRead(n.id)
  } catch {
    // 尽力而为：不阻断阅读，也不回滚
  }
}

function close() {
  active.value = null
}

/** Esc 关闭弹层：键盘用户唯一能退出的方式（弹层里没有可聚焦的关闭叉） */
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && active.value) {
    e.preventDefault()
    close()
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  // 进页面先校准一次角标：store 里那个数字来自 15 秒轮询，
  // 可能是上一拍的值（比如刚在别的标签页里读过）。不 await —— 别让它拖慢列表
  inbox.refreshNotice()
  try {
    notifications.value = (await getMyNotifications()) ?? []
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
/* 医生端：外层已给内边距，这里只限制阅读宽度并居中 */
.nt-admin {
  max-width: 1040px;
  margin: 0 auto;
}

/* ---------- 筛选条 ---------- */

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.filter-chip:hover:not(.is-on) {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

/* 选中：主色实底 + 白字 5.17:1。不用 --primary-soft 填底配 --primary 字（4.44:1） */
.filter-chip.is-on {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

.fc-n {
  font-size: 11.5px;
  opacity: 0.75;
}

/* ---------- 列表卡 ---------- */

/*
 * 外层 L1 白卡：收圆角与阴影、给整页一个内容面。
 * 行本身不再各自成卡 —— 一屏十几张白卡会让描边与投影互相干扰，
 * 分栏交给行间距与悬停底色（与 Home.vue 的「最近记录」面板同一套做法）。
 */
.notice-card {
  padding: 8px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.notice-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.notice-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px 12px 12px;
  border-radius: var(--radius-ctrl);
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.notice-row:hover {
  background: var(--panel-bg);
}

.notice-row:focus-visible {
  outline: 2px solid var(--primary-dark);
  outline-offset: -2px;
}

/*
 * 行间分隔线。
 * ⚠️ 不能用 --panel-bg（透明也行不了）：行 :hover 会把行底换成 --panel-bg，
 *    分隔线用同一个值就会在悬停时「消失」，结构感随之丢掉。
 *    用 --card-border 混合 65% 透明度：未悬停时可见，悬停时仍然看得见。
 */
.notice-row + .notice-row {
  box-shadow: inset 0 1px 0 color-mix(in srgb, var(--card-border) 65%, transparent);
}

/*
 * 未读的唯一强调：一个主色小圆点。
 * 已读行保留一个同宽的空位（透明），两种状态的标签左边界始终对齐。
 */
.unread-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: transparent;
}

.notice-row.is-unread .unread-dot {
  background: var(--primary);
}

/*
 * 类型标签固定 46px。
 * 四种类型字数不同（预警/咨询/预约/系统 都是两字，但换成「问诊」「系统消息」就会差一倍），
 * 不固定列宽的话标题左边界会随类型跳动 —— 这正是「看起来乱」的第一来源。
 * 底色不用 --panel-bg：悬停时行底就是那个值，标签会融进去。
 */
.nt-tag {
  flex: none;
  width: 46px;
  padding: 3px 0;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.4;
  text-align: center;
}

.nt-tag.is-alert {
  border-color: transparent;
  background: var(--risk-b-soft);
  color: var(--risk-b-ink);
}

.nt-tag.is-appointment {
  border-color: transparent;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.notice-main {
  flex: 1;
  min-width: 0;
}

.notice-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.notice-title {
  min-width: 0;
  overflow: hidden;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 时间戳落在白面行上 —— --text-3 在白面上是 4.8:1，达标；落在 --panel-bg 上就只有 4.27 */
.notice-time {
  flex: none;
  margin-left: auto;
  color: var(--text-3);
  font-size: 11.5px;
}

/*
 * 摘要恒为一行。这是「每行总高一致」的关键 ——
 * 摘要一旦换行，长通知占 3 行、短通知占 1 行，列表立刻参差。
 * 空 summary 的老数据（v14 之前的历史通知）回落到正文开头，
 * 靠 CSS 省略而不是 JS 截断：截断会丢掉「刚好在边界上的那几个字」且不可预期。
 */
.notice-summary {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-3);
  font-size: 12.5px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-arrow {
  flex: none;
  color: var(--text-3);
  font-size: 16px;
  line-height: 1;
}

.filter-empty {
  margin: 0;
  padding: 36px 0;
  color: var(--text-3);
  font-size: 13.5px;
  text-align: center;
}

/* ---------- 空态 ---------- */

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

/* 说明落在白卡面上，--text-3 的 4.8:1 成立 */
.empty-desc {
  margin: 0;
  max-width: 36em;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-3);
}

/*
 * 主按钮：原生 button 而不是 a-button —— 本批改造不允许新增任何 a-* 标签（预算硬约束）。
 * 外观对齐 a-button type=primary：--primary 实底 + --on-primary 白字（5.17:1）+ 控件圆角。
 */
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

/* ---------- 详情弹层 ---------- */

.overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(17, 27, 43, 0.42);
}

.dialog {
  width: 100%;
  max-width: 480px;
  max-height: 80vh;
  overflow-y: auto;
  padding: 24px;
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: 0 24px 60px -24px rgba(17, 27, 43, 0.4);
}

.dg-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.dg-time {
  margin-left: auto;
  color: var(--text-3);
  font-size: 11.5px;
}

.dg-title {
  margin: 0 0 10px;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 17px;
  font-weight: 600;
}

/* 正文落白面，用 --text-2（--text-3 在白面 4.8:1 勉强达标，但正文需要更稳的对比度） */
.dg-body {
  margin: 0;
  color: var(--text-2);
  font-size: 13.5px;
  line-height: 1.85;
  white-space: pre-line;
}

.dg-ops {
  display: flex;
  justify-content: flex-end;
  margin-top: 22px;
}

.dg-btn {
  height: 36px;
  padding: 0 20px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-1);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.dg-btn:hover {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}
</style>
