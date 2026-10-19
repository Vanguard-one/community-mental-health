<template>
  <div class="risk-scale">
    <!-- 四段带：当前档位加高，是设计稿的记忆点 -->
    <div class="bands">
      <div
        v-for="band in resolvedBands"
        :key="band.level"
        class="band"
        :class="[`is-${band.level}`, { active: band.level === level }]"
      >
        <span class="band-letter mono">{{ band.level }}</span>
        <span v-if="showRange" class="band-range mono">{{ band.from }}–{{ band.to }}</span>
      </div>
    </div>

    <!-- 分数刻度 -->
    <div class="ticks mono">
      <span>0</span>
      <span v-if="maxScore > 0" class="tick-max">{{ maxScore }}</span>
    </div>

    <!-- 当前分数浮标 -->
    <div v-if="showMarker" class="marker" :style="markerStyle">
      <span class="mono marker-value">{{ score }}</span>
      <span class="marker-arrow" />
    </div>

    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { RiskLevel } from '@/types'

export interface RiskBand {
  level: RiskLevel
  from: number
  to: number
}

const props = withDefaults(
  defineProps<{
    /** 当前档位 */
    level: RiskLevel
    /** 本次总分（用于定位浮标） */
    score?: number
    /** 量表满分 */
    maxScore?: number
    /**
     * 分界区间。真实分界由管理端配置（见 Result.vue 脚注），
     * 未传时按 maxScore 四等分——仅作视觉回退，不代表临床标准。
     */
    bands?: RiskBand[]
    /** 标尺下方提示文案，如「建议 2 周内复测」 */
    hint?: string
  }>(),
  { maxScore: 27, bands: undefined, score: undefined, hint: '' },
)

const LEVEL_ORDER: RiskLevel[] = ['A', 'B', 'C', 'D']

/** 是否由调用方提供了真实分界 */
const hasRealBands = computed(() => !!props.bands?.length)

/**
 * 区间数字只在有真实分界时展示。
 * 等分回退值不是临床分界（且相邻段在切点处重叠），显示出来会误导使用者。
 */
const showRange = computed(() => hasRealBands.value)

/** 未显式传 bands 时按满分四等分，保证 from/to 单调递增且不越界 */
const resolvedBands = computed<RiskBand[]>(() => {
  if (hasRealBands.value) return props.bands as RiskBand[]
  const max = props.maxScore
  return LEVEL_ORDER.map((lv, i) => ({
    level: lv,
    from: Math.round((max * i) / 4),
    to: Math.round((max * (i + 1)) / 4),
  }))
})

const showMarker = computed(
  () => typeof props.score === 'number' && props.maxScore > 0,
)

/** 浮标横向位置 = 分数占比，钳制在 0–100% 防止越界溢出 */
const markerStyle = computed(() => {
  const ratio = Math.min(Math.max((props.score ?? 0) / props.maxScore, 0), 1)
  return { left: `${ratio * 100}%` }
})
</script>

<style scoped>
.risk-scale {
  position: relative;
  padding-top: 34px; /* 给浮标留出空间 */
}

/* ---- 四段带 ---- */
.bands {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 44px;
}

.band {
  flex: 1;
  height: 12px;
  border-radius: var(--radius-pill);
  background: var(--band-color);
  opacity: 0.42;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  transition:
    height var(--dur) var(--ease),
    opacity var(--dur) var(--ease);
}

/* 当前档位：加高 + 满不透明 + 字母可读 */
.band.active {
  height: 40px;
  opacity: 1;
  box-shadow: 0 4px 14px color-mix(in srgb, var(--band-color) 32%, transparent);
}

.band-letter,
.band-range {
  display: none;
}

.band.active .band-letter {
  display: block;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
}

.band.active .band-range {
  display: block;
  font-size: 11px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
}

/* ---- 四色阶 ---- */
.is-A {
  --band-color: var(--risk-a);
}
.is-B {
  --band-color: var(--risk-b);
}
.is-C {
  --band-color: var(--risk-c);
}
.is-D {
  --band-color: var(--risk-d);
}

/* ---- 刻度 ---- */
.ticks {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-2);
}

/* ---- 浮标 ---- */
.marker {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  pointer-events: none;
}

.marker-value {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-1);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  padding: 2px 8px;
  box-shadow: var(--shadow-card);
}

.marker-arrow {
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid var(--text-3);
}

.hint {
  margin: 12px 0 0;
  font-size: 13px;
  color: var(--text-2);
}
</style>
