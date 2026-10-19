import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  chatStream,
  clearMessages as clearMessagesApi,
  closeSession,
  listSessions,
  openSession,
  sessionDetail,
  STAGE_TOTAL,
} from '@/api/consult'
import type {
  AiSessionItem,
  ConsultCitation,
  ConsultCrisis,
  ConsultError,
  ConsultRecommend,
  ConsultUsage,
} from '@/api/consult'

export interface ChatItem {
  role: 'user' | 'ai'
  content: string
  time: string
  /** 正在流式接收中 */
  streaming: boolean
  /** 该条为危机干预固定文案 */
  crisis: boolean
  /** 后端归类后的失败信息；有值时气泡内联显示错误条与「重试」 */
  error: ConsultError | null
  /** 本轮 token 用量（仅调试面板使用，不展示给普通用户） */
  usage: ConsultUsage | null
  /**
   * 本轮回复引用的知识库资料（Phase 4）。
   *
   * **只存在于「本轮刚生成」的气泡上**：引用与当轮检索一一对应，历史接口
   * 不返回它（`consult_message` 没存引用关系），所以恢复历史会话后是空数组。
   * 这是刻意的 —— 与其回填一个可能是错的引用关系，不如不显示。
   */
  citations: ConsultCitation[]
}

/** 引导问诊阶段（来自 meta 事件 / 历史接口） */
export interface StageInfo {
  stage: string
  label: string
  step: number
  total: number
  goal: string
}

function now() {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/**
 * 后端 Jackson 配的是 `yyyy-MM-dd HH:mm:ss`，这个格式在部分浏览器（尤其 iOS/Safari）
 * 用 `new Date()` 直接解析会得到 Invalid Date。必须把空格换成 T 再解析。
 */
function parseTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso.replace(' ', 'T'))
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function emptyItem(role: 'user' | 'ai'): ChatItem {
  return {
    role,
    content: '',
    time: now(),
    streaming: false,
    crisis: false,
    error: null,
    usage: null,
    citations: [],
  }
}

/*
 * 危机态不再由前端猜。历史上这里的做法是 CRISIS_MARK = '12356' ——
 * 「正文里出现热线号码就算危机」。但提示词 L2 第 3 条明确要求模型在风险语境下
 * 建议拨打 12356，于是模型每一次合规的热线提示都会命中：气泡变红、
 * 右侧弹出「系统已为你转接人工」。拿一个业务上鼓励出现的内容当异常标志，
 * 必然把正常判成异常。现在改由服务端的 crisis 事件显式告知（见 api/consult.ts）。
 */

export const useChatStore = defineStore('chat', () => {
  const sessionId = ref<number | null>(null)
  /** 本次会话关联的测评记录 */
  const linkedAssessmentId = ref<number | null>(null)
  const linkedAlertId = ref<number | null>(null)
  const messages = ref<ChatItem[]>([])
  const opening = ref(false)
  const sending = ref(false)
  const crisisDetected = ref(false)
  /**
   * 危机来源：INPUT = 居民自己表达了危机（后端已建 CRISIS 工单）；
   * OUTPUT = 模型输出被安全护栏拦下（未建工单）。
   *
   * 区分二者的唯一用处是：只有 INPUT 才能承诺「已为你转接人工」。
   * 恢复既有会话时若判断不出，就保持 null，界面按「不承诺」渲染 —— 保守方向才是安全方向。
   */
  const crisisReason = ref<ConsultCrisis['reason'] | null>(null)
  /** 最后一次失败的提问原文，供「重试」原样重发 */
  const lastFailedText = ref<string | null>(null)
  /** 当前引导阶段（meta 事件每轮刷新） */
  const stage = ref<StageInfo>({
    stage: 'OPENING',
    label: '建立联结',
    step: 1,
    total: STAGE_TOTAL,
    goal: '',
  })
  /**
   * 用户已同意接受医生推荐（服务端 recommend 事件）。
   *
   * 由服务端判定而非前端猜：「AI 问过 + 用户答应」这个一问一答里，
   * 「AI 问过没有」只有服务端能确认。收到后由视图播分析动画、
   * 结束会话并跳到推荐页。
   */
  const recommendAccepted = ref(false)
  /** 当前会话状态：OPEN / CLOSED。恢复出来的会话可能是已结束的（历史遗留数据） */
  const sessionStatus = ref('OPEN')
  /** 较早对话的摘要（从后端带回），用于向用户解释「更早的内容还在」 */
  const summary = ref<string | null>(null)
  /** 会话列表。页面侧已无「历史会话」入口，仅供恢复最近一段对话、取关联测评用 */
  const sessions = ref<AiSessionItem[]>([])
  const loadingSessions = ref(false)

  const ready = computed(() => sessionId.value !== null)
  const hasMessages = computed(() => messages.value.length > 0)
  const canRetry = computed(() => !sending.value && lastFailedText.value !== null)
  /**
   * 能否发消息。
   *
   * 把「会话已结束」也算进来：恢复出来的会话可能是 CLOSED 的，
   * 打开它只能看不能发 —— 后端对已结束会话返回 400，
   * 前端若不提前拦住，用户会看到一条「看起来像故障」的报错。
   */
  const canSend = computed(
    () => sessionId.value !== null && !sending.value && sessionStatus.value === 'OPEN',
  )

  /**
   * 取当前正在流式接收的 AI 气泡。
   *
   * 必须这样「从数组里读回」而不是改 push 进去的那个原对象：
   * `ref([])` 里的元素被读出来才是响应式代理，直接改原对象不会触发视图更新
   * （与本项目 `<script setup>` 里 ref/computed 必须 `.value` 是同一类坑）。
   */
  const bubble = () => messages.value[messages.value.length - 1]

  /**
   * 清空「会话级视图状态」。
   *
   * 与 sessionId 无关的那一半：消息列表、摘要、危机态、重试入口、阶段。
   * 两个调用方都需要它，而且都必须拿到：
   *  - open()   —— 换了一段对话，视图里任何残留都属于「上一段」；
   *  - reset()  —— 登出、或从测评结果页重新进入时清干净。
   * 分成两份写迟早会漏掉一个字段（漏的那个不会报错，只会在界面上露出来）。
   */
  function clearConversation() {
    messages.value = []
    summary.value = null
    crisisDetected.value = false
    crisisReason.value = null
    recommendAccepted.value = false
    lastFailedText.value = null
    stage.value = { stage: 'OPENING', label: '建立联结', step: 1, total: STAGE_TOTAL, goal: '' }
  }

  /**
   * 确保有一段可以继续聊的对话。
   *
   * <p>**服务端是幂等的**：一个居民只有一条进行中的 AI 会话，已有就复用并返回它
   * （见 `AiConsultService.openSession`）。所以方法名虽叫「开启」，实际语义更接近
   * 「打开」—— 传进来的 assessmentId / alertId 会被**更新**到那条会话上，
   * 这样「做完测评再来问诊」不会把对话记录切断。
   *
   * <p>`sessionId` 非空时直接返回：同一页面重复调用（组件重挂载）不该重复开口。
   */
  async function open(assessmentId?: number | null, alertId?: number | null) {
    if (sessionId.value !== null) return sessionId.value
    opening.value = true
    try {
      const res = await openSession(assessmentId, alertId)
      /*
       * 先清空残留再写入 id。
       *
       * 复用来的会话里可能已经躺着几十条消息，而 store 的 messages 还停在上一段 ——
       * 不清会得到「新 id + 旧消息」：顶部显示得完全正常，底下却像接着上次聊，
       * 而这两者未必对得上。真正要展示的消息由调用方随后 loadSession() 拉回来。
       */
      clearConversation()
      sessionId.value = res.sessionId
      sessionStatus.value = 'OPEN'
      linkedAssessmentId.value = assessmentId ?? null
      linkedAlertId.value = alertId ?? null
      return res.sessionId
    } finally {
      opening.value = false
    }
  }

  /**
   * 拉取会话列表。
   *
   * <p>页面侧已经**没有**「历史会话」入口了，这个方法现在的用途是：
   * ① 供 `restoreLast()` 找到最近一段对话；
   * ② 供 `loadSession()` 取 `assessmentId` —— 明细接口不带这个字段，
   * 而右栏的「关联测评」卡要用它。
   */
  async function loadSessions(): Promise<void> {
    loadingSessions.value = true
    try {
      sessions.value = await listSessions()
    } catch {
      // 列表失败不影响当前对话，抽屉显示为空即可（拦截器已弹过提示）
      sessions.value = []
    } finally {
      loadingSessions.value = false
    }
  }

  /**
   * 打开一个会话：拉明细并把当前消息列表**整体替换**。
   *
   * 不做「追加」：追加会让两个会话的消息混在同一列里，而它们的上下文在服务端
   * 是彼此独立的 —— 界面上混着显示，用户会以为 AI 记得这些内容。
   *
   * @returns 是否成功。失败（403 越权 / 404 已删 / 网络）时返回 false，由调用方决定是否新建会话
   */
  async function loadSession(id: number): Promise<boolean> {
    if (sending.value) return false
    loadingSessions.value = true
    try {
      const detail = await sessionDetail(id)
      sessionId.value = detail.sessionId
      sessionStatus.value = detail.status
      stage.value = {
        stage: detail.stage,
        label: detail.stageLabel,
        step: detail.stageStep,
        total: stage.value.total,
        goal: '',
      }
      messages.value = detail.messages.map((m) => ({
        role: m.sender === 'AI' ? 'ai' : 'user',
        content: m.content,
        time: parseTime(m.createdAt),
        streaming: false,
        crisis: m.crisisFlag === 1,
        error: null,
        usage: null,
        // 历史消息不带引用：consult_message 只存正文，没存「当时引了哪几条资料」。
        // 不在这里反查补全 —— 补出来的是「现在检索会命中什么」，不是「当时用的是什么」，
        // 两者不同却被展示成引用，属于伪造证据。
        citations: [],
      }))
      const anyCrisis = detail.messages.some((m) => m.crisisFlag === 1)
      crisisDetected.value = anyCrisis
      // 恢复的会话里只有「居民那条消息 crisisFlag=1」才说明走的是输入侧短路（真建了工单）。
      // 输入侧短路会给居民消息也打上 crisis_flag=1，正常路径恒为 0 —— 这是个可靠信号。
      // 判不出来（只剩 AI 侧标记）就保持 null，界面按「不承诺转接」渲染。
      crisisReason.value = anyCrisis
        ? detail.messages.some((m) => m.sender === 'RESIDENT' && m.crisisFlag === 1)
          ? 'INPUT'
          : 'OUTPUT'
        : null
      summary.value = detail.summary
      lastFailedText.value = null
      // 关联测评 id 只在列表接口里（明细不带），没拉过列表就先拉一次 ——
      // 否则从「刷新恢复」这条路径进来的会话，右栏的关联测评卡会莫名其妙空掉
      if (!sessions.value.length) {
        await loadSessions()
      }
      linkedAssessmentId.value =
        sessions.value.find((s) => s.sessionId === detail.sessionId)?.assessmentId ?? null
      return true
    } catch {
      return false
    } finally {
      loadingSessions.value = false
    }
  }

  /**
   * 恢复这个居民最近的一段对话，并把消息装进 store。
   *
   * <p>数据从**服务端**来，不再依赖 localStorage 里的一个 id：
   * 一个居民只有一条进行中的 AI 会话（见 `AiConsultService.openSession` 的复用逻辑），
   * 所以「最近一条」就是「他一直在聊的那段」。少一个本地状态源，就少一类
   * 「本地记的 id 与服务端实际对不上」的故障，顺带让换浏览器、清缓存之后记录也还在。
   *
   * <p>优先挑**进行中**的：历史遗留数据里可能有已结束的会话，恢复它会让界面只能看不能发。
   *
   * <p>返回 false 是**正常情况**之一（新用户、会话被清理、接口失败），
   * 调用方据此新建一条，用户不会看到任何报错。
   */
  async function restoreLast(): Promise<boolean> {
    try {
      const list = await listSessions()
      sessions.value = list
      const target = list.find((s) => s.status === 'OPEN') ?? list[0]
      if (!target) return false
      return await loadSession(target.sessionId)
    } catch {
      return false
    }
  }

  /**
   * 进入 AI 问诊页：把「这个居民的这段对话」装好。
   *
   * <p>两种进入方式共用它，差别只在第一步：
   * <ul>
   *   <li>带 `assessmentId`（从测评结果页发起）→ 直接 open：后端会**复用**既有会话
   *       并把**这次**测评关联上，所以对话记录不会因为做了一次测评就断掉；</li>
   *   <li>不带 → 先恢复最近一段对话，确实没有才新建。</li>
   * </ul>
   *
   * <p>拿到会话 id 之后还要显式拉一次消息明细：`open()` 只保证「会话存在」，
   * 而它很可能是**复用**来的、里头已经躺着几十条消息，store 的 messages 却是空的。
   */
  async function enter(assessmentId?: number | null): Promise<boolean> {
    if (assessmentId == null && (await restoreLast())) return true
    let sid: number | null = null
    try {
      sid = await open(assessmentId)
    } catch {
      // 建会话的接口自己会弹提示（拦截器），这里只把「没成」这件事如实返回
      return false
    }
    if (sid === null) return false
    // 拉明细失败不致命：会话已经在了，用户至少能发出第一条消息
    await loadSession(sid)
    return true
  }

  /**
   * 开一个全新会话（先清空视图再建）。
   *
   * <p>入口只有一个：会话已结束（CLOSED）时底部那条「开启新会话」。
   * 常规情况下每个居民只有一条进行中的会话，`open()` 会**复用**它（后端行为）；
   * 只有当前这条真的已结束时，后端找不到 OPEN 的，才会插一条新的。
   */
  async function startNewSession(assessmentId?: number | null): Promise<number | null> {
    reset()
    try {
      return await open(assessmentId)
    } catch {
      return null
    }
  }

  /**
   * 发送一条消息并接收流式回复。
   *
   * 失败**不抛异常**：把失败信息写进 AI 气泡的 `error` 字段，由视图内联展示并提供重试。
   * 抛出去只会让调用方（视图）多写一遍 try/catch，而它并不比气泡更知道该怎么提示。
   */
  async function send(content: string): Promise<boolean> {
    const text = content.trim()
    if (!text || sending.value || sessionId.value === null) return false

    messages.value.push({ ...emptyItem('user'), content: text })
    messages.value.push({ ...emptyItem('ai'), streaming: true })
    sending.value = true

    try {
      await chatStream(sessionId.value, text, {
        onMeta: (meta) => {
          // 阶段跟着 meta 走：后端在请求开始前就推进完了，所以第一帧就带着它。
          // 指示条因此不会有「先停在上一阶段、过一会儿再跳」的闪烁。
          if (meta.stage) {
            stage.value = {
              stage: meta.stage,
              label: meta.stageLabel ?? stage.value.label,
              step: meta.stageStep ?? stage.value.step,
              total: meta.stageTotal ?? stage.value.total,
              goal: meta.stageGoal ?? '',
            }
          }
        },
        onCitations: (c) => {
          // 该事件在 token 之前到达，此时 `bubble()` 指向的就是本轮刚建好的 AI 气泡
          // （send() 里 push 过 emptyItem('ai')），所以不需要额外的时序防护。
          // 后端有资料才发这个事件，没有资料时事件根本不出现 —— 这里不用判空 items。
          bubble().citations = c?.items ?? []
        },
        onToken: (token) => {
          const last = bubble()
          last.content += token
        },
        onOverride: (fullText) => {
          // 服务端改写后的最终正文（如命中安全护栏被替换成固定文案）：
          // 必须整体覆盖，不能追加 —— 库里存的就是这一版
          const last = bubble()
          last.content = fullText
        },
        onCrisis: (crisis) => {
          // 危机态由服务端显式告知，不再靠正文里认关键词
          bubble().crisis = true
          crisisDetected.value = true
          crisisReason.value = crisis?.reason ?? null
        },
        onRecommend: (_rec: ConsultRecommend) => {
          // 只记标记，真正的动作（动画 → 跳转）交给视图在 send() 返回之后做：
          // 事件回来时本轮回复还在收尾，此刻弹遮罩会把最后几个字盖住。
          recommendAccepted.value = true
        },
        onUsage: (usage) => {
          bubble().usage = usage
        },
        onError: (error) => {
          bubble().error = error
        },
      })
      return true
    } catch (e) {
      // 连接层失败（网络断开、非事件流响应）：后端还没来得及归类，这里自己造一个
      const last = bubble()
      last.error = {
        code: 'NETWORK',
        msg: e instanceof Error ? e.message : '网络异常，请检查连接后重试',
        retryable: true,
      }
      if (!last.content) last.content = '（对话中断，未能收到任何回复）'
      return false
    } finally {
      const last = bubble()
      last.streaming = false
      sending.value = false
      // 只在「真的失败」时保留重试入口，正常对话结束后按钮不该还亮着
      lastFailedText.value = last.error ? text : null
    }
  }

  /**
   * 重试最后一次失败的提问。
   *
   * 先把失败的那一轮（AI 错误气泡 + 对应的提问气泡）摘掉再重发，
   * 而不是在下面追加一轮 —— 否则连试三次会在会话里留下三条重复的提问，
   * 而且它们都会被带进下一轮的上下文，把历史越撑越长。
   */
  async function retry(): Promise<boolean> {
    const text = lastFailedText.value
    if (text === null || sending.value) return false
    if (messages.value.length && messages.value[messages.value.length - 1].role === 'ai') {
      messages.value.pop()
    }
    if (messages.value.length && messages.value[messages.value.length - 1].role === 'user') {
      messages.value.pop()
    }
    lastFailedText.value = null
    return send(text)
  }

  /**
   * 结束当前会话（服务端置 CLOSED）。
   *
   * <p>**当前没有调用方**，保留是为了语义完整 —— 「结束」这个能力在服务端
   * （`closeSession` 接口）一直存在。之所以没人调：产品上取消了「历史会话」，
   * 聊天框展示的是居民唯一的一段对话，「推荐医生」跳转后他随时可能回来接着聊，
   * 关掉会得到一个只能看不能发的会话，下次进来就成了「刚打开就坏了」。
   */
  async function close(): Promise<boolean> {
    if (sessionId.value === null) return false
    try {
      await closeSession(sessionId.value)
      sessionStatus.value = 'CLOSED'
      return true
    } finally {
      sessionId.value = null
    }
  }

  /**
   * 彻底重置：会话 id 与视图状态一并清掉。
   *
   * <p>两个调用方，都不是「离开页面」：
   * ① 登出（见 `stores/auth.ts`）—— 不清的话换账号登进来会看到上一个人的倾诉内容；
   * ② 测评结果页发起问诊前 —— 清掉 sessionId 才能让 `open()` 真的走到后端，
   *    从而把这次测评关联上（否则它会认为「已经有会话了」直接返回）。
   */
  function reset() {
    clearConversation()
    sessionId.value = null
    sessionStatus.value = 'OPEN'
    linkedAssessmentId.value = null
    linkedAlertId.value = null
  }

  /**
   * 清空聊天记录，但**保留上下文记忆**。
   *
   * <p>与 {@link reset} / {@link clearConversation} 的关键区别，也是这个方法
   * 存在的唯一理由：**不清 `summary`**。
   *
   * <p>后端 `loadHistory()` 每轮现查 `consult_message`，而摘要读的是
   * `consult_session.summary` —— 两条独立来源。所以删掉消息行之后：
   * 界面上的气泡全没了，但模型下一轮拿到的 L4 前馈里仍是那份摘要，
   * 它依然记得用户说过什么。摘要压缩机制本身也一行没改，
   * 后续攒够消息数会照常基于旧摘要做增量压缩。
   *
   * <p>阶段（`stage`）同样**不清**：用户已经讲过的内容不该因为清屏就倒退回开场，
   * 否则 AI 会重新问一遍问过的事，与保留下来的摘要自相矛盾。
   *
   * <p>`crisisDetected` / `crisisReason` 一并清掉：危机标记描述的是「**界面上**
   * 有一条危机消息」，气泡都删了却还在右侧挂「已为你转接人工」，
   * 会让用户以为刚触发了什么。工单本身在后端，不受影响。
   *
   * @returns 后端实际删除的消息条数；失败返回 null（错误提示由拦截器统一弹）
   */
  async function clearMessages(): Promise<number | null> {
    if (sessionId.value === null) return null
    // 流式期间不清：正收着 token 却把气泡删了，下一个 token 会追加到一条
    // 已不在数组里的对象上（bubble() 取到的是新数组的末位），界面会错乱。
    if (sending.value) return null
    try {
      const deleted = await clearMessagesApi(sessionId.value)
      messages.value = []
      crisisDetected.value = false
      crisisReason.value = null
      lastFailedText.value = null
      return deleted
    } catch {
      return null
    }
  }

  return {
    sessionId,
    sessionStatus,
    summary,
    linkedAssessmentId,
    linkedAlertId,
    messages,
    opening,
    sending,
    crisisDetected,
    crisisReason,
    recommendAccepted,
    lastFailedText,
    stage,
    sessions,
    loadingSessions,
    ready,
    hasMessages,
    canRetry,
    canSend,
    open,
    loadSessions,
    loadSession,
    restoreLast,
    enter,
    startNewSession,
    send,
    retry,
    close,
    reset,
    clearMessages,
  }
})
