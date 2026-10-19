<template>
  <div class="page-container q-page">
    <a-spin :spinning="loading">
      <template v-if="detail && items.length">
        <!--
          动效容器：整页只在进入时错峰播一次（进度 → 题目 → 底部按钮），
          不随每题切换重播 —— 每题都动会把「答题」这件事变得很吵。
          recipe 'stagger' 取容器内所有 [data-anim]，110ms 递进。
        -->
        <div v-reveal="'stagger'" class="q-body">
          <!-- 顶部进度 -->
          <header class="progress-head" data-anim="rise">
            <div class="progress-meta">
              <span class="scale-name font-serif">{{ detail.scale.name }}</span>
              <span class="progress-count mono">{{ answeredCount }} / {{ items.length }}</span>
            </div>
            <div class="progress-track">
              <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
            </div>
          </header>

          <!-- 单题聚焦 -->
          <main class="stage">
            <Transition :name="dir === 'forward' ? 'fwd' : 'bwd'" mode="out-in">
              <div :key="current.id" class="question" data-anim="rise">
                <div class="q-no mono">
                  Q{{ current.itemNo }}<span class="q-no-total"> / {{ items.length }}</span>
                </div>
                <h1 class="q-title">{{ current.content }}</h1>
                <div class="options">
                  <button
                    v-for="(opt, i) in current.options"
                    :key="opt.id"
                    type="button"
                    class="option"
                    :class="{ active: answers[current.id] === opt.id }"
                    @click="choose(opt.id)"
                  >
                    <span class="option-key mono">{{ OPTION_KEYS[i] ?? i + 1 }}</span>
                    <span class="option-text">{{ opt.content }}</span>
                  </button>
                </div>
              </div>
            </Transition>
          </main>

          <!-- 底部导航 -->
          <footer class="nav-bar" data-anim="rise">
            <a-button :disabled="currentIndex === 0" @click="go(-1)">上一题</a-button>
            <span class="nav-hint">
              <template v-if="restored">已恢复上次作答</template>
              <template v-else-if="!allAnswered && isLast">
                还有 {{ items.length - answeredCount }} 题未作答
              </template>
            </span>
            <a-button v-if="!isLast" type="primary" :disabled="answers[current.id] === undefined" @click="go(1)">
              下一题
            </a-button>
            <a-button
              v-else
              type="primary"
              size="large"
              :disabled="!allAnswered"
              :loading="submitting"
              @click="onSubmit"
            >
              提交测评
            </a-button>
          </footer>
        </div>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAssessmentStore } from '@/stores/assessment'

const route = useRoute()
const router = useRouter()
const store = useAssessmentStore()

const scaleId = Number(route.params.id)
const DRAFT_KEY = `psycare:questionnaire:${scaleId}`

/** 选项序号：用字母而不是数字，避免被读成「请输入题号」 */
const OPTION_KEYS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H']

const currentIndex = ref(0)
const dir = ref<'forward' | 'backward'>('forward')
const restored = ref(false)

const loading = computed(() => store.detailLoading)
const submitting = computed(() => store.submitting)
const detail = computed(() => store.detail)
const answers = computed(() => store.answers)
const items = computed(() => store.detail?.items ?? [])
const answeredCount = computed(() => store.answeredCount)
const progressPercent = computed(() => store.progressPercent)
const allAnswered = computed(() => store.allAnswered)
const isLast = computed(() => currentIndex.value >= items.value.length - 1)
const current = computed(() => items.value[currentIndex.value])

let advanceTimer: number | undefined

function clearAdvanceTimer() {
  if (advanceTimer !== undefined) {
    window.clearTimeout(advanceTimer)
    advanceTimer = undefined
  }
}

function go(step: number) {
  const next = currentIndex.value + step
  if (next < 0 || next >= items.value.length) return
  dir.value = step > 0 ? 'forward' : 'backward'
  currentIndex.value = next
}

/** 选中即答完，短暂停顿后自动翻到下一题；最后一题不翻，留出复核空间 */
function choose(optionId: number) {
  store.setAnswer(current.value.id, optionId)
  clearAdvanceTimer()
  if (!isLast.value) {
    advanceTimer = window.setTimeout(() => go(1), 280)
  }
}

interface Draft {
  answers: Record<number, number>
  index: number
}

function saveDraft() {
  try {
    sessionStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({ answers: answers.value, index: currentIndex.value } satisfies Draft),
    )
  } catch {
    /* 隐私模式等场景写入失败可忽略，不影响作答 */
  }
}

function clearDraft() {
  sessionStorage.removeItem(DRAFT_KEY)
}

async function onSubmit() {
  try {
    const result = await store.submit()
    clearDraft()
    router.push(`/assessment/${result.assessmentId}/result`)
  } catch {
    // 失败提示已由拦截器统一处理，这里保留在当前页便于重试
  }
}

onMounted(async () => {
  await store.loadDetail(scaleId)
  // 草稿必须在 detail 就绪、题目 id 确定之后再恢复
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    if (raw) {
      const draft = JSON.parse(raw) as Draft
      const valid = Object.fromEntries(
        Object.entries(draft.answers ?? {}).filter(([itemId]) =>
          items.value.some((it) => it.id === Number(itemId)),
        ),
      )
      if (Object.keys(valid).length) {
        store.restoreAnswers(valid)
        currentIndex.value = Math.min(Math.max(draft.index ?? 0, 0), items.value.length - 1)
        restored.value = true
      }
    }
  } catch {
    /* 草稿损坏则按全新作答处理 */
  }
  saveDraft()
})

watch([answers, currentIndex], saveDraft, { deep: true })

onUnmounted(clearAdvanceTimer)
</script>

<style scoped>
.q-page {
  max-width: 760px;
  margin: 0 auto;
  /*
   * 顶栏已移入侧栏（DefaultLayout 改成左侧导航），内容区从视口顶端开始，
   * 所以直接用 100vh —— 再减 --header-h 会矮掉一整块。
   * border-box 下 min-height 已含 padding，不需要额外补 padding。
   */
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/*
 * a-spin 会在内容和外层之间插入 .ant-spin-nested-loading / .ant-spin-container 两层 div，
 * 把 flex 链打断（.stage 的 flex:1 与外层 min-height 之间失去联系，页面撑不满）。
 * 必须显式把这两层透传成 flex 容器。
 * .q-body 是动效容器，同样要透传 flex 链。
 */
.q-page :deep(.ant-spin-nested-loading),
.q-page :deep(.ant-spin-container),
.q-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* ---------- 进度 ---------- */
.progress-head {
  margin-bottom: 8px;
}

.progress-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.scale-name {
  font-size: 16px;
  color: var(--text-1);
}

/* 进度计数直接落在页面底上 → 只能 --text-1 / --text-2；--text-3 在这只有 3.60:1 */
.progress-count {
  font-size: 13px;
  color: var(--text-2);
}

/*
 * 自绘进度条（不引入 a-progress，省 vendor 预算）。
 * 轨道用 L1 白：贴页面底的面只有白卡或主色看得见，--panel-bg 在这约 1.04:1。
 * 填充走 --grad-primary（小面积焦点图形，正是这个 token 的适用范围）。
 */
.progress-track {
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--grad-primary);
  transition: width 0.3s var(--ease);
}

/* ---------- 题目（L1 白卡） ---------- */
.stage {
  flex: 1;
  display: flex;
  align-items: center;
  padding: 24px 0;
}

.question {
  width: 100%;
  padding: var(--space-inner);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.q-no {
  font-size: 13px;
  font-weight: 600;
  color: var(--primary);
  letter-spacing: 0.04em;
  margin-bottom: 12px;
}

/* 白卡上的 --text-3 是 4.8:1，达 AA；不要把它挪到任何 --panel-bg 分区里 */
.q-no-total {
  color: var(--text-3);
  font-weight: 400;
}

.q-title {
  margin: 0 0 var(--space-inner);
  font-family: var(--font-serif);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--text-1);
}

.options {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/*
 * 选项块 = 白卡内部的 L2 分区（--panel-bg），只用底色与白卡分层，**不叠阴影**
 * （卡内再叠阴影会让画面发脏）。
 * 选中态：主色描边 + --primary-soft 底 —— 这个浅色只在白卡内部成立，
 * 贴到页面底上会和底同亮（约 1.04:1）而消失。
 */
.option {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  min-height: 56px;
  padding: 14px 18px;
  border: 1px solid transparent; /* 预留描边位，选中时不产生位移 */
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  color: var(--text-2);
  font-family: inherit;
  font-size: 15px;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.option:hover {
  border-color: var(--ctrl-border);
}

/*
 * 选中态文字用 --text-1 而不是 --primary：
 * 实测 --primary 落在 --primary-soft 上只有 4.44:1，15px 未达 AA 的 4.5
 * （也不是大字体豁免）。选中信号由描边 + 浅底 + 序号徽标 + 字重共同承担。
 */
.option.active {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--text-1);
  font-weight: 600;
}

/* 选项序号：等宽字母，选中时翻成主色实底白字 */
.option-key {
  flex: none;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ctrl-border);
  border-radius: 50%;
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 12.5px;
  font-weight: 600;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.option.active .option-key {
  background: var(--primary);
  border-color: var(--primary);
  color: var(--on-primary);
}

.option-text {
  line-height: 1.5;
}

/* ---------- 导航 ---------- */
/* 不用 sticky + 背景渐变色带：页面底部的底色随渐变位置变化，
   写死任何一种底色都会在底部露出一条色块 */
.nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 0;
  flex-shrink: 0;
}

/* 同上：导航提示也裸落在页面底上，--text-3 在这不达 AA */
.nav-hint {
  flex: 1;
  text-align: center;
  font-size: 13px;
  color: var(--text-2);
}

/* ---------- 切题过渡 ---------- */
.fwd-enter-active,
.fwd-leave-active,
.bwd-enter-active,
.bwd-leave-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}

.fwd-enter-from {
  opacity: 0;
  transform: translateX(28px);
}

.fwd-leave-to {
  opacity: 0;
  transform: translateX(-28px);
}

.bwd-enter-from {
  opacity: 0;
  transform: translateX(-28px);
}

.bwd-leave-to {
  opacity: 0;
  transform: translateX(28px);
}

@media (max-width: 640px) {
  .q-title {
    font-size: 21px;
  }

  .question {
    padding: 20px;
  }
}
</style>
