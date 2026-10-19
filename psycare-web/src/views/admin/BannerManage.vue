<template>
  <div class="banner-page">
    <!-- ===== 工具条 ===== -->
    <div class="toolbar surface">
      <div class="toolbar-left">
        <h2 class="page-title font-serif">首页轮播管理</h2>
        <span class="page-sub">
          共 <b class="mono">{{ rows.length }}</b> 条 ·
          启用中 <b class="mono">{{ enabledCount }}</b> 条
        </span>
      </div>
      <div class="toolbar-right">
        <a-button :loading="loading" @click="reload">刷新</a-button>
        <a-button type="primary" @click="openEditor(null)">新增 banner</a-button>
      </div>
    </div>

    <!--
      当前正在首页展示的条数。这个提示是必要的：
      「启用了」不等于「看得到」—— 还要在时间窗口内。运营配完发现首页没有，
      第一个要回答的就是这两者的差，所以直接算好摆在这里。
    -->
    <a-alert
      v-if="rows.length && visibleCount === 0"
      class="hint-alert"
      type="warning"
      show-icon
      message="当前没有 banner 会在首页展示"
      description="所有条目要么已停用，要么不在展示时间窗口内。请检查「状态」与「展示时间」。"
    />

    <!-- ===== 列表 ===== -->
    <div class="table-wrap surface">
      <div class="grid-head">
        <span class="ta-center">排序</span>
        <span>预览</span>
        <span>标题 / 链接</span>
        <span>展示时间</span>
        <span>状态</span>
        <span class="ta-right">操作</span>
      </div>

      <div v-if="loading" class="skeleton-wrap">
        <div v-for="i in 3" :key="i" class="skeleton-row" />
      </div>

      <a-empty v-else-if="rows.length === 0" class="list-empty" description="还没有 banner，点右上角新增" />

      <template v-else>
        <div v-for="(row, i) in rows" :key="row.id" class="grid-row">
          <!-- 排序：上下移 -->
          <div class="cell ta-center">
            <div class="sort-ops">
              <button
                class="sort-btn"
                type="button"
                title="上移"
                :disabled="i === 0 || moving"
                @click="doMove(row, 'up')"
              >
                <UpOutlined />
              </button>
              <span class="sort-num mono">{{ row.sort }}</span>
              <button
                class="sort-btn"
                type="button"
                title="下移"
                :disabled="i === rows.length - 1 || moving"
                @click="doMove(row, 'down')"
              >
                <DownOutlined />
              </button>
            </div>
          </div>

          <!-- 图片预览 -->
          <div class="cell">
            <div class="thumb">
              <img v-if="!brokenIds.has(row.id)" :src="row.imageUrl" :alt="row.title" @error="markBroken(row.id)" />
              <div v-else class="thumb-broken" title="图片加载失败">
                <PictureOutlined />
              </div>
            </div>
          </div>

          <!-- 标题 + 链接 -->
          <div class="cell">
            <span class="banner-title" :title="row.title">{{ row.title }}</span>
            <span v-if="row.linkUrl" class="banner-link mono" :title="row.linkUrl">{{ row.linkUrl }}</span>
            <span v-else class="banner-nolink">不可点击</span>
          </div>

          <!-- 展示时间 -->
          <div class="cell">
            <span class="time-range mono">{{ timeRange(row) }}</span>
            <span v-if="windowState(row) !== 'in'" class="win-chip" :class="windowState(row)">
              {{ windowState(row) === 'future' ? '未开始' : '已过期' }}
            </span>
          </div>

          <!-- 状态 -->
          <div class="cell">
            <label class="toggle-line">
              <input
                type="checkbox"
                :checked="row.status === 1"
                :disabled="togglingId === row.id"
                @change="doToggle(row)"
              />
              <span class="toggle-text" :class="{ on: row.status === 1 }">
                {{ row.status === 1 ? '启用' : '停用' }}
              </span>
            </label>
          </div>

          <!-- 操作 -->
          <div class="cell ta-right">
            <div class="ops">
              <a-button size="small" @click="openEditor(row)">编辑</a-button>
              <a-popconfirm
                title="删除后首页立即不再展示，确认？"
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
    </div>

    <!-- ===== 新增 / 编辑弹窗 ===== -->
    <a-modal
      v-model:open="editor.open"
      :title="editor.id ? `编辑 banner #${editor.id}` : '新增 banner'"
      :width="620"
      :confirm-loading="editor.saving"
      ok-text="保存"
      cancel-text="取消"
      destroy-on-close
      @ok="doSave"
    >
      <div class="editor">
        <div class="editor-row">
          <span class="field-label req">标题</span>
          <div class="field-body">
            <a-input v-model:value="editor.title" placeholder="如：秋季心理健康筛查月" :maxlength="128" />
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label req">图片</span>
          <div class="field-body">
            <!--
              用原生 input + 自定义上传，而不是 a-upload：
              1) a-upload 会引入一批依赖（含进度条、拖拽），而 vendor-antd 有 800 kB 预算；
              2) 这里只需要「选文件 → 立刻上传 → 回填 URL」三步，自定义 action 反而更直白。
            -->
            <div class="upload-line">
              <input
                ref="fileRef"
                class="file-input"
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                @change="onPickFile"
              />
              <a-button size="small" :loading="editor.uploading" @click="pickFile">
                {{ editor.uploading ? '上传中…' : '选择图片' }}
              </a-button>
              <span class="hint">
                支持 jpg / png / gif / webp，不超过 5 MB；选定后会先进入裁剪，输出
                <b class="mono">{{ BANNER_OUT_W }}×{{ BANNER_OUT_H }}</b>（2.5:1）
              </span>
            </div>
            <div v-if="editor.imageUrl" class="upload-preview">
              <img :src="editor.imageUrl" alt="预览" />
              <span class="upload-url mono" :title="editor.imageUrl">{{ editor.imageUrl }}</span>
            </div>
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">跳转链接</span>
          <div class="field-body">
            <a-input v-model:value="editor.linkUrl" placeholder="站内路径如 /scales，或完整 https:// 链接；留空则不可点击" />
          </div>
        </div>

        <div class="editor-row">
          <span class="field-label">展示时间</span>
          <div class="field-body field-inline">
            <!--
              ⚠️ 刻意用原生 datetime-local 而不是 a-range-picker：
              a-range-picker 会把整棵 DatePicker（日历面板 + 时间列 + dayjs 适配层）
              拉进 vendor-antd，实测 **+135 kB**，直接顶爆 800 kB 预算
              （这与 Register.vue 里「用 a-input 代替 a-input-number」是同一条约定）。
              datetime-local 的原生控件还自带系统级日期选择器，体验并不差。
            -->
            <input v-model="editor.startAt" class="dt-input" type="datetime-local" />
            <span class="dt-sep">至</span>
            <input v-model="editor.endAt" class="dt-input" type="datetime-local" />
            <button class="link-btn" type="button" @click="clearRange">清空</button>
          </div>
          <span class="hint">两端都留空 = 永久展示。结束时间早于开始时间会被后端拒绝</span>
        </div>

        <div class="editor-row">
          <span class="field-label">排序</span>
          <div class="field-body field-inline">
            <!-- 原生 number input：理由同上，省掉 a-input-number -->
            <input v-model="editor.sort" class="num-input" type="number" min="0" placeholder="留空自动排到末尾" />
            <!-- 用 checkbox 而不是 a-switch：本页唯一的开关场景，不值得为它引入一个组件 -->
            <label class="check-line">
              <input v-model="editor.enabled" type="checkbox" />
              <span>启用（关闭则首页不展示）</span>
            </label>
          </div>
        </div>

        <a-alert v-if="editor.error" type="error" show-icon :message="editor.error" />
      </div>
    </a-modal>

    <!--
      裁剪弹窗。放在编辑弹窗之外、作为兄弟节点，而不是嵌在里面：
      a-modal 嵌套时 z-index 与遮罩层会互相盖住（内层弹窗的遮罩把外层内容也压暗），
      而且关闭顺序容易错乱。两者平级，用 open 状态串起流程最稳。
    -->
    <ImageCropper
      v-model:open="cropper.open"
      :src="cropper.src"
      :ratio="BANNER_RATIO"
      :out-width="BANNER_OUT_W"
      @cropped="onCropped"
      @cancel="onCropCancel"
    />
  </div>
</template>

<script setup lang="ts">
/*
 * 管理端 · 首页轮播管理。
 *
 * 五项能力对应五个接口（新增/编辑/删除/排序/启停），其中「排序」用的是
 * **相邻交换**而不是拖拽 —— 项目没有引入拖拽库，且拖拽提交整个顺序数组
 * 在并发编辑时会互相覆盖（见后端 BannerService.move 的注释）。
 *
 * 权限：后端每个 /banner 写接口都独立断言 ADMIN（硬约定 #18）。
 * 本页对应路由 meta.roles = ['ADMIN']，菜单也只对管理员出现，但那些都只是
 * 「不给入口」，不是安全边界。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { DownOutlined, PictureOutlined, UpOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import ImageCropper from '@/components/ImageCropper.vue'
import {
  createBanner,
  deleteBanner,
  listBanners,
  moveBanner,
  toggleBannerStatus,
  updateBanner,
  uploadBannerImage,
} from '@/api/banner'
import type { Banner } from '@/types'

/*
 * 轮播尺寸常量。必须与 tokens.css 的 --banner-ratio 与后端
 * UploadProperties.targetRatio 保持一致（三处同源，改动要一起改）。
 * 这里写成 TS 常量而不是读 CSS 变量：裁剪器要的是 JS 数值，
 * 读 CSS 变量得跨组件取计算样式，反而脆弱。
 */
const BANNER_RATIO = 2.5
const BANNER_OUT_W = 1200
const BANNER_OUT_H = 480

const loading = ref(false)
const rows = ref<Banner[]>([])
const moving = ref(false)
const togglingId = ref<number | null>(null)
const brokenIds = ref<Set<number>>(new Set())

const enabledCount = computed(() => rows.value.filter((r) => r.status === 1).length)

/** 真正会出现在首页的条数：启用中 **且** 在时间窗口内 */
const visibleCount = computed(() => rows.value.filter((r) => r.status === 1 && windowState(r) === 'in').length)

function markBroken(id: number) {
  brokenIds.value = new Set([...brokenIds.value, id])
}

/* ==================== 时间窗口判定 ==================== */

type WindowState = 'in' | 'future' | 'past'

/**
 * 判断某条 banner 相对「现在」处于哪个阶段。
 *
 * 前端这份判定**只用于管理端打标签**（「未开始」/「已过期」），
 * 首页的过滤完全由后端 `/banner/active` 负责 —— 不能让客户端时间决定
 * 展示与否（用户改本机时间就会看到不该看的内容）。
 */
function windowState(row: Banner): WindowState {
  const now = dayjs()
  if (row.startAt && dayjs(row.startAt).isAfter(now)) return 'future'
  if (row.endAt && dayjs(row.endAt).isBefore(now)) return 'past'
  return 'in'
}

function timeRange(row: Banner): string {
  if (!row.startAt && !row.endAt) return '永久展示'
  const s = row.startAt ? dayjs(row.startAt).format('YYYY-MM-DD HH:mm') : '立即'
  const e = row.endAt ? dayjs(row.endAt).format('YYYY-MM-DD HH:mm') : '永久'
  return `${s} ~ ${e}`
}

/* ==================== 列表 ==================== */

async function reload() {
  loading.value = true
  try {
    rows.value = (await listBanners()) ?? []
    brokenIds.value = new Set()
  } finally {
    loading.value = false
  }
}

async function doMove(row: Banner, direction: 'up' | 'down') {
  moving.value = true
  try {
    // 后端返回重排后的完整列表，直接整体替换 —— 不做「本地先猜再回滚」，
    // 那样在 sort 有重复值时会与后端结果不一致（后端有重排兜底逻辑）。
    rows.value = (await moveBanner(row.id, direction)) ?? []
  } finally {
    moving.value = false
  }
}

async function doToggle(row: Banner) {
  togglingId.value = row.id
  try {
    const updated = await toggleBannerStatus(row.id)
    const idx = rows.value.findIndex((r) => r.id === row.id)
    if (idx >= 0 && updated) rows.value[idx] = updated
    message.success(updated?.status === 1 ? '已启用' : '已停用')
  } finally {
    togglingId.value = null
  }
}

async function doDelete(row: Banner) {
  await deleteBanner(row.id)
  message.success('已删除')
  await reload()
}

/* ==================== 编辑 ==================== */

interface EditorState {
  open: boolean
  id: number | null
  title: string
  imageUrl: string
  linkUrl: string
  /** 原生 number input 绑的是字符串（空串代表「未填」），提交前再转数字 */
  sort: string
  enabled: boolean
  /** datetime-local 的值：格式 `YYYY-MM-DDTHH:mm`，空串代表「未设」 */
  startAt: string
  endAt: string
  saving: boolean
  uploading: boolean
  error: string
}

const editor = reactive<EditorState>({
  open: false,
  id: null,
  title: '',
  imageUrl: '',
  linkUrl: '',
  sort: '',
  enabled: true,
  startAt: '',
  endAt: '',
  saving: false,
  uploading: false,
  error: '',
})

const fileRef = ref<HTMLInputElement | null>(null)

/**
 * `datetime-local` 要求 `YYYY-MM-DDTHH:mm`（**没有秒、没有时区**），
 * 而后端返回的是 `YYYY-MM-DD HH:mm:ss`（空格分隔、带秒）。
 * 这两个格式必须显式互转 —— 直接把后端的字符串塞进 input，控件会显示为空，
 * 表现是「编辑时时间总是空的，一保存就被清掉」。
 */
function toInputValue(s: string | null): string {
  if (!s) return ''
  return dayjs(s).format('YYYY-MM-DDTHH:mm')
}

/** 提交给后端要带秒，否则 MySQL 的 DATETIME 会补 00 秒（不影响功能，但保持一致更好） */
function fromInputValue(s: string): string | null {
  return s ? dayjs(s).format('YYYY-MM-DD HH:mm:ss') : null
}

function openEditor(row: Banner | null) {
  editor.open = true
  editor.error = ''
  editor.id = row?.id ?? null
  editor.title = row?.title ?? ''
  editor.imageUrl = row?.imageUrl ?? ''
  editor.linkUrl = row?.linkUrl ?? ''
  editor.sort = row?.sort === undefined || row?.sort === null ? '' : String(row.sort)
  editor.enabled = row ? row.status === 1 : true
  editor.startAt = toInputValue(row?.startAt ?? null)
  editor.endAt = toInputValue(row?.endAt ?? null)
  // 每次打开都清掉上一次残留的文件选择（input 的值不会自动重置，
  // 否则「选同一张图第二次不触发 change」）
  if (fileRef.value) fileRef.value.value = ''
}

function clearRange() {
  editor.startAt = ''
  editor.endAt = ''
}

function pickFile() {
  fileRef.value?.click()
}

/* ==================== 裁剪 → 上传 ==================== */

interface CropperState {
  open: boolean
  /** 待裁剪图片的 blob: URL。用完必须 revoke，否则整页会话里每个选过的文件都驻留内存 */
  src: string
}

const cropper = reactive<CropperState>({ open: false, src: '' })

/**
 * 选文件 → **不直接上传** → 先开裁剪弹窗。
 *
 * 为什么必须先裁剪再上传，而不是「原图直传 + 后端裁」：
 * 后端裁需要引入图片处理库（ImageIO 能读不能高质量缩放），且会浪费一次
 * 原图上传的带宽与存储。在浏览器里裁好再传，顺带把 5 MB 的原图压成
 * 一两百 KB 的 1200×480 —— 省流量、省存储、图库干净。
 *
 * 前端裁剪是**体验优化，不是安全边界**：后端 UploadService 仍会独立校验比例
 * （硬约定 #18 的同一条精神 —— 前端能做的一切都能被绕过）。
 */
function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 立刻重置选择器：否则「再选同一张图」不会触发 change，
  // 用户会觉得「点了没反应」（尤其在上一次取消之后）。
  input.value = ''
  if (!file) return

  // 先做本地快速校验，避免让用户裁半天才被后端拒
  if (!file.type.startsWith('image/')) {
    message.error('请选择图片文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    message.error('原图不要超过 10 MB')
    return
  }

  revokeCropperSrc()
  cropper.src = URL.createObjectURL(file)
  cropper.open = true
}

function revokeCropperSrc() {
  if (cropper.src) {
    URL.revokeObjectURL(cropper.src)
    cropper.src = ''
  }
}

/** 裁剪确认：拿到 1200×480 的 jpeg，直接上传 */
async function onCropped({ file }: { blob: Blob; file: File }) {
  cropper.open = false
  editor.uploading = true
  editor.error = ''
  try {
    const res = await uploadBannerImage(file)
    editor.imageUrl = res.url
    message.success('图片已裁剪并上传')
  } catch {
    // 具体原因（格式/大小/比例/权限）已由 request.ts 统一弹出
  } finally {
    revokeCropperSrc()
    editor.uploading = false
  }
}

function onCropCancel() {
  revokeCropperSrc()
}

async function doSave() {
  editor.error = ''
  if (!editor.title.trim()) {
    editor.error = '请填写标题'
    return
  }
  if (!editor.imageUrl) {
    editor.error = '请上传图片'
    return
  }

  const start = fromInputValue(editor.startAt)
  const end = fromInputValue(editor.endAt)
  // 后端也会校验，这里提前拦一道是为了不让用户白等一次往返
  if (start && end && dayjs(end).isBefore(dayjs(start))) {
    editor.error = '结束时间不能早于开始时间'
    return
  }

  const payload = {
    title: editor.title.trim(),
    imageUrl: editor.imageUrl,
    linkUrl: editor.linkUrl.trim() || null,
    // 空串 → undefined：让后端走「自动排到末尾」，而不是被塞成 0 抢到第一位
    sort: editor.sort.trim() === '' ? undefined : Number(editor.sort),
    status: editor.enabled ? 1 : 0,
    startAt: start,
    endAt: end,
  }

  editor.saving = true
  try {
    if (editor.id) {
      await updateBanner(editor.id, payload)
      message.success('已保存')
    } else {
      await createBanner(payload)
      message.success('已新增')
    }
    editor.open = false
    await reload()
  } catch {
    // 失败原因已由 request.ts 弹过；这里保持弹窗打开，让用户能改完重试
  } finally {
    editor.saving = false
  }
}

onMounted(reload)

// 切走路由时若还留着 blob: URL，浏览器的内存不会自己释放（它只在你 revoke
// 或页面卸载时释放）。SPA 里页面「卸载」和标签页关闭不是一回事，必须显式清。
onBeforeUnmount(revokeCropperSrc)
</script>

<style scoped>
.banner-page {
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
  grid-template-columns: 92px 130px minmax(0, 1.4fr) minmax(0, 1.2fr) 110px 150px;
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

.ta-right {
  align-items: flex-end;
  text-align: right;
}

.ta-center {
  align-items: center;
  text-align: center;
}

/* ---------- 排序 ---------- */
.sort-ops {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
}

.sort-btn {
  width: 22px;
  height: 18px;
  border: 1px solid var(--card-border);
  border-radius: 4px;
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0;
  transition: all var(--dur) var(--ease);
}

.sort-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

.sort-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.sort-num {
  font-size: 10px;
  color: var(--text-3);
}

/* ---------- 缩略图 ---------- */
.thumb {
  width: 120px;
  height: 62px;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--card-border);
  background: var(--primary-soft);
  flex: none;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.thumb-broken {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-3);
  font-size: 18px;
}

/* ---------- 文本单元 ---------- */
.banner-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.banner-link {
  font-size: 11px;
  color: var(--primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.banner-nolink {
  font-size: 11px;
  color: var(--text-3);
}

.time-range {
  font-size: 11px;
  color: var(--text-2);
  line-height: 1.5;
}

.win-chip {
  align-self: flex-start;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
}

.win-chip.future {
  color: var(--risk-b);
  background: color-mix(in srgb, var(--risk-b) 14%, transparent);
}

.win-chip.past {
  color: var(--text-3);
  background: color-mix(in srgb, var(--text-3) 13%, transparent);
}

/* ---------- 列表内启停（原生 checkbox） ---------- */
.toggle-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}

.toggle-line input {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  cursor: pointer;
  flex: none;
}

.toggle-line input:disabled {
  cursor: wait;
  opacity: 0.5;
}

.toggle-text {
  font-size: 12px;
  color: var(--text-3);
}

.toggle-text.on {
  color: var(--primary);
  font-weight: 600;
}

.ops {
  display: flex;
  gap: 6px;
}

/* ---------- 骨架 / 空态 ---------- */
.skeleton-wrap {
  padding: 0 22px 12px;
}

.skeleton-row {
  height: 62px;
  margin: 12px 0;
  border-radius: 6px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--card-border) 45%, transparent) 25%,
    color-mix(in srgb, var(--card-border) 20%, transparent) 37%,
    color-mix(in srgb, var(--card-border) 45%, transparent) 63%
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
  padding: 44px 0;
}

/* ---------- 编辑器 ---------- */
.editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}

.editor-row {
  display: flex;
  gap: 14px;
}

.field-label {
  width: 68px;
  flex: none;
  font-size: 13px;
  color: var(--text-2);
  line-height: 32px;
  text-align: right;
}

.field-label.req::after {
  content: ' *';
  color: var(--risk-d);
}

.field-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-inline {
  flex-direction: row;
  align-items: center;
}

.w-140 {
  width: 140px;
}

.w-full {
  width: 100%;
}

/* ---------- 原生控件（替代 a-range-picker / a-input-number / a-switch） ---------- */

/*
 * 这三个控件用原生实现是**有意的取舍**：a-range-picker 会把整棵 DatePicker
 * 拉进 vendor-antd（实测 +135 kB，顶爆 800 kB 预算）。样式上尽量贴近 antd 的观感，
 * 让它们在同一张表单里不显得突兀。
 */
.dt-input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text-1);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 6px;
  outline: none;
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}

.dt-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 15%, transparent);
}

.dt-sep {
  font-size: 12px;
  color: var(--text-3);
  flex: none;
}

.link-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: var(--primary);
  cursor: pointer;
  flex: none;
}

.link-btn:hover {
  text-decoration: underline;
}

.num-input {
  width: 140px;
  height: 32px;
  padding: 0 10px;
  font-size: 13px;
  font-family: inherit;
  color: var(--text-1);
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 6px;
  outline: none;
}

.num-input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 15%, transparent);
}

.check-line {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}

.check-line input {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  cursor: pointer;
  flex: none;
}

.hint {
  font-size: 11px;
  color: var(--text-3);
  line-height: 1.5;
}

.upload-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-input {
  /* 隐藏原生控件但保留可被 JS 触发（display:none 在部分浏览器下无法 .click()） */
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.upload-preview {
  display: flex;
  align-items: center;
  gap: 10px;
}

.upload-preview img {
  width: 96px;
  height: 50px;
  object-fit: cover;
  border-radius: 5px;
  border: 1px solid var(--card-border);
  flex: none;
}

.upload-url {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1100px) {
  .grid-head,
  .grid-row {
    grid-template-columns: 80px 110px minmax(0, 1.2fr) minmax(0, 1fr) 100px 130px;
    padding: 12px 16px;
  }

  .thumb {
    width: 100px;
    height: 54px;
  }
}

@media (max-width: 860px) {
  .toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .grid-head {
    display: none;
  }

  .grid-row {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .ta-right,
  .ta-center {
    align-items: flex-start;
    text-align: left;
  }

  .sort-ops {
    flex-direction: row;
  }
}
</style>
