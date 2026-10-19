/**
 * 把某个 .vue 的 `<script setup>` 真正执行一遍，验证 setup() 不抛异常。
 *
 * 为什么需要这个工具：`vue-tsc` 与 `vite build` **都不执行 setup**，
 * 所以「声明顺序错 / 引用了暂时性死区里的变量 / 访问 undefined 的属性」这类
 * 错误能一路通过类型检查与打包，只在浏览器里表现为**整页白屏**。
 * 典型反例（2026-10-17 真实事故）：`watch(() => chat.sending, ...)` 写在
 * `const chat = useChatStore()` 之前 —— watch 定义时就会调用 getter，
 * 于是 `chat` 还在 TDZ 里，抛 ReferenceError，setup 直接崩。
 *
 * 用法：node setup_smoke.mjs <vue 文件路径> [更多路径...]
 * 退出码 0 = 全部 setup 执行成功。
 *
 * <h3>它明确<b>不</b>做的事（别拿它当渲染测试）</h3>
 * <ul>
 *   <li><b>不求值 computed</b>：computed 是惰性的，setup 期间不执行 getter，
 *       所以「computed 里访问了 undefined 的属性」这类**渲染期**错误它抓不到。
 *       刻意不加「强制求值」—— 那种求值会在 `route.query.x.split()`、`props.y.map()`
 *       这类「要等真实数据」的闭包上误报一片，把工具变成噪音源。
 *       渲染期问题交给真机验证或组件测试。</li>
 *   <li><b>不校验 props 语义</b>：props 桩是「怎么取都不炸」的宽容代理，
 *       取错属性不会报错。那类问题由 vue-tsc 负责。</li>
 *   <li><b>不执行模板</b>：只跑 `<script setup>`。模板里的表达式错误不在范围内。</li>
 * </ul>
 * <p>已验证的灵敏度（自我校验，改动本文件后应重跑）：把 AiChat.vue 里的
 * `watch(() => chat.sending, ...)` 挪到 `const chat = useChatStore()` 之前，
 * 本工具会报 `ReferenceError: Cannot access 'chat' before initialization`。
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const ts = require(
  path.resolve(process.cwd(), 'node_modules/typescript/lib/typescript.js'),
)

/* ---------------- 桩：尽量贴近真实语义 ---------------- */

// ref 返回带 value 的普通对象；computed 保持**惰性**（与真实行为一致）
const ref = (v) => ({ value: v })
const computed = (fn) => ({
  get value() {
    return typeof fn === 'function' ? fn() : fn.get()
  },
})
const reactive = (o) => o
const readonly = (o) => o
// ⚠️ 关键：watch **立即求值** getter（这就是事故的成因），必须如实模拟
const watch = (src, _cb) => {
  if (typeof src === 'function') src()
  return () => {}
}
const watchEffect = (fn) => {
  fn()
  return () => {}
}
const nextTick = () => Promise.resolve()
const onMounted = () => {}
const onBeforeUnmount = () => {}
const onUnmounted = () => {}
const onActivated = () => {}
const onDeactivated = () => {}
const provide = () => {}
const inject = (k, d) => d
/*
 * props 桩：「怎么取都不炸」的宽容对象。
 *
 * <p>props 是**外部输入**，harness 无法知道父组件会传什么。若用 `defineProps()` 返回
 * `{}`，凡是在 setup 顶层读 `props.xxx.length` 的组件都会抛 TypeError
 * （真实例：`BannerCarousel` 的 `props.banners.length <= 1`），而那不是 bug，
 * 只是「没给数据」。所以要允许任意层级访问都返回一个**空数组的代理**：
 * `[].length` 存在、`[]` 转数字得 0、链式再取也不会抛。
 *
 * <p>代价：props 用错（取了不存在的属性）这类 bug 本工具就抓不到了。
 * 这是有意的取舍 —— 它只负责「setup 崩掉」，props 语义交给 vue-tsc。
 */
const lenientProp = new Proxy([], {
  get(target, key) {
    if (key in target) {
      const v = target[key]
      return typeof v === 'function' ? v.bind(target) : v
    }
    return lenientProp
  },
})
const propsProxy = new Proxy({}, { get: () => lenientProp })

const defineProps = () => propsProxy
const defineEmits = () => () => {}
const defineExpose = () => {}
const defineOptions = () => {}
const defineSlots = () => ({})
const defineModel = (v) => ({ value: v })
// withDefaults 是编译期宏：它把 defineProps() 的返回再套一层默认值。
// 桩只要能把它原样传回去即可 —— 我们验证的是「setup 不崩」，不是 props 语义
const withDefaults = (props) => props || {}
const h = () => ({})
const toRefs = (o) => o
const toRef = (o, k) => ({ get value() { return o?.[k] } })
const shallowRef = ref
const shallowReactive = reactive
const unref = (v) => (v && 'value' in v ? v.value : v)
const isRef = (v) => !!(v && typeof v === 'object' && 'value' in v)
const storeToRefs = () => ({})

// 万能桩：任何未显式建模的导入都落到这里。get 返回自身，调用返回自身，
// 这样链式访问不会中途抛错，但仍能被「必须真实存在」的地方揪出来。
const anyStub = new Proxy(function () {}, {
  get: (t, k) => {
    if (k === Symbol.toPrimitive) return () => ''
    if (k === 'then') return undefined
    return anyStub
  },
  apply: () => anyStub,
  construct: () => anyStub,
})

const routeStub = {
  path: '/ai-chat',
  fullPath: '/ai-chat',
  query: {},
  params: {},
  name: 'AiChat',
  meta: {},
}
const routerStub = {
  push: () => Promise.resolve(),
  replace: () => Promise.resolve(),
  back: () => {},
  currentRoute: { value: routeStub },
}
const messageStub = {
  error: () => {},
  success: () => {},
  warning: () => {},
  info: () => {},
}

const chatStoreStub = {
  sessionId: 1,
  sessionStatus: 'OPEN',
  sending: false,
  opening: false,
  canSend: true,
  hasMessages: false,
  summary: '',
  messages: [],
  stage: { stage: 'OPENING', label: '建立联结', step: 1, total: 3, goal: '' },
  usage: null,
  enter: async () => {},
  send: async () => {},
  reset: () => {},
  clearMessages: async () => 0,
  startNewSession: async () => {},
  close: async () => {},
}

/* ---------------- 浏览器全局桩 ----------------
 * setup 里出现浏览器 API 是**正常**的（例如 Dashboard 会 getComputedStyle 读 CSS 变量、
 * 登录页会探测 WebGL）。它们在 Node 里不存在，不补桩就会把这些页面误判成「有 bug」。
 * 原则：桩只保证「调用不抛异常」，不模拟真实返回值 —— 这个工具的职责是抓
 * 「setup 崩掉」这一类事故，不是做渲染验证。 */
const winStub = new Proxy(
  {},
  {
    get: (t, k) => {
      if (k === 'location') return { href: 'http://localhost/', hostname: 'localhost', origin: 'http://localhost' }
      if (k === 'localStorage' || k === 'sessionStorage') {
        const m = new Map()
        return {
          getItem: (x) => (m.has(x) ? m.get(x) : null),
          setItem: (x, v) => m.set(x, String(v)),
          removeItem: (x) => m.delete(x),
          clear: () => m.clear(),
        }
      }
      if (k === 'matchMedia' || k === 'getComputedStyle') return () => stubMedia()
      if (k === 'devicePixelRatio') return 1
      if (k === 'innerWidth') return 1440
      if (k === 'innerHeight') return 900
      return anyStub
    },
  },
)

function stubMedia() {
  return {
    matches: false,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    getPropertyValue: () => '',
    width: '100px',
    height: '100px',
  }
}

const docStub = new Proxy(
  {},
  {
    get: (t, k) => {
      if (k === 'documentElement' || k === 'body') {
        return {
          classList: { add: () => {}, remove: () => {}, contains: () => false, toggle: () => {} },
          style: {},
          setAttribute: () => {},
          removeAttribute: () => {},
          getAttribute: () => null,
          appendChild: () => {},
        }
      }
      if (k === 'createElement') {
        return () => ({
          style: {},
          setAttribute: () => {},
          appendChild: () => {},
          getContext: () => null,
          remove: () => {},
        })
      }
      if (k === 'getElementById' || k === 'querySelector') return () => null
      if (k === 'querySelectorAll') return () => []
      if (k === 'addEventListener' || k === 'removeEventListener') return () => {}
      return anyStub
    },
  },
)

const browserGlobals = {
  window: winStub,
  document: docStub,
  localStorage: winStub.localStorage,
  sessionStorage: winStub.sessionStorage,
  navigator: { userAgent: 'node', language: 'zh-CN', languages: ['zh-CN'] },
  location: winStub.location,
  getComputedStyle: () => stubMedia(),
  matchMedia: () => stubMedia(),
  requestAnimationFrame: (cb) => setTimeout(() => cb(Date.now()), 0),
  cancelAnimationFrame: (id) => clearTimeout(id),
  IntersectionObserver: class {
    constructor() {}
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return []
    }
  },
  ResizeObserver: class {
    constructor() {}
    observe() {}
    unobserve() {}
    disconnect() {}
  },
  MutationObserver: class {
    constructor() {}
    observe() {}
    disconnect() {}
  },
  PerformanceObserver: class {
    constructor() {}
    observe() {}
    disconnect() {}
  },
  Image: class {
    constructor() {
      this.onload = null
      this.onerror = null
    }
    set src(_v) {}
  },
}

const KNOWN = {
  ...browserGlobals,
  // CommonJS 兼容垫片：transpileModule 会把 `export ...` 改写成 `exports.x = ...`，
  // 而 Function 里没有 exports/module。少数文件的 <script setup> 里有 export，
  // 不给这两个桩就会报「exports is not defined」，与真实故障混淆。
  exports: {},
  module: { exports: {} },
  require: () => anyStub,
  ref,
  computed,
  reactive,
  readonly,
  watch,
  watchEffect,
  nextTick,
  onMounted,
  onBeforeUnmount,
  onUnmounted,
  onActivated,
  onDeactivated,
  provide,
  inject,
  defineProps,
  defineEmits,
  defineExpose,
  defineOptions,
  defineSlots,
  defineModel,
  withDefaults,
  h,
  toRefs,
  toRef,
  shallowRef,
  shallowReactive,
  unref,
  isRef,
  storeToRefs,
  useRoute: () => routeStub,
  useRouter: () => routerStub,
  message: messageStub,
  useChatStore: () => chatStoreStub,
  useAuthStore: () => ({ role: 'RESIDENT', user: { id: 1 } }),
  RISK_LEVEL_DESC: { A: '低风险', B: '中风险', C: '高风险', D: '危机' },
  getRecommendDoctors: async () => [],
}

/* ---------------- 主流程 ---------------- */

const IMPORT_RE = /^\s*import\s+[\s\S]*?\s+from\s+['"][^'"]+['"];?\s*$/gm
const BARE_IMPORT_RE = /^\s*import\s+['"][^'"]+['"];?\s*$/gm

function collectBindings(src) {
  const names = new Set()
  const re = /^\s*import\s+(type\s+)?([\s\S]*?)\s+from\s+['"][^'"]+['"]/gm
  let m
  while ((m = re.exec(src))) {
    if (m[1]) continue // type-only 导入运行时不存在，转译时会被擦掉
    let clause = m[2].trim()
    // 去掉 default 名 + 花括号
    clause = clause.replace(/^[A-Za-z_$][\w$]*\s*,?\s*/, (s) => s)
    const braces = clause.match(/\{([\s\S]*)\}/)
    const head = clause.replace(/\{[\s\S]*\}/, '').replace(/,/g, ' ').trim()
    if (head) names.add(head.split(/\s+/)[0])
    if (braces) {
      for (const part of braces[1].split(',')) {
        const n = part.trim().split(/\s+as\s+/).pop()
        if (n) names.add(n.trim())
      }
    }
  }
  return [...names].filter((n) => n && !n.startsWith('type '))
}

function extractSetup(file) {
  const src = fs.readFileSync(file, 'utf8')
  const m = src.match(/<script setup[^>]*>([\s\S]*?)<\/script>/)
  if (!m) return null
  return m[1]
}

function runSetup(file) {
  const raw = extractSetup(file)
  if (raw == null) return { file, skipped: true }

  const bindings = collectBindings(raw)
  const body = raw.replace(IMPORT_RE, '').replace(BARE_IMPORT_RE, '')

  // `import.meta` 只能在 ES module 里出现，塞进 Function 会直接 SyntaxError。
  // setup 里用它基本只有 env 判断，替换成字面量足够。
  const noMeta = body
    .replace(/import\.meta\.env\.DEV/g, 'true')
    .replace(/import\.meta\.env\.PROD/g, 'false')
    .replace(/import\.meta\.env\.MODE/g, "'development'")
    .replace(/import\.meta\.env\.BASE_URL/g, "'/'")
    .replace(/import\.meta\.env/g, '({})')
    .replace(/import\.meta\.(?!env)/g, '({})')

  // 擦掉类型；target 取 ES2020 以免可选链被降级
  const js = ts.transpileModule(noMeta, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
  }).outputText

  const stubNames = bindings.filter((b) => !(b in KNOWN))

  /*
   * ⚠️ 不能只注入「被 import 的名字」。
   * `defineProps` / `withDefaults` / `defineEmits` 是**编译期宏**，源码里根本没有
   * 对应的 import 语句（编译时被 @vitejs/plugin-vue 直接改写掉）；`window` /
   * `document` / `getComputedStyle` 则是浏览器全局。它们的名字都不会出现在
   * import 列表里，但运行时确实被引用。
   * 所以做法是：把 KNOWN 里**全部**桩都作为形参注入（形参只遮蔽函数体，不影响外部），
   * 再把「import 了但没建模」的名字挂万能桩。
   */
  const paramNames = [...new Set([...Object.keys(KNOWN), ...stubNames])]
  const argValues = [
    ...Object.keys(KNOWN).map((k) => KNOWN[k]),
    ...stubNames.map(() => anyStub),
  ]

  // 用 Function 包一层：setup 体是顺序执行的，与浏览器里一致
  let factory
  try {
    factory = new Function(...paramNames, `"use strict";\n${js}`)
  } catch (e) {
    // 构造阶段就会抛 SyntaxError：语法/宏改写问题，同样算「这页起不来」
    return { file, ok: false, error: `${e.name}: ${e.message}` }
  }
  try {
    factory(...argValues)
    return { file, ok: true }
  } catch (e) {
    return { file, ok: false, error: `${e.name}: ${e.message}` }
  }
}

const targets = process.argv.slice(2)
if (!targets.length) {
  console.error('用法：node setup_smoke.mjs <vue 文件> [更多...]')
  process.exit(2)
}

let failed = 0
for (const t of targets) {
  const r = runSetup(path.resolve(t))
  if (r.skipped) {
    console.log(`跳过（无 script setup）：${t}`)
    continue
  }
  if (r.ok) {
    console.log(`✓ ${path.basename(t)}  setup 执行通过`)
  } else {
    failed += 1
    console.log(`✗ ${path.basename(t)}  ${r.error}`)
  }
}
process.exit(failed ? 1 : 0)
