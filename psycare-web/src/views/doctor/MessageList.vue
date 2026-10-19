<template>
  <div class="msg-page">
    <PageHeader title="消息列表" subtitle="居民发来的消息，按最近活跃排序">
      <template #extra>
        <a-button size="small" :loading="loading" @click="refresh">刷新</a-button>
      </template>
    </PageHeader>

    <!-- 两条汇总分开写：一句是「有新东西」，一句是「有债没还」，含义不同不该挤成一句 -->
    <p v-if="unreadTotal > 0 || awaitingCount > 0" class="await-bar">
      <template v-if="unreadTotal > 0">
        有 <b class="mono">{{ unreadTotal }}</b> 条新消息未读
      </template>
      <template v-if="unreadTotal > 0 && awaitingCount > 0">
        <span class="bar-sep" aria-hidden="true">·</span>
      </template>
      <template v-if="awaitingCount > 0">
        有 <b class="mono">{{ awaitingCount }}</b> 位居民在等你回复
      </template>
    </p>

    <div class="msg-body">
      <aside class="sess-list">
        <p v-if="loading && sessions.length === 0" class="hint">加载中…</p>
        <p v-else-if="sessions.length === 0" class="hint">
          还没有居民和你聊过。<br />
          对方发出第一条消息后，对话就会出现在这里。
        </p>
        <!-- v-for 与 v-else 不能同元素，必须 template 包一层 -->
        <template v-else>
          <button
            v-for="s in sessions"
            :key="s.id"
            type="button"
            class="sess-item"
            :class="{ active: s.id === activeId }"
            @click="activeId = s.id"
          >
            <span class="avatar-wrap">
              <RoleAvatar class="sess-avatar" role="user" :size="42" />
              <!-- 红点（有未读）与「待回复」是两件事，别用一个字段表达两种意思 -->
              <UnreadBadge :count="s.unreadCount" mode="dot" :tip="`${s.unreadCount} 条未读`" />
            </span>
            <span class="sess-main">
              <span class="sess-top">
                <span class="sess-name">{{ s.peerName }}</span>
                <UnreadBadge :count="s.unreadCount" :tip="`${s.unreadCount} 条未读`" />
                <span class="sess-time mono">{{ fmt(s.lastMessageAt || s.createdAt) }}</span>
              </span>
              <!-- 列表已在后端过滤为「有过消息」，lastMessage 必有值；兜底只为脏数据不留空白 -->
              <span class="sess-preview" :class="{ unread: s.unreadCount > 0 }">
                {{ s.lastMessage || '（还没有消息）' }}
              </span>
            </span>
          </button>
        </template>
      </aside>

      <section class="chat-slot">
        <template v-if="active">
          <header class="chat-head">
            <div class="chat-peer-box">
              <h3 class="chat-peer">{{ active.peerName }}</h3>
              <p class="chat-sub mono">
                会话 #{{ active.id }} · 共 {{ active.messageCount }} 条
              </p>
            </div>
            <span v-if="active.awaitingMyReply" class="await-tag">待回复</span>
          </header>
          <ChatPanel :session-id="active.id" />
        </template>
        <p v-else class="hint">从左侧选择一位居民查看对话</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import ChatPanel from '@/components/ChatPanel.vue'
import UnreadBadge from '@/components/UnreadBadge.vue'
import { listSessions } from '@/api/chat'
import { useInboxStore } from '@/stores/inbox'
import type { ChatSession } from '@/types'

/**
 * 医生端「消息列表」—— 医患对话的收件箱。
 *
 * 与「用户信息」的分工：那边是**主动找人**（扫患者档案、顺手点聊天），
 * 这边是**被动接收**（谁给我发了消息、谁还没回）。两条入口共用 ChatPanel，
 * 所以同一会话在哪边打开都是同一份记录。
 *
 * 两条设计取舍：
 *
 * 1. **「未读」与「待回复」是两个信号，不合并**
 *    `unreadCount` 来自服务端已读时间（`read_at` 为空的条数），
 *    `awaitingMyReply` 是「最后一条是对方发的」。读过但没回时前者为 0、
 *    后者仍为真 —— 红点表达「有我没看的」，待回复表达「有我欠的」。
 *    合并成一个会让红点在读过之后还一直亮，用户为了灭掉它只能回一句废话。
 *
 * 2. **会话列表轮询 10 秒**
 *    新会话是"居民主动发起"才出现的，不轮询的话医生得手动刷新才能发现。
 *    10 秒而非 5 秒：会话列表带多个聚合子查询，比 ChatPanel 的增量拉消息重，
 *    而它的变化频率低得多（进消息的人少，进消息的次数更少）。
 */

const sessions = ref<ChatSession[]>([])
const loading = ref(false)
const activeId = ref<number | null>(null)
const inbox = useInboxStore()

const active = computed(() => sessions.value.find((s) => s.id === activeId.value) ?? null)
const awaitingCount = computed(() => sessions.value.filter((s) => s.awaitingMyReply).length)
/**
 * 未读条数取 inbox 的汇总而不是自己累加 `s.unreadCount`：
 * 列表每 10 秒才刷一次，而 ChatPanel 标已读是即时发生的 ——
 * 自己累加会出现「消息已读、红点数还停在旧值」的最长 10 秒窗口。
 */
const unreadTotal = computed(() => inbox.messageCount)

function fmt(s: string | null): string {
  return s ? s.replace('T', ' ').slice(5, 16) : ''
}

/**
 * @param silent 静默刷新：轮询用。失败不弹提示、不显示 loading，
 *               否则医生正在打字时界面会每 10 秒闪一下
 */
async function load(silent: boolean) {
  if (!silent) loading.value = true
  try {
    const list = await listSessions()
    sessions.value = list
    // 首次进来自动选中最近的一条，省一次点击；之后保留医生的手动选择
    if (list.length && activeId.value === null) {
      activeId.value = list[0].id
    }
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  } finally {
    if (!silent) loading.value = false
  }
}

/** 按钮绑定用无参函数，避免 @click 把 MouseEvent 当作 silent 传进来 */
function refresh() {
  load(false)
}

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  load(false)
  timer = setInterval(() => load(true), 10000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.msg-page {
  padding: 20px 24px 32px;
}

.await-bar {
  margin: 16px 0 0;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--risk-d-soft);
  border: 1px solid var(--risk-d-border);
  color: var(--risk-d);
  font-size: 13px;
}

.bar-sep {
  margin: 0 6px;
  opacity: 0.5;
}

.msg-body {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
  /* 减去头部与页面内边距，让聊天区占满剩余高度 */
  height: calc(100vh - 230px);
  min-height: 420px;
  margin-top: 16px;
}

.sess-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  padding: 8px;
}

.hint {
  margin: auto;
  padding: 24px 12px;
  text-align: center;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-3);
}

.sess-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.sess-item:hover {
  background: var(--panel-bg);
}

.sess-item.active {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.avatar-wrap {
  position: relative;
  flex: none;
  display: inline-flex;
}

/* 尺寸与配色由 `RoleAvatar` 自理（42px / 用户图标）。
   原先这里是 `--accent-purple` 实心方块，与居民端会话列表的蓝底各说各话 ——
   统一到角色图标后，两端「这一条是一个居民」的视觉表达才一致。 */
.sess-avatar {
  flex: none;
}

/*
 * 红点样式已移入 components/UnreadBadge.vue（居民端列表要用同一个）。
 * 定位所需的 position: relative 仍由 .avatar-wrap 提供 —— 那是布局约定，不属于徽标本身。
 */

.sess-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sess-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.sess-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.sess-time {
  flex: none;
  font-size: 11px;
  color: var(--text-3);
}

.sess-preview {
  font-size: 12px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 对方发的最后一条加粗提亮，一眼看出哪几条还没回 */
.sess-preview.unread {
  color: var(--text-1);
  font-weight: 500;
}

.chat-slot {
  min-width: 0;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.chat-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--card-border);
}

.chat-peer-box {
  min-width: 0;
}

.chat-peer {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.chat-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.await-tag {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--risk-d-soft);
  border: 1px solid var(--risk-d-border);
  color: var(--risk-d);
  font-size: 12px;
}

.chat-slot > :deep(.chat-panel) {
  flex: 1;
  min-height: 0;
}
</style>
