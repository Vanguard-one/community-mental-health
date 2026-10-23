<template>
  <!--
    全屏装饰层。`aria-hidden` + `pointer-events: none` 由使用方给外层包一层，
    这里只负责「填满父容器 + 一块 canvas」。
  -->
  <div ref="rootEl" class="lens">
    <canvas ref="canvasEl" class="lens-canvas" aria-hidden="true"></canvas>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

/*
 * Mosaic Lens —— 双通道 WebGL2 背景（Originkit 的 MosaicLens 移植到 Vue）。
 *
 * <h3>它到底在算什么</h3>
 * <pre>
 *   pass 1  field   → 离屏 FBO（RGBA16F）
 *           对每个像素做 74 次迭代的折叠 + 扭曲，累加出一个「光丝」场。
 *           这是整个效果里唯一贵的部分。
 *   pass 2  finish  → 屏幕
 *           把 field 的结果按马赛克格子取样；格子大小随「离指针的距离」分级
 *           （4 档），所以指针附近越精细、越远越碎。
 * </pre>
 *
 * <h3>与原始实现的四处必要改动</h3>
 * <ol>
 *   <li><b>去掉 `minWidth: 1200 / minHeight: 800`</b>。原组件是个独立演示块，
 *       最小尺寸能保住构图；但这里要当**全页背景**，那两个值会在窄屏上撑出
 *       横向滚动条，并把页面顶成 800px 高的死区。</li>
 *   <li><b>field 分辨率加了上限</b>。原实现固定取 `bw/2`。DPR 2 的 1440×900 页面上
 *       那是 1440×900 ≈ 130 万像素 × 74 层 ≈ 一帧 9600 万次迭代，全页背景跑不动。
 *       现在把 field 宽度封顶在 1024（约 30 万像素，降到 1/4 成本）；
 *       因为 pass 2 本来就要把它马赛克化，这点分辨率损失看不出来。</li>
 *   <li><b>颜色从 CSS 变量读</b>。原实现把色值写在 props 默认值里；本项目的规矩是
 *       色值只放 tokens.css，所以默认改为读 `--lens-bg / --lens-c1 / --lens-c2`
 *       （见 tokens.css）。props 仍然可以覆盖，方便做主题变体。</li>
 *   <li><b>补了减动效与页面隐藏的暂停</b>。原组件只有 RAF 循环，没有这两个开关。</li>
 * </ol>
 *
 * <h3>降级</h3>
 * 拿不到 WebGL2 / 着色器编译失败 / 浮点渲染目标不可用时，canvas 保持空白，
 * 但根元素仍有 `--lens-bg` 的底色 —— 页面不会变成一块黑洞。
 */

/* ================= 着色器 ================= */

/** 一个全屏三角形：比两个三角形的四边形少一次光栅化接缝，也没有顶点缓冲 */
const VERT_SRC = `#version 300 es
const vec2 P[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() { gl_Position = vec4(P[gl_VertexID], 0.0, 1.0); }
`

/** 层数。直接决定成本 —— 每减一半，pass 1 就便宜一半 */
const LAYERS = 74
/** 最终亮度增益 */
const GAIN = 0.54
/** 马赛克的精度档数（指针附近最大精细到 base/2^3） */
const TIERS = 4
/**
 * field 通道的宽度上限（像素）。
 *
 * 原实现固定取 `bw/2` 且不设上限。它在 1200×800 的演示块里是 600×400 ≈ 24 万像素，
 * 但当成全页背景、DPR 2 时 `bw/2` 会变成 1440 宽 ≈ 130 万像素 —— 乘上 74 层循环，
 * 一帧就是近一亿次迭代（每层还含 3 个 sin、1 个 exp2，属于高开销指令），
 * 集显上会直接掉帧。
 *
 * 896 是「成本回到原演示量级、肉眼看不出细节损失」的折中：
 * 896×504 ≈ 45 万像素 × 74 层 ≈ 3300 万次。
 * pass 2 本来就要把它马赛克化，所以这里的分辨率损失在粗格子上完全不可见，
 * 只有指针正下方那一档（最细 1.5px 格子）会略软 —— 全页背景不必为此付费。
 *
 * ⚠️ 如果目标机器是低端集显还觉得卡，第一个该调的就是这个值（降到 640 立竿见影）。
 */
const FIELD_MAX_W = 896

const FIELD_SRC = `#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uC1;
uniform vec3 uC2;
uniform float uSize;
uniform float uAngle;
out vec4 o;

const float TAU = 6.28318530718;
const float LAYERS = ${LAYERS.toFixed(1)};
const float GAIN = ${GAIN.toFixed(3)};
const vec2 CENTRE = vec2(-0.25, -0.56);
const float TILT = -2.2;
const float ZOOM = 1.05;
const float THETA = 2.12;
const float SHEAR = 0.956;
const float SHRINK = 0.949;
const vec2 WARP_FREQ = vec2(0.58, 2.8);
const vec2 WARP_AMP = vec2(0.16, 0.034);
const vec2 ASPECT = vec2(1.65, 0.22);
const float OFFSET = 0.31;
const float GLOW = 0.0021;
const float SOFT = 0.0019;
const float FALLOFF = 0.37;
const float PHASE = 64.0;
const float CYCLE = 0.16;
const float HUE_TRAVEL = 2.0;

mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

void main() {
  vec2 R = uRes;
  vec2 pos = (gl_FragCoord.xy - 0.5 * R) / R.y;

  pos = rot(uAngle) * pos / uSize;
  float t = uTime * 0.49 + PHASE;
  float breath = (-sin(uTime * 0.735) + sin(uTime * 0.49 + 1.0)) * 0.25 + 0.5;
  vec2 u = rot(TILT) * ((pos - CENTRE) * (ZOOM - breath * 0.085));
  mat2 fold = mat2(cos(THETA), sin(THETA), -SHEAR, cos(THETA));

  vec3 col = vec3(0.0);
  for (float i = 1.0; i <= LAYERS; i += 1.0) {
    u.x -= sin(u.y * WARP_FREQ.x + t + i * 0.007) * WARP_AMP.x;
    u.y -= sin(u.x * WARP_FREQ.y - t + i * 0.02) * WARP_AMP.y;
    u = fold * u * SHRINK;
    vec2 q = (u - vec2(OFFSET + breath * 0.1, 0.0)) * ASPECT;
    float g = GLOW / (dot(q, q) + SOFT) * (0.25 + breath * 0.4);
    float r = length(u);
    float k = sin(i * CYCLE + t * 1.2 + r * HUE_TRAVEL) * 0.5 + 0.5;
    col += g * mix(uC1, uC2, k) * (0.62 + 0.5 * k) * exp2(-r * FALLOFF);
  }
  vec3 x = max(col * GAIN, 0.0);
  col = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
  col = pow(clamp(col, 0.0, 1.0), vec3(0.85, 0.92, 0.98));
  col *= 1.0 - smoothstep(0.5, 1.6, length(pos)) * 0.07;
  o = vec4(col, 1.0);
}
`

const FINISH_SRC = `#version 300 es
precision highp float;
uniform sampler2D uField;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uBg;
uniform float uPaper;
uniform vec2 uMouse;
uniform float uOn;
uniform float uReach;
uniform float uTile;
out vec4 o;

const float TIERS = ${TIERS.toFixed(1)};

float ign(vec2 p, float f) { p += 5.588238 * mod(f, 64.0); return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y)); }

vec3 scene(vec2 uv) { return texture(uField, clamp(uv, 0.0, 1.0)).rgb; }

vec3 mosaic(vec2 frag, vec2 uv) {
  float base = uTile;
  float tier = 0.0;

  if (uOn > 1e-4) {
    vec2 cc = (floor(frag / base) + 0.5) * base;
    vec2 d = (cc - uMouse) / uReach;
    float w = uOn * exp(-dot(d, d));
    tier = floor(min(w * (TIERS + 1.0), TIERS));
  }
  if (tier >= TIERS) return scene(uv);
  float cell = base / exp2(tier);
  vec2 c = (floor(frag / cell) + 0.5) * cell;
  vec3 s = scene((c + cell * vec2(-0.25, -0.25)) / uRes) + scene((c + cell * vec2(0.25, -0.25)) / uRes)
         + scene((c + cell * vec2(-0.25, 0.25)) / uRes) + scene((c + cell * vec2(0.25, 0.25)) / uRes);
  return s * 0.25;
}

void main() {
  vec2 frag = gl_FragCoord.xy;
  vec3 L = max(mosaic(frag, frag / uRes), 0.0);

  vec3 dark = uBg + L * (1.0 - uBg);
  float strength = clamp(max(L.r, max(L.g, L.b)), 0.0, 1.0);
  vec3 paper = uBg * (1.0 - strength) + L * 0.96;
  vec3 col = mix(dark, paper, uPaper);
  col += (ign(frag, floor(uTime * 24.0)) - 0.5) / 255.0;
  o = vec4(clamp(col, 0.0, 1.0), 1.0);
}
`

/* ================= 颜色解析 ================= */

type RGB = [number, number, number]

const colorCache = new Map<string, RGB | null>()

/**
 * 把 CSS 颜色串解析成 0~1 的 RGB。
 *
 * 支持 `#rgb` / `#rrggbb` / `rgb[a]()` / `hsl[a]()`，以及
 * `var(--x, fallback)` 这种写法（取 fallback）。
 * 解析失败返回 null，由调用方决定兜底 —— **不要在这里抛异常**：
 * 一个拼错的色值不该让整个背景消失。
 */
function parseColor(input: string | undefined): RGB | null {
  if (!input) return null
  const key = String(input)
  if (colorCache.has(key)) return colorCache.get(key) ?? null
  let s = key.trim()
  const v = s.match(/^var\(\s*--[^,]+,\s*(.+)\)$/)
  if (v) s = v[1].trim()
  let out: RGB | null = null
  if (s.charAt(0) === '#') {
    let h = s.slice(1)
    if (h.length === 3 || h.length === 4) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2]
    if (h.length >= 6) {
      const r = parseInt(h.slice(0, 2), 16)
      const g = parseInt(h.slice(2, 4), 16)
      const b = parseInt(h.slice(4, 6), 16)
      if (Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b)) out = [r / 255, g / 255, b / 255]
    }
  } else {
    const m = s.match(/^(rgba?|hsla?)\(([^)]*)\)/i)
    if (m) {
      const parts = m[2].split(/[\s,/]+/).filter(Boolean)
      const f = (i: number) => parseFloat(parts[i])
      if (parts.length >= 3 && [0, 1, 2].every((i) => Number.isFinite(f(i)))) {
        if (m[1].toLowerCase().startsWith('rgb')) {
          const ch = (i: number) => (parts[i].endsWith('%') ? f(i) / 100 : f(i) / 255)
          out = [ch(0), ch(1), ch(2)]
        } else {
          const hh = (((f(0) % 360) + 360) % 360) / 360
          const ss = f(1) / 100
          const ll = f(2) / 100
          const q = ll < 0.5 ? ll * (1 + ss) : ll + ss - ll * ss
          const p = 2 * ll - q
          const hue = (t: number) => {
            t = t < 0 ? t + 1 : t > 1 ? t - 1 : t
            if (t < 1 / 6) return p + (q - p) * 6 * t
            if (t < 1 / 2) return q
            if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6
            return p
          }
          out = [hue(hh + 1 / 3), hue(hh), hue(hh - 1 / 3)]
        }
        out = out.map((c) => Math.min(1, Math.max(0, c))) as RGB
      }
    }
  }
  colorCache.set(key, out)
  return out
}

function clampN(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v
}

/* ================= 组件 ================= */

const props = withDefaults(
  defineProps<{
    /** 底色。不传则读根元素的 `--lens-bg` */
    background?: string
    /** 两档光色。不传则读 `--lens-c1` / `--lens-c2` */
    color1?: string
    color2?: string
    /** 0~100，动画快慢（50 ≈ 原速，100 = 2×） */
    speed?: number
    /** 50~200，画面缩放（100 = 原尺寸） */
    size?: number
    /** -180~180，整体旋转角 */
    angle?: number
    /** 6~48，马赛克基础格子（px，CSS 像素） */
    tileSize?: number
    /** 0~200，指针透镜强度（100 = 满强度） */
    hover?: number
    /** 10~800，透镜作用半径（px） */
    reach?: number
    /**
     * 1~2，设备像素比上限。
     *
     * 默认 2（原始实现的值）。降到 1.5 会让 pass 2 的填充率降低约 44% ——
     * 对「背后还有玻璃层、大面积阴影、会滚动的内容」的页面（如 AI 问诊）
     * 这是最有效的一档节流，代价只是马赛克格子略软一点（本效果本来就是碎格）。
     */
    dprCap?: number
  }>(),
  {
    background: '',
    color1: '',
    color2: '',
    speed: 100,
    size: 200,
    angle: 0,
    tileSize: 6,
    hover: 75,
    reach: 288,
    dprCap: 2,
  },
)

const rootEl = ref<HTMLDivElement | null>(null)
const canvasEl = ref<HTMLCanvasElement | null>(null)

/**
 * 兜底色 —— **仅用于 tokens 缺失时不让画面变成黑洞**。
 * 真正的色值来源是 tokens.css 的 `--lens-bg / --lens-c1 / --lens-c2`。
 */
const FALLBACK_BG = '#a483e6'
const FALLBACK_C1 = '#fff500'
const FALLBACK_C2 = '#4db5ff'

let dispose: (() => void) | null = null

onMounted(() => {
  dispose = start()
})

onBeforeUnmount(() => {
  dispose?.()
  dispose = null
})

/** 编译 + 链接，失败时返回 null 并打日志（不抛，让调用方降级） */
function link(gl: WebGL2RenderingContext, frag: string, label: string): WebGLProgram | null {
  const compile = (type: number, src: string): WebGLShader | null => {
    const sh = gl.createShader(type)
    if (!sh) return null
    gl.shaderSource(sh, src)
    gl.compileShader(sh)
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      // 把 infoLog 打出来 —— 否则「背景没出来」只能靠猜是哪一段 GLSL 错了
      console.error(`[MosaicLens] ${label} 着色器编译失败：`, gl.getShaderInfoLog(sh))
      gl.deleteShader(sh)
      return null
    }
    return sh
  }
  const vs = compile(gl.VERTEX_SHADER, VERT_SRC)
  const fs = compile(gl.FRAGMENT_SHADER, frag)
  if (!vs || !fs) {
    if (vs) gl.deleteShader(vs)
    return null
  }
  const prog = gl.createProgram()
  if (!prog) return null
  gl.attachShader(prog, vs)
  gl.attachShader(prog, fs)
  gl.linkProgram(prog)
  gl.deleteShader(vs)
  gl.deleteShader(fs)
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
    console.error(`[MosaicLens] ${label} 链接失败：`, gl.getProgramInfoLog(prog))
    gl.deleteProgram(prog)
    return null
  }
  return prog
}

function start(): (() => void) | null {
  const canvas = canvasEl.value
  const root = rootEl.value
  if (!canvas || !root) return null

  const gl = canvas.getContext('webgl2', {
    antialias: false,
    alpha: false,
    depth: false,
    stencil: false,
    // 这个场景不需要保留绘制缓冲，省一块带宽
    preserveDrawingBuffer: false,
  })
  if (!gl) {
    console.warn('[MosaicLens] 当前环境不支持 WebGL2，背景退化为纯色')
    return null
  }

  const fieldProg = link(gl, FIELD_SRC, 'field')
  const finishProg = link(gl, FINISH_SRC, 'finish')
  if (!fieldProg || !finishProg) {
    if (fieldProg) gl.deleteProgram(fieldProg)
    return null
  }

  const uField = {
    uRes: gl.getUniformLocation(fieldProg, 'uRes'),
    uTime: gl.getUniformLocation(fieldProg, 'uTime'),
    uC1: gl.getUniformLocation(fieldProg, 'uC1'),
    uC2: gl.getUniformLocation(fieldProg, 'uC2'),
    uSize: gl.getUniformLocation(fieldProg, 'uSize'),
    uAngle: gl.getUniformLocation(fieldProg, 'uAngle'),
  }
  const uFinish = {
    uField: gl.getUniformLocation(finishProg, 'uField'),
    uRes: gl.getUniformLocation(finishProg, 'uRes'),
    uTime: gl.getUniformLocation(finishProg, 'uTime'),
    uBg: gl.getUniformLocation(finishProg, 'uBg'),
    uPaper: gl.getUniformLocation(finishProg, 'uPaper'),
    uMouse: gl.getUniformLocation(finishProg, 'uMouse'),
    uOn: gl.getUniformLocation(finishProg, 'uOn'),
    uReach: gl.getUniformLocation(finishProg, 'uReach'),
    uTile: gl.getUniformLocation(finishProg, 'uTile'),
  }

  const vao = gl.createVertexArray()
  gl.bindVertexArray(vao)

  /* ---------- 离屏渲染目标 ---------- */
  const fbo = gl.createFramebuffer()
  let tex: WebGLTexture | null = null
  let fw = 0
  let fh = 0
  /*
   * 优先 RGBA16F：field 里的光强会超过 1，8 位色深会被直接裁掉、亮部失去层次。
   * 但 EXT_color_buffer_float 不是所有设备都有（尤其部分移动 GPU），
   * 拿不到就退回 RGBA8 —— 画面偏硬，但不会黑屏。
   */
  let half = !!gl.getExtension('EXT_color_buffer_float')

  const resizeTarget = (nw: number, nh: number) => {
    if (nw === fw && nh === fh && tex) return
    for (let attempt = 0; attempt < 2; attempt++) {
      if (tex) gl.deleteTexture(tex)
      tex = gl.createTexture()
      gl.bindTexture(gl.TEXTURE_2D, tex)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
      gl.texImage2D(
        gl.TEXTURE_2D,
        0,
        half ? gl.RGBA16F : gl.RGBA8,
        nw,
        nh,
        0,
        gl.RGBA,
        half ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE,
        null,
      )
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo)
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0)
      const ok = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE
      gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      if (ok || !half) break
      half = false
    }
    fw = nw
    fh = nh
  }

  /* ---------- 指针 ---------- */
  const ptr = { tx: 0, ty: 0, inside: false }
  const readPointer = (e: PointerEvent) => {
    const r = root.getBoundingClientRect()
    // 用 offsetWidth/rect.width 的比例换算：元素被 transform 缩放时 rect 会变，
    // 而 canvas 的绘图缓冲是按 offsetWidth 算的
    const sx = root.offsetWidth / (r.width || 1)
    const sy = root.offsetHeight / (r.height || 1)
    ptr.tx = (e.clientX - r.left) * sx
    ptr.ty = (e.clientY - r.top) * sy
    ptr.inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom
  }
  const onPointerOut = (e: PointerEvent) => {
    // relatedTarget 为 null = 指针离开了文档（而不是移到别的元素上）
    if (!e.relatedTarget) ptr.inside = false
  }

  /* ---------- 颜色 ---------- */
  const readVar = (name: string): string => getComputedStyle(root).getPropertyValue(name).trim()
  const VAR_REF = /^var\(\s*(--[A-Za-z0-9_-]+)\s*\)$/
  /**
   * 把读到的 CSS 值变成颜色，**额外支持一层 `var(--x)` 间接引用**。
   *
   * 为什么不直接信任 `getComputedStyle` 会替我们展开：
   * 自定义属性的 computed value 在各家浏览器里的展开时机并不一致（有的返回
   * `var(--x)` 原文，有的返回替换后的值）。而「一个页面覆盖 `--lens-bg` 指向
   * 另一个 token」正是本组件的常规用法（问诊页就是这么写的），一旦解析失败就会
   * **静默**落到 FALLBACK（紫底），页面会莫名其妙变成另一种配色 —— 所以这里
   * 主动再查一层：拿到 `var(--x)` 就去读 `--x` 的值。只跟一层，不做递归。
   */
  const readColor = (raw: string): RGB | null => {
    const ref = raw.match(VAR_REF)
    if (!ref) return parseColor(raw)
    return parseColor(readVar(ref[1]))
  }
  const resolveColor = (prop: string, varName: string, fallback: string): RGB => {
    const fromProp = parseColor(prop)
    if (fromProp) return fromProp
    const fromVar = readColor(readVar(varName))
    if (fromVar) return fromVar
    // 兜底色解析失败（几乎不可能）也要返回一个合法的三元组，不能让调用方拿到 null
    return parseColor(fallback) ?? ([0, 0, 0] as RGB)
  }
  const c1 = resolveColor(props.color1, '--lens-c1', FALLBACK_C1)
  const c2 = resolveColor(props.color2, '--lens-c2', FALLBACK_C2)
  const bg = resolveColor(props.background, '--lens-bg', FALLBACK_BG)

  /* ---------- 参数归一 ---------- */
  const speedN = clampN(Number(props.speed), 0, 100) / 50
  const sizeN = clampN(Number(props.size), 50, 200) / 100
  const angleN = (clampN(Number(props.angle), -180, 180) * Math.PI) / 180
  const tileN = clampN(Math.round(Number(props.tileSize)), 6, 48)
  const hoverN = clampN(Number(props.hover), 0, 200) / 100
  const reachN = clampN(Number(props.reach), 10, 800)
  const dprCapN = clampN(Number(props.dprCap), 1, 2)

  /*
   * uPaper 决定最终走「暗底 + 发光」还是「纸感浅底」。
   * 由底色亮度推出来：底色越亮，越应该走纸感（否则亮底 + 亮光会糊成一片白）。
   */
  const bgLum = 0.2126 * bg[0] + 0.7152 * bg[1] + 0.0722 * bg[2]
  const paper = clampN((bgLum - 0.35) / 0.3, 0, 1)

  /* ---------- 动画状态 ---------- */
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  let mx = 0
  let my = 0
  let on = 0
  let raf = 0
  let last = -1
  let clock = 0
  let disposed = false

  const drawFrame = (now: number) => {
    const dt = last < 0 ? 0 : clampN((now - last) / 1000, 0, 0.05)
    last = now
    clock = (clock + dt * speedN) % 3600

    const dpr = Math.min(window.devicePixelRatio || 1, dprCapN)
    const cw = canvas.clientWidth || 1
    const ch = canvas.clientHeight || 1
    const bw = Math.max(1, Math.round(cw * dpr))
    const bh = Math.max(1, Math.round(ch * dpr))
    if (canvas.width !== bw || canvas.height !== bh) {
      canvas.width = bw
      canvas.height = bh
    }
    /*
     * field 分辨率：保持宽高比，宽度封顶 FIELD_MAX_W。
     * 原实现是 `bw/2`；全页 + 74 层循环下必须设上限，否则一帧上亿次迭代。
     * 因为 pass 2 一定会把它马赛克化，肉眼分辨不出这里少了多少细节。
     */
    const halfW = bw / 2
    const scale = halfW > FIELD_MAX_W ? FIELD_MAX_W / halfW : 1
    resizeTarget(Math.max(1, Math.round(halfW * scale)), Math.max(1, Math.round((bh / 2) * scale)))

    const present = ptr.inside ? 1 : 0
    if (present && on < 0.02) {
      // 指针刚进入时不要从 (0,0) 滑过来，直接落位
      mx = ptr.tx
      my = ptr.ty
    }
    on += (present - on) * (1 - Math.exp(-dt * 5))
    const k = 1 - Math.exp(-dt * 16)
    mx += (ptr.tx - mx) * k
    my += (ptr.ty - my) * k

    const sx = bw / cw
    const sy = bh / ch

    // ---- pass 1 → FBO ----
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo)
    gl.viewport(0, 0, fw, fh)
    gl.useProgram(fieldProg)
    gl.uniform2f(uField.uRes, fw, fh)
    gl.uniform1f(uField.uTime, clock)
    gl.uniform3f(uField.uC1, c1[0], c1[1], c1[2])
    gl.uniform3f(uField.uC2, c2[0], c2[1], c2[2])
    gl.uniform1f(uField.uSize, sizeN)
    gl.uniform1f(uField.uAngle, angleN)
    gl.drawArrays(gl.TRIANGLES, 0, 3)

    // ---- pass 2 → 屏幕 ----
    gl.bindFramebuffer(gl.FRAMEBUFFER, null)
    gl.viewport(0, 0, bw, bh)
    gl.useProgram(finishProg)
    gl.activeTexture(gl.TEXTURE0)
    gl.bindTexture(gl.TEXTURE_2D, tex)
    gl.uniform1i(uFinish.uField, 0)
    gl.uniform2f(uFinish.uRes, bw, bh)
    gl.uniform1f(uFinish.uTime, clock)
    gl.uniform3f(uFinish.uBg, bg[0], bg[1], bg[2])
    gl.uniform1f(uFinish.uPaper, paper)
    // gl_FragCoord 原点在左下，指针坐标原点在左上 → Y 要翻过来
    gl.uniform2f(uFinish.uMouse, mx * sx, bh - my * sy)
    gl.uniform1f(uFinish.uOn, on * hoverN)
    gl.uniform1f(uFinish.uReach, reachN * sy)
    gl.uniform1f(uFinish.uTile, Math.max(1, Math.round(tileN * dpr)))
    gl.drawArrays(gl.TRIANGLES, 0, 3)
  }

  const loop = (now: number) => {
    if (disposed) return
    raf = requestAnimationFrame(loop)
    drawFrame(now)
  }

  /*
   * 减动效：只画一帧然后停。
   * 全屏 60fps 的流动纹理正是「减少动态」这条偏好要避开的；
   * 但又不能不出图（那会是一块纯色），所以取「冻结的第一帧」。
   * 指针事件也不再注册 —— 冻结状态下透镜没有意义。
   */
  if (reduced) {
    drawFrame(0)
  } else {
    /*
     * 先同步画第一帧，再挂 RAF。
     *
     * 否则从「挂载」到「第一次 RAF 回调」之间（路由切换动画期间可能超过 16ms），
     * canvas 是空白的 —— 透出来的只有 `.lens` 的 `--lens-bg` 纯色。
     * 观感就是「先闪一下纯色，图案才长出来」，在页面切换时特别明显。
     * 这一帧画完 `last` 已经是 0，下一帧 dt 会被钳到 50ms，肉眼看不出来。
     */
    drawFrame(0)
    window.addEventListener('pointermove', readPointer, { passive: true })
    window.addEventListener('pointerdown', readPointer, { passive: true })
    document.addEventListener('pointerout', onPointerOut)
    raf = requestAnimationFrame(loop)
  }

  /*
   * 页面隐藏时停 RAF。
   * 浏览器对后台标签页本来就会降频，但不停下来仍会占着一个 GPU 上下文和绘制循环；
   * 回到前台再无条件重启一次（`clock` 连续，不会跳帧）。
   */
  const onVisibility = () => {
    if (reduced) return
    if (document.hidden) {
      cancelAnimationFrame(raf)
    } else {
      last = -1
      raf = requestAnimationFrame(loop)
    }
  }
  document.addEventListener('visibilitychange', onVisibility)

  return () => {
    disposed = true
    cancelAnimationFrame(raf)
    document.removeEventListener('visibilitychange', onVisibility)
    if (!reduced) {
      window.removeEventListener('pointermove', readPointer)
      window.removeEventListener('pointerdown', readPointer)
      document.removeEventListener('pointerout', onPointerOut)
    }
    if (tex) gl.deleteTexture(tex)
    gl.deleteFramebuffer(fbo)
    gl.deleteVertexArray(vao)
    gl.deleteProgram(fieldProg)
    gl.deleteProgram(finishProg)
    /*
     * 主动丢弃上下文：SPA 里登录页会反复进出，不丢的话每次都留一个上下文，
     * 而浏览器对同时存在的 WebGL 上下文有上限（约 16 个），到顶后新页面会拿不到。
     */
    gl.getExtension('WEBGL_lose_context')?.loseContext()
  }
}
</script>

<style scoped>
.lens {
  position: absolute;
  inset: 0;
  overflow: hidden;
  /* WebGL 不可用时的兜底底色：整个背景至少还是一块有色块，而不是白屏 */
  background-color: var(--lens-bg, transparent);
}

.lens-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}
</style>
