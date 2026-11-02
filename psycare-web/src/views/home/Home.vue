<template>
  <div class="page-container">
    <!--
      ⚠️ 这里**刻意不用 PageHeader**（全站唯一例外，`PageHeader.vue` 本身一行没改）。

      PageHeader 的存在理由是「8 个页面共享同一块标题面」；但首页的第一块不是「标题」，
      而是「欢迎 + 当前状态」。这两件东西合起来才成立 —— 拆成「标题条 + 状态卡」之后，
      上半部分就变成四条等高的横带（实测 87:124:82），节奏接近 1:1.4:1，观感平淡。
      合并成一张 288px 的主角卡之后是 288:96:260 ≈ 3:1:2.7，重音才只有一个。

      设计稿与取舍依据见 首页改版设计稿.md §7.1。
    -->
    <section v-reveal="'header'" class="hero">
      <!-- 左栏：问候 + 副标 + 日期胶囊 + 主 CTA -->
      <div class="hero-l">
        <h1 class="greet" data-anim="line">{{ greeting }}</h1>
        <p class="sub" data-anim="rise">愿你在这里，找到心灵的平静与力量。</p>

        <!--
          日期胶囊：把「今天几号」从一段副标题变成一枚可触摸的信息块。
          连续记录 ≤ 1 天时整段不渲染（只留日历与日期）——
          「连续记录 1 天」和「今天刚来」说的是同一件事，写出来反而像任务清单。
        -->
        <div class="chip" data-anim="rise">
          <svg class="chip-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3.5" y="5.5" width="17" height="15" rx="3" />
            <path d="M8 3.5v4M16 3.5v4M3.5 10.5h17" />
          </svg>
          <span class="chip-date">{{ dateText }}</span>
          <template v-if="streak > 1">
            <span class="chip-sep" aria-hidden="true" />
            <span class="chip-streak">连续记录 {{ streak }} 天</span>
          </template>
        </div>

        <!--
          主 CTA 用原生 button 而不是 a-button：这里要精确控制 40px 高 + 渐变底，
          覆盖 antd 按钮需要 :deep 进去改内部结构。入口卡与轮播箭头也都是原生 button，
          项目内已有先例。零新增 antd 组件（vendor-antd 预算不动）。
        -->
        <button class="hero-cta" type="button" data-anim="rise" @click="router.push('/scales')">
          开始测评
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 12h13M13 6.5 18.5 12 13 17.5" />
          </svg>
        </button>
      </div>

      <!-- 右栏：最近一次测评（玻璃内嵌白卡） -->
      <div class="latest" data-anim="chrome">
        <div v-if="loading" class="lt-skeleton">
          <a-skeleton active :title="false" :paragraph="{ rows: 3 }" />
        </div>

        <template v-else-if="latest">
          <span class="lt-eyebrow">最近一次测评</span>

          <div class="lt-score-row">
            <div class="lt-score">
              {{ latest.totalScore }}<small>分</small>
            </div>
            <RiskLevelTag :level="latest.riskLevel" />
          </div>

          <div class="lt-name">{{ latestScaleName }}</div>
          <div class="lt-meta">满分 {{ latestMaxScore }} · {{ fmtShort(latest.createdAt) }}</div>

          <div class="lt-divider" aria-hidden="true" />

          <div class="lt-foot">
            <!--
              迷你趋势条：6 根 div，**不引 ECharts** —— 引库会把首屏 chunk 抬高，
              而这里只需要「最近几次在什么水位」这一个信息。
              不足 3 次不显示：两根柱子画不出趋势，反而像装饰。
            -->
            <div v-if="spark.length >= 3" class="lt-spark-wrap">
              <div class="spark" aria-hidden="true">
                <span
                  v-for="(h, i) in spark"
                  :key="i"
                  class="bar"
                  :class="{ on: i === spark.length - 1 }"
                  :style="{ height: `${h}%` }"
                />
              </div>
              <span class="spark-cap">近 {{ spark.length }} 次</span>
            </div>

            <a class="lt-link" @click="router.push(`/assessment/${latest.id}/result`)">查看报告 →</a>
          </div>
        </template>

        <!-- 空态：不显示分数区与趋势条，只留一句引导 -->
        <div v-else class="lt-empty">
          <span class="lt-eyebrow">最近一次测评</span>
          <p class="lt-empty-text">还没有测评记录</p>
          <a class="lt-link" @click="router.push('/scales')">做第一次自评 →</a>
        </div>
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

    <!--
      底部三栏 1 : 0.95 : 1.45。

      ⚠️ 高度关系：本行**没有固定高度**，行高由中栏（服务卡 + 保障条）的内容自然决定（约 270）。
         左栏用 `align-self: stretch` 拉平，Banner 组件自己写 `align-self: stretch`。
         所以「减最近记录的条数」不会让底部变矮 —— 只会在左栏底部留白。

      ⚠️ `minmax(0, …fr)` 不能省。少了 `minmax(0, …)`，`auto` 的最小值按内容算，
         一张长量表名就能把整列撑破，表现为「页面出现横向滚动条」，
         而单独看某一张卡完全正常 —— 这种 bug 很难和「三栏比例」联系起来。
    -->
    <section v-reveal="'grid'" class="bottom">
      <!-- ① 最近记录（691 → 367，行形态重做） -->
      <div class="card rec-card">
        <div class="card-head">
          <svg class="head-icon" viewBox="0 0 24 24" aria-hidden="true">
            <rect x="3.5" y="5.5" width="17" height="15" rx="3" />
            <path d="M8 3.5v4M16 3.5v4M3.5 10.5h17" />
          </svg>
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
            一行 = 一条测评记录。收窄到 367 之后，原来「4px 竖条 + 右侧竖向两行（分数 + 等级）」
            放不下了：量表名会被截断，而且分数在 hero 的内嵌卡里已经承担过一次，
            再展示一次只会稀释它。新形态把「时间 + 状态」并成一行，分数整列去掉。
          -->
          <li
            v-for="row in recent"
            :key="row.id"
            :class="`lv-${row.riskLevel.toLowerCase()}`"
            @click="router.push(`/assessment/${row.id}/result`)"
          >
            <span class="rec-tile">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3.5 12h3.2l2-4.6 3.2 9.2 2.4-6.1 1.7 3.5h4.5" />
              </svg>
            </span>
            <span class="grow">
              <span class="rec-name">{{ scaleName(row.scaleId) }}</span>
              <span class="rec-meta"><span class="mono">{{ fmtShort(row.createdAt) }}</span> · 已完成</span>
            </span>
            <!--
              状态胶囊：**白底** + 同族墨字。

              为什么不用 RiskLevelTag：那枚标签的 default 模式底是 `--risk-*-soft`，
              落在同样是 soft 的行底上会**完全隐形**（只剩一块更亮的空白）；
              plain 模式是透明底 + 彩色描边，不是胶囊。这里需要的是第三种形态
              「白底 + 墨字」，只在本页出现 —— 为它给 RiskLevelTag 加第三个变体，
              会让另外 8 处调用点一起承担改动的风险，不划算。

              色值仍走 token（--rec-fg = --risk-*-ink），文案走 RISK_LEVEL_DESC，
              所以「四色阶唯一定义处」没有被破坏。
            -->
            <span class="rec-badge">{{ RISK_LEVEL_DESC[row.riskLevel] }}</span>
            <span class="rec-go" aria-hidden="true">›</span>
          </li>
        </ul>
      </div>

      <!-- ② 中栏：服务引导卡 + 四项保障条 -->
      <div class="bottom-col">
        <div class="card care">
          <!--
            参考图右侧是绿植实拍。这里**不引第二张图** ——
            整页已经有一张插画底（居民端 app-bg-resident.jpg），
            再加一张图既重复又多一次请求。用矢量线稿替代，同样能补右侧留白。
            光晕与植物都压在标题右侧之外，不覆盖任何文字。
          -->
          <svg class="care-plant" viewBox="0 0 100 100" aria-hidden="true">
            <path d="M50 94V42" />
            <path d="M50 60C33 60 24 47 24 32c17 0 26 12 26 28Z" />
            <path d="M50 48c0-15 9-28 26-28 0 15-9 28-26 28Z" />
            <path d="M50 76c-12 0-19-8-19-19 12 0 19 6 19 19Z" />
          </svg>

          <h3 class="care-title">每一次倾诉，<br />都是向更好的自己靠近</h3>
          <!--
            说明文案控制在 24 字内（349px 宽、12.5px，一行约 24 字）。
            这不是文案洁癖：这一行一旦折成两行，中栏高度就 +20，
            底部整行被拉高、Banner 实际比例从 1.97:1 掉到 1.81:1，`cover` 越裁越多。
            底部高度由中栏决定，不由左栏列表长度决定。
          -->
          <p class="care-desc">焦虑、压力或情绪低落时，不必独自承受。</p>
          <a class="care-cta" @click="router.push('/doctors')">
            了解心理健康服务 <span aria-hidden="true">→</span>
          </a>
        </div>

        <!--
          四项保障 1×4 横排。说明文字全部 ≤ 7 字：
          349px 下四块各 76px，10.5px 的 6 个字约 63px 已经贴边，再长一个字就折行，
          四条立刻高低不齐。也**没有**做成 2×2 —— 那会让这一条从 95 翻到 ~190，
          底部被拉到 370，Banner 比例掉到 1.44:1（各裁 20%）。
        -->
        <div class="feat">
          <div class="ft">
            <span class="ft-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 3.5 5 6.2v5.3c0 4.2 2.9 7.7 7 9 4.1-1.3 7-4.8 7-9V6.2Z" />
                <path d="m9.2 12 2 2 3.6-3.8" />
              </svg>
            </span>
            <span class="ft-title">专业保障</span>
            <span class="ft-desc">医生与咨询师</span>
          </div>

          <div class="ft">
            <span class="ft-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <rect x="4.5" y="10.5" width="15" height="9.5" rx="2.6" />
                <path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
              </svg>
            </span>
            <span class="ft-title">隐私保护</span>
            <span class="ft-desc">信息加密存储</span>
          </div>

          <div class="ft">
            <span class="ft-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path
                  d="M20.4 8.6a4.4 4.4 0 0 0-7.5-3.1l-.9.9-.9-.9A4.4 4.4 0 0 0 3.6 8.6c0 4.1 4.2 6.8 8.4 10.2 4.2-3.4 8.4-6.1 8.4-10.2Z"
                />
              </svg>
            </span>
            <span class="ft-title">温暖陪伴</span>
            <span class="ft-desc">全流程关怀</span>
          </div>

          <div class="ft">
            <span class="ft-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M13.2 3 6 13.6h4.8L10.4 21 18 10.4h-4.9Z" />
              </svg>
            </span>
            <span class="ft-title">便捷高效</span>
            <span class="ft-desc">在线预约响应</span>
          </div>
        </div>
      </div>

      <!-- ③ 运营 Banner：位置、格式、组件本身都不动，变的只是列宽 -->
      <BannerCarousel :banners="banners" :loading="loading" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FormOutlined, RobotOutlined, TeamOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import BannerCarousel from '@/components/BannerCarousel.vue'
import { getHistory, getScaleList } from '@/api/assessment'
import { getActiveBanners } from '@/api/banner'
import { useAuthStore } from '@/stores/auth'
import { RISK_LEVEL_DESC } from '@/types'
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

/**
 * 迷你趋势条的高度百分比。
 *
 * ⚠️ **不能直接用绝对分**：PHQ-9 满分 27、GAD-7 满分 21，
 *    同一个「15 分」在两个量表里是完全不同的严重程度。
 *    所以按各自满分归一化成百分比再画，这样跨量表的柱子才可比。
 *    下限 8% 是为了让 0 分也留一条可见的细柱（否则看起来像「缺了一次」）。
 */
const spark = computed(() => {
  const rows = history.value.slice(0, 6).reverse() // 旧 → 新，最后一根才是最近一次
  return rows.map((r) => {
    const max = scales.value.find((s) => s.id === r.scaleId)?.maxScore || 27
    return Math.max(8, Math.min(100, Math.round((r.totalScore / max) * 100)))
  })
})

/* ---------- 文案 ---------- */
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

/** 日期胶囊里的文字。连续天数单独渲染，所以这里只给日期 */
const dateText = computed(() => {
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][dayjs().day()]
  return `${dayjs().format('YYYY 年 M 月 D 日')} ${week}`
})

/** 记录行与内嵌卡用的短格式（10-23 16:08）。年份在「最近」语境里是噪音 */
function fmtShort(s: string): string {
  if (!s) return ''
  return dayjs(s).format('MM-DD HH:mm')
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
/* 页面上这三组 SVG（胶囊、箭头、保障条、记录行）都是**线性描边**，统一在这里给描边约定，
   避免每个选择器重复写四行 stroke 属性。fill: none 是前提 —— 少了它浏览器会填黑。 */
.chip-icon,
.hero-cta svg,
.head-icon,
.care-plant,
.ft-icon svg,
.rec-tile svg {
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* ============================================================
   一、HERO 主角卡（288px 玻璃）
   ============================================================ */
/*
 * 全站唯一的「大面积玻璃面」。为什么 hero 用玻璃、不用参考图里那张插画：
 * 页面底已经有插画了（居民端 app-bg-resident.jpg + 0.25 白纱），再用一张图就是
 * 「同图出现两次」；而且那张图上压不住 --text-2（分区实测 p5 处只有 3.60:1）。
 * 磨砂玻璃正好把身后的插画压成一片柔和色温，既拿到「大面积图示感」，
 * 又不影响任何文字对比度。
 *
 * ⚠️ backdrop-filter 必须与 --card-bg-glass 成对写（纯 alpha 无论多白都压不住背景色差）。
 * ⚠️ 描边用白色而不是 --card-border：后者是为纯白面配的，压在玻璃上会显脏。
 */
.hero {
  position: relative;
  overflow: hidden; /* 光晕 ::before 不能溢出圆角 */
  display: flex;
  align-items: center;
  gap: 40px;
  min-height: 288px;
  padding: 36px; /* 比卡内标准 24 大一档 —— 主角卡需要更大的边距比 */
  margin-bottom: var(--space-block);
  border-radius: var(--radius-lg);
  background: var(--card-bg-glass);
  backdrop-filter: blur(var(--glass-blur));
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--shadow-card);
}

/* 唯一的纯装饰元素：右半两团光晕，给玻璃一点色温差，避免大面积灰白。
   两团都在右上 / 右下，永远不落到左栏文字区。 */
.hero::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(420px 260px at 88% -10%, var(--hero-aurora-a), transparent 70%),
    radial-gradient(360px 240px at 96% 110%, var(--hero-aurora-b), transparent 72%);
}

.hero-l {
  position: relative; /* 压在 ::before 之上 */
  flex: 1;
  min-width: 0;
}

/* 36 → 15 → 13 → 12.5，主副比 2.4（改版前 23 → 13 只有 1.8，
   上半部分之所以「平」，字号跨度不足是第二个原因） */
.greet {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 36px;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: 0.01em;
  color: var(--text-1);
}

.sub {
  margin: 8px 0 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--text-2);
}

/* ---------- 日期胶囊 ---------- */
.chip {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  height: 38px;
  padding: 0 16px;
  margin-top: 20px;
  border-radius: var(--radius-pill);
  background: var(--chip-bg);
  border: 1px solid var(--chip-border);
  box-shadow: 0 2px 10px rgba(11, 18, 32, 0.05);
}

.chip-icon {
  width: 16px;
  height: 16px;
  flex: none;
  color: var(--primary);
  stroke-width: 1.8;
}

/* 胶囊里的字**一律 --text-2**：胶囊是 90% 白压在玻璃上，等效底亮度约 0.96，
   --text-3 在它上面只有 4.55，贴线不保险（白卡上才是 4.76）。 */
.chip-date {
  font-size: 13px;
  color: var(--text-2);
  white-space: nowrap;
}

.chip-sep {
  width: 1px;
  height: 16px;
  flex: none;
  background: rgba(213, 223, 235, 0.95);
}

.chip-streak {
  font-size: 13px;
  font-weight: 600;
  color: var(--primary-dark); /* 不用 --primary：压 --chip-bg 只有 4.9，且它要更「实」 */
  white-space: nowrap;
}

/* ---------- 主 CTA ---------- */
.hero-cta {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 40px;
  padding: 0 22px;
  margin-top: 20px;
  border: 0;
  border-radius: var(--radius-ctrl);
  background: linear-gradient(135deg, var(--primary), var(--primary-dark));
  color: var(--on-primary);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--shadow-primary);
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.hero-cta:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.36);
}

.hero-cta svg {
  width: 15px;
  height: 15px;
  stroke-width: 2;
}

/* ---------- 右栏内嵌卡 ---------- */
/*
 * 这层阴影是对「阴影只给 L1」的例外：它画的不是白卡，而是 82% 白的玻璃，
 * 不给阴影就会和玻璃糊成一片。幅度control 到 0.05，比 --shadow-card 还淡。
 */
.latest {
  position: relative;
  flex: none;
  width: 360px;
  padding: 18px 20px;
  border-radius: var(--radius-sm);
  background: var(--card-bg-inner);
  border: 1px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 2px 12px rgba(11, 18, 32, 0.05);
}

/* 骨架态也要占住同样的宽高，否则数据到达时整张 hero 会跳一下 */
.lt-skeleton {
  padding: 4px 0 6px;
}

.lt-eyebrow {
  font-size: 12.5px;
  color: var(--text-3);
}

.lt-score-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
}

.lt-score {
  display: flex;
  align-items: baseline;
  gap: 3px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 40px;
  font-weight: 700;
  line-height: 1;
  color: var(--primary);
}

.lt-score small {
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--text-3);
}

.lt-name {
  margin-top: 8px;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lt-meta {
  margin-top: 4px;
  font-family: var(--font-mono);
  font-size: 12.5px;
  color: var(--text-3);
}

.lt-divider {
  height: 1px;
  margin: 12px 0 10px;
  background: color-mix(in srgb, var(--card-border) 70%, transparent);
}

.lt-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
}

/* 迷你趋势条：6 根 div，不引图表库 */
.lt-spark-wrap {
  flex: 1;
  min-width: 0;
}

.spark {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 30px;
}

.spark .bar {
  flex: 1;
  display: block;
  border-radius: 3px;
  background: var(--primary-soft);
}

.spark .bar.on {
  background: var(--primary);
}

.spark-cap {
  display: block;
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--text-3);
}

.lt-link {
  flex: none;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--primary-dark); /* --primary 压 86% 白卡 4.6 刚过线，deep 一档到 5.71 */
  white-space: nowrap;
  cursor: pointer;
}

.lt-link:hover {
  text-decoration: underline;
}

.lt-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lt-empty-text {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--text-2);
}

/* ============================================================
   二、三张入口卡
   ============================================================ */
.entries {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-card);
  margin-bottom: var(--space-block);
}

.entry-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 96px;
  padding: 20px 22px;
  width: 100%;
  text-align: left;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  font: inherit;
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

/* 卡内右下角同色淡晕：让三张白卡「各有颜色」，而不是三块一模一样的白。
   三个 tone 类各自给 --entry-glow —— 这是「三张卡不足以承载重音」的补偿手段，
   它们仍然不应该变重（全页真正的重音只有 hero 一个）。 */
.entry-card::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(140px 140px at 100% 118%, var(--entry-glow), transparent 68%);
}

.entry-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-hover);
}

.entry-icon {
  position: relative; /* 压在 ::after 之上 */
  width: 48px;
  height: 48px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  font-size: 22px;
  transform-origin: center;
  transition: transform var(--dur) var(--ease);
}

.entry-card:hover .entry-icon {
  transform: scale(1.06);
}

/*
 * 三套图标砖配色（与 RoleAvatar / DeptIcon 同源的「浅渐变底 + 同族实色图形」）。
 * 不放进 tokens.css：这是图标专属的成对色，不是可换肤的语义色。
 * --entry-glow 沿用同一处理 —— 它是这三个 tone 的私有属性，不是全局 token。
 */
.tone-blue {
  background: linear-gradient(140deg, #eff6ff, #dbeaff);
  color: #1f7aff;
  --entry-glow: rgba(31, 122, 255, 0.09);
}
.tone-teal {
  background: linear-gradient(140deg, #e6fbf7, #d2f4ee);
  color: #0e9c8d;
  --entry-glow: rgba(14, 156, 141, 0.09);
}
.tone-purple {
  background: linear-gradient(140deg, #f6f4fe, #eae5fe);
  color: var(--accent-purple);
  --entry-glow: rgba(124, 58, 237, 0.09);
}

.entry-body {
  position: relative;
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
  position: relative;
  flex: none;
  color: var(--text-3);
  font-size: 16px;
  transition:
    color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.entry-card:hover .entry-arrow {
  color: var(--primary);
  transform: translateX(4px);
}

/* ============================================================
   三、底部三栏
   ============================================================ */
.bottom {
  display: grid;
  /* 1 : 0.95 : 1.45 → 367 / 349 / 532（内容宽 1288 − 两个 20 间隙 = 1248）。
     Banner 拿最大的一份，是因为它是 2.5:1 的运营物料 —— 容器越窄，cover 裁掉的左右越多。
     均分（各 416）会让比例掉到 1.5:1、各裁 20%，带字的运营图会被切字。 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.95fr) minmax(0, 1.45fr);
  gap: var(--space-card);
  align-items: start;
}

/* 白卡基座。`overflow: hidden` 让记录行的 hover 位移与内部背景不溢出圆角 */
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
  gap: 8px;
  padding: 17px 16px 12px;
}

.head-icon {
  width: 17px;
  height: 17px;
  flex: none;
  color: var(--text-3);
  stroke-width: 1.8;
}

.card-head h3 {
  margin: 0;
  font-family: var(--font-sans);
  font-size: 15.5px;
  font-weight: 600;
  color: var(--text-1);
}

/* 卡头右侧「全部记录」：margin-left:auto 把它推到最右，且 flex:none 不允许它被标题挤掉 */
.more {
  margin-left: auto;
  flex: none;
  font-size: 12.5px;
  color: var(--primary);
  text-decoration: none;
  white-space: nowrap;
}

.more:hover {
  text-decoration: underline;
}

/* ---------- ① 最近记录 ---------- */
/* 列表自己撑满：中栏比它自然的 245 高，让空白落在卡片底部，
   而不是让整张卡「悬在半空」（align-items:start 会让它保持自然高度）。 */
.rec-card {
  align-self: stretch;
  display: flex;
  flex-direction: column;
}

.rec-list {
  flex: 1;
  list-style: none;
  margin: 0;
  padding: 0 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rec-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px 9px 9px;
  border-radius: 14px;
  background: var(--rec-bg);
  cursor: pointer;
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

/*
 * 悬停：纵向上浮 1px + 一层极淡投影。
 *
 * 为什么不是原来的 scale(1.02)：一行现在只有 54px 高、367px 宽，
 * 1.02 的横向增量只有 7px 几乎看不出来；而 scale 会让右侧胶囊溢出圆角。
 * 纵向 1px 反而能和卡内其他行的间距对齐。
 *
 * ⚠️ 卡内分区叠阴影本来违反「阴影只给 L1」，这里只在 hover 的瞬时态出现、0.06 alpha，
 *    是「行必须可点」这一层的必要反馈；静态态不加阴影。
 */
.rec-list li:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(11, 18, 32, 0.06);
}

/* 等级 → 两个自定义属性。色值全部来自 token，组件内不出现任何十六进制。 */
.lv-a {
  --rec-bg: var(--risk-a-soft);
  --rec-fg: var(--risk-a-ink);
}
.lv-b {
  --rec-bg: var(--risk-b-soft);
  --rec-fg: var(--risk-b-ink);
}
.lv-c {
  --rec-bg: var(--risk-c-soft);
  --rec-fg: var(--risk-c-ink);
}
.lv-d {
  --rec-bg: var(--risk-d-soft);
  --rec-fg: var(--risk-d-ink);
}

/* 图标砖：白底浮在淡色行上，让「量表」这一层比「风险」那一层更靠前 */
.rec-tile {
  width: 36px;
  height: 36px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: var(--card-bg);
  color: var(--rec-fg);
  box-shadow: 0 1px 3px rgba(11, 18, 32, 0.06);
}

.rec-tile svg {
  width: 17px;
  height: 17px;
  stroke-width: 1.8;
}

.grow {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rec-name {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ⚠️ 时间行**不能**用 --text-3。它落在 --risk-*-soft 上四档实测依次
   4.31 / 4.42 / 4.27 / 4.40，**全部不到 AA 4.5**；--text-2 是 6.94~7.19。 */
.rec-meta {
  font-size: 11.5px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 状态胶囊必须白底：行底已经是 --risk-*-soft，标签再用同族 soft 就等于「没有标签」——
 * 界面上只会看到一块更亮的空白。白 90% 合成底实测 #fdfffe 上 ink 5.20、
 * #fffefd 上 5.06，均达 AA。
 */
.rec-badge {
  flex: none;
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: var(--radius-pill);
  background: var(--chip-bg);
  color: var(--rec-fg);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.25;
  white-space: nowrap;
  box-shadow: 0 1px 2px rgba(11, 18, 32, 0.05);
}

/* 箭头用 --text-2 而不是 --text-3：同上，text-3 在两档行底上 4.31 / 4.42 不达标。
   它虽然是装饰性指示，但同一行里 text-2 有 7.0，没理由留一个不达标的。 */
.rec-go {
  flex: none;
  font-size: 15px;
  line-height: 1;
  color: var(--text-2);
  transition:
    transform var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.rec-list li:hover .rec-go {
  transform: translateX(2px);
  color: var(--text-1);
}

.rec-empty {
  margin: 0;
  padding: 8px 16px 20px;
  font-size: 13px;
  color: var(--text-2); /* 卡片白底上 text-3 也达标，但这里要和上面的 meta 同档 */
}

.rec-empty a {
  cursor: pointer;
}

/* 骨架屏占位：padding 与 .rec-list 对齐，数据到达时不产生跳动 */
.rec-skeleton {
  padding: 4px 16px 16px;
}

/* ---------- ② 中栏：服务引导卡 + 四项保障条 ---------- */
/* 纵向叠两张卡。gap 12 而不是 --space-card 20：这两块在语义上是「同一栏的两段」，
   而 20 会把中栏推到 290+，底部整体被拉高、Banner 越裁越多。 */
.bottom-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.care {
  position: relative;
  overflow: hidden;
  padding: 18px 18px 16px;
}

/* 右上角一团极淡的青色光 + 植物线稿。两者都压在标题右侧之外，不覆盖任何文字 */
.care::before {
  content: '';
  position: absolute;
  right: -60px;
  top: -70px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--care-glow), transparent 70%);
  pointer-events: none;
}

.care-plant {
  position: absolute;
  right: -10px;
  top: 6px;
  width: 92px;
  height: 92px;
  color: var(--care-plant);
  stroke-width: 2.4;
  pointer-events: none;
}

.care-title {
  position: relative;
  margin: 0;
  font-family: var(--font-serif);
  font-size: 19px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: 0.01em;
  color: var(--text-1);
}

.care-desc {
  position: relative;
  margin: 8px 0 0;
  max-width: 26em;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
}

.care-cta {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 32px;
  padding: 0 15px;
  margin-top: 13px;
  border-radius: var(--radius-pill);
  background: var(--primary);
  color: var(--on-primary);
  font-size: 12.5px;
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.22);
  transition:
    background var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.care-cta:hover {
  background: var(--primary-dark);
  transform: translateY(-1px);
}

.care-cta span {
  font-size: 13px;
  line-height: 1;
}

/* 四项保障：1×4 横排，无纵向留白余地 —— 见模板里的注释 */
.feat {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding: 13px 10px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

.ft {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
  text-align: center;
}

/* --primary 压 --primary-soft 只有 4.44，但这是**图形**（AA 要求 3.0）不是文字，合格 */
.ft-icon {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary);
}

.ft-icon svg {
  width: 16px;
  height: 16px;
  stroke-width: 1.9;
}

.ft-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
  white-space: nowrap;
}

/* 白卡上 text-3 是 4.76 ✓（这是它少数能用的地方之一） */
.ft-desc {
  font-size: 10.5px;
  line-height: 1.4;
  color: var(--text-3);
  white-space: nowrap;
}

/* ============================================================
   四、响应式
   ============================================================ */
/*
 * ⚠️ 这五档的**书写顺序不能改**。`@media` 不增加选择器权重，同权重下后写的生效，
 *    顺序颠倒就会让「≤1100 单列时中栏改左右并排」这条永远不生效
 *    （被前面的单列规则压掉），而症状是「代码明明写了却没反应」。
 */
@media (max-width: 1180px) {
  .hero {
    padding: 30px;
    gap: 30px;
  }
  .greet {
    font-size: 32px;
  }
  .latest {
    width: 330px;
  }
  .entries {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  /* 三栏压到 1120 时中栏只剩 ~328：1×4 每块 71px，6 字说明正好贴边 → 折成 2×2。
     这一条会让中栏变高 ~95，底部整体被拉高、Banner 变得更方 —— 这是「文字不折行」
     与「Banner 比例」之间必须付的一笔，两个都完美做不到。 */
  .feat {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 8px;
  }
}

@media (max-width: 1100px) {
  .bottom {
    grid-template-columns: minmax(0, 1fr);
  }
  /* 单列后中栏有 1224 宽：若照旧纵向叠，服务卡文案只占左侧 340，
     右边 880 全是白底 + 一团不知道往哪放的光晕。
     所以改成「文案 | 保障」左右并排，让两张卡各自回到合理宽度。 */
  .bottom-col {
    display: grid;
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
    gap: var(--space-card);
    align-items: stretch;
  }
}

@media (max-width: 900px) {
  .hero {
    flex-direction: column;
    align-items: stretch;
    gap: 24px;
  }
  .latest {
    width: 100%;
  }
  .lt-foot {
    align-items: center;
  }
}

@media (max-width: 720px) {
  .hero {
    padding: 24px;
    border-radius: 22px;
  }
  .greet {
    font-size: 26px;
  }
  .sub {
    font-size: 14px;
  }
  .entries {
    grid-template-columns: minmax(0, 1fr);
  }
  .entry-card {
    min-height: 84px;
  }
  /* 手机上一行放不下「文案 | 保障」两栏，回到纵向 */
  .bottom-col {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 480px) {
  /* 胶囊里三段文字在 375 宽下会折行，砍掉「连续记录」只留日期 */
  .chip-streak,
  .chip-sep {
    display: none;
  }
}
</style>
