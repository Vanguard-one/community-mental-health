<template>
  <!--
    居民端骨架：左侧 64px 窄图标栏 + 页面内容。

    改版（2026-10-13）：
      · 原来一条 64px 顶栏横跨页面 + 一条 232px 侧栏，现在整个顶栏删除，
        导航全部收进 64px 窄栏（见 components/SideRail.vue）。
      · 顶栏删掉后，各页面的 `calc(100vh - 64px)` 全部要改回 `100vh`
        —— 这是一处**跨文件连锁**：问卷页、AI 问诊页、专有聊天页都写死过这个算式。
  -->
  <div class="app-shell">
    <SideRail
      :items="navItems"
      :active="activePath"
      home-path="/home"
      role="user"
      :user-tip="userTip"
    />

    <main class="app-body">
      <div class="content-layer">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  HomeOutlined,
  FileTextOutlined,
  MessageOutlined,
  TeamOutlined,
  MailOutlined,
  BellOutlined,
} from '@ant-design/icons-vue'
import SideRail from '@/components/SideRail.vue'
import type { RailItem } from '@/components/SideRail.vue'
import { useAuthStore } from '@/stores/auth'
import { useInboxStore } from '@/stores/inbox'

const route = useRoute()
const authStore = useAuthStore()
const inbox = useInboxStore()

/**
 * 导航结构（与角标无关的那部分）。
 * 图标一律取 @ant-design/icons-vue 的 Outlined 线框族 —— 与 AdminLayout 同一族，
 * 避免两个端一个实心一个描边。新增图标会让 vendor 体积上浮，改完必须真构建看数字（硬约定 #9）。
 */
const NAV: { path: string; label: string; icon: typeof HomeOutlined }[] = [
  { path: '/home', label: '首页', icon: HomeOutlined },
  { path: '/scales', label: '测评中心', icon: FileTextOutlined },
  { path: '/ai-chat', label: 'AI 问诊', icon: MessageOutlined },
  { path: '/doctors', label: '医生咨询', icon: TeamOutlined },
  { path: '/messages', label: '我的消息', icon: MailOutlined },
  { path: '/notifications', label: '通知', icon: BellOutlined },
]

/**
 * 只有「我的消息」带角标：其余导航项没有「等我处理的东西」这层语义，
 * 给它们加数字只会稀释角标的意义。
 * 数据来自 `inbox` store（服务端 `/chat/unread`），与医生端是同一个来源 ——
 * 所以「红点的样式与触发条件两端一致」不是靠约定，而是同一份数据 + 同一个组件。
 *
 * 「我的记录」**刻意不在这里**：它只允许从首页「最近一次测评」面板的
 * 「全部记录 →」进入（见 Home.vue）。导航栏是常驻入口，挂在这里等于
 * 让用户随时能绕开首页直达，与「单一入口」的产品意图相反。
 * 移出后该页面仍可正常访问（路由不变），只是不再有常驻入口。
 */
const navItems = computed<RailItem[]>(() =>
  NAV.map((item) =>
    item.path === '/messages'
      ? {
          ...item,
          badge: inbox.sessionCount,
          badgeTip: `${inbox.sessionCount} 位医生发来新消息`,
        }
      : { ...item },
  ),
)

const userTip = computed(() => `${authStore.realName || '居民'} · 居民用户`)

/**
 * 导航高亮。两条特殊规则，其余按精确匹配：
 * ① 作答页与结果页属于测评流程，高亮回落到「测评中心」；
 * ② 带子路径的页面跟随父级高亮 —— 专有聊天页 `/messages/:doctorId`
 *    必须让「我的消息」保持点亮，否则用户点进对话后会觉得「菜单把我丢在了外面」。
 * 另外「AI 推荐结果」`/ai-recommend` 是从 AI 问诊流程里跳出来的，高亮回落到「AI 问诊」。
 */
const activePath = computed(() => {
  const p = route.path
  const hit = NAV.find((item) => {
    if (item.path === '/scales') return p.startsWith('/scales') || p.startsWith('/assessment')
    if (item.path === '/ai-chat') return p.startsWith('/ai-chat') || p.startsWith('/ai-recommend')
    return p === item.path || p.startsWith(`${item.path}/`)
  })
  return hit?.path ?? ''
})

onMounted(() => {
  // 未读轮询由 store 自己管（引用计数），布局只负责开和关
  inbox.startPolling()
})

onBeforeUnmount(() => {
  inbox.stopPolling()
})
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
}

/*
 * 内容区。**不加 padding** —— 居民端各页最外层都是 .page-container
 * （global.css 里自带 var(--content-pad) 内边距 + --content-max 居中），
 * 这里再加一层会变成双倍内边距。
 * 高度用 100vh：顶栏已整个删除，内容区从视口顶端开始，不再需要减 --header-h。
 */
.app-body {
  margin-left: var(--rail-w);
  min-height: 100vh;
}

.content-layer {
  position: relative;
  z-index: 1;
}
</style>
