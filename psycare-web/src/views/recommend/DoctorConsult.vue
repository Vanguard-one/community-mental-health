<template>
  <div class="page-container">
    <!-- 页头：标题 + 一句说明。PageHeader 自带 L1 白面与 v-reveal="'header'" -->
    <PageHeader title="医生咨询" :subtitle="headerSubtitle" />

    <!--
      加载占位：复用 CardGridSkeleton（内部样式由统一改造负责）。
      min 必须与下方 .doctor-grid 的列宽下限一致（324 = --grid-col-w），否则列数会跳。
    -->
    <CardGridSkeleton v-if="loading && !catalog.length" :count="6" :min="324" />

    <template v-else-if="catalog.length">
      <!--
        科室分类条：横向 chip，装在**一张 L1 白卡**里。
        为什么必须放进白卡：未选中态是「透明底 + --text-2」，悬停才给 --panel-bg。
        若把这条 chip 直接铺在页面底上，--panel-bg 与页面底只差约 1.04:1（等于隐形），
        整条筛选器会连悬停反馈一起消失（三级面硬规则①）。
        选中态走 L3 主色实底 + 白字（5.17:1）；**不用** --primary-soft 填底 ——
        「--primary 文字 + --primary-soft 底」只有 4.44:1，差一点不达 AA。
      -->
      <nav v-reveal="'stagger'" class="dept-bar" aria-label="按科室筛选医生">
        <button
          type="button"
          class="dept-chip"
          :class="{ active: !currentDept }"
          data-anim="chrome"
          @click="selectDept('')"
        >
          全部科室
        </button>
        <button
          v-for="g in catalog"
          :key="g.department"
          type="button"
          class="dept-chip"
          :class="{ active: g.department === currentDept }"
          data-anim="chrome"
          @click="selectDept(g.department)"
        >
          {{ g.department }}
        </button>
      </nav>

      <!-- 医生网格：`grid` recipe 按 90ms 依次揭示每张卡 -->
      <div v-if="visibleDoctors.length" v-reveal="'grid'" class="doctor-grid">
        <article v-for="d in visibleDoctors" :key="d.doctorId" class="doctor-card">
          <header class="dc-head">
            <RoleAvatar class="dc-avatar" role="doctor" :size="48" />
            <div class="dc-ident">
              <h3 class="dc-name">
                {{ d.doctorName }}
                <span v-if="d.title" class="dc-title">{{ d.title }}</span>
              </h3>
              <p class="dc-meta">
                <span class="dc-rating">★ <span class="mono">{{ d.rating?.toFixed(1) ?? '—' }}</span></span>
                · 接诊 <span class="mono">{{ d.currentLoad ?? 0 }}</span> 人次
              </p>
            </div>
          </header>

          <!-- 科室标识：DeptIcon 科室砖 + 科室名 -->
          <div v-if="d.department" class="dc-dept">
            <DeptIcon :name="d.department || ''" />
            <span class="dc-dept-name">{{ d.department }}</span>
          </div>

          <!-- 专长：描边 chip（不用实底，避免「主色文字压浅底」的对比度问题） -->
          <div v-if="tagsOf(d).length" class="dc-tags">
            <span v-for="t in tagsOf(d)" :key="t" class="dc-tag">{{ t }}</span>
          </div>

          <button type="button" class="consult-btn" @click="goChat(d)">
            {{ d.sessionId ? '继续聊天' : '发起咨询' }}
          </button>
        </article>
      </div>

      <!--
        空态：白卡 + --panel-bg 圆标 + 说明 + 「换个科室」。
        ⚠️ 圆标里的图形落 --panel-bg 上，只能用 --text-2 —— --text-3 在浅分区只有
           4.27:1，不到 AA（硬约定 #12 的对比度那一档）。
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
      <span class="link" @click="router.push('/messages')">我的消息</span> 里。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CardGridSkeleton from '@/components/CardGridSkeleton.vue'
import DeptIcon from '@/components/DeptIcon.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import PageHeader from '@/components/PageHeader.vue'
import { listDoctorCatalog } from '@/api/chat'
import type { ChatPeer, DepartmentGroup } from '@/types'

/**
 * 「医生咨询」页（`/doctors`）。
 *
 * 一级结构：页头 → 科室分类条（L1 白卡里的横向 chip）→ 医生卡网格。
 * 选中的科室用 `?dept=` 同步，不做成子路由：
 * ① 两个视图共用同一份「医生目录」数据，拆路由会让每个路由各自请求一次同样的接口；
 * ② 科室名是中文，放进路径段要反复编码解码，query 天然处理。
 * `?dept=` 缺省（或非法）时退化为「全部科室」，刷新与分享链接都能还原到同一个视图 ——
 * 这是 query 相对组件内 state 的唯一优势，也是这里选它的原因。
 *
 * <p>与 AI 推荐结果页（`/ai-recommend`）的分工：那边是「AI 替你挑好了」，
 * 这边是「你自己按科室挑」。两种意图都真实存在，合并会牺牲其中一边。
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
    ? `共 ${catalog.value.length} 个科室 · ${totalDoctors.value} 位医生，选好医生即可开始一对一沟通`
    : '按科室找到合适的医生，随时开始一对一沟通',
)

/** 单个医生的专长标签（后端存逗号分隔字符串，中英文逗号都容错） */
function tagsOf(d: ChatPeer): string[] {
  return (d.expertiseTags || '')
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
}

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
 * 曾经这里有一条 `watch(() => route.path, p => p === '/doctors' && load())`：
 * 它要求「本页还活着、而 path 又刚变成 /doctors」，可 path 一变本页就卸载，条件永假。
 * 这类**看似在补一个场景、实则永远不会触发**的守卫比没有更糟 —— 它会让人以为覆盖了
 * 返回刷新，从而不去检查真正负责它的是 `onMounted`。
 *
 * ⚠️ 数据新鲜度：离开再回来会重新拉。这是刻意的 —— 期间医生可能刚通过审核或被下架。
 */
onMounted(load)
</script>

<style scoped>
/* ---------- 科室分类条 ---------- */
.dept-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 18px;
  margin-bottom: var(--space-card);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/*
 * 未选中：透明底 + --text-2（它落在上方白卡内部，不是直接贴页面底，
 * 所以透明底成立）；悬停给 --panel-bg。
 * 选中：L3 主色实底 + 白字（5.17:1）。
 */
.dept-chip {
  height: 34px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 13.5px;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.dept-chip:hover {
  background: var(--panel-bg);
}

.dept-chip.active {
  background: var(--primary);
  border-color: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
}

.dept-chip.active:hover {
  background: var(--primary-dark);
  border-color: var(--primary-dark);
}

/* ---------- 医生卡网格 ---------- */
/*
 * 列宽取 --grid-col-w（324px，居民端卡片单列宽的唯一真源），卡间距取 --space-card。
 * 内容区最宽 1288px（--content-max 1344 − 两侧 --content-pad 28×2），
 * 于是每行 floor((1288 + 20) / (324 + 20)) = 3 列，每列约 416px —— 正好是
 * 「≥3 列、卡宽约 400px」的目标。窄屏由 auto-fill 自动降为 2 / 1 列，无需媒体查询。
 * ⚠️ 骨架屏 CardGridSkeleton 的 min 必须与这里的下限一致（324），否则列数会跳。
 */
.doctor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--grid-col-w), 1fr));
  gap: var(--space-card);
}

.doctor-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: var(--space-inner);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  /* 网格里的医生卡属于「内卡」→ --radius-sm(16)，不是页面级大卡的 --radius-lg */
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  transition:
    box-shadow var(--dur) var(--ease),
    transform var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.doctor-card:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}

.dc-head {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 尺寸与配色由 `RoleAvatar` 自理 */
.dc-avatar {
  flex: none;
}

.dc-ident {
  flex: 1;
  min-width: 0;
}

/* 卡片级小标题：走 --font-sans + 600（与 Home.vue 的 .card-head h3 同一约定），
   不继承全局 h1-h6 的宋体 */
.dc-name {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  font-family: var(--font-sans);
  font-size: 16.5px;
  font-weight: 600;
  color: var(--text-1);
}

/* 职称徽标：患者挑医生时最常看这一项。
   ⚠️ --primary-soft 底 + --primary 字只有 4.44:1（差一点不达 AA），
      所以文字改用同色相深一档的 --primary-dark（5.71:1）。 */
.dc-title {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-family: var(--font-sans);
  font-size: 11.5px;
  font-weight: 500;
}

/* 元信息落在白卡上 → --text-3 的 4.8:1 成立 */
.dc-meta {
  margin: 5px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.dc-rating {
  color: var(--text-1);
  font-weight: 600;
}

.dc-dept {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dc-dept-name {
  font-size: 13.5px;
  color: var(--text-2);
}

.dc-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* 专长标签：描边 chip，不用实底 */
.dc-tag {
  padding: 3px 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 12px;
}

/* 主色实底按钮贴底：卡片高低不一时按钮仍对齐同一水平线 */
.consult-btn {
  margin-top: auto;
  height: 40px;
  padding: 0 18px;
  border: 1px solid var(--primary);
  border-radius: var(--radius-pill);
  background: var(--primary);
  color: var(--on-primary);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.consult-btn:hover {
  background: var(--primary-dark);
  border-color: var(--primary-dark);
}

/* ---------- 空态 ---------- */
.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/* --panel-bg 圆标：卡内浅色分区只用底色区分，不再叠阴影（硬规则②）；
   图形色用 --text-2（--text-3 落 --panel-bg 上只有 4.27:1） */
.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  margin-bottom: 16px;
  border-radius: 50%;
  background: var(--panel-bg);
  color: var(--text-2);
}

.empty-mark svg {
  display: block;
  width: 26px;
  height: 26px;
}

.empty-title {
  margin: 0 0 6px;
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.empty-desc {
  margin: 0 0 18px;
  font-size: 13px;
  color: var(--text-2);
}

/* 次按钮：白底 + 控件描边，悬停转主色 */
.btn-ghost {
  height: 36px;
  padding: 0 20px;
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.btn-ghost:hover {
  border-color: var(--primary);
  color: var(--primary);
}

/* ---------- 页脚说明 ---------- */
/*
 * ⚠️ 直接贴页面底的文字不能用 --text-3（贴底只有 3.60:1，不到 AA）→ 用 --text-2（约 5.4:1）。
 * 行内的「我的消息」链接同理：--primary 贴底只有约 3.6~3.9:1，
 * 改用同色相深一档的 --primary-dark（约 4.6~5.0:1）。
 */
.footnote {
  margin: var(--space-block) 0 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-2);
}

.link {
  color: var(--primary-dark);
  cursor: pointer;
  text-decoration: underline;
}
</style>
