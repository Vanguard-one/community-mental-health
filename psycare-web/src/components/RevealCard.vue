<template>
  <div class="reveal-card">
    <div class="head">
      <span class="title">{{ title }}</span>
      <span v-if="score !== undefined" class="mono score">{{ score.toFixed(1) }}</span>
    </div>

    <div class="body">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 推荐理由专用卡。
 * 用于医生推荐页：把 RecommendationEngine 的排序依据暴露给用户，
 * 使「为什么推荐这位医生」可解释，而不是黑盒结果。
 */
withDefaults(
  defineProps<{
    title?: string
    /** 综合排序得分，展示在标题右侧 */
    score?: number
  }>(),
  { title: '推荐理由', score: undefined },
)
</script>

<style scoped>
.reveal-card {
  position: relative;
  background: var(--panel-bg);
  border-radius: var(--radius-sm);
  padding: 16px 18px 16px 22px;
  overflow: hidden;
}

/* 左侧主色竖线 */
.reveal-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--primary);
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.title {
  font-size: 13px;
  font-weight: 700;
  color: var(--primary);
}

.score {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-1);
}

.body {
  font-size: 13px;
  line-height: 1.75;
  color: var(--text-2);
}
</style>
