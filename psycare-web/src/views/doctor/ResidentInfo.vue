<template>
  <div class="resident-page">
    <PageHeader title="用户信息" subtitle="查看居民档案与所属评测问卷，可直接发起聊天" />

    <div class="toolbar">
      <a-input
        v-model:value="keyword"
        class="search"
        placeholder="搜索姓名或登录名"
        allow-clear
        @press-enter="reload"
      />
      <a-button @click="reload">搜索</a-button>
      <span class="count-tip">共 {{ total }} 位居民</span>
    </div>

    <p v-if="loading" class="hint">加载中…</p>
    <p v-else-if="list.length === 0" class="hint">没有匹配的居民</p>

    <div v-else class="resident-list">
      <article v-for="r in list" :key="r.id" class="resident-card">
        <header class="rc-head">
          <div class="rc-id">
            <RoleAvatar class="rc-avatar" role="user" :size="46" />
            <div class="rc-title">
              <h3 class="rc-name">{{ r.realName || r.username }}</h3>
              <p class="rc-sub mono">{{ r.username }} · {{ r.phone || '未填手机号' }}</p>
            </div>
          </div>
          <div class="rc-actions">
            <a-button size="small" @click="toggleAssessments(r.id)">
              {{ expanded === r.id ? '收起问卷' : '查看问卷' }}
            </a-button>
            <a-button size="small" type="primary" @click="openChat(r)">聊天</a-button>
          </div>
        </header>

        <dl class="rc-meta">
          <div>
            <dt>性别</dt>
            <dd>{{ genderText(r.gender) }}</dd>
          </div>
          <div>
            <dt>年龄</dt>
            <dd class="mono">{{ r.age === null ? '—' : `${r.age} 岁` }}</dd>
          </div>
          <div>
            <dt>职业</dt>
            <dd>{{ r.occupation || '—' }}</dd>
          </div>
          <div>
            <dt>测评次数</dt>
            <dd class="mono">{{ r.assessmentCount }} 次</dd>
          </div>
          <div>
            <dt>最近测评</dt>
            <dd class="mono">{{ fmt(r.lastAssessmentAt) }}</dd>
          </div>
          <div>
            <dt>最近风险</dt>
            <dd>
              <RiskLevelTag v-if="r.lastRiskLevel" :level="r.lastRiskLevel" />
              <span v-else>—</span>
            </dd>
          </div>
        </dl>

        <!-- 危机预警单独一行，用颜色提出来：这是医生最该先看到的信息 -->
        <p v-if="r.crisisCount > 0" class="rc-crisis">
          该居民有 {{ r.crisisCount }} 次测评命中危机项，建议优先关注
        </p>

        <div v-if="expanded === r.id" class="rc-assessments">
          <h4 class="ass-title">所属评测问卷</h4>
          <p v-if="loadingAssessments" class="hint">加载中…</p>
          <p v-else-if="assessments.length === 0" class="hint">该居民还没有测评记录</p>
          <ul v-else class="ass-list">
            <li v-for="a in assessments" :key="a.id" class="ass-item">
              <span class="ass-name">{{ a.scaleName }}</span>
              <span class="ass-code mono">{{ a.scaleCode }}</span>
              <span class="ass-score mono">{{ a.totalScore }} 分</span>
              <RiskLevelTag :level="a.riskLevel" />
              <span class="ass-time mono">{{ fmt(a.completedAt) }}</span>
            </li>
          </ul>
        </div>
      </article>
    </div>

    <div v-if="total > size" class="pager">
      <a-pagination
        v-model:current="current"
        :page-size="size"
        :total="total"
        :show-size-changer="false"
        @change="load"
      />
    </div>

    <!--
      聊天抽屉：手写而不是 a-drawer。
      本机有 HTTP 代理层且 vendor-antd 预算只剩约 12 kB（见 vite.config.ts），
      一个只用一次的遮罩+侧栏不值得再挂一个组件进去。
    -->
    <div v-if="chatOpen" class="chat-mask" @click.self="closeChat">
      <aside class="chat-drawer">
        <header class="chat-head">
          <div>
            <h3 class="chat-peer">{{ chatPeer }}</h3>
            <p class="chat-sub mono">会话 #{{ chatSessionId }}</p>
          </div>
          <button class="chat-close" type="button" @click="closeChat">×</button>
        </header>
        <ChatPanel v-if="chatSessionId" :session-id="chatSessionId" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import ChatPanel from '@/components/ChatPanel.vue'
import { openSession } from '@/api/chat'
import { listResidentAssessments, pageResidents } from '@/api/resident'
import type { AssessmentHistoryItem, ResidentInfo } from '@/types'

/**
 * 医生端「用户信息」：居民档案 + 所属评测问卷 + 一键聊天。
 *
 * 结构上刻意用「卡片列表 + 行内展开」而不是左右分栏：
 * 医生在这里的主任务是"扫一遍我的居民，看谁需要关注"，
 * 展开问卷是次要动作，不该占据常驻版面（与工单处置台的主从结构不同，
 * 那边是"选中一条就必须看详情"）。
 */

const list = ref<ResidentInfo[]>([])
const loading = ref(false)
const total = ref(0)
const current = ref(1)
const size = ref(10)
const keyword = ref('')

/** 当前展开问卷的居民 id，null 表示全部收起（同时只展开一个，避免页面被撑爆） */
const expanded = ref<number | null>(null)
const assessments = ref<AssessmentHistoryItem[]>([])
const loadingAssessments = ref(false)

const chatOpen = ref(false)
const chatSessionId = ref<number | null>(null)
const chatPeer = ref('')

function fmt(s: string | null): string {
  return s ? s.replace('T', ' ').slice(0, 16) : '—'
}

function genderText(g: string | null): string {
  if (g === 'M') return '男'
  if (g === 'F') return '女'
  return '未填写'
}

async function load() {
  loading.value = true
  try {
    const res = await pageResidents({
      current: current.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
    })
    list.value = res.records
    total.value = res.total
    // 翻页后原来展开的人可能已不在当前页，收起避免错位
    expanded.value = null
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

function reload() {
  current.value = 1
  load()
}

async function toggleAssessments(residentId: number) {
  if (expanded.value === residentId) {
    expanded.value = null
    return
  }
  expanded.value = residentId
  assessments.value = []
  loadingAssessments.value = true
  try {
    assessments.value = await listResidentAssessments(residentId)
  } catch {
    // 同上，交给拦截器
  } finally {
    loadingAssessments.value = false
  }
}

async function openChat(r: ResidentInfo) {
  chatPeer.value = r.realName || r.username
  // 已有会话直接打开，省掉一次写请求；没有才创建
  if (r.sessionId) {
    chatSessionId.value = r.sessionId
    chatOpen.value = true
    return
  }
  try {
    const session = await openSession(r.id)
    chatSessionId.value = session.id
    // 回写到列表项：否则连续点两次「聊天」会建出两条会话
    r.sessionId = session.id
    chatOpen.value = true
  } catch {
    // 交给拦截器
  }
}

function closeChat() {
  chatOpen.value = false
  chatSessionId.value = null
}

onMounted(load)
</script>

<style scoped>
.resident-page {
  padding: 20px 24px 32px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
}

.search {
  max-width: 260px;
}

.count-tip {
  margin-left: auto;
  font-size: 13px;
  color: var(--text-3);
}

.hint {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}

.resident-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.resident-card {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  padding: 14px 16px;
}

.rc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.rc-id {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

/* 尺寸与配色由 `RoleAvatar` 自理（46px / 用户图标） */
.rc-avatar {
  flex: none;
}

.rc-title {
  min-width: 0;
}

.rc-name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.rc-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.rc-actions {
  display: flex;
  gap: 8px;
}

.rc-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
  margin: 12px 0 0;
}

.rc-meta dt {
  font-size: 12px;
  color: var(--text-3);
}

.rc-meta dd {
  margin: 2px 0 0;
  font-size: 13px;
  color: var(--text-1);
}

.rc-crisis {
  margin: 12px 0 0;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  background: var(--risk-d-soft);
  color: var(--risk-d);
  font-size: 12px;
}

.rc-assessments {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--card-border);
}

.ass-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.ass-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ass-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
}

.ass-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ass-code {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}

.ass-score {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.ass-time {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

/* ---- 聊天抽屉 ---- */
.chat-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgb(0 0 0 / 32%);
  display: flex;
  justify-content: flex-end;
}

.chat-drawer {
  width: 420px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
  border-left: 1px solid var(--card-border);
}

.chat-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--card-border);
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

.chat-close {
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.chat-close:hover {
  color: var(--text-1);
}
</style>
