<template>
  <div
    class="banner-carousel"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
  >
    <!--
      顶部标题条（「推荐活动」+ 1 / 3 计数）已按用户要求移除 ——
      这块面板改为「纯图片展示」：卡片里除了图，不留任何文字。
      位置信息仍有圆点承担（选中的那颗变长变蓝），不必再写一个数字计数。
    -->

    <!-- 加载中 -->
    <div v-if="loading" class="carousel-skeleton">
      <a-skeleton active :title="false" :paragraph="{ rows: 3 }" />
    </div>

    <!-- 空态 -->
    <div v-else-if="!banners.length" class="carousel-empty">
      <PictureOutlined class="empty-icon" />
      <p class="empty-text">暂无推荐内容</p>
    </div>

    <!-- 轮播主体 -->
    <div v-else class="carousel-stage">
      <div
        class="slide"
        role="button"
        :tabindex="activeBanner?.linkUrl ? 0 : -1"
        :class="{ clickable: !!activeBanner?.linkUrl }"
        @click="onSlideClick"
        @keydown.enter="onSlideClick"
      >
        <img
          v-if="!brokenIds.has(activeBanner!.id)"
          :src="resolveImage(activeBanner!.imageUrl)"
          :alt="activeBanner!.title"
          class="slide-img"
          @error="onImgError"
        />
        <!--
          图片 404/损坏时不留破图：换成一个纯色占位图标。
          ⚠️ 这里**不再有文字**（原本会显示标题 + 「图片加载失败」）——
             按「纯图片展示」的要求，卡片内不留任何文字。
             代价是：加载失败时用户只知道「这里有东西没出来」，不知道具体是哪一条；
             具体条目仍可在管理端列表里看到。
             标题没有丢：它仍然作为 img 的 alt 保留，读屏器与图片加载失败时都能拿到。
        -->
        <div v-else class="slide-fallback" role="img" :aria-label="activeBanner!.title">
          <PictureOutlined class="fallback-icon" />
        </div>
      </div>

      <!-- 左右切换按钮：只在多张时出现 -->
      <template v-if="banners.length > 1">
        <button
          class="nav-btn nav-prev"
          type="button"
          aria-label="上一张"
          @click.stop="prev"
        >
          <LeftOutlined />
        </button>
        <button
          class="nav-btn nav-next"
          type="button"
          aria-label="下一张"
          @click.stop="next"
        >
          <RightOutlined />
        </button>
      </template>

      <!--
        圆点指示器：已从卡片下方移到**图片内部**，以绝对定位叠加在图片底缘。

        这条改动连带三件事：
        1. 它不再占据布局高度 —— 卡片高度从此只由图决定，这才是能和左侧卡片
           对齐的前提（原来图 + 圆点 + 间距三者相加，高度天然比左卡矮一截）。
        2. 「图片内」意味着它会压在画面内容上，所以必须比图片高一层（z-index），
           否则会被 .slide-img 的层叠顺序盖住。
        3. 点击**不会**冒泡到 .slide 的跳转 —— 两者是兄弟节点，不是父子。
           尽管如此仍显式 stop：将来若有人把 dots 挪进 .slide 里，不加 stop
           就会变成「点圆点顺带跳走」。
      -->
      <div v-if="banners.length > 1" class="dots">
        <button
          v-for="(b, i) in banners"
          :key="b.id"
          class="dot"
          type="button"
          :class="{ active: i === current }"
          :aria-label="`第 ${i + 1} 张`"
          @click.stop="goTo(i)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/*
 * 首页轮播 banner。
 *
 * 为什么抽成独立组件而不是写在 Home.vue 里：
 * 轮播有「自动播放 + 手动切换 + 悬停暂停 + 边界回绕」四件事要管，
 * 塞进 Home.vue 会让那个本来只负责「拉数据、摆布局」的页面多出两屏定时器逻辑。
 * 抽出来之后，Home.vue 只需要给它一个 `banners` 数组。
 *
 * ⚠️ 刻意**不用** a-carousel：
 * 项目有一条硬约束 —— vendor-antd 打包含预算（800 kB）。a-carousel 会引入
 * 一整套 `slick` 样式与运行时，而这里的需求（淡入淡出 + 圆点 + 左右箭头）
 * 用 20 行 CSS transition 就能做到同等观感。零新增 antd 组件是明确取舍，
 * 不是偷懒。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { LeftOutlined, PictureOutlined, RightOutlined } from '@ant-design/icons-vue'
import type { Banner } from '@/types'

const props = withDefaults(
  defineProps<{
    banners: Banner[]
    loading?: boolean
    /** 自动轮播间隔（毫秒）。设为 0 则关闭自动播放 */
    interval?: number
  }>(),
  { loading: false, interval: 4000 },
)

const router = useRouter()

const current = ref(0)
/** 悬停暂停：用户把鼠标放在轮播上时停住，否则点「下一张」的瞬间可能刚好自动翻页 */
const paused = ref(false)
/** 图片加载失败的 id 集合：失败后回退成占位块，而不是留下一个破图 */
const brokenIds = ref<Set<number>>(new Set())

const activeBanner = computed<Banner | null>(() => props.banners[current.value] ?? null)

let timer: ReturnType<typeof setInterval> | null = null

function startTimer() {
  stopTimer()
  if (props.interval <= 0 || props.banners.length <= 1) return
  timer = setInterval(() => {
    if (paused.value) return
    next()
  }, props.interval)
}

function stopTimer() {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

/**
 * 回绕式切换。
 *
 * 用取模而不是「到末尾就停」：轮播的语义是循环，停在最后一张会让用户
 * 以为「没有更多了」。取模要处理负数（i - 1 在 i=0 时是 -1），
 * `(n + len) % len` 是必须的写法 —— 直接 `-1 % 3` 在 JS 里得到 -1 而不是 2。
 */
function goTo(index: number) {
  const len = props.banners.length
  if (len === 0) return
  current.value = ((index % len) + len) % len
}

function next() {
  goTo(current.value + 1)
}

function prev() {
  goTo(current.value - 1)
}

function onSlideClick() {
  const url = activeBanner.value?.linkUrl
  if (!url) return
  if (/^https?:\/\//i.test(url)) {
    // 外链新开标签：留在站内会把 SPA 整个替换掉，用户回不来
    window.open(url, '_blank', 'noopener,noreferrer')
  } else {
    router.push(url)
  }
}

function resolveImage(url: string): string {
  // 后端返回的是相对路径（/api/uploads/...）。dev 下 vite 代理会把 /api 转给后端，
  // 生产由 nginx 转发，两种情况都无需在此拼 host —— 拼了反而会在换域名时失效。
  return url
}

function onImgError() {
  const id = activeBanner.value?.id
  if (id !== undefined) {
    // 用新 Set 触发响应式（直接 add 同一个 Set 实例不会让 computed 重算）
    brokenIds.value = new Set([...brokenIds.value, id])
  }
}

/**
 * 列表变化时重置索引并重启定时器。
 *
 * 两处细节：
 * - `current` 可能越界（原来有 5 张、看到第 4 张，管理端删到只剩 2 张）→ 必须收敛。
 * - 定时器要跟着重启，否则「从 1 张变 3 张」时定时器还停留在「不播放」的状态
 *   （startTimer 在 length<=1 时直接 return，之后再没被调用过）。
 */
watch(
  () => props.banners.length,
  () => {
    if (current.value >= props.banners.length) {
      current.value = 0
    }
    startTimer()
  },
)

watch(() => props.interval, startTimer)

onMounted(startTimer)
// 必须清定时器：SPA 切走路由时组件卸载，但 setInterval 还挂在 window 上，
// 它会继续跑并持有对已卸载组件的引用 —— 内存泄漏 + 后台持续调用 next()
onBeforeUnmount(stopTimer)
</script>

<style scoped>
/*
 * 容器在视觉上必须「不存在」—— 它只负责把图、按钮、圆点竖着排好。
 *
 * 这里刻意**没有任何**会被看成「框」的属性：
 * - 无 background：原先的 --card-bg 白底，就是图片四周那圈白边的直接来源；
 * - 无 padding：原先 12px 14px 14px 的留白，会让图里外各有一层白；
 * - 无 box-shadow：阴影会在图外再造一圈边界，图就显得还是「嵌在一张卡里」；
 * - 无 border-radius：容器已经不可见，圆角只剩「给一个透明盒子倒角」的意义。
 *
 * 四角圆角由 .slide 自己承担（overflow: hidden + radius），那是**图片的形态**，
 * 不是容器的边框 —— 用户要的是去掉白框，不是把图切成直角。
 */
.banner-carousel {
  display: flex;
  flex-direction: column;
  /*
   * 高度**跟随左侧卡片**（按需求：两者底边对齐）。
   *
   * 实现方式是反向的：这里不给自己定高，而是接受 grid 行高的拉伸。
   * .bottom 的行高 = max(左卡, 右卡)，左卡（最近一次测评，含 RiskScale + 时间）
   * 实测约 290–310px，恒高于 banner 自身内容，所以行高实际由左卡决定 ——
   * 本项 stretch 到行高，就等于「和左卡一样高」。
   *
   * ⚠️ 与上一版**方向相反**：上一版刻意 align-self: start 避免被拉伸，
   * 那时是因为卡片有白底+内边距，多出来的高度会显示成图片下方的白边；
   * 现在容器透明、高度就是图片高度，拉伸反而正是想要的「对齐」。
   * **不要凭「怕变形」的直觉把它改回 start** —— 那会立刻退回「banner 比左卡矮一截」。
   */
  align-self: stretch;
  /*
   * flex 子项默认 min-height: auto，会让内容「撑住」而不允许收缩。
   * 配合下方 .carousel-stage 的 flex: 1，这里是让 stage 能真正被拉伸的前提。
   */
  min-height: 0;
}

/*
 * 顶部标题条（.panel-head / .panel-title / .indicator）的样式已随模板一并删除。
 * 不要在这里留「以后可能用得上」的空规则 —— 卡片已经改成纯图片展示，
 * 文字一旦加回来就会破坏这个形态，届时应该重新设计而不是复活旧样式。
 */

/* ---------- 舞台 ---------- */
/*
 * 轮播区：吃掉容器剩余的全部高度。
 *
 * 比例不再由这里定义 —— 高度是「外部给」的（grid 行高 = 左卡高度），
 * 所以本层只负责撑满，比例交给图片的 object-fit: cover 去适配。
 *
 * 这也意味着**图片的显示比例会随屏幕宽度浮动**：左卡在不同宽度下因文字换行
 * 高度略有变化，banner 跟着变。这是「与左卡对齐」的必然结果，不是抖动 ——
 * 若哪天要求回到固定的 2.5:1，就必须同时放弃底边对齐，二者不可兼得。
 */
.carousel-stage {
  position: relative;
  flex: 1;
  /* flex 默认 min-height: auto，不置 0 时 stage 会被图片内容撑住、无法收缩 */
  min-height: 0;
}

.slide {
  position: relative;
  /* 高度来自父级拉伸，不再用 aspect-ratio —— 两者同时写会互相打架 */
  height: 100%;
  width: 100%;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--primary-soft);
}

.slide.clickable {
  cursor: pointer;
}

/*
 * ⚠️ 图片必须**脱离文档流**（absolute + inset: 0），这不是为了定位，是为了高度。
 *
 * 若留在普通流里：slide 的高度 = 100%（依赖 stage），而 stage 在 grid 计算行高时
 * 还没有被拉伸，百分比无基准 → 退化成 auto → 图片按自身比例算出高度（宽 543、
 * 比例 1.79 → 约 303px）→ **banner 的贡献高度反而超过左卡**（约 290px）→
 * 行高被 banner 顶大 → 最终两张卡一样高，但都比「左卡自身高度」高，
 * 而且会随图片比例变化。表现就是「对齐看起来差一点，换张图又变一个值」。
 *
 * 改成 absolute 后，图片不再贡献高度：banner 的内容高度是 0，
 * grid 行高完全由左卡决定，stretch 之后才是真正的「和左卡一样高」。
 */
.slide-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  /* cover = 短边铺满、长边居中裁掉，绝不拉伸变形 */
  object-fit: cover;
  display: block;
  transform: scale(1);
  /* 淡入 + 轻微缩放：切图时不该有「闪一下白」的感觉 */
  transition:
    transform var(--dur) var(--ease),
    opacity var(--dur) var(--ease);
}

/*
 * 这里原本有一层底部渐变遮罩（.slide-mask）与标题条（.slide-caption）。
 * 2026-10-09 按用户要求**移除**：标题不再压在轮播图上。
 * 遮罩是为「白字压图」服务的，标题走了它就没有存在意义，一并删掉 ——
 * 留着它只会无谓地把图片底部压暗。
 * 标题现在只在管理端列表里出现（运营需要靠它区分条目）。
 */

/* 图片损坏时的占位：同样 absolute，理由与 .slide-img 一致 —— 它也不能贡献高度，
   否则「有图」和「图挂了」两种状态下列宽相同、行高不同，会出现同样的错位。 */
.slide-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--primary-soft);
  color: var(--text-2);
}

/* 占位只剩一个图标 —— 文字（标题 + 「图片加载失败」）按纯图片展示的要求去掉了。
   图标的透明度比原来略高（0.55 → 0.7）：没有文字分担视觉重量之后，
   0.55 会显得整块区域像「没渲染出来」。 */
.fallback-icon {
  font-size: 24px;
  opacity: 0.7;
}

.slide.clickable:hover .slide-img {
  transform: scale(1.02);
}

/* ---------- 左右按钮 ---------- */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: rgb(0 0 0 / 34%);
  color: #fff;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--dur) var(--ease), background var(--dur) var(--ease);
  padding: 0;
}

.carousel-stage:hover .nav-btn {
  opacity: 1;
}

.nav-btn:hover {
  background: rgb(0 0 0 / 56%);
}

.nav-prev {
  left: 10px;
}

.nav-next {
  right: 10px;
}

/* ---------- 圆点 ---------- */
/*
 * 绝对定位叠加在图片底部居中（DOM 上位于 .slide 之后，所以天然压在图上）。
 *
 * 三点注意：
 * - `left: 50% + translateX(-50%)` 而不是 `left: 0 + right: 0 + margin: auto`：
 *   前者不会把容器拉成整行宽，鼠标悬停范围就只在点附近，不会挡住图片的点击跳转。
 * - 不写 margin-top —— 它已经脱离文档流，任何外边距都对布局无效，只会误导后来者。
 * - z-index 显式给：.slide-img 上有 transform（悬停缩放），会建立层叠上下文，
 *   依赖默认顺序在个别浏览器/特定合成层下不够稳。
 */
.dots {
  position: absolute;
  left: 50%;
  bottom: 12px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  z-index: 2;
}

/*
 * 圆点形状：inactive 与 active 都是正圆，靠**颜色 + 轻微放大**区分。
 *
 * 上一版 active 是 16px 宽的胶囊（--radius-pill），按「改为圆点」的要求取消。
 * 放大用 transform 而不是改 width —— 后者会让相邻点跟着位移，切换时整排会轻微抖动。
 *
 * 颜色**保持原样**：inactive 仍是 --card-border，active 仍是 --primary。
 * 叠加到图片上之后，浅灰点在浅色画面里会看不清，所以只加了一层**投影**
 * （见下方 box-shadow）—— 投影不是填充色，不改变圆点本身的颜色。
 */
.dot {
  width: 7px;
  height: 7px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--card-border);
  /* 浅底图上靠投影勾出轮廓；深底图上投影几乎不可见，浅色填充本身已足够 */
  box-shadow: 0 1px 3px rgb(0 0 0 / 35%);
  cursor: pointer;
  transition:
    transform var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.dot.active {
  background: var(--primary);
  transform: scale(1.3);
}

/* 键盘可达性：叠加在图片上之后，焦点圈必须足够明显，否则 Tab 到这里等于没有反馈 */
.dot:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

/* ---------- 空态 / 骨架 ---------- */
/*
 * 两者都改成 flex: 1 —— 与图片态保持同一套高度规则。
 *
 * 上一版靠 aspect-ratio 定高。现在容器高度由「左侧卡片」决定，
 * 空态若还按比例算高度，就会出现「有 banner 时高、没 banner 时矮一截」，
 * 数据到达的瞬间整页跳一下。改成 flex: 1 后三条分支高度完全一致。
 */
.carousel-empty {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed var(--card-border);
  border-radius: var(--radius-sm);
  color: var(--text-3);
}

.empty-icon {
  font-size: 26px;
  opacity: 0.5;
}

.empty-text {
  font-size: 13px;
  margin: 0;
}

.carousel-skeleton {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  align-items: center;
  overflow: hidden;
}

/*
 * ---------- 单列布局回退 ----------
 *
 * 1100px 以下 Home.vue 把 .bottom 改成单列，此时**右侧没有「左卡」可以对齐**：
 * 容器高度由自身内容决定，而内容（stage flex: 1）又要靠容器高度 —— 互相依赖，
 * 结果是高度塌成 0，图片整个消失。
 *
 * 所以这个断点必须把固定比例还回来。判据很简单：
 * 「有没有并排的卡片」决定用拉伸还是用比例，不能一套规则走到底。
 */
@media (max-width: 1100px) {
  .banner-carousel {
    /* auto = 继承父级 .bottom 的 align-items: start，高度回归自身内容 */
    align-self: auto;
  }

  /*
   * ⚠️ 这一条不能漏。
   *
   * 桌面端 stage 是 `flex: 1`（= `flex: 1 1 0%`），它靠**父容器有确定高度**才能
   * 被拉伸。单列后父容器高度变成 auto（由内容决定），此时 flex-basis: 0 会让
   * stage 的 hypothetical 高度算成 0 —— 而 slide 又是绝对高度来源，
   * 于是整块塌成 0 高、图片完全消失。
   *
   * 改回 `flex: 0 0 auto`：高度交还给内容（slide 的 aspect-ratio）。
   */
  .carousel-stage {
    flex: 0 0 auto;
  }

  .slide {
    aspect-ratio: var(--banner-ratio);
    height: auto;
  }

  .carousel-empty,
  .carousel-skeleton {
    flex: 0 0 auto;
    aspect-ratio: var(--banner-ratio);
  }
}
</style>
