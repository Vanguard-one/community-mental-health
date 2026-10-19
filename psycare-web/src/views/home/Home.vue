<template>
  <div class="page-container">
    <!--
      页头：问候 + 日期 + 连续记录天数。用 PageHeader 而不是自己写标题 ——
      全站页头共用一张 L1 白卡（见 components/PageHeader.vue），首页也不该例外。
    -->
    <PageHeader :title="greeting" :subtitle="dateLine">
      <template #extra>
        <a-button type="primary" @click="router.push('/scales')">开始测评</a-button>
      </template>
    </PageHeader>

    <!--
      hero：最近一次测评。全站唯一的玻璃面（--card-bg-glass + backdrop-filter）——
      它面积最大、内容最少，让页面底的蓝从卡面下透出来收益最大；
      entries / 面板 / 轮播承载文字与图片，半透明只会牺牲可读性。
      ⚠️ 走 --card-bg-glass 局部 token，**绝不能改 --card-bg**（硬约定 #114）。
      ⚠️ 必须成对写 backdrop-filter：纯 alpha 即便提到 0.88，主色小字也只有 4.57，
        模糊是可读性的必要条件而不仅是美观选项。
    -->
    <section v-reveal="'stagger'" class="hero">
      <div class="hero-main">
        <span class="eyebrow" data-anim="rise">最近一次测评</span>

        <template v-if="latest">
          <div class="hero-row" data-anim="rise">
            <span class="score mono" data-anim="pop">
              {{ latest.totalScore }}<small>分</small>
            </span>
            <div class="hero-meta">
              <div class="hero-name">{{ latestScaleName }}</div>
              <div class="hero-time mono">满分 {{ latestMaxScore }} · {{ fmtTime(latest.createdAt) }} 完成</div>
            </div>
          </div>
        </template>

        <div v-else-if="loading" class="hero-loading" data-anim="rise">
          <a-skeleton active :title="false" :paragraph="{ rows: 1 }" />
        </div>

        <div v-else class="hero-empty" data-anim="rise">
          还没有测评记录，<a @click="router.push('/scales')">去做第一次自评</a>
        </div>
      </div>

      <div v-if="latest" class="hero-right" data-anim="chrome">
        <RiskLevelTag :level="latest.riskLevel" dot />
        <a-button ghost type="primary" @click="router.push(`/assessment/${latest.id}/result`)">
          查看报告 →
        </a-button>
      </div>
    </section>

    <!-- 快捷入口：三列，与 entries 的 3 项一一对应 -->
    <section v-reveal="'grid'" class="entries">
      <button
        v-for="item in entries"
        :key="item.path"
        class="entry-card"
        type="button"
        @click="router.push(item.path)"
      >
        <span class="entry-icon" :class="item.tone">
          <component :is="item.icon" />
        </span>
        <span class="entry-body">
          <span class="entry-title">{{ item.title }}</span>
          <span class="entry-desc">{{ item.desc }}</span>
        </span>
        <span class="entry-arrow" aria-hidden="true">→</span>
      </button>
    </section>

    <!-- 底部两栏 1.2 : 1 -->
    <section v-reveal="'stagger'" class="bottom">
      <div class="card">
        <div class="card-head">
          <h3>最近记录</h3>
          <router-link v-if="recent.length > 1" to="/history" class="more">全部记录 →</router-link>
        </div>

        <div v-if="loading" class="rec-skeleton">
          <a-skeleton active :title="false" :paragraph="{ rows: 3 }" />
        </div>

        <p v-else-if="!recent.length" class="rec-empty">
          还没有测评记录，<a @click="router.push('/scales')">去做第一次自评</a>
        </p>

        <ul v-else class="rec-list">
          <!--
            一行 = 一条测评记录，配色按风险等级分（Uiverse / Cybercom682 的 alert 条改写）。
            结构：左缘 4px 实色条 + 同色图标 + 柔和底色 + 右侧等级标签。
            ⚠️ 标签必须用 `plain`（描边模式）。默认模式的底色是同族 --risk-*-soft，
               与本行底色**完全一样**，pill 会在行里隐形，只剩一行彩色文字。
          -->
          <li
            v-for="row in recent"
            :key="row.id"
            :class="`lv-${row.riskLevel.toLowerCase()}`"
            @click="router.push(`/assessment/${row.id}/result`)"
          >
            <svg
              class="rec-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M13 16h-1v-4h1m0-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <span class="grow">
              <span class="rec-name">{{ scaleName(row.scaleId) }}</span>
              <span class="rec-time mono">{{ fmtTime(row.createdAt) }}</span>
            </span>
            <RiskLevelTag :level="row.riskLevel" plain />
          </li>
        </ul>
      </div>

      <!--
        右下角：轮播 banner（原「通知」面板）。
        通知列表仍可在 /notifications 页查看 —— 这里换掉的只是**首页的这一块**，
        notification 表与那个页面都保持不动。
      -->
      <BannerCarousel :banners="banners" :loading="loading" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FormOutlined, RobotOutlined, TeamOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import PageHeader from '@/components/PageHeader.vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import BannerCarousel from '@/components/BannerCarousel.vue'
import { getHistory, getScaleList } from '@/api/assessment'
import { getActiveBanners } from '@/api/banner'
import { useAuthStore } from '@/stores/auth'
import type { Assessment, Banner, Scale } from '@/types'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(true)
const history = ref<Assessment[]>([])
const scales = ref<Scale[]>([])
const banners = ref<Banner[]>([])

/*
 * 快捷入口里**刻意没有**「我的记录」：该页只允许从下方「最近记录」面板的
 * 「全部记录 →」进入。放一张常驻卡片在这里等于开了第二个入口，
 * 而那个面板本来就贴着数据、入口语义更自然（「看到最近一次成绩」→「看全部」）。
 */
const entries = [
  {
    path: '/scales',
    title: '心理测评',
    desc: '抑郁 / 焦虑 / 躯体 / 睡眠专业量表',
    icon: FormOutlined,
    tone: 'tone-blue',
  },
  {
    path: '/ai-chat',
    title: 'AI 咨询',
    desc: '随时倾诉 · 7×24 小时在线',
    icon: RobotOutlined,
    tone: 'tone-teal',
  },
  {
    path: '/doctors',
    title: '医生咨询',
    desc: '按科室找到合适的医生',
    icon: TeamOutlined,
    tone: 'tone-purple',
  },
]

// 后端按时间倒序返回，取最新一条
const latest = computed(() => history.value[0] ?? null)
const recent = computed(() => history.value.slice(0, 3))

const latestScale = computed(() => scales.value.find((s) => s.id === latest.value?.scaleId))

/** 量表满分。取不到时回退 27（PHQ-9），避免标尺刻度为 0 */
const latestMaxScore = computed(() => latestScale.value?.maxScore || 27)

const latestScaleName = computed(() => {
  const s = latestScale.value
  if (!s) return '心理自评'
  return `${s.code} · ${s.name}`
})

function scaleName(scaleId: number): string {
  const s = scales.value.find((x) => x.id === scaleId)
  return s ? `${s.code} · ${s.name}` : '心理自评'
}

/* ---------- 页头文案 ---------- */
const greeting = computed(() => {
  const h = dayjs().hour()
  const name = authStore.realName || '居民'
  if (h < 6) return `夜深了，${name}`
  if (h < 12) return `早上好，${name}`
  if (h < 14) return `中午好，${name}`
  if (h < 18) return `下午好，${name}`
  return `晚上好，${name}`
})

/**
 * 连续记录天数。
 *
 * 做法：把测评日期按天去重、倒序，然后从最近一天往回数「日期连续」的天数。
 * 结束条件用 dayjs 的 diff 而不是字符串比较 —— history 可能一天多次，
 * 必须先归一到「天」再比，否则同一天的第二次记录会被当成「不连续」而中断。
 */
const streak = computed(() => {
  if (!history.value.length) return 0
  const days = Array.from(new Set(history.value.map((h) => dayjs(h.createdAt).format('YYYY-MM-DD'))))
    .sort()
    .reverse()
  let n = 1
  for (let i = 1; i < days.length; i += 1) {
    const gap = dayjs(days[i - 1]).diff(dayjs(days[i]), 'day')
    if (gap !== 1) break
    n += 1
  }
  return n
})

const dateLine = computed(() => {
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][dayjs().day()]
  const base = `${dayjs().format('YYYY 年 M 月 D 日')} ${week}`
  return streak.value > 1 ? `${base} · 已连续记录 ${streak.value} 天` : base
})

function fmtTime(s: string): string {
  if (!s) return ''
  return dayjs(s).format('YYYY-MM-DD HH:mm')
}

onMounted(async () => {
  // 三个板块互不依赖，任一失败不影响其余渲染
  const [h, s, b] = await Promise.allSettled([getHistory(), getScaleList(), getActiveBanners()])
  if (h.status === 'fulfilled') history.value = h.value ?? []
  if (s.status === 'fulfilled') scales.value = s.value ?? []
  if (b.status === 'fulfilled') banners.value = b.value ?? []
  loading.value = false
})
</script>

<style scoped>
/* ---------- hero（玻璃面） ---------- */
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 24px 28px;
  margin-bottom: var(--space-card);
  border-radius: var(--radius-lg);
  background: var(--card-bg-glass);
  backdrop-filter: blur(var(--glass-blur));
  /* 玻璃面的描边必须偏亮（用白色而不是 --card-border）——
     --card-border 是为纯白面配的，压在玻璃上会显得脏、且与模糊后的边缘冲突 */
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--shadow-card);
  transition: box-shadow var(--dur) var(--ease);
}

.hero:hover {
  box-shadow: var(--shadow-hover);
}

.hero-main {
  min-width: 0;
}

.eyebrow {
  font-size: 13px;
  color: var(--text-2);
}

.hero-row {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  margin-top: 6px;
}

/*
 * 分数是全页最强的一个数字，给它主色 + 46px。
 * 为什么敢用主色：白/玻璃底上 --primary 是 4.64~4.68:1，**通过 AA**；
 * 而 46px 的字号本身已远超 large text 的 3:1 门槛，双保险。
 */
.score {
  font-size: 46px;
  font-weight: 700;
  line-height: 1;
  color: var(--primary);
}

.score small {
  font-size: 15px;
  font-weight: 500;
  color: var(--text-3);
  margin-left: 4px;
}

.hero-meta {
  padding-bottom: 3px;
}

.hero-name {
  font-size: 16px;
  font-weight: 500;
  color: var(--text-1);
}

.hero-time {
  font-size: 13px;
  color: var(--text-3);
  margin-top: 2px;
}

.hero-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
}

.hero-loading {
  margin-top: 10px;
  min-width: 320px;
}

.hero-empty {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-2);
}

.hero-empty a {
  cursor: pointer;
}

/* ---------- 快捷入口 ---------- */
/*
 * 三列，与 entries 数组的 3 项一一对应。
 * 改动前是 repeat(4, ...) 而只有 3 项 —— 第 4 格**永久空置**，
 * 三张卡被白白压窄 25%（若将来加到 4 项，这里要同步改）。
 */
.entries {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-card);
  margin-bottom: var(--space-card);
}

.entry-card {
  display: flex;
  align-items: center;
  gap: 14px;
  text-align: left;
  width: 100%;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  padding: 18px 20px;
  cursor: pointer;
  font: inherit;
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.entry-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-hover);
}

.entry-icon {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

/*
 * 三套图标砖配色（与 RoleAvatar / DeptIcon 同源的「浅渐变底 + 同族实色图形」）。
 * 不放进 tokens.css：这是图标专属的成对色，不是可换肤的语义色。
 */
.tone-blue {
  background: linear-gradient(140deg, #eff6ff, #dbeaff);
  color: #1f7aff;
}
.tone-teal {
  background: linear-gradient(140deg, #e6fbf7, #d2f4ee);
  color: #0e9c8d;
}
.tone-purple {
  background: linear-gradient(140deg, #f6f4fe, #eae5fe);
  color: var(--accent-purple);
}

.entry-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.entry-title {
  font-size: 15.5px;
  font-weight: 600;
  color: var(--text-1);
}

.entry-desc {
  font-size: 12.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-arrow {
  flex: none;
  color: var(--text-3);
  font-size: 16px;
  transition:
    color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.entry-card:hover .entry-arrow {
  color: var(--primary);
  transform: translateX(3px);
}

/* ---------- 底部两栏 ---------- */
/*
 * 1.2fr : 1fr（原 1.35fr : 1fr）。左栏略收、右栏（轮播图）略放，
 * 缓解两栏底部的高度落差。⚠️ 轮播列实宽因此由 ≈543px 变为 ≈580px，
 * 渲染高度 217 → 232px —— tokens.css 里 --banner-ratio 的说明数字需同步（已同步）。
 */
.bottom {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: var(--space-card);
  align-items: start;
}

/* 白卡基座（与 .surface 工具类同义，这里写成 scoped 类便于和 .bottom 的网格对齐） */
.card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 14px;
}

.card-head h3 {
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  margin: 0;
}

.more {
  font-size: 13px;
  flex: none;
}

.rec-list {
  list-style: none;
  margin: 0;
  padding: 0 14px 14px;
  /*
   * 行与行之间必须留缝。
   * 样例是 `space-y-2`（8px）；这里取 6px —— 卡片内空间有限，
   * 而 6px 已经足够让「悬停放大 2%」的位移落在缝隙里，不会压到相邻行。
   * 若把 gap 改成 0，下面的 scale 会与上下行直接贴住，看起来像粘连。
   */
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/*
 * 一条记录 = 一个「分级提示条」。
 *
 * 三处颜色分工（都来自 .lv-* 注入的自定义属性，组件里不出现任何色值）：
 *   --rec-accent  左缘 4px 实色条 —— 有形状、不承载文字，用**实色**才够醒目
 *   --rec-bg      行底色 —— 同族 soft，浅到能压深色文字
 *   --rec-fg      图标色 —— 深一档的 ink
 *
 * ⚠️ 行底色**不能**用实色 --risk-*：实色上压任何文字都不达 AA（--risk-a 对白仅 2.49），
 *    而且整行铺实色在心理量表列表里会显得像「警告」而不是「记录」。
 */
.rec-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px 10px 8px;
  /* 左缘色条：与样例的 border-l-4 同构。它挂在 li 上而不是 ::before 上，
     这样圆角会自动把左上/左下两角也带圆，不必额外裁切 */
  border-left: 4px solid var(--rec-accent);
  border-radius: var(--radius-ctrl);
  background: var(--rec-bg);
  cursor: pointer;
  /*
   * transform 必须写进过渡列表。
   * 只在 :hover 里加 transform、忘了加进 transition，表现是「瞬间跳过去」而不是「顶出来」。
   */
  transition: transform var(--dur) var(--ease);
  /* 从左缘放大：向右顶出去，不会朝卡片左边挤（左边是卡片内边距，挤出去会被裁） */
  transform-origin: left center;
}

/*
 * 悬停：只做放大，**不改底色**。
 *
 * 这不是漏写。样例的悬停是「底色加深一档 + scale-105」，但底色一加深，
 * 压在行上的 --risk-*-ink 就会掉到 4.5 以下（实测混 8% 就只有 4.37~4.48），
 * 而右侧等级标签用的正是 ink 色 —— 鼠标一悬停文字就不达标了。
 * 所以把「加深底色」这条去掉，保留最有辨识度的「放大」。
 *
 * 幅度也从 1.05 收到 1.02：行是接近全宽的，1.05 会让右端多出十几像素、
 * 直接顶到卡片右内边距上；1.02 约 7px，是「确实动了」但不越界的量。
 * 也没加投影 —— 卡内分区叠阴影会发脏（面分级规则 ②）。
 */
.rec-list li:hover {
  transform: scale(1.02);
}

/* 等级 → 三个自定义属性。放在 li 上，图标的 currentColor 直接吃 --rec-fg */
.lv-a {
  --rec-accent: var(--risk-a);
  --rec-bg: var(--risk-a-soft);
  --rec-fg: var(--risk-a-ink);
}

.lv-b {
  --rec-accent: var(--risk-b);
  --rec-bg: var(--risk-b-soft);
  --rec-fg: var(--risk-b-ink);
}

.lv-c {
  --rec-accent: var(--risk-c);
  --rec-bg: var(--risk-c-soft);
  --rec-fg: var(--risk-c-ink);
}

.lv-d {
  --rec-accent: var(--risk-d);
  --rec-bg: var(--risk-d-soft);
  --rec-fg: var(--risk-d-ink);
}

/* 图标：样例是 h-5 w-5（20px），这里收到 16px ——
   行高只有约 44px，20px 的图标会把两行文字挤扁 */
.rec-icon {
  width: 16px;
  height: 16px;
  flex: none;
  color: var(--rec-fg);
}

.grow {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.rec-name {
  font-size: 14.5px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rec-time {
  font-size: 12.5px;
  /*
   * ⚠️ 不能是 --text-3。行底色现在是 --risk-*-soft，四档实测分别是
   * 4.31 / 4.42 / 4.27 / 4.40，**全部不达 AA 4.5**；--text-2 是 6.94~7.19。
   */
  color: var(--text-2);
  margin-top: 2px;
}

.rec-empty {
  margin: 0;
  padding: 8px 22px 22px;
  font-size: 13px;
  color: var(--text-3);
}

.rec-empty a {
  cursor: pointer;
}

/* 骨架屏占位：padding 与 .rec-list 对齐，数据到达时不产生跳动 */
.rec-skeleton {
  padding: 4px 22px 22px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1100px) {
  /* 3 项在 2 列下会余出 1 张独占第二行 —— 这是刻意的：
     1 列（约 1050px 宽）太空、3 列在这个宽度会把 entry-desc 挤到省略号，
     2 列能让描述完整显示，代价只是网格不完全对称。 */
  .entries {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .bottom {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
  .hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 22px;
  }
  .entries {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
