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
  /**
   * 本轮回答之后的三条追问建议（`followups` 事件，在 `done` 之后到达）。
   *
   * <p>**空数组是常态而不是异常**：生成失败、超时、危机轮次、模型输出不可解析，
   * 后端都不会发这个事件。视图侧必须以静态三条兜底，不能把「没有建议」渲染成空白。
   *
   * <p>与当前回答**强绑定**：新一轮开始后旧建议必须失效，否则用户会对着
   * 一段新回答看到上一轮才成立的三句话（见 `send()` 里的 `myIdx` 守卫）。
   */
  const followups = ref<string[]>([])
  /**
   * 本轮是否还在「等建议」。
   *
   * <p>为什么需要它：`sending` 在 `done` 就变 false 了（正文结束即解锁输入），
   * 而 `followups` 还要 1~2 秒才到、甚至永远不来。这一两秒里视图如果按
   * 「sending=false 就显示建议」渲染，会先亮出静态兜底那三条、再被真建议替换掉 ——
   * 用户看到的是「提示自己变回固定消息了」。
   *
   * <p>它有两个正常落点，**谁先到就按谁放行**：
   * <ol>
   *   <li>收到 `followups` 事件 —— 内容已经是最终态，立刻放行（推荐路径，
   *       不依赖「客户端什么时候察觉流关闭」）；</li>
   *   <li>整条流关闭仍没收到 —— 说明这一轮压根没有建议（危机轮次 / 生成失败 / 超时），
   *       放行后由视图回落到静态三条。</li>
   * </ol>
   * 两个落点都指向「显示的一定是最终内容」，所以中间不存在「先兜底再替换」的可视帧。
   *
   * <p>与 `sending` 的关系：`sending` 管「能不能发」，它管「建议能不能显示」。
   * 两者在 `done` 与流关闭之间刻意不同步 —— 这正是它存在的理由。
   */
  const followupsPending = ref(false)
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
    // 追问建议跟着会话走：换了一段对话，上一段的三句话与新的回答毫无关系
    followups.value = []
    // 同理，「等建议中」也是上一段的状态。留着它会让新一段对话的建议行一直不出来
    followupsPending.value = false
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
   * <p>短路条件必须是「**有一段还能发消息的会话**」，而不是「sessionId 非空」：
   * store 里可能残留着一条已 CLOSED 的会话（上一轮 <code>loadSession</code> 装进来的），
   * 此时 sessionId 有值却发不出消息 —— 只看 id 就会把这个坏状态一直留着，
   * 用户每次进页面都看到「会话已结束」。
   */
  async function open(assessmentId?: number | null, alertId?: number | null) {
    if (sessionId.value !== null && sessionStatus.value === 'OPEN') return sessionId.value
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
   * <p>页面侧已经**没有**「历史会话」入口了，这个方法现在只有一个用途：
   * 供 `loadSession()` 取 `assessmentId` —— 明细接口不带这个字段，
   * 而右栏的「关联测评」卡要用它。
   *
   * <p>⚠️ **不要再用它判断「当前该打开哪条会话」**：列表只回最近 5 条
   * （`AiConsultService.SESSION_LIST_LIMIT`），一旦这 5 条恰好都已结束，
   * 那条真正进行中的会话就不在列表里。判断会话是否可继续，只有
   * `openSession` 一个可靠来源 —— 详见 {@link enter} 的说明。
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
      // followups 不落库（见 FollowupSuggester 的注释），所以历史会话里一定没有建议。
      // 这里必须显式清空：否则切换会话后，上一条会话最后那三条建议会残留在新会话的输入框上方，
      // 看起来像「AI 针对这段历史回答了什么问题」。
      followups.value = []
      followupsPending.value = false
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
   * 进入 AI 问诊页：把「这个居民的这段对话」装好。
   *
   * <h3>为什么不再「先查列表、再挑一条」</h3>
   * <p>旧实现是 `restoreLast()`：拉会话列表，优先找 `status === 'OPEN'` 的，
   * 找不到就退而求其次取 `list[0]`。这条路有两个致命前提：
   * <ol>
   *   <li><b>列表里必须有那条进行中的会话</b>。而列表接口带 `LIMIT 5`
   *       （`AiConsultService.SESSION_LIST_LIMIT`）并按 id 倒序 —— 一旦最近 5 条
   *       恰好都是已结束的，那条真正 OPEN 的会话就被挤到了窗口之外；</li>
   *   <li>`list[0]` 这个兜底会**把一条 CLOSED 的会话加载进来**，于是界面直接
   *       显示「该会话已结束，只能查看历史消息」+「开启新会话」。</li>
   * </ol>
   * <p>实测复现（2026-10-22，user02）：库里共 40 多条 AI 会话，最近 5 条
   * （id 61~65）全是 CLOSED，而 id=60 那条 OPEN、带 14 条消息的在第 6 位 ——
   * 于是每次进页面都落到一条空白的已结束会话上，看起来像「聊了一半回来就结束了」。
   * user01 最近 5 条里正好有一条 OPEN，所以同样是这个 bug 却看不出问题。
   *
   * <h3>现在只做一件事：调 openSession</h3>
   * <p>服务端的语义本来就是「这个居民**进行中**的那段对话」：按
   * `resident_id + consult_type=AI + status=OPEN` 查（<b>不受列表 LIMIT 约束</b>），
   * 有就复用、没有才新建。这正是进入页面需要的全部判断，
   * 前端再拿一份列表去猜，只会多一个会和真实状态漂移的真相源。
   *
   * @param assessmentId 从测评结果页发起时传入，会被写到（或更新到）当前会话上
   */
  async function enter(assessmentId?: number | null): Promise<boolean> {
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
    // 本轮 AI 气泡的下标。用途是判断「流里的这个回调还属于最后一轮吗」——
    // 详见 onDone 与 finally 里的守卫。
    const myIdx = messages.value.length - 1
    // 旧建议立刻作废：它们是针对上一段回答生成的，与新问题无关。
    // 清空后如果本轮没收到 followups（生成失败/危机轮次），视图会回落到静态兜底三条。
    followups.value = []
    // 整个回合（正文 + 建议生成）都算「等建议」：视图据此把建议行整行藏起来。
    // 藏而不是「灰掉」，是因为上一轮的三句话对新问题不成立，
    // 灰着显示既点不了、又让人以为点得动（2026-10-22 用户反馈「有虚影」就是 0.55 透明度造成的）。
    followupsPending.value = true
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
        /**
         * 正文结束（`done`）。**这是本轮最早可以解锁输入的时刻**。
         *
         * ⚠️ 不要以为流到这里就结束了：后端在 done 之后还会发一帧 `followups`
         * （生成要额外调一次模型，1~2 秒）。`chatStream()` 的 promise 要等整条流关闭
         * 才 resolve，若把解锁放在 finally 里，发送按钮会被这个旁路任务多锁一两秒 ——
         * 用户已经读完回复、也看出了下一步想说什么，却点不了发送。
         *
         * 代价是「解锁后旧流仍有事件可能到达」，所以这里与 onFollowups 都要判
         * `myIdx`：用户若在这一两秒内真的发了下一条，长度会变，旧事件就被丢弃。
         */
        onDone: () => {
          if (messages.value.length - 1 !== myIdx) return
          messages.value[myIdx].streaming = false
          sending.value = false
        },
        /**
         * 追问建议。**排在 done 之后**，因此到达时发送按钮早已解锁。
         *
         * 条数可能不足 3（后端会丢弃太短或命中安全词表的条目），
         * 也可能整个事件不出现 —— 视图侧必须有静态兜底。
         */
        onFollowups: (f) => {
          // 用户已经发了下一轮：这批建议属于上一段回答，直接丢弃，不要覆盖新一轮的槽位
          if (messages.value.length - 1 !== myIdx) return
          const items = (f?.items ?? [])
            .filter((t) => typeof t === 'string' && t.trim().length > 0)
            .slice(0, 3)
          if (!items.length) return
          followups.value = items
          // 建议到手就放行建议行，**不再等流关闭**。
          // 服务端发完 followups 才结束响应，两者相差只有几毫秒，所以正常情况下看不出区别；
          // 但「靠流关闭来放行」把可视时机压在「客户端什么时候察觉响应结束」上 ——
          // 代理缓冲、连接延迟、reader 调度都会让这个察觉晚于数据的到达。
          // 拿到最终内容的那一刻就显示，是唯一不依赖时序推断的判据。
          followupsPending.value = false
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
      // 兜底解锁，不是主路径：正常结束时 `onDone` 已经放掉了 sending。
      // 这里真正要兜住的是「异常断开」—— 那种情况下 onDone 根本不会触发，
      // 只有这里能把发送按钮和光标记号收回来。
      //
      // ⚠️ 守卫同样必要：用户可能在 onDone 解锁后的一两秒内就发了下一轮，
      // 此时 `bubble()` 指向的是新一轮的气泡，无脑改它会把还在流的新回复掐掉。
      if (messages.value.length - 1 === myIdx) {
        messages.value[myIdx].streaming = false
        sending.value = false
        // 流已经关掉了：本轮的建议要么已经写进 followups，要么根本不会来。
        // 到这一刻才放行建议行，用户看到的第一帧就是最终内容 —— 没有「先兜底再替换」的闪。
        followupsPending.value = false
        // 只在「真的失败」时保留重试入口，正常对话结束后按钮不该还亮着
        lastFailedText.value = messages.value[myIdx].error ? text : null
      }
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
      // 消息都清空了，追问建议失去依据；留着会变成「针对不存在的回答」的三条提示
      followups.value = []
      return deleted
    } catch {
      return null
    }
  }

  return {
    sessionId,
    sessionStatus,
    summary,
    followups,
    followupsPending,
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
    enter,
    startNewSession,
    send,
    retry,
    close,
    reset,
    clearMessages,
  }
})
