<template>
  <!--
    科室图标砖：浅色渐变底托 + 96 栅格彩色线条图标。
    整块 `aria-hidden` —— 卡片上已经有科室名文字，图标是纯装饰，
    让读屏器再念一遍「心理科」只会变成噪音。
  -->
  <span class="dept-icon" :class="{ 'is-fallback': !key }" :style="themeStyle" aria-hidden="true">
    <svg v-if="key" class="dept-icon-svg" viewBox="0 0 96 96" fill="none" xmlns="http://www.w3.org/2000/svg">
      <!-- 心理科：一颗心 + 心电波 -->
      <template v-if="key === 'psychology'">
        <path
          d="M48 78 C 30 64 14 50 14 34 C 14 21 24 12 35 12 C 41 12 46 15 48 19 C 50 15 55 12 61 12 C 72 12 82 21 82 34 C 82 50 66 64 48 78 Z"
          fill="none" stroke="#1F7AFF" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M28 43 H39 L44 33 L51 53 L56 43 H68"
          fill="none" stroke="#8FC0FF" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
      </template>

      <!-- 儿童青少年心理科：一大一小两个人 + 一颗星 -->
      <template v-else-if="key === 'child'">
        <circle cx="36" cy="30" r="11" fill="none" stroke="#FF9A2E" stroke-width="5.5" />
        <path
          d="M18 72 C 18 57 26 48 36 48 C 46 48 54 57 54 72"
          fill="none" stroke="#FF9A2E" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <circle cx="68" cy="46" r="8.5" fill="none" stroke="#FF9A2E" stroke-width="5.5" />
        <path
          d="M56 80 C 56 69 61 62 68 62 C 75 62 80 69 80 80"
          fill="none" stroke="#FF9A2E" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M76 10 L78.5 16.6 L85.5 16.9 L80 21.3 L81.9 28.1 L76 24.2 L70.1 28.1 L72 21.3 L66.5 16.9 L73.5 16.6 Z"
          fill="#FFCE85" stroke="none"
        />
      </template>

      <!-- 睡眠医学科：一弯月 + 两颗星 -->
      <template v-else-if="key === 'sleep'">
        <path
          d="M60 14 C 38 18 22 32 22 50 C 22 68 38 82 60 80 C 42 72 34 62 34 48 C 34 34 44 22 60 14 Z"
          fill="#4B4DE0" stroke="none"
        />
        <path d="M72 18 L75.5 26 L72 34 L68.5 26 Z" fill="#A9ABFF" stroke="none" />
        <path d="M82 40 L84.5 46 L82 52 L79.5 46 Z" fill="#A9ABFF" stroke="none" />
      </template>

      <!-- 精神科：一个想事情的脑袋 + 内部旋纹 -->
      <template v-else-if="key === 'psychiatry'">
        <path
          d="M46 16 C 36 16 30 22 30 28 C 24 30 20 36 22 44 C 20 50 24 56 30 58 C 31 66 38 72 48 72 C 58 72 65 66 66 58 C 72 56 76 50 74 44 C 76 36 72 30 66 28 C 66 22 60 16 50 16 Z"
          fill="none" stroke="#8A5CF6" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M44 28 C 36 30 35 38 41 42 C 47 46 47 52 41 56"
          fill="none" stroke="#C4A8FF" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M57 30 C 63 32 63 39 57 42"
          fill="none" stroke="#C4A8FF" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <circle cx="34" cy="52" r="3.5" fill="#C4A8FF" />
        <circle cx="62" cy="52" r="3.5" fill="#C4A8FF" />
      </template>

      <!-- 全科：听诊器 -->
      <template v-else-if="key === 'general'">
        <circle cx="28" cy="18" r="4.5" fill="#7FDDE5" />
        <circle cx="68" cy="18" r="4.5" fill="#7FDDE5" />
        <path
          d="M28 22 V 40 C 28 52 36 60 48 60 C 60 60 68 52 68 40 V 22"
          fill="none" stroke="#00A9B8" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M48 60 V 68 C 48 77 53 81 60 81"
          fill="none" stroke="#00A9B8" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <circle cx="68" cy="74" r="9" fill="none" stroke="#00A9B8" stroke-width="5.5" />
        <circle cx="68" cy="74" r="3.5" fill="#7FDDE5" />
      </template>

      <!-- 危机干预门诊：急救标志 + 外扩信号波 -->
      <template v-else-if="key === 'crisis'">
        <g transform="rotate(-38 48 50)" fill="#E5324F" stroke="none">
          <rect x="34" y="16" width="22" height="26" rx="11" />
          <rect x="34" y="58" width="22" height="26" rx="11" />
          <rect x="39" y="36" width="12" height="28" rx="6" />
        </g>
        <path
          d="M68 26 A 16 16 0 0 1 84 42"
          fill="none" stroke="#FF9AA9" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M72 15 A 26 26 0 0 1 91 34"
          fill="none" stroke="#FF9AA9" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
      </template>

      <!-- 康复科：活动的人 + 向上的弧线 -->
      <template v-else-if="key === 'rehab'">
        <circle cx="34" cy="24" r="10" fill="none" stroke="#0EAF7B" stroke-width="5.5" />
        <path d="M34 34 V 54" fill="none" stroke="#0EAF7B" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round" />
        <path
          d="M20 40 C 26 46 42 46 48 40"
          fill="none" stroke="#0EAF7B" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M34 54 C 31 62 28 68 24 74"
          fill="none" stroke="#0EAF7B" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M34 54 C 37 62 40 68 44 74"
          fill="none" stroke="#0EAF7B" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M54 66 C 63 58 68 47 64 35"
          fill="none" stroke="#7FE3BE" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M57 39 L64 35 L67 42"
          fill="none" stroke="#7FE3BE" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
      </template>

      <!-- 心身医学科：圆环里一颗心 + 托举的弧线 -->
      <template v-else-if="key === 'psychosomatic'">
        <circle cx="48" cy="44" r="26" fill="none" stroke="#D9489F" stroke-width="5.5" />
        <path
          d="M48 56 C 40 50 33 44 33 37 C 33 32 37 29 41 29 C 44 29 47 31 48 34 C 49 31 52 29 55 29 C 59 29 63 32 63 37 C 63 44 56 50 48 56 Z"
          fill="#D9489F" stroke="none"
        />
        <path
          d="M24 84 C 24 74 34 68 48 68 C 62 68 72 74 72 84"
          fill="none" stroke="#FF9BD1" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
      </template>

      <!-- 老年心理科：长者 + 拐杖 -->
      <template v-else-if="key === 'geriatric'">
        <circle cx="40" cy="30" r="13" fill="none" stroke="#C0801A" stroke-width="5.5" />
        <circle cx="34.5" cy="31" r="5.5" fill="none" stroke="#F0C878" stroke-width="4.5" />
        <circle cx="46" cy="31" r="5.5" fill="none" stroke="#F0C878" stroke-width="4.5" />
        <path
          d="M22 80 C 22 66 30 58 40 58 C 50 58 58 66 58 80"
          fill="none" stroke="#C0801A" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <path
          d="M74 34 C 74 27 66 27 66 34 L 66 74"
          fill="none" stroke="#F0C878" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"
        />
      </template>

      <!-- 中医心理科：太极 + 一叶草药 -->
      <template v-else-if="key === 'tcm'">
        <circle cx="44" cy="50" r="27" fill="none" stroke="#17694F" stroke-width="5.5" />
        <path
          d="M44 23 C 57 23 57 50 44 50 C 31 50 31 77 44 77"
          fill="none" stroke="#17694F" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"
        />
        <circle cx="44" cy="36.5" r="5" fill="#5FB894" />
        <circle cx="44" cy="63.5" r="5" fill="#5FB894" />
        <path d="M62 30 C 62 22 68 14 80 12 C 82 22 74 30 62 30 Z" fill="#5FB894" stroke="none" />
      </template>
    </svg>

    <!-- 兜底：后端出现图标组未覆盖的新科室时，退回「首字方块」，不留空白 -->
    <span v-else class="dept-icon-letter">{{ letter }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 科室图标砖（医疗科室图标体系，96 栅格 / 5.5px 描边 / 双色阶）。
 *
 * <p>为什么内联 SVG 而不是放 `public/` 用 `<img>`：这 10 个图标要**原样保留各自的多色**，
 * 但同时得能跟着卡片尺寸缩放、且不额外产生 10 个 HTTP 请求。内联后由 CSS 统一控制
 * 外框尺寸，矢量放大到任意倍率都不糊。
 *
 * <p>为什么按「特征词」匹配而不是从接口拿图标名：后端 `department` 是自由文本
 * （`varchar(32)`，没有字典表约束），拿不到稳定的图标标识。所以这里用**有序特征词**匹配 ——
 * 顺序是**从具体到宽泛**，「儿童青少年心理科」必须先于「心理科」命中，
 * 否则所有带「心理」二字的科室都会被吸到同一个图标上。
 *
 * <p>匹配不上时的兜底不是空白，而是回到本项目原有的「主色软底 + 科室首字」方块，
 * 保证后端新增科室时这里不会破相。
 */
const props = defineProps<{ name: string }>()

/**
 * 特征词表。**顺序即优先级**：越靠前越具体。
 *
 * 新增科室时的维护动作只有一条 —— 把新特征词插到比它更宽泛的那个词**之前**。
 */
const PATTERNS: { key: string; match: RegExp }[] = [
  { key: 'child', match: /儿童|青少年|少儿|未成年/ },
  { key: 'geriatric', match: /老年|老龄|长者/ },
  { key: 'sleep', match: /睡眠|失眠/ },
  { key: 'crisis', match: /危机|干预|急诊/ },
  { key: 'psychosomatic', match: /心身/ },
  { key: 'rehab', match: /康复/ },
  { key: 'tcm', match: /中医/ },
  { key: 'psychiatry', match: /精神/ },
  { key: 'general', match: /全科|综合|普通/ },
  // 最后兜底：「心理」是最宽的一类，放最后才不会抢走上面那些更具体的科室
  { key: 'psychology', match: /心理/ },
]

/** 命中的图标标识；未命中时为空串（走兜底分支） */
const key = computed(() => {
  const n = props.name || ''
  for (const p of PATTERNS) {
    if (p.match.test(n)) return p.key
  }
  return ''
})

/** 兜底方块上的字：科室首字 */
const letter = computed(() => (props.name || '科').charAt(0))

/**
 * 每个科室的底托配色（取自图标体系设计稿）。
 *
 * `bg` 是 140° 的浅色渐变，`glow` 是与图标主色同源的柔和辉光 ——
 * 两者都不是装饰：**砖块与白卡片的对比只有 1.05~1.15:1**，
 * 靠外圈辉光才能在卡面上「浮」起来（同硬约定 #111 ②的思路）。
 */
const THEME: Record<string, { bg: string; glow: string }> = {
  psychology: { bg: 'linear-gradient(140deg,#EFF6FF 0%,#DBEAFF 100%)', glow: 'rgba(31,122,255,.55)' },
  child: { bg: 'linear-gradient(140deg,#FFF8F0 0%,#FFEFDE 100%)', glow: 'rgba(255,154,46,.55)' },
  sleep: { bg: 'linear-gradient(140deg,#F2F3FD 0%,#E2E3FA 100%)', glow: 'rgba(75,77,224,.55)' },
  psychiatry: { bg: 'linear-gradient(140deg,#F7F4FE 0%,#ECE5FE 100%)', glow: 'rgba(138,92,246,.55)' },
  general: { bg: 'linear-gradient(140deg,#EDF9FA 0%,#D6F1F4 100%)', glow: 'rgba(0,169,184,.55)' },
  crisis: { bg: 'linear-gradient(140deg,#FDF1F3 0%,#FBDEE3 100%)', glow: 'rgba(229,50,79,.55)' },
  rehab: { bg: 'linear-gradient(140deg,#EEF9F6 0%,#D8F2EA 100%)', glow: 'rgba(14,175,123,.55)' },
  psychosomatic: { bg: 'linear-gradient(140deg,#FCF2F8 0%,#F9E2F0 100%)', glow: 'rgba(217,72,159,.55)' },
  geriatric: { bg: 'linear-gradient(140deg,#FBF6EF 0%,#F5EBDA 100%)', glow: 'rgba(192,128,26,.55)' },
  tcm: { bg: 'linear-gradient(140deg,#EFF4F3 0%,#DAE7E3 100%)', glow: 'rgba(23,105,79,.55)' },
}

const themeStyle = computed(() => {
  const t = THEME[key.value]
  if (!t) return undefined
  return {
    '--tile-bg': t.bg,
    '--tile-glow': t.glow,
  } as Record<string, string>
})
</script>

<style scoped>
.dept-icon {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  border-radius: 16px;
  background: var(--tile-bg);
  /* 内圈 1px 白描边模拟「玻璃高光」，外圈同色辉光让砖块从白卡上浮起 */
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.9),
    0 10px 22px -12px var(--tile-glow);
}

.dept-icon-svg {
  width: 34px;
  height: 34px;
  display: block;
}

/* 兜底：图标组未覆盖的科室 → 回到项目原有的「主色软底 + 首字」 */
.dept-icon.is-fallback {
  border-radius: var(--radius-sm);
  background: var(--primary-soft);
  box-shadow: none;
}

.dept-icon-letter {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 600;
  line-height: 1;
  color: var(--primary);
}
</style>
