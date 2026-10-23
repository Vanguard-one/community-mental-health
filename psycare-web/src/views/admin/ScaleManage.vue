<template>
  <!--
    评测筛查表管理（管理端）。

    范围：**只做治理，不做新增** —— 新增按钮按需求暂缓，保留为禁用态，
    避免管理员点了没反应时怀疑页面坏了。禁用 / 启用 / 删除三个操作完整可用。

    为什么删除要强调"历史记录保留"：量表被 assessment 引用，物理删除会让
    居民的历史测评与工单里的纵向对比断链。后端做的是逻辑删除，
    所以确认框必须把这个后果说成人话，而不是只问一句"确认删除？"。
  -->
  <div class="scale-page">
    <!-- ===== 工具条 ===== -->
    <div class="toolbar surface">
      <div class="toolbar-left">
        <h2 class="page-title font-serif">评测筛查表管理</h2>
        <span class="page-sub">
          共 <b class="mono">{{ rows.length }}</b> 张 ·
          启用中 <b class="mono">{{ enabledCount }}</b> 张
        </span>
      </div>
      <div class="toolbar-right">
        <!--
          新增按钮按需求暂缓，保留为禁用态 —— 直接不渲染会让管理员找不到入口，
          以为页面做漏了。提示走原生 title（挂在外层 span 上：disabled 按钮
          自身不派发事件，title 在部分浏览器上不弹），不为此引入 a-tooltip，
          与「管理端零新增 antd 组件」的取舍一致（vendor-antd 有体积预算）。
        -->
        <span
          class="tip-wrap"
          title="新增筛查表功能暂缓上线，当前只支持对已有筛查表做启用、禁用与删除"
        >
          <a-button disabled>新增筛查表</a-button>
        </span>
        <a-button :loading="loading" @click="reload">刷新</a-button>
      </div>
    </div>

    <!--
      全部禁用时的告警。这个提示是必要的：
      「禁用」在管理端看起来只是一个状态开关，但全部禁用之后
      居民端首页会一张表都没有、完全无法测评 —— 后果必须在管理端可见。
    -->
    <a-alert
      v-if="!loading && rows.length && enabledCount === 0"
      class="hint-alert"
      type="warning"
      show-icon
      message="当前没有启用的筛查表"
      description="居民端将无法发起任何测评。请至少启用一张筛查表。"
    />

    <!-- ===== 列表 ===== -->
    <div class="table-wrap surface">
      <div class="grid-head">
        <span>编码</span>
        <span>量表名称 / 说明</span>
        <span class="ta-center">题目</span>
        <span class="ta-center">满分</span>
        <span class="ta-center">测评记录</span>
        <span class="ta-center">状态</span>
        <span class="ta-right">操作</span>
      </div>

      <div v-if="loading" class="skeleton-wrap">
        <div v-for="i in 4" :key="i" class="skeleton-row" />
      </div>

      <a-empty v-else-if="rows.length === 0" class="list-empty" description="没有可管理的筛查表" />

      <template v-else>
        <div v-for="row in rows" :key="row.id" class="grid-row">
          <!-- 编码 -->
          <div class="cell">
            <span class="scale-code mono">{{ row.code }}</span>
          </div>

          <!-- 名称 + 维度 + 说明 -->
          <div class="cell">
            <span class="scale-name" :class="{ off: row.status !== 1 }">{{ row.name }}</span>
            <span class="scale-meta">
              <span class="dim-tag">{{ row.dimension }}</span>
              <span v-if="row.description" class="scale-desc" :title="row.description">
                {{ row.description }}
              </span>
            </span>
          </div>

          <!-- 题目数 -->
          <div class="cell ta-center">
            <span class="num mono">{{ row.itemCount ?? 0 }}</span>
            <span class="num-unit">题</span>
          </div>

          <!-- 满分 -->
          <div class="cell ta-center">
            <span class="num mono">{{ row.maxScore ?? '—' }}</span>
          </div>

          <!-- 测评记录数 -->
          <div class="cell ta-center">
            <span class="num mono">{{ row.assessmentCount }}</span>
            <span v-if="row.assessmentCount > 0" class="hist-chip">有历史</span>
          </div>

          <!-- 状态 -->
          <div class="cell ta-center">
            <span class="state-chip" :class="row.status === 1 ? 'on' : 'off'">
              {{ row.status === 1 ? '启用中' : '已禁用' }}
            </span>
          </div>

          <!-- 操作 -->
          <div class="cell ta-right">
            <div class="ops">
              <a-button
                size="small"
                :loading="togglingId === row.id"
                :disabled="deletingId === row.id"
                @click="doToggle(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </a-button>

              <a-popconfirm
                :title="deleteConfirmText(row)"
                ok-text="确认删除"
                cancel-text="取消"
                :ok-button-props="{ danger: true }"
                @confirm="doDelete(row)"
              >
                <a-button
                  size="small"
                  danger
                  :loading="deletingId === row.id"
                  :disabled="togglingId === row.id"
                >
                  删除
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!--
      底部说明：把「删除与禁用的区别」一次讲清，省掉管理员反复试。
      这两件事在别的系统里常常被做成一样的效果，这里必须区分开。
    -->
    <p class="foot-note">
      <b>禁用</b>：居民端列表不再出现这张表，随时可以再启用；
      <b>删除</b>：从管理端一并移除，居民端不可见、不可提交，但已有测评记录与逐题明细
      仍保留在系统里（历史报告可正常查看）。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { deleteScale, listAdminScales, setScaleStatus } from '@/api/scale'
import type { AdminScale } from '@/types'

const loading = ref(false)
const rows = ref<AdminScale[]>([])

/**
 * 单行操作中的互斥标记。
 * 用 id 而不是一个全局 boolean：一行在请求中时，其他行仍然可以操作，
 * 页面不该因为某一行慢而被整体锁死。
 */
const togglingId = ref<number | null>(null)
const deletingId = ref<number | null>(null)

const enabledCount = computed(() => rows.value.filter((r) => r.status === 1).length)

/* ==================== 列表 ==================== */

async function reload() {
  loading.value = true
  try {
    rows.value = (await listAdminScales()) ?? []
  } finally {
    loading.value = false
  }
}

/* ==================== 启用 / 禁用 ==================== */

/**
 * 传**目标值**而不是让后端 toggle。
 * 目标值天然幂等：连点两次「禁用」结果仍是禁用；
 * 而 toggle 在双击时会翻回启用，页面还会显示成功 —— 最难查的一类状态不一致。
 */
async function doToggle(row: AdminScale) {
  const next: 0 | 1 = row.status === 1 ? 0 : 1
  togglingId.value = row.id
  try {
    const updated = await setScaleStatus(row.id, next)
    const idx = rows.value.findIndex((r) => r.id === row.id)
    if (idx >= 0) {
      // 只回填 status：题数 / 测评次数这些聚合值与本次操作无关，
      // 重新拉全表反而会让整页闪烁
      rows.value[idx] = { ...rows.value[idx], status: updated?.status ?? next }
    }
    if (next === 1) {
      message.success(`已启用「${row.name}」`)
    } else if (enabledCount.value === 0) {
      // 禁用最后一张表是个"看起来没事、实际把居民端锁死"的操作，必须显式告警
      message.warning('已禁用，但当前已没有启用的筛查表，居民端将无法测评')
    } else {
      message.success(`已禁用「${row.name}」`)
    }
  } finally {
    togglingId.value = null
  }
}

/* ==================== 删除 ==================== */

/**
 * 删除确认文案按「有没有历史测评」分两档。
 * 有历史时不说清"记录还在"，管理员会因为怕丢数据而不敢删 ——
 * 而这正是软删除要解决的顾虑，不写出来等于白做。
 */
function deleteConfirmText(row: AdminScale): string {
  if (row.assessmentCount > 0) {
    return `「${row.name}」已有 ${row.assessmentCount} 条测评记录。`
      + '删除后居民端不再展示、无法再发起该测评，但历史记录与逐题明细会保留。确认删除？'
  }
  return `确认删除「${row.name}」？该表暂无测评记录，删除后不可再对居民开放。`
}

async function doDelete(row: AdminScale) {
  deletingId.value = row.id
  try {
    await deleteScale(row.id)
    message.success(`已删除「${row.name}」`)
    // 删除改变了列表构成（少一行），与启停不同，这里必须重拉
    await reload()
  } finally {
    deletingId.value = null
  }
}

onMounted(reload)
</script>

<style scoped>
.scale-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.surface {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
}

/* ---------- 工具条 ---------- */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
}

.toolbar-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0;
}

.page-sub {
  font-size: 12px;
  color: var(--text-3);
}

.toolbar-right {
  display: flex;
  gap: 10px;
  flex: none;
}

/* a-tooltip 包 disabled 按钮：disabled 元素不派发鼠标事件，
   必须由外层 span 承接 hover，否则提示永远不出现 */
.tip-wrap {
  display: inline-block;
  cursor: not-allowed;
}

.hint-alert {
  border-radius: var(--radius-sm);
}

/* ---------- 表格 ---------- */
.table-wrap {
  padding: 6px 0 0;
  overflow: hidden;
}

.grid-head,
.grid-row {
  display: grid;
  grid-template-columns: 92px minmax(0, 1.6fr) 74px 74px 104px 96px 168px;
  align-items: center;
  gap: 12px;
  padding: 12px 22px;
}

.grid-head {
  font-size: 12px;
  color: var(--text-3);
  border-bottom: 1px solid var(--card-border);
  padding-bottom: 10px;
}

.grid-row {
  border-bottom: 1px solid color-mix(in srgb, var(--card-border) 55%, transparent);
  transition: background var(--dur) var(--ease);
}

.grid-row:last-child {
  border-bottom: none;
}

.grid-row:hover {
  background: color-mix(in srgb, var(--primary) 3%, transparent);
}

.cell {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.ta-center {
  align-items: center;
  text-align: center;
}

.ta-right {
  align-items: flex-end;
  text-align: right;
}

/* ---------- 单元格内容 ---------- */
.scale-code {
  font-size: 12px;
  font-weight: 600;
  color: var(--primary);
  letter-spacing: 0.3px;
}

.scale-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 禁用态整行名称转灰：一眼能区分"这张表现在不对外" */
.scale-name.off {
  color: var(--text-3);
}

.scale-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.dim-tag {
  flex: none;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  color: var(--primary);
  background: var(--primary-soft);
}

.scale-desc {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.num {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.2;
}

.num-unit {
  font-size: 10px;
  color: var(--text-3);
}

.hist-chip {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  color: var(--risk-b-ink);
  background: var(--risk-b-soft);
  white-space: nowrap;
}

/* ---------- 状态 ---------- */
.state-chip {
  font-size: 11px;
  padding: 2px 9px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
}

.state-chip.on {
  color: var(--risk-a-ink);
  background: var(--risk-a-soft);
}

.state-chip.off {
  color: var(--text-3);
  background: color-mix(in srgb, var(--text-3) 12%, transparent);
}

/* ---------- 操作 ---------- */
.ops {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

/* ---------- 骨架 / 空态 ---------- */
.skeleton-row {
  height: 52px;
  margin: 10px 22px;
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--card-border) 40%, transparent) 25%,
    color-mix(in srgb, var(--card-border) 18%, transparent) 37%,
    color-mix(in srgb, var(--card-border) 40%, transparent) 63%
  );
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

@keyframes shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.list-empty {
  padding: 48px 0;
}

/* ---------- 底部说明 ---------- */
.foot-note {
  margin: 0;
  padding: 0 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-3);
}

.foot-note b {
  color: var(--text-2);
  font-weight: 600;
}
</style>
