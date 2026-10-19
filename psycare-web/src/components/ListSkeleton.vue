<template>
  <div class="list-skeleton" role="status" aria-busy="true" aria-live="polite">
    <span class="sr-only">正在加载</span>
    <div v-for="i in count" :key="i" class="row">
      <a-skeleton active :title="false" :paragraph="{ rows: 2, width: ['62%', '38%'] }" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 列表加载骨架屏。
 *
 * 统一列表页的空窗期表现：此前各页只用 a-spin 转圈，
 * 内容区高度会在数据到达瞬间从 0 跳变，观感是「页面抖一下」。
 * 骨架屏按最终卡片数量占位，避免布局跳动，也让等待有确定感。
 *
 * 注意：只在**首次加载**时用（loading 且列表为空）；
 * 已有数据时的翻页/刷新请继续用 a-spin，否则会把已有内容抹掉。
 */
withDefaults(defineProps<{ count?: number }>(), { count: 4 })
</script>

<style scoped>
.list-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row {
  padding: 18px 20px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
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
