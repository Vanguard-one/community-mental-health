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
    </div>

    <!-- 圆点指示器 -->
    <div v-if="banners.length > 1" class="dots">
      <button
        v-for="(b, i) in banners"
        :key="b.id"
        class="dot"
        type="button"
        :class="{ active: i === current }"
        :aria-label="`第 ${i + 1} 张`"
        @click="goTo(i)"
      />
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
.banner-carousel {
  background: var(--card-bg);
  /*
   * 描边已按「纯图片展示」的要求去掉 —— 现在卡片的边界**只由 --shadow-card 定义**。
   *
   * 一个直接的后果：阴影是 diffuse 的，边缘不像描边那样有一条硬线。
   * 在白底与页面渐变交界处，这会让卡片的轮廓比其它卡片「软」一点点。
   * 这是去掉边框的必然代价，不是阴影没调好；若要硬边就只剩「加回 1px 描边」这一条路。
   */
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  /*
   * 内边距刻意比左侧「最近一次测评」面板（22px 24px）小一圈。
   *
   * 这块面板的主体就是一张图，容器留白多少会**直接**变成图片四周的白边；
   * 而左侧面板承载的是文字列表，同样的留白是它需要的呼吸感。
   * 两者「对齐」的直觉在这里是错的 —— 图片需要比文字更贴边才不显拥挤。
   * 12px 14px 是从 22px 24px 收下来的实测值：图片明显变大，卡片外框不变。
   */
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  /*
   * 关键：不要让卡片被拉伸到整行高度。
   *
   * .bottom 是 grid，两列的行高取 max(左卡, 右卡)。实测左卡（最近一次测评，
   * 含 RiskScale + 时间）约 290–310px，而右卡自身内容只有 ~277px —— 差出的
   * 那 15–35px 会全部堆在**圆点下方**，看起来就是「图片下面留白很多」。
   *
   * 那根本不是 padding 的问题（底部 padding 只有 14px），是 grid 的默认
   * stretch 造成的死空间。align-self: start 让卡片高度回归自身内容，
   * 底部白边随之消失。代价是两张卡底部不再齐平 —— 但对「图片」来说，
   * 不出现空洞远比和旁边卡片底部对齐重要。
   */
  align-self: start;
}

/*
 * 顶部标题条（.panel-head / .panel-title / .indicator）的样式已随模板一并删除。
 * 不要在这里留「以后可能用得上」的空规则 —— 卡片已经改成纯图片展示，
 * 文字一旦加回来就会破坏这个形态，届时应该重新设计而不是复活旧样式。
 */

/* ---------- 舞台 ---------- */
/*
 * 轮播区比例的唯一真源是 --banner-ratio（2.5:1）。
 *
 * 为什么必须在这里定比例，而不是让图片自己决定高度：
 * 「图片撑开容器」在首页是灾难 —— 轮播列实宽约 543px，一张 1080×1920 的竖图
 * 会让这一格涨到 970px 左右，整个首页被顶下去两屏。
 *
 * 为什么用 aspect-ratio 而不是 max-height：
 * max-height 只是「封顶」。图不够高时（比如 6:1 的细长图）容器缩到 90px，
 * 图被压得又扁又变形；而 aspect-ratio 是让**容器比例固定**，图由
 * object-fit: cover 去等比裁剪 —— 不管原图什么比例，看到的都是一张
 * 不变形的、取中间的 2.5:1 画面。
 */
.carousel-stage {
  position: relative;
  flex: 1;
}

.slide {
  position: relative;
  /* 比例只在这里定义一次；不写 height，否则会和 aspect-ratio 打架 */
  aspect-ratio: var(--banner-ratio);
  width: 100%;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--primary-soft);
}

.slide.clickable {
  cursor: pointer;
}

.slide-img {
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

/* 图片损坏时的占位：背景用主题色，文字用标题，至少信息不丢 */
.slide-fallback {
  height: 100%;
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
.dots {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 10px;
}

.dot {
  width: 6px;
  height: 6px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--card-border);
  cursor: pointer;
  transition: width var(--dur) var(--ease), background var(--dur) var(--ease);
}

.dot.active {
  width: 16px;
  border-radius: var(--radius-pill);
  background: var(--primary);
}

/* ---------- 空态 / 骨架 ---------- */
/* 空态沿用轮播的固定比例：否则「有 banner」和「没 banner」两种状态下
   右侧面板高度不一致，页面在数据到达的瞬间会跳一下 */
.carousel-empty {
  aspect-ratio: var(--banner-ratio);
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
  /* 同上：骨架屏也占满同一块高度，避免加载完成时布局抖动 */
  aspect-ratio: var(--banner-ratio);
  width: 100%;
  display: flex;
  align-items: center;
  overflow: hidden;
}
</style>
