<template>
  <div class="page-container">
    <PageHeader title="我的预约" subtitle="你提交过的全部预约，按就诊时间排序">
      <template #extra>
        <button type="button" class="btn btn-ghost" :disabled="loading" @click="load">刷新</button>
      </template>
    </PageHeader>

    <div class="split">
      <AppointmentSubNav
        :departments="catalog"
        :total="totalDoctors"
        :active-key="MINE_KEY"
        @select="goDoctors"
        @mine="load"
      />

      <div class="split-main">
        <p v-if="loading && !items.length" class="hint">加载中…</p>

        <template v-else-if="items.length">
          <section v-for="g in groups" :key="g.key" class="group">
            <h2 class="group-title">
              {{ g.label }}<span class="g-count mono">{{ g.list.length }}</span>
            </h2>

            <div class="appt-list">
              <article v-for="a in g.list" :key="a.id" class="appt-row" :class="{ 'is-off': g.key !== 'BOOKED' }">
                <!-- 时间块固定宽度：三组的日期长短一致，固定列宽才能让右侧信息对齐 -->
                <div class="ar-when">
                  <span class="ar-date mono">{{ a.shortDate }}</span>
                  <span class="ar-week">{{ a.weekday }}</span>
                  <span class="ar-time mono">{{ a.timeText }}</span>
                </div>

                <div class="ar-main">
                  <h3 class="ar-doctor">
                    {{ a.doctorDisplayName }}
                    <span v-if="a.department" class="ar-dept">{{ a.department }}</span>
                  </h3>
                  <p class="ar-meta">
                    就诊人 {{ a.patientName }}
                    <span class="ar-sep" aria-hidden="true">·</span>
                    单号 <span class="mono">{{ a.appointmentNo }}</span>
                  </p>
                  <p v-if="a.chiefComplaint" class="ar-note">{{ a.chiefComplaint }}</p>
                </div>

                <div class="ar-ops">
                  <!--
                    状态用「描边 + 墨色文字」而不是实底：实底会让这一列比卡片还重，
                    但这里的主信息是时间与医生，状态只是补充。
                    墨色文字保证 AA，实色留给状态圆点。
                  -->
                  <span class="ar-status" :class="`st-${a.statusKey.toLowerCase()}`">
                    {{ a.statusText }}
                  </span>

                  <!--
                    聊天按钮：三组都显示，不只在「待就诊」上显示。
                    目的地是**那位医生**，不是这张单子 —— 已过期想问复诊、
                    已取消想问能不能改约，都是真实需求；在某一行藏掉它，
                    只会让人以为「这条单子不能联系医生」，而其实从医生列表点进去照样能聊。
                    两个入口通向同一个目的地，却在不同页面给出不同结论，是最难解释的那种不一致。

                    aria-label 带上医生名：一屏里会有多个同名按钮，读屏只听得到「聊天」。
                  -->
                  <button
                    type="button"
                    class="ar-chat"
                    :aria-label="`与 ${a.doctorDisplayName} 聊天`"
                    @click="goChat(a)"
                  >
                    <svg class="ar-chat-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="M21 11.6a8.5 8.5 0 0 1-8.5 8.5 8.4 8.4 0 0 1-3.8-.9L3 21l1.8-5.6a8.4 8.4 0 0 1-.8-3.8 8.5 8.5 0 0 1 8.5-8.5h.5a8.4 8.4 0 0 1 8 8v.5Z"
                        stroke="currentColor"
                        stroke-width="1.7"
                        stroke-linejoin="round"
                      />
                    </svg>
                    聊天
                  </button>

                  <button
                    v-if="a.cancellable"
                    type="button"
                    class="ar-cancel"
                    :disabled="cancellingId === a.id"
                    @click="openCancel(a)"
                  >
                    {{ cancellingId === a.id ? '取消中…' : '取消预约' }}
                  </button>
                </div>
              </article>
            </div>
          </section>
        </template>

        <div v-else class="empty-card">
          <span class="empty-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
              <rect x="3.5" y="5.5" width="17" height="15" rx="3" stroke="currentColor" stroke-width="1.7" />
              <path d="M8 3.5v4M16 3.5v4M3.5 10.5h17" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            </svg>
          </span>
          <p class="empty-title">还没有预约</p>
          <p class="empty-desc">去医生列表选一位，挑个合适的时间段就能约上。</p>
          <button type="button" class="btn btn-primary" @click="goDoctors('')">去找医生</button>
        </div>
      </div>
    </div>

    <!--
      取消确认：自己画的遮罩层，不引 antd 的 Modal。
      页面里只有一个弹层，为它引入一个组件（构建体积 + 全局样式覆盖）不划算。
    -->
    <div v-if="cancelTarget" class="overlay" @click.self="closeCancel">
      <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="cancel-title">
        <h2 id="cancel-title" class="dg-title">取消这次预约？</h2>
        <p class="dg-sub">
          {{ cancelTarget.whenText }} · {{ cancelTarget.doctorDisplayName }}<br />
          取消后号源会重新放出，如需再次就诊要重新预约。
        </p>

        <label class="field">
          <span class="field-label">取消原因（可不填）</span>
          <input
            v-model.trim="cancelReason"
            type="text"
            maxlength="128"
            class="input"
            placeholder="例如：临时有事，改天再约"
          />
        </label>

        <div class="dg-ops">
          <button type="button" class="btn btn-ghost" :disabled="!!cancellingId" @click="closeCancel">再想想</button>
          <button type="button" class="btn btn-danger" :disabled="!!cancellingId" @click="confirmCancel">
            {{ cancellingId ? '取消中…' : '确认取消' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppointmentSubNav from '@/components/AppointmentSubNav.vue'
import PageHeader from '@/components/PageHeader.vue'
import { listDoctorCatalog } from '@/api/chat'
import { cancelAppointment, getMyAppointments } from '@/api/appointment'
import type { AppointmentItem, DepartmentGroup } from '@/types'

/**
 * 我的预约（`/appointments`）。
 *
 * <h3>为什么分组键用后端的 statusKey 而不是自己判断</h3>
 * 「已过期」= 状态仍是 BOOKED 但开始时间已过，是**算出来的**而非库里的状态。
 * 若前端自己拿 `status + visitDate` 判一次，就存在两份规则；
 * 两处一旦不一致，会出现「列表显示已过期、详情显示待就诊」这种自相矛盾。
 *
 * <h3>为什么取消后整页重取，而不是本地改一行</h3>
 * `POST /appointment/{id}/cancel` 只返回成功与否，没有回传更新后的单子；
 * 而 `statusText / statusKey / cancellable` 三个派生字段都由后端算。
 * 本地改 `status = 'CANCELLED'` 会让这三个字段停留在旧值（按钮还在、状态还是「待就诊」），
 * 刷新一下才对 —— 属于最难查的那类 bug（不报错、刷新就好）。
 * 一次列表请求换一个正确的界面状态，这笔账很划算。
 */
const router = useRouter()

/** 与 AppointmentSubNav 里约定的哨兵值一致 */
const MINE_KEY = 'appointments'

const loading = ref(false)
const items = ref<AppointmentItem[]>([])
const catalog = ref<DepartmentGroup[]>([])

const cancelTarget = ref<AppointmentItem | null>(null)
const cancelReason = ref('')
const cancellingId = ref<number | null>(null)

const totalDoctors = computed(() => catalog.value.reduce((n, g) => n + g.doctors.length, 0))

/**
 * 三组固定顺序：待就诊 → 已过期 → 已取消。
 * 空组不渲染 —— 一条预约都没有的人不该看到三个空标题。
 */
const groups = computed(() => {
  const order: Array<{ key: 'BOOKED' | 'EXPIRED' | 'CANCELLED'; label: string }> = [
    { key: 'BOOKED', label: '待就诊' },
    { key: 'EXPIRED', label: '已过期' },
    { key: 'CANCELLED', label: '已取消' },
  ]
  return order
    .map((g) => ({ ...g, list: items.value.filter((a) => a.statusKey === g.key) }))
    .filter((g) => g.list.length > 0)
})

async function load() {
  loading.value = true
  try {
    // 一级菜单要显示科室与人数，两个请求互不依赖，一起发。
    // 注意不要把这个变量命名成 `groups` —— 会遮蔽上面同名的 computed。
    const [list, depts] = await Promise.all([getMyAppointments(), listDoctorCatalog()])
    items.value = list
    catalog.value = depts
  } catch {
    // 已由拦截器提示
  } finally {
    loading.value = false
  }
}

function goDoctors(dept: string) {
  router.push({ path: '/doctors', query: dept ? { dept } : {} })
}

/**
 * 去和这位医生聊天 —— 复用居民端唯一的聊天页 `/messages/:doctorId`。
 *
 * <h3>为什么跳 doctorId 而不是 sessionId</h3>
 * 这里根本不知道会话是否存在（可能与这位医生从没聊过，只约了个时间）。
 * 而聊天页进页面时调的 `POST /chat/sessions` 是幂等的：有进行中的会话就复用、
 * 没有就建。所以**不需要**先查一次再决定跳哪 —— 那样只多一次往返，
 * 还得处理「查到之后会话已结束」的竞态。
 *
 * <h3>为什么不按状态分支</h3>
 * 已过期、已取消的单子也跳同一个目的地。按钮通向的是「那位医生」，
 * 不是「这张单子」；按状态藏按钮会让人以为联系不上了。
 */
function goChat(a: AppointmentItem) {
  router.push(`/messages/${a.doctorId}`)
}

function openCancel(a: AppointmentItem) {
  cancelTarget.value = a
  cancelReason.value = ''
}

function closeCancel() {
  if (cancellingId.value) return
  cancelTarget.value = null
  cancelReason.value = ''
}

async function confirmCancel() {
  const target = cancelTarget.value
  if (!target) return
  cancellingId.value = target.id
  try {
    await cancelAppointment(target.id, cancelReason.value || undefined)
    cancelTarget.value = null
    cancelReason.value = ''
    // 重新拉列表，让 statusKey / statusText / cancellable 三个派生字段一起刷新
    await load()
  } catch {
    // 已由拦截器提示（可能是「就诊时段已开始，无法自助取消」）
    cancelTarget.value = null
  } finally {
    cancellingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.hint {
  margin: var(--space-block) 0;
  color: var(--text-3);
  font-size: 14px;
}

.split {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: var(--space-card);
  align-items: start;
}

.split-main {
  min-width: 0;
}

@media (max-width: 900px) {
  .split {
    grid-template-columns: minmax(0, 1fr);
  }
}

/* ---------- 分组 ---------- */

.group + .group {
  margin-top: var(--space-block);
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  color: var(--text-2);
  font-family: var(--font-sans);
  font-size: 13.5px;
  font-weight: 600;
}

.g-count {
  color: var(--text-3);
  font-size: 12px;
  font-weight: 400;
}

.appt-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ---------- 预约行 ---------- */

.appt-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: var(--shadow-card);
}

/* 已过期/已取消的整行降权：它们仍然可读，但不再抢占注意力 */
.appt-row.is-off {
  box-shadow: none;
  opacity: 0.72;
}

.ar-when {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  width: 82px;
  padding: 8px 0;
  border-radius: var(--radius-ctrl);
  background: var(--panel-bg);
}

.ar-date {
  color: var(--text-1);
  font-size: 15px;
  font-weight: 600;
}

.ar-week {
  color: var(--text-2);
  font-size: 11.5px;
}

.ar-time {
  margin-top: 3px;
  color: var(--text-2);
  font-size: 11.5px;
}

.ar-main {
  flex: 1;
  min-width: 0;
}

.ar-doctor {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 15px;
  font-weight: 600;
}

.ar-dept {
  color: var(--text-3);
  font-size: 12px;
  font-weight: 400;
}

.ar-meta {
  margin: 5px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.ar-sep {
  color: var(--text-3);
}

/* 主诉可能很长：单行省略，不在列表里展开 */
.ar-note {
  margin: 5px 0 0;
  overflow: hidden;
  color: var(--text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ar-ops {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}

/* 状态：描边 + 墨色文字。三个状态用同一个形状，只换色相 —— 
   形状不同会让这一列在三个分组里看起来不是同一个东西 */
.ar-status {
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  font-size: 11.5px;
}

.st-booked {
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.st-expired {
  background: var(--panel-bg);
  color: var(--text-2);
}

/* 已取消不需要警告色：它是用户自己做的决定，不是异常 */
.st-cancelled {
  background: var(--panel-bg);
  color: var(--text-3);
}

.ar-cancel {
  padding: 5px 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), color var(--dur) var(--ease);
}

.ar-cancel:hover:not(:disabled) {
  border-color: var(--risk-b-ink);
  color: var(--risk-b-ink);
}

.ar-cancel:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/*
 * 聊天：软底 + --primary-dark 字（约 5.5:1）。
 * 不用主色实底 —— 一屏好几行都顶着实心按钮时，真正要紧的「时间与医生」会被压下去。
 * 悬停切成实底（--on-primary 压 --primary 是 5.17:1）：这里整行唯一鼓励点的动作，给一点明确反馈。
 */
.ar-chat {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-ctrl);
  background: var(--primary-soft);
  color: var(--primary-dark);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}

.ar-chat:hover {
  background: var(--primary);
  color: var(--on-primary);
}

.ar-chat-icon {
  flex: none;
  width: 13px;
  height: 13px;
}

/*
 * 降权行（已过期 / 已取消）里的聊天换成中性描边。
 * 原因不是好看：整行带 `opacity: 0.72`，主色软底会跟着一起褪成一片，
 * 块面糊住之后反而不像按钮；中性描边过这层透明度仍然看得出来是可点的，
 * 且与同行的其它文字落在同一档对比度上。
 */
.appt-row.is-off .ar-chat {
  border-color: var(--card-border);
  background: transparent;
  color: var(--text-2);
}

.appt-row.is-off .ar-chat:hover {
  border-color: var(--text-2);
  color: var(--text-1);
}

@media (max-width: 640px) {
  .appt-row {
    flex-wrap: wrap;
    gap: 12px;
  }

  .ar-main {
    flex: 1 1 calc(100% - 98px);
  }

  .ar-ops {
    width: 100%;
    justify-content: flex-end;
  }
}

/* ---------- 空态 ---------- */

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

.empty-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  margin-bottom: 14px;
  border-radius: 50%;
  background: var(--panel-bg);
  color: var(--text-2);
}

.empty-mark svg {
  width: 24px;
  height: 24px;
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
  line-height: 1.7;
}

/* ---------- 弹层 ---------- */

.overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(17, 27, 43, 0.42);
}

.dialog {
  width: 100%;
  max-width: 420px;
  padding: 24px;
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  box-shadow: 0 24px 60px -24px rgba(17, 27, 43, 0.4);
}

.dg-title {
  margin: 0 0 8px;
  color: var(--text-1);
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 600;
}

.dg-sub {
  margin: 0 0 18px;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.8;
}

.dg-ops {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}

/* ---------- 表单 ---------- */

.field {
  display: block;
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
  padding: 0 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-1);
  font-family: inherit;
  font-size: 13.5px;
}

.input:focus {
  border-color: var(--primary-dark);
  outline: none;
}

/* ---------- 按钮三件套 ---------- */

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  height: 38px;
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: var(--radius-ctrl);
  font-family: inherit;
  font-size: 13.5px;
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

/*
 * 危险动作实心按钮：填充用 --risk-*-ink 而不是 --risk-d。
 * 白字压 --risk-d 只有 4.21:1（不达 AA），压 -ink 是 6.23:1（硬约定 #124）。
 */
.btn-danger {
  background: var(--risk-b-ink);
  color: var(--on-primary);
}

.btn-danger:hover:not(:disabled) {
  filter: brightness(0.94);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
