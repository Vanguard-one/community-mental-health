<template>
  <slot v-if="!error" />

  <div v-else class="error-boundary">
    <div class="icon" aria-hidden="true">!</div>
    <h3 class="title">{{ title }}</h3>
    <p class="msg">{{ message }}</p>
    <p v-if="showDetail" class="mono detail">{{ detail }}</p>
    <a-button type="primary" @click="reset">重新加载</a-button>
  </div>
</template>

<script setup lang="ts">
import { computed, onErrorCaptured, ref } from 'vue'

/**
 * 全局错误边界。
 * 包裹页面级组件，捕获子树渲染异常，避免整站白屏。
 *
 * 注意：onErrorCaptured 只捕获子组件的渲染/生命周期异常，
 * 不覆盖子组件自身、异步回调（Promise 内部）与事件处理器中的异常，
 * 后者由 request.ts 拦截器统一提示。
 */
const props = withDefaults(
  defineProps<{
    title?: string
    message?: string
    /** 是否展示原始错误信息，默认仅开发环境开启 */
    showDetail?: boolean
  }>(),
  {
    title: '页面渲染出错',
    message: '该模块加载失败，可尝试重新加载或返回上一页。',
    showDetail: import.meta.env.DEV,
  },
)

const error = ref<Error | null>(null)

onErrorCaptured((err) => {
  error.value = err instanceof Error ? err : new Error(String(err))
  // 返回 false 阻止异常继续冒泡到 app 级 errorHandler
  return false
})

const detail = computed(() =>
  error.value ? `${error.value.name}: ${error.value.message}` : '',
)

function reset() {
  error.value = null
}

defineExpose({ reset })
</script>

<style scoped>
.error-boundary {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 10px;
  padding: 56px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

.icon {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--risk-d-soft);
  color: var(--risk-d);
  font-size: 22px;
  font-weight: 700;
}

.title {
  margin: 4px 0 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--text-1);
}

.msg {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
  max-width: 420px;
  line-height: 1.7;
}

.detail {
  margin: 0 0 8px;
  font-size: 11px;
  color: var(--text-3);
  max-width: 520px;
  word-break: break-all;
}
</style>
