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

    <!--
      底部按钮区：设置 / 退出登录 在上，头像压在最下。

      <h3>为什么不再用 a-dropdown 把两个动作挂在头像上</h3>
      原来点头像弹一个 a-menu，两项藏在二级菜单里 —— 要点两次才够得到，而且
      「退出登录」这种会被频繁用到的动作藏在头像后面不符合直觉。
      拆成并排两个按钮后，一次点击到位，也顺带省掉 a-dropdown + a-menu 两个组件
      在这处的使用（vendor-antd 800 kB 预算，能省则省）。

      <h3>头像为什么只做展示、不再可点</h3>
      资料面板的入口已经由上方设置按钮承担，头像再挂一次点击就是**同一个动作的第二个入口** ——
      而侧栏头像的通行语义是「我是谁 / 切换账号」，点它弹出「编辑资料」会让人反复点到不该点的地方。
      纯展示之后，头像只保留一个作用：悬停告诉你当前登录的是谁（data-tip = 姓名）。
      ⚠️ 因此这里用 span 而不是 button：button 的语义是「能点」，视觉上还会带手型光标，
        那是对用户的承诺。保留 role="img" + aria-label 是为了让读屏用户仍能听到当前账号。
    -->
    <div class="rail-foot">
      <!--
        设置（个人资料）：与上方菜单项**同构** —— 直接复用 .rail-item（44×44、
        14px 圆角、悬停右弹 5px + 气泡提示），只把元素从 router-link 换成 button。
        data-tip 必须是「设置」两个字（需求指定），悬停即显。
      -->
      <button
        class="rail-item rail-act"
        type="button"
        data-tip="设置"
        aria-label="设置（个人资料）"
        @click="profileOpen = true"
      >
        <!--
          齿轮用描边（fill:none + currentColor）而不是实心：
          上方菜单项全是 antd 的线性图标，实心齿轮混在线性图标里会显得偏重。
        -->
        <svg
          viewBox="0 0 24 24"
          width="20"
          height="20"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="3" />
          <path
            d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"
          />
        </svg>
      </button>

      <!--
        退出登录：Uiverse 样例按钮（MUJTABA201566），**配色完全按样例**。

        形态：常态 44×44 正圆、白底、黑箭头；悬停转黑底、箭头反白、
        宽度 44 → 125 展开露出文字；按下整体位移 (2px, 2px)。

        为什么这里可以是「黑」，而全站按钮都不许用黑：
          ① 它不是主操作按钮，是侧栏里唯一的一个「反色」控件 —— 靠**明暗反转**
             而不是靠色相来和上方一列蓝色系导航区分，比之前的红更像「工具」而非「警示」；
          ② 白/黑都不带色相，落到半透明玻璃栏上不会像彩色那样发脏；
          ③ 两个状态的对比度都远超要求：黑图标压白底 ≈ 18:1，白图标压近黑底 ≈ 19:1。
      -->
      <!--
        aria-label 同时含中英两种写法，不是啰嗦：
        可见文本是 "Logout"，WCAG 2.5.3 要求可访问名称**包含**可见文本，
        否则语音控制用户说「点 Logout」会匹配不上；而后半段中文是给中文读屏用户的。
      -->
      <button class="logout-btn" type="button" aria-label="Logout 退出登录" @click="doLogout">
        <span class="sign">
          <svg viewBox="0 0 512 512" aria-hidden="true">
            <path
              d="M377.9 105.9L500.7 228.7c7.2 7.2 11.3 17.1 11.3 27.3s-4.1 20.1-11.3 27.3L377.9 406.1c-6.4 6.4-15 9.9-24 9.9c-18.7 0-33.9-15.2-33.9-33.9l0-62.1-128 0c-17.7 0-32-14.3-32-32l0-64c0-17.7 14.3-32 32-32l128 0 0-62.1c0-18.7 15.2-33.9 33.9-33.9c9 0 17.6 3.6 24 9.9zM160 96L96 96c-17.7 0-32 14.3-32 32l0 256c0 17.7 14.3 32 32 32l64 0c17.7 0 32 14.3 32 32s-14.3 32-32 32l-64 0c-53 0-96-43-96-96L0 128C0 75 43 32 96 32l64 0c17.7 0 32 14.3 32 32s-14.3 32-32 32z"
            />
          </svg>
        </span>
        <!-- 文案用样例原文 "Logout"（用户指定） -->
        <span class="text">Logout</span>
      </button>

      <span class="rail-user" :data-tip="userTip" role="img" :aria-label="userTip">
        <!-- 传 src：设了自定义头像就显图，没设自动回落角色图标（回落逻辑在组件内） -->
        <RoleAvatar :role="role" :size="38" :src="authStore.avatarUrl" />
      </span>
    </div>

    <!--
      资料面板挂在这里而不是各 Layout 里：
      三端（居民 / 医生 / 管理）都通过本组件渲染侧栏，挂这一处就等于三端都有了；
      挂到三个 Layout 里则会漏掉一个（管理端有顶栏、居民端没有，很容易只顾一端）。
      antd 的 modal 会 teleport 到 body，放在 <aside> 内部不影响它的定位与层级。
    -->
    <ProfileModal v-model:open="profileOpen" />
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import UnreadBadge from '@/components/UnreadBadge.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import ProfileModal from '@/components/ProfileModal.vue'
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

/** 资料面板开合。放在这里而不是 props：入口只有这一个，父级没有理由关心它 */
const profileOpen = ref(false)

/**
 * 退出登录。
 *
 * <p>刻意不加二次确认：登出是**可逆**动作（重新登录就回来了），与「清空会话并重置记忆」
 * 那种「删掉就找不回来」的不是同一档。给可逆动作套确认框会把确认框本身贬值 ——
 * 用户点几次都无痛通过之后，真正危险的那次也会闭眼点。
 *
 * <p>顺序是「先请求后跳转」：logout() 内部要把当前 token 丢进 Redis 黑名单，
 * 失败时它自己会兜住（不抛），所以这里不需要 try/catch 拦着不让用户走。
 */
async function doLogout() {
  await authStore.logout()
  message.success('已退出登录')
  router.push('/login')
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

/*
 * 底部按钮区（设置 / 退出 / 头像）。
 *
 * ⚠️ width 必须写死 44px，不能让它随内容撑开。
 *    退出按钮悬停时宽度 44 → 125，如果 .rail-foot 是 auto 宽，它会被一起撑到 125px，
 *    于是「居中对齐」的基准跟着变 —— 上方设置按钮和下方头像会同时横向偏移一格，
 *    表现为「鼠标一划到退出，整列图标就往左跳一下」。写死 44 之后，
 *    按钮只会在盒子内部溢出（没有 overflow: hidden，所以看得见），布局纹丝不动。
 */
.rail-foot {
  flex: none;
  width: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

/*
 * 设置按钮：复用 .rail-item 的外观与交互，但它是 <button> 不是 <a>，
 * 浏览器默认的 buttonface 底色 / padding / 字体族都得显式清掉，
 * 否则会看到一块灰底方块 —— 而 .rail-item 只在 :hover 里写 background，
 * 正常态完全依赖元素自身的透明背景。
 */
.rail-act {
  padding: 0;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
}

/* ---------- 退出登录（Uiverse 样例，白底黑箭头 → 悬停黑底反色） ---------- */
.logout-btn {
  /*
   * align-self: flex-start 是本组件唯一需要的「锚定」修正。
   * 样例按钮在 64px 栏里如果沿用居中对齐，宽度涨到 125 时是**向两边同时长**，
   * 左边那 30px 直接冲出视口被裁掉 —— 看起来像个残缺的按钮。
   * 锚定到左边缘后，44 → 125 只朝右（也就是朝内容区）展开。
   * 起点与上方菜单项对齐：栏宽 64、foot 宽 44，父级居中后正好 left = 10px。
   */
  align-self: flex-start;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  flex: none;
  width: 44px;
  height: 44px;
  padding: 0;
  border: none;
  /*
   * 常态是正圆：44×44 配 22px 半径 = 完美圆形（不是椭圆）。
   *
   * ⚠️ 故意写 22px 而不是 50%。50% 是百分比、悬停态是 40px 长度，
   *    两者不同单位时浏览器**无法插值**，鼠标移上去圆角会瞬间跳变一次，
   *    看起来像「闪了一下」。22px 与 40px 同为长度，过渡才连得上。
   */
  border-radius: 22px;
  cursor: pointer;
  overflow: hidden;
  /*
   * 底色 = --card-bg（#ffffff），与样例的 `background-color: white` 完全同值。
   * 不用 --chrome-bg-glass：那是「面」的玻璃色，套在 44px 的圆上等于透明，
   * 而样例要的正是一个**实心白圆**浮在栏上。
   */
  background-color: var(--card-bg);
  /*
   * 图标颜色用 color 传递（`.sign svg path` 取 currentColor），
   * 这样「变黑 / 反白」只需要过渡 color 一个属性，fill 会跟着逐帧重算。
   * 采样例的 black 用 --text-1（#0b1220）—— 全站最深的一档，观感与纯黑无异。
   */
  color: var(--text-1);
  box-shadow: 2px 2px 10px rgb(0 0 0 / 19.9%);
  /*
   * 文字基准。样例是 1em 继承，这里显式给 13px —— 因为 .rail 没有统一的 font-size，
   * 一旦继承到 20px（.rail-item 的值），.text 的 1.2em 会变成 24px。
   * 24px 下 "Logout" 六个字母约 85px，几乎顶满 87px 的展开宽度，
   * 再遇到字体回退就会从右边被 overflow: hidden 切掉一截。
   */
  font-size: 13px;
  /* 宽度 + 圆角 + 反色一起过 300ms：只动宽度的话，直角变胶囊会显得很突兀 */
  transition:
    width 0.3s var(--ease),
    border-radius 0.3s var(--ease),
    background-color 0.3s var(--ease),
    color 0.3s var(--ease);
}

/* 图标容器：常态占满整个按钮，悬停时收到 30% 让位给文字 */
.sign {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    width 0.3s var(--ease),
    padding-left 0.3s var(--ease);
}

.sign svg {
  width: 17px;
  display: block;
}

/*
 * 图标取 currentColor，而不是写死 fill —— 样例是 `fill: black` + 悬停 `fill: white`
 * 两条独立规则，那样反色是**硬切**的（fill 不在过渡列表里）。
 * 取 currentColor 后跟着父级 color 一起过渡，反色才是渐变的。
 */
.sign svg path {
  fill: currentColor;
}

/*
 * 文字：常态宽度 0 + 透明，悬停才铺开。
 * position: absolute 是必须的 —— 留在文档流里的话，它会在展开过程中
 * 一边撑宽一边挤压 .sign，图标会被推着往左跑，看起来像在抖。
 */
.text {
  position: absolute;
  right: 0;
  width: 0;
  opacity: 0;
  padding-right: 0;
  /* 反白字压悬停态的近黑底（≈19:1）。常态 opacity 为 0，所以白字不会出现在白底上 */
  color: var(--card-bg);
  font-size: 1.2em;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
  transition:
    opacity 0.3s var(--ease),
    width 0.3s var(--ease),
    padding-right 0.3s var(--ease);
}

.logout-btn:hover {
  width: 125px;
  border-radius: 40px;
  /*
   * 悬停整块反色：白底黑箭头 → 黑底白箭头（样例原样）。
   * 底色取 --text-1 而不是 #000：全站最深的一档，观感与纯黑一致，
   * 且不会多出一个「系统里只出现过一次」的硬编码色。
   */
  background-color: var(--text-1);
  color: var(--card-bg);
  /*
   * 抬高层级。上面的 .rail-item:hover 用 z-index: 3 是为了让气泡冒出来；
   * 退出按钮展开后是要**压在内容区上面**的，同样得抬。
   */
  z-index: 4;
}

.logout-btn:hover .sign {
  width: 30%;
  padding-left: 20px;
}

.logout-btn:hover .text {
  opacity: 1;
  width: 70%;
  padding-right: 10px;
}

.logout-btn:active {
  transform: translate(2px, 2px);
}

/*
 * 焦点圈不跟着按钮反色走。
 * 用主色而不是 --text-1：白底与黑底两种状态下主色都看得清，
 * 而黑圈在黑底上会消失、在白底上又会和图标糊成一片。
 */
.logout-btn:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

/*
 * 头像：纯展示元素（span），不再是按钮。
 *
 * 这里刻意**不写** cursor: pointer —— 手型光标是「这里能点」的承诺，
 * 点了没反应比光标是默认箭头更让人困惑。
 * position: relative 保留，它是 tooltip（::after）的定位基准。
 */
.rail-user {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: default;
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

  /*
   * 退出按钮的宽度/圆角展开是**布局变化**，减动效下直接跳变 —— 保留的话
   * 一个 44→125 的横向拉伸比位移更容易引起不适。
   * 但 opacity 的淡入留着：它承担「这里出现了文字」这个信息，不是装饰。
   */
  .logout-btn {
    /* 反色本身不是「动效」，它承担「你现在指着的是退出」这个信息，保留 */
    transition:
      background-color var(--dur) var(--ease),
      color var(--dur) var(--ease);
  }

  .sign,
  .text {
    transition: opacity var(--dur) var(--ease);
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
