<template>
  <!--
    装饰性动画，`aria-hidden` 已由使用方在外层加过一次，这里再给 canvas 标一次 ——
    屏幕阅读器会读到 canvas 这个「图形」，加不加都无害，但省得每个使用方都要记得。
  -->
  <div ref="rootEl" class="orb" :class="{ 'orb--inert': !interactive }" :style="rootStyle">
    <canvas ref="canvasEl" class="orb-canvas" aria-hidden="true"></canvas>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/*
 * Orb Noise —— 一颗由点阵构成的旋转球（Originkit 的 OrbNoise 移植到 Vue）。
 *
 * <h3>它到底在画什么</h3>
 * <pre>
 *   ① 取球面上一组均匀分布的点（斐波那契球，fib + polar）；
 *   ② 给每个点叠一个「三倍频 + 二倍频」的拍频扰动 w，让球面像呼吸一样起伏；
 *   ③ 逐帧绕 Y 轴自转 + 绕 X 轴倾斜，投影到 2D；
 *   ④ 按 z 排序（远的先画），并按深度做「透视缩放 + 透明度衰减」。
 * </pre>
 * 整个过程是纯 Canvas 2D 的 `arc + fill`，没有 WebGL，也没有离屏缓冲。
 *
 * <h3>与原始实现的改动</h3>
 * <ol>
 *   <li><b>颜色从 CSS 变量读</b>。原实现把 `#AC40D2` 写在预设里；本项目的规矩是
 *       色值只放 tokens.css，所以 `dotColor` 留空时读 `--orb-dot`（见 tokens.css）。</li>
 *   <li><b>补了 `interactive` 开关</b>。原实现默认允许「按住拖动转球」，但
 *       `touch-action: none` 会吃掉这块区域的移动端纵向滚动 —— 在一句话就能滑走的
 *       空态里，这个交互的收益远小于代价。默认关闭，需要时再开。</li>
 *   <li><b>补了减动效 / 页面隐藏的暂停</b>。原实现只有 RAF 循环。</li>
 *   <li><b>补了「挂载后先同步画一帧」</b>。否则从挂载到第一次 RAF 之间那块区域是空的。</li>
 * </ol>
 *
 * <h3>成本</h3>
 * 每帧约 `150 × density/100` 个点（density 上限 300 → 450 个），每个点一次 `arc + fill`。
 * 450 个 arc 在集显上约 1~2ms，比全屏 WebGL 背景便宜一个量级 —— 但它只在空态出现，
 * 一旦有了消息就随 `v-if` 卸载，不会和滚动、气泡重排抢帧。
 */

/* ================= 常量 ================= */

const MAX_DPR = 2
const TAU = Math.PI * 2

/** 一「圈」的时长（秒）。speed=50 时正好 5.4 秒转一整圈 */
const PERIOD = 5.4
/** 球体基准半径（× size）。真正的撑满程度由 autoFit 再放大一轮 */
const BASE_SPREAD = 0.29
/** 透视强度：z 方向 1 个单位对应多大的投影缩放 */
const PERSPECTIVE = 3.5
const DEPTH_SIZE = 1
const DEPTH_FADE = 1
/** 小于这个半径的点按面积衰减 alpha，再抬到该半径 —— 否则远处的点会变成硬芝麻 */
const MIN_RADIUS = 0.6
/** 单帧最多画多少个点（防护性上限，实际最多 450） */
const MAX_DOTS = 1024

/* ================= 数学 ================= */

function clamp01(x: number): number {
  return x < 0 ? 0 : x > 1 ? 1 : x
}

function clampN(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v
}

function num(v: unknown, fb: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fb
}

function dotsN(base: number, n: number): number {
  const v = Math.round(base * n)
  return v < 1 ? 1 : v
}

/** 斐波那契球面采样：第 i 个点的单位向量 */
function fib(i: number, n: number): [number, number, number] {
  const y = 1 - (i / Math.max(1, n - 1)) * 2
  const r = Math.sqrt(Math.max(0, 1 - y * y))
  const th = 2.399963 * i
  return [Math.cos(th) * r, y, Math.sin(th) * r]
}

/** 单位向量 → (极角, 方位角)，扰动项按角度做，才不会在两极堆叠 */
function polar(p: [number, number, number]): [number, number] {
  return [Math.acos(Math.max(-1, Math.min(1, p[1]))), Math.atan2(p[2], p[0])]
}

/**
 * 一个点：`[x, y, z, 尺寸系数?, 透明度系数?, 颜色?]`。
 * 后三项可选 —— 只有需要「某个点单独大一点/暗一点」时才填。
 */
type Dot = [number, number, number, number?, number?, string?]

/** 绕 Y 轴 yaw、再绕 X 轴 pitch 旋转 */
function spin(p: Dot, yaw: number, pitch: number): Dot {
  const ca = Math.cos(yaw)
  const sa = Math.sin(yaw)
  const rx = p[0] * ca - p[2] * sa
  let rz = p[0] * sa + p[2] * ca
  const co = Math.cos(pitch)
  const so = Math.sin(pitch)
  const ry = p[1] * co - rz * so
  rz = p[1] * so + rz * co
  return [rx, ry, rz, p[3], p[4], p[5]]
}

type Params = {
  /** 密度（点的数量 = 150 × n） */
  n: number
  /** 球体尺度 */
  sp: number
  /** 点的基准半径 */
  ds: number
  /** 静止偏航 */
  yw: number
  /** 每个周期自转几圈 */
  sn: number
  /** 俯仰 */
  pc: number
  /** 相位 0~1 */
  t: number
  dot: string
  acc: string
}

/**
 * 生成本帧的球面点（未投影）。
 * 扰动是两个不同频率的正弦叠加 —— 单一频率会让整颗球「一起胀缩」，
 * 拍频才会出现「这半边鼓起、那半边凹下」的活物感。
 */
function frame(t: number, P: Params, out: Dot[]): void {
  const n = dotsN(150, P.n)
  for (let i = 0; i < n; i += 1) {
    const q = fib(i, n)
    const p = polar(q)

    const w =
      0.5 * Math.sin(3 * p[1] + 2 * p[0] + TAU * t) +
      0.5 * Math.sin(2 * p[1] - 3 * p[0] - 2 * TAU * t)
    const s = 1 + 0.15 * w
    out.push(
      spin([q[0] * s, q[1] * s, q[2] * s, 0.7 + (0.6 * (w + 1)) / 2, 0.55 + (0.45 * (w + 1)) / 2], TAU * t, 0.36),
    )
  }
}

type Emit = (x: number, y: number, r: number, a: number, col: string) => void

/** 把 3D 点投影成屏幕上的圆，并按 z 从前到后排序后逐个吐出 */
function project(pts: Dot[], size: number, P: Params, emit: Emit): void {
  const c = size / 2
  const R = size * BASE_SPREAD * P.sp
  const pv = PERSPECTIVE

  const yaw = P.yw + TAU * P.sn * P.t
  const list: Array<[number, number, number, number, string, number]> = []
  for (const p of pts) {
    const q = spin(p, yaw, P.pc)
    const z = q[2]
    const s = pv / (pv - z)
    const f = clamp01((z + 1.1) / 2.2)
    list.push([
      c + q[0] * R * s,
      c + q[1] * R * s,
      P.ds * (0.4 + 1.6 * DEPTH_SIZE * f) * s * (q[3] === undefined ? 1 : q[3]),
      (0.07 + 0.93 * Math.pow(f, 1.55 * DEPTH_FADE)) * (q[4] === undefined ? 1 : q[4]),
      q[5] || P.dot,
      z,
    ])
  }
  list.sort((a, b) => a[5] - b[5])
  for (const d of list) emit(d[0], d[1], d[2], d[3], d[4])
}

/**
 * 自动适配尺寸。
 *
 * 原实现把「球多大」交给固定的 BASE_SPREAD，结果小尺寸下球只占画布一半、
 * 大尺寸下又被裁掉边缘。这里先拿 20 个相位试画一遍，量出最大外延，
 * 再反推一个缩放系数把球撑到目标占比（`fill × size`，默认 83%）。
 *
 * 贵吗：20 × 450 次投影，约 1~2ms —— 只在该尺寸第一次出现时算一次（有缓存），
 * 窗口缩放时才会重算。
 */
const fitCache = new Map<string, number>()

function autoFit(size: number, P: Params, restYaw: number, restPitch: number, fill: number): number {
  // ⚠️ 缓存键必须把 `fill` 也算进去：同一尺寸下两种占比是两个不同的解
  const key = size + '/' + P.n + '/' + P.sp + '/' + restYaw + '/' + restPitch + '/' + P.sn + '/' + fill
  const hit = fitCache.get(key)
  if (hit !== undefined) return hit

  const half = size / 2
  let ext = 0
  const probe: Params = { ...P, ds: 1, dot: '#fff', acc: '#fff', t: 0, yw: restYaw, pc: restPitch }
  const emit: Emit = (x, y, r, a) => {
    if (a <= 0.05 || r <= 0.15) return
    ext = Math.max(ext, Math.abs(x - half) + 0.5 * r, Math.abs(y - half) + 0.5 * r)
  }
  for (let k = 0; k < 20; k += 1) {
    probe.t = k / 20
    const out: Dot[] = []
    frame(probe.t, probe, out)
    project(out, size, probe, emit)
  }
  const fit = ext > 1 ? Math.max(0.55, Math.min(1.7, (fill * size) / ext)) : 1
  // 键必须与上面查的键一致 —— 原实现这里写的是 `size`，等于永远缓存不中（每次 resize 都白算）
  fitCache.set(key, fit)
  return fit
}

/**
 * 尺寸 → 点的基准半径系数。
 * 小尺寸（图标）用更小的点，否则点会糊成一团；大尺寸放大点，保住「点阵」质感。
 */
function dotScaleFor(size: number): number {
  if (size <= 46) return 0.4
  if (size <= 190) return 0.4 + ((size - 46) / 144) * 0.6
  if (size <= 340) return 1 + ((size - 190) / 150) * 0.55
  return 1.55
}

/* ================= 颜色 ================= */

type RGBA = [number, number, number, number]

/**
 * 解析 `#rgb / #rrggbb / #rrggbbaa / rgb() / rgba()`，失败返回兜底色。
 *
 * 注意**不声称支持 hsl()**：原实现用 `/[\d.]+/g` 一把抓，`hsl(210 40% 50%)`
 * 会被解成 `rgb(210, 40, 50)` 而**静默**出错色。与其错，不如落到兜底。
 */
function parseColor(input: string | undefined, fb: RGBA): RGBA {
  if (!input) return fb
  const s = String(input).trim()
  if (s.charAt(0) === '#') {
    let h = s.slice(1)
    if (h.length === 3 || h.length === 4) {
      h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2] + (h.length === 4 ? h[3] + h[3] : '')
    }
    if (h.length >= 6) {
      const r = parseInt(h.slice(0, 2), 16)
      const g = parseInt(h.slice(2, 4), 16)
      const b = parseInt(h.slice(4, 6), 16)
      const a = h.length >= 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1
      if (Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b)) return [r, g, b, a]
    }
    return fb
  }
  const m = s.match(/^rgba?\(([^)]*)\)/i)
  if (m) {
    const p = m[1].split(/[\s,/]+/).filter(Boolean)
    const f = (i: number) => parseFloat(p[i])
    if (p.length >= 3 && [0, 1, 2].every((i) => Number.isFinite(f(i)))) {
      return [Math.min(255, f(0)), Math.min(255, f(1)), Math.min(255, f(2)), p.length >= 4 ? Math.min(1, f(3)) : 1]
    }
  }
  return fb
}

function css(c: RGBA): string {
  return 'rgba(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ',' + c[3] + ')'
}

/* ================= 组件 ================= */

const props = withDefaults(
  defineProps<{
    /** 画布宽度（px）。不传 = 填满父容器 */
    width?: number
    /** 画布高度（px）。不传 = 填满父容器 */
    height?: number
    /** 点的颜色。留空则读根元素的 `--orb-dot` */
    dotColor?: string
    /** 20~300，点数密度（100 = 150 个点） */
    density?: number
    /** 20~300，点的大小（100 = 基准） */
    dotSize?: number
    /** -100~100，自转速度（50 = 5.4 秒一圈，100 = 2.7 秒） */
    speed?: number
    /** -3~3，每个周期自转圈数（0 = 不转，只做呼吸扰动） */
    spinTurns?: number
    /** 球体形态：spread 40~180（大小）/ turn -180~180（静止偏航）/ tilt -90~90（俯仰） */
    ball?: { spread?: number; turn?: number; tilt?: number }
    /** 指针拖拽：drag 0~300（灵敏度，0 = 关闭拖拽）/ damping 1~100（回弹衰减） */
    pointer?: { drag?: number; damping?: number }
    /** 1~2，设备像素比上限 */
    dprCap?: number
    /**
     * 0.3~0.5，球体最大外延占**画布半宽**的比例。
     *
     * 0.415（默认）= 球的直径占画布 83%，四周留一圈呼吸空间；
     * 0.48 ≈ 96%，留给「球要跟旁边那个盒子一样高」的场景（如输入框右侧那颗）。
     * 不要给 0.5：球的最外圈点会被父级的 `overflow: hidden` 啃掉。
     */
    fill?: number
    /** 是否允许按住拖动转球（会接管该区域的触摸手势） */
    interactive?: boolean
  }>(),
  {
    width: 0,
    height: 0,
    dotColor: '',
    density: 300,
    dotSize: 100,
    speed: 50,
    spinTurns: 1,
    ball: () => ({}),
    pointer: () => ({}),
    dprCap: MAX_DPR,
    fill: 0.415,
    interactive: false,
  },
)

const rootEl = ref<HTMLDivElement | null>(null)
const canvasEl = ref<HTMLCanvasElement | null>(null)

/**
 * 只有显式传了 `width` / `height` 才写内联尺寸。
 *
 * <h3>为什么不能无条件写 `width: 100%`（这是一个真踩到的坑）</h3>
 * 内联样式的优先级高于任何选择器 —— 只要根节点上挂着 `width: 100%; height: 100%`，
 * 使用方就**再也没有办法**用 CSS 给它一个固定尺寸：那条规则永远输。
 * 症状不是「尺寸不对」，而是**离奇地看不见**：
 * 输入框左侧那颗球是 `position: absolute` + `right: calc(100% + gap)` 摆放的，
 * 一旦宽度被撑成父级的 100%（770px），它就从胶囊左缘往**左**铺开 770px，
 * 整块被 `.chat-main` 的 `overflow: hidden` 裁掉 —— 观感是「功能没做」。
 * 默认尺寸交给 `.orb` 自己的 `width/height: 100%`，使用方要用固定尺寸时
 * 用更高特异性的选择器覆盖即可（`.input-row .composer-orb` 就是这么写的）。
 */
const rootStyle = computed(() => {
  const s: Record<string, string> = {}
  if (props.width > 0) s.width = props.width + 'px'
  if (props.height > 0) s.height = props.height + 'px'
  return s
})

/** 兜底色 —— 只在 `--orb-dot` 拿不到时生效，正常路径永远走 token */
const FALLBACK_DOT: RGBA = [111, 82, 240, 1]

let dispose: (() => void) | null = null

onMounted(() => {
  dispose = start()
})

onBeforeUnmount(() => {
  dispose?.()
  dispose = null
})

function start(): (() => void) | null {
  const canvas = canvasEl.value
  const root = rootEl.value
  if (!canvas || !root) return null

  const ctx = canvas.getContext('2d')
  if (!ctx) {
    // 拿不到 2D 上下文就当不存在，不要抛 —— 装饰层不该让页面挂掉
    console.warn('[OrbNoise] 2D context 不可用，粒子球跳过渲染')
    return null
  }

  /* ---------- 颜色（挂载后读一次，prop 变化时重读） ---------- */
  const VAR_REF = /^var\(\s*(--[A-Za-z0-9_-]+)\s*\)$/
  const readVar = (name: string) => getComputedStyle(root).getPropertyValue(name).trim()
  /*
   * 为什么还要手动展开一层 `var(--x)`：
   * 自定义属性的 computed value 在「是否已替换 var()」上各家浏览器不一致
   * （见记忆 #149）。这里容错一层，拿不到就落兜底色，绝不静默成另一套配色 ——
   * 兜底色与 `--orb-dot` 同族，所以就算真的走到兜底也不会跳色。
   */
  const resolveColor = (): string => {
    const raw = props.dotColor.trim()
    if (raw) {
      const ref = raw.match(VAR_REF)
      return css(parseColor(ref ? readVar(ref[1]) : raw, FALLBACK_DOT))
    }
    return css(parseColor(readVar('--orb-dot'), FALLBACK_DOT))
  }
  let dotCol = resolveColor()

  /* ---------- 交互状态 ---------- */
  const drag = { active: false, lx: 0, ly: 0, lt: 0, yaw: 0, pitch: 0, vx: 0, vy: 0 }

  let raf = 0
  let last = performance.now()
  let phase = 0
  let disposed = false

  const render = (now: number) => {
    const dt = Math.min(0.05, (now - last) / 1000)
    last = now

    const dpr = Math.min(window.devicePixelRatio || 1, clampN(num(props.dprCap, MAX_DPR), 1, MAX_DPR))
    const cw = props.width > 0 ? props.width : canvas.clientWidth || 120
    const ch = props.height > 0 ? props.height : canvas.clientHeight || 120
    const bw = Math.max(1, Math.round(cw * dpr))
    const bh = Math.max(1, Math.round(ch * dpr))
    if (canvas.width !== bw || canvas.height !== bh) {
      canvas.width = bw
      canvas.height = bh
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, cw, ch)

    const speedN = clampN(num(props.speed, 50), -100, 100) / 50
    phase = (phase + (dt * speedN) / PERIOD) % 1
    if (phase < 0) phase += 1

    // 球是正方形：取短边，再居中
    const size = Math.max(4, Math.min(cw, ch))
    const bx = (cw - size) / 2
    const by = (ch - size) / 2

    const restPitch = (clampN(num(props.ball.tilt, 0), -90, 90) * Math.PI) / 180
    const restYaw = (clampN(num(props.ball.turn, 0), -180, 180) * Math.PI) / 180

    // 松手后的惯性：指数衰减，再叠加到 yaw/pitch 上
    if (!drag.active) {
      const damping = clampN(num(props.pointer.damping, 20), 1, 100)
      const decay = Math.exp(-damping * 0.12 * dt)
      drag.yaw += drag.vx * dt
      drag.pitch += drag.vy * dt
      drag.vx *= decay
      drag.vy *= decay
    }
    drag.pitch = clampN(drag.pitch, -Math.PI / 2 - restPitch, Math.PI / 2 - restPitch)

    const P: Params = {
      n: clampN(num(props.density, 300), 20, 300) / 100,
      sp: clampN(num(props.ball.spread, 100), 40, 180) / 100,
      ds: dotScaleFor(size) * (clampN(num(props.dotSize, 100), 20, 300) / 100),
      yw: restYaw + drag.yaw,
      sn: Math.round(clampN(num(props.spinTurns, 1), -3, 3)),
      pc: restPitch + drag.pitch,
      t: phase,
      dot: dotCol,
      acc: dotCol,
    }

    const fit = autoFit(size, P, restYaw, restPitch, clampN(num(props.fill, 0.415), 0.3, 0.5))
    const half = size / 2

    const out: Dot[] = []
    frame(phase, P, out)

    let drawn = 0
    let curCol = ''
    project(out, size, P, (x, y, r, a, col) => {
      if (drawn >= MAX_DOTS) return
      const rr = r * (0.55 + 0.45 * fit)
      if (rr <= 0.05 || a <= 0.004) return

      const cx = bx + half + (x - half) * fit
      const cy = by + half + (y - half) * fit

      let dr = rr
      let da = Math.min(1, a)
      if (dr < MIN_RADIUS) {
        // 按面积衰减（半径比的平方），再抬到最小半径 —— 不然远处的点会变成一颗硬芝麻
        da *= (dr / MIN_RADIUS) * (dr / MIN_RADIUS)
        dr = MIN_RADIUS
      }

      // 点的颜色本帧恒定，只在第一次赋值，省掉 300+ 次字符串比对
      if (col !== curCol) {
        ctx.fillStyle = col
        curCol = col
      }
      ctx.globalAlpha = da
      ctx.beginPath()
      ctx.arc(cx, cy, dr, 0, TAU)
      ctx.fill()
      drawn += 1
    })
    ctx.globalAlpha = 1
  }

  const loop = (now: number) => {
    if (disposed) return
    raf = requestAnimationFrame(loop)
    render(now)
  }

  /* ---------- 拖拽（仅 interactive） ---------- */
  const onDown = (e: PointerEvent) => {
    if (!props.interactive) return
    if (clampN(num(props.pointer.drag, 100), 0, 300) <= 0) return
    drag.active = true
    drag.lx = e.clientX
    drag.ly = e.clientY
    drag.lt = performance.now()
    drag.vx = 0
    drag.vy = 0
    try {
      canvas.setPointerCapture(e.pointerId)
    } catch {
      /* 某些环境不支持 pointer capture，退化成普通 move 事件即可 */
    }
  }
  const onMove = (e: PointerEvent) => {
    if (!drag.active) return
    const k = ((clampN(num(props.pointer.drag, 100), 0, 300) / 100) * TAU) / Math.max(1, canvas.clientWidth || 120)
    const dx = (e.clientX - drag.lx) * k
    const dy = (e.clientY - drag.ly) * k
    const now2 = performance.now()
    const span = Math.max(1, now2 - drag.lt)
    drag.lx = e.clientX
    drag.ly = e.clientY
    drag.lt = now2

    drag.yaw -= dx
    drag.pitch += dy
    drag.vx = (-dx / span) * 1000
    drag.vy = (dy / span) * 1000
  }
  const onUp = () => {
    drag.active = false
  }

  /*
   * 减动效：只画一帧然后停。
   * 不能完全不画（那会是一块空白），取「停在有层次的相位」。
   * 0.38 是试出来的：球体略有转动、明暗分布最自然，不像刚起步。
   */
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduced) {
    phase = 0.38
    render(performance.now())
  } else {
    /*
     * 先同步画第一帧再挂 RAF：从挂载到第一次 RAF 回调之间（空态刚切进来时）
     * 那块区域是空的，观感上是「先空一拍，粒子才长出来」。
     */
    render(performance.now())
    if (props.interactive) {
      canvas.addEventListener('pointerdown', onDown)
      canvas.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
      window.addEventListener('pointercancel', onUp)
    }
    raf = requestAnimationFrame(loop)
  }

  /* 后台标签页停 RAF（浏览器会降频，但没必要占着一个绘制循环） */
  const onVisibility = () => {
    if (reduced) return
    if (document.hidden) {
      cancelAnimationFrame(raf)
    } else {
      last = performance.now()
      raf = requestAnimationFrame(loop)
    }
  }
  document.addEventListener('visibilitychange', onVisibility)

  /* 颜色热更新：换 token 或换 prop 时不用重新挂载 */
  const stopWatch = watch(
    () => props.dotColor,
    () => {
      dotCol = resolveColor()
    },
  )

  return () => {
    disposed = true
    stopWatch()
    cancelAnimationFrame(raf)
    document.removeEventListener('visibilitychange', onVisibility)
    canvas.removeEventListener('pointerdown', onDown)
    canvas.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
  }
}
</script>

<style scoped>
.orb {
  position: relative;
  /* 默认填满父容器；要固定尺寸由使用方的选择器覆盖（见 rootStyle 上方那段） */
  width: 100%;
  height: 100%;
  /* 原实现的下限：再小就连一个点都放不下了 */
  min-width: 24px;
  min-height: 24px;
  overflow: hidden;
}

/* 不接管指针：让「滑动离开空态」这类手势直接穿透到滚动容器 */
.orb--inert {
  pointer-events: none;
}

.orb-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
}
</style>
