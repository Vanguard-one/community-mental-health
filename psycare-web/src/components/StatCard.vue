<template>
  <div class="stat-card" :class="{ 'is-loading': loading }">
    <div class="head">
      <span class="label">{{ label }}</span>
      <span v-if="$slots.icon" class="icon"><slot name="icon" /></span>
    </div>

    <div class="value-row">
      <span class="mono value">{{ displayValue }}</span>
      <span v-if="unit" class="unit">{{ unit }}</span>
    </div>

    <div class="foot">
      <span v-if="delta !== undefined" class="mono delta" :class="deltaTone">
        {{ deltaText }}
      </span>
      <span v-if="deltaLabel" class="delta-label">{{ deltaLabel }}</span>
      <span v-if="!deltaLabel && delta === undefined" class="delta-label">{{ hint }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    /** 指标值。传入数字时按千分位格式化 */
    value: number | string
    /** 单位，如「人」「次」 */
    unit?: string
    /** 环比变化，正数上升、负数下降、0 持平 */
    delta?: number
    /** 环比说明，如「较上周」 */
    deltaLabel?: string
    /** 无环比时的脚注文案 */
    hint?: string
    loading?: boolean
  }>(),
  { unit: '', delta: undefined, deltaLabel: '', hint: '', loading: false },
)

const displayValue = computed(() =>
  typeof props.value === 'number' ? props.value.toLocaleString('zh-CN') : props.value,
)

/**
 * 上升用主色（中性偏正向），下降用琥珀（值得关注），持平用灰。
 * 管理端指标无绝对好坏方向，故不套用涨跌红绿。
 */
type Tone = 'up' | 'down' | 'flat'
const deltaTone = computed<Tone>(() => {
  if (props.delta === undefined || props.delta === 0) return 'flat'
  return props.delta > 0 ? 'up' : 'down'
})

const deltaText = computed(() => {
  if (props.delta === undefined) return ''
  const sign = props.delta > 0 ? '+' : ''
  return `${sign}${props.delta}%`
})
</script>

<style scoped>
.stat-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: box-shadow var(--dur) var(--ease);
}
.stat-card:hover {
  box-shadow: var(--shadow-hover);
}

.is-loading {
  opacity: 0.6;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.label {
  font-size: 13px;
  color: var(--text-2);
  font-weight: 500;
}

.icon {
  color: var(--primary);
  display: inline-flex;
  font-size: 16px;
}

.value-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--text-1);
}

.unit {
  font-size: 13px;
  color: var(--text-3);
}

.foot {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 18px;
}

.delta {
  font-size: 12px;
  font-weight: 600;
}
.delta.up {
  color: var(--primary);
}
.delta.down {
  color: var(--risk-b);
}
.delta.flat {
  color: var(--text-3);
}

.delta-label {
  font-size: 12px;
  color: var(--text-3);
}
</style>
