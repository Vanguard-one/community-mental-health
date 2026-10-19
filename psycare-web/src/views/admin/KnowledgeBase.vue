<template>
  <div class="kb-page">
    <!-- ===== 概览条：两侧数字分开给 ===== -->
    <div class="summary-bar surface">
      <div class="summary-item">
        <span class="summary-label">文档</span>
        <span class="summary-value mono">{{ overview?.docCount ?? 0 }}</span>
        <span class="summary-unit">篇</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">已就绪</span>
        <span class="summary-value mono">{{ overview?.readyCount ?? 0 }}</span>
        <span class="summary-unit">篇</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">失败</span>
        <span
          class="summary-value mono"
          :class="{ warn: (overview?.failedCount ?? 0) > 0 }"
        >{{ overview?.failedCount ?? 0 }}</span>
        <span class="summary-unit">篇</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">MySQL 子块</span>
        <span class="summary-value mono">{{ overview?.childChunkCount ?? 0 }}</span>
      </div>
      <div class="summary-item">
        <span class="summary-label">Milvus 向量</span>
        <span
          class="summary-value mono"
          :class="{ warn: (overview?.milvusRows ?? -1) < 0 }"
        >{{ (overview?.milvusRows ?? -1) < 0 ? '—' : overview?.milvusRows }}</span>
      </div>
      <div class="summary-actions">
        <a-button :loading="probing" @click="doProbe">探测 Milvus</a-button>
        <a-button :loading="loading" @click="reload">刷新</a-button>
      </div>
    </div>

    <!--
      Milvus 不可用时给出「该去做什么」，而不是一句「索引不可用」。
      这一段的存在本身就是设计：概览页恰恰是用户排查 Milvus 的地方，
      它自己不能因为 Milvus 挂了就打不开 —— 所以后端 countAll 返回 -1 而不是抛异常。
    -->
    <a-alert
      v-if="overview && !overview.milvusOk"
      class="kb-alert"
      type="warning"
      show-icon
      :message="overview.milvusEnabled ? 'Milvus 不可用 —— 文档可以正常入库，但向量索引建不起来' : 'Milvus 已在配置中禁用'"
      :description="milvusHint"
    />

    <!-- ===== 工具条 ===== -->
    <div class="filter-bar surface">
      <div class="filter-fields">
        <a-input
          v-model:value="filters.keyword"
          class="f-keyword"
          placeholder="按标题搜索，回车查询"
          allow-clear
          @press-enter="search"
        />
        <a-select v-model:value="filters.status" class="f-status" :options="STATUS_OPTIONS" />
        <a-button type="primary" @click="search">查询</a-button>
        <a-button @click="resetFilters">重置</a-button>
      </div>
      <a-button type="primary" @click="openEditor(null)">新建 / 导入文档</a-button>
    </div>

    <!-- ===== 文档列表 ===== -->
    <div class="table-wrap surface">
      <div class="grid-head">
        <span>文档</span>
        <span>类型</span>
        <span>状态</span>
        <span class="ta-right">子块</span>
        <span>模型 / 维度</span>
        <span>时间</span>
        <span class="ta-right">操作</span>
      </div>

      <div v-if="loading" class="skeleton-wrap">
        <div v-for="i in 5" :key="i" class="skeleton-row" />
      </div>

      <a-empty v-else-if="rows.length === 0" class="list-empty" description="还没有知识库文档" />

      <template v-else>
        <div v-for="row in rows" :key="row.id" class="grid-row">
          <div class="cell">
            <span class="doc-title" :title="row.title">{{ row.title }}</span>
            <span class="doc-id mono">#{{ row.id }}</span>
            <span v-if="row.errorMsg" class="doc-err" :title="row.errorMsg">{{ row.errorMsg }}</span>
          </div>

          <div class="cell">
            <span class="type-chip" :class="`t-${row.sourceType}`">{{ SOURCE_DESC[row.sourceType] ?? row.sourceType }}</span>
            <span v-if="row.publishYear" class="cell-sub mono">{{ row.publishYear }}</span>
          </div>

          <div class="cell">
            <span class="status-chip" :class="`s-${row.status}`">{{ STATUS_DESC[row.status] ?? row.status }}</span>
          </div>

          <div class="cell ta-right mono">{{ row.chunkCount }}</div>

          <div class="cell">
            <span class="cell-sub mono">{{ row.embedModel || '—' }}</span>
            <span class="cell-sub mono">{{ row.dim ? row.dim + ' 维' : '' }}</span>
            <!-- 换过模型但没重建：这是最容易被忽略、后果又最严重的一种不一致 -->
            <span v-if="staleModel(row)" class="stale-chip" title="建索引用的模型与当前配置不一致，检索结果不可比">需重建</span>
          </div>

          <div class="cell mono cell-time">{{ shortTime(row.updatedAt || row.createdAt) }}</div>

          <div class="cell ta-right">
            <div class="ops">
              <a-button size="small" @click="openChunks(row)">分块</a-button>
              <a-popconfirm
                title="会先清空该文档在 Milvus 的向量，再从 MySQL 的分块重跑，确认？"
                ok-text="确认重建"
                cancel-text="取消"
                @confirm="doRebuild(row)"
              >
                <a-button size="small" type="primary">{{ row.status === 'FAILED' ? '重试' : '重建' }}</a-button>
              </a-popconfirm>
              <a-popconfirm
                title="将删除该文档、其全部分块与 Milvus 中的向量，确认？"
                ok-text="确认删除"
                cancel-text="取消"
                @confirm="doDelete(row)"
              >
                <a-button size="small" danger>删除</a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </template>

      <div v-if="total > 0" class="pager">
        <a-pagination
          v-model:current="page"
          v-model:page-size="size"
          :total="total"
          :page-size-options="['10', '20', '50']"
          show-size-changer
          @change="reload"
        />
      </div>
    </div>

    <!-- ===== 新建 / 导入 ===== -->
    <a-modal v-model:open="editor.open" title="新建 / 导入文档" :width="880" :footer="null" destroy-on-close>
      <div class="editor">
        <div class="editor-row">
          <span class="field-label">文件</span>
          <div class="field-body">
            <!--
              用原生 input 而不是上传组件：这里的「上传」其实只是「把文本读进浏览器」，
              文件内容由前端读成文本后随 JSON 提交，服务端不落文件。
              为这个动作引入上传组件（含进度、分片、拖拽）不划算。
            -->
            <input class="file-input" type="file" accept=".txt,.md,.jsonl,.json,.csv" @change="onFileChange" />
            <span class="hint">{{ editor.fileName || '未选择文件（也可以直接在下面粘贴文本）' }}</span>
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">标题</span>
          <div class="field-body">
            <a-input v-model:value="editor.title" placeholder="文档标题（引用卡片会展示它）" />
            <span v-if="isJsonl" class="hint">JSONL 语料：标题取自语料里的 doc_title，这里填的会被忽略</span>
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">类型</span>
          <div class="field-body field-inline">
            <a-select v-model:value="editor.sourceType" class="w-160" :options="SOURCE_OPTIONS" />
            <a-input v-model:value="editor.publishYear" class="w-140" placeholder="发布年份（可空）" />
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">切分</span>
          <div class="field-body field-inline">
            <a-input v-model:value="editor.childSize" class="w-110" placeholder="子块字数" />
            <a-input v-model:value="editor.parentSize" class="w-110" placeholder="父块字数" />
            <a-input v-model:value="editor.overlap" class="w-110" placeholder="重叠字数" />
            <a-button :loading="editor.previewing" @click="doPreview">预览切分</a-button>
            <span class="hint">留空走默认 400 / 1400 / 50；预览不调模型、不入库，零成本</span>
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">正文</span>
          <div class="field-body">
            <a-textarea v-model:value="editor.content" :rows="8" placeholder="粘贴正文，或在上方选择文件自动读入" />
          </div>
        </div>

        <!-- 预览结果 -->
        <div v-if="editor.preview" class="preview-box">
          <div class="preview-head">
            <span class="preview-stat">
              父块 <b class="mono">{{ editor.preview.parentCount }}</b> ·
              子块 <b class="mono">{{ editor.preview.childCount }}</b> ·
              子块合计 <b class="mono">{{ editor.preview.totalChars }}</b> 字 ·
              约 <b class="mono">{{ editor.preview.totalTokens }}</b> token
            </span>
            <span class="hint">归一化后全文 {{ editor.preview.normalizedChars }} 字</span>
          </div>
          <div class="preview-list">
            <div v-for="(s, i) in editor.preview.samples" :key="i" class="preview-item">
              <span class="preview-tag" :class="s.type === 'PARENT' ? 'p' : 'c'">
                {{ s.type === 'PARENT' ? '父' : '子' }} #{{ s.ordinal }}
              </span>
              <span class="preview-meta mono">{{ s.charCount }} 字</span>
              <span class="preview-section" :title="s.section">{{ s.section || '（无章节）' }}</span>
              <div class="preview-text">{{ s.preview }}</div>
            </div>
          </div>
        </div>

        <a-alert
          v-if="editor.error"
          class="kb-alert"
          type="error"
          show-icon
          :message="editor.error"
        />

        <div class="editor-footer">
          <a-button @click="editor.open = false">取消</a-button>
          <a-button type="primary" :loading="editor.submitting" @click="doSubmit">
            {{ isJsonl ? '批量导入' : '建档并建索引' }}
          </a-button>
        </div>
      </div>
    </a-modal>

    <!-- ===== 分块查看 ===== -->
    <a-modal v-model:open="chunks.open" :title="`分块 · ${chunks.docTitle}`" :width="880" :footer="null">
      <div class="chunks">
        <div class="chunks-bar">
          <a-select v-model:value="chunks.type" class="w-160" :options="CHUNK_TYPE_OPTIONS" @change="loadChunks" />
          <span class="hint">按 ordinal 升序 —— 与重建时拼回原文的顺序一致</span>
        </div>
        <div v-if="chunks.loading" class="skeleton-wrap">
          <div v-for="i in 5" :key="i" class="skeleton-row" />
        </div>
        <a-empty v-else-if="chunks.items.length === 0" description="没有分块" />
        <div v-else class="chunk-list">
          <div v-for="c in chunks.items" :key="c.id" class="chunk-item">
            <div class="chunk-head">
              <span class="preview-tag" :class="c.chunkType === 'PARENT' ? 'p' : 'c'">
                {{ c.chunkType === 'PARENT' ? '父' : '子' }} #{{ c.ordinal }}
              </span>
              <span class="chunk-meta mono">id={{ c.id }}{{ c.parentId ? ' · parent=' + c.parentId : '' }}</span>
              <span class="chunk-meta mono">{{ c.charCount }} 字 · {{ c.tokenCount }} tk</span>
              <span class="chunk-section" :title="c.section || ''">{{ c.section || '' }}</span>
            </div>
            <div class="chunk-text">{{ c.preview }}</div>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createKbDocument,
  deleteKbDocument,
  getKnowledgeOverview,
  importKbCorpus,
  listKbChunks,
  listKbDocuments,
  previewKnowledge,
  probeMilvus,
  rebuildKbDocument,
} from '@/api/knowledge'
import type {
  KbChunkItem,
  KbDocument,
  KnowledgeOverview,
  KnowledgePreview,
  KnowledgeRequest,
} from '@/types'

/* ==================== 常量 ==================== */

const STATUS_DESC: Record<string, string> = {
  PENDING: '待处理',
  INDEXING: '索引中',
  READY: '已就绪',
  FAILED: '失败',
}

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'READY', label: '已就绪' },
  { value: 'FAILED', label: '失败' },
  { value: 'INDEXING', label: '索引中' },
]

const SOURCE_DESC: Record<string, string> = {
  SYNTHETIC: '合成语料',
  AUTHORITATIVE: '权威资料',
  UPLOAD: '人工上传',
}

const SOURCE_OPTIONS = [
  { value: 'SYNTHETIC', label: '合成语料（仅验链路）' },
  { value: 'AUTHORITATIVE', label: '权威资料' },
  { value: 'UPLOAD', label: '人工上传' },
]

const CHUNK_TYPE_OPTIONS = [
  { value: '', label: '全部分块' },
  { value: 'PARENT', label: '只看父块' },
  { value: 'CHILD', label: '只看子块' },
]

/* ==================== 列表 ==================== */

const loading = ref(false)
const probing = ref(false)
const overview = ref<KnowledgeOverview | null>(null)
const rows = ref<KbDocument[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const filters = reactive({ keyword: '', status: '' })

async function reload() {
  loading.value = true
  try {
    const [ov, pg] = await Promise.all([
      getKnowledgeOverview(),
      listKbDocuments({
        page: page.value,
        size: size.value,
        keyword: filters.keyword || undefined,
        status: filters.status || undefined,
      }),
    ])
    overview.value = ov
    rows.value = pg.records ?? []
    total.value = Number(pg.total ?? 0)
  } catch {
    // 拦截器已经弹过提示，这里只保证界面不停在 loading
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  reload()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  search()
}

async function doProbe() {
  probing.value = true
  try {
    const r = await probeMilvus()
    message[r.ok ? 'success' : 'warning'](String(r.state ?? '探测完成'))
    reload()
  } catch {
    // 已提示
  } finally {
    probing.value = false
  }
}

/** 「建索引用的模型」与「当前配置的模型」不一致 → 检索结果不可比，必须重建 */
function staleModel(row: KbDocument): boolean {
  if (!overview.value || !row.embedModel) return false
  return row.embedModel !== overview.value.embedModel || row.dim !== overview.value.dim
}

function shortTime(t: string | null): string {
  if (!t) return '—'
  return t.length >= 16 ? t.slice(5, 16) : t
}

const milvusHint = computed(() => {
  const ov = overview.value
  if (!ov) return ''
  if (!ov.milvusEnabled) {
    return 'psycare.milvus.enabled=false。文档会正常入库（MySQL 侧），但不会建立向量索引，Phase 4 的检索也就拿不到内容。'
  }
  return `当前状态：${ov.milvusState}。常见原因：容器没起（本机脚本 E:\\Milvus\\standalone.bat）、19530 被占用、或鉴权 token 配置不当。文档仍会入库，恢复 Milvus 后点「重建」即可补齐向量。`
})

/* ==================== 新建 / 导入 ==================== */

const editor = reactive({
  open: false,
  title: '',
  fileName: '',
  content: '',
  sourceType: 'SYNTHETIC',
  publishYear: '',
  childSize: '',
  parentSize: '',
  overlap: '',
  previewing: false,
  submitting: false,
  preview: null as KnowledgePreview | null,
  error: '',
})

const isJsonl = computed(() => editor.fileName.toLowerCase().endsWith('.jsonl'))

function openEditor(_row: KbDocument | null) {
  editor.open = true
  editor.error = ''
  editor.preview = null
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  if (!f) return
  const lower = f.name.toLowerCase()
  // 与服务端同一个边界：PDF/DOCX 不在服务端解析。
  // 与其让用户传上去再收一个 400，不如在这里就说清为什么。
  if (lower.endsWith('.pdf') || lower.endsWith('.docx') || lower.endsWith('.doc')) {
    editor.error = 'PDF / DOCX 需要在离线工具里转成 .txt / .md 再上传：中文 PDF 的双栏错行与页眉页脚必须人工抽页比对后才可信。'
    return
  }
  editor.fileName = f.name
  editor.error = ''
  if (!editor.title) {
    editor.title = f.name.replace(/\.[^.]+$/, '')
  }
  const reader = new FileReader()
  reader.onload = () => {
    editor.content = String(reader.result ?? '')
    message.success(`已读入 ${f.name}（${editor.content.length} 字）`)
    // 重置 input 的值：否则再次选择**同一个文件**不会触发 change 事件
    input.value = ''
  }
  reader.onerror = () => {
    editor.error = '文件读取失败，请改用粘贴文本的方式'
  }
  reader.readAsText(f, 'utf-8')
}

function payload(): KnowledgeRequest {
  const num = (s: string) => (s.trim() === '' ? undefined : Number(s))
  return {
    title: editor.title,
    content: editor.content,
    fileName: editor.fileName,
    sourceType: editor.sourceType,
    publishYear: num(editor.publishYear),
    childSize: num(editor.childSize),
    parentSize: num(editor.parentSize),
    overlap: num(editor.overlap),
  }
}

async function doPreview() {
  if (!editor.content.trim()) {
    editor.error = '请先粘贴正文或选择文件'
    return
  }
  editor.previewing = true
  editor.error = ''
  try {
    editor.preview = await previewKnowledge(payload())
  } catch {
    editor.preview = null
  } finally {
    editor.previewing = false
  }
}

async function doSubmit() {
  if (!editor.content.trim()) {
    editor.error = '请先粘贴正文或选择文件'
    return
  }
  editor.submitting = true
  editor.error = ''
  try {
    if (isJsonl.value) {
      const r = await importKbCorpus(payload())
      message[r.status === 'READY' ? 'success' : 'warning'](r.message || '导入完成')
    } else {
      const r = await createKbDocument(payload())
      if (r.status === 'READY') {
        message.success(`已索引：父块 ${r.parentCount} / 子块 ${r.childCount} / 向量 ${r.milvusRows}`)
      } else {
        message.warning(r.message || '索引未完全成功')
      }
    }
    editor.open = false
    search()
  } catch {
    // 拦截器已提示
  } finally {
    editor.submitting = false
  }
}

/* ==================== 重建 / 删除 ==================== */

async function doRebuild(row: KbDocument) {
  try {
    const r = await rebuildKbDocument(row.id)
    message[r.status === 'READY' ? 'success' : 'warning'](
      r.status === 'READY'
        ? `重建完成：子块 ${r.childCount} / 向量 ${r.milvusRows}`
        : r.message || '重建未完全成功',
    )
    reload()
  } catch {
    // 已提示
  }
}

async function doDelete(row: KbDocument) {
  try {
    await deleteKbDocument(row.id)
    message.success('已删除')
    reload()
  } catch {
    // 已提示
  }
}

/* ==================== 分块 ==================== */

const chunks = reactive({
  open: false,
  docId: 0,
  docTitle: '',
  type: '',
  items: [] as KbChunkItem[],
  loading: false,
})

async function openChunks(row: KbDocument) {
  chunks.open = true
  chunks.docId = row.id
  chunks.docTitle = row.title
  chunks.type = ''
  await loadChunks()
}

async function loadChunks() {
  chunks.loading = true
  try {
    chunks.items = await listKbChunks(chunks.docId, chunks.type || undefined, 100)
  } catch {
    chunks.items = []
  } finally {
    chunks.loading = false
  }
}

onMounted(reload)
</script>

<style scoped>
.kb-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ---- 概览条 ---- */
.summary-bar {
  display: flex;
  align-items: center;
  gap: 26px;
  padding: 14px 18px;
  flex-wrap: wrap;
}

.summary-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.summary-label {
  font-size: 12px;
  color: var(--text-3);
}

.summary-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}

/* 数值异常（失败数、Milvus 不可用）用危机红 —— 一律取 token，不写死色值 */
.summary-value.warn {
  color: var(--risk-d);
}

.summary-unit {
  font-size: 12px;
  color: var(--text-3);
}

.summary-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.kb-alert {
  margin: 0;
}

/* ---- 筛选条 ---- */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  justify-content: space-between;
  flex-wrap: wrap;
}

.filter-fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.f-keyword {
  width: 260px;
}

.f-status {
  width: 130px;
}

/* ---- 列表 ---- */
.table-wrap {
  padding: 0 0 8px;
  overflow: hidden;
}

.grid-head,
.grid-row {
  display: grid;
  grid-template-columns: minmax(220px, 2fr) 130px 100px 70px 150px 110px 250px;
  align-items: center;
  gap: 10px;
  padding: 11px 18px;
}

.grid-head {
  font-size: 12px;
  color: var(--text-3);
  border-bottom: 1px solid var(--card-border);
  background: var(--panel-bg);
}

.grid-row {
  border-bottom: 1px solid var(--card-border);
  font-size: 13px;
  color: var(--text-2);
}

.grid-row:hover {
  background: var(--panel-bg);
}

.ta-right {
  text-align: right;
}

.cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.cell.ta-right {
  align-items: flex-end;
}

.cell-sub {
  font-size: 11px;
  color: var(--text-3);
}

.doc-title {
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-id {
  font-size: 11px;
  color: var(--text-3);
}

/* 失败原因直接显示在行里：让它需要 hover 才能看到，就等于没人会看到 */
.doc-err {
  font-size: 11px;
  color: var(--risk-d);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-time {
  font-size: 12px;
  color: var(--text-3);
}

.ops {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

/* ---- 各类 chip ---- */
.type-chip,
.status-chip,
.stale-chip {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
  align-self: flex-start;
}

.type-chip {
  background: var(--primary-soft);
  color: var(--primary);
}

.type-chip.t-SYNTHETIC {
  background: color-mix(in srgb, var(--accent-purple) 14%, transparent);
  color: var(--accent-purple);
}

/* 状态色一律复用风险色阶（低风险青 / 中风险橙 / 危机红），不另起一套语义色 */
.status-chip.s-READY {
  background: var(--risk-a-soft);
  color: var(--risk-a);
}

.status-chip.s-FAILED {
  background: var(--risk-d-soft);
  color: var(--risk-d);
}

.status-chip.s-INDEXING {
  background: var(--risk-b-soft);
  color: var(--risk-b);
}

.status-chip.s-PENDING {
  background: var(--panel-bg);
  color: var(--text-3);
}

.stale-chip {
  background: var(--risk-b-soft);
  color: var(--risk-b);
}

/* ---- 骨架 / 空态 ---- */
.skeleton-wrap {
  padding: 10px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.skeleton-row {
  height: 34px;
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--panel-bg) 25%, var(--card-border) 37%, var(--panel-bg) 63%);
  background-size: 400% 100%;
  animation: kb-shimmer 1.4s ease infinite;
}

@keyframes kb-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

.list-empty {
  padding: 40px 0;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px 4px;
}

/* ---- 新建 / 导入 ---- */
.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.field-label {
  flex: none;
  width: 44px;
  padding-top: 6px;
  font-size: 13px;
  color: var(--text-3);
}

.field-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-inline {
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 0;
}

.w-160 { width: 160px; }
.w-140 { width: 140px; }
.w-110 { width: 110px; }

.file-input {
  font-size: 12px;
  color: var(--text-2);
}

.hint {
  font-size: 11px;
  color: var(--text-3);
}

.preview-box {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: var(--panel-bg);
}

.preview-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: var(--text-2);
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.preview-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 280px;
  overflow-y: auto;
}

.preview-item {
  border-left: 2px solid var(--card-border);
  padding: 4px 0 4px 10px;
}

.preview-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  margin-right: 6px;
}

.preview-tag.p {
  background: color-mix(in srgb, var(--primary) 14%, transparent);
  color: var(--primary);
}

.preview-tag.c {
  background: var(--panel-bg);
  color: var(--text-3);
  border: 1px solid var(--card-border);
}

.preview-meta,
.chunk-meta {
  font-size: 11px;
  color: var(--text-3);
  margin-right: 8px;
}

.preview-section,
.chunk-section {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-text,
.chunk-text {
  font-size: 12px;
  color: var(--text-2);
  margin-top: 4px;
  line-height: 1.6;
}

.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}

/* ---- 分块 ---- */
.chunks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chunks-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 60vh;
  overflow-y: auto;
}

.chunk-item {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}

.chunk-head {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

/* 窄屏（如 ≤1280）下操作列被裁是历史遗留问题（见记忆待办），
   这里让它整行换行而不是横向溢出，至少保证按钮点得到。 */
@media (max-width: 1400px) {
  .grid-head,
  .grid-row {
    grid-template-columns: minmax(180px, 2fr) 110px 90px 60px 130px 100px 230px;
  }
}
</style>
