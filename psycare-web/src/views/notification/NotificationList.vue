<template>
  <div class="page-container narrow">
    <PageHeader title="通知" subtitle="系统消息与提醒" />

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
      <p class="empty-desc" data-anim="rise">完成一次心理测评后，系统的提醒会出现在这里。</p>
      <button type="button" class="empty-btn" data-anim="chrome" @click="router.push('/scales')">
        去做测评
      </button>
    </section>

    <!--
      列表：一张 L1 白卡装若干白面行（照 Home.vue 的 .card + .rec-list li 写法）。
      动效用 recipe 'card' —— 容器自身就是一张卡，整块依序揭示，不给每一行挂 data-anim
      （几十条通知逐条入场既是泛型 fade-up，也会把动效元素数量顶穿 5~12 的上限）。
    -->
    <div v-else v-reveal="'card'" class="notice-card">
      <ul class="notice-list">
        <li
          v-for="n in notifications"
          :key="n.id"
          class="notice-row"
          :class="{ 'is-unread': n.readFlag !== 1 }"
        >
          <span class="unread-dot" aria-hidden="true" />
          <div class="notice-main">
            <div class="notice-head">
              <span class="notice-title">{{ n.title }}</span>
              <span class="notice-tag mono" :class="{ 'is-alert': n.type === 'ALERT' }">
                {{ typeDesc(n.type) }}
              </span>
              <span class="notice-time mono">{{ formatTime(n.createdAt) }}</span>
            </div>
            <p class="notice-content">{{ n.content }}</p>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BellOutlined } from '@ant-design/icons-vue'
import { getMyNotifications } from '@/api/notification'
import PageHeader from '@/components/PageHeader.vue'
import ListSkeleton from '@/components/ListSkeleton.vue'
import type { Notification } from '@/types'

const router = useRouter()

const loading = ref(true)
const notifications = ref<Notification[]>([])

function typeDesc(type: string): string {
  switch (type) {
    case 'ALERT':
      return '预警'
    case 'CONSULT':
      return '问诊'
    default:
      return '系统'
  }
}

function formatTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

onMounted(async () => {
  try {
    notifications.value = (await getMyNotifications()) ?? []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ---------- 列表 ---------- */
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
  align-items: flex-start;
  gap: 10px;
  padding: 14px 14px 14px 12px;
  border-radius: var(--radius-ctrl);
  transition: background var(--dur) var(--ease);
}

.notice-row:hover {
  background: var(--panel-bg);
}

/*
 * 未读的唯一强调：一个主色小圆点。
 * 已读行保留一个同宽的空位（透明），两种状态的标题左边界始终对齐；
 * 行底一律不染色 —— 整行染色会把正文一起压暗，得不偿失。
 */
.unread-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 7px;
  border-radius: 50%;
  background: transparent;
}

.notice-row.is-unread .unread-dot {
  background: var(--primary);
}

.notice-main {
  flex: 1;
  min-width: 0;
}

.notice-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.notice-title {
  min-width: 0;
  font-size: 14.5px;
  font-weight: 500;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 类型标签。默认中性：透明底 + --card-border 描边 + --text-2 文字 ——
 * 描边而不是 --panel-bg 底，是因为悬停会把行底换成 --panel-bg，
 * 用底色做标签会在悬停时与行底融成一片。
 * 预警走 --risk-b-soft + --risk-b-ink：soft 底上的文字必须用同族 ink（实色 --risk-b
 * 落在 soft 上只有 2.2 上下），这是风险色阶的既定规则。
 */
.notice-tag {
  flex: none;
  padding: 2px 8px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  color: var(--text-2);
  font-size: 11.5px;
  line-height: 1.4;
}

.notice-tag.is-alert {
  border-color: transparent;
  background: var(--risk-b-soft);
  color: var(--risk-b-ink);
}

/* 时间戳落在白面行上 —— --text-3 在白面上是 4.8:1，达标；落在 --panel-bg 上就只有 4.27。 */
.notice-time {
  flex: none;
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}

.notice-content {
  margin: 6px 0 0;
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--text-2);
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
</style>
