<template>
  <div class="sched-page">
    <PageHeader title="预约设置" subtitle="为居民开放在线预约的时间段，每段可设置接诊人数上限">
      <template #extra>
        <button type="button" class="btn btn-primary" :disabled="saving || !draft.length" @click="save">
          {{ saving ? '保存中…' : '保存当天时段' }}
        </button>
      </template>
    </PageHeader>

    <p v-if="loading" class="hint">加载中…</p>

    <template v-else>
      <!--
        批量排班：一套模板铺到多个周几上。

        放在日期条上方而不是塞进右侧「添加时段」卡：它是跨天的全局动作，
        和右边那个「往当前这一天加一行」不是同一个粒度，混在一起
        会让医生以为要先选日期才能批量铺。

        `v-show` 而不是 `v-if`：收起/展开时保留已输入的模板，
        否则每次收起再展开都要重新填 5 行时间。
      -->
      <section class="card bulk-card">
        <header class="card-head bulk-head">
          <div>
            <h2 class="card-title">按周批量排班</h2>
            <p class="card-sub">
              默认周一至周五 · {{ bulk.slots.length }} 个时段，一次铺两周，省掉逐天配置
            </p>
          </div>
          <button type="button" class="bulk-toggle" @click="bulkOpen = !bulkOpen">
            {{ bulkOpen ? '收起' : '展开' }}
          </button>
        </header>

        <div v-show="bulkOpen" class="bulk-body">
          <div class="bulk-line">
            <span class="bulk-label">每周</span>
            <div class="wd-row">
              <label
                v-for="(label, i) in WD_LABELS"
                :key="label"
                class="wd-chip"
                :class="{ 'is-on': bulk.weekdays.includes(i + 1) }"
              >
                <input v-model="bulk.weekdays" type="checkbox" :value="i + 1" />
                {{ label }}
              </label>
            </div>
            <label class="bulk-weeks">
              <span class="bulk-label">铺</span>
              <select v-model.number="bulk.weekCount" class="input wk-select">
                <option v-for="n in MAX_WEEKS" :key="n" :value="n">{{ n }} 周</option>
              </select>
            </label>
          </div>

          <div class="tpl-list">
            <div v-for="(s, i) in bulk.slots" :key="s.key" class="tpl-row">
              <input v-model="s.startTime" type="time" step="300" class="input tpl-time" />
              <span class="tpl-dash" aria-hidden="true">–</span>
              <input v-model="s.endTime" type="time" step="300" class="input tpl-time" />
              <label class="tpl-cap-field">
                <span class="tpl-cap-label">上限</span>
                <input
                  v-model.number="s.capacity"
                  type="number"
                  min="1"
                  max="50"
                  class="input tpl-cap"
                />
              </label>
              <button
                type="button"
                class="row-del"
                :disabled="bulk.slots.length <= 1"
                title="删除这个模板时段"
                @click="removeTpl(i)"
              >
                删除
              </button>
            </div>
          </div>

          <div class="tpl-ops">
            <button type="button" class="btn btn-ghost btn-sm" @click="addTpl">
              + 加一个时段
            </button>
            <label class="chk">
              <input v-model="bulk.overwriteCapacity" type="checkbox" />
              <span>同时把已有时段的人数上限改成上面的值</span>
            </label>
          </div>

          <div class="bulk-foot">
            <button
              type="button"
              class="btn btn-primary"
              :disabled="bulking || !bulk.slots.length || !bulk.weekdays.length"
              @click="runBulk"
            >
              {{ bulking ? '生成中…' : `一键铺 ${previewDays} 天` }}
            </button>
            <p class="bulk-hint">
              预计 {{ previewDays }} 天 × {{ bulk.slots.length }} 段 =
              <b class="mono">{{ previewDays * bulk.slots.length }}</b> 个号源。
              已有号源不会被删除；今天已过的时段自动跳过。
            </p>
          </div>
        </div>
      </section>

      <!--
        日期条：28 天连续给出（4 周 × 7 天，与批量排班的上限对齐 ——
        铺了 4 周却只能看到 2 周的话，后两周就再也没有入口能改）。
        没有排班的日期也在这里（后端把「这天没号」表达为
        空 slots 而不是缺字段），否则日期条会出现断档，用户会以为是加载失败。
      -->
      <nav class="day-bar" aria-label="选择日期">
        <button
          v-for="d in days"
          :key="d.dateText"
          type="button"
          class="day-chip"
          :class="{ 'is-on': d.dateText === selected }"
          @click="selected = d.dateText"
        >
          <span class="dc-week">{{ d.weekday }}</span>
          <span class="dc-date mono">{{ d.shortText }}</span>
          <span class="dc-count mono">{{ d.slots.length ? `${d.slots.length} 段` : '休' }}</span>
        </button>
      </nav>

      <div class="sched-body">
        <!-- 左：当天时段列表 -->
        <section class="card">
          <header class="card-head">
            <h2 class="card-title">{{ currentLabel }} 的时段</h2>
            <p class="card-sub">
              共 <b class="mono">{{ draft.length }}</b> 段 · 可接诊
              <b class="mono">{{ totalCapacity }}</b> 人 · 已约
              <b class="mono">{{ totalBooked }}</b> 人
            </p>
          </header>

          <p v-if="!draft.length" class="empty">
            这天还没有排班。<br />
            在右侧添加时段后点「保存当天时段」，居民就能看到并预约了。
          </p>

          <ul v-else class="slot-list">
            <li v-for="(s, i) in draft" :key="s.key" class="slot-row">
              <span class="slot-time mono">{{ s.startTime }}-{{ s.endTime }}</span>
              <span class="slot-meta">上限 <b class="mono">{{ s.capacity }}</b></span>
              <!-- 已约人数是这一行最重要的信息：它决定「还能不能删」和「还能不能改小」 -->
              <span class="slot-meta" :class="{ 'is-has': s.bookedCount > 0 }">
                已约 <b class="mono">{{ s.bookedCount }}</b>
              </span>
              <span class="slot-left mono" :class="{ 'is-full': s.bookedCount >= s.capacity }">
                {{ s.bookedCount >= s.capacity ? '已满' : `剩 ${s.capacity - s.bookedCount}` }}
              </span>
              <button
                type="button"
                class="row-del"
                :disabled="s.bookedCount > 0"
                :title="s.bookedCount > 0 ? `已有 ${s.bookedCount} 人预约，不能删除` : '删除该时段'"
                @click="removeRow(i)"
              >
                删除
              </button>
            </li>
          </ul>
        </section>

        <!-- 右：新增时段 -->
        <section class="card add-card">
          <header class="card-head">
            <h2 class="card-title">添加时段</h2>
            <p class="card-sub">同一天最多 20 段，单段上限 50 人</p>
          </header>

          <div class="form-row">
            <label class="field">
              <span class="field-label">开始</span>
              <!-- 原生 type=time：引入 antd 的 a-time-picker 会往 vendor-antd 里再加一块 -->
              <input v-model="form.startTime" type="time" step="300" class="input" />
            </label>
            <label class="field">
              <span class="field-label">结束</span>
              <input v-model="form.endTime" type="time" step="300" class="input" />
            </label>
          </div>

          <label class="field">
            <span class="field-label">该时段人数上限</span>
            <input v-model.number="form.capacity" type="number" min="1" max="50" class="input" />
          </label>

          <button type="button" class="btn btn-ghost btn-block" @click="addRow">添加到当天</button>

          <p class="tips">
            改动只在本页生效，点右上角「保存当天时段」才会写入。<br />
            已有预约的时段不能删除，但可以把上限改到等于已约人数来停止放号。
          </p>
        </section>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import PageHeader from '@/components/PageHeader.vue'
import { deleteScheduleSlot, getMySchedule, saveScheduleBatch, saveWeeklySchedule } from '@/api/schedule'
import type { ScheduleDay } from '@/types'

/** 编辑中的一行。`id` 存在表示后端已有该时段（删除要真调接口），否则只是草稿 */
interface DraftRow {
  key: number
  id?: number
  startTime: string
  endTime: string
  capacity: number
  bookedCount: number
}

const DAYS_AHEAD = 28

/** 与后端 MAX_WEEKS 对齐：4 周正好覆盖上面那 28 天的日期条 */
const MAX_WEEKS = 4

/** 周几标签，下标 0 是周一。批量接口要 1~7，故取值恒为 `index + 1` */
const WD_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/**
 * 默认模板：上午 3 段 + 下午 2 段，每段 30 分钟、上限 3 人。
 *
 * 社区心理门诊的典型节奏就是一上午看三个人、下午看两个。
 * 给一份能直接点「生成」的默认值，比让医生从空白开始填 5 行更实际 ——
 * 真要调，改两个数字比从零开始快得多。
 */
const DEFAULT_SLOTS: [string, string][] = [
  ['09:00', '09:30'],
  ['09:30', '10:00'],
  ['10:00', '10:30'],
  ['14:00', '14:30'],
  ['14:30', '15:00'],
]

const loading = ref(false)
const saving = ref(false)
const days = ref<ScheduleDay[]>([])
const selected = ref('')
const draft = ref<DraftRow[]>([])

/** 用它做 v-for 的 key：索引当 key 在删除中间行时会让输入状态错位 */
let keySeed = 0

const form = reactive({ startTime: '09:00', endTime: '09:30', capacity: 3 })

/** 批量排班的模板行。与 DraftRow 的区别：它不对应任何真实号源，没有 id、没有已约数 */
interface TplRow {
  key: number
  startTime: string
  endTime: string
  capacity: number
}

const bulkOpen = ref(true)
const bulking = ref(false)

const bulk = reactive({
  weekdays: [1, 2, 3, 4, 5] as number[],
  weekCount: 2,
  overwriteCapacity: false,
  slots: [] as TplRow[],
})

// ⚠️ 必须在 `let keySeed = 0` 之后初始化：写在前面会命中 TDZ（硬约定 #20）
bulk.slots = DEFAULT_SLOTS.map(([startTime, endTime]) => ({
  key: ++keySeed,
  startTime,
  endTime,
  capacity: 3,
}))

const currentDay = computed(() => days.value.find((d) => d.dateText === selected.value))

const currentLabel = computed(() => {
  const d = currentDay.value
  return d ? `${d.shortText} ${d.weekday}` : '所选日期'
})

const totalCapacity = computed(() => draft.value.reduce((n, s) => n + s.capacity, 0))
const totalBooked = computed(() => draft.value.reduce((n, s) => n + s.bookedCount, 0))

/**
 * 一键生成会铺几天（按钮上的数字）。
 *
 * 从服务端返回的 `days` 里筛，不在前端自己算日期：`new Date('2026-10-25')`
 * 会按 UTC 解析，东八区用户可能算出前一天，星期几跟着错一位 ——
 * 于是预览说「10 天」而实际只铺了 8 天。`days` 里的 weekday 是服务端算好的。
 *
 * 还要额外排掉「今天的时段已经全部过去」这种情况：下午 4 点打开页面时，
 * 今天压根不会产生号源，算进去就会变成「按钮说 10 天、提示说 9 天」。
 */
const previewDays = computed(() => {
  if (!bulk.weekdays.length || !bulk.slots.length) {
    return 0
  }
  const today = days.value[0]?.dateText
  const now = new Date()
  const nowMinutes = now.getHours() * 60 + now.getMinutes()
  const lastEnd = Math.max(...bulk.slots.map((s) => toMinutes(s.endTime)))

  return days.value
    .slice(0, bulk.weekCount * 7)
    .filter((d) => {
      if (!bulk.weekdays.includes(WD_LABELS.indexOf(d.weekday) + 1)) {
        return false
      }
      if (d.dateText === today && lastEnd <= nowMinutes) {
        return false
      }
      return true
    }).length
})

/**
 * 把当天服务端数据摊成草稿。
 *
 * 逐行带出 `id` 与 `bookedCount`：前者决定「删除是否要调接口」，
 * 后者决定「删除按钮是否可用、上限能不能改小」。少了任何一个，
 * 界面都会变成「看起来能删，点了报错」。
 */
function resetDraft() {
  const slots = currentDay.value?.slots ?? []
  draft.value = slots.map((s) => ({
    key: ++keySeed,
    id: s.id,
    startTime: s.startText,
    endTime: s.endText,
    capacity: s.capacity,
    bookedCount: s.bookedCount,
  }))
}

/**
 * 拉取号源。
 *
 * `silent` 用于「批量生成之后立即刷新」：置 true 时不切 loading，
 * 页面上的日期条与卡片就不会先被整块卸载再挂回来（会闪一下）。
 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
  }
  try {
    const list = await getMySchedule(undefined, DAYS_AHEAD)
    days.value = list
    if (!list.some((d) => d.dateText === selected.value)) {
      selected.value = list[0]?.dateText ?? ''
    }
    resetDraft()
  } catch {
    // 接口错误已由 request.ts 拦截器统一提示，这里静默即可
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

// ⚠️ watch 必须写在它依赖的 `selected` 之后：写在前面会命中 TDZ，
// setup 同步抛 ReferenceError → 整页白屏，而 vue-tsc 与 build 都查不出来（硬约定 #20）。
watch(selected, () => resetDraft())

function addRow() {
  const { startTime, endTime } = form
  const capacity = Number(form.capacity)
  if (!startTime || !endTime) {
    message.warning('请填写开始与结束时间')
    return
  }
  if (startTime >= endTime) {
    message.warning('开始时间必须早于结束时间（暂不支持跨天排班）')
    return
  }
  if (!Number.isFinite(capacity) || capacity < 1) {
    message.warning('人数上限至少为 1')
    return
  }
  if (draft.value.some((s) => s.startTime === startTime)) {
    message.warning('这个开始时间已经排过了')
    return
  }
  // 重叠检测：字符串比较对 "HH:mm" 成立（定长、零填充）
  if (draft.value.some((s) => startTime < s.endTime && s.startTime < endTime)) {
    message.warning('与已排的时段重叠')
    return
  }
  draft.value.push({ key: ++keySeed, startTime, endTime, capacity, bookedCount: 0 })
  draft.value.sort((a, b) => a.startTime.localeCompare(b.startTime))
}

async function removeRow(index: number) {
  const row = draft.value[index]
  if (!row) {
    return
  }
  if (row.bookedCount > 0) {
    message.warning(`已有 ${row.bookedCount} 人预约，不能删除；可把上限改为 ${row.bookedCount} 停止放号`)
    return
  }
  // 已落库的行要立刻真删：留着「待保存的删除」会让「保存」的语义变成
  // 「增改 + 删」三件事，而其中删除是不可逆的 —— 医生改个上限顺手删掉两个时段，
  // 这种事故只要发生过一次就不会有人再信任这个页面。
  if (row.id) {
    try {
      await deleteScheduleSlot(row.id)
    } catch {
      return
    }
    const day = currentDay.value
    if (day) {
      day.slots = day.slots.filter((s) => s.id !== row.id)
    }
  }
  draft.value.splice(index, 1)
}

async function save() {
  if (!draft.value.length) {
    message.warning('至少保留一个时段')
    return
  }
  if (!selected.value) {
    return
  }
  saving.value = true
  try {
    const saved = await saveScheduleBatch({
      workDate: selected.value,
      slots: draft.value.map((s) => ({
        startTime: s.startTime,
        endTime: s.endTime,
        capacity: s.capacity,
      })),
    })
    // 用响应覆盖当天数据：新增的行这时才拿到真实 id 与 bookedCount，
    // 不覆盖的话「刚加完的行删不掉」（id 还是 undefined，被当成草稿只 removel 本地）
    const day = currentDay.value
    if (day) {
      day.slots = saved
    }
    resetDraft()
    message.success('已保存')
  } catch {
    // 已提示
  } finally {
    saving.value = false
  }
}

onMounted(() => load())

// ---------------- 批量排班 ----------------

/** "HH:mm" → 当天第几分钟。与「当前时刻」比较必须换成数值，字符串比不了 */
function toMinutes(text: string): number {
  const [h = 0, m = 0] = text.split(':').map(Number)
  return h * 60 + m
}

/** "HH:mm" + 分钟数。用于「加一段」时接着上一段往下排（跨 24:00 会绕回） */
function addMinutes(text: string, minutes: number): string {
  const total = (toMinutes(text) + minutes) % (24 * 60)
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

/** 新段接在最后一段后面：连点几下就排出一个上午，比每次重填时间快 */
function addTpl() {
  const last = bulk.slots[bulk.slots.length - 1]
  const startTime = last?.endTime ?? '09:00'
  bulk.slots.push({
    key: ++keySeed,
    startTime,
    endTime: addMinutes(startTime, 30),
    capacity: last?.capacity ?? 3,
  })
}

function removeTpl(index: number) {
  if (bulk.slots.length <= 1) {
    message.warning('至少保留一个时段')
    return
  }
  bulk.slots.splice(index, 1)
}

async function runBulk() {
  if (!bulk.weekdays.length) {
    message.warning('请至少选择一个周几')
    return
  }
  if (!bulk.slots.length) {
    message.warning('至少配置一个时段')
    return
  }

  // 前端先挡一遍，后端也挡：等一次往返再报错，医生已经不知道是哪两段冲突
  const sorted = [...bulk.slots].sort((a, b) => a.startTime.localeCompare(b.startTime))
  for (let i = 0; i < sorted.length; i++) {
    const s = sorted[i]
    const cap = Number(s.capacity)
    if (!s.startTime || !s.endTime || s.startTime >= s.endTime) {
      message.warning(`时段 ${s.startTime || '?'}-${s.endTime || '?'} 的开始必须早于结束`)
      return
    }
    if (!Number.isFinite(cap) || cap < 1 || cap > 50) {
      message.warning(`时段 ${s.startTime}-${s.endTime} 的人数上限应在 1~50 之间`)
      return
    }
    // 字符串比较对 "HH:mm" 成立（定长、零填充）
    if (i > 0 && s.startTime < sorted[i - 1].endTime) {
      message.warning(`时段 ${s.startTime}-${s.endTime} 与前一段重叠`)
      return
    }
  }

  bulking.value = true
  try {
    const r = await saveWeeklySchedule({
      weekCount: bulk.weekCount,
      weekdays: [...bulk.weekdays].sort((a, b) => a - b),
      overwriteCapacity: bulk.overwriteCapacity,
      slots: sorted.map((s) => ({
        startTime: s.startTime,
        endTime: s.endTime,
        capacity: Number(s.capacity),
      })),
    })
    // summary 由服务端拼：三个计数的组合语义（全跳过 = 已经排过、
    // 部分跳过 = 今天已过）只有那边清楚，前端再拼一次就要重复这套判断
    message.success(r.summary || '已生成')
    await load(true)
  } catch {
    // 已由 request.ts 拦截器统一提示
  } finally {
    bulking.value = false
  }
}
</script>

<style scoped>
/*
 * 不加 padding：外层 AdminLayout 的 .content 已经带了 var(--content-pad)，
 * 这里再加一层会变成 56px 的空白（ScaleManage / BannerManage 同样不加）。
 */
.sched-page {
  display: flex;
  flex-direction: column;
}

.hint {
  margin: var(--space-block) 0;
  color: var(--text-3);
  font-size: 14px;
}

/* ---------------- 批量排班 ---------------- */

.bulk-card {
  margin-bottom: var(--space-card);
}

.bulk-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.bulk-toggle {
  flex: none;
  padding: 4px 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.bulk-toggle:hover {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

.bulk-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
  margin-bottom: 14px;
}

.bulk-label {
  flex: none;
  color: var(--text-2);
  font-size: 12.5px;
}

.wd-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/*
 * 选中态用 --primary-soft 底 + --primary-dark 字（5.71:1）。
 * 不能写「--primary 字 + --primary-soft 底」—— 那只有 4.44:1，差一点不达 AA。
 */
.wd-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.wd-chip:hover {
  border-color: var(--primary-dark);
}

.wd-chip.is-on {
  border-color: transparent;
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-weight: 600;
}

.wd-chip input {
  accent-color: var(--primary);
}

.bulk-weeks {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

/* 双类名：.input 的 width:100% 定义在下方，单类名会被它盖掉 */
.input.wk-select {
  width: 92px;
}

.tpl-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tpl-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-ctrl);
  /* 落 L1 白卡之内用 --panel-bg；input 仍是 --card-bg，所以输入框在白底行上是凸起的 */
  background: var(--panel-bg);
}

.input.tpl-time {
  width: 112px;
  flex: none;
}

.tpl-dash {
  flex: none;
  color: var(--text-3);
}

.tpl-cap-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.tpl-cap-label {
  color: var(--text-2);
  font-size: 12.5px;
}

.input.tpl-cap {
  width: 70px;
}

.tpl-ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-top: 12px;
}

.chk {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-2);
  font-size: 12.5px;
  cursor: pointer;
}

.chk input {
  accent-color: var(--primary);
}

.bulk-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--card-border);
}

/* 双类名同上：.btn 的高度定义在下方 */
.btn.btn-sm {
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
}

.bulk-hint {
  flex: 1;
  min-width: 220px;
  margin: 0;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.7;
}

/* ⚠️ 媒体查询必须排在它要覆盖的规则之后（硬约定 #26） */
@media (max-width: 640px) {
  .tpl-row {
    flex-wrap: wrap;
  }

  .tpl-cap-field {
    margin-left: 0;
  }

  .bulk-weeks {
    margin-left: 0;
  }
}

/* ---------------- 日期条 ---------------- */

/*
 * 横向滚动而不是换行：14 个 chip 在 1440 宽下刚好一行，
 * 换行会让「第二行只剩两个」显得像加载失败。
 * 只加 padding-bottom 给滚动条留位置，不做负外边距 ——
 * 本页根容器没有内边距（交给 AdminLayout 的 .content），
 * 负边距会把日期条推到比页头更靠左，看起来像没对齐。
 */
.day-bar {
  display: flex;
  gap: 8px;
  margin: 0 0 var(--space-card);
  padding-bottom: 8px;
  overflow-x: auto;
  scrollbar-width: thin;
}

.day-chip {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  width: 72px;
  padding: 9px 0 8px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}

.day-chip:hover {
  border-color: var(--primary-dark);
}

/* 选中：主色实底 + 白字（5.17:1）。不用 --primary-soft 填底 ——
   「--primary 字 + --primary-soft 底」只有 4.44:1，差一点不达 AA */
.day-chip.is-on {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

.dc-week {
  font-size: 12px;
  opacity: 0.85;
}

.dc-date {
  font-size: 14.5px;
  font-weight: 600;
}

/* 强调色跟父级走：选中时是白字，未选中时是 --text-3。
   直接写死 --text-3 会在选中态上变成浅灰压主色，几乎看不见 */
.dc-count {
  font-size: 11.5px;
  color: var(--text-3);
}

.day-chip.is-on .dc-count {
  color: inherit;
  opacity: 0.9;
}

/* ---------------- 主体 ---------------- */

.sched-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--space-card);
  align-items: start;
}

/* ⚠️ 媒体查询必须排在 .sched-body 之后：同特异性下后者胜，
   @media 本身不加权重（硬约定 #26）。写到前面等于没写 */
@media (max-width: 1024px) {
  .sched-body {
    grid-template-columns: minmax(0, 1fr);
  }
}

.card {
  padding: var(--space-inner);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
}

.card-head {
  margin-bottom: 16px;
}

/* 卡片小标题走 sans + 600，不用 h1–h6 的 serif（那是页面级标题的字体） */
.card-title {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
}

.card-sub {
  margin: 6px 0 0;
  color: var(--text-3);
  font-size: 12.5px;
}

/* 数字统一 mono，避免「共 3 段」在字体切换时宽度跳动 */
.card-sub b,
.slot-meta b {
  font-weight: 600;
  color: var(--text-2);
}

.empty {
  margin: 0;
  padding: 28px 0;
  color: var(--text-3);
  font-size: 13.5px;
  line-height: 1.8;
  text-align: center;
}

/* ---------------- 时段行 ---------------- */

.slot-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.slot-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  border-radius: var(--radius-ctrl);
  /* 落在 L1 白卡之内，所以用 --panel-bg 分区底色；文字只能用 --text-2 ——
     --text-3 落 --panel-bg 只有 4.27:1，不到 AA */
  background: var(--panel-bg);
}

.slot-time {
  min-width: 104px;
  color: var(--text-1);
  font-size: 14px;
  font-weight: 600;
}

.slot-meta {
  color: var(--text-2);
  font-size: 13px;
}

/* 有人约过时把「已约」加重：这是医生唯一需要立刻注意到的数字 */
.slot-meta.is-has b {
  color: var(--primary-dark);
}

.slot-left {
  margin-left: auto;
  color: var(--text-2);
  font-size: 12.5px;
}

/* 已满用主色而不是风险色：约满不是异常，是正常状态 */
.slot-left.is-full {
  color: var(--primary-dark);
  font-weight: 600;
}

.row-del {
  flex: none;
  padding: 4px 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.row-del:hover:not(:disabled) {
  border-color: var(--risk-b-ink);
  color: var(--risk-b-ink);
}

.row-del:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------------- 表单 ---------------- */

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.field {
  display: block;
  margin-bottom: 14px;
}

.field-label {
  display: block;
  margin-bottom: 6px;
  color: var(--text-2);
  font-size: 12.5px;
}

.input {
  width: 100%;
  height: 38px;
  padding: 0 10px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-1);
  font-family: inherit;
  font-size: 14px;
  transition: border-color var(--dur) var(--ease);
}

.input:focus {
  border-color: var(--primary-dark);
  outline: none;
}

/* ---------------- 按钮三件套 ---------------- */

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 38px;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: var(--radius-ctrl);
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--dur) var(--ease), border-color var(--dur) var(--ease),
    opacity var(--dur) var(--ease);
}

.btn-primary {
  background: var(--primary);
  color: var(--on-primary);
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark);
}

.btn-ghost {
  border-color: var(--card-border);
  background: transparent;
  color: var(--text-1);
}

.btn-ghost:hover {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

.btn-block {
  width: 100%;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tips {
  margin: 14px 0 0;
  color: var(--text-3);
  font-size: 12px;
  line-height: 1.75;
}
</style>
