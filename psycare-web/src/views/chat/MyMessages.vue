<template>
  <div class="page-container">
    <PageHeader title="我的消息" subtitle="和医生的对话都在这里，按最近活跃排序" />

    <!--
      未读汇总：数字来自 inbox store（服务端 /chat/unread），与医生端侧栏同源。
      取 store 而不是累加列表里的 unreadCount —— 本页只在挂载时拉一次列表，
      而聊天页标已读是即时发生的，自己累加会停在旧值上。

      视觉统一改造（两点，都因为原写法踩了硬约束）：
      · 原为「--risk-d-soft 浅底 + --risk-d 实色文字」的小色条。浅色面直接贴页面底
        不合规（面分级规则 ①：贴底的只能是白卡或主色），故改为 L1 白卡。
      · --risk-d 压在同族 --risk-d-soft 上只有 3.9 上下、不达 AA（风险色阶规则：
        soft 底上的文字必须用 --risk-*-ink）。改后数字走 --risk-d-ink，白卡上 6.2 上下。
    -->
    <p v-if="!loading && unreadTotal > 0" class="unread-bar">
      有 <b class="mono">{{ unreadTotal }}</b> 条新消息未读
    </p>

    <p v-if="loading" class="hint">加载中…</p>

    <!--
      空态给出口而不是干说「暂无数据」：新居民必须有地方找到第一位医生。

      做成一张 L1 白卡而不是透明容器：图标圆标是浅色面（--panel-bg），
      浅色面不能直接贴页面底（面分级规则 ①，与底仅约 1.3:1 等于隐形）。
      动效按元素语义分开：圆标 pop、两行文字 rise、按钮 chrome。
    -->
    <section v-else-if="!doctors.length" v-reveal="'stagger'" class="empty-state">
      <span class="empty-mark" data-anim="pop" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="currentColor">
          <path
            d="M12 3.2c-5 0-9.1 3.5-9.1 7.8 0 2.4 1.3 4.6 3.3 6-.2 1.2-.7 2.4-1.5 3.3-.2.3 0 .7.4.6 2.1-.3 3.9-1.3 5-2.2.9.2 1.9.3 2.9.3 5 0 9.1-3.5 9.1-7.9S17 3.2 12 3.2z"
          />
        </svg>
      </span>
      <p class="empty-title" data-anim="rise">还没有和医生聊过</p>
      <p class="empty-desc" data-anim="rise">
        去「医生咨询」按科室选一位合适的医生，发出第一条消息后，对话就会出现在这里。
      </p>
      <button type="button" class="empty-btn" data-anim="chrome" @click="router.push('/doctors')">
        去医生咨询
      </button>
    </section>

    <!--
      列表：一张 L1 白卡装若干白面行（照 Home.vue 的 .card + .rec-list li 写法）——
      行不再各自成卡：一屏十几张白卡会让描边与投影互相干扰，分栏交给行间距与悬停底色。
      动效用 'stagger'：行按 DOM 顺序 110ms 递进升起。
      ⚠️ 本页只在挂载时拉一次列表，没有流式追加；若将来改成轮询 / 推送，
        新到达的行会再播一次入场，那时应撤掉行上的 data-anim（与聊天页消息区同理）。
    -->
    <div v-else v-reveal="'stagger'" class="conv-card">
      <ul class="conv-list">
        <li v-for="d in doctors" :key="d.doctorId" data-anim="rise">
          <!--
            整行即入口：不再单独放一个「继续聊天」按钮。
            一行一个对话是消息列表的既有心智（短信、微信都这样），
            按钮反而让用户要先找到按钮再点。
          -->
          <router-link class="conv" :to="`/messages/${d.doctorId}`">
            <span class="conv-avatar-wrap">
              <RoleAvatar class="conv-avatar" role="doctor" :size="52" />
              <!-- 与医生端会话列表同一个组件、同一个触发条件（unreadCount > 0） -->
              <UnreadBadge :count="d.unreadCount" mode="dot" :tip="`${d.unreadCount} 条未读`" />
            </span>

            <span class="conv-main">
              <span class="conv-top">
                <span class="conv-name">{{ d.doctorName }}</span>
                <span v-if="d.department" class="conv-dept">{{ d.department }}</span>
                <UnreadBadge :count="d.unreadCount" :tip="`${d.unreadCount} 条未读`" />
                <span class="conv-time mono">{{ fmt(d.lastMessageAt) }}</span>
              </span>
              <span class="conv-last" :class="{ unread: d.unreadCount > 0 }">
                {{ d.lastMessage || '（暂无消息）' }}
              </span>
            </span>

            <span class="conv-arrow" aria-hidden="true">→</span>
          </router-link>
        </li>
      </ul>
    </div>

    <p v-if="doctors.length" class="footnote">
      列表只包含<b>有过消息往来</b>的医生。从未聊过的医生请到
      <router-link class="link" to="/doctors">医生咨询</router-link> 里找。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import UnreadBadge from '@/components/UnreadBadge.vue'
import { listChatDoctors } from '@/api/chat'
import { useInboxStore } from '@/stores/inbox'
import type { ChatPeer } from '@/types'

/**
 * 居民端「我的消息」—— 会话列表。
 *
 * <p>与专有聊天页（`/messages/:doctorId`）分工：本页负责**找得到人**，
 * 那边负责**说得上话**。以前两者挤在一个抽屉里，结果是列表项为了塞下
 * 聊天记录做得又高又杂，而聊天区只有 420px 宽。
 *
 * <p>只列有消息往来的医生（判定条件在后端 SQL，一句话：该会话下至少 1 条消息）。
 * 因此「在某位医生那里聊了一句」之后，他会自动出现在这里 —— 本页不需要任何回写。
 *
 * <p>排序由后端给定（最近消息倒序），前端不二次排序：
 * 「最近联系人在最上面」是列表的既定语义，两处排序迟早会打架。
 *
 * <p>管理端医生侧的对称页是 `views/doctor/MessageList.vue`（左右分栏）。
 * 那边是**被动接收**（谁发来了消息、谁还没回），所以保留了常驻的会话列表；
 * 居民端是**主动找人**，一层列表点进一层对话更直接。
 */

const doctors = ref<ChatPeer[]>([])
const loading = ref(false)
const inbox = useInboxStore()
const router = useRouter()

/** 未读总条数（与医生端「消息列表」顶部同一口径、同一数据源） */
const unreadTotal = computed(() => inbox.messageCount)

/** 后端返回 `yyyy-MM-dd HH:mm:ss`，只截到分钟 —— 列表不需要秒 */
function fmt(s: string | null): string {
  return s ? s.replace('T', ' ').slice(5, 16) : ''
}

async function load() {
  loading.value = true
  try {
    doctors.value = await listChatDoctors()
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 加载句：直接落在页面底上，只能走 --text-2（--text-3 在页面底上只有 3.60:1，不达 AA） */
.hint {
  margin: 0;
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-2);
}

/*
 * 未读汇总：L1 白卡（贴页面底的只能是白卡或主色）。
 * 数字走 --risk-d-ink：白卡上是 6.2 上下，达 AA；原来的 --risk-d 压在浅底上只有 3.9。
 */
.unread-bar {
  margin: 0 0 var(--space-card);
  padding: 12px 16px;
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  box-shadow: var(--shadow-card);
  font-size: 13px;
  color: var(--text-2);
}

.unread-bar b {
  color: var(--risk-d-ink);
  font-weight: 600;
}

/* ---------- 空态 ---------- */
/*
 * 轻量空态：图标圆标（--panel-bg）+ 一句说明 + 一个主按钮。
 * 整块是一张 L1 白卡 —— 圆标是浅色面，不能直接裸落在页面底上（面分级规则 ①）。
 * 说明落在白面上，--text-3 的 4.8:1 成立。
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
  /* 圆标底是 --panel-bg，不是白面 —— 图标是图形不是正文，取 --text-2 保证形状清晰 */
  color: var(--text-2);
}

.empty-mark svg {
  display: block;
  width: 26px;
  height: 26px;
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

/*
 * 主按钮：原生 button 而不是 a-button —— 本批改造不允许新增任何 a-* 标签（vendor-antd 预算）。
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

/* ---------- 会话列表 ---------- */
/*
 * 外层 L1 白卡收圆角与阴影、给整页一个内容面；行本身不再各自成卡
 * （与 Home.vue 的「最近记录」面板、通知页的 .notice-card 同一套做法）。
 */
.conv-card {
  padding: 8px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.conv-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

/*
 * 行：与 Home.vue 的 .rec-list li 同一套外观（白面行 + 圆角 + 悬停 --panel-bg 底）。
 * 这里悬停底色写在**链接**上而不是 <li> 上：整行就是入口，padding 必须落在可点区域里，
 * 否则「悬停变色但点了没反应」的那圈 padding 会变成一个假的点击区。
 */
.conv {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: var(--radius-ctrl);
  text-decoration: none;
  transition: background var(--dur) var(--ease);
}

/* 悬停只换底色（L2），不叠阴影、不位移：卡内浅色分区只用底色区分（面分级规则 ②） */
.conv:hover {
  background: var(--panel-bg);
}

/* 红点要压在头像右上角，所以头像外面必须有一层定位容器 */
.conv-avatar-wrap {
  position: relative;
  flex: none;
  display: inline-flex;
}

/* 尺寸与配色由 `RoleAvatar` 自理（52px / 医生图标） */
.conv-avatar {
  flex: none;
}

.conv-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.conv-top {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

/* 行标题走 --font-sans + 600（16px 级小标题的统一写法，见 Home.vue 的 .card-head h3） */
.conv-name {
  font-size: 15.5px;
  font-weight: 600;
  color: var(--text-1);
}

/*
 * 科室做成小徽标：患者记医生往往按科室记（「那个心理科的」）。
 * ⚠️ 文字取 --primary-dark 而不是 --primary：实测 --primary 落在 --primary-soft 上只有
 *    4.44:1，差一点不到 AA 的 4.5；深一档的 --primary-dark 是 5.71:1，色相不变。
 *    （与风险色阶「soft 底配深一档同族文字」的 --risk-*-ink 是同一套做法。）
 */
.conv-dept {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-size: 11.5px;
}

/* 时间靠右：margin-left auto 而不是 space-between，
   这样名字短的时候时间仍然贴右边，长的时候也不会被挤掉。
   时间戳落在白面行上 —— --text-3 在白面上是 4.8:1 达标（悬停变 --panel-bg 时会短暂降到 4.27，
   与通知页的 .notice-time 是同一取舍）。 */
.conv-time {
  margin-left: auto;
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}

.conv-last {
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 有未读时提亮加粗 —— 与医生端会话列表同一条规则，扫一眼就知道该点哪行 */
.conv-last.unread {
  color: var(--text-1);
  font-weight: 500;
}

.conv-arrow {
  flex: none;
  color: var(--text-3);
  font-size: 15px;
  transition:
    color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.conv:hover .conv-arrow {
  color: var(--primary);
  transform: translateX(2px);
}

/*
 * 脚注直接落在页面底上，所以走 --text-2 而不是 --text-3（后者在页面底上只有 3.60:1）。
 * 链接取 --primary-dark：--primary 在页面底上约 3.7:1，不到 AA 的 4.5；深一档是 4.6 上下。
 */
.footnote {
  margin: var(--space-card) 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-2);
}

.link {
  color: var(--primary-dark);
  text-decoration: none;
}
</style>
