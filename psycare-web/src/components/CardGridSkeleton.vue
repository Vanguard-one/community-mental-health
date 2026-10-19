<template>
  <div
    class="card-grid-skeleton"
    :style="{ '--col-min': `${min}px` }"
    role="status"
    aria-busy="true"
    aria-live="polite"
  >
    <span class="sr-only">正在加载</span>
    <div v-for="i in count" :key="i" class="cell">
      <a-skeleton active :title="{ width: '48%' }" :paragraph="{ rows: 3 }" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 卡片栅格骨架屏（量表中心、医生推荐这类 auto-fill 卡片墙用）。
 *
 * `min` 必须与该页真实栅格的 minmax 下限一致，否则骨架与真实内容的
 * 列数不同，数据到达时仍会跳一次 —— 那就白做骨架了。
 *   量表中心：minmax(292px, 1fr)  → min=292
 *   医生推荐：minmax(320px, 1fr)  → min=320
 */
withDefaults(defineProps<{ count?: number; min?: number }>(), { count: 6, min: 292 })
</script>

<style scoped>
.card-grid-skeleton {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--col-min), 1fr));
  gap: 16px;
}

.cell {
  padding: 20px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  min-height: 168px;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
