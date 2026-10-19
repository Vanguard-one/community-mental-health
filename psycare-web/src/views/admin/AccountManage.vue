<template>
  <div class="account-manage">
    <!-- ===== 概览条 ===== -->
    <div class="summary-bar surface">
      <div class="summary-item">
        <span class="summary-label">当前筛选结果</span>
        <span class="summary-value mono">{{ total }}</span>
        <span class="summary-unit">个账号</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">待审核医生</span>
        <span class="summary-value mono" :class="{ warn: pendingAudit > 0 }">
          {{ pendingAudit }}
        </span>
        <span class="summary-unit">人</span>
      </div>
      <a-button :loading="loading" @click="reload">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <!-- ===== 筛选 ===== -->
    <div class="filter-bar surface">
      <nav class="type-tabs">
        <button
          v-for="t in TYPE_TABS"
          :key="t.value"
          type="button"
          class="type-tab"
          :class="{ active: filters.userType === t.value }"
          @click="filters.userType = t.value"
        >
          {{ t.label }}
        </button>
      </nav>

      <div class="filter-fields">
        <a-select
          v-model:value="filters.status"
          class="f-status"
          :options="STATUS_OPTIONS"
        />
        <a-select
          v-if="showAuditFilter"
          v-model:value="filters.auditStatus"
          class="f-audit"
          :options="AUDIT_OPTIONS"
        />
        <a-input
          v-model:value="filters.keyword"
          class="f-keyword"
          placeholder="按用户名 / 姓名搜索，回车查询"
          allow-clear
          @press-enter="searchKeyword"
        />
        <a-button type="primary" @click="searchKeyword">查询</a-button>
        <a-button @click="resetFilters">重置</a-button>
      </div>
    </div>

    <!-- ===== 列表 ===== -->
    <div class="table-wrap surface">
      <div class="grid-head">
        <span>账号</span>
        <span>姓名</span>
        <span>角色</span>
        <span>手机号</span>
        <span>状态</span>
        <span>资质 / 附加信息</span>
        <span class="ta-right">操作</span>
      </div>

      <!-- 骨架：不用 a-spin，避免插入额外 div 打断 grid -->
      <div v-if="loading" class="skeleton-wrap">
        <div v-for="i in 6" :key="i" class="skeleton-row" />
      </div>

      <a-empty v-else-if="rows.length === 0" class="list-empty" description="没有符合条件的账号" />

      <template v-else>
        <div v-for="row in rows" :key="`${row.userType}-${row.id}`" class="grid-row">
          <div class="cell">
            <span class="acc-name">{{ row.username }}</span>
            <span class="acc-id mono">#{{ row.id }}</span>
          </div>

          <div class="cell">{{ row.realName || '—' }}</div>

          <div class="cell">
            <span class="role-chip" :class="`r-${row.userType}`">
              {{ USER_TYPE_DESC[row.userType] }}
            </span>
          </div>

          <div class="cell mono">{{ row.phone || '—' }}</div>

          <div class="cell">
            <span class="status-chip" :class="row.status === 1 ? 'on' : 'off'">
              {{ row.status === 1 ? '正常' : '已禁用' }}
            </span>
          </div>

          <div class="cell">
            <!-- 医生：审核状态是治理的第一顺位信息 -->
            <template v-if="row.userType === 'DOCTOR'">
              <span class="audit-chip" :class="`a-${row.auditStatus}`">
                {{ auditLabel(row.auditStatus) }}
              </span>
              <span v-if="row.department || row.title" class="cell-sub">
                {{ [row.department, row.title].filter(Boolean).join(' · ') }}
              </span>
              <span v-else-if="row.licenseNo" class="cell-sub mono">{{ row.licenseNo }}</span>
            </template>

            <template v-else-if="row.userType === 'ADMIN'">
              <span class="level-chip" :class="{ super: row.adminLevel === 'SUPER' }">
                {{ row.adminLevel === 'SUPER' ? '超级管理员' : '普通管理员' }}
              </span>
            </template>

            <template v-else>
              <span class="cell-sub">
                {{ [genderLabel(row.gender), row.birthYear ? `${row.birthYear} 年生` : ''].filter(Boolean).join(' · ') || '—' }}
              </span>
            </template>
          </div>

          <div class="cell ta-right">
            <div class="ops">
              <!-- 医生待审核：最高优先级动作，前置且主色 -->
              <a-button
                v-if="row.userType === 'DOCTOR' && row.auditStatus === 'PENDING'"
                type="primary"
                size="small"
                @click="openAudit(row)"
              >
                审核
              </a-button>

              <a-button size="small" @click="openReset(row)">重置密码</a-button>

              <a-popconfirm
                v-if="row.status === 1"
                title="禁用后该账号立即无法登录，确认？"
                ok-text="确认禁用"
                cancel-text="取消"
                :disabled="!canDisable(row)"
                @confirm="toggleStatus(row, 0)"
              >
                <a-button
                  class="op-disable"
                  size="small"
                  :disabled="!canDisable(row)"
                  :title="lockReason(row)"
                >
                  禁用
                </a-button>
              </a-popconfirm>

              <a-button v-else class="op-enable" size="small" @click="toggleStatus(row, 1)">
                启用
              </a-button>

              <a-popconfirm
                title="逻辑删除（保留行与审计），确认？"
                ok-text="确认删除"
                cancel-text="取消"
                :disabled="!canDelete(row)"
                @confirm="doDelete(row)"
              >
                <a-button size="small" danger :disabled="!canDelete(row)" :title="lockReason(row)">
                  删除
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </template>

      <footer v-if="total > size" class="table-foot">
        <a-pagination
          :current="page"
          :page-size="size"
          :total="total"
          :show-size-changer="false"
          @change="onPageChange"
        />
      </footer>
    </div>

    <!-- ===== 审核弹窗 ===== -->
    <a-modal
      v-model:open="auditVisible"
      title="医生资质审核"
      :confirm-loading="auditing"
      ok-text="提交审核结果"
      cancel-text="取消"
      @ok="submitAudit"
    >
      <div v-if="auditTarget" class="audit-form">
        <dl class="kv">
          <div>
            <dt>账号</dt>
            <dd class="mono">{{ auditTarget.username }}（#{{ auditTarget.id }}）</dd>
          </div>
          <div>
            <dt>姓名</dt>
            <dd>{{ auditTarget.realName || '—' }}</dd>
          </div>
          <div>
            <dt>执业证书编号</dt>
            <dd class="mono">{{ auditTarget.licenseNo || '未填写' }}</dd>
          </div>
          <div>
            <dt>科室 / 职称</dt>
            <dd>{{ [auditTarget.department, auditTarget.title].filter(Boolean).join(' · ') || '未填写' }}</dd>
          </div>
          <div class="full">
            <dt>专长标签</dt>
            <dd>{{ auditTarget.expertiseTags || '未填写' }}</dd>
          </div>
        </dl>

        <a-form layout="vertical">
          <a-form-item label="审核结果">
            <a-radio-group v-model:value="auditForm.auditStatus">
              <a-radio-button value="APPROVED">通过</a-radio-button>
              <a-radio-button value="REJECTED">驳回</a-radio-button>
            </a-radio-group>
          </a-form-item>
        </a-form>

        <a-alert
          v-if="auditForm.auditStatus === 'APPROVED'"
          type="info"
          show-icon
          message="通过后该医生即可登录并接收预警工单"
        />
        <a-alert
          v-else
          type="warning"
          show-icon
          message="驳回后仍无法登录，医生需补充材料后重新联系管理员审核"
        />
      </div>
    </a-modal>

    <!-- ===== 重置密码结果 ===== -->
    <a-modal
      v-model:open="pwdVisible"
      title="密码已重置"
      :footer="null"
      width="420px"
    >
      <p class="pwd-tip">
        系统只保存 BCrypt 哈希、无法反查，请<strong>立即复制</strong>并安全转达给用户：
      </p>
      <div class="pwd-box">
        <span class="pwd-text mono">{{ pwdPlain }}</span>
        <a-button size="small" @click="copyPwd">复制</a-button>
      </div>
      <p class="pwd-sub">
        账号 <span class="mono">{{ pwdTarget?.username }}</span> 的旧密码已失效。
      </p>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import {
  auditDoctor,
  deleteAccount,
  getAccountPage,
  getPendingAuditCount,
  resetAccountPassword,
  updateAccountStatus,
} from '@/api/account'
import { useAuthStore } from '@/stores/auth'
import {
  AUDIT_STATUS_DESC,
  USER_TYPE_DESC,
  type AccountVO,
  type DoctorAuditStatus,
  type UserType,
} from '@/types'

const authStore = useAuthStore()

/* ==================== 类型常量 ==================== */

const TYPE_TABS: { value: UserType | ''; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'RESIDENT', label: '居民' },
  { value: 'DOCTOR', label: '医生' },
  { value: 'ADMIN', label: '管理员' },
]

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 1, label: '正常' },
  { value: 0, label: '已禁用' },
]

const AUDIT_OPTIONS = [
  { value: '', label: '全部审核状态' },
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

/* ==================== 列表状态 ==================== */

const filters = reactive<{
  userType: UserType | ''
  status: number | ''
  auditStatus: DoctorAuditStatus | ''
  keyword: string
}>({
  userType: '',
  status: '',
  auditStatus: '',
  keyword: '',
})

const rows = ref<AccountVO[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const pendingAudit = ref(0)

/** 审核状态只在可能包含医生时才有意义 —— 「全部」tab 下也保留，避免来回切 tab 丢筛选 */
const showAuditFilter = computed(
  () => filters.userType === 'DOCTOR' || filters.userType === '',
)

const currentAdminIsSuper = computed(() => authStore.extra?.adminLevel === 'SUPER')

async function load() {
  loading.value = true
  try {
    const res = await getAccountPage({
      userType: filters.userType || undefined,
      status: filters.status === '' ? undefined : filters.status,
      auditStatus: filters.auditStatus || undefined,
      keyword: filters.keyword.trim() || undefined,
      current: page.value,
      size: size.value,
    })
    rows.value = res.records
    total.value = res.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadPendingAudit() {
  try {
    pendingAudit.value = await getPendingAuditCount()
  } catch {
    // 后端未升级时静默降级，不阻断列表使用
    pendingAudit.value = 0
  }
}

/** 切筛选条件必须回到第 1 页，否则会停在越界页拿到空列表 */
watch(
  () => [filters.userType, filters.status, filters.auditStatus],
  () => {
    page.value = 1
    void load()
  },
)

function searchKeyword() {
  page.value = 1
  void load()
}

function resetFilters() {
  filters.userType = ''
  filters.status = ''
  filters.auditStatus = ''
  filters.keyword = ''
  page.value = 1
  void load()
}

function onPageChange(p: number) {
  page.value = p
  void load()
}

function reload() {
  void Promise.all([load(), loadPendingAudit()])
}

/* ==================== 展示辅助 ==================== */

function auditLabel(s?: DoctorAuditStatus): string {
  return s ? AUDIT_STATUS_DESC[s] ?? s : '未审核'
}

function genderLabel(g?: string): string {
  if (g === 'M') return '男'
  if (g === 'F') return '女'
  if (g === 'U') return '不愿透露'
  return ''
}

/** 禁用/删除的自锁条件：与后端的三条保护一一对应，前端只做预防性禁用 */
function isSelf(row: AccountVO): boolean {
  return row.id === authStore.userId
}

function isSuperAdmin(row: AccountVO): boolean {
  return row.userType === 'ADMIN' && row.adminLevel === 'SUPER'
}

function canDisable(row: AccountVO): boolean {
  return !isSelf(row) && !(isSuperAdmin(row) && !currentAdminIsSuper.value)
}

function canDelete(row: AccountVO): boolean {
  return canDisable(row)
}

function lockReason(row: AccountVO): string {
  if (isSelf(row)) return '不能操作当前登录账号'
  if (isSuperAdmin(row) && !currentAdminIsSuper.value) {
    return '需要超级管理员权限才能操作超级管理员账号'
  }
  return ''
}

/* ==================== 启用 / 禁用 ==================== */

async function toggleStatus(row: AccountVO, status: number) {
  try {
    await updateAccountStatus(row.userType, row.id, status)
    message.success(status === 1 ? '已启用' : '已禁用')
    await Promise.all([load(), loadPendingAudit()])
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  }
}

/* ==================== 逻辑删除 ==================== */

async function doDelete(row: AccountVO) {
  try {
    await deleteAccount(row.userType, row.id)
    message.success('已逻辑删除，历史业务数据保留')
    // 删掉当前页最后一条时回退一页，避免停在空页
    if (rows.value.length === 1 && page.value > 1) page.value -= 1
    await Promise.all([load(), loadPendingAudit()])
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  }
}

/* ==================== 重置密码 ==================== */

const pwdVisible = ref(false)
const pwdTarget = ref<AccountVO | null>(null)
const pwdPlain = ref('')

function openReset(row: AccountVO) {
  pwdTarget.value = row
  pwdPlain.value = ''
  pwdVisible.value = true
  void doReset(row)
}

async function doReset(row: AccountVO) {
  try {
    // 不传 newPassword：由后端生成随机强密码，避免管理员设弱口令
    const res = await resetAccountPassword(row.userType, row.id)
    pwdPlain.value = res.password
  } catch {
    pwdVisible.value = false
  }
}

async function copyPwd() {
  if (!pwdPlain.value) return
  try {
    await navigator.clipboard.writeText(pwdPlain.value)
    message.success('已复制到剪贴板')
  } catch {
    message.warning('复制失败，请手动选中文档复制')
  }
}

/* ==================== 医生审核 ==================== */

const auditVisible = ref(false)
const auditing = ref(false)
const auditTarget = ref<AccountVO | null>(null)
/** 审核只采集「通过 / 驳回」这一项结论，不再采集文字意见 */
const auditForm = reactive<{ auditStatus: 'APPROVED' | 'REJECTED' }>({
  auditStatus: 'APPROVED',
})

function openAudit(row: AccountVO) {
  auditTarget.value = row
  auditForm.auditStatus = 'APPROVED'
  auditVisible.value = true
}

async function submitAudit() {
  const target = auditTarget.value
  if (!target) return
  auditing.value = true
  try {
    await auditDoctor(target.id, auditForm.auditStatus)
    message.success(auditForm.auditStatus === 'APPROVED' ? '审核已通过' : '已驳回')
    auditVisible.value = false
    await Promise.all([load(), loadPendingAudit()])
  } catch {
    // 错误提示由 request.ts 拦截器统一弹出
  } finally {
    auditing.value = false
  }
}

onMounted(() => {
  void Promise.all([load(), loadPendingAudit()])
})
</script>

<style scoped>
.account-manage {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ==================== 概览条 ==================== */
.summary-bar {
  display: flex;
  align-items: center;
  gap: 32px;
  padding: 14px 20px;
}

.summary-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.summary-label {
  font-size: 13px;
  color: var(--text-3);
}

.summary-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
  color: var(--text-1);
}

.summary-value.warn {
  color: var(--risk-b);
}

.summary-unit {
  font-size: 12px;
  color: var(--text-3);
}

/* 刷新按钮推到右侧 */
.summary-bar > :last-child {
  margin-left: auto;
}

/* ==================== 筛选 ==================== */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  flex-wrap: wrap;
}

.type-tabs {
  display: flex;
  gap: 6px;
}

.type-tab {
  padding: 6px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: var(--panel-bg);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.type-tab:hover {
  color: var(--text-1);
}

.type-tab.active {
  background: var(--primary-soft);
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}

.filter-fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.f-status {
  width: 132px;
}

.f-audit {
  width: 156px;
}

.f-keyword {
  width: 232px;
}

/* ==================== 表格 ==================== */
.table-wrap {
  padding: 0 0 4px;
  overflow: hidden;
}

/* 七列固定栅格：表头与数据行共用，保证列对齐 */
.grid-head,
.grid-row {
  display: grid;
  grid-template-columns:
    minmax(150px, 1.3fr) minmax(80px, 0.8fr) minmax(70px, 0.7fr)
    minmax(110px, 0.9fr) minmax(76px, 0.7fr) minmax(190px, 1.6fr) minmax(280px, 2fr);
  gap: 12px;
  align-items: center;
  padding: 12px 18px;
  min-width: 1080px;
}

.grid-head {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  background: var(--panel-bg);
  border-bottom: 1px solid var(--card-border);
}

.grid-row {
  border-bottom: 1px solid var(--card-border);
  transition: background var(--dur) var(--ease);
}

.grid-row:last-child {
  border-bottom: none;
}

.grid-row:hover {
  background: var(--panel-bg);
}

.cell {
  font-size: 13px;
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

/* 右对齐列（操作列）。表头与数据行**必须分别处理**：
   - 数据行：.ta-right 落在 .cell 上，.cell 是 flex 容器 → justify-content 生效；
   - 表头：.ta-right 落在 <span> 上，它是 .grid-head（grid 容器）的**网格项**，
     自身既不是 flex/grid 容器、子内容也不是 flex item → justify-content 完全无效，
     文字会贴在列左缘，与下方右对齐的按钮错开（本次修复的问题）。
   故表头这一支改用 text-align：网格项默认 justify-self: stretch 会撑满整列，
   右对齐后文字右缘正好与最右侧按钮的右缘齐平。 */
.ta-right {
  justify-content: flex-end;
}

.grid-head .ta-right {
  text-align: right;
}

.acc-name {
  font-weight: 600;
  color: var(--text-1);
}

.acc-id {
  font-size: 12px;
  color: var(--text-3);
}

.cell-sub {
  font-size: 12px;
  color: var(--text-3);
}

/* ---- 各类 chip ---- */
.role-chip,
.status-chip,
.audit-chip,
.level-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
  white-space: nowrap;
}

.r-RESIDENT {
  background: var(--primary-soft);
  color: var(--primary);
}

.r-DOCTOR {
  background: color-mix(in srgb, var(--accent-purple) 12%, transparent);
  color: var(--accent-purple);
}

.r-ADMIN {
  background: var(--panel-bg);
  color: var(--text-2);
  border: 1px solid var(--card-border);
}

.status-chip.on {
  background: var(--risk-a-soft);
  color: var(--risk-a);
}

.status-chip.off {
  background: var(--risk-d-soft);
  color: var(--risk-d);
}

.audit-chip.a-PENDING {
  background: var(--risk-b-soft);
  color: var(--risk-b);
}

.audit-chip.a-APPROVED {
  background: var(--risk-a-soft);
  color: var(--risk-a);
}

.audit-chip.a-REJECTED {
  background: var(--risk-d-soft);
  color: var(--risk-d);
}

.level-chip.super {
  background: color-mix(in srgb, var(--accent-purple) 12%, transparent);
  color: var(--accent-purple);
}

.level-chip:not(.super) {
  background: var(--panel-bg);
  color: var(--text-2);
  border: 1px solid var(--card-border);
}

/* ---- 操作列 ---- */
.ops {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}

/* ---- 禁用 / 启用：按语义着色，与「删除」的 danger 红区分开 ----
   禁用 = 警示橙（可逆的阻断）、启用 = 恢复青绿，与状态列「已禁用 / 正常」的色阶同源。
   一律限定 :not(:disabled)：权限锁定时保持 antd 的置灰，别把「不能点」也画成可点。
   选择器带 .ops 前缀是为了压过 antd cssinjs 的默认/hover 规则。 */
.ops .op-disable:not(:disabled),
.ops .op-enable:not(:disabled) {
  background: var(--card-bg);
  font-weight: 600;
}

.ops .op-disable:not(:disabled) {
  border-color: var(--risk-b);
  color: var(--risk-b);
}

.ops .op-disable:not(:disabled):hover {
  background: var(--risk-b-soft);
  border-color: var(--risk-b);
  color: var(--risk-b);
}

.ops .op-enable:not(:disabled) {
  border-color: var(--risk-a);
  color: var(--risk-a);
}

.ops .op-enable:not(:disabled):hover {
  background: var(--risk-a-soft);
  border-color: var(--risk-a);
  color: var(--risk-a);
}

/* ==================== 加载骨架 ==================== */
.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 1px 0;
}

.skeleton-row {
  height: 48px;
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

.list-empty {
  padding: 56px 0;
}

.table-foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--card-border);
}

/* ==================== 弹窗内 ==================== */
.audit-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  margin: 0 0 16px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  border: 1px solid var(--card-border);
}

.kv > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.kv > div.full {
  grid-column: 1 / -1;
}

.kv dt {
  font-size: 12px;
  color: var(--text-3);
}

.kv dd {
  margin: 0;
  font-size: 13px;
  color: var(--text-1);
  word-break: break-all;
}

.pwd-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-2);
}

.pwd-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--panel-bg);
  border: 1px dashed var(--primary);
}

.pwd-text {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--text-1);
  user-select: all;
}

.pwd-sub {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-3);
}
</style>
