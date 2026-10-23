<template>
  <!--
    角色头像：96 栅格身份图标 + 与科室图标砖同源的浅色渐变底衬。
    整块 `aria-hidden` —— 每一处用到它的地方旁边都已经有人名 / 角色文字，
    让读屏器再念一遍「医生」只会变成噪音。

    为什么四种角色共用一个组件：全站有 13 处圆形头像（顶栏、消息气泡、
    会话列表、居民列表、医生卡……）。若各自内联一份 SVG，
    「四套图标保持同一栅格与底衬」就只是口头约定，改一处必然漏一处。
  -->
  <span class="role-avatar" :class="`is-${role}`" :style="themeStyle" aria-hidden="true">
    <!--
      有自定义头像就显图，没有再回落角色图标。
      回落必须保留：上传的图可能被清理掉（孤儿文件清理、换部署丢磁盘），
      此时 avatarUrl 指向 404 —— 图标仍在，界面不会出现一个破图方块。
    -->
    <img v-if="showImg" class="ra-img" :src="src" alt="" @error="onImgError" />
    <svg
      v-else
      class="ra-svg"
      viewBox="0 0 96 96"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs v-if="role === 'ai'">
        <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#63AAFF" />
          <stop offset="1" stop-color="#1C54D6" />
        </linearGradient>
      </defs>

      <!-- AI 助手：圆角头身 + 大眼睛 + 腮红微笑 + 天线信号灯 -->
      <template v-if="role === 'ai'">
        <path d="M48 28.5 V 22.5" stroke="#9FD0FF" stroke-width="5" stroke-linecap="round" />
        <circle cx="48" cy="17" r="4.6" fill="#9FD0FF" />
        <rect x="12" y="43" width="9" height="19" rx="4.5" fill="#2C74F0" />
        <rect x="75" y="43" width="9" height="19" rx="4.5" fill="#2C74F0" />
        <rect x="20" y="27.5" width="56" height="49" rx="17" :fill="`url(#${gradId})`" />
        <circle cx="37" cy="48.5" r="8.6" fill="#FFFFFF" />
        <circle cx="59" cy="48.5" r="8.6" fill="#FFFFFF" />
        <circle cx="37.4" cy="49.4" r="4.4" fill="#12306E" />
        <circle cx="59.4" cy="49.4" r="4.4" fill="#12306E" />
        <circle cx="38.9" cy="47.4" r="1.6" fill="#FFFFFF" />
        <circle cx="60.9" cy="47.4" r="1.6" fill="#FFFFFF" />
        <ellipse cx="27" cy="60.5" rx="4.6" ry="3.3" fill="#FFAFC9" opacity="0.78" />
        <ellipse cx="69" cy="60.5" rx="4.6" ry="3.3" fill="#FFAFC9" opacity="0.78" />
        <path
          d="M42.5 62.5 C 45 66.6 51 66.6 53.5 62.5"
          stroke="#FFFFFF"
          stroke-width="3.4"
          fill="none"
          stroke-linecap="round"
        />
        <path
          d="M81 17.8 L82.008 20.992 L85.2 22 L82.008 23.008 L81 26.2 L79.992 23.008 L76.8 22 L79.992 20.992 Z"
          fill="#9FD0FF"
        />
        <path
          d="M14.5 28.9 L15.124 30.876 L17.1 31.5 L15.124 32.124 L14.5 34.1 L13.876 32.124 L11.9 31.5 L13.876 30.876 Z"
          fill="#9FD0FF"
        />
      </template>

      <!-- 医生：人形 + 医疗十字 -->
      <template v-else-if="role === 'doctor'">
        <circle cx="48" cy="35" r="14" fill="#1F7AFF" />
        <rect x="42.5" y="44" width="11" height="13" fill="#1F7AFF" />
        <path d="M20 84 C 20 67 32 55 48 55 C 64 55 76 67 76 84 Z" fill="#1F7AFF" />
        <path d="M45 60 h6 v7 h7 v6 h-7 v7 h-6 v-7 h-7 v-6 h7 Z" fill="#FFFFFF" />
      </template>

      <!-- 管理员：人形 + 盾牌对勾 -->
      <template v-else-if="role === 'admin'">
        <circle cx="48" cy="35" r="14" fill="#7C5CF6" />
        <rect x="42.5" y="44" width="11" height="13" fill="#7C5CF6" />
        <path d="M20 84 C 20 67 32 55 48 55 C 64 55 76 67 76 84 Z" fill="#7C5CF6" />
        <path
          d="M48 58.5 L 61.5 63 C 61.5 73.5 55.8 80 48 82.5 C 40.2 80 34.5 73.5 34.5 63 Z"
          fill="#FFFFFF"
        />
        <path
          d="M41.5 70.5 L 46 75 L 55 65.5"
          stroke="#7C5CF6"
          stroke-width="4.2"
          fill="none"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </template>

      <!-- 用户默认：纯人形（未设置头像时的缺省态） -->
      <template v-else>
        <circle cx="48" cy="35" r="14" fill="#6B7A90" />
        <rect x="42.5" y="44" width="11" height="13" fill="#6B7A90" />
        <path d="M20 84 C 20 67 32 55 48 55 C 64 55 76 67 76 84 Z" fill="#6B7A90" />
      </template>
    </svg>
  </span>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'

/**
 * 角色。`user` 同时承担「居民」与「未设置头像的缺省态」两种语义 ——
 * 它们在任何列表里都表达同一件事：「一个普通的人」，不需要拆成两个图标。
 */
type Role = 'ai' | 'doctor' | 'admin' | 'user'

const props = withDefaults(
  defineProps<{
    role?: Role
    /**
     * 直径（px）。**不传时不写内联尺寸**，改用 CSS 变量 `--ra-size`
     * （回落值 40px）—— 这样调用方的媒体查询才能把它改小。
     * 传了 `:size` 则内联样式优先级最高，媒体查询改不动它，二选一。
     */
    size?: number
    /**
     * 自定义头像 URL。传了就显图，不传（或加载失败）回落角色图标。
     *
     * 为什么做成这个组件的 prop 而不是新开一个 Avatar 组件：
     * 全站 13 处圆形头像共用同一个「缺省态」规则（四套图标、同一栅格、
     * 同一底衬）。新开一个组件，这两套就得各维护一份缺省逻辑，
     * 而缺省态恰恰是最容易被漏测的路径。
     */
    src?: string
  }>(),
  { role: 'user' },
)

/**
 * 加载失败标记。
 *
 * 为什么需要它：`<img>` 加载失败时浏览器会显示一个破图图标 + alt 文本，
 * 在 38px 的圆形头像里就是一块灰色噪点，比没有头像难看得多。
 * 这里记下失败的 URL 并在模板里回落 —— 但**不缓存全局**，
 * 否则用户重新上传后同一路径还能用时会一直被挡住。
 *
 * `watch(src)` 重置：同一个头像位换了新图（资料面板里刚上传），
 * 不清掉标记就会一直显示图标，看起来像「上传没生效」。
 */
const failedSrc = ref('')
watch(
  () => props.src,
  () => {
    failedSrc.value = ''
  },
)
function onImgError() {
  failedSrc.value = props.src || ''
}

/** 是否显图。加载失败的那个 URL 不显，直接落到图标分支 */
const showImg = computed(() => !!props.src && failedSrc.value !== props.src)

/**
 * AI 图案的渐变要一个 id。同一页面会渲染多个头像，
 * 写死 id 会让后来的实例引用到第一个实例的 `<defs>`（同值虽然看不出差别，
 * 但 DOM 里出现重复 id 是无效 HTML，且一旦将来改色就会「只有第一个变」）。
 */
const gradId = `ra-ai-grad-${useId()}`

/**
 * 角色配色（取自图标体系设计稿，与 `DeptIcon.vue` 的底衬规范同源）。
 *
 * 不放进 `tokens.css`：这四套是「图标主色 + 同族浅填充 + 底托渐变 + 辉光」
 * 的**成对色**，属于图标专属而非可换肤的语义色，拆成全局 token
 * 只会凭空多出十几个一次性变量（同《硬约定.md》#119）。
 */
const THEME: Record<Role, { bg: string; tint: string; glow: string }> = {
  ai: {
    bg: 'linear-gradient(140deg,#F0F6FF 0%,#DEEAFF 100%)',
    tint: '#2F7BFF',
    glow: 'rgba(47,123,255,.55)',
  },
  doctor: {
    bg: 'linear-gradient(140deg,#EFF6FF 0%,#DBEAFF 100%)',
    tint: '#1F7AFF',
    glow: 'rgba(31,122,255,.55)',
  },
  admin: {
    bg: 'linear-gradient(140deg,#F6F4FE 0%,#EAE5FE 100%)',
    tint: '#7C5CF6',
    glow: 'rgba(124,92,246,.55)',
  },
  user: {
    bg: 'linear-gradient(140deg,#F5F6F7 0%,#E7EAED 100%)',
    tint: '#6B7A90',
    glow: 'rgba(107,122,144,.55)',
  },
}

const themeStyle = computed(() => {
  const t = THEME[props.role]
  const style: Record<string, string> = {
    '--tile-bg': t.bg,
    '--tile-tint': t.tint,
    '--tile-glow': t.glow,
  }
  if (props.size) style['--ra-size'] = `${props.size}px`
  return style
})
</script>

<style scoped>
.role-avatar {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--ra-size, 40px);
  height: var(--ra-size, 40px);
  /* 圆形而不是设计稿的圆角方 —— 全站这 13 处头像本来就都是圆的，
     形状是既有约定，改形状会牵动顶栏、气泡、列表四类布局的对齐 */
  border-radius: 50%;
  background: var(--tile-bg);
  /*
   * 两道阴影各司其职，缺一不可：
   *   ① 内圈 1px 角色色描边 —— 浅渐变圆直接贴白卡只有 ~1.06:1，
   *      不描边会「化」进卡面（同《硬约定.md》#111 ①）。取值 30% 而不是设计稿的
   *      纯白高光：白环在白卡上等于没画。
   *   ② 外圈同色辉光 —— 与科室图标砖 `DeptIcon.vue` 用同一条公式，
   *      但收敛了一档（10px/22px/-12px → 4px/10px/-6px）：
   *      那是 60px 的砖块，这里最小只有 36px，照搬会让列表里的头像发虚。
   */
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--tile-tint) 30%, transparent),
    0 4px 10px -6px var(--tile-glow);
}

.ra-svg {
  display: block;
  width: 84%;
  height: 84%;
}

/*
 * 自定义头像。
 *
 * `object-fit: cover` 而不是 contain：用户传的图比例不可控（手机拍的 4:3、截图 16:9），
 * contain 会在圆里留出白边，看起来像「头像没铺满」。
 *
 * 尺寸取 100% 而不是像 .ra-svg 那样留 84%：图标留白是为了让图形在圆形底衬里
 * 显得有呼吸感；而照片本身就是满幅内容，再缩一圈只会变成一圈灰边。
 * 代价是照片会盖住底衬（--tile-bg）与内圈描边 —— 这正是想要的：
 * 有真实头像时，角色色底衬不再有信息价值。
 *
 * 圆角必须再写一次：外层虽然有 border-radius: 50%，但 img 自身是矩形，
 * 不给它圆角会出现方形图片的四角戳出圆形之外（部分浏览器不会按父级裁剪）。
 */
.ra-img {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}

/*
 * AI 助手图案自带天线、星光，几乎铺满整个 96 栅格；
 * 身份三件套的人形只占栅格中段（y 21..84）。
 * 两者缩放到同一个百分比，视觉上会一大一小 —— 按各自内容边界配平，
 * 这不是笔误：AI 66% / 身份 84%，最终可见图形都约占直径的 50%~55%。
 */
.role-avatar.is-ai .ra-svg {
  width: 66%;
  height: 66%;
}
</style>
