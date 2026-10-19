<template>
  <span class="status-chip" :class="`is-${status}`">
    <i class="dot" aria-hidden="true" />
    <span class="text">{{ ALERT_STATUS_DESC[status] }}</span>
  </span>
</template>

<script setup lang="ts">
import type { AlertStatus } from '@/types'
import { ALERT_STATUS_DESC } from '@/types'

/**
 * 工单状态 chip —— 6 态色映射的唯一渲染入口。
 *
 * 语义约定：
 *   PENDING    待确认 → 琥珀（需要人工介入）
 *   REJECTED   已驳回 → 灰（终态，非正常路径）
 *   CONFIRMED  已确认 → 陶土（已定性，仍未处置）
 *   PROCESSING 处置中 → 电光蓝（进行中）
 *   FOLLOWED   已回访 → 青（闭环偏正向）
 *   CLOSED     已关闭 → 灰（终态）
 */
defineProps<{ status: AlertStatus }>()
</script>

<style scoped>
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  background: var(--chip-bg);
  color: var(--chip-fg);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--chip-fg);
  flex: none;
}

.text {
  font-size: 12px;
}

/* ---- 6 态映射（唯一定义处） ---- */
.is-PENDING {
  --chip-fg: var(--risk-b);
  --chip-bg: var(--risk-b-soft);
}
.is-REJECTED {
  --chip-fg: var(--text-3);
  --chip-bg: var(--panel-bg);
}
.is-CONFIRMED {
  --chip-fg: var(--risk-c);
  --chip-bg: var(--risk-c-soft);
}
.is-PROCESSING {
  --chip-fg: var(--primary);
  --chip-bg: var(--primary-soft);
}
.is-FOLLOWED {
  --chip-fg: var(--risk-a);
  --chip-bg: var(--risk-a-soft);
}
.is-CLOSED {
  --chip-fg: var(--text-3);
  --chip-bg: var(--panel-bg);
}
</style>
