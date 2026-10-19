/**
 * 动效引擎 · recipe 驱动
 * ----------------------------------------------------------------------------
 * 移植自 guizang-ppt-skill（歸藏）的瑞士风模板（assets/template-swiss.html）：
 * **recipe 体系与缓动常数（IBM Carbon Motion）逐字照搬**，但驱动层由 Motion One
 * 换成原生 Web Animations API —— Motion One 本身就是 WAAPI 的薄封装，参数语义等价，
 * 换成原生可零网络依赖（原模板需本地 64KB 文件或 CDN 二选一）。
 *
 * 照搬的核心设计原则：**每个区块一个语义化 recipe，不做统一 fade-up**。
 * 数字是「滚动计数 + 弹入」，卡片是「依序揭示并轻微放大到位」，标题是「逐行升起」，
 * 下划线是「从左描出」—— 动作绑在内容语义上，而不是所有元素同一个淡入。
 *
 * 用法（两条约定，与示例页 `.workbuddy/demo/redesign-preview.html` 完全一致）：
 *   <div v-reveal="'grid'">         容器：声明这个区块用哪个 recipe
 *     <div data-anim="card">…</div> 元素：声明自己怎么入场
 *     <strong data-anim="pop" data-count>128</strong>  数字滚动
 *   </div>
 *
 * 四条硬约束：
 *   ① 能力检测不过 / 用户开了「减少动效」→ 引擎整体不启动，不挂 `anim-ready`，
 *      于是 CSS 前置态不成立，内容静态可读（skill 的降级哲学）；
 *   ② 只动 transform / opacity，都是合成层属性，不触发 layout；
 *   ③ 动画播完必须 `cancel()` —— 否则 `fill: both` 会长期压住元素自身的悬停变换；
 *   ④ 滚动触发用 IntersectionObserver，触发一次即 unobserve。
 */

import type { App, Directive } from 'vue'

const EASE_PROD = 'cubic-bezier(.2, 0, .38, .9)' // 落定：快出慢停
const EASE_ENTRY = 'cubic-bezier(0, 0, .3, 1)' // 入场：缓入急停

/** 动作表：`data-anim` 的值 → 起止帧。x/y 像素位移，s 等比缩放，sx 横向缩放（描线专用） */
interface Frames {
  x: [number, number]
  y: [number, number]
  s: [number, number]
  sx?: [number, number]
}

const KINDS: Record<string, Frames> = {
  chrome: { x: [0, 0], y: [0, 0], s: [1, 1] }, // 纯淡入
  line: { x: [0, 0], y: [10, 0], s: [1, 1] }, // 逐行升起
  rise: { x: [0, 0], y: [14, 0], s: [1, 1] }, // 块体上浮
  card: { x: [0, 0], y: [20, 0], s: [0.96, 1] }, // 卡片揭示（照搬 rGridReveal）
  left: { x: [-24, 0], y: [0, 0], s: [1, 1] },
  right: { x: [24, 0], y: [0, 0], s: [1, 1] },
  pop: { x: [0, 0], y: [0, 0], s: [0.6, 1] }, // 缩放弹入
  draw: { x: [0, 0], y: [0, 0], s: [1, 1], sx: [0, 1] }, // 描线
}

/**
 * recipe 表。一条 recipe 只回答两件事：**从哪些元素里取、按什么节奏错峰**；
 * 「这个元素做什么动作」仍由元素自己的 `data-anim` 决定（缺省时用 recipe 的 fallback）。
 *
 *   scope  self  只动容器自身
 *          kids  只取直接子元素
 *          anim  取容器内所有 `[data-anim]`
 */
interface Recipe {
  scope: 'self' | 'kids' | 'anim'
  step: number // 错峰步长（秒）
  base: number // 起跑延迟（秒）
  fallback: keyof typeof KINDS
}

const RECIPES: Record<string, Recipe> = {
  // 单个块整体上浮（详情页头部、单卡片）
  rise: { scope: 'self', step: 0, base: 0, fallback: 'rise' },
  // 容器自身就是一张卡
  card: { scope: 'self', step: 0, base: 0, fallback: 'card' },
  // 页头：标题 → 副标 → 右侧操作，90ms 递进
  header: { scope: 'anim', step: 0.09, base: 0, fallback: 'rise' },
  // 卡片网格依序揭示（参数照搬 skill 的 rGridReveal：90ms 错开、y 20→0、scale .96→1）
  grid: { scope: 'kids', step: 0.09, base: 0.3, fallback: 'card' },
  // 两栏卡阶梯式
  stagger: { scope: 'anim', step: 0.11, base: 0, fallback: 'rise' },
  // 登录页品牌区：走 hero 档（160ms/step）—— 这一块是整屏唯一的「仪式感」区域，
  // 比正文的 75ms 更慢，才有封面感（照搬 skill 的 rHero 节奏）
  brand: { scope: 'anim', step: 0.16, base: 0.1, fallback: 'line' },
  // 默认：整页内容按 DOM 顺序 80ms 递进（滚动到哪、播到哪）
  enter: { scope: 'anim', step: 0.08, base: 0, fallback: 'rise' },
}

/* ============================ 引擎 ============================ */

let ready = false

/** 能力检测：必须与「挂 anim-ready」的判定完全一致，否则内容会被前置态永久藏住 */
function canAnimate(): boolean {
  if (typeof window === 'undefined') return false
  if (!window.Element || !Element.prototype.animate) return false
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
  return true
}

function tf(k: Frames, i: 0 | 1): string {
  const sx = k.sx ?? [1, 1]
  return (
    `translate3d(${k.x[i]}px,${k.y[i]}px,0)` +
    ` scale(${k.s[i]}) scaleX(${sx[i]})`
  )
}

function play(el: Element, kind: string, opt: { duration?: number; delay?: number; easing?: string } = {}) {
  if (!(el instanceof HTMLElement)) return
  const k = KINDS[kind] ?? KINDS.rise
  const anim = el.animate(
    [
      { opacity: 0, transform: tf(k, 0) },
      { opacity: 1, transform: tf(k, 1) },
    ],
    {
      duration: (opt.duration ?? 0.55) * 1000,
      delay: (opt.delay ?? 0) * 1000,
      easing: opt.easing ?? EASE_ENTRY,
      fill: 'both',
    },
  )
  // ③ 播完撤掉 fill，把元素交还给 CSS —— 否则 :hover 的 transform 会被整条压掉
  anim.onfinish = () => {
    anim.cancel()
    el.classList.add('anim-done')
  }
}

/**
 * 数字滚动。
 * 在元素还不可见时（卡片仍是 opacity:0）就先归零 —— 所以看不到「12 跳成 0」的穿帮。
 * 结尾必写回精确值，防止中途被打断而停在中间数。
 * 只处理「纯数字 +（可选）同行后缀」的文本节点，其余形态一律跳过。
 */
function countUp(el: Element, delay: number, dur = 0.8) {
  if (!(el instanceof HTMLElement)) return
  const node = el.firstChild
  if (!node || node.nodeType !== 3) return
  const raw = node.textContent ?? ''
  const target = parseFloat(raw.replace(/[^\d.]/g, ''))
  if (!Number.isFinite(target) || target <= 0) return
  const suffix = raw.replace(/[\d.]+/g, '')
  node.textContent = `0${suffix}`
  const start = performance.now() + delay * 1000
  const span = dur * 1000
  const tick = (now: number) => {
    const p = (now - start) / span
    if (p < 0) {
      requestAnimationFrame(tick)
      return
    }
    if (p >= 1) {
      node.textContent = `${target}${suffix}` // 收尾写回精确值
      return
    }
    const e = 1 - Math.pow(1 - p, 3) // 三次缓出，与 EASE_PROD 同族
    node.textContent = `${Math.round(target * e)}${suffix}`
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}

/** 取本次 recipe 要动的元素清单（DOM 顺序） */
function pick(box: HTMLElement, r: Recipe): Element[] {
  if (r.scope === 'self') return [box]
  if (r.scope === 'kids') return Array.from(box.children)
  return Array.from(box.querySelectorAll('[data-anim]'))
}

/** 执行一条 recipe */
function run(box: HTMLElement, name: string) {
  const r = RECIPES[name] ?? RECIPES.enter
  const list = pick(box, r)
  list.forEach((el, i) => {
    if (!(el instanceof HTMLElement)) return
    const kind = el.dataset.anim || r.fallback
    const delay = r.base + i * r.step
    // 与 skill 的「落定类动作走 prod 曲线」一致：弹入用快出慢停
    play(el, kind, { delay, easing: kind === 'pop' ? EASE_PROD : EASE_ENTRY })
    // 数字滚动：挂在元素上，与它自己的入场同时起步（延后一点，避免和位移打架）
    el.querySelectorAll('[data-count]').forEach((n) => countUp(n, delay + 0.26))
    if (el.hasAttribute('data-count')) countUp(el, delay + 0.26)
  })

  /*
   * 收口：容器内**所有** `[data-anim]` 立刻补上 anim-done，并摘掉 pending 标记。
   *
   * 为什么敢一次性全补：WAAPI 的动画效果处在「动画来源」，优先级高于普通作者声明，
   * 所以对已经开始播的元素，`.anim-done { opacity: 1 }` 完全压不动它们（仍按关键帧走）；
   * 而对**没被这条 recipe 覆盖**的元素（比如配方是 self，子元素却写了 data-anim），
   * 这一步就是兜底 —— 否则它们会被前置态永久藏住，是这套机制唯一致命的失败模式。
   */
  box.removeAttribute('data-reveal-pending')
  box.querySelectorAll('[data-anim]:not(.anim-done)').forEach((el) => el.classList.add('anim-done'))
  if (box.hasAttribute('data-anim')) box.classList.add('anim-done')
}

/* ============================ 观察器 ============================ */

let observer: IntersectionObserver | null = null

function getObserver(): IntersectionObserver | null {
  if (observer) return observer
  if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return null
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (!e.isIntersecting) return
        const el = e.target as HTMLElement
        observer!.unobserve(el) // ④ 只触发一次
        const name = el.dataset.reveal
        if (name) run(el, name)
      })
    },
    /*
     * 阈值用 0 而不是示例页的 0.15。
     *
     * 示例页每个 view 都是「一屏一块」，0.15 够用；但本项目里 `v-reveal` 会挂在
     * **整页容器**上（比如测评中心的一张网格），它可能高达数千像素 ——
     * 那时「15% 面积进入视口」在物理上不可达，recipe 永远不会触发，内容会被前置态
     * 永久藏住。改成「上边缘进入视口上方 92% 处即触发」，
     * 既保留了「滑到跟前才开始」的观感，又不会被内容高度绑架。
     */
    { threshold: 0, rootMargin: '0px 0px -8% 0px' },
  )
  return observer
}

/* ============================ 指令 ============================ */

type RevealValue = string | { recipe?: string } | undefined

/**
 * `v-reveal="'grid'"` —— 挂在区块容器上，声明它用哪条 recipe。
 * 不传值时按 `enter` 处理（整块内容依次升起）。
 */
const vReveal: Directive<HTMLElement, RevealValue> = {
  mounted(el, binding) {
    const name =
      (typeof binding.value === 'string' ? binding.value : binding.value?.recipe) || 'enter'
    el.dataset.reveal = name

    if (!canAnimate()) return // ① 降级出口：不挂前置态，内容静态可读

    /*
     * `scope: 'self'` 的配方（rise / card）动的是容器自身，而容器上不一定写了
     * data-anim。补一个，CSS 前置态才有东西可挂 —— 否则它会先正常画一帧再被藏起来，
     * 出现「闪一下又淡入」。
     */
    const recipe = RECIPES[name] ?? RECIPES.enter
    if (recipe.scope === 'self' && !el.dataset.anim) el.dataset.anim = recipe.fallback

    if (!ready) {
      ready = true
      // 与 CSS 约定一致：只有这类存在时 [data-anim] 才被藏起来
      document.documentElement.classList.add('anim-ready')
    }
    /*
     * 前置态标记。挂在**容器**而不是全局 —— 这样「写了 data-anim 却忘了包 v-reveal」
     * 的元素不会被永久藏住（它压根不在 pending 容器里），失败模式从「看不见内容」
     * 降级为「缺少入场动画」。这一处与示例页不同，是为了 SPA 里几十个页面并存时的安全。
     */
    el.setAttribute('data-reveal-pending', '')

    const io = getObserver()
    if (io) {
      io.observe(el)
    } else {
      run(el, name) // 没有 IO（极老浏览器）→ 直接播，别把内容吊着
    }
  },
  unmounted(el) {
    observer?.unobserve(el)
  },
}

export function setupReveal(app: App) {
  app.directive('reveal', vReveal)
}

export default vReveal
