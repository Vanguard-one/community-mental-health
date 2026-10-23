<template>
  <!--
    医生端 / 管理端骨架：左侧 64px 窄图标栏 + 56px 半透明玻璃顶栏 + 内容区。

    改版（2026-10-13）：
      · 侧栏 232px（带文字菜单）→ 64px 窄图标栏，与居民端共用 SideRail.vue
      · 顶栏 64px 纯白 → 56px 半透明玻璃。顶栏与窄栏同为「结构面」，用同一档玻璃底，
        两侧在视觉上连成一体，内容区（纯白卡）从中浮出来。
      · 顶栏内容：左边是「PsyCare / 当前页」面包屑（原来只放了一个页面名，
        没有层级关系可读），右边保留角色徽标。
  -->
  <div class="app-shell">
    <SideRail
      :items="menuItems"
      :active="activePath"
      :home-path="homePath"
      :role="isAdmin ? 'admin' : 'doctor'"
      :user-tip="userTip"
    />

    <div class="app-body">
      <header class="topbar">
        <div class="crumb">
          <span>PsyCare</span>
          <i class="sep">/</i>
          <b>{{ pageTitle }}</b>
        </div>

        <div class="topbar-right">
          <span class="role-badge" :class="{ doctor: !isAdmin }">{{ roleDesc }}</span>
        </div>
      </header>

      <main class="content">
        <div class="content-layer">
          <router-view />
        </div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  DashboardOutlined,
  TeamOutlined,
  WarningOutlined,
  UserOutlined,
  MessageOutlined,
  DatabaseOutlined,
  PictureOutlined,
  FormOutlined,
  CalendarOutlined,
  BellOutlined,
} from '@ant-design/icons-vue'
import SideRail from '@/components/SideRail.vue'
import type { RailItem } from '@/components/SideRail.vue'
import { getPendingAuditCount } from '@/api/account'
import { useAuthStore } from '@/stores/auth'
import { useInboxStore } from '@/stores/inbox'

const route = useRoute()
const authStore = useAuthStore()

const isAdmin = computed(() => authStore.role === 'ADMIN')
const isDoctor = computed(() => authStore.role === 'DOCTOR')
const roleDesc = computed(() => (isAdmin.value ? '管理员' : '医生'))
const homePath = computed(() => (isAdmin.value ? '/admin/dashboard' : '/admin/alerts'))
const userTip = computed(() => `${authStore.realName || '未命名'} · ${roleDesc.value}`)

/**
 * 窄栏角标。
 *
 * 医生那一项不再由本布局自己数：数字来自 `inbox` store（服务端 `/chat/unread`），
 * 与居民端窄栏是同一个来源。以前是 `listSessions().filter(awaitingMyReply).length` ——
 * 那是「有几个人等我回」，而角标表达的是「有几条我没看」，两件事。
 * 现在「待回复」仍由会话列表的 await-tag 承担，语义各归各位。
 *
 * 管理员的待审核数是管理端独有的，与聊天无关，留在本地。
 */
const pendingAudit = ref(0)
const inbox = useInboxStore()

async function loadBadges() {
  // 仅 ADMIN：/admin/account 只对管理员开放，医生调它必定 403
  if (isAdmin.value) {
    try {
      pendingAudit.value = await getPendingAuditCount()
    } catch {
      pendingAudit.value = 0
    }
  }
}

onMounted(() => {
  loadBadges()
  inbox.startPolling()
})

onBeforeUnmount(() => {
  inbox.stopPolling()
})

/**
 * 菜单可见性。
 *
 * 每一项都显式写满 adminOnly / doctorOnly —— 不给默认值，
 * 是因为 TS 对数组字面量推断的是联合类型：只要有一项缺这个属性，
 * 后面的 `i.doctorOnly` 就会报「属性不存在」。写满反而最省事也最不容易漏。
 *
 * `badge` 依赖 `pendingAudit` / `inbox.sessionCount`，所以整张表是 computed；
 * 交给 SideRail 时再显式挑出它认识的字段（RailItem），不把权限标志漏进子组件。
 */
const ALL_MENU = computed<(RailItem & { adminOnly: boolean; doctorOnly: boolean })[]>(() => [
  {
    path: '/admin/dashboard',
    label: '数据驾驶舱',
    icon: DashboardOutlined,
    adminOnly: true,
    doctorOnly: false,
    badge: 0,
    badgeTip: '',
  },
  {
    // 评测筛查表：全站测评链路的入口（居民端能做什么测评由它决定）。
    // 纯管理职能，只给 ADMIN —— 后端 /scale/manage/** 与删除逐个断言 ADMIN。
    path: '/admin/scales',
    label: '筛查表管理',
    icon: FormOutlined,
    adminOnly: true,
    doctorOnly: false,
    badge: 0,
    badgeTip: '',
  },
  {
    path: '/admin/accounts',
    label: '账号管理',
    icon: TeamOutlined,
    adminOnly: true,
    doctorOnly: false,
    badge: pendingAudit.value,
    badgeTip: `${pendingAudit.value} 位医生待审核`,
  },
  {
    // 知识库（RAG 索引层）。仅管理员：索引是全局资源，改动影响所有居民拿到的回答。
    // 后端每个 /knowledge 接口都独立断言 ADMIN，这里不给入口只是「不给入口」。
    path: '/admin/knowledge',
    label: '知识库',
    icon: DatabaseOutlined,
    adminOnly: true,
    doctorOnly: false,
    badge: 0,
    badgeTip: '',
  },
  {
    // 首页轮播管理。与「知识库」同类：面向全体访客的统一展示位，
    // 改动影响所有人的首页，所以只给 ADMIN（后端 /banner 写接口逐个断言）。
    path: '/admin/banners',
    label: '轮播管理',
    icon: PictureOutlined,
    adminOnly: true,
    doctorOnly: false,
    badge: 0,
    badgeTip: '',
  },
  {
    path: '/admin/alerts',
    label: '预警工单',
    icon: WarningOutlined,
    adminOnly: false,
    doctorOnly: false,
    badge: 0,
    badgeTip: '',
  },
  {
    // 医患对话收件箱。与「用户信息」的分工：那边是主动找人（看患者档案），
    // 这边是被动接收（谁发了消息、谁还没回）。
    // 仅医生 —— 后端 /chat 限定 DOCTOR/RESIDENT，管理员进来只会拿到 403。
    path: '/admin/messages',
    label: '消息列表',
    icon: MessageOutlined,
    adminOnly: false,
    doctorOnly: true,
    badge: inbox.sessionCount,
    badgeTip: `${inbox.sessionCount} 位居民发来新消息`,
  },
  {
    // 医生看患者的入口。刻意不给管理员：管理员已有账号治理页，
    // 那边管的是「账号」，这里看的是「患者档案 + 评测问卷」，职责不同。
    path: '/admin/users',
    label: '用户信息',
    icon: UserOutlined,
    adminOnly: false,
    doctorOnly: true,
    badge: 0,
    badgeTip: '',
  },
  {
    // 预约设置：医生配自己的出诊时段与人数上限。居民端所有号源都来自这里，
    // 所以它是「医生能做什么」的一部分，不是管理职能 —— 管理员不给入口
    //（后端 /schedule 的写接口每个都独立断言 DOCTOR）。
    path: '/admin/schedule',
    label: '预约设置',
    icon: CalendarOutlined,
    adminOnly: false,
    doctorOnly: true,
    badge: 0,
    badgeTip: '',
  },
  {
    /*
     * 通知中心：医生在这里看「新预约」「预约已取消」，管理员在这里看系统与预警消息。
     * 与居民端 `/notifications` 是同一个组件、同一个接口 —— 通知从来就是「按登录者查自己的」。
     *
     * 角标口径与「消息列表」不同，所以两个各自都要有：
     *   · 消息列表 = 有未读消息的**会话数**（`/chat/unread`）
     *   · 通知     = 未读通知**条数**（`/notification/unread-count`，一条 COUNT）
     * 曾因为「未读数要额外一次请求」而不给通知加角标，代价是：医生收到「新预约」
     * 站内信时侧栏毫无反应，只能靠记忆去点开看。两个图标（Message / Bell）
     * 本来就分得开，不存在「分不清哪个是对话」的问题。
     */
    path: '/admin/notifications',
    label: '通知',
    icon: BellOutlined,
    adminOnly: false,
    doctorOnly: false,
    badge: inbox.noticeCount,
    badgeTip: `${inbox.noticeCount} 条未读通知`,
  },
])

const menuItems = computed<RailItem[]>(() =>
  ALL_MENU.value
    .filter((i) => (i.adminOnly ? isAdmin.value : i.doctorOnly ? isDoctor.value : true))
    .map((i) => ({
      path: i.path,
      label: i.label,
      icon: i.icon,
      badge: i.badge,
      badgeTip: i.badgeTip,
    })),
)

/** 高亮沿用「前缀匹配」：/admin/alerts/:id 这类详情路径要跟随父级 */
const activePath = computed(() => {
  const hit = menuItems.value.find((i) => route.path.startsWith(i.path))
  return hit?.path ?? ''
})

const pageTitle = computed(() => {
  const hit = menuItems.value.find((i) => i.path === activePath.value)
  return hit?.label ?? roleDesc.value
})
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-body {
  margin-left: var(--rail-w);
  min-height: 100vh;
}

/* ---- 顶栏（56px 玻璃条） ---- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  height: var(--header-h);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 var(--content-pad);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(var(--glass-blur));
  border-bottom: 1px solid rgba(213, 223, 235, 0.8);
}

.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-3);
}

.crumb .sep {
  font-style: normal;
  opacity: 0.6;
}

/* 面包屑末级用无衬线（与 global.css 的 h1-h6 宋体规则区分开）：
   它不是标题，是位置指示 */
.crumb b {
  color: var(--text-1);
  font-weight: 500;
  font-family: var(--font-sans);
}

.topbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ---- 角色徽标 ---- */
.role-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  color: var(--primary);
  white-space: nowrap;
}

/* 医生端用辅助紫区分角色 */
.role-badge.doctor {
  background: color-mix(in srgb, var(--accent-purple) 12%, transparent);
  color: var(--accent-purple);
}

/* ---- 内容区 ---- */
.content {
  position: relative;
  min-height: calc(100vh - var(--header-h));
  background: transparent;
  padding: var(--content-pad);
}

.content-layer {
  position: relative;
  z-index: 1;
}
</style>
