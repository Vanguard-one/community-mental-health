<template>
  <div class="page-container">
    <PageHeader
      title="心理测评中心"
      subtitle="选择一份量表开始测评，系统将根据作答给出风险分级与建议"
    />

    <!-- 分类筛选：维度取自量表数据，不写死 -->
    <div v-if="dimensions.length > 1" class="filter-row">
      <button
        v-for="d in dimensions"
        :key="d"
        type="button"
        class="filter-chip"
        :class="{ active: d === activeDimension }"
        @click="activeDimension = d"
      >
        {{ d }}
      </button>
    </div>

    <!--
      加载占位。
      ① 自绘 shimmer，不走 a-skeleton / CardGridSkeleton —— 骨架的「底色 → 高光」
         必须成对取 --skeleton-base / --skeleton-hi（这两个值是成对取定的）；
         错拿 --panel-bg / --bg-3 会静默退化成静止灰条，看起来像「加载卡住了」。
      ② 栅格参数与下方 .scale-grid 完全一致（同列宽、同 gap），数据到达时列宽不跳。
    -->
    <div
      v-if="loading && !visibleScales.length"
      class="sk-grid"
      role="status"
      aria-busy="true"
      aria-live="polite"
      aria-label="正在加载量表"
    >
      <div v-for="i in 4" :key="i" class="sk-card">
        <span v-for="(w, j) in SK_LINES" :key="j" class="sk-line" :style="{ width: w }" />
      </div>
    </div>

    <!--
      量表网格。v-reveal="'grid'" —— recipe 取直接子元素、90ms 依次揭示、
      基准 0.3s、默认动作 card（y20 + scale .96），正是设计规范里的 rGridReveal。
    -->
    <div v-else-if="visibleScales.length" v-reveal="'grid'" class="scale-grid">
      <ScaleCard
        v-for="s in visibleScales"
        :key="s.id"
        :name="s.name"
        :dimension="s.dimension"
        :description="s.description"
        @select="goScale(s.id)"
      />
    </div>

    <a-empty v-else description="暂无可用量表" />

    <!--
      合规提示：把安全红线写在界面上，而不是藏在文档里。
      ⚠️ 必须是 L1 白卡 —— 改版前这里是直接贴页面底的 --panel-bg，
         它与页面底只差约 1.04:1，等于隐形（三级面硬规则①）。
    -->
    <section v-reveal="'rise'" class="notice">
      <h3 class="notice-title">测评须知</h3>
      <ul class="notice-list">
        <li>
          本测评为心理健康<strong>初筛工具</strong>，结果<strong>不构成医学诊断</strong>，不能替代专业医生面诊。
        </li>
        <li>
          若你出现伤害自己的念头，请立即拨打<strong>全国心理援助热线 12356</strong>；紧急情况请拨打 120。
        </li>
        <li>
          作答数据仅用于生成你的个人评估与必要的转介服务；管理端的统计分析均做<strong>去标识化聚合</strong>处理。
        </li>
        <li>风险分级规则由管理端配置维护，可按临床规范调整。</li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ScaleCard from '@/components/ScaleCard.vue'
import { useAssessmentStore } from '@/stores/assessment'
import type { Scale } from '@/types'

const router = useRouter()
const store = useAssessmentStore()

const ALL = '全部'
const activeDimension = ref(ALL)

const loading = computed(() => store.scalesLoading)
const scales = computed(() => store.scales)

/** 维度筛选标签：从已加载量表里归纳，避免写死「抑郁/焦虑/压力」 */
const dimensions = computed(() => {
  const set = new Set(
    scales.value.map((s: Scale) => s.dimension).filter((d): d is string => !!d),
  )
  return [ALL, ...set]
})

const visibleScales = computed(() =>
  activeDimension.value === ALL
    ? scales.value
    : scales.value.filter((s) => s.dimension === activeDimension.value),
)

/** 骨架屏每张卡的线条宽度。首条由 :first-child 单独加高，模拟卡片标题 */
const SK_LINES = ['64%', '40%', '100%', '86%', '52%']

function goScale(id: number) {
  router.push(`/scales/${id}`)
}

onMounted(() => {
  store.loadScales()
})
</script>

<style scoped>
/* ---------- 维度筛选 ---------- */
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: var(--space-card);
}

/*
 * 筛选 chip 直接落在页面底上（不在任何白卡内部），所以：
 *   未选中 → L1 白面 + 控件描边（--ctrl-border，比 --card-border 深一档，
 *            「可交互」的信号要更强）
 *   选中   → L3 主色实底 + 白字（5.17:1）。**不能**用 --primary-soft 填底：
 *            它贴页面底只有约 1.04:1，整颗 chip 会连边界一起消失（硬规则①）。
 */
.filter-chip {
  height: 34px;
  padding: 0 18px;
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 14px;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.filter-chip:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.filter-chip.active {
  background: var(--primary);
  border-color: var(--primary);
  color: var(--on-primary);
  font-weight: 600;
}

/*
 * 量表网格。列宽取 --grid-col-w（324px，居民端卡片单列宽的唯一真源），
 * 列间距取 --space-card（20px）。
 * ⚠️ 骨架屏 .sk-grid 与它共用同一组栅格参数（写在一起是刻意的）——
 *    两者列宽只要差一点，数据到达时就会横向跳一下，骨架屏就白做了。
 */
.scale-grid,
.sk-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--grid-col-w), 1fr));
  gap: var(--space-card);
}

/* ---------- 骨架屏 ---------- */

.sk-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /*
   * 三个参数必须与 ScaleCard 对齐，否则数据到达时卡片会「跳一下」：
   *   min-height 200   ← 与 .scale-card 的 min-height 一致
   *   padding 16/18    ← 与 .scale-card 的 .body 一致
   *   底色/无描边      ← 与新卡片的「白 + 主色 8%、无边框」一致
   * 这是骨架屏最容易漏的地方：只改真实卡片、不改占位块，加载完成那一下会有
   * 高度突增 + 底色突变两道闪。
   */
  min-height: 200px;
  padding: 16px 18px;
  background: color-mix(in srgb, var(--primary) 8%, var(--card-bg));
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

.sk-line {
  height: 12px;
  border-radius: var(--radius-pill);
  /* 底色 → 高光必须成对取 token；两者差 1 会让 400% 位移动画退化成静止灰条 */
  background: linear-gradient(
    90deg,
    var(--skeleton-base) 25%,
    var(--skeleton-hi) 37%,
    var(--skeleton-base) 63%
  );
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease infinite;
}

.sk-line:first-child {
  height: 18px;
}

@keyframes sk-shimmer {
  from {
    background-position: 100% 50%;
  }
  to {
    background-position: 0 50%;
  }
}

/* ---------- 测评须知 ---------- */
/*
 * L1 白卡：贴页面底的面只能是白卡或主色。左侧 3px 是实色 --risk-b 的**描边**
 * （实色色阶允许用于色块 / 圆点 / 描边），用来表达「须知 = 需要留意」。
 */
.notice {
  margin-top: var(--space-block);
  padding: 20px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-left: 3px solid var(--risk-b);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/* 卡片小标题（16px 级）走 --font-sans + 600，而不是全局 h1-h6 的宋体 */
.notice-title {
  margin: 0 0 12px;
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.notice-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: 13.5px;
  line-height: 1.9;
}

.notice-list strong {
  color: var(--text-1);
  font-weight: 600;
}
</style>
