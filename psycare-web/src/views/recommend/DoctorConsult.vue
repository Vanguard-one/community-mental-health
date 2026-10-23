<template>
  <div class="page-container">
    <!-- 页头：标题 + 一句说明。PageHeader 自带 L1 白面与 v-reveal="'header'" -->
    <PageHeader title="医生咨询" :subtitle="headerSubtitle" />

    <!--
      两级结构：左一级是科室（+ 我的预约），右二级是该科室的医生横条列表。
      一级菜单只放「科室 + 我的预约」两样东西 —— 全站已经有 64px 侧栏，
      再在一级菜单里放首页/个人中心会让两套导航并排存在、互相失效。
    -->
    <div class="split">
      <AppointmentSubNav
        :departments="catalog"
        :total="totalDoctors"
        :active-key="currentDept"
        @select="selectDept"
        @mine="router.push('/appointments')"
      />

      <div class="split-main">
        <ListSkeleton v-if="loading && !catalog.length" :count="5" />

        <template v-else-if="catalog.length">
          <!-- 列表头：交代「当前在哪一类、有几个人」，避免用户点了科室后不知所处 -->
          <p class="list-head">
            <b>{{ currentDept || '全部科室' }}</b>
            <span class="lh-count mono">{{ visibleDoctors.length }}</span>
            <span class="lh-hint">位医生</span>
            <span class="lh-tip">选好医生后可直接预约时间段</span>
          </p>

          <div v-if="visibleDoctors.length" v-reveal="'stagger'" class="doc-list">
            <DoctorRow
              v-for="d in visibleDoctors"
              :key="d.doctorId"
              :doctor="d"
              @book="goBook"
              @chat="goChat"
            />
          </div>

          <!--
            空态：白卡 + 说明 + 「换个科室」。
            ⚠️ 说明文字落白面，只能用 --text-2 —— --text-3 落在白面是 4.8:1（刚好过），
               但它落 --panel-bg 只有 4.27:1。这里为了和其它页的空态保持一致，统一用 --text-2。
          -->
          <div v-else v-reveal="'rise'" class="empty-card">
            <span class="empty-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8" />
                <path d="M16 16 21 21" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </span>
            <p class="empty-title">该科室暂无医生</p>
            <p class="empty-desc">医生可能正在调整出诊安排，先看看其他科室。</p>
            <button type="button" class="btn-ghost" @click="selectDept('')">换个科室</button>
          </div>
        </template>

        <!-- 目录整体为空：白卡空态 -->
        <div v-else class="empty-card">
          <span class="empty-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8" />
              <path d="M16 16 21 21" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            </svg>
          </span>
          <p class="empty-title">暂无可咨询的医生</p>
          <p class="empty-desc">医生通过资质审核后会自动出现在这里，请稍后再来。</p>
        </div>

        <p class="footnote">
          这里列出的是全部可咨询的医生。若你已经和某位医生聊过，他的对话会出现在
          <span class="link" @click="router.push('/messages')">我的消息</span> 里；
          已提交的预约在 <span class="link" @click="router.push('/appointments')">我的预约</span> 里。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppointmentSubNav from '@/components/AppointmentSubNav.vue'
import DoctorRow from '@/components/DoctorRow.vue'
import ListSkeleton from '@/components/ListSkeleton.vue'
import PageHeader from '@/components/PageHeader.vue'
import { listDoctorCatalog } from '@/api/chat'
import type { ChatPeer, DepartmentGroup } from '@/types'

/**
 * 「医生咨询」页（`/doctors`）—— 两级结构。
 *
 * 一级：科室（含「全部科室」与「我的预约」）；二级：该科室下的医生横条列表。
 * 选中的科室用 `?dept=` 同步，不做成子路由：
 * ① 两个视图共用同一份「医生目录」数据，拆路由会让每个路由各自请求一次同样的接口；
 * ② 科室名是中文，放进路径段要反复编码解码，query 天然处理。
 * `?dept=` 缺省（或非法）时退化为「全部科室」，刷新与分享链接都能还原到同一个视图。
 *
 * <p>与 AI 推荐结果页（`/ai-recommend`）的分工：那边是「AI 替你挑好了」，
 * 这边是「你自己按科室挑」。两种意图都真实存在，合并会牺牲其中一边。
 *
 * <p>⚠️ 这里的医生卡是 `DoctorRow`（横条），**不是** `RecommendedDoctors`（网格 + 匹配依据）。
 * 理由写在 `DoctorRow.vue` 顶部：形态与语义都不同，合并只会让两边都长出分支参数。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const catalog = ref<DepartmentGroup[]>([])

/** 当前选中的科室（来自 URL）；为空表示停在「全部科室」 */
const currentDept = computed(() => {
  const d = route.query.dept
  return typeof d === 'string' && d.trim() ? d.trim() : ''
})

/**
 * 当前要展示的医生。
 * - 未选科室 → 全部医生（「全部科室」）；
 * - 选了科室 → 该科室医生。科室名在 URL 里，可能指向一个不存在的科室（手改链接、
 *   或该科室最后一位医生被下架）—— 那种情况下返回空数组，视图显示「该科室暂无医生」
 *   而不是报错：404 页对用户没有任何可操作价值，他需要的是「换个科室」这个出路。
 */
const visibleDoctors = computed<ChatPeer[]>(() => {
  if (!currentDept.value) return catalog.value.flatMap((g) => g.doctors)
  return catalog.value.find((g) => g.department === currentDept.value)?.doctors ?? []
})

const totalDoctors = computed(() => catalog.value.reduce((n, g) => n + g.doctors.length, 0))

/** 页头说明句：把「几个科室、多少位医生」先交代清楚 */
const headerSubtitle = computed(() =>
  catalog.value.length
    ? `共 ${catalog.value.length} 个科室 · ${totalDoctors.value} 位医生，选好医生即可预约时间段或直接咨询`
    : '按科室找到合适的医生，预约时间段或直接开始一对一沟通',
)

function selectDept(dept: string) {
  router.push({ path: '/doctors', query: dept ? { dept } : {} })
}

/**
 * 去和该医生聊天。
 *
 * 「发起咨询」与「继续聊天」是同一个目的地：专有聊天页自己会调幂等的
 * {@code POST /chat/sessions} 建或取会话。所以本页不按按钮文案分两条分支，
 * 也不在跳转前先请求一次 —— 那样只会多一个「查到的会话已过期」的竞态。
 */
function goChat(d: ChatPeer) {
  router.push(`/messages/${d.doctorId}`)
}

/** 去预约该医生的时间段 */
function goBook(d: ChatPeer) {
  router.push(`/doctors/${d.doctorId}/book`)
}

async function load() {
  loading.value = true
  try {
    catalog.value = await listDoctorCatalog()
  } catch {
    catalog.value = []
  } finally {
    loading.value = false
  }
}

/*
 * 只在挂载时拉一次。
 *
 * 「切科室不重新请求」是本页的设计，不是省事：目录接口**一次返回全部科室的全部医生**，
 * 而 `currentDept` 是对 `route.query` 的 computed，换科室只是换一个切片，无需回源。
 * 于是换科室是纯本地操作 —— 没有网络等待、没有骨架屏、没有入场动画重播。
 *
 * 为什么不需要「返回本页时刷新」的 watch：本页没有 `<keep-alive>`，
 * 从菜单点进来、或用浏览器前进后退**跨页**回来，都是重新挂载，`onMounted` 已经覆盖；
 * 同页内只换 `?dept=` 时组件不重挂（`App.vue` 的错误边界 key 取 `path` 而非 `fullPath`），
 * 由上面的 computed 原地响应 —— 这两种情况合起来已穷尽，中间没有第三种状态。
 *
 * ⚠️ 数据新鲜度：离开再回来会重新拉。这是刻意的 —— 期间医生可能刚通过审核或被下架。
 */
onMounted(load)
</script>

<style scoped>
/* ---------- 两级布局 ---------- */

/*
 * 左一级 200px 固定（菜单项是「科室名 + 数字」，再窄就换行），
 * 右二级自适应。`align-items: start` 让左栏在内容变长时不被拉高 ——
 * 否则 sticky 侧栏会比视口还高，滚动时反而不跟随。
 */
.split {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: var(--space-card);
  align-items: start;
}

/* min-width: 0 不可省：grid 子项默认 min-width:auto，
   不写这条时长专长文本会把整列撑出容器，页面出现横向滚动 */
.split-main {
  min-width: 0;
}

/* 断点用 900 而不是 768：左栏 200 + 间距 20 + 右栏最小可用宽度约 560
   才不至于把医生卡挤成两行 —— 900 是「右栏还够宽」的下限 */
@media (max-width: 900px) {
  .split {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 列表头 ---------- */

.list-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 0 0 12px;
  color: var(--text-2);
  font-size: 13px;
}

.list-head b {
  color: var(--text-1);
  font-size: 14.5px;
  font-weight: 600;
}

.lh-count {
  color: var(--primary-dark);
  font-size: 14.5px;
  font-weight: 600;
}

.lh-hint {
  color: var(--text-2);
}

/* 右侧提示推到最右；窄屏隐藏（挤不下时它会先换行，把列表头撑成两行） */
.lh-tip {
  margin-left: auto;
  color: var(--text-3);
  font-size: 12px;
}

@media (max-width: 640px) {
  .lh-tip {
    display: none;
  }
}

.doc-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ---------- 空态 ---------- */

.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 44px 24px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
  text-align: center;
}

.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: 14px;
  border-radius: 50%;
  background: var(--panel-bg);
  /* 图形落 --panel-bg，只能用 --text-2（--text-3 在浅分区只有 4.27:1） */
  color: var(--text-2);
}

.empty-mark svg {
  width: 24px;
  height: 24px;
}

.empty-title {
  margin: 0 0 6px;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
}

.empty-desc {
  margin: 0 0 16px;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.7;
}

.btn-ghost {
  height: 34px;
  padding: 0 16px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-1);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.btn-ghost:hover {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

/* ---------- 页脚说明 ---------- */

.footnote {
  margin: 18px 0 0;
  color: var(--text-3);
  font-size: 12.5px;
  line-height: 1.8;
}

.link {
  color: var(--primary-dark);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
