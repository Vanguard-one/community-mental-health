<template>
  <div class="page-container">
    <PageHeader title="预约医生" :subtitle="subtitle">
      <template #extra>
        <button type="button" class="btn btn-ghost" @click="backToDoctors">返回医生列表</button>
      </template>
    </PageHeader>

    <!--
      步骤条：成功页（step === 3）不显示。
      做完的事不需要再被「步骤 3/3」提醒一遍，而成功页要的是干净的结果。
    -->
    <ol v-if="step < 3" class="steps">
      <li class="st" :class="{ 'is-on': step >= 1 }">
        <span class="st-idx mono">1</span><span class="st-text">选时间</span>
      </li>
      <li class="st-line" aria-hidden="true" />
      <li class="st" :class="{ 'is-on': step >= 2 }">
        <span class="st-idx mono">2</span><span class="st-text">填信息</span>
      </li>
      <li class="st-line" aria-hidden="true" />
      <li class="st" :class="{ 'is-on': step >= 3 }">
        <span class="st-idx mono">3</span><span class="st-text">完成</span>
      </li>
    </ol>

    <p v-if="loading" class="hint">加载中…</p>

    <div v-else-if="!doctor" class="empty-card">
      <p class="empty-title">找不到这位医生</p>
      <p class="empty-desc">医生可能已下架或链接有误，回到列表重新选择即可。</p>
      <button type="button" class="btn btn-ghost" @click="backToDoctors">返回医生列表</button>
    </div>

    <div v-else class="bk-layout">
      <!-- 左：医生摘要。三步全程常驻 —— 用户随时能确认「我在约谁」 -->
      <aside class="card doc-card">
        <RoleAvatar class="dc-avatar" role="doctor" :size="52" />
        <h2 class="dc-name">{{ doctor.doctorName }}</h2>
        <p class="dc-title">{{ [doctor.title, doctor.department].filter(Boolean).join(' · ') || '医生' }}</p>
        <p class="dc-meta">
          <span>★ <span class="mono">{{ ratingText }}</span></span>
          <span class="dc-sep" aria-hidden="true">·</span>
          <span>接诊 <span class="mono">{{ doctor.currentLoad ?? 0 }}</span> 人次</span>
        </p>
        <p v-if="pickedSlot && step < 3" class="dc-picked">
          <span class="dp-label">已选</span>
          <span class="dp-val mono">{{ pickedDateText }} {{ pickedSlot.timeText }}</span>
        </p>
      </aside>

      <!-- 右：主面板 -->
      <section class="card bk-main">
        <!-- ---------- 第 1 步：选时间 ---------- -->
        <template v-if="step === 1">
          <h2 class="sec-title">选择就诊时间</h2>
          <p class="sec-sub">只能预约未来 {{ DAYS_AHEAD }} 天内已放出的时段</p>

          <div class="day-bar">
            <button
              v-for="d in days"
              :key="d.dateText"
              type="button"
              class="day-chip"
              :class="{ 'is-on': d.dateText === pickedDate, 'is-off': !hasOpenSlot(d) }"
              @click="pickDay(d.dateText)"
            >
              <span class="dc-week">{{ d.weekday }}</span>
              <span class="dc-date mono">{{ d.shortText }}</span>
              <span class="dc-n mono">{{ hasOpenSlot(d) ? `${openCount(d)} 个号` : '无号' }}</span>
            </button>
          </div>

          <template v-if="activeDay && activeDay.slots.length">
            <div class="slot-grid">
              <button
                v-for="s in activeDay.slots"
                :key="s.id"
                type="button"
                class="slot"
                :class="{ 'is-on': pickedSlot?.id === s.id, 'is-full': s.full }"
                :disabled="s.full"
                @click="pickSlot(s)"
              >
                <span class="slot-time mono">{{ s.timeText }}</span>
                <span class="slot-left">{{ s.full ? '已满' : `剩 ${s.remaining} 个` }}</span>
              </button>
            </div>
          </template>

          <div v-else class="inline-empty">
            <p>{{ activeDay ? '这一天医生没有放号。' : '暂无号源。' }}</p>
            <p class="ie-sub">可以换个日期，或先和医生文字沟通一下。</p>
            <button type="button" class="btn btn-ghost" @click="goChat">先聊聊</button>
          </div>

          <footer class="bk-foot">
            <span class="foot-hint">{{ pickedSlot ? '' : '请先选择一个时段' }}</span>
            <button type="button" class="btn btn-primary" :disabled="!pickedSlot" @click="step = 2">
              下一步
            </button>
          </footer>
        </template>

        <!-- ---------- 第 2 步：填信息 ---------- -->
        <template v-else-if="step === 2">
          <h2 class="sec-title">填写就诊信息</h2>
          <p class="sec-sub">这些信息只给接诊医生看，用于当天沟通与核对</p>

          <label class="field">
            <span class="field-label">就诊人姓名</span>
            <input v-model.trim="form.patientName" type="text" maxlength="32" class="input" placeholder="请填写真实姓名" />
          </label>

          <label class="field">
            <span class="field-label">联系电话</span>
            <input v-model.trim="form.patientPhone" type="tel" maxlength="11" class="input" placeholder="11 位手机号" />
            <span v-if="phoneError" class="field-err">{{ phoneError }}</span>
          </label>

          <label class="field">
            <span class="field-label">想和医生聊什么（可不填）</span>
            <textarea
              v-model.trim="form.chiefComplaint"
              rows="3"
              maxlength="255"
              class="input textarea"
              placeholder="例如：最近两周入睡困难，白天注意力下降"
            />
          </label>

          <!--
            知情同意：心理健康场景的合规底线，不是可选项。
            未勾选时提交按钮 disabled —— 让用户在点之前就知道「还差这一步」，
            比点了之后弹一句「请先同意」体验更好，也不会被当成系统故障。
          -->
          <label class="consent">
            <input v-model="form.agree" type="checkbox" class="consent-box" />
            <span class="consent-text">
              我已阅读并同意《预约知情同意书》，理解本次为心理健康咨询预约，
              如遇紧急情况请立即拨打 120 或前往就近医院急诊。
            </span>
          </label>

          <footer class="bk-foot">
            <button type="button" class="btn btn-ghost" @click="step = 1">上一步</button>
            <button type="button" class="btn btn-primary" :disabled="!canSubmit" @click="submit">
              {{ submitting ? '提交中…' : '提交预约' }}
            </button>
          </footer>
        </template>

        <!-- ---------- 第 3 步：成功 ---------- -->
        <template v-else>
          <div class="done">
            <span class="done-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M5 12.5 10 17.5 19 7.5" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <h2 class="done-title">预约成功</h2>
            <p class="done-sub">预约信息已发送到「通知」，医生也会同步收到</p>

            <dl v-if="created" class="done-list">
              <div class="dl-row">
                <dt>预约单号</dt>
                <dd class="mono">{{ created.appointmentNo }}</dd>
              </div>
              <div class="dl-row">
                <dt>就诊时间</dt>
                <dd class="mono">{{ created.whenText }}</dd>
              </div>
              <div class="dl-row">
                <dt>医生</dt>
                <dd>{{ created.doctorDisplayName }}<template v-if="created.department"> · {{ created.department }}</template></dd>
              </div>
              <div class="dl-row">
                <dt>就诊人</dt>
                <dd>{{ created.patientName }}</dd>
              </div>
            </dl>

            <p class="done-tip">
              请提前 5 分钟准备好。如需取消，在「我的预约」中操作（就诊开始前可自助取消）。
            </p>

            <div class="done-ops">
              <button type="button" class="btn btn-primary" @click="router.push('/appointments')">查看我的预约</button>
              <button type="button" class="btn btn-ghost" @click="backToDoctors">继续找医生</button>
            </div>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { listDoctorCatalog } from '@/api/chat'
import { getDoctorSchedule } from '@/api/schedule'
import { createAppointment } from '@/api/appointment'
import { getMyProfile } from '@/api/profile'
import type { AppointmentItem, ChatPeer, ScheduleDay, ScheduleSlot } from '@/types'

/**
 * 预约三步页（`/doctors/:doctorId/book`）。
 *
 * <h3>为什么医生信息复用了 `/chat/doctor-catalog`</h3>
 * 那里已经返回全部可约医生的姓名/职称/科室/评分，本页只需要其中一个。
 * 为「查一位医生」新增 `GET /doctor/{id}` 意味着再写一份投影与再维护一次字段，
 * 而这份目录本来就是本页的上一跳（用户是从列表点进来的），数据必定在缓存范围内。
 *
 * <h3>为什么成功页不重新拉接口</h3>
 * `POST /appointment` 的响应就是完整单子（含单号、医生名、时段），
 * 拿它渲染成功页即可。再发一次 `GET /appointment/my` 既慢又可能拿到
 * 与刚提交不一样的排序结果。
 */
const route = useRoute()
const router = useRouter()

const DAYS_AHEAD = 7
/** 知情同意书版本。改动文案时必须同步改这里，否则留痕记录会对不上实际文本 */
const CONSENT_VERSION = 'v1'

const doctorId = Number(route.params.doctorId)

const loading = ref(true)
const submitting = ref(false)
const step = ref(1)

const doctor = ref<ChatPeer | null>(null)
const days = ref<ScheduleDay[]>([])
const pickedDate = ref('')
const pickedSlot = ref<ScheduleSlot | null>(null)
const created = ref<AppointmentItem | null>(null)

const form = reactive({
  patientName: '',
  patientPhone: '',
  chiefComplaint: '',
  agree: false,
})

const subtitle = computed(() =>
  doctor.value
    ? `为 ${doctor.value.doctorName} 预约一个时间段，提交后双方都会收到站内通知`
    : '选择时间段并填写就诊信息',
)

const ratingText = computed(() =>
  doctor.value?.rating == null ? '—' : doctor.value.rating.toFixed(1),
)

const activeDay = computed(() => days.value.find((d) => d.dateText === pickedDate.value))

/** 左栏「已选」那一行要显示日期，取的是当前选中日而不是 slot 自己（slot 不带日期） */
const pickedDateText = computed(() => {
  const d = activeDay.value
  return d ? `${d.shortText} ${d.weekday}` : ''
})

const phoneError = computed(() => {
  const v = form.patientPhone
  if (!v) return ''
  return /^1[3-9]\d{9}$/.test(v) ? '' : '请填写 11 位手机号'
})

const canSubmit = computed(
  () =>
    !!form.patientName &&
    /^1[3-9]\d{9}$/.test(form.patientPhone) &&
    form.agree &&
    !!pickedSlot.value &&
    !submitting.value,
)

function hasOpenSlot(d: ScheduleDay): boolean {
  return d.slots.some((s) => !s.full)
}

function openCount(d: ScheduleDay): number {
  return d.slots.reduce((n, s) => n + s.remaining, 0)
}

function pickDay(date: string) {
  pickedDate.value = date
  // 换日期必须清掉已选时段：不清的话「已选 10-25 09:00」会和左边日期条
  // 指向的 10-26 同时显示，提交时用的还是旧 slot —— 用户会收到一个自己没选的日期。
  pickedSlot.value = null
}

function pickSlot(s: ScheduleSlot) {
  if (s.full) return
  pickedSlot.value = s
}

async function loadSchedule() {
  try {
    days.value = await getDoctorSchedule(doctorId, DAYS_AHEAD)
    // 默认落在第一个「还有号」的日期：让用户一进来就看到可选时段，
    // 而不是面对一排日期先做一次无意义的点击。
    const firstOpen = days.value.find((d) => hasOpenSlot(d))
    pickedDate.value = firstOpen?.dateText ?? days.value[0]?.dateText ?? ''
    pickedSlot.value = null
  } catch {
    days.value = []
  }
}

async function load() {
  // 路径参数拼错（如 /doctors/abc/book）时不要发出 `/schedule/doctor/NaN`：
  // 那会变成一次必然失败的请求 + 一条看不懂的错误提示。
  // 直接走「找不到这位医生」的空态，那个空态里有回到列表的出口。
  if (!Number.isFinite(doctorId)) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const [catalog] = await Promise.all([
      listDoctorCatalog(),
      loadSchedule(),
      // 预填姓名与手机号：这两个字段用户每次都要填一遍，而资料里本来就有。
      // 拿不到就算了 —— 它是体验优化，不是前置条件，不能因为 401/网络失败挡住预约。
      getMyProfile()
        .then((p) => {
          form.patientName = form.patientName || p.realName || ''
          form.patientPhone = form.patientPhone || p.phone || ''
        })
        .catch(() => undefined),
    ])
    for (const g of catalog) {
      const hit = g.doctors.find((d) => d.doctorId === doctorId)
      if (hit) {
        doctor.value = hit
        break
      }
    }
  } catch {
    doctor.value = null
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value || !pickedSlot.value) return
  submitting.value = true
  try {
    created.value = await createAppointment({
      scheduleId: pickedSlot.value.id,
      patientName: form.patientName,
      patientPhone: form.patientPhone,
      chiefComplaint: form.chiefComplaint || undefined,
      consentVersion: CONSENT_VERSION,
    })
    step.value = 3
  } catch {
    // 失败提示由拦截器统一给出（「该时段已约满」/「你已预约过该时段」）。
    // 这里要额外做一件事：重新拉号源并退回第 1 步 —— 失败最常见的原因就是
    // 刚才那几分钟里号被别人占了，如果不刷新，用户会对着一个已经不存在的时段反复点提交。
    await loadSchedule()
    step.value = 1
  } finally {
    submitting.value = false
  }
}

function backToDoctors() {
  router.push('/doctors')
}

function goChat() {
  router.push(`/messages/${doctorId}`)
}

onMounted(load)
</script>

<style scoped>
.hint {
  margin: var(--space-block) 0;
  color: var(--text-3);
  font-size: 14px;
}

/* ---------- 步骤条 ---------- */

.steps {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 var(--space-card);
  padding: 0;
  list-style: none;
}

.st {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-3);
  font-size: 13px;
}

/* 未到达的步骤也保持可读（--text-3 落页面底只有 3.60:1，
   但它是「非必要信息」，属于装饰性弱化，不承担阅读任务） */
.st.is-on {
  color: var(--text-1);
  font-weight: 600;
}

.st-idx {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--card-border);
  border-radius: 50%;
  background: var(--card-bg);
  font-size: 12px;
}

.st.is-on .st-idx {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

.st-line {
  flex: 1;
  max-width: 56px;
  height: 1px;
  background: var(--card-border);
}

/* ---------- 布局 ---------- */

.bk-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--space-card);
  align-items: start;
}

/* 媒体查询排在基础规则之后（硬约定 #26） */
@media (max-width: 900px) {
  .bk-layout {
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

/* ---------- 医生摘要 ---------- */

.doc-card {
  text-align: center;
}

.dc-avatar {
  margin: 0 auto 10px;
}

.dc-name {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-serif);
  font-size: 18px;
}

.dc-title {
  margin: 4px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.dc-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 10px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.dc-sep {
  color: var(--text-3);
}

/* 已选时间的回显：整块浅主色底，让「即将提交的是这个时段」一眼可确认 */
.dc-picked {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin: 16px 0 0;
  padding: 10px 12px;
  border-radius: var(--radius-ctrl);
  background: var(--primary-soft);
  text-align: left;
}

.dp-label {
  color: var(--primary-dark);
  font-size: 11.5px;
  font-weight: 600;
}

/* 文字必须 --primary-dark：--primary 压 --primary-soft 只有 4.44:1 */
.dp-val {
  color: var(--primary-dark);
  font-size: 13.5px;
}

/* ---------- 主面板 ---------- */

.bk-main {
  min-width: 0;
}

.sec-title {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
}

.sec-sub {
  margin: 6px 0 16px;
  color: var(--text-2);
  font-size: 12.5px;
}

/* ---------- 日期条 ---------- */

.day-bar {
  display: flex;
  gap: 8px;
  padding-bottom: 6px;
  overflow-x: auto;
  scrollbar-width: thin;
}

.day-chip {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  width: 70px;
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

/* 选中：主色实底 + 白字 5.17:1 */
.day-chip.is-on {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

/* 无号的日期只弱化不隐藏：隐藏会让日期条断档，用户会以为加载失败。
   仍可点击（点进去会看到「这天没有放号」的说明 + 换个日期的指引）。 */
.day-chip.is-off:not(.is-on) {
  opacity: 0.55;
}

.dc-week {
  font-size: 12px;
  opacity: 0.85;
}

.dc-date {
  font-size: 14.5px;
  font-weight: 600;
}

.dc-n {
  color: var(--text-3);
  font-size: 11px;
}

.day-chip.is-on .dc-n {
  color: inherit;
  opacity: 0.9;
}

/* ---------- 时段网格 ---------- */

.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.slot {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 11px 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-1);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.slot:hover:not(:disabled):not(.is-on) {
  border-color: var(--primary-dark);
}

/* 选中：实底主色。剩余数跟着变 inherit，否则 --text-3 压在主色上完全看不见 */
.slot.is-on {
  border-color: transparent;
  background: var(--primary);
  color: var(--on-primary);
}

.slot.is-full {
  /* 约满用「低饱和 + 不可点」表达，不用风险色 —— 约满不是异常 */
  border-style: dashed;
  background: var(--panel-bg);
  color: var(--text-3);
  cursor: not-allowed;
}

.slot-time {
  font-size: 14px;
  font-weight: 600;
}

.slot-left {
  color: var(--text-2);
  font-size: 11.5px;
}

.slot.is-on .slot-left {
  color: inherit;
  opacity: 0.9;
}

/* ---------- 空态 ---------- */

.inline-empty {
  margin-top: 16px;
  padding: 28px 20px;
  border-radius: var(--radius-ctrl);
  background: var(--panel-bg);
  text-align: center;
}

.inline-empty p {
  margin: 0;
  color: var(--text-2);
  font-size: 13.5px;
}

.ie-sub {
  margin: 6px 0 14px !important;
  font-size: 12.5px !important;
}

/* ---------- 表单 ---------- */

.field {
  display: block;
  margin-bottom: 16px;
}

.field-label {
  display: block;
  margin-bottom: 6px;
  color: var(--text-2);
  font-size: 12.5px;
}

.input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
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

.textarea {
  height: auto;
  padding: 10px 12px;
  line-height: 1.6;
  resize: vertical;
}

.field-err {
  display: block;
  margin-top: 6px;
  color: var(--risk-b-ink);
  font-size: 12px;
}

/* 知情同意：勾选框用 accent-color 跟主色走，避免浏览器默认蓝与全站不一致 */
.consent {
  display: flex;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-ctrl);
  background: var(--panel-bg);
  cursor: pointer;
}

.consent-box {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 2px;
  accent-color: var(--primary);
}

.consent-text {
  /* 落 --panel-bg，必须用 --text-1/2：--text-3 在这里只有 4.27:1 */
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.75;
}

/* ---------- 底部操作 ---------- */

.bk-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--card-border);
}

.foot-hint {
  flex: 1;
  color: var(--text-3);
  font-size: 12.5px;
}

/* 按钮不参与收缩：居中/靠右时一旦和 flex-shrink 同时发生，先被压扁的是文字间距 */
.bk-foot .btn {
  flex: none;
}

.bk-foot .foot-hint + .btn {
  margin-left: auto;
}

/* ---------- 按钮三件套 ---------- */

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
    color var(--dur) var(--ease), opacity var(--dur) var(--ease);
}

.btn-primary {
  background: var(--primary);
  color: var(--on-primary);
}

/* 悬停加深而不是变浅：白字压在更浅的主色上会掉到 4:1 以下 */
.btn-primary:hover:not(:disabled) {
  background: var(--primary-dark);
}

.btn-ghost {
  border-color: var(--card-border);
  background: transparent;
  color: var(--text-1);
}

.btn-ghost:hover:not(:disabled) {
  border-color: var(--primary-dark);
  color: var(--primary-dark);
}

.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* ---------- 成功页 ---------- */

.done {
  text-align: center;
}

.done-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  margin-bottom: 14px;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.done-mark svg {
  width: 28px;
  height: 28px;
}

.done-title {
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-serif);
  font-size: 20px;
}

.done-sub {
  margin: 8px 0 22px;
  color: var(--text-2);
  font-size: 13px;
}

.done-list {
  margin: 0 auto;
  max-width: 380px;
  padding: 4px 16px;
  border-radius: var(--radius-ctrl);
  background: var(--panel-bg);
  text-align: left;
}

.dl-row {
  display: flex;
  gap: 12px;
  padding: 10px 0;
}

/* 行间分隔线用 card-border 而不是 panel-bg —— 后者的值等于容器底色，线会消失 */
.dl-row + .dl-row {
  border-top: 1px solid var(--card-border);
}

.dl-row dt {
  flex: none;
  width: 76px;
  color: var(--text-2);
  font-size: 12.5px;
}

.dl-row dd {
  margin: 0;
  color: var(--text-1);
  font-size: 13.5px;
}

.done-tip {
  margin: 18px auto 0;
  max-width: 380px;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.75;
}

.done-ops {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 20px;
}

/* ---------- 空态卡 ---------- */

.empty-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 44px 24px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
  text-align: center;
}

.empty-title {
  margin: 0 0 6px;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
}

.empty-desc {
  margin: 0 0 16px;
  color: var(--text-2);
  font-size: 13px;
}
</style>
