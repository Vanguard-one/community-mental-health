<template>
  <article class="doc-row">
    <RoleAvatar class="dr-avatar" role="doctor" :size="48" />

    <div class="dr-main">
      <h3 class="dr-name">
        {{ doctor.doctorName }}
        <span v-if="doctor.title" class="dr-title">{{ doctor.title }}</span>
      </h3>

      <p class="dr-meta">
        <span class="dr-rating">★ <span class="mono">{{ ratingText }}</span></span>
        <span class="dr-sep" aria-hidden="true">·</span>
        <span>接诊 <span class="mono">{{ doctor.currentLoad ?? 0 }}</span> 人次</span>
        <template v-if="doctor.department">
          <span class="dr-sep" aria-hidden="true">·</span>
          <span class="dr-dept">{{ doctor.department }}</span>
        </template>
      </p>

      <!--
        专长用描边 chip 而不是实底：实底要解决「主色字压浅底只有 4.44:1」的对比度问题，
        描边没有这个约束，视觉上也更轻 —— 一屏要放好几位医生，chip 太重要抢走注意力。
      -->
      <div v-if="tags.length" class="dr-tags">
        <span v-for="t in tags" :key="t" class="dr-tag">{{ t }}</span>
      </div>
    </div>

    <div class="dr-ops">
      <button type="button" class="op op-primary" @click="emit('book', doctor)">预约</button>
      <!--
        保留「先聊聊」：线上文字咨询是既有能力，删掉等于藏功能。
        它与预约是两条并行的路 —— 有人想先问两句再决定要不要占一个号。
      -->
      <button type="button" class="op op-ghost" @click="emit('chat', doctor)">
        {{ doctor.sessionId ? '继续聊天' : '先聊聊' }}
      </button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import type { ChatPeer } from '@/types'

/**
 * 医生横条卡（`/doctors` 二级列表用）。
 *
 * <h3>为什么不复用 RecommendedDoctors.vue</h3>
 * 那个组件是<strong>网格卡 + 匹配依据</strong>，服务于「AI 替你挑好了」的语境 ——
 * 卡片上要回答的是「为什么推荐他」。这里要的是横条卡（一屏看多人、便于按科室浏览），
 * 没有匹配分，也不需要那么大的视觉重量。
 * 把两者合并意味着组件里长出 `mode` 分支和一组只有一半场景生效的 props，
 * 那种组件在改动时会让人反复确认「这个 prop 在这个模式下有用吗」。
 *
 * <p>卡片本身不发请求、不做路由决策：`book` / `chat` 都交给父级 ——
 * 同一张卡将来若出现在别的页面，目的地可能不同。
 */
const props = defineProps<{ doctor: ChatPeer }>()

const emit = defineEmits<{
  (e: 'book', doctor: ChatPeer): void
  (e: 'chat', doctor: ChatPeer): void
}>()

/** 专长是逗号分隔字符串（中英文逗号都容错），后端不做结构化 */
const tags = computed(() =>
  (props.doctor.expertiseTags || '')
    .split(/[,，]/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 4),
)

const ratingText = computed(() =>
  props.doctor.rating == null ? '—' : props.doctor.rating.toFixed(1),
)
</script>

<style scoped>
.doc-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
  transition: border-color var(--dur) var(--ease);
}

.doc-row:hover {
  border-color: var(--primary-dark);
}

.dr-avatar {
  flex: none;
}

/* min-width:0 是必需的：flex 子项默认 min-width:auto，
   不写这一条时长专长文本会把整个卡片撑破，而不是内部换行 */
.dr-main {
  flex: 1;
  min-width: 0;
}

.dr-name {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 600;
}

/* 职称不是标题的一部分，压低到 --text-3 —— 但它只能落白面（本卡就是白面，4.8:1） */
.dr-title {
  color: var(--text-3);
  font-size: 12.5px;
  font-weight: 400;
}

.dr-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 6px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.dr-rating {
  color: var(--text-2);
}

.dr-sep {
  color: var(--text-3);
}

/*
 * 科室只写文字，不用 DeptIcon：那个组件是 60×60 的渐变砖（内圈高光 + 外圈辉光），
 * 缩到 meta 行需要的大小后描边和辉光会糊成一团，图形本身也认不出。
 * 它适合科室级卡片（如 /scales 的分类入口），不适合 12.5px 的元信息行。
 */
.dr-dept {
  color: var(--text-2);
}

.dr-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.dr-tag {
  padding: 2px 8px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  color: var(--text-2);
  font-size: 11.5px;
}

.dr-ops {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
}

.op {
  height: 34px;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: var(--radius-ctrl);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

/* 主色实底 + --on-primary 白字：5.17:1。不要用 --primary-soft 填底配 --primary 字（4.44:1） */
.op-primary {
  background: var(--primary);
  color: var(--on-primary);
}

.op-primary:hover {
  background: var(--primary-dark);
}

.op-ghost {
  border-color: var(--card-border);
  background: transparent;
  color: var(--text-1);
}

.op-ghost:hover {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

/*
 * 窄屏把操作按钮从「右侧竖排」改成整行横排并铺满。
 * 媒体查询必须排在基础规则之后（硬约定 #26）。
 */
@media (max-width: 640px) {
  .doc-row {
    flex-wrap: wrap;
    gap: 12px;
    padding: 16px;
  }

  .dr-main {
    flex: 1 1 calc(100% - 64px);
  }

  .dr-ops {
    flex-direction: row;
    width: 100%;
  }

  .op {
    flex: 1;
  }
}
</style>
