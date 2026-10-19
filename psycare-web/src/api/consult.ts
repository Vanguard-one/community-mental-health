import { get, postRaw, unwrapResult } from './request'
import type { Result } from '@/types'

/** 开启 AI 问诊会话，返回 sessionId */
export async function openSession(
  assessmentId?: number | null,
  alertId?: number | null,
): Promise<{ sessionId: number }> {
  // 该接口历史版本返回裸 Map，后端已收敛为 Result 包装，这里两种都兼容
  const body = await postRaw<{ sessionId: number } | Result<{ sessionId: number }>>(
    '/ai-consult/open',
    { assessmentId: assessmentId ?? null, alertId: alertId ?? null },
  )
  return unwrapResult<{ sessionId: number }>(body)
}

/** 结束会话 */
export async function closeSession(sessionId: number): Promise<void> {
  await postRaw<unknown>(`/ai-consult/${sessionId}/close`)
}

/**
 * 清空会话的聊天记录（**保留**上下文摘要）。
 *
 * 后端只删 `consult_message` 的行，不碰 `consult_session.summary` ——
 * 所以屏幕清空了，但 AI 依然记得之前聊过的内容，摘要机制照常工作。
 *
 * 返回删除条数：用来区分「清掉了 N 条」与「本来就没记录」，
 * 比一个笼统的 ok 更能说明实际发生了什么。
 */
export async function clearMessages(sessionId: number): Promise<number> {
  const body = await postRaw<{ deleted: number } | Result<{ deleted: number }>>(
    `/ai-consult/${sessionId}/clear-messages`,
  )
  return unwrapResult<{ deleted: number }>(body).deleted
}

/**
 * `meta` 事件：本轮标识 + 当前引导阶段。
 *
 * 阶段是在请求开始前就定下来的（后端先推进阶段再组装提示词），属于「本轮元信息」，
 * 所以搭 meta 一起下发，不为它单开一种事件类型 —— 每多一种事件，
 * 前后端就多一处必须同步的契约。
 */
export interface ConsultMeta {
  sessionId: number
  userMessageId?: number
  traceId?: string
  model?: string
  /** 阶段枚举名，如 CHIEF_COMPLAINT */
  stage?: string | null
  /** 中文阶段名，如「主诉澄清」 */
  stageLabel?: string
  /** 阶段序号 1–5 */
  stageStep?: number
  /** 阶段总数 */
  stageTotal?: number
  /** 本阶段目标，用于指示条的说明文字 */
  stageGoal?: string
}

/** `usage` 事件：token 用量。reasoningTokens 是推理模型不可见的思考开销 */
export interface ConsultUsage {
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  reasoningTokens: number
  totalTokens: number
}

/** `error` 事件：后端归类后的失败信息（不放上游响应体） */
export interface ConsultError {
  code: string
  msg: string
  retryable: boolean
}

/**
 * `crisis` 事件：本轮回复是危机文案，前端应把气泡渲染成危机态、并提示人工出口。
 *
 * 为什么必须由服务端给这个信号，而不是前端从正文里认关键词：
 * 提示词（L2 第 3 条）**要求**模型在风险语境下建议拨打 12356，
 * 所以「正文里出现 12356」是正常行为，不能当危机标志。
 */
export interface ConsultCrisis {
  /** INPUT = 居民自己表达了危机（已建工单，可承诺转接人工）；OUTPUT = 模型输出被护栏拦下（未建工单） */
  reason: 'INPUT' | 'OUTPUT'
}

/**
 * `recommend` 事件：用户已同意接受医生推荐。
 *
 * 收到它意味着「AI 问过 + 用户答应了」这个一问一答已经完成（判定在服务端，
 * 见后端 `ReferralRules`），前端应播「AI 正在分析」动画，动画结束后
 * **结束会话并跳转到推荐结果页**。
 *
 * 为什么不让前端自己判断：前端只看得到「用户说了什么」，
 * 任何一轮里的一句「好的」都会被误读成要去看医生；而跳转是不可逆的（会话要关闭），
 * 这种动作不能由启发式规则触发。
 */
export interface ConsultRecommend {
  sessionId: number
}

/**
 * `citations` 事件里的一条引用（Phase 4 · RAG）。
 *
 * 只包含「够画一张卡片」的字段：**没有正文全文**。完整段落已经进过提示词，
 * 再传一遍是重复传输；而且卡片的作用是告诉用户「AI 读了什么」，
 * 想读全文应该去知识库页面。
 */
export interface ConsultCitation {
  /** 命中的子块 id（与后端日志对齐用） */
  chunkId: number
  docId: number
  /** 文档标题（卡片主标题） */
  docTitle: string | null
  /** 章节路径，文档没有结构（纯文本粘贴）时为 null 或空串 */
  section: string | null
  /** 正文摘要，最多 60 字 */
  snippet: string
  /** RRF 融合分，仅用于排序，**不可当相似度** */
  score: number
  /** 稠密相似度；null 表示这条由稀疏（关键词）路召回 */
  denseScore: number | null
  /**
   * 是否合成语料。为 true 时必须显示「合成示例数据」角标 ——
   * 合成语料是模型记忆的再表述、可能含错（风险 R5），用户有权知道它不是权威来源。
   */
  synthetic: boolean
}

/** `citations` 事件：本轮回复所依据的知识库资料 */
export interface ConsultCitations {
  items: ConsultCitation[]
}

export interface ConsultHandlers {
  onMeta?: (meta: ConsultMeta) => void
  /** 增量正文 */
  onToken?: (token: string) => void
  /**
   * 服务端改写后的**最终正文**，前端必须用它整体覆盖气泡内容。
   * 当前唯一来源是输出侧安全护栏：原始文字已经流出来了撤不回来，
   * 不给这个事件，用户读到的与库里存的就不是同一段对话。
   */
  onOverride?: (fullText: string) => void
  /** 本轮被判定为危机（气泡转危机态 + 人工出口卡片） */
  onCrisis?: (crisis: ConsultCrisis) => void
  /** 用户已同意接受医生推荐（本轮回复结束后进入分析 → 跳转流程） */
  onRecommend?: (recommend: ConsultRecommend) => void
  /**
   * 本轮回复引用的知识库资料。**在 `token` 之前到达**，因此挂到当前气泡上
   * （此时气泡已由 `beginAssistant()` 建好），不会有「先塞引用、后建气泡」的时序问题。
   * 没有资料可引用时后端**不发**这个事件，`items` 不会为空数组。
   */
  onCitations?: (citations: ConsultCitations) => void
  onUsage?: (usage: ConsultUsage) => void
  onError?: (error: ConsultError) => void
  onDone?: () => void
}

/** 后端 SSE 的事件名，与 SseEvent 契约（SSE事件协议.md）一一对应 */
const EV_TOKEN = 'token'
const EV_META = 'meta'
const EV_USAGE = 'usage'
const EV_OVERRIDE = 'override'
const EV_CRISIS = 'crisis'
const EV_RECOMMEND = 'recommend'
const EV_CITATIONS = 'citations'
const EV_ERROR = 'error'
const EV_DONE = 'done'
const DONE_MARK = '[DONE]'

/**
 * 流式对话：使用 fetch 读取 SSE，按事件名分发。
 *
 * 四条硬约束：
 * 1. 必须用 fetch —— Axios 读不到流式响应体；
 * 2. 必须用 fetch —— EventSource 只支持 GET，而这里是 POST；
 * 3. 一个事件里可能有多行 `data:`（LLM token 自带换行时 Spring 会拆行），
 *    按 SSE 规范必须用 `\n` 重新拼接，否则回复里的段落会连成一行；
 * 4. **必须解析 `event:` 字段**。只认 `data:` 会把 `error` 事件里的
 *    `{"code":"LLM_..."}` 当成正文拼进气泡 —— 用户会在聊天内容里看到一段 JSON。
 */
export async function chatStream(
  sessionId: number,
  content: string,
  handlers: ConsultHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const token = localStorage.getItem('token')
  const response = await fetch('/api/ai-consult/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ sessionId, content }),
    signal,
  })

  if (!response.ok || !response.body) {
    // 401 返回裸 JSON，尽量把后端的 msg 透出来，而不是笼统的"请求失败"
    let detail = ''
    try {
      detail = (await response.json())?.msg ?? ''
    } catch {
      /* 响应体不可解析则忽略 */
    }
    throw new Error(detail || `对话请求失败（HTTP ${response.status}）`)
  }

  // 会话归属校验失败（403）、会话已结束（400）这类业务错误走 HTTP 200 + body.code，
  // 响应体是 JSON 而不是事件流。不识别的话会被当成 SSE 静默解析 ——
  // 一个 data: 行都读不到，用户看到的是「消息发出去了但没有任何回复」，
  // 比直接报错更难排查。所以先看 Content-Type，不是事件流就按 Result 解出 msg 抛出去。
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('text/event-stream')) {
    let detail = ''
    try {
      detail = (await response.json())?.msg ?? ''
    } catch {
      /* 响应体不可解析则忽略 */
    }
    throw new Error(detail || '对话请求被拒绝')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  /** 按事件名分发一帧 */
  const dispatch = (event: string, data: string) => {
    switch (event) {
      case EV_TOKEN:
        handlers.onToken?.(data)
        break
      case EV_META:
        handlers.onMeta?.(safeParse<ConsultMeta>(data))
        break
      case EV_USAGE:
        handlers.onUsage?.(safeParse<ConsultUsage>(data))
        break
      case EV_OVERRIDE:
        handlers.onOverride?.(data)
        break
      case EV_CRISIS:
        handlers.onCrisis?.(safeParse<ConsultCrisis>(data))
        break
      case EV_RECOMMEND:
        handlers.onRecommend?.(safeParse<ConsultRecommend>(data))
        break
      case EV_CITATIONS:
        handlers.onCitations?.(safeParse<ConsultCitations>(data))
        break
      case EV_ERROR:
        handlers.onError?.(safeParse<ConsultError>(data))
        break
      case EV_DONE:
        handlers.onDone?.()
        break
      default:
        // 没有 event: 字段的帧（旧后端只发 Flux<String>）。
        // [DONE] 要当成结束而不是正文，否则气泡末尾会多出一行 "[DONE]"。
        if (data.trim() === DONE_MARK) handlers.onDone?.()
        else handlers.onToken?.(data)
    }
  }

  /** 解析缓冲区中所有已完整的事件 */
  const drain = () => {
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)

      let eventName = ''
      const dataLines: string[] = []
      for (const rawLine of block.split('\n')) {
        const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
        if (line.startsWith(':')) continue // SSE 注释行（心跳）
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          let value = line.slice(5)
          if (value.startsWith(' ')) value = value.slice(1)
          dataLines.push(value)
        }
      }
      if (dataLines.length) dispatch(eventName, dataLines.join('\n'))
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    // 不同服务端可能用 \r\n 结束行，先归一化，否则 \n\n 永远匹配不到
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    drain()
  }
  buffer += decoder.decode().replace(/\r\n/g, '\n')
  drain()
}

/** 事件 data 已是 JSON 字符串；解析失败时给一个能显示的空对象，不抛错打断整轮对话 */
function safeParse<T>(data: string): T {
  try {
    return JSON.parse(data) as T
  } catch {
    return {} as T
  }
}

/* ==================== 引导问诊阶段（Phase 2） ==================== */

/**
 * 三个阶段的展示定义。
 *
 * 与后端 `ai/InterviewStage` **一一对应**：后端负责「怎么推进」，前端只负责「怎么画」。
 * 之所以前端也留一份：meta 事件一次只发当前阶段，而指示条要画出完整流程。
 * ⚠️ 改后端枚举（名称或顺序）时必须同步这里，否则指示条会错位。
 *
 * 由五段压到三段：初筛的目标是**尽快给出一条可执行的去向**。
 * 「功能影响」并入主诉澄清的追问要点，「风险筛查」并入安全边界
 * （出现相关表述立即处置，不出现不专门追问）。详见后端 `InterviewStage` 类注释。
 */
export const STAGE_FLOW: { stage: string; label: string; goal: string }[] = [
  { stage: 'OPENING', label: '建立联结', goal: '共情、确认来意，问一个最关键的问题' },
  { stage: 'CHIEF_COMPLAINT', label: '主诉澄清', goal: '追问程度与生活影响，累计不超过 3 个问题' },
  { stage: 'SUMMARY_REFERRAL', label: '建议转介', goal: '复述、给建议，并询问是否需要推荐医生' },
]

/** 阶段总数（与后端 InterviewStage.TOTAL_STEPS 一致；STAGE_FLOW.length 即它的来源） */
export const STAGE_TOTAL = STAGE_FLOW.length

/** 历史会话列表项 */
export interface AiSessionItem {
  sessionId: number
  status: string
  assessmentId: number | null
  messageCount: number
  /** 最后一条消息（可能很长，展示时自行截断） */
  lastMessage: string | null
  lastAt: string | null
  createdAt: string | null
}

export interface AiMessageItem {
  id: number
  /** RESIDENT / DOCTOR / AI */
  sender: string
  content: string
  crisisFlag: number
  /** 仅 AI 消息有值：DONE / INTERRUPTED / CRISIS / ERROR */
  finishReason: string | null
  createdAt: string | null
}

export interface AiSessionDetail {
  sessionId: number
  status: string
  stage: string
  stageLabel: string
  stageStep: number
  /** 早远对话的摘要，会话太短时为 null */
  summary: string | null
  messages: AiMessageItem[]
}

/** 当前登录用户的历史 AI 会话（倒序，最多 30 条） */
export function listSessions(): Promise<AiSessionItem[]> {
  return get<AiSessionItem[]>('/ai-consult/sessions')
}

/** 会话消息明细。非本人会话返回 403（后端 requireMember 拦截） */
export function sessionDetail(sessionId: number): Promise<AiSessionDetail> {
  return get<AiSessionDetail>(`/ai-consult/sessions/${sessionId}/messages`)
}
