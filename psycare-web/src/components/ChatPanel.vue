<template>
  <div class="chat-panel">
    <div ref="scrollEl" class="chat-body">
      <p v-if="loading" class="chat-hint">正在加载消息…</p>

      <!--
        空态：说清「接下来会发生什么」而不是只报「暂无数据」。
        对话开始前用户最容易犹豫「发出去有人看吗」，这句话比一个灰色图标有用。
      -->
      <div v-else-if="!messages.length" class="chat-empty">
        <span class="empty-mark" aria-hidden="true">
          <span class="em-dot"></span>
          <span class="em-dot"></span>
          <span class="em-dot"></span>
        </span>
        <p class="empty-title">还没有消息</p>
        <p class="empty-desc">
          {{ peerName ? `发一条消息，${peerName} 会在看到后回复你` : '发一条消息开始对话吧' }}
        </p>
      </div>

      <!-- v-for 与 v-else 不能挂在同一个元素上，必须用 template 包一层 -->
      <template v-else>
        <template v-for="(m, i) in messages" :key="m.id">
          <!-- 日期分隔条：跨天时插一条，同一天的消息不再各自带日期，读起来连贯 -->
          <p v-if="showDay(i)" class="day-sep"><span>{{ dayLabel(m.createdAt) }}</span></p>

          <div class="msg" :class="{ mine: isMine(m) }">
            <RoleAvatar class="msg-avatar" :role="isMine(m) ? myRole : peerRole" :size="40" />
            <div class="msg-main">
              <div class="bubble">{{ m.content }}</div>
              <time class="msg-time">
                {{ fmtTime(m.createdAt) }}
                <!--
                  已读回执只挂在**自己最后一条**消息下面。
                  每条都挂会变成一排重复的「未读」，而用户真正想知道的只是
                  「我最后说的那句他看到没有」—— 主流 IM 也只标最新一条。
                  只对自己发的消息显示：对方消息上的 read_at 记的是「我读它的时间」，
                  拿它给对方气泡标「未读」是错的（那不是对方没看，是没人会给它置位）。
                -->
                <span
                  v-if="isMine(m) && m.id === lastMineId"
                  class="receipt"
                  :class="{ read: m.readAt !== null }"
                  >{{ m.readAt ? '已读' : '未读' }}</span
                >
              </time>
            </div>
          </div>
        </template>
      </template>
    </div>

    <div class="chat-compose">
      <p v-if="!canSend" class="compose-closed">会话已结束，无法继续发送消息</p>

      <template v-else>
        <!--
          刻意用原生 textarea 而不是 a-textarea：
          antd 的 Input 模块虽已引入，但这里没必要为了外观多挂一层受控组件，
          而且 vendor-antd 的 800 kB 预算只剩不到 10 kB（见 vite.config.ts）。
        -->
        <textarea
          ref="inputEl"
          v-model="draft"
          class="compose-input"
          rows="1"
          maxlength="2000"
          placeholder="输入消息，Enter 发送，Shift + Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <div class="compose-bar">
          <span class="compose-tip">{{ draft.length }}/2000</span>
          <a-button
            type="primary"
            size="small"
            :loading="sending"
            :disabled="!draft.trim()"
            @click="send"
          >
            发送
          </a-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { listMessages, sendMessage } from '@/api/chat'
import { useAuthStore } from '@/stores/auth'
import { useInboxStore } from '@/stores/inbox'
import type { ChatMessage } from '@/types'

/**
 * 医患聊天面板 —— 医生端与居民端共用同一个组件。
 *
 * <h3>契约</h3>
 *
 * **填满父容器**（`height: 100%`，自己不管宽高）。调用方负责给它高度：
 * 居民端是整页卡片（`/messages/:doctorId`），医生端是左右分栏的右栏与抽屉。
 * 三条硬取舍：
 *
 * 1. **「哪条是我发的」按角色判断，不按 senderId**
 *    角色是登录时确定的，而 senderId 依赖后端每条消息都写对。
 *    用角色判断，即使历史数据里 senderId 为 null 也不会把气泡排到对面去。
 *
 * 2. **轮询增量而不是长连接**
 *    本机环境已有 HTTP 代理层（见项目记忆），SSE 在这条链路上要额外配置；
 *    而医患对话的消息密度很低（分钟级），5 秒轮询用 `sinceId` 只取增量，
 *    代价远低于维护一条长连接。
 *
 * 3. **已读是显式动作，不是「拉过消息就算看过」**
 *    标记已读走独立接口 `POST /chat/sessions/{id}/read`，且只在**页面可见**
 *    且有真未读时才调：让 GET 带写副作用，轮询与重试会把未读数悄悄清掉 ——
 *    用户会看到「明明没打开对话，红点却自己灭了」。
 *
 * 4. **增量轮询拿不到已读变化，所以周期性全量同步**
 *    `sinceId` 只返回新消息，而「对方把我这条读掉了」是对**既有**行的更新。
 *    每 {@link FULL_SYNC_EVERY} 轮做一次全量拉取来补这个差；
 *    全量拉取时必须先看一眼滚动位置，否则用户正在翻记录时会被拽回底部。
 */

const props = withDefaults(
  defineProps<{
    /** 会话 id */
    sessionId: number
    /** 对方展示名：用于空态文案与头像首字。不传时按当前角色给默认值 */
    peerName?: string
    /** 是否允许发送。会话已 CLOSED 时传 false，避免点一次发一条 400 报错 */
    canSend?: boolean
  }>(),
  { peerName: '', canSend: true },
)

const authStore = useAuthStore()
const inbox = useInboxStore()

/**
 * 增量轮询里夹一次全量同步的间隔（轮数）。
 * 4 轮 × 5 秒 = 20 秒：比「对方点开对话」这个动作的实际频率慢不了多少，
 * 而全量拉取带完整消息体，做太频繁会把流量放大一个数量级。
 */
const FULL_SYNC_EVERY = 4

/** 距底部多少像素内算「贴着底部」—— 决定全量刷新后要不要自动滚到底 */
const NEAR_BOTTOM_PX = 80

const messages = ref<ChatMessage[]>([])
const loading = ref(false)
const sending = ref(false)
const draft = ref('')
const scrollEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)

const isDoctor = computed(() => authStore.role === 'DOCTOR')
const mySender = computed(() => (isDoctor.value ? 'DOCTOR' : 'RESIDENT'))

/**
 * 头像角色。
 *
 * 这个面板被医生端与居民端共用，同一个人在不同端看到的两侧头像必须互换 ——
 * 判据就是登录角色，不用判断「对方是谁」：医患对话里对方永远是另一个角色。
 * 用角色图标而不是姓名首字：全站 13 处头像统一成同一套（`RoleAvatar`），
 * 且医生端列表本来就把患者姓名脱敏了，硬塞一个姓反而误导。
 */
const myRole = computed(() => (isDoctor.value ? 'doctor' : 'user'))
const peerRole = computed(() => (isDoctor.value ? 'user' : 'doctor'))

function isMine(m: ChatMessage): boolean {
  return m.sender === mySender.value
}

/**
 * 我发的最后一条消息的 id —— 只有它下面挂已读回执。
 *
 * 从后往前找而不是 `findLast`：`findLast` 需要 ES2023 lib，本项目 target 更低。
 */
const lastMineId = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (isMine(messages.value[i])) return messages.value[i].id
  }
  return null
})

/** 后端时间格式为 `yyyy-MM-dd HH:mm:ss`，本地置位时保持同形，避免两种格式混在列表里 */
function nowStamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`
}

/** 对方发来、我还没读的条数 —— 决定要不要调标记已读接口 */
function hasUnreadFromPeer(): boolean {
  return messages.value.some((m) => !isMine(m) && m.readAt === null)
}

/**
 * 把「对方发来的消息」标成已读。
 *
 * <p>三个前置条件缺一不可：
 * <ul>
 *   <li><b>页面可见</b> —— 后台标签页里也标记的话，用户回来会发现红点没了、
 *       却不知道自己"看过"了什么；</li>
 *   <li><b>确有未读</b> —— 否则每次轮询都打一次写接口；</li>
 *   <li><b>不在标记中</b> —— 并发标记会重复写库（虽然幂等，但没必要）。</li>
 * </ul>
 *
 * <p>标记成功后**本地同步置位**：轮询是增量的，拿不到已读时间的变化，
 * 不在这里补上，红点要等下一次全量加载才会灭，看着像「卡了一下」。
 */
let marking = false
async function markReadIfNeeded() {
  if (marking || document.hidden || !hasUnreadFromPeer()) return
  marking = true
  try {
    await inbox.markRead(props.sessionId)
    const stamp = nowStamp()
    messages.value.forEach((m) => {
      if (!isMine(m) && m.readAt === null) m.readAt = stamp
    })
  } finally {
    marking = false
  }
}

/** 只取「时:分」。日期已经由分隔条交代，每条消息再重复一遍日期是噪音 */
function fmtTime(s: string): string {
  return s ? s.replace('T', ' ').slice(11, 16) : ''
}

/** 本地日期键，形如 2026-09-29 */
function dayKeyOf(s: string): string {
  return s ? s.slice(0, 10) : ''
}

function localDayKey(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 今天 / 昨天 / 09-27 —— 只保留区分度，年份在对话里没有信息量 */
function dayLabel(s: string): string {
  const k = dayKeyOf(s)
  if (!k) return ''
  if (k === localDayKey(new Date())) return '今天'
  const y = new Date()
  y.setDate(y.getDate() - 1)
  if (k === localDayKey(y)) return '昨天'
  return k.slice(5)
}

/**
 * 该条消息是否需要插入日期分隔条。
 *
 * 与上一条比日期，而不是「与今天比」—— 后者会在对话跨三天时漏掉中间那条。
 * 服务端已按 id 正序返回，所以相邻两条的时间必然单调不减。
 */
function showDay(i: number): boolean {
  if (i === 0) return true
  return dayKeyOf(messages.value[i].createdAt) !== dayKeyOf(messages.value[i - 1].createdAt)
}

/** 新消息上屏后滚到底部 —— 否则用户看不到刚发出去的话 */
async function scrollToBottom() {
  await nextTick()
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 输入框随内容增高（上限 120px 后转为内部滚动），避免长消息只看得见两行 */
async function autoGrow() {
  await nextTick()
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}

/** 滚动位置是否贴着底部 —— 用户正在翻旧记录时，刷新不该把他拽回底部 */
function nearBottom(): boolean {
  const el = scrollEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
}

/**
 * 全量加载。
 *
 * @param silent 静默模式（周期同步用）：不置 loading、不强制滚到底。
 *               置 loading 会让消息区整块换成「正在加载…」，20 秒闪一次等于抽搐；
 *               强制滚到底则会把正在翻记录的用户的视线拽走。
 */
async function loadAll(silent = false) {
  if (!silent) loading.value = true
  const stick = silent ? nearBottom() : true
  try {
    messages.value = await listMessages(props.sessionId)
    if (stick) await scrollToBottom()
    await markReadIfNeeded()
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出；静默模式下连它也不该打扰用户
  } finally {
    if (!silent) loading.value = false
  }
}

/**
 * 增量拉取：只取 id 比最后一条大的。
 * 用最后一条的 id 而不是列表长度做游标 —— 长度在并发发送时会错位。
 */
let ticks = 0

async function pollNew() {
  ticks += 1
  /*
   * 周期性全量同步：增量接口只回新消息，而「对方把我这条读掉了」是对既有行的
   * 更新，增量永远拿不到 —— 不做这一步，已读状态只有自己这一侧会动。
   */
  if (ticks % FULL_SYNC_EVERY === 0) {
    await loadAll(true)
    return
  }

  const lastId = messages.value.length ? messages.value[messages.value.length - 1].id : undefined
  try {
    const fresh = await listMessages(props.sessionId, lastId)
    if (!fresh.length) return
    // 去重兜底：轮询与「发送后立刻上屏」可能拿到同一条
    const known = new Set(messages.value.map((m) => m.id))
    const add = fresh.filter((m) => !known.has(m.id))
    if (!add.length) return
    messages.value.push(...add)
    // 只有贴底时才自动滚：对方发来消息时用户若正在往上翻，不该被强行拉下来
    if (nearBottom()) await scrollToBottom()
    await markReadIfNeeded()
  } catch {
    // 轮询失败静默：偶发网络抖动不该弹一堆报错
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || !props.canSend) return
  sending.value = true
  try {
    const saved = await sendMessage(props.sessionId, content)
    draft.value = ''
    // 用后端返回的实体上屏，而不是本地伪造 —— 否则 id 为 undefined，
    // 轮询的去重集合会把真身当成新消息再插一次
    if (!messages.value.some((m) => m.id === saved.id)) {
      messages.value.push(saved)
    }
    await scrollToBottom()
    await autoGrow()
  } catch {
    // 失败时保留草稿，避免用户白打一段字
  } finally {
    sending.value = false
  }
}

let timer: ReturnType<typeof setInterval> | null = null

/**
 * 页面重新可见时补一次标记。
 *
 * 消息到达时页面在后台（用户切走了标签页）不该标记已读 ——
 * 那时他并没有"看到"。回到前台再标，红点的语义才站得住。
 */
function onVisibilityChange() {
  if (!document.hidden) markReadIfNeeded()
}

onMounted(() => {
  loadAll()
  timer = setInterval(pollNew, 5000)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

// 切换会话时重新加载（医生端在同一抽屉里换人）
watch(
  () => props.sessionId,
  () => {
    messages.value = []
    draft.value = ''
    ticks = 0
    loadAll()
  },
)
</script>

<style scoped>
/*
 * 容器查询：头像在很窄的容器里（医生端抽屉 420px）会挤占气泡宽度。
 * 用 @container 而不是 @media —— 后者看的是视口，医生端右栏在 1280px 视口下
 * 可能只有 480px 宽，@media 根本不会触发。
 */
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  container-type: inline-size;
}

.chat-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--panel-bg);
}

.chat-hint {
  margin: auto;
  font-size: 13px;
  color: var(--text-3);
}

/* ---------- 空态 ---------- */
.chat-empty {
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  text-align: center;
  padding: 20px;
}

/* 纯 CSS 画的气泡：一个圆角矩形 + 左下角斜方块当尾巴，零图标依赖 */
.empty-mark {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 62px;
  height: 46px;
  margin-bottom: 10px;
  border: 2px solid var(--primary);
  border-radius: 14px;
  background: var(--card-bg);
}

.empty-mark::after {
  content: '';
  position: absolute;
  left: 13px;
  bottom: -6px;
  width: 11px;
  height: 11px;
  background: var(--card-bg);
  border-right: 2px solid var(--primary);
  border-bottom: 2px solid var(--primary);
  border-bottom-right-radius: 3px;
  transform: rotate(45deg);
}

.em-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary-soft);
  border: 1.5px solid var(--primary);
}

.empty-title {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-1);
}

.empty-desc {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-3);
}

/* ---------- 日期分隔 ---------- */
.day-sep {
  display: flex;
  justify-content: center;
  margin: 6px 0 2px;
}

.day-sep span {
  padding: 2px 11px;
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  color: var(--text-3);
  font-size: 11.5px;
}

/* ---------- 消息 ---------- */
.msg {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 82%;
}

/* 自己发的气泡靠右（头像也跟着换到右边） */
.msg.mine {
  flex-direction: row-reverse;
  align-self: flex-end;
}

/* 尺寸与配色由 `RoleAvatar` 自理（40px / 按角色取自图标体系），这里只管排布 */
.msg-avatar {
  flex: none;
}

.msg-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.mine .msg-main {
  align-items: flex-end;
}

.bubble {
  padding: 9px 13px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  color: var(--text-1);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.mine .bubble {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

.msg-time {
  padding: 0 3px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}

/*
 * 已读回执：文案 + 颜色双重区分。
 * 只靠颜色区分对色觉障碍用户等于没有区分，所以「已读 / 未读」两个字才是主信号，
 * 颜色只是让它更快被扫到（已读=主色，未读=次要灰）。
 */
.receipt {
  margin-left: 6px;
  font-family: var(--font-sans);
  font-size: 10.5px;
  color: var(--text-3);
}

.receipt.read {
  color: var(--primary);
}

/* ---------- 输入区 ---------- */
.chat-compose {
  flex: none;
  border-top: 1px solid var(--card-border);
  background: var(--card-bg);
  padding: 12px 16px 10px;
}

.compose-closed {
  margin: 4px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}

.compose-input {
  display: block;
  width: 100%;
  min-height: 42px;
  max-height: 120px;
  resize: none;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  color: var(--text-1);
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  padding: 9px 11px;
  box-sizing: border-box;
  outline: none;
  transition: border-color var(--dur) var(--ease);
}

.compose-input:focus {
  border-color: var(--primary);
}

.compose-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.compose-tip {
  font-size: 11px;
  color: var(--text-3);
}

/*
 * 窄容器（医生端抽屉 420px）：头像让位给气泡。
 * 隐藏而不是缩小 —— 32px 已经是最小可辨识尺寸，再缩就只剩一个色块。
 */
@container (max-width: 440px) {
  /* 选择器刻意写成 `.msg .msg-avatar` 而不是单个类名：头像的 `display`
     现在由 `RoleAvatar` 提供，两个组件的作用域规则同为 (0,2,0)，
     谁生效取决于构建产物的拼接顺序 —— 加一层父级把特异性提到 (0,3,0)，
     顺序就不再影响结果。 */
  .msg .msg-avatar {
    display: none;
  }

  .msg {
    max-width: 86%;
  }
}
</style>
