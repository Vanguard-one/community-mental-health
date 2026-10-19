<template>
  <!--
    左侧窄图标栏（SideRail）—— 三端共用的导航骨架。

    <h3>为什么抽成组件</h3>
    居民端与管理端原本各写一套侧栏：居民端 232px（带文字菜单），管理端 232px + 64px 顶栏。
    两套并存的直接后果是「改了一端忘另一端」——比如给居民端调了间距，管理端不会跟着变。
    改版后三端统一为 64px 窄图标栏，差异只剩「菜单项不同 / 有没有顶栏」，
    于是把结构、样式、交互全部收进这一个组件，两端只传数据。

    <h3>为什么菜单名改成悬浮提示</h3>
    64px 放不下「测评中心」这类四字菜单。方案有二：引入 a-tooltip，或纯 CSS 自绘。
    选自绘 —— vendor-antd 已在 800 kB 预算线上（实测 752 kB），一个 tooltip 不值得
    再往里塞几 kB；而且自绘的触发范围就是整个 44px 图标块，不需要额外的包裹元素。

    ⚠️ 提示气泡用的是 `::after` + `content: attr(data-tip)`，所以 `data-tip` 必须写在
       `.rail-item` 自身（不能写在里面的 svg 上）。aria-label 同样要写，
       否则读屏器拿到的是一个没有名字的链接。

    ⚠️ 窄栏是**半透明玻璃**（--chrome-bg-glass + backdrop-filter），与内容区纯白卡形成
       「结构半透明 / 内容纯白」两级。用它的元素必须成对写 backdrop-filter（硬约定 #114）；
       且**不要**手写 -webkit- 前缀（#113），构建链会自动补。
  -->
  <aside class="rail">
    <button class="rail-logo" type="button" :aria-label="brandLabel" @click="router.push(homePath)">
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path
          d="M2 12h4l2.5-6 3 12 3-9 2 3h5.5"
          fill="none"
          stroke="#fff"
          stroke-width="2.1"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </button>

    <nav class="rail-nav">
      <router-link
        v-for="item in items"
        :key="item.path"
        :to="item.path"
        class="rail-item"
        :class="{ 'is-active': item.path === active }"
        :data-tip="item.label"
        :aria-label="item.label"
      >
        <component :is="item.icon" />
        <!--
          角标一律走 UnreadBadge（rail 形态）—— 居民端与管理端是同一个组件、
          同一个数据源，「两端红点一致」由结构保证，不靠两边照着写。
        -->
        <UnreadBadge
          v-if="item.badge"
          mode="rail"
          :count="item.badge"
          :tip="item.badgeTip || item.label"
        />
      </router-link>
    </nav>

    <!-- 底部用户卡：撑住窄栏高度，避免菜单被垂直居中 -->
    <div class="rail-foot">
      <a-dropdown placement="topRight">
        <button class="rail-user" type="button" :data-tip="userTip" :aria-label="userTip">
          <RoleAvatar :role="role" :size="38" />
        </button>
        <template #overlay>
          <a-menu @click="onMenuClick">
            <a-menu-item key="logout" danger>退出登录</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import UnreadBadge from '@/components/UnreadBadge.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { useAuthStore } from '@/stores/auth'

export interface RailItem {
  path: string
  label: string
  icon: Component
  /** 未读数。0 或省略时不显示角标 */
  badge?: number
  /** 角标的悬浮说明，如「3 位居民发来新消息」 */
  badgeTip?: string
}

const props = withDefaults(
  defineProps<{
    items: RailItem[]
    /** 当前应高亮的 `path`。由父级计算 —— 高亮回落规则（如 /assessment/* 归到 /scales）
        属于路由语义，写在各自的 Layout 里比塞进组件更清楚 */
    active: string
    /** 点 logo 去哪 */
    homePath: string
    /** 底部头像的角色 */
    role: 'user' | 'doctor' | 'admin'
    /** 底部头像悬浮提示，如「张伟 · 居民用户」 */
    userTip?: string
  }>(),
  { userTip: '', role: 'user' },
)

const router = useRouter()
const authStore = useAuthStore()

const brandLabel = 'PsyCare 首页'
const userTip = computed(() => props.userTip || authStore.realName || '我的账号')

async function onMenuClick({ key }: { key: string | number }) {
  if (key === 'logout') {
    await authStore.logout()
    message.success('已退出登录')
    router.push('/login')
  }
}
</script>

<style scoped>
.rail {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 40;
  width: var(--rail-w);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 14px 0 16px;
  background: var(--chrome-bg-glass);
  backdrop-filter: blur(var(--glass-blur));
  /* 只朝右描一条亮边：窄栏贴在页面底上，用「亮边」而不是投影来切分，
     投影在 64px 宽的元素上会糊成一条灰带 */
  border-right: 1px solid rgba(255, 255, 255, 0.8);
}

.rail-logo {
  width: 40px;
  height: 40px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 14px;
  background: var(--grad-primary);
  box-shadow: var(--shadow-primary);
  cursor: pointer;
  margin-bottom: 18px;
  padding: 0;
}

.rail-nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.rail-item {
  position: relative;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  border: 1px solid transparent;
  color: var(--ink);
  font-size: 20px;
  text-decoration: none;
  /*
   * transform 必须进过渡列表。
   * 典型漏写：只在 :hover 里加了 transform、没把它加进 transition，
   * 结果位移是「瞬间跳过去」而不是「弹出去」—— 看起来像卡了一下，而不是动画。
   */
  transition:
    background var(--dur) var(--ease),
    color var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

/*
 * 悬停：整块朝**右**弹出 + 浮起阴影。
 *
 * ⚠️ 方向是这处改动最需要判断的地方。参考样例是横向表情条，`-translate-y-5`（向上 20px）
 *    在那里等于「离开条、朝你靠近」。但这里是 64px 的**纵向**导航栏 ——
 *    上下平移会被读成「图标在栏里往上滑」，语义完全不同（甚至像滚动）。
 *    纵向栏里同义的「弹出」方向只有一个：朝内容区，也就是 +X。
 *
 * 位移只给 5px：栏宽 64、块宽 44，左右各剩 10px；给到 10px 就会顶到栏的描边上，
 * 看起来像要掉出去。5px 刚好是「确实动了」但仍在栏内的量。
 *
 * 阴影用 --shadow-hover 而不是自己拼一个深色投影：样例的 shadow-xl 是中性投影，
 * 而半透明玻璃栏上叠彩色投影会发脏（同 .rail 不用投影的那条判据）。
 *
 * z-index 必须显式抬高。`transform` 会让元素成为**层叠上下文**，
 * 里面那条 `z-index: 60` 的提示气泡从此只能在**本元素内部**比较层级 ——
 * 不做这一步，气泡会被后面几个菜单项盖住（表现为「提示只露一半」）。
 */
.rail-item:hover {
  background: rgba(255, 255, 255, 0.7);
  color: var(--primary);
  transform: translateX(5px);
  box-shadow: var(--shadow-hover);
  z-index: 3;
}

/*
 * 图标本体再单独放大。
 *
 * 为什么放大图标、而不是放大整个 44px 的块：块一旦 scale，里面的提示气泡
 * （`::after` 是块的子元素）会跟着放大 —— 12.5px 的文字被拉到 14px 以上，
 * 边缘会发虚；而且块放大到 1.25 就会与上下相邻项（间距只有 6px）挤在一起。
 * 只放大图标，就能在「不动布局」的前提下保留样例那种「弹一下」的手感。
 *
 * `:deep(svg)` 是必须的：`<component :is="item.icon" />` 渲染出的是
 * antd 的 `.anticon > svg`，scoped 样式拿不到子组件内部的元素。
 */
.rail-item :deep(svg) {
  transition: transform var(--dur) var(--ease);
}

.rail-item:hover :deep(svg) {
  transform: scale(1.18);
}

/*
 * 选中态：半透明栏上浮一块纯白 + 主色描边。
 * 为什么不用「浅蓝底 + 主色文字」（改版前的做法）：--primary-soft 落在半透明玻璃栏上
 * 只有约 1.04:1，等于没画；白块 + 主色描边在白玻璃上有明确的两道信号（面 + 线）。
 */
.rail-item.is-active {
  background: #fff;
  border-color: var(--primary);
  color: var(--primary);
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.16);
}

/* 悬浮提示：纯 CSS 自绘，不引入 a-tooltip */
.rail-item::after,
.rail-user::after {
  content: attr(data-tip);
  position: absolute;
  left: calc(100% + 12px);
  top: 50%;
  /*
   * 与主色块同向「弹出」：从左侧滑入 + 略微放大。
   * `transform-origin: left center` 是关键 —— 默认从中心缩放时，气泡会从中间
   * 往两边长；锚在左边才像「从图标旁边冒出来」。
   * 起点用 scale(0.9) 而不是 0：从 0 开始放大在 12.5px 的小字上会有一帧发虚。
   */
  transform: translate(-6px, -50%) scale(0.9);
  transform-origin: left center;
  padding: 6px 10px;
  border-radius: 8px;
  background: #0b1220;
  color: #fff;
  font-size: 12.5px;
  font-weight: 400;
  line-height: 1.4;
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  z-index: 60;
  transition:
    opacity var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.rail-item:hover::after,
.rail-user:hover::after {
  opacity: 1;
  transform: translate(0, -50%) scale(1);
}

.rail-foot {
  flex: none;
}

.rail-user {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
}

/* 头像本身的尺寸与配色由 RoleAvatar 自理，这里只管它在窄栏里的排布 */

/*
 * 减动效：去掉位移与缩放，**保留颜色与提示的淡入**。
 * 颜色变化不是「动效」，它承担「你正指着哪一项」这个信息；提示气泡的淡入同理。
 * 要砍的只是位移和放大 —— 那才是会引起前庭不适的部分。
 */
@media (prefers-reduced-motion: reduce) {
  .rail-item,
  .rail-item :deep(svg),
  .rail-item::after,
  .rail-user::after {
    transition:
      opacity var(--dur) var(--ease),
      color var(--dur) var(--ease),
      background var(--dur) var(--ease),
      border-color var(--dur) var(--ease),
      box-shadow var(--dur) var(--ease);
  }

  .rail-item:hover {
    transform: none;
  }

  .rail-item:hover :deep(svg) {
    transform: none;
  }

  .rail-item:hover::after,
  .rail-user:hover::after {
    transform: translate(0, -50%);
  }
}
</style>
