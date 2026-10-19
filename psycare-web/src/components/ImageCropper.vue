<template>
  <a-modal
    v-model:open="open"
    title="裁剪图片为轮播尺寸"
    :width="720"
    :mask-closable="false"
    ok-text="确认裁剪并上传"
    cancel-text="取消"
    :confirm-loading="submitting"
    @ok="doConfirm"
    @cancel="doCancel"
  >
    <div class="cropper">
      <p class="tip">
        轮播图固定为 <b class="mono">2.5 : 1</b>（导出 {{ outW }}×{{ outH }}）。
        图片已等比铺满画面，<b>拖动调整位置</b>，<b>滚轮或下方滑杆缩放</b>。
      </p>

      <!--
        取景区。宽高比恒等于目标比例 —— 用户看到的框，就是最终裁出来的画面。
        这样做比「先自由裁剪再拉伸到 1200×480」好：后者会把圆脸压扁。
      -->
      <div
        ref="stageRef"
        class="stage"
        :style="{ aspectRatio: `${ratio}` }"
        @mousedown="onDragStart"
        @wheel.prevent="onWheel"
      >
        <img
          ref="imgRef"
          :src="src"
          alt="待裁剪图片"
          class="stage-img"
          :style="imgStyle"
          draggable="false"
          @load="onImgLoad"
          @dragstart.prevent
        />
        <div class="stage-grid" />
        <div v-if="!ready" class="stage-loading">图片加载中…</div>
      </div>

      <div class="controls">
        <span class="ctrl-label">缩放</span>
        <input
          v-model.number="zoom"
          class="zoom-slider"
          type="range"
          :min="1"
          :max="4"
          step="0.01"
          :disabled="!ready"
        />
        <span class="zoom-val mono">{{ Math.round(zoom * 100) }}%</span>
        <button class="link-btn" type="button" :disabled="!ready" @click="reset">重置</button>
      </div>

      <div class="meta mono">
        原图 {{ naturalW }}×{{ naturalH }} · 输出 {{ outW }}×{{ outH }} ·
        {{ Math.round(estimateBytes / 1024) }} KB（预估）
      </div>

      <a-alert v-if="error" type="error" show-icon :message="error" />
    </div>
  </a-modal>
</template>

<script setup lang="ts">
/*
 * 轮播图裁剪器（canvas 手写，零新增依赖）。
 *
 * <h3>为什么不引库</h3>
 * cropperjs / vue-cropper 这类库功能完整，但要拖进一个几十 kB 的运行时，
 * 而且它们的交互模型（自由比例、旋转、翻转）远超本场景需要 —— 这里只有
 * **一个固定比例的取景框 + 拖动 + 缩放**。项目对包体有明确预算（vendor-antd
 * 800 kB，硬约定 #9/#96），能自己写 150 行解决的事不引库。
 *
 * <h3>坐标模型（这是本组件唯一的难点，务必先看懂再改）</h3>
 * 取景框是固定尺寸的「窗口」，图片在窗口**下方**平移与缩放。
 * 用「显示尺寸 + 平移量」两个量描述状态：
 *
 *   - `displayW/H`：图片按 `zoom` 缩放后的实际显示尺寸（CSS px）；
 *   - `offsetX/Y`：图片左上角相对取景框左上角的偏移。
 *
 * 渲染用 `transform: translate(offsetX, offsetY)`，图片本体尺寸就是 displayW/H。
 * 导出时把这三个量按 `天然宽 / displayW` 的比例放大回原始像素空间即可 ——
 * 不需要再关心 CSS 与设备像素比，因为导出画布就是按原始分辨率换算的。
 *
 * <h3>为什么 offset 要用 clampOffset 夹住</h3>
 * 不夹的话图片能被拖出窗口留下空白边。规则是：图片的显示尺寸若大于窗口，
 * 则平移范围是 [窗口 - 显示尺寸, 0]（即始终盖满窗口）；若小于窗口（zoom 很小
 * 时理论上不会发生，但宽高比差异大时单边可能发生），则居中固定为
 * (窗口 - 显示尺寸) / 2，不允许拖动。
 */
import { computed, nextTick, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 受控开合。用法：`<ImageCropper v-model:open="show" :src="rawUrl" @cropped="onCropped" />` */
    open: boolean
    /** 待裁剪的图片来源：objectURL / dataURL 均可 */
    src: string
    /** 输出比例（宽/高），默认 2.5，与 tokens.css 的 --banner-ratio 一致 */
    ratio?: number
    /** 导出基准宽（像素）。高度由 ratio 推出 */
    outWidth?: number
    /** 导出质量（0-1），仅对 jpeg 有效 */
    quality?: number
  }>(),
  { ratio: 2.5, outWidth: 1200, quality: 0.92 },
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  /** 裁剪完成，回传 Blob 与 File（File 可直接丢给上传接口） */
  (e: 'cropped', payload: { blob: Blob; file: File }): void
  (e: 'cancel'): void
}>()

const open = computed({
  get: () => props.open,
  set: (v: boolean) => emit('update:open', v),
})

const outW = computed(() => props.outWidth)
const outH = computed(() => Math.round(props.outWidth / props.ratio))

const stageRef = ref<HTMLElement | null>(null)
const imgRef = ref<HTMLImageElement | null>(null)

const zoom = ref(1)
const naturalW = ref(0)
const naturalH = ref(0)
/** 取景框的实际 CSS 尺寸（由 aspect-ratio 撑出来，必须实测，不能猜） */
const stageW = ref(0)
const stageH = ref(0)
const offsetX = ref(0)
const offsetY = ref(0)

const ready = ref(false)
const submitting = ref(false)
const error = ref('')

/**
 * 图片按 zoom 缩放后的显示尺寸。
 *
 * ⚠️ 注意这里是 `natural * baseScale * zoom`，**不是** `natural * zoom`。
 * zoom 的语义是「相对 cover 基准的倍数」，基准态（zoom=1）就已经把图放大到
 * 恰好盖满窗口了。忽略 baseScale 的话，一张 2000px 宽的原图会以原始像素
 * 铺开在 300px 的框里 —— 用户一打开看到的就是图片的一小块角落。
 * 依赖顺序：baseScale 依赖 stageW/stageH/naturalW/naturalH，都是 ref，会自动重算。
 */
const displayW = computed(() => naturalW.value * effectiveScale.value)
const displayH = computed(() => naturalH.value * effectiveScale.value)

const imgStyle = computed(() => ({
  width: `${displayW.value}px`,
  height: `${displayH.value}px`,
  transform: `translate(${offsetX.value}px, ${offsetY.value}px)`,
}))

/** 粗估导出体积，给用户一个「这张图多大」的预期（不精确，jpeg 质量相关） */
const estimateBytes = computed(() => outW.value * outH.value * 0.28)

/* ==================== 尺寸计算 ==================== */

/**
 * 基准缩放 `baseScale`：让图片**恰好盖满**取景框（cover），也就是「短边铺满」。
 * 这是打开弹窗时的初始状态 —— 用户一进来看到的就是一张不留白的满屏画面。
 *
 * `baseScale` 是「原图 → 恰好 cover」的倍率，例如原图 1000×2000、
 * 取景框 500×200，则 max(500/1000, 200/2000) = 0.5（高度方向反而缩小）。
 */
const baseScale = computed(() => {
  if (!stageW.value || !stageH.value || !naturalW.value || !naturalH.value) return 0
  return Math.max(stageW.value / naturalW.value, stageH.value / naturalH.value)
})

/** 「原图 → 当前显示」的总倍率。导出换算与锚点缩放都以它为准 */
const effectiveScale = computed(() => baseScale.value * zoom.value)

/**
 * 把平移量夹在合法范围内。
 *
 * `slack = 显示尺寸 - 窗口尺寸`。若为正（图片比窗口大），偏移量允许范围是
 * `[-slack, 0]`；若为负（图片比窗口小），居中在 `slack / 2`（负值即向左偏移半个差值）。
 */
function clampOffset() {
  const slackX = displayW.value - stageW.value
  offsetX.value = slackX > 0 ? Math.min(0, Math.max(-slackX, offsetX.value)) : slackX / 2

  const slackY = displayH.value - stageH.value
  offsetY.value = slackY > 0 ? Math.min(0, Math.max(-slackY, offsetY.value)) : slackY / 2
}

/** 重置为「cover 居中」的初始视角 */
function reset() {
  // 挡住 zoom watcher：重置是自己算好偏移的，不需要锚点换算再插一脚
  silentZoom = true
  zoom.value = 1
  silentZoom = false
  // zoom = 1 时 displayW/H 恰好等于取景框尺寸，偏移为 0；仍走一遍 clamp 兜底
  offsetX.value = (stageW.value - displayW.value) / 2
  offsetY.value = (stageH.value - displayH.value) / 2
  clampOffset()
}

function onImgLoad() {
  const el = imgRef.value
  if (!el) return
  naturalW.value = el.naturalWidth
  naturalH.value = el.naturalHeight

  // 取景框尺寸要实测：aspect-ratio 撑出来的高度取决于父容器宽度，
  // 在 flex/grid 里提前算容易算错，读一次 clientWidth/Height 最可靠。
  const stage = stageRef.value
  if (stage) {
    stageW.value = stage.clientWidth
    stageH.value = stage.clientHeight
  }
  ready.value = naturalW.value > 0 && naturalH.value > 0 && stageW.value > 0
  // reset 内部会把 zoom 归位并居中；这里不要先动 zoom，否则 reset 的
  // 归位成了空操作、而 watcher 又已经按「旧 zoom → 1」算过一次（结果错位）。
  reset()
}

/* ==================== 交互：拖动 ==================== */

let dragging = false
let dragStartX = 0
let dragStartY = 0
let baseOffsetX = 0
let baseOffsetY = 0

function onDragStart(e: MouseEvent) {
  if (!ready.value) return
  dragging = true
  dragStartX = e.clientX
  dragStartY = e.clientY
  baseOffsetX = offsetX.value
  baseOffsetY = offsetY.value
  // 用 window 监听而不是元素监听：指针移出取景框后仍应继续跟手，
  // 否则拖到边缘就「掉了」，得松手重按 —— 这是裁剪器最容易做糙的地方。
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e: MouseEvent) {
  if (!dragging) return
  offsetX.value = baseOffsetX + (e.clientX - dragStartX)
  offsetY.value = baseOffsetY + (e.clientY - dragStartY)
  clampOffset()
}

function onDragEnd() {
  dragging = false
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

/* ==================== 交互：滚轮缩放 ==================== */

/**
 * 滚轮缩放**以光标位置为锚点**（而不是画面中心）。
 * 中心缩放时，用户想放大的那个角落会往反方向跑，得再拖一次 —— 手感很差。
 * 锚点算法：把光标在窗口内的坐标视为「图片上的某个点」，
 * 缩放后让这个点仍落在同一屏幕位置。
 */
function onWheel(e: WheelEvent) {
  if (!ready.value) return
  const stage = stageRef.value
  if (!stage) return

  const rect = stage.getBoundingClientRect()
  const cx = e.clientX - rect.left
  const cy = e.clientY - rect.top

  const oldScale = effectiveScale.value
  const nextZoom = Math.min(4, Math.max(1, zoom.value * (e.deltaY < 0 ? 1.08 : 1 / 1.08)))
  if (nextZoom === zoom.value) return

  // 光标下的图片坐标（以原图像素为单位）
  const imgX = (cx - offsetX.value) / oldScale
  const imgY = (cy - offsetY.value) / oldScale

  zoom.value = nextZoom
  const newScale = effectiveScale.value
  // 反解出新的偏移，使同一个原图点仍投影到 (cx, cy)
  offsetX.value = cx - imgX * newScale
  offsetY.value = cy - imgY * newScale
  clampOffset()
}

/**
 * 滑杆缩放：以画面中心为锚点（滑杆没有光标位置可用，中心最符合直觉）。
 *
 * ⚠️ 用 `silentZoom` 标志位挡住 `reset()` 内部的赋值 —— 否则重置时这个 watcher
 * 会按「从旧 zoom 到 1」的锚点换算再算一遍偏移，把 reset 刚设好的居中值覆盖掉。
 */
let silentZoom = false

watch(zoom, (next, prev) => {
  if (silentZoom || !ready.value || next === prev) return
  const oldScale = baseScale.value * prev
  const newScale = baseScale.value * next
  if (oldScale <= 0 || newScale <= 0) return
  const cx = stageW.value / 2
  const cy = stageH.value / 2
  const imgX = (cx - offsetX.value) / oldScale
  const imgY = (cy - offsetY.value) / oldScale
  offsetX.value = cx - imgX * newScale
  offsetY.value = cy - imgY * newScale
  clampOffset()
})

/* ==================== 导出 ==================== */

async function doConfirm() {
  error.value = ''
  const el = imgRef.value
  if (!el || !ready.value) {
    error.value = '图片尚未加载完成'
    return
  }

  submitting.value = true
  try {
    const blob = await renderToBlob(el)
    const file = new File([blob], `banner-${Date.now()}.jpg`, { type: 'image/jpeg' })
    emit('cropped', { blob, file })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '裁剪失败'
  } finally {
    submitting.value = false
  }
}

/**
 * 把当前取景框内的画面渲染到目标尺寸的画布。
 *
 * 换算关系：从「CSS 显示空间」映射回「原图像素空间」用同一个比例
 * `naturalW / displayScale`。因为 displayScale 已经是「原图 → 显示」的缩放，
 * 所以源矩形的宽 = 取景框宽 / displayScale。
 *
 * 用 createImageBitmap + drawImage 的 9 参数形式做一次性的高质量缩放，
 * 而不是先用小画布裁再放大 —— 一次绘制能避免二次重采样的模糊。
 */
async function renderToBlob(el: HTMLImageElement): Promise<Blob> {
  const scale = effectiveScale.value
  if (scale <= 0) throw new Error('缩放比例异常')

  // 取景框左上角对应的原图像素坐标
  const sx = -offsetX.value / scale
  const sy = -offsetY.value / scale
  // 源矩形尺寸 = 窗口尺寸 / scale
  const sw = stageW.value / scale
  const sh = stageH.value / scale

  const canvas = document.createElement('canvas')
  canvas.width = outW.value
  canvas.height = outH.value
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('当前浏览器不支持 canvas')

  // 白底：png 透明区域导出成 jpeg 会变黑，先铺白
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'

  ctx.drawImage(el, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height)

  return await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('导出图片失败'))),
      'image/jpeg',
      props.quality,
    )
  })
}

function doCancel() {
  emit('cancel')
}

/* ==================== 生命周期 ==================== */

// 每次打开都重置状态：图片换了、窗口宽度变了，都必须在重新加载后重算
watch(
  () => props.open,
  async (v) => {
    if (!v) return
    ready.value = false
    error.value = ''
    zoom.value = 1
    offsetX.value = 0
    offsetY.value = 0
    naturalW.value = 0
    naturalH.value = 0
    // 等 DOM 渲染完再量取景框尺寸，否则读到的是 0
    await nextTick()
    const stage = stageRef.value
    if (stage) {
      stageW.value = stage.clientWidth
      stageH.value = stage.clientHeight
    }
  },
)

// 窗口尺寸变化时取景框会变宽/变高，必须重算避免图片露白
function onResize() {
  const stage = stageRef.value
  if (!stage || !ready.value) return
  stageW.value = stage.clientWidth
  stageH.value = stage.clientHeight
  clampOffset()
}

watch(ready, (v) => {
  if (v) window.addEventListener('resize', onResize)
  else window.removeEventListener('resize', onResize)
})

defineExpose({ reset })
</script>

<style scoped>
.cropper {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tip {
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.7;
  margin: 0;
}

/* ---------- 取景区 ---------- */
.stage {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--primary-soft);
  border: 1px solid var(--card-border);
  cursor: grab;
  user-select: none;
}

.stage:active {
  cursor: grabbing;
}

.stage-img {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none; /* 关键：否则会被父级 100% 宽约束住，缩放失效 */
  transform-origin: top left;
  will-change: transform;
  /* 拖动时不加过渡：加了会有「橡皮筋」滞后感 */
}

/* 三分线，辅助构图 */
.stage-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(to right, rgb(255 255 255 / 32%) 1px, transparent 1px),
    linear-gradient(to bottom, rgb(255 255 255 / 32%) 1px, transparent 1px);
  background-size: 33.333% 33.333%;
  background-position: -1px -1px;
  box-shadow: inset 0 0 0 1px rgb(0 0 0 / 18%);
}

.stage-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--text-3);
  background: var(--primary-soft);
}

/* ---------- 控制条 ---------- */
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ctrl-label {
  font-size: 12px;
  color: var(--text-2);
  flex: none;
}

.zoom-slider {
  flex: 1;
  min-width: 0;
  accent-color: var(--primary);
  cursor: pointer;
}

.zoom-slider:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.zoom-val {
  font-size: 11px;
  color: var(--text-3);
  width: 44px;
  text-align: right;
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

.link-btn:hover:not(:disabled) {
  text-decoration: underline;
}

.link-btn:disabled {
  color: var(--text-3);
  cursor: not-allowed;
}

.meta {
  font-size: 11px;
  color: var(--text-3);
}
</style>
