<template>
  <!--
    白点漂移场 —— 登录页品牌区的背景装饰层。

    <h3>它是什么</h3>
    一层 WebGL 片元着色器画出的「缓慢漂移的点阵」：整片点以极低速度平移，
    同时每个点在自己的格内小幅游走（相邻格的相位连续 → 摆动呈波面扫过，
    而不是各自乱跳）；再叠一层低频波场调制点的亮度，暗处只变暗不消失。

    <h3>它是独立实现，不是移植</h3>
    ⚠️ 视觉灵感来自 guizang-ppt-skill（歸藏）的瑞士风模板（AGPL-3.0），但**本文件是
       独立重写的实现**，没有沿用其着色器代码、结构或参数命名 —— 这是刻意为之：
       那个模板是 AGPL-3.0，逐行照搬会把整个项目拖进传染性许可。
       这里的做法更简单也更契合本项目：着色器只输出「黑底 + 白点」的亮度蒙版，
       再由 CSS 的 `mix-blend-mode: screen` 把它叠到蓝底上 ——
       原模板那套「主副双层网格 + lineColor/bgColor 双分支」在这里没有存在必要，
       而且本项目已明确「页面不出现任何网格线」，网格层本就不该有。

    <h3>三件套降级（缺一不可）</h3>
      ① 拿不到 WebGL → 退到 CSS 静态点阵（见本组件样式里的 background-image），
         点距与着色器一致，肉眼只少了「在动」；
      ② 系统开启「减少动效」→ 只画一帧静态点阵，不起 RAF；
      ③ 标签页不可见 → 停掉 RAF，回前台时补回暂停时长（不跳帧）。
    另：`.auth-card` 必须带 `isolation: isolate`，把 screen 混合锁在卡内，
    否则它会去和外面的玻璃框底色混合，整块发亮发灰。
  -->
  <canvas ref="cv" class="dot-field" aria-hidden="true" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 点距（CSS px）。越小越密 */
    spacing?: number
  }>(),
  { spacing: 12 },
)

const cv = ref<HTMLCanvasElement | null>(null)

/*
 * 着色器。只输出亮度蒙版：a=1 是点，a=0 是空。
 * CSS 侧 `mix-blend-mode: screen` + `opacity: .42` 负责把它变成「蓝底白点」。
 */
const VERT = 'attribute vec2 a_pos;void main(){gl_Position=vec4(a_pos,0.0,1.0);}'

const FRAG = `
precision highp float;
uniform vec2  u_res;
uniform float u_time;
uniform float u_density;
uniform vec2  u_pointer;
uniform float u_pointer_on;

void main() {
  vec2 uv = gl_FragCoord.xy / u_res;
  float aspect = u_res.x / u_res.y;
  // 把 x 按宽高比拉伸，圆点才不会被压成椭圆
  vec2 p = vec2(uv.x * aspect, uv.y);

  // 整片点缓慢漂移。速度刻意压得很低（约 13px/s），
  // —— 快到能被注意到的背景就是干扰，目标是「盯着看才发现它在动」。
  vec2 drift = p + vec2(u_time * 0.020, u_time * 0.013);

  // 低频波场：决定哪些区域亮、哪些暗。
  // 暗区**只变暗不消失**（下方取 0.25 + 0.75*wave）——
  // 若让暗处把点杀光，整片会显得「疏」，且密度感随呼吸大幅波动。
  float wave = 0.5 + 0.5 * sin(drift.x * 1.9 + u_time * 0.30) * cos(drift.y * 2.1 - u_time * 0.24);

  // 每格的摆动相位：由 drift 直接决定 → 相邻格只差约 0.23rad，
  // 于是摆动是连续波面扫过，不是每点随机抖动。
  // 幅度 0.18 格，点半径上限 0.16 —— 合成最大偏移 0.18*1.414 + 0.16 = 0.41 < 0.5，
  // 点永远落在自己格内，不会被格边界切掉一半（那会闪）。
  vec2 sway = vec2(sin(drift.y * 12.0 + u_time * 0.50),
                   cos(drift.x * 10.0 + u_time * 0.42)) * 0.18;
  vec2 cell = fract(drift * u_density + sway) - 0.5;

  float d = length(cell);
  float mask = 1.0 - smoothstep(0.06, 0.16, d);
  mask *= 0.25 + 0.75 * wave;

  // 指针邻域轻微提亮：反馈是「背景对你有反应」，但不做跟随光斑（那太抢戏）
  vec2 pointer = vec2(u_pointer.x * aspect, u_pointer.y);
  float near = exp(-length(p - pointer) * 5.0) * u_pointer_on;
  mask *= 1.0 + near * 0.6;

  gl_FragColor = vec4(vec3(clamp(mask, 0.0, 1.0)), 1.0);
}
`

let raf = 0
let pausedAt = 0
let t0 = 0
let cleanup: (() => void) | null = null

onMounted(() => {
  const canvas = cv.value
  if (!canvas) return

  const gl = canvas.getContext('webgl', { alpha: false, antialias: true })
  if (!gl) return // ① 拿不到 WebGL → 保留 CSS 静态点阵兜底

  const compile = (type: number, src: string) => {
    const s = gl.createShader(type)
    if (!s) return null
    gl.shaderSource(s, src)
    gl.compileShader(s)
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      console.warn('[DotField] 着色器编译失败：', gl.getShaderInfoLog(s))
      return null
    }
    return s
  }

  const vs = compile(gl.VERTEX_SHADER, VERT)
  const fs = compile(gl.FRAGMENT_SHADER, FRAG)
  if (!vs || !fs) return

  const prog = gl.createProgram()
  if (!prog) return
  gl.attachShader(prog, vs)
  gl.attachShader(prog, fs)
  gl.linkProgram(prog)
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
    console.warn('[DotField] program 链接失败：', gl.getProgramInfoLog(prog))
    return
  }
  gl.useProgram(prog)

  const buf = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, buf)
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1]),
    gl.STATIC_DRAW,
  )
  const loc = gl.getAttribLocation(prog, 'a_pos')
  gl.enableVertexAttribArray(loc)
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)

  const uRes = gl.getUniformLocation(prog, 'u_res')
  const uTime = gl.getUniformLocation(prog, 'u_time')
  const uDensity = gl.getUniformLocation(prog, 'u_density')
  const uPointer = gl.getUniformLocation(prog, 'u_pointer')
  const uPointerOn = gl.getUniformLocation(prog, 'u_pointer_on')

  /** 点距换算：着色器的格是按「uv 高度」划分的，所以密度 = 高度 / 点距 */
  let density = 52

  function resize() {
    if (!canvas || !gl) return
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const w = Math.max(1, Math.round(canvas.clientWidth * dpr))
    const h = Math.max(1, Math.round(canvas.clientHeight * dpr))
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w
      canvas.height = h
    }
    gl.viewport(0, 0, w, h)
    density = canvas.clientHeight / props.spacing
  }

  const pointer = { x: 0.5, y: 0.5, on: 0 }
  // 指针换算到 canvas 局部空间，且 y 要翻转（gl_FragCoord 原点在左下）
  const onMove = (e: PointerEvent) => {
    const r = canvas.getBoundingClientRect()
    if (!r.width || !r.height) return
    pointer.x = (e.clientX - r.left) / r.width
    pointer.y = 1 - (e.clientY - r.top) / r.height
    pointer.on = 1
  }
  const onLeave = () => {
    pointer.on = 0
  }

  function draw(t: number) {
    // 再判一次：函数声明会被提升，TS 不会把外层的窄化带进来
    if (!gl || !canvas) return
    gl.uniform2f(uRes, canvas.width, canvas.height)
    gl.uniform1f(uTime, t)
    gl.uniform1f(uDensity, density)
    gl.uniform2f(uPointer, pointer.x, pointer.y)
    gl.uniform1f(uPointerOn, pointer.on)
    gl.drawArrays(gl.TRIANGLES, 0, 6)
  }

  const ro = new ResizeObserver(resize)
  ro.observe(canvas)
  resize()

  const host = canvas.parentElement
  host?.addEventListener('pointermove', onMove)
  host?.addEventListener('pointerleave', onLeave)

  const reduced =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches

  if (reduced) {
    draw(0) // ② 只画静态一帧：保留点阵质感，不做漂移
  } else {
    t0 = performance.now()
    const loop = (now: number) => {
      raf = requestAnimationFrame(loop)
      // 卡片在窄屏会被 display:none → 跳过着色，别白烧 GPU
      if (canvas.clientWidth === 0) return
      draw((now - t0) / 1000)
    }
    raf = requestAnimationFrame(loop)

    // ③ 后台标签页停 RAF，回前台补回暂停时长（否则时间会跳一大段，点会「瞬移」）
    const onVisibility = () => {
      if (document.hidden) {
        pausedAt = performance.now()
        cancelAnimationFrame(raf)
        raf = 0
      } else if (!raf) {
        t0 += performance.now() - pausedAt
        raf = requestAnimationFrame(loop)
      }
    }
    document.addEventListener('visibilitychange', onVisibility)

    cleanup = () => document.removeEventListener('visibilitychange', onVisibility)
  }

  cleanup = (() => {
    const prev = cleanup
    return () => {
      prev?.()
      ro.disconnect()
      host?.removeEventListener('pointermove', onMove)
      host?.removeEventListener('pointerleave', onLeave)
    }
  })()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  raf = 0
  cleanup?.()
  cleanup = null
})
</script>

<style scoped>
.dot-field {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  pointer-events: none;
  /* 混合与不透明度：着色器输出的是黑底白点蒙版，screen 叠加后即为「蓝底白点」。
     opacity .42 是上限 —— 再高会让点盖上白字，实测最亮点像素上的白字对比度会跌到 AA 以下。 */
  opacity: 0.42;
  mix-blend-mode: screen;
  /*
   * WebGL 不可用时的兜底：静态点阵，点距与着色器一致（12px）。
   * WebGL 正常时 canvas 会用自己的绘制内容整块盖住这层 background，不需要额外判断。
   */
  background-image: radial-gradient(rgba(255, 255, 255, 0.5) 0.9px, transparent 1.1px);
  background-size: 12px 12px;
}
</style>
