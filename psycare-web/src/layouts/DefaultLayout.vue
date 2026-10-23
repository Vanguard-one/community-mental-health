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
    <!--
      居民端整页插画底（2026-10-23）。纯装饰：`aria-hidden` + `pointer-events: none`，
      不参与布局、不接收事件，也不进入任何页面的尺寸计算。

      它挂在**布局根节点**而不是 body 上，这是对硬约定 #110（背景唯一真源在 body）
      的一次刻意例外，理由只有一个：**只有居民端要这张图**，管理端与登录页保持渐变。
      而 body 是全局的，改它就等于三端一起换。
      兜底关系没变：body 的 `--app-bg` 仍是页面底，这一层只是画在它上面的图，
      图画不出来（拦截、离线、解码失败）时看到的仍是渐变或 `--bg-art-fallback`。

      用 `position: fixed` 而不是 body 的 `background-attachment: fixed`：
      后者在移动端 Safari 上会被当成「相对整个文档高度」的定位区域，
      滚动时图会跳；再叠 `cover` 基本不可控。固定层没有这个问题。
    -->
    <div class="bg-art" aria-hidden="true" />

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
import { computed, onBeforeUnmount, onMounted, type Component } from 'vue'
import { useRoute } from 'vue-router'
import {
  HomeOutlined,
  FileTextOutlined,
  TeamOutlined,
  MailOutlined,
  BellOutlined,
} from '@ant-design/icons-vue'
import IconAi from '@/components/IconAi.vue'
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
 *
 * ⚠️ **唯一的例外是「AI 问诊」的 `IconAi`**（2026-10-23）。它不是 antd 图标，
 * 是本项目自绘的四角星芒（sparkles）。留这个例外的理由：
 *   ① AI 这个语义没有公认的 antd Outlined 对位物 —— 现取的 `MessageOutlined`
 *      与「我的消息」的 `MailOutlined` 在 20px 下几乎分不开（都是「一片纸」）；
 *   ② 它必须与 AI 问诊页顶部导航第一枚按钮的图标**完全同一形状**，两处共用
 *      `components/IconAi.vue` 一份几何；
 *   ③ 仍是**线性描边 + currentColor**，与 Outlined 族同语言，不是实心块。
 * 所以不要把它「顺手改回 antd 图标」—— 改回去会同时破掉 ①②。
 *
 * 类型从 `typeof HomeOutlined` 放宽成 `Component`：前者是单个具体组件类型，
 * 塞不进自绘组件。`RailItem.icon` 本来就是 `Component`，这里只是与之对齐。
 */
const NAV: { path: string; label: string; icon: Component }[] = [
  { path: '/home', label: '首页', icon: HomeOutlined },
  { path: '/scales', label: '测评中心', icon: FileTextOutlined },
  { path: '/ai-chat', label: 'AI 问诊', icon: IconAi },
  { path: '/doctors', label: '医生咨询', icon: TeamOutlined },
  { path: '/messages', label: '我的消息', icon: MailOutlined },
  { path: '/notifications', label: '通知', icon: BellOutlined },
]

/**
 * 只有「我的消息」和「通知」带角标：其余导航项没有「等我处理的东西」这层语义，
 * 给它们加数字只会稀释角标的意义。
 *
 * 两个角标口径不同，也不该相同：
 * - 「我的消息」是**有未读消息的会话数**（一位医生发来 5 条也只算 1）；
 * - 「通知」是**未读通知条数**（逐条计）。
 *   数据都来自 `inbox` store，与医生端是同一个来源 ——
 *   所以「红点的样式与触发条件三端一致」不是靠约定，而是同一份数据 + 同一个组件。
 *
 * ⚠️ 两张角标**不能合并成一个数字**：一个是会话数、一个是条数，
 * 合成一个之后「点开一条通知该减多少」就说不清了，用户也分不清
 * 那个数字是在说消息还是说通知（两个图标 Mail / Bell 本来就是分开的）。
 *
 * 「我的记录」**刻意不在这里**：它只允许从首页「最近一次测评」面板的
 * 「全部记录 →」进入（见 Home.vue）。导航栏是常驻入口，挂在这里等于
 * 让用户随时能绕开首页直达，与「单一入口」的产品意图相反。
 * 移出后该页面仍可正常访问（路由不变），只是不再有常驻入口。
 */
const navItems = computed<RailItem[]>(() =>
  NAV.map((item) => {
    if (item.path === '/messages') {
      return {
        ...item,
        badge: inbox.sessionCount,
        badgeTip: `${inbox.sessionCount} 位医生发来新消息`,
      }
    }
    if (item.path === '/notifications') {
      return {
        ...item,
        badge: inbox.noticeCount,
        badgeTip: `${inbox.noticeCount} 条未读通知`,
      }
    }
    return { ...item }
  }),
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
/*
 * 整页插画底。层级 0，内容区（.content-layer）是 1、侧栏（.rail）是 40，
 * 所以它天然在所有东西下面，不需要给任何页面抬 z-index。
 *
 * `background-color` 必须留着：JPEG 解码完成前浏览器会先画它，
 * 没有它会闪一帧白底（在浅色页面上像是「背景闪了一下」）。
 */
.bg-art {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-color: var(--bg-art-fallback);
  background-image: url('/app-bg-resident.jpg');
  background-repeat: no-repeat;
  /* 1920×1080：16:9 视口下 1:1 呈现（人物在左下、楼房在右下、中间是空云团），
     宽屏裁上下、竖屏裁左右 —— 竖屏正好只剩中间那片云，单栏内容不会和插画打架 */
  background-position: center center;
  background-size: cover;
}

/*
 * 压噪音的浅遮罩：插画是「蓝天 + 白云 + 人物 + 楼房」，卡与卡之间的窄缝会透出来。
 * 目的**不是**提对比度（实测白卡 vs 均亮已有 1.54:1，比原渐变的 1.32 还高），
 * 而是把细节压到「背景」这一档。要调浓淡只改 `--bg-art-veil` 一个 token。
 */
.bg-art::after {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--bg-art-veil);
}

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
