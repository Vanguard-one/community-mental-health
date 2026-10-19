<template>
  <span class="risk-tag" :class="[`is-${level}`, `size-${size}`, { plain }]">
    <i v-if="dot" class="dot" aria-hidden="true" />
    <span class="mono label">{{ RISK_LEVEL_DESC[level] }}</span>
  </span>
</template>

<script setup lang="ts">
import type { RiskLevel } from '@/types'
import { RISK_LEVEL_DESC } from '@/types'

/**
 * 风险等级标签 —— 四色阶唯一渲染入口
 *
 * 色值来自 tokens.css 的 --risk-a/b/c/d 与配套浅底，
 * 组件内不得出现任何十六进制字面量。
 */
withDefaults(
  defineProps<{
    level: RiskLevel
    /** default 用于表格/列表，large 用于结果页主视觉 */
    size?: 'default' | 'large'
    /** 是否显示前置圆点 */
    dot?: boolean
    /** 描边模式：透明底 + 彩色描边 + 彩色文字 */
    plain?: boolean
  }>(),
  { size: 'default', dot: false, plain: false },
)
</script>

<style scoped>
.risk-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  background: var(--tag-bg);
  color: var(--tag-fg);
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  transition: background var(--dur) var(--ease);
}

/* ---- 尺寸 ---- */
.size-default {
  padding: 4px 10px;
  font-size: 12px;
}
.size-large {
  padding: 8px 18px;
  font-size: 15px;
}

/* ---- 四色阶映射（唯一定义处） ----
 * ⚠️ 文字色取 `--risk-*-ink`（深一档的同色）而**不是**实色 `--risk-a/b/c/d`：
 *    实色落在同族 soft 底上普遍不达标 —— 实测 --risk-a 只有 2.49:1，
 *    --risk-b 也只有 2.2 左右，四个都不满足 AA 正文的 4.5。
 *    深一档的 ink 系列全部在 5.5:1 以上，同时色相不变，仍能一眼分辨等级。
 *    圆点则相反：它是有形状的色块、不承载文字，用实色才够醒目（--tag-dot）。
 */
.is-A {
  --tag-fg: var(--risk-a-ink);
  --tag-dot: var(--risk-a);
  --tag-bg: var(--risk-a-soft);
}
.is-B {
  --tag-fg: var(--risk-b-ink);
  --tag-dot: var(--risk-b);
  --tag-bg: var(--risk-b-soft);
}
.is-C {
  --tag-fg: var(--risk-c-ink);
  --tag-dot: var(--risk-c);
  --tag-bg: var(--risk-c-soft);
}
.is-D {
  --tag-fg: var(--risk-d-ink);
  --tag-dot: var(--risk-d);
  --tag-bg: var(--risk-d-soft);
}

/* ---- 描边模式 ---- */
.plain {
  background: transparent;
  border-color: var(--tag-fg);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tag-dot);
  flex: none;
}
.size-large .dot {
  width: 8px;
  height: 8px;
}

.label {
  font-size: inherit;
  letter-spacing: 0.02em;
}
</style>
