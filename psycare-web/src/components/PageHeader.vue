<template>
  <!--
    页头。改版（2026-10-13）：补上一层 L1 白面。

    为什么必须补：改动前页头是「无底色」的 —— 标题直接落在页面上、副标题裸落在
    页面底上（实测 --text-3 vs 页面底只有 3.31:1，不到 AA）。把它做成一张标准白卡后，
    副标题的对比度回到 4.8:1，同时全站 8 个用它的页面共享同一块「页面标题面」，
    比每个页面自己写标题在结构上更不容易走样。

    动效：`v-reveal="'header'"` + 三个 `data-anim`，走 recipe 的 90ms 递进
    （标题 → 副标 → 右侧操作）。页头是每页的第一块内容，让它在进入视口时就位，
    等于给整页定一个起跑节奏。
  -->
  <header v-reveal="'header'" class="page-header">
    <div class="left">
      <button v-if="back" class="back" type="button" aria-label="返回" @click="onBack">←</button>
      <div class="titles">
        <h1 class="title font-serif" data-anim="rise">{{ title }}</h1>
        <p v-if="subtitle" class="subtitle" data-anim="rise">{{ subtitle }}</p>
      </div>
    </div>

    <div v-if="$slots.extra" class="extra" data-anim="chrome">
      <slot name="extra" />
    </div>
  </header>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    /** 显示返回按钮 */
    back?: boolean
  }>(),
  { subtitle: '', back: false },
)

const emit = defineEmits<{ (e: 'back'): void }>()
const router = useRouter()

function onBack() {
  emit('back')
  router.back()
}
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 22px;
  margin-bottom: var(--space-block);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

.left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.back {
  flex: none;
  width: 32px;
  height: 32px;
  border: 1px solid var(--ctrl-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}
.back:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.titles {
  min-width: 0;
}

.title {
  margin: 0;
  font-size: 23px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--text-1);
}

/* 副标落在白面上 → --text-3 的 4.8:1 成立。
   ⚠️ 不要把它挪到 --panel-bg 之类的浅分区里，那边 --text-3 只有 4.27。 */
.subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-3);
}

.extra {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
