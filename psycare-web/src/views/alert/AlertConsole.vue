<template>
  <div class="console">
    <!-- ============ 左栏：工单列表 ============ -->
    <aside class="list-col surface">
      <header class="list-head">
        <div class="list-title">
          <span class="font-serif">预警工单</span>
          <span class="list-total mono">{{ store.total }}</span>
        </div>
        <a-select
          :value="store.typeFilter || undefined"
          :options="typeOptions"
          placeholder="全部类型"
          allow-clear
          size="small"
          class="type-filter"
          @change="onTypeChange"
        />
      </header>

      <!-- 状态分组：角标数来自 /alert/status-count 聚合 -->
      <nav class="group-tabs">
        <button
          v-for="g in ALERT_GROUPS"
          :key="g.key"
          type="button"
          class="group-tab"
          :class="{ active: store.groupKey === g.key }"
          @click="onGroupChange(g.key)"
        >
          <span>{{ g.label }}</span>
          <span v-if="groupBadge(g.key) !== null" class="mono badge">{{ groupBadge(g.key) }}</span>
        </button>
      </nav>

      <div class="list-body">
        <!-- 加载占位：不用 a-spin，它会插两层 div 打断 flex 链 -->
        <div v-if="store.loading" class="skeleton-wrap">
          <div v-for="i in 4" :key="i" class="skeleton-row" />
        </div>

        <a-empty
          v-else-if="store.records.length === 0"
          class="list-empty"
          description="该分组下暂无工单"
        />

        <template v-else>
          <button
            v-for="row in store.records"
            :key="row.id"
            type="button"
            class="ticket"
            :class="{ active: row.id === store.selectedId, crisis: row.alertType === 'CRISIS' }"
            @click="pick(row.id)"
          >
            <div class="ticket-row">
              <span class="ticket-id mono">#{{ row.id }}</span>
              <AlertStatusChip :status="row.status" />
            </div>
            <div class="ticket-row">
              <span class="type-chip" :class="`t-${row.alertType}`">
                {{ ALERT_TYPE_DESC[row.alertType] }}
              </span>
              <RiskLevelTag :level="row.alertLevel" dot />
            </div>
            <div class="ticket-row ticket-foot">
              <span>居民 #{{ row.residentId }}</span>
              <span class="mono">{{ fmtShort(row.createdAt) }}</span>
            </div>
          </button>
        </template>
      </div>

      <footer v-if="store.total > store.size" class="list-foot">
        <a-pagination
          size="small"
          :current="store.current"
          :page-size="store.size"
          :total="store.total"
          :show-size-changer="false"
          @change="onPageChange"
        />
      </footer>
    </aside>

    <!-- ============ 右栏：详情 ============ -->
    <section class="detail-col">
      <div v-if="!alert" class="detail-empty surface">
        <a-empty :description="store.detailLoading ? '加载中…' : '请从左侧选择一条工单'" />
      </div>

      <template v-else>
        <!-- 头部摘要 -->
        <div class="head surface">
          <div class="head-left">
            <span class="head-id font-serif">
              工单 <span class="mono">#{{ alert.id }}</span>
            </span>
            <AlertStatusChip :status="alert.status" />
            <RiskLevelTag :level="alert.alertLevel" size="large" dot />
          </div>
          <dl class="head-meta">
            <div>
              <dt>触发时间</dt>
              <dd class="mono">{{ fmt(alert.createdAt) }}</dd>
            </div>
            <div>
              <dt>最近更新</dt>
              <dd class="mono">{{ fmt(alert.updatedAt) }}</dd>
            </div>
          </dl>
        </div>

        <!-- ① 触发原因 -->
        <div class="panel surface">
          <h3 class="panel-title font-serif">触发原因</h3>

          <div v-if="alert.alertType === 'CRISIS'" class="crisis-note">
            <strong>危机项阳性 · 已强制置为最高等级</strong>
            <p>该工单绕开常规分级窗口，须优先人工介入核实。全国心理援助热线 <span class="mono">12356</span>。</p>
          </div>

          <dl class="kv">
            <div>
              <dt>预警类型</dt>
              <dd>
                <span class="type-chip" :class="`t-${alert.alertType}`">
                  {{ ALERT_TYPE_DESC[alert.alertType] }}
                </span>
              </dd>
            </div>
            <div>
              <dt>风险等级</dt>
              <dd>
                <RiskLevelTag :level="alert.alertLevel" dot />
                <span class="kv-extra">{{ RISK_LEVEL_DESC[alert.alertLevel] }}</span>
              </dd>
            </div>
            <div>
              <dt>关联居民</dt>
              <dd class="mono">#{{ alert.residentId }}</dd>
            </div>
            <div>
              <dt>关联测评</dt>
              <dd class="mono">{{ alert.assessmentId ? `#${alert.assessmentId}` : '—' }}</dd>
            </div>
          </dl>

          <p class="rule-text">
            <span class="rule-label">触发规则</span>
            {{ RULE_TEXT[alert.alertType] }}
          </p>
        </div>

        <!-- ② 评测做题记录（关联用户） -->
        <div class="panel surface">
          <h3 class="panel-title font-serif">评测做题记录</h3>

          <div v-if="store.assessmentLoading" class="rec-loading">
            <a-skeleton active :title="false" :paragraph="{ rows: 4 }" />
          </div>

          <a-empty
            v-else-if="!store.assessment"
            description="该校验接口暂不可用（后端可能未更新到最新版本）"
          />

          <template v-else>
            <p class="rec-owner">
              关联居民
              <span class="rec-owner-name">{{ store.assessment.residentName || `#${store.assessment.residentId}` }}</span>
              <span class="kv-extra mono">#{{ store.assessment.residentId }}</span>
            </p>

            <a-alert
              v-if="!store.assessment.assessmentId"
              type="info"
              show-icon
              class="rec-no-assessment"
              message="该工单未关联具体测评（趋势类工单）"
              description="工单由相邻两次测评的分数变化触发，因此没有单一「本次答卷」。下方按筛查表列出该居民历次做题记录，展开即可逐题查看。"
            />

            <a-empty
              v-if="scaleGroups.length === 0"
              description="该居民暂无可查看的做题记录"
            />

            <!--
              每个筛查表（量表）一块：默认折叠，右侧下拉箭头展开该量表历次做题记录。
              逐题明细在展开时才按需拉取 —— 一个居民可能有十几次记录，进页面就全拉会白等。
            -->
            <div v-else class="rec-groups">
              <section
                v-for="g in scaleGroups"
                :key="g.scaleCode"
                class="rec-group"
                :class="{ open: isOpen(g.scaleCode) }"
              >
                <button
                  type="button"
                  class="rec-group-head"
                  :aria-expanded="isOpen(g.scaleCode)"
                  @click="toggleGroup(g)"
                >
                  <span class="rec-scale">{{ g.scaleName }}</span>
                  <span class="rec-code mono">{{ g.scaleCode }}</span>
                  <span class="rec-group-count">{{ g.items.length }} 次记录</span>

                  <span class="rec-group-latest">
                    <span class="rec-group-latest-label">最近</span>
                    <span class="rec-group-score mono">{{ g.items[0].totalScore }}</span>
                    <span class="rec-score-unit">分</span>
                    <RiskLevelTag :level="g.items[0].riskLevel" dot />
                  </span>

                  <span
                    class="rec-toggle"
                    :title="isOpen(g.scaleCode) ? '收起做题记录' : '展开做题记录'"
                  >
                    <span class="rec-toggle-icon" />
                  </span>
                </button>

                <div v-if="isOpen(g.scaleCode)" class="rec-group-body">
                  <article v-for="r in g.items" :key="r.id" class="rec-record">
                    <header class="rec-record-head">
                      <span class="rec-record-time mono">{{ fmt(r.completedAt) }}</span>
                      <span class="rec-record-score mono">{{ r.totalScore }} 分</span>
                      <RiskLevelTag :level="r.riskLevel" dot />
                      <span v-if="r.crisisFlag === 1" class="type-chip t-CRISIS">
                        危机项阳性
                      </span>
                      <span class="rec-record-count">
                        {{ r.loaded ? `${r.answers.length} 题` : '' }}
                      </span>
                    </header>

                    <div v-if="answerLoading[r.id]" class="rec-loading">
                      <a-skeleton active :title="false" :paragraph="{ rows: 2 }" />
                    </div>

                    <p v-else-if="answerFailed[r.id]" class="rec-record-failed">
                      该次逐题明细加载失败。
                      <button type="button" class="rec-retry" @click="loadDetail(r.id)">重试</button>
                    </p>

                    <!-- 手写有序列表而非 a-table：Table 是 antd 最重的组件之一，不值得为一张只读表引入 -->
                    <ol v-else-if="r.loaded" class="answers">
                      <li
                        v-for="a in r.answers"
                        :key="a.itemNo"
                        class="answer"
                        :class="{ crisis: a.crisisFlag === 1 }"
                      >
                        <span class="answer-no mono">{{ a.itemNo }}</span>
                        <div class="answer-body">
                          <p class="answer-q">{{ a.question }}</p>
                          <p class="answer-a">
                            <span class="answer-label">作答</span>
                            <span class="answer-text">{{ a.answer || '（未作答）' }}</span>
                            <span class="answer-score mono">
                              {{ a.score === null ? '—' : `${a.score} 分` }}
                            </span>
                          </p>
                        </div>
                      </li>
                    </ol>
                  </article>
                </div>
              </section>
            </div>
          </template>
        </div>

        <!-- ③ 工单处置 -->
        <div class="panel surface">
          <h3 class="panel-title font-serif">工单处置</h3>

          <p class="status-hint">{{ STATUS_HINT[alert.status] }}</p>

          <dl v-if="alert.handlerId" class="kv kv-tight">
            <div>
              <dt>处理人</dt>
              <dd class="mono">
                #{{ alert.handlerId }}
                <span v-if="alert.handlerId === selfId" class="kv-extra">（当前登录用户）</span>
              </dd>
            </div>
            <div v-if="alert.handleNote">
              <dt>处置备注</dt>
              <dd>{{ alert.handleNote }}</dd>
            </div>
          </dl>

          <template v-if="nextStatuses.length > 0 || canReopen">
            <a-textarea
              v-model:value="note"
              :rows="3"
              :maxlength="200"
              show-count
              placeholder="处置备注（可选，会写入流转日志作为留痕）"
              class="note-input"
            />
          </template>

          <!--
            管理员专属：把已驳回 / 已关闭的工单放回医生待办，让医生端重新核实一遍。
            医生端（DOCTOR）看不到这一块，仍然是原来的「该工单已终结」提示 ——
            医生能打回自己的终态处置，那套留痕就没意义了。
          -->
          <template v-if="canReopen">
            <div class="actions">
              <a-popconfirm
                title="重新开启后工单退回「待确认」，处置人与备注清空，医生端会再次看到它。确认？"
                ok-text="确认开启"
                cancel-text="取消"
                @confirm="doReopen"
              >
                <a-button type="primary" :loading="store.reopening">重新开启</a-button>
              </a-popconfirm>
            </div>
            <p class="actions-hint">
              仅管理员可重新开启，且只对「已驳回 / 已关闭」的工单生效；原来的处置记录仍保留在下方流转日志里。
            </p>
          </template>

          <template v-else-if="nextStatuses.length > 0">
            <div class="actions">
              <a-button
                v-for="s in nextStatuses"
                :key="s"
                :type="s === 'REJECTED' ? 'default' : 'primary'"
                :danger="s === 'REJECTED'"
                :loading="store.handling"
                @click="doHandle(s)"
              >
                {{ ALERT_TRANSITION_LABEL[s] }}
              </a-button>
            </div>
            <p class="actions-hint">
              仅渲染当前状态下的合法流转；后端对非法流转返回
              <span class="mono">400</span>，此处已预防性禁用其余动作。
            </p>
          </template>

          <a-alert
            v-else
            type="info"
            show-icon
            message="该工单已终结，无后续流转动作"
            class="terminal-alert"
          />
        </div>

        <!-- ④ 流转日志 -->
        <div class="panel surface">
          <h3 class="panel-title font-serif">流转日志</h3>

          <div v-if="store.logsLoading" class="log-loading">加载中…</div>
          <a-empty v-else-if="store.logs.length === 0" description="尚无流转记录" />
          <a-timeline v-else class="log-timeline">
            <a-timeline-item v-for="row in logRows" :key="row.id">
              <div class="log-head">
                <AlertStatusChip v-if="row.toValid" :status="row.to as AlertStatus" />
                <span v-else class="log-raw mono">{{ row.to }}</span>
                <span class="log-from">由「{{ row.fromLabel }}」流转而来</span>
              </div>
              <p v-if="row.remark" class="log-remark">{{ row.remark }}</p>
              <p class="log-meta mono">
                {{ fmt(row.createdAt) }} · 操作人 #{{ row.operatorId }}
              </p>
            </a-timeline-item>
          </a-timeline>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import AlertStatusChip from '@/components/AlertStatusChip.vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import { getAssessmentRecord } from '@/api/assessment'
import { useAlertStore } from '@/stores/alert'
import { useAuthStore } from '@/stores/auth'
import {
  ALERT_GROUPS,
  ALERT_STATUS_DESC,
  ALERT_TRANSITION_LABEL,
  ALERT_TRANSITIONS,
  ALERT_TYPE_DESC,
  RISK_LEVEL_DESC,
  type AlertStatus,
  type AlertType,
  type AssessmentAnswerItem,
  type AssessmentRecordVO,
  type RiskLevel,
} from '@/types'

const route = useRoute()
const router = useRouter()
const store = useAlertStore()
const authStore = useAuthStore()

const note = ref('')

/* ==================== 评测做题记录：按筛查表分组折叠 ==================== */

/** 一条做题记录：history 只给摘要，逐题明细在展开该筛查表时才按需拉取 */
interface GroupRecord {
  id: number
  completedAt: string
  totalScore: number
  riskLevel: RiskLevel
  /** 摘要接口不返回此项，拿到明细后才有值；null = 未知 */
  crisisFlag: number | null
  /** 未加载时为 []，与 loaded 配合可让模板不必做可选链收窄 */
  answers: AssessmentAnswerItem[]
  loaded: boolean
}

/** 一个筛查表（量表）分组 */
interface ScaleGroup {
  scaleCode: string
  scaleName: string
  items: GroupRecord[]
}

/** 逐题明细缓存：key = 测评 id。展开某组时按需拉，不做「进页面拉全量」 */
const answerCache = reactive<Record<number, AssessmentRecordVO>>({})
const answerLoading = reactive<Record<number, boolean>>({})
const answerFailed = reactive<Record<number, boolean>>({})
/** 已展开的筛查表 code（默认全部折叠） */
const openScales = ref<string[]>([])

const alert = computed(() => store.detail)
const selfId = computed(() => authStore.userId)

/**
 * 换工单时重置展开态与明细缓存。
 *
 * 用 watch 而不是在 select() 里赋值 —— 深链直达（/admin/alerts/:id）时，
 * 组件是直接读 URL 触发 select 的，走 watch 才能同时覆盖「点击列表」和「URL 变化」两条路径。
 *
 * 工单关联的那一次答卷已随接口一起返回，先塞进缓存：展开时不必再请求一次。
 */
watch(
  () => store.assessment,
  (v) => {
    openScales.value = []
    for (const k of Object.keys(answerCache)) delete answerCache[Number(k)]
    for (const k of Object.keys(answerLoading)) delete answerLoading[Number(k)]
    for (const k of Object.keys(answerFailed)) delete answerFailed[Number(k)]
    if (v?.record) answerCache[v.record.id] = v.record
  },
  { immediate: true },
)

/**
 * 按筛查表（量表）分组，组内按完成时间倒序。
 *
 * 全量记录以 history 为准；工单关联的那一次若不在 history 里则补进来，
 * 避免后端口径变化（history 只返回部分）时出现「当前答卷看不见」。
 */
const scaleGroups = computed<ScaleGroup[]>(() => {
  const a = store.assessment
  if (!a) return []

  const map = new Map<string, ScaleGroup>()
  const addRecord = (
    scaleCode: string,
    scaleName: string,
    base: { id: number; completedAt: string; totalScore: number; riskLevel: RiskLevel },
  ) => {
    let g = map.get(scaleCode)
    if (!g) {
      g = { scaleCode, scaleName, items: [] }
      map.set(scaleCode, g)
    }
    const detail = answerCache[base.id]
    g.items.push({
      id: base.id,
      completedAt: detail?.completedAt ?? base.completedAt,
      totalScore: detail?.totalScore ?? base.totalScore,
      riskLevel: detail?.riskLevel ?? base.riskLevel,
      crisisFlag: detail?.crisisFlag ?? null,
      answers: detail?.answers ?? [],
      loaded: Boolean(detail),
    })
  }

  for (const h of a.history) addRecord(h.scaleCode, h.scaleName, h)
  if (a.record && !a.history.some((h) => h.id === a.record?.id)) {
    addRecord(a.record.scaleCode, a.record.scaleName, a.record)
  }

  const groups = [...map.values()]
  for (const g of groups) {
    g.items.sort((x, y) => (x.completedAt < y.completedAt ? 1 : -1))
  }
  return groups
})

function isOpen(scaleCode: string): boolean {
  return openScales.value.includes(scaleCode)
}

/** 展开 / 收起某个筛查表；展开时补齐该组各次记录的逐题明细 */
async function toggleGroup(g: ScaleGroup) {
  if (isOpen(g.scaleCode)) {
    openScales.value = openScales.value.filter((c) => c !== g.scaleCode)
    return
  }
  openScales.value = [...openScales.value, g.scaleCode]
  await Promise.all(g.items.filter((r) => !r.loaded).map((r) => loadDetail(r.id)))
}

/** 拉取某一次测评的逐题明细（并行调用，单条失败不影响其余） */
async function loadDetail(id: number) {
  if (answerLoading[id]) return
  answerLoading[id] = true
  answerFailed[id] = false
  try {
    answerCache[id] = await getAssessmentRecord(id)
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出；此处保留「加载失败 + 重试」入口
    answerFailed[id] = true
  } finally {
    answerLoading[id] = false
  }
}

const nextStatuses = computed<AlertStatus[]>(() =>
  alert.value ? (ALERT_TRANSITIONS[alert.value.status] ?? []) : [],
)

/* ==================== 管理员：重新开启终结工单 ==================== */

/** 处置台是 ADMIN 与 DOCTOR 共用的页面，管理端专属动作一律按角色收口 */
const isAdmin = computed(() => authStore.role === 'ADMIN')

/**
 * 能否重新开启：仅管理员，且工单处于「流程终止」态（已驳回 / 已关闭）。
 *
 * 医生端此值为 false —— 处置面板退化成原来的「该工单已终结」提示，行为零变化。
 * 后端 /alert/{id}/reopen 也独立断言了 ADMIN，前端这层只是不给入口。
 */
const canReopen = computed(
  () =>
    isAdmin.value &&
    !!alert.value &&
    (alert.value.status === 'REJECTED' || alert.value.status === 'CLOSED'),
)

async function doReopen() {
  try {
    await store.reopen(note.value.trim())
    message.success('已重新开启，工单退回「待确认」，医生端可再次处置')
    note.value = ''
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  }
}

const typeOptions = (Object.keys(ALERT_TYPE_DESC) as AlertType[]).map((t) => ({
  value: t,
  label: ALERT_TYPE_DESC[t],
}))

/** 触发规则说明：阈值数值在 application.yml，此处不硬编码，避免与配置脱节 */
const RULE_TEXT: Record<AlertType, string> = {
  THRESHOLD: '单次测评总分达到该量表配置的预警阈值，系统自动生成工单。',
  TREND: '相邻两次测评总分涨幅超过系统配置的阈值，提示状态呈恶化趋势。',
  CRISIS: '测评中命中危机项（如 PHQ-9 第 9 题自杀意念），绕开常规分级直接置为最高等级。',
}

const STATUS_HINT: Record<AlertStatus, string> = {
  PENDING: '工单待人工确认。确认后进入处置流程；若核实为误报，可直接驳回。',
  CONFIRMED: '已确认需要介入，请尽快开始处置并记录处置方式。',
  PROCESSING: '处置进行中。完成后请标记已回访，以进入结案环节。',
  FOLLOWED: '回访已完成，确认无需继续跟进即可结案归档。',
  REJECTED: '已判定为误报并驳回，流程终止。',
  CLOSED: '工单已结案归档，流程终止。',
}

/** 日志行做了类型收敛，避免把后端任意字符串当状态渲染 */
const logRows = computed(() =>
  store.logs.map((l) => ({
    id: l.id,
    to: l.toStatus,
    toValid: Object.prototype.hasOwnProperty.call(ALERT_STATUS_DESC, l.toStatus),
    fromLabel: statusLabel(l.fromStatus),
    remark: l.remark,
    operatorId: l.operatorId,
    createdAt: l.createdAt,
  })),
)

function statusLabel(s: string): string {
  return (ALERT_STATUS_DESC as Record<string, string>)[s] ?? s
}

function fmt(s?: string): string {
  return s ? s.replace('T', ' ').slice(0, 19) : '—'
}

/** "2026-09-24 17:12:03" → "09-24 17:12" */
function fmtShort(s?: string): string {
  if (!s) return '—'
  const t = s.replace('T', ' ')
  return t.length >= 16 ? t.slice(5, 16) : t
}

function groupBadge(key: string): number | null {
  const n = store.groupCounts[key]
  return n === undefined ? null : n
}

/** 选中工单：已与 URL 一致则直接选中，否则改 URL 由 watcher 触发 */
async function pick(id: number) {
  if (store.selectedId === id) return
  const cur = route.params.id ? Number(route.params.id) : null
  if (cur === id) {
    await store.select(id)
    return
  }
  await router.replace({ name: 'admin-alert-detail', params: { id: String(id) } })
}

/** 列表刷新后保证右栏与列表一致：选中项已不在当前分组则自动落到第一条 */
async function refresh() {
  await store.loadPage()
  const stillHere = store.records.some((r) => r.id === store.selectedId)
  if (stillHere) return
  const first = store.records[0]
  if (first) {
    await pick(first.id)
  } else {
    store.clearSelection()
    if (route.params.id) await router.replace({ name: 'admin-alerts' })
  }
}

function onGroupChange(key: string) {
  if (store.groupKey === key) return
  store.setGroup(key)
  store.clearSelection()
  void refresh()
}

function onTypeChange(v: unknown) {
  store.setTypeFilter((v as AlertType) ?? '')
  store.clearSelection()
  void refresh()
}

function onPageChange(page: number) {
  store.setPage(page)
  void refresh()
}

async function doHandle(toStatus: AlertStatus) {
  try {
    await store.handle(toStatus, note.value.trim())
    message.success(`已${ALERT_TRANSITION_LABEL[toStatus] ?? '流转'}`)
    note.value = ''
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  }
}

watch(
  () => route.params.id,
  async (v) => {
    const id = v ? Number(v) : null
    if (id && id !== store.selectedId) {
      await store.select(id)
    }
  },
)

onMounted(async () => {
  await Promise.all([store.loadPage(), store.loadStatusCount()])
  const fromUrl = route.params.id ? Number(route.params.id) : null
  if (fromUrl) {
    await store.select(fromUrl)
  } else if (store.records[0]) {
    await pick(store.records[0].id)
  }
})
</script>

<style scoped>
/* ============ 骨架：撑满内容区，两栏各自独立滚动 ============ */
.console {
  display: flex;
  gap: 16px;
  /* 内容区可用高 = 视口 - 顶栏 - 上下内边距（全局 border-box） */
  height: calc(100vh - var(--header-h) - 2 * var(--content-pad));
  min-height: 520px;
}

/* ============ 左栏 ============ */
.list-col {
  width: 372px;
  flex: none;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.list-head {
  flex: none;
  padding: 16px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.list-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 17px;
  color: var(--text-1);
}

.list-total {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-3);
}

.type-filter {
  width: 100%;
}

.group-tabs {
  flex: none;
  display: flex;
  gap: 6px;
  padding: 0 16px 12px;
  border-bottom: 1px solid var(--card-border);
}

.group-tab {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 6px 4px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: var(--panel-bg);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.group-tab:hover {
  color: var(--text-1);
}

.group-tab.active {
  background: var(--primary-soft);
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}

.badge {
  font-size: 11px;
  opacity: 0.85;
}

.list-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.list-empty {
  padding: 48px 0;
}

/* ---- 加载骨架 ---- */
.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px;
}

.skeleton-row {
  height: 76px;
  border-radius: var(--radius-sm);
  /* 底色 → 高光必须成对取 token：曾用 --panel-bg / --bg-3，两者加深后仅差 1
     （#eef3fa vs #eef3f9）会让动画退化成静止灰条 */
  background: linear-gradient(
    90deg,
    var(--skeleton-base) 25%,
    var(--skeleton-hi) 37%,
    var(--skeleton-base) 63%
  );
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  from {
    background-position: 100% 50%;
  }
  to {
    background-position: 0 50%;
  }
}

/* ---- 工单项 ---- */
.ticket {
  position: relative;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 12px 12px 14px;
  margin-bottom: 6px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.ticket:hover {
  background: var(--panel-bg);
}

.ticket.active {
  background: var(--primary-soft);
  border-color: var(--primary);
}

/* 选中态的左侧强调条：用伪元素，不占布局，避免选中时内容位移 */
.ticket::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: transparent;
}

.ticket.active::before {
  background: var(--primary);
}

/* 危机工单在未选中时也留一个警示色，防止被淹没在列表里 */
.ticket.crisis .ticket-id {
  color: var(--risk-d);
}

.ticket-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ticket-id {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.ticket-foot {
  font-size: 12px;
  color: var(--text-3);
}

.list-foot {
  flex: none;
  padding: 10px 16px;
  border-top: 1px solid var(--card-border);
  display: flex;
  justify-content: center;
}

/* ---- 预警类型 chip ---- */
.type-chip {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  background: var(--chip-bg);
  color: var(--chip-fg);
}

.t-CRISIS {
  --chip-fg: var(--alert-crisis);
  --chip-bg: var(--risk-d-soft);
}
.t-THRESHOLD {
  --chip-fg: var(--alert-threshold);
  --chip-bg: var(--risk-b-soft);
}
.t-TREND {
  --chip-fg: var(--alert-trend);
  --chip-bg: var(--primary-soft);
}

/* ============ 右栏 ============ */
.detail-col {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.head {
  flex: none;
  padding: 18px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.head-id {
  font-size: 19px;
  color: var(--text-1);
}

.head-meta {
  display: flex;
  gap: 28px;
  margin: 0;
}

.head-meta dt {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 4px;
}

.head-meta dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
}

/* ---- 通用面板 ---- */
.panel {
  flex: none;
  padding: 18px 20px 20px;
}

.panel-title {
  font-size: 16px;
  color: var(--text-1);
  margin: 0 0 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--card-border);
}

/* ---- 键值对 ---- */
.kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 24px;
  margin: 0 0 16px;
}

.kv-tight {
  margin-bottom: 14px;
}

.kv > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.kv dt {
  font-size: 12px;
  color: var(--text-3);
}

.kv dd {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-1);
}

.kv-extra {
  font-size: 12px;
  color: var(--text-3);
}

/* ---- 触发规则说明 ---- */
.rule-text {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  border: 1px solid var(--card-border);
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

.rule-label {
  display: inline-block;
  margin-right: 8px;
  font-weight: 600;
  color: var(--text-1);
}

/* ---- 危机提示（与 AI 问诊页危机卡同色系） ---- */
.crisis-note {
  margin-bottom: 16px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--risk-d-soft);
  border: 1px solid var(--risk-d-border);
}

.crisis-note strong {
  display: block;
  font-size: 14px;
  color: var(--risk-d);
  margin-bottom: 4px;
}

.crisis-note p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

/* ---- 处置区 ---- */
.status-hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

.note-input {
  margin-bottom: 14px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.actions-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.terminal-alert {
  border-radius: var(--radius-sm);
}

/* ---- 流转日志 ---- */
.log-loading {
  padding: 12px 0;
  font-size: 13px;
  color: var(--text-3);
}

.log-timeline {
  margin-top: 4px;
}

.log-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.log-from {
  font-size: 13px;
  color: var(--text-2);
}

.log-raw {
  font-size: 13px;
  color: var(--text-1);
}

.log-remark {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}

.log-meta {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

/* ---- 评测做题记录 ---- */
.rec-loading {
  padding: 4px 0 8px;
}

.rec-owner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--text-3);
}

.rec-owner-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.rec-no-assessment {
  margin-bottom: 14px;
  border-radius: var(--radius-sm);
}

.rec-scale {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.rec-code {
  font-size: 12px;
  color: var(--text-3);
}

.rec-score-unit {
  font-size: 12px;
  color: var(--text-3);
  margin-left: -4px;
}

/* ---- 筛查表分组：默认折叠，右侧下拉箭头展开 ---- */
.rec-groups {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rec-group {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  overflow: hidden;
  transition: border-color var(--dur) var(--ease);
}

.rec-group.open {
  border-color: var(--primary);
}

/* 组头整块可点，右侧箭头是唯一的展开提示 */
.rec-group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 13px 14px;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.rec-group-head:hover {
  background: var(--card-bg);
}

.rec-group-count {
  flex: none;
  padding: 2px 9px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  font-size: 12px;
  color: var(--text-3);
}

.rec-group-latest {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.rec-group-latest-label {
  font-size: 12px;
  color: var(--text-3);
}

.rec-group-score {
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
  color: var(--text-1);
}

.rec-toggle {
  flex: none;
  width: 24px;
  height: 24px;
  margin-left: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  transition:
    border-color var(--dur) var(--ease),
    background var(--dur) var(--ease);
}

.rec-group-head:hover .rec-toggle {
  border-color: var(--primary);
}

/* 纯 CSS 箭头：vendor-antd 预算只剩 12KB，不为一个箭头再引图标组件 */
.rec-toggle-icon {
  width: 7px;
  height: 7px;
  margin-top: -3px;
  border-right: 1.5px solid var(--text-3);
  border-bottom: 1.5px solid var(--text-3);
  transform: rotate(45deg);
  transition:
    transform var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.rec-group.open .rec-toggle-icon {
  margin-top: 3px;
  transform: rotate(-135deg);
  border-color: var(--primary);
}

.rec-group-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px 14px 14px;
  border-top: 1px dashed var(--card-border);
}

.rec-record-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-bottom: 8px;
}

.rec-record-time {
  font-size: 12px;
  color: var(--text-3);
}

.rec-record-score {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-1);
}

.rec-record-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}

.rec-record-failed {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}

.rec-retry {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  color: var(--primary);
  cursor: pointer;
}

/* ---- 逐题作答：手写有序列表，避免为一张只读表引入 antd Table ---- */
.answers {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.answer {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
}

/* 危机项：左侧色条 + 浅底，医生扫一眼就能定位 */
.answer.crisis {
  border-color: var(--risk-d-border);
  background: var(--risk-d-soft);
  border-left: 3px solid var(--risk-d);
}

.answer-no {
  flex: none;
  width: 22px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-3);
  text-align: right;
  padding-top: 1px;
}

.answer-body {
  flex: 1;
  min-width: 0;
}

.answer-q {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}

.answer-a {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 13px;
}

.answer-label {
  flex: none;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  color: var(--text-3);
  background: var(--panel-bg);
}

.answer-text {
  color: var(--text-1);
  font-weight: 500;
}

.answer-score {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}

</style>
