/**
 * cdp-popover-probe.mjs —— 定位「弹层位置一直在动」类问题：连续采样元素坐标，看谁在动、往哪动。
 *
 * 用途：
 *   气泡确认框（popconfirm）/ tooltip / message 出现后持续漂移、抖动、往一侧滑 ——
 *   单张截图看不出来（只拍一帧），必须按时间轴采样坐标才能定性。
 *
 * 用法：
 *   node cdp-popover-probe.mjs \
 *     --base http://localhost:5173 \
 *     --path /admin/accounts \
 *     --token <JWT> \
 *     --userinfo '{"token":"...","userId":1,...}' \
 *     --click 禁用 \
 *     --watch ".ant-popover" \
 *     --samples 40 --interval 80
 *
 * 参数：
 *   --base / --path / --token / --userinfo  同 cdp-shot.mjs
 *   --click     点击目标按钮文本（打开弹层，默认 禁用）；用 element.click()，不改动服务端数据
 *   --watch     要采样的选择器，默认为 ".ant-popover"，可逗号分隔多个
 *   --samples   采样次数，默认 30
 *   --interval  采样间隔 ms，默认 80
 *   --out       可选，最后再截一张图
 *
 * 输出：每个采样点的选择器命中数 + 首个命中的 left/top/width/height + inline style 摘要，
 *       并在末尾打印「谁在动」的诊断（位移趋势、是否单调、命中数量变化）。
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
const CLICK = args.click || '禁用'
const WATCH = String(args.watch || '.ant-popover')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)
const SAMPLES = Number(args.samples || 30)
const INTERVAL = Number(args.interval || 80)
const W = Number(args.width || 1280)
const H = Number(args.height || 1000)
const PORT = Number(args.port || 9334)

if (!BASE || !PATH_) {
  console.error('缺少必填参数：--base / --path')
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

const profile = join(tmpdir(), `cdp-probe-prof-${Date.now()}`)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

/** 在页面里挂一个采样函数，返回每个 watch 选择器首元素的可视坐标 */
const SAMPLE_FN = `
window.__probe = function (sels) {
  const out = {}
  // 文档级指标：弹层溢出视口会让滚动条出现/消失，进而触发重新定位 —— 常见漂移根因
  out.__doc__ = {
    n: 1,
    l: document.documentElement.clientWidth,
    t: document.documentElement.scrollWidth,
    w: document.body.scrollWidth,
    h: document.documentElement.scrollHeight,
  }
  for (const sel of sels) {
    const els = [...document.querySelectorAll(sel)]
    const visible = els.filter((e) => {
      const r = e.getBoundingClientRect()
      return r.width > 0 && r.height > 0
    })
    const el = visible[0] || els[0]
    if (!el) {
      out[sel] = { n: 0, seen: els.length }
      continue
    }
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    out[sel] = {
      n: visible.length,
      seen: els.length,
      l: +r.left.toFixed(1),
      t: +r.top.toFixed(1),
      w: +r.width.toFixed(1),
      h: +r.height.toFixed(1),
      pos: cs.position,
      tf: cs.transform === 'none' ? '-' : cs.transform,
      anim: cs.animationName === 'none' ? '-' : cs.animationName,
      inline: (el.getAttribute('style') || '').replace(/\\s+/g, ' ').slice(0, 120),
      parentTf: (() => {
        let p = el.parentElement
        while (p && p !== document.body) {
          const t = getComputedStyle(p).transform
          if (t && t !== 'none') return p.className + ' => ' + t
          p = p.parentElement
        }
        return '-'
      })(),
    }
  }
  return out
}; 'probe-ready'
`

const chrome = spawn(
  chromeBin,
  [
    '--headless=new',
    '--disable-gpu',
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

  await cdp.send('Page.navigate', { url: `${BASE}${PATH_}` })
  await sleep(Number(args.wait || 4000))
  await evaluate(cdp, SAMPLE_FN)

  // 布局体检：弹层漂移常与「容器溢出 + 靠近视口边缘反复翻转」相关，先把尺寸量出来
  if (args.layout) {
    const report = await evaluate(
      cdp,
      `(() => {
        const r = (el) => {
          if (!el) return null
          const b = el.getBoundingClientRect()
          return { l: Math.round(b.left), t: Math.round(b.top), w: Math.round(b.width), h: Math.round(b.height) }
        }
        const q = (s) => document.querySelector(s)
        return {
          viewport: { w: innerWidth, h: innerHeight },
          docScroll: { sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth },
          bodyScroll: { sw: document.body.scrollWidth, cw: document.body.clientWidth },
          layout: r(q('.admin-layout') || q('.ant-layout')),
          content: r(q('.admin-content') || q('main') || q('.ant-layout-content')),
          tableWrap: r(q('.table-wrap')),
          gridHead: r(q('.grid-head')),
          gridRow: r(q('.grid-row')),
          ops: r(q('.ops')),
          buttons: [...document.querySelectorAll('.grid-row button')]
            .slice(0, 8)
            .map((b) => ({ txt: b.textContent.replace(/\\s+/g, ''), dis: b.disabled, ...r(b) })),
        }
      })()`,
    )
    console.log('\\n---- 布局体检 ----')
    console.log('视口            :', JSON.stringify(report.viewport))
    console.log('文档 scrollWidth:', JSON.stringify(report.docScroll), '（sw > cw 即出现横向滚动）')
    console.log('body scrollWidth:', JSON.stringify(report.bodyScroll))
    console.log('布局根          :', JSON.stringify(report.layout))
    console.log('内容区          :', JSON.stringify(report.content))
    console.log('table-wrap      :', JSON.stringify(report.tableWrap))
    console.log('grid-head       :', JSON.stringify(report.gridHead))
    console.log('首个 grid-row   :', JSON.stringify(report.gridRow))
    console.log('ops 容器        :', JSON.stringify(report.ops))
    for (const b of report.buttons) {
      const edge = b.l + b.w
      console.log(
        `  按钮 ${b.txt}${b.dis ? '(禁用)' : ''} left=${b.l} right=${edge} ` +
          `视口宽=${report.viewport.w} ${edge > report.viewport.w ? '⚠️ 超出右边界' : ''}`,
      )
    }
  }

  // 点击目标按钮（只打开弹层，不点确认）
  if (CLICK) {
    const clicked = await evaluate(
      cdp,
      `(() => {
        const norm = (s) => (s || '').replace(/\\s+/g, '')
        const want = norm(${JSON.stringify(CLICK)})
        const hit = [...document.querySelectorAll('button, a, label')].find(
          (b) => norm(b.textContent) === want && !b.disabled,
        )
        if (!hit) return 'not-found'
        hit.click()
        return 'clicked'
      })()`,
    )
    console.log(`[click] ${CLICK} -> ${clicked}`)
    if (clicked === 'not-found') {
      const seen = await evaluate(
        cdp,
        `[...document.querySelectorAll('button')].map((b) => b.textContent.trim() + (b.disabled ? '(禁用)' : '')).filter(Boolean).slice(0, 20).join(' | ')`,
      )
      console.error('未找到目标按钮，页面上有：' + seen)
    }
  }

  // 连续采样
  console.log(`\n[sample] 每 ${INTERVAL}ms 一次，共 ${SAMPLES} 次，watch=${WATCH.join(' / ')}`)
  const series = []
  for (let i = 0; i < SAMPLES; i++) {
    const snap = await evaluate(cdp, `JSON.stringify(window.__probe(${JSON.stringify(WATCH)}))`)
    series.push({ i, at: i * INTERVAL, data: JSON.parse(snap) })
    await sleep(INTERVAL)
  }

  // 文档横向溢出时间线：sw > cw 表示出现了横向滚动（弹层把页面撑宽了）
  console.log('\n---- 文档横向溢出（clientWidth / scrollWidth / bodyScrollWidth）----')
  let prevDoc = null
  for (const s of series) {
    const d = s.data.__doc__
    if (!d) continue
    const key = `${d.l}|${d.t}|${d.w}`
    if (key === prevDoc) continue
    prevDoc = key
    const overflow = d.t > d.l || d.w > d.l
    console.log(
      `  ${String(s.at).padStart(5)}ms  clientW=${d.l}  docScrollW=${d.t}  bodyScrollW=${d.w}` +
        `${overflow ? '  ⚠️ 横向溢出（会出滚动条）' : ''}`,
    )
  }

  // 打印时间轴：只输出「与上一帧不同」的帧，相同的一律跳过（比逐帧更易读）
  for (const sel of WATCH) {
    console.log(`\n---- ${sel} ----`)
    console.log('  t(ms)  n  left    top     width  height  transform')
    let prevKey = null
    let shown = 0
    for (const s of series) {
      const d = s.data[sel] || {}
      const key = `${d.n}|${d.l}|${d.t}|${d.w}|${d.h}|${d.tf}`
      if (key === prevKey) continue
      prevKey = key
      shown++
      console.log(
        `  ${String(s.at).padStart(5)} ${String(d.n ?? '-').padStart(2)}  ` +
          `${String(d.l ?? '-').padStart(7)} ${String(d.t ?? '-').padStart(7)} ` +
          `${String(d.w ?? '-').padStart(6)} ${String(d.h ?? '-').padStart(6)}  ${d.tf ?? '-'}`,
      )
    }
    console.log(`  （共 ${series.length} 帧，其中 ${shown} 帧与上一帧不同）`)
    const withRect = series.filter((s) => s.data[sel] && s.data[sel].l !== undefined)
    const first = withRect[0]
    const last = withRect[withRect.length - 1]
    if (first && last) {
      const d0 = first.data[sel]
      const d1 = last.data[sel]
      console.log(
        `  >> 位移：left ${(d1.l - d0.l).toFixed(1)}px，top ${(d1.t - d0.t).toFixed(1)}px，` +
          `宽度 ${(d1.w - d0.w).toFixed(1)}px，命中数 ${d0.n} -> ${d1.n}`,
      )
      console.log(`  >> 首帧 style：${d0.inline}`)
      console.log(`  >> 末帧 style：${d1.inline}`)
      console.log(`  >> 祖先 transform：${d0.parentTf}`)
      console.log(`  >> 自身 position=${d0.pos} animation=${d0.anim} transform=${d0.tf}`)
    } else {
      console.log('  >> 整个采样期都没有可见命中：弹层可能被禁用 / 未渲染 / 选择器不对')
    }
  }

  if (args.out) {
    const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
    mkdirSync(dirname(args.out), { recursive: true })
    writeFileSync(args.out, Buffer.from(shot.data, 'base64'))
    console.log(`\n[screenshot] ${args.out}`)
  }

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
