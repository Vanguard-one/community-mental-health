/**
 * cdp-shot.mjs —— 用 Chrome DevTools Protocol 给「需要登录 / 需要点击才出现」的页面截图。
 *
 * 为什么不用 chrome --screenshot：
 *   `--screenshot` 只能截静态首屏，拿不到弹窗、抽屉、下拉这类交互后才渲染的 DOM。
 *   本脚本先用 CDP 把登录态写进 localStorage，再导航、再点按钮，最后截图。
 *
 * 用法：
 *   node cdp-shot.mjs \
 *     --base http://127.0.0.1:5174 \
 *     --path /admin/accounts \
 *     --token <JWT> \
 *     --userinfo '{"token":"...","userId":1,"username":"admin","role":"ADMIN",...}' \
 *     --click 审核 \
 *     --text .ant-modal \
 *     --out shot.png
 *
 * ⚠️ Git Bash（MSYS）下必须先 `export MSYS_NO_PATHCONV=1`：
 *    否则 `--path /admin/accounts` 会被当成 POSIX 路径转换成
 *    `C:/Program Files/Git/admin/accounts`，CDP 报 `Cannot navigate to invalid URL`。
 *    --out 要传 Windows 原生路径（`C:\...\x.png`）。
 *    纯前端改动（CSS/模板）不必跑本脚本，交给用户自己看；
 *    只在需要「交互后才出现的 UI」证据时才用。
 *
 * 参数：
 *   --base      站点根地址（必填）。同源才能写 localStorage，所以先访问 /login 再跳目标页。
 *   --path      目标路由（必填）
 *   --token     JWT，写入 localStorage.token
 *   --userinfo  LoginResponse JSON 字符串，写入 localStorage.userInfo
 *   --click     目标页渲染后要点击的按钮文本（可选，用于打开弹窗）。可用逗号分隔多个，按顺序点
 *   --click-sel 按 CSS 选择器点击（可选）。用于文本匹配不了的场景：
 *               按钮文本由多个子元素拼成（如「量表名+次数+最近分数」），按文本比永远匹配不上
 *   --clicks    按选择器重复点击的次数，默认 1（配 --click-sel 用）
 *   --sel-after 每次按选择器点击后等待毫秒，默认 2500。
 *               展开类操作会触发按需请求（如并行拉 N 条明细），要留够时间
 *   --text      截图后额外打印该 CSS 选择器的 innerText（可选，用于核对文案）
 *   --wait      导航后等待毫秒，默认 4000
 *   --after     最后一次点击后额外等待毫秒，默认 1200。
 *               提交类按钮点完会触发请求 + 列表重载，不等就 dump 到的是旧数据。
 *   --out       输出 PNG 路径（必填）
 *   --width/--height  视口，默认 1280 / 1000
 */
import { spawn } from 'node:child_process'
import { writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]])
    return acc
  }, []),
)

const BASE = args.base
const PATH_ = args.path
const OUT = args.out
const CLICK = args.click
const TEXT = args.text
const WAIT = Number(args.wait || 4000)
const AFTER = Number(args.after || 1200)
const W = Number(args.width || 1280)
const H = Number(args.height || 1000)
const PORT = Number(args.port || 9333)

if (!BASE || !PATH_ || !OUT) {
  console.error('缺少必填参数：--base / --path / --out')
  process.exit(2)
}

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
]
const chromeBin = args.chrome || CHROME_CANDIDATES.find((p) => existsSync(p))
if (!chromeBin) {
  console.error('找不到 Chrome/Edge，请用 --chrome 指定可执行文件')
  process.exit(2)
}

const profile = join(tmpdir(), `cdp-shot-prof-${Date.now()}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 最小 CDP 客户端：Node 22 自带全局 WebSocket，无需 ws 依赖 */
async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', rej, { once: true })
  })
  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    const slot = pending.get(msg.id)
    if (slot) {
      pending.delete(msg.id)
      msg.error ? slot.rej(new Error(JSON.stringify(msg.error))) : slot.res(msg.result)
    }
  })
  return {
    send(method, params = {}) {
      const id = ++seq
      return new Promise((res, rej) => {
        pending.set(id, { res, rej })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    close: () => ws.close(),
  }
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'evaluate 失败')
  return r.result.value
}

const chrome = spawn(
  chromeBin,
  [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--no-first-run',
    '--no-default-browser-check',
    '--no-proxy-server',
    '--force-device-scale-factor=1',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`,
    `--window-size=${W},${H}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

try {
  // 等调试端口就绪
  let version = null
  for (let i = 0; i < 40; i++) {
    try {
      version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
      break
    } catch {
      await sleep(250)
    }
  }
  if (!version) throw new Error(`调试端口 ${PORT} 未就绪`)

  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
  const page = list.find((t) => t.type === 'page')
  if (!page) throw new Error('没有可用的 page target')

  const cdp = await connect(page.webSocketDebuggerUrl)
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')

  // ① 先落到同源页面，才能写 localStorage
  await cdp.send('Page.navigate', { url: `${BASE}/login` })
  await sleep(1500)

  if (args.token || args.userinfo) {
    await evaluate(
      cdp,
      `(() => {
        ${args.token ? `localStorage.setItem('token', ${JSON.stringify(args.token)});` : ''}
        ${args.userinfo ? `localStorage.setItem('userInfo', ${JSON.stringify(args.userinfo)});` : ''}
        return 'storage-ok'
      })()`,
    )
  }

  // ② 走 SPA 内部跳转，避免整页刷新丢掉刚写入的内存态
  await cdp.send('Page.navigate', { url: `${BASE}${PATH_}` })
  await sleep(WAIT)

  // ③ 交互：依次点击指定文本的按钮 / 单选项（--click 可用逗号分隔多个，按顺序点）
  if (CLICK) {
    for (const want of String(CLICK).split(',').map((s) => s.trim()).filter(Boolean)) {
      const clicked = await evaluate(
        cdp,
        `(() => {
          // antd 会给两字中文按钮插入空格（「审 核」），所以比较时一律去掉空白
          const norm = (s) => (s || '').replace(/\\s+/g, '')
          const want = norm(${JSON.stringify(want)})
          const els = [...document.querySelectorAll('button, a, label, .ant-radio-button-wrapper')]
          const hit = els.find((b) => norm(b.textContent) === want)
          if (!hit) return 'not-found'
          hit.click()
          return 'clicked'
        })()`,
      )
      console.log(`[click] ${want} -> ${clicked}`)
      if (clicked === 'not-found') {
        const seen = await evaluate(
          cdp,
          `[...document.querySelectorAll('button, a, label')].map((b) => b.textContent.trim()).filter(Boolean).slice(0, 20).join(' | ')`,
        )
        console.error('未找到目标控件，页面上的可点元素有：' + seen)
      }
      await sleep(900)
    }
    // 提交类操作点完会发请求并重载列表，留出时间再 dump / 截图
    await sleep(AFTER)
  }

  // ③b 按选择器点击（文本拼不出来时用，如「量表名+次数+分数」的折叠头）
  if (args['click-sel']) {
    const times = Number(args.clicks || 1)
    for (let i = 0; i < times; i++) {
      const clicked = await evaluate(
        cdp,
        `(() => {
          const el = document.querySelector(${JSON.stringify(args['click-sel'])})
          if (!el) return 'not-found'
          el.click()
          return 'clicked'
        })()`,
      )
      console.log(`[click-sel] ${args['click-sel']} -> ${clicked}`)
      await sleep(Number(args['sel-after'] || 2500))
    }
  }

  // ④ 核对渲染后的文案（可选）
  if (TEXT) {
    const txt = await evaluate(
      cdp,
      `(() => { const el = document.querySelector(${JSON.stringify(TEXT)}); return el ? el.innerText : '<未找到 ' + ${JSON.stringify(TEXT)} + '>' })()`,
    )
    console.log('--- innerText ---')
    console.log(txt)
    console.log('--- /innerText ---')
  }

  // ⑤ 截图
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, Buffer.from(shot.data, 'base64'))
  console.log(`[screenshot] ${OUT}`)
  cdp.close()
} finally {
  chrome.kill()
  await sleep(400)
  try {
    rmSync(profile, { recursive: true, force: true })
  } catch {
    /* 临时 profile 清理失败不影响结果 */
  }
}
