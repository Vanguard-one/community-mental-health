<template>
  <div class="chat-page">
    <!-- ============ 左：对话 ============ -->
    <section class="chat-main">
      <header class="chat-head">
        <div class="chat-head-id">
          <!--
            AI 身份标记：AI 助手图标 + 一圈缓慢外扩的柔光涟漪。
            用意是「把在线/工作中这件事做在图形上，而不是写成一行字」——
            省掉一句文案，同时给界面一个会呼吸的焦点。
            图标本身由 `RoleAvatar` 统一提供，与消息气泡、顶栏账号头像同一套。
          -->
          <RoleAvatar class="ai-mark" role="ai" :size="48" />
          <!--
            标题与状态说明合并成一行。相比原来的两行式（第二行「● #68 · 内容仅用于本次咨询」）改了两点：
              ① **去掉会话号** —— 它是后端的内部 id，用户既看不懂也用不上；
                 需要知道的只有「这次对话安不安全、还能不能继续」。
              ② 剩下的说明改写成**括注**紧跟标题，字号维持 12.5px
                 （刻意不跟着 18px 的标题变大：它是注解，不是标题的一部分）。
            在线指示点保留，从第二行挪到标题前 —— 它表达的是「AI 是否在线」这个状态，
            与注解文字不是一回事，不该跟着一起被吸收进括号里。
          -->
          <div class="chat-head-text">
            <h1 class="chat-title font-serif">
              <span
                class="live-dot"
                :class="{ 'is-off': !chat.sessionId }"
                aria-hidden="true"
              ></span>
              AI 智能问诊<span class="title-note">（{{ headNote }}）</span>
            </h1>
          </div>
        </div>
        <div class="chat-head-ops">
          <!--
            清空记录（次要操作）放在主行动召唤左侧，用描边胶囊与实心胶囊区分权重 ——
            两者形状同族但视觉重量不同，一眼能看出哪个是「继续往下走」、
            哪个是「抹掉重来」。用原生 button 而不是 a-button：
            vendor-antd 预算只剩约 9 kB（硬约定 #9）。
          -->
          <button type="button" class="ghost-btn" :disabled="!canClear" @click="openClearDialog">
            清空会话
          </button>
          <!--
            本页唯一的主行动召唤。原先用的是 a-button（白底灰边 + 6px 小圆角），
            与旁边的胶囊形按钮形状配色都不成组 —— 改成同一套骨架的实心胶囊。
            原先并排的「历史会话」入口已随该功能一并移除。
          -->
          <button
            type="button"
            class="ghost-btn is-solid"
            :disabled="chat.sessionId === null"
            @click="onClickRecommend"
          >
            推荐医生
          </button>
        </div>
      </header>

      <!--
        引导问诊阶段指示条已移除。
        它展示的是后端阶段机（meta 事件每轮下发当前阶段），纯 CSS 画点与连线。
        移除原因：三个阶段的推进对居民来说是内部实现细节 —— 他关心的是
        「AI 有没有听懂我在说什么」，而不是「现在进行到第几步」。
        后端阶段机本身不受影响（仍驱动追问策略与提示词的 L5 分层），
        只是不再占用界面顶部这一行。需要恢复时把这段与 .stage-bar 的样式一起拿回来即可。
      -->

      <!--
        @scroll 用来记录「用户是否还贴着底部」（见 onBodyScroll）。
        它只在用户**主动**滚动时反映真实意图 —— 程序滚动触发的事件里
        nearBottom() 为 true，回写 stickToBottom 也是 true，不会自相矛盾。
      -->
      <div ref="bodyRef" class="chat-body" @scroll.passive="onBodyScroll">
        <div v-if="!chat.hasMessages && !chat.opening" class="chat-empty">
          <!--
            空态原有一行标题「你好，我是 PsyCare AI 咨询助手」，已删除 ——
            它与顶栏的「AI 智能问诊」说的是同一件事，属于「同一句话讲两遍」。
            改用一个缓慢自转的光核承接注意力：图形负责表达「我在」，文案只负责
            说清「能做什么 + 这里安全」，信息密度上去了，字反而少了。
          -->
          <div class="empty-orb" aria-hidden="true">
            <span class="orb-halo"></span>
            <span class="orb-ring"></span>
            <span class="orb-core">AI</span>
          </div>
          <p class="empty-desc">说说你最近的感受，这里不评判、不贴标签。</p>
          <div class="empty-tips">
            <button
              v-for="t in PRESET_PROMPTS"
              :key="t"
              type="button"
              class="tip-chip"
              @click="sendPreset(t)"
            >
              {{ t }}
            </button>
          </div>
        </div>

        <!--
          摘要提示：只在消息足够长、触发过摘要压缩时出现。
          它解释了一件用户会好奇的事 —— 前面的对话为什么不完整，AI 又为什么还记得。
        -->
        <p v-if="chat.summary" class="summary-note">
          <span class="sn-tag">前情摘要</span>{{ chat.summary }}
        </p>

        <div v-for="(m, i) in chat.messages" :key="i" class="msg" :class="m.role">
          <!--
            两侧各一个圆形身份标，都走 `RoleAvatar`：
            AI 侧 = AI 助手图标，用户侧 = 用户默认图标（与顶栏账号头像同一个组件、
            同一套底衬）—— 让「我的头像」在页面各处长得一样，而不是在这里另造一个形象。
            用角色图标而不是真名首字：全站 13 处头像统一成同一套，才有「同一套设计语言」。
            用户头像挂在气泡**之后**而不是之前：`.msg.user` 是 flex-end 排列，
            DOM 顺序即视觉顺序，挂在前面会跑到气泡左边去。
          -->
          <RoleAvatar v-if="m.role === 'ai'" class="msg-face" role="ai" :size="36" />
          <div class="bubble" :class="{ 'is-crisis': m.crisis, 'is-error': !!m.error }">
            <!--
              等待态：气泡已建、正文还没有的这段时间。
              ⚠️ 这段时间**长得出人意料**：真库 ai_usage_log 81 条记录显示首帧中位 1.53s，
              而首帧占总耗时的中位比例是 **83%** —— 也就是说用户绝大部分等待时间里
              屏幕上什么都没有。此前这里只画一个闪烁光标，5~10 秒的空窗里没有任何
              「它在推进」的信号。现在换成「随阶段变化的文案 + 不确定进度条」。

              为什么敢随阶段变：阶段是后端在**请求开始前**就定好的（见 store 的 onMeta），
              它随第一帧 meta 事件到达，所以等待一开始文案就已经是对的，不会先显示
              一句通用话再跳。
              为什么用「不确定」流动条而不是百分比：真实耗时不可预知（思考量本身波动很大，
              实测同参数同输入首帧在 2.0s 与 5.0s 之间跳）。画一个会填满的进度条等于撒谎。
            -->
            <div v-if="m.streaming && !m.content" class="think-wait">
              <p class="tw-line">
                <!--
                  八点旋转脉冲（Uiverse / abrahamcalsin）。
                  八个圆点各转 45° 分布在一个圆上，每个点按 -0.125 的步长错开延迟，
                  于是「点亮」的位置绕着圆周跑一圈 —— 用 8 个点做出一个旋转的错觉，
                  不需要真的旋转任何东西。
                  纯 CSS，8 个 <i> 是必需的结构（点数决定圆周上脉冲的密度）。
                -->
                <span class="think-spin" aria-hidden="true">
                  <i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i>
                </span>
                <span class="tw-text">{{ thinkingText }}</span>
              </p>
              <!-- 超过 4 秒才追加这一行。它说的是一件真实的事（推理模型的思考量大），
                   不是安慰话；短轮次里根本不会出现，也就不会显得啰嗦 -->
              <p v-if="showSlowTip" class="tw-slow">这一轮要斟酌的内容较多，正在仔细组织…</p>
            </div>
            <p v-else class="bubble-text">{{ m.content }}<span v-if="m.streaming" class="cursor mono">▍</span></p>
            <!--
              知识库引用卡片（Phase 4）。只在真的检索到资料时渲染 ——
              后端没资料就不发 citations 事件，因此「没有标题的知识回答」是常态而非异常。

              为什么用原生元素而不是 a-collapse / a-tag：
              vendor-antd 预算只剩约 9 kB（硬约定 #9），加一个自定义渲染的引用列表
              在这里只需要几十行 CSS，换来的是不必再掂量预算。
            -->
            <div v-if="m.citations.length" class="bubble-cites">
              <span class="cites-label">参考资料</span>
              <ul class="cites-list">
                <li v-for="(c, ci) in m.citations" :key="ci" class="cite-item">
                  <span class="cite-title">{{ c.docTitle || '未命名资料' }}</span>
                  <span v-if="c.section" class="cite-section">{{ c.section }}</span>
                  <!--
                    合成语料必须打角标（风险 R5）：它本质是「模型关于医疗知识的记忆的再表述」，
                    可能包含错误，用户有权知道这不是权威来源。上线前 rebuild 整体换成真实语料后，
                    这个角标自然消失 —— 不需要改前端。
                  -->
                  <span v-if="c.synthetic" class="cite-badge">合成示例</span>
                </li>
              </ul>
            </div>
            <p v-if="showBasis(m, i)" class="bubble-basis">依据：{{ basisText }}</p>
            <!--
              失败提示内联在气泡里，而不是弹一个全局 message：
              用户需要知道「是哪一条没成功」。可重试的错误才给按钮（如鉴权失败重试必然还失败）。
              用原生 button 而不是 a-button —— vendor-antd 预算只剩约 9 kB。
            -->
            <div v-if="m.error" class="bubble-error">
              <span class="be-msg">{{ m.error.msg }}</span>
              <span class="be-code mono">{{ m.error.code }}</span>
              <button
                v-if="m.error.retryable && i === chat.messages.length - 1"
                type="button"
                class="be-retry"
                :disabled="chat.sending"
                @click="onRetry"
              >
                重试
              </button>
            </div>
          </div>
          <RoleAvatar v-if="m.role === 'user'" class="msg-face" role="user" :size="36" />
        </div>
      </div>

      <footer class="chat-input">
        <!-- 已结束的会话（从历史里打开的）只能看不能发：后端对 CLOSED 会话返回 400，
             不提前拦住的话，用户会看到一条「看起来像故障」的报错 -->
        <div v-if="chat.sessionStatus === 'CLOSED'" class="closed-bar">
          <span class="closed-text">该会话已结束，只能查看历史消息。</span>
          <button type="button" class="ghost-btn is-primary" @click="onNewSession">开启新会话</button>
        </div>
        <template v-else>
          <!--
            下划线输入框（样式见文件末 .composer / .composer-line）。
            ⚠️ 必须给 textarea 套一层 `.composer`：那条聚焦渐变的实现是
            「一条绝对定位、宽度 0 的线，聚焦时铺满容器」，它需要一个
            `position: relative` 的定位基准，而 a-textarea 自己不能当基准
            （它的内部结构由 antd 决定，加定位会连带影响自动高度计算）。
            原始样例用了 `.input:focus + .input-border` 这个相邻兄弟选择器；
            这里改用 `.composer:focus-within`，理由是相邻兄弟依赖
            「span 紧挨在 input 后面」这个 DOM 细节，而 antd 的包裹层随时可能变
            —— `:focus-within` 只看「焦点在不在这个壳里」，与中间的节点结构无关。
          -->
          <div class="composer">
            <a-textarea
              v-model:value="input"
              :auto-size="{ minRows: 1, maxRows: 4 }"
              placeholder="说说你的感受…（回车发送，Shift+回车换行）"
              :disabled="!chat.canSend"
              @pressEnter="onSend"
            />
            <span class="composer-line" aria-hidden="true"></span>
          </div>
          <!--
            发送按钮（Uiverse / xopc333 样例改写）。与样例的五处差异，都不是单纯换色：

            ① **箭头朝上**：样例的路径指向 +x，配 `rotate(180deg)` 得到「朝左」。
               朝上要 `rotate(-90deg)`（CSS 正角是顺时针，负角才是逆时针）。

            ② **滑动方向改纵向**：样例是横向 `translateX(-56px)` —— 朝左的箭头左右换位，
               逻辑自洽；但箭头朝上之后若还横向滑动，读起来是「横向平移」而不是「往上送出」。
               改成 `flex-direction: column` + `translateY(-40px)`，第二个箭头从下方顶上来。

            ③ **尺寸 56 → 40**：输入框只有约 38px 高，56px 的圆会把整个输入区顶高一截，
               且与输入框的底线不在一个基线上。40px 与本页发送区高度对齐。

            ④ **颜色换主色**：样例的 `#f0eeef` 是给深色背景的浅灰环；本项目输入区是白卡，
               浅灰环在白底上几乎看不见（#f0eeef 对白只有约 1.1:1）。
               这里环与箭头统一取 `--primary`，悬停换 `--primary-dark`。

            ⑤ **补 loading / disabled**：原来是 a-button，这两个状态由 antd 提供；
               换成原生 button 之后必须自己做，否则发送期间按钮看不出在工作、
               也能被重复点击。见 `.send-spin` 与 `.send-btn:disabled`。
          -->
          <button
            type="button"
            class="send-btn"
            :class="{ 'is-busy': chat.sending }"
            :disabled="chat.sessionId === null || chat.sending"
            :aria-label="chat.sending ? '正在发送' : '发送'"
            :title="chat.sending ? '正在发送' : '发送'"
            @click="onSend"
          >
            <span class="send-box" aria-hidden="true">
              <span class="send-elem">
                <svg viewBox="0 0 46 40" xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M46 20.038c0-.7-.3-1.5-.8-2.1l-16-17c-1.1-1-3.2-1.4-4.4-.3-1.2 1.1-1.2 3.3 0 4.4l11.3 11.9H3c-1.7 0-3 1.3-3 3s1.3 3 3 3h33.1l-11.3 11.9c-1 1-1.2 3.3 0 4.4 1.2 1.1 3.3.8 4.4-.3l16-17c.5-.5.8-1.1.8-1.9z"
                  />
                </svg>
              </span>
              <span class="send-elem">
                <svg viewBox="0 0 46 40" xmlns="http://www.w3.org/2000/svg">
                  <path
                    d="M46 20.038c0-.7-.3-1.5-.8-2.1l-16-17c-1.1-1-3.2-1.4-4.4-.3-1.2 1.1-1.2 3.3 0 4.4l11.3 11.9H3c-1.7 0-3 1.3-3 3s1.3 3 3 3h33.1l-11.3 11.9c-1 1-1.2 3.3 0 4.4 1.2 1.1 3.3.8 4.4-.3l16-17c.5-.5.8-1.1.8-1.9z"
                  />
                </svg>
              </span>
            </span>
            <span class="send-spin" aria-hidden="true"></span>
          </button>
        </template>
      </footer>
    </section>

    <!-- ============ 右：上下文 ============ -->
    <aside class="chat-side">
      <!-- 关联测评 -->
      <div v-if="linkedAssessment" class="side-card">
        <h3 class="side-title">关联测评</h3>
        <div class="side-row">
          <span class="side-name">{{ linkedScale?.name ?? '心理自评' }}</span>
          <RiskLevelTag :level="linkedAssessment.riskLevel" />
        </div>
        <div class="side-score">
          <span class="side-score-num mono">{{ linkedAssessment.totalScore }}</span>
          <span class="side-score-unit">{{ linkedScale ? `/ ${linkedScale.maxScore} 分` : '分' }}</span>
        </div>
        <p class="side-note">
          {{ assessmentStatusText }} · 已作为对话背景
        </p>
      </div>
      <div v-else class="side-card is-muted">
        <h3 class="side-title">关联测评</h3>
        <p class="side-note">
          本次会话未关联测评。先完成一次测评，AI 的判断会更贴合你的情况。
        </p>
        <a-button size="small" type="link" class="side-link" @click="router.push('/scales')">
          去测评 →
        </a-button>
      </div>

      <!--
        危机出口（条件触发）。文案随来源分叉 —— 见 transferPromised：
        只有确实建了工单的路径才承诺「已为你转接人工」。
      -->
      <div v-if="crisisOn" class="side-card is-crisis">
        <h3 class="side-title">需要立即帮助？</h3>
        <p v-if="transferPromised" class="side-note">
          如果你有伤害自己的念头，请立刻联系专业人员。系统已为你转接人工，稍后会有专人跟进。
        </p>
        <p v-else class="side-note">
          如果你有伤害自己的念头，请立刻联系专业人员。也可以随时在「我的消息」里找医生聊一聊。
        </p>
        <a class="hotline mono" href="tel:12356">心理援助热线 12356</a>
        <a class="hotline mono" href="tel:01082951332">北京危机干预 010-82951332</a>
      </div>

      <!-- AI 推荐医生 -->
      <div class="side-card">
        <h3 class="side-title">AI 推荐医生</h3>
        <a-spin :spinning="doctorLoading" size="small">
          <template v-if="topDoctors.length">
            <div v-for="doc in topDoctors" :key="doc.doctorId" class="doc">
              <div class="doc-head">
                <RoleAvatar class="doc-avatar" role="doctor" :size="40" />
                <div class="doc-meta">
                  <div class="doc-name">{{ doc.doctorName }}</div>
                  <div class="doc-dept">{{ doc.department }} · {{ doc.title }}</div>
                </div>
              </div>
              <p class="doc-reason">{{ firstReason(doc.reason) }}</p>
            </div>
            <a-button size="small" type="link" class="side-link" @click="router.push('/ai-recommend')">
              查看完整推荐与理由 →
            </a-button>
          </template>
          <p v-else-if="!doctorLoading" class="side-note">
            完成测评后，这里会按风险等级与擅长方向推荐医生。
          </p>
        </a-spin>
      </div>

      <!-- 边界声明 -->
      <p class="side-footnote">
        AI 只能提供心理知识科普与情绪疏导，<strong>不进行诊断、不开具药物</strong>，也不替代医生面诊。
      </p>
    </aside>

    <!--
      「清空会话」的确认弹窗。

      为什么不用 `Modal.confirm`：它只给「确定 / 取消」两个出口，而这里要区分
      **两种清空** —— 只清聊天记录（AI 仍记得）与连记忆一起重置（从零开始）。
      两个出口只能把其中一种藏进正文里，用户会照着直觉点「确定」，
      而它未必是他要的那种；代价是一整段倾诉记录，且不可恢复。

      `centered` 是必需的：a-modal 默认贴顶（top: 100px），
      在这张只有一屏内容的弹窗上会把视觉重心整体拉到上沿，与页面的居中版式对不上。

      `:footer="null"` + 自绘底栏：antd 的 footer 只支持两个按钮，
      三选一的排布、以及「危险项更重」的权重语言都表达不出来。
    -->
    <a-modal v-model:open="clearOpen" :footer="null" :width="468" centered title="清空会话">
      <div class="cm-body">
        <p class="cm-lead">
          清空后无法恢复，请按需要选择。两种方式对「AI 是否还记得你说过的话」影响不同：
        </p>

        <!-- 两个选项各配一句后果说明。名称与下方按钮一一对应，充当图例 ——
             只靠按钮上那四个字，「聊天记录」和「记忆」的差别是读不出来的。 -->
        <ul class="cm-list">
          <li class="cm-item">
            <span class="cm-tag">只清空聊天记录</span>
            <span class="cm-desc">
              屏幕上的对话消失，但 AI 仍记得你说过的内容（前情摘要保留），可以接着聊。
            </span>
          </li>
          <li class="cm-item is-strong">
            <span class="cm-tag">清空并重置记忆</span>
            <span class="cm-desc">
              对话与记忆一并清零，之后是一段全新的咨询，AI 不再记得之前说过的任何内容。
            </span>
          </li>
        </ul>

        <div class="cm-ops">
          <button type="button" class="cm-btn" :disabled="clearing" @click="clearOpen = false">
            暂不清空
          </button>
          <button
            type="button"
            class="cm-btn is-primary"
            :disabled="clearing"
            @click="doClearKeepMemory"
          >
            只清空聊天记录
          </button>
          <button
            type="button"
            class="cm-btn is-danger"
            :disabled="clearing"
            @click="doClearAndReset"
          >
            清空并重置记忆
          </button>
        </div>
      </div>
    </a-modal>

    <!--
      「AI 正在分析」全屏动画（收到 recommend 事件后播放）。

      为什么要有这段动画：推荐本身是后端毫秒级算完的，直接跳转会让用户
      什么也没看见就换了一页，感觉像被弹走。动画的作用是**把因果讲清楚** ——
      先「读取测评 → 梳理对话 → 匹配医生」三步走完，再进入结果页，
      用户才会认为这几位医生是「根据我说的挑出来的」，而不是随机弹出来的。

      刻意不用遮罩盖住整页再加一个转圈：步骤文案是这段动画唯一有信息量的部分。
    -->
    <Transition name="analyze">
      <div v-if="analyzing" class="analyze-mask">
        <div class="analyze-box">
          <!--
            「AI 推荐」等待动画（Uiverse / alexruix 的方块弹跳，改成浅蓝色系）。
            纯 CSS：`::after` 是那个翻滚的方块，`::before` 是它在「地面」上的椭圆影子。
            ⚠️ 只换了视觉，**标题与三步文案刻意保留** —— 见上方注释：推荐本身是毫秒级
            算完的，这三行才是把「为什么推荐这几位」讲清楚的东西；换成纯 loading
            会让整段等待退化成「卡了一下」，反而降低了推荐的公信力。
          -->
          <div class="analyze-loader" aria-hidden="true"></div>

          <h2 class="analyze-title font-serif">正在为你匹配医生</h2>

          <ul class="analyze-steps">
            <li
              v-for="(s, i) in ANALYZE_STEPS"
              :key="s"
              class="as-item"
              :class="{ 'is-done': i < analyzeStep, 'is-active': i === analyzeStep }"
            >
              <span class="as-mark">{{ i < analyzeStep ? '✓' : '' }}</span>
              <span class="as-text">{{ s }}</span>
            </li>
          </ul>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { getRecommendDoctors } from '@/api/recommend'
import { useAssessmentStore } from '@/stores/assessment'
import { useChatStore } from '@/stores/chat'
import { RISK_LEVEL_DESC } from '@/types'
import type { RecommendResult } from '@/types'

const PRESET_PROMPTS = [
  '最近总是睡不好，该怎么办？',
  '我该怎么跟医生描述我的情况？',
  '有什么办法能缓解紧张情绪？',
]

/**
 * 「AI 正在分析」的步骤。顺序即用户能理解的推理链：
 * 先看我有什么底子（测评）→ 再看我刚才说了什么（对话）→ 最后才谈匹配。
 */
const ANALYZE_STEPS = [
  '读取你的测评结果',
  '梳理本次对话中提到的困扰',
  '匹配医生擅长方向与接诊情况',
]

/** 每一步点亮的间隔。三步 ≈ 2.4s：短到不烦人，长到能读清每一步 */
const ANALYZE_STEP_MS = 800
/** 最后一步点亮后多停留一会儿再跳，否则「匹配完成」这四个字来不及被看见 */
const ANALYZE_TAIL_MS = 420

/**
 * 等待期文案，按引导阶段给一句更具体的话。
 *
 * <p>为什么按阶段而不是轮换播放一堆花样：阶段是**真实状态**，用户看到
 * 「正在梳理你说的这些」时，AI 确实在做那件事；而随机轮播的「正在努力思考…」
 * 只是装饰，等得久了反而显得敷衍。
 *
 * <p>键取自后端 {@code InterviewStage} 的枚举名（经 meta 事件下发）。
 * 取不到就退回最后一项 —— <b>未知阶段必须仍然有话说</b>，
 * 否则新加阶段时会得到一个空白的等待态。
 */
const WAIT_COPY: Record<string, string> = {
  OPENING: '正在理解你的情况…',
  CHIEF_COMPLAINT: '正在梳理你说的这些…',
  SUMMARY_REFERRAL: '正在整理建议，并为你留意合适的医生…',
}
const WAIT_COPY_FALLBACK = '正在思考…'

/** 等待多久之后追加「内容较多」那一行 */
const SLOW_TIP_SECONDS = 4

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const store = useAssessmentStore()

/*
 * ⚠️ 等待态的三个派生量必须放在 `const chat = useChatStore()` **之后**。
 *
 * `watch()` 在**定义时就会立刻调用一次 getter** 去取初始值（不是等到值变化才跑），
 * 而 `<script setup>` 里 `const` 同样有暂时性死区。把它写在 store 初始化之前，
 * 定义这一行就会抛 `ReferenceError: Cannot access 'chat' before initialization`
 * —— setup() 直接崩，整页白屏，且 vue-tsc 与 vite build **都不报错**
 * （它们只做类型与打包，不执行 setup）。
 * 这属于硬约定 #12 的同类陷阱：凡是「看起来像声明、实际会立即执行」的 API
 * （`watch` / `watchEffect` / 立即执行的 computed 求值），都要确认它依赖的变量已初始化。
 */
const waitTick = ref(0)
let waitTimer: number | undefined

/**
 * 等待计时。
 *
 * <p>用一个每秒自增的 tick 而不是 `setInterval` 里直接改文案：
 * 时间只驱动「已经等了 n 秒」这一个事实，具体显示什么仍由 computed 决定，
 * 这样文案规则可以随时改而不用碰定时器。
 *
 * <p>`sending` 是整段流的标志（从请求发出到流结束）。它落到 false 时必须停表，
 * 否则用户在读回复时后台还在一秒一次地跑定时器 —— 白白占用主线程。
 */
watch(
  () => chat.sending,
  (sending) => {
    if (waitTimer !== undefined) {
      window.clearInterval(waitTimer)
      waitTimer = undefined
    }
    waitTick.value = 0
    if (sending) {
      waitTimer = window.setInterval(() => {
        waitTick.value += 1
      }, 1000)
    }
  },
)

onBeforeUnmount(() => {
  if (waitTimer !== undefined) {
    window.clearInterval(waitTimer)
    waitTimer = undefined
  }
})

/** 当前等待文案。阶段没取到时用兜底那句，绝不返回空串 */
const thinkingText = computed(() => WAIT_COPY[chat.stage.stage] ?? WAIT_COPY_FALLBACK)

/**
 * 是否显示「内容较多」的追加说明。
 *
 * <p>不做成「等越久文案越夸张」的梯度：只分「短」与「明显长」两档就够。
 * 多档会让同一次等待里文字反复变化，视线被反复拉住，反而更焦躁。
 */
const showSlowTip = computed(() => chat.sending && waitTick.value >= SLOW_TIP_SECONDS)

const input = ref('')
const bodyRef = ref<HTMLElement>()
const doctors = ref<RecommendResult[]>([])
const doctorLoading = ref(false)

/* ---- 推荐流程（收到 recommend 事件后播放的分析动画） ---- */
const analyzing = ref(false)
const analyzeStep = ref(0)

const topDoctors = computed(() => doctors.value.slice(0, 2))

/**
 * 顶栏标题右侧的状态注解（模板里由全角括号包住）。
 *
 * 文案由**会话状态**决定，而非会话 id —— 会话号已经去掉，用户需要知道的是
 * 「这次对话安不安全、还能不能继续」这类能指导行动的信息。
 * 四种状态的措辞都收在一句以内，避免括注把 18px 的标题挤成两行。
 */
const headNote = computed(() => {
  if (chat.opening) return '正在建立会话…'
  if (!chat.sessionId) return '会话未建立，请稍后重试'
  if (chat.sessionStatus === 'CLOSED') return '已结束，只能查看'
  return '内容仅用于本次咨询'
})

/** 本次会话关联的测评记录（用于右栏上下文卡与「依据」小字） */
const linkedAssessmentId = computed(() =>
  route.query.assessmentId ? Number(route.query.assessmentId) : chat.linkedAssessmentId,
)
const linkedAssessment = computed(() => store.findHistory(linkedAssessmentId.value))
const linkedScale = computed(() => {
  const scaleId = linkedAssessment.value?.scaleId
  return scaleId ? store.scales.find((s) => s.id === scaleId) : undefined
})

/** 「依据」只在首条已完成的 AI 回复上出现一次，重复展示没有信息增量 */
const firstAiIndex = computed(() => chat.messages.findIndex((m) => m.role === 'ai'))
const basisText = computed(() =>
  linkedAssessment.value
    ? `${linkedScale.value?.name ?? '心理测评'} · ${linkedAssessment.value.rawLevel || linkedAssessment.value.riskLevel}`
    : '',
)

function showBasis(m: { role: string; streaming: boolean }, index: number) {
  return m.role === 'ai' && !m.streaming && index === firstAiIndex.value && !!basisText.value
}

const crisisOn = computed(
  () => chat.crisisDetected || linkedAssessment.value?.crisisFlag === 1,
)

/**
 * 能否承诺「已为你转接人工」。
 *
 * 只在确实建了 CRISIS 工单的路径上为真：
 * ① reason=INPUT（居民自己表达了危机 → 后端 crisisStream 建工单）；
 * ② 会话关联的测评危机项阳性（测评提交时 AlertEngine 建工单）。
 * 模型输出被安全护栏拦下（reason=OUTPUT）时没有工单在流转 ——
 * 那时说「已为你转接」就是一句不会兑现的话，在心理场景里比不承诺更伤人。
 */
const transferPromised = computed(
  () => chat.crisisReason === 'INPUT' || linkedAssessment.value?.crisisFlag === 1,
)

/**
 * 状态说明。
 * rawLevel（临床五级）与 riskLevel（系统四档）会不一致：危机项阳性时 rawLevel 仍可能是「轻度」。
 * 两者并列展示会自相矛盾，因此危机时直接说明危机项状态。
 */
const assessmentStatusText = computed(() => {
  const a = linkedAssessment.value
  if (!a) return ''
  return a.crisisFlag === 1 ? '危机项阳性，需人工介入' : RISK_LEVEL_DESC[a.riskLevel]
})

function firstReason(reason: string) {
  const parts = (reason || '').split('；').map((s) => s.trim()).filter(Boolean)
  return parts.length ? parts[0] : '综合擅长方向与接诊情况排序靠前'
}

/* ==================== 自动滚动到底部 ==================== */

/**
 * 距底部多少像素内算「贴着底部」。
 *
 * 与 `components/ChatPanel.vue` 的 NEAR_BOTTOM_PX 取同一个值 —— 两处都是
 * 「用户在翻旧记录时不要把他拽走」，判据应当一致，否则同样一次滚动
 * 在医患对话里跟、在 AI 对话里不跟，用户会觉得「有时灵有时不灵」。
 *
 * 取 80 而不是 0：`scrollTop` 是整数、`scrollHeight - clientHeight` 常常带小数，
 * 贴着底部时两者可能差 0.5～1px；而触控板上轻微一碰就离开底部十几像素。
 * 留 80px 的容差，既不会误判「用户想翻看」为「还在底部」，也不会因为
 * 一像素的抖动就停止跟滚。
 */
const NEAR_BOTTOM_PX = 80

/**
 * 是否处于「跟滚」状态。
 *
 * 用普通变量而不是 ref：它**不参与渲染**，只影响滚动这一个副作用。
 * 做成 ref 会让它的每次翻转都触发组件更新，而翻转恰恰发生在滚动事件里 ——
 * 等于在滚动过程中制造渲染，白白抢帧。
 */
let stickToBottom = true

/** 合并本帧内的多次滚动请求。流式期间每帧最多滚一次，见 scheduleScrollToBottom */
let scrollRaf = 0

/** 滚动位置是否贴着底部 —— 用户正在往上翻时，不该把他拽回底部 */
function nearBottom(): boolean {
  const el = bodyRef.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM_PX
}

/** 立刻滚到底部（同步执行，调用方负责确保 DOM 已布局） */
function doScrollToBottom() {
  const el = bodyRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/**
 * 把本帧内的多次滚动请求合并成一次。
 *
 * <h3>为什么必须合并</h3>
 * 流式回复里 `onToken` 每收到一个片段就把 `content` 改一次，而 deep watch
 * 会因此每片段触发一次。一次回复几千个片段 = 几千次
 * 「读 scrollHeight（强制布局）+ 写 scrollTop（强制重绘）」。
 * 这是典型的 layout thrashing：读与写交替进行，浏览器每轮都得重新算布局。
 * 合并到 `requestAnimationFrame` 之后，无论这一帧来了多少片段，都只滚一次。
 *
 * <h3>为什么用 rAF 而不是 nextTick</h3>
 * `nextTick` 只等到「Vue 把 DOM 改完」，**不保证浏览器已完成布局**。
 * 若最后一个片段的渲染让气泡变高（换行、引用卡片插入），
 * 此刻读到的 `scrollHeight` 仍是旧值，滚动会差一点 ——
 * 表现就是「最下面一行露不出来」。rAF 在浏览器的渲染时机里执行，
 * 此时布局已完成，`scrollHeight` 是最终值。
 */
function scheduleScrollToBottom() {
  if (scrollRaf) return // 本帧已排队，无需重复
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = 0
    doScrollToBottom()
  })
}

/**
 * 内容变化时的跟滚入口。
 *
 * 只有「用户本来就贴着底部」才跟 —— 否则他在往上翻看前文时，
 * 每一个新片段都会把他拽回底部，等于禁止他阅读。
 */
function followScroll() {
  if (stickToBottom) scheduleScrollToBottom()
}

/**
 * 用户主动滚动时更新跟滚意图。
 *
 * 用 `@scroll` 而不是 `@wheel` / `@touchmove`：后者只覆盖鼠标滚轮与触摸，
 * 漏掉拖滚动条、键盘 PageUp/Home、以及浏览器的惯性滚动。
 * `scroll` 事件对所有输入方式一视同仁，且是滚动实际生效后的结果 ——
 * 直接读它比推断「用户想干嘛」更可靠。
 *
 * 注意：这里把「滚到底部」也算作恢复跟滚，所以用户自己滑回底部后，
 * 后续新内容会继续自动跟随，不需要额外的「取消固定」操作。
 */
function onBodyScroll() {
  stickToBottom = nearBottom()
}

/**
 * AI **开始输出**的瞬间，把视图贴到底部。
 *
 * <p>触发点是 `chat.messages` 的**长度**变化，而不是 `sending`。
 * 理由：`sending` 是「本轮进行中」这一整段的标志，从请求发出一直亮到流式结束 ——
 * 只有它**变 true 的那一帧**才等于「刚开始」，之后几千个 token 都还是 true。
 * 而本轮 AI 气泡是 `send()` 在 `sending` 置 true 的同一次同步执行里 push 进数组的
 * （`stores/chat.ts`：先 push 用户气泡、再 push AI 气泡、最后 `sending.value = true`），
 * 所以「数组变长」与「刚开始」是同一时刻，且**只会发生一次**，
 * 天然就是我们要的那个边沿。
 *
 * <h3>为什么不用 `sending` 的 true 上升沿</h3>
 * 两者在同一轮里都会触发一次，但 `sending` 还承担「重试」语义：
 * 重试会从数组里 pop 掉失败的两条再 send，数组长度可能**不变**（-2 再 +2），
 * 靠长度判断会漏掉；而 `sending` 在重试时同样会 false→true，覆盖得更全。
 * 代价是 `sending` 需要额外一道闸（见下方 `aiBubbleCount` 钳制），所以这里
 * **两个都用**：长度变化负责正常发送，`sending` 上升沿负责重试这类数组被重写的情况。
 *
 * <h3>为什么无条件滚，不看 `stickToBottom`</h3>
 * 这是用户明确要的语义：AI 一开口就把最新内容推到你眼前。
 * 与「流式追加时只在贴底才跟」（`followScroll`）刻意分成两条路径 ——
 * 后者保护的是「用户正在往上翻旧记录」的阅读姿势，前者是一个**新回合的开始**，
 * 此时用户刚发完问题，理应看到回答从第一行开始长出来。
 *
 * <h3>为什么用 nextTick 而不是 rAF</h3>
 * `scheduleScrollToBottom()` 的 rAF 是为**高频追加**设计的：等一帧，把这一帧内
 * 的所有变化合并成一次滚动。这里是一次性的：刚 push 的空 AI 气泡本身就是当前
 * 视口底部之外的那一行，`nextTick` 等 Vue 把这一行插进 DOM 后立刻滚，
 * 视觉上就是「问题发出去、界面立刻沉到底」。
 * 若交给 rAF，本轮的 `onMeta` / `onCitations` 会先把 `content` 改几次，
 * 虽然结果同样是滚到底，但中间可能先按旧高度滚一次，反而多一次抖动。
 */
function onReplyStart() {
  nextTick(() => {
    doScrollToBottom()
  })
}

/**
 * 已渲染的 AI 气泡条数，用来识别「新开的这一轮」。
 *
 * <p>启动时读一次初值：进入页面会先恢复历史会话（`chat.enter()`），
 * 那一批消息是「过去的对话」不是「刚开始的一轮」，
 * 不读初值就会在进页面时误触发一次滚到底（用户刚打开就发现历史被拽到了末尾）。
 */
let aiBubbleCount = chat.messages.filter((m) => m.role === 'ai').length

// 消息内容变化（含流式追加）→ 贴底时跟滚。
// 这里**不用 flush 选项**：默认的 pre 队列已经够早，真正的时机控制交给 rAF。
watch(chat.messages, followScroll, { deep: true })

/**
 * AI 开始输出的瞬间 → 无条件滚到底。
 *
 * <p>用 `deep`：`messages` 是数组，push 新元素只会改数组本身、不会改内部对象，
 * 不 deep 也能触发；加 deep 是为了与 `followScroll` 那个 watch 保持一致的语义，
 * 免得日后有人给气泡加字段时两处行为不一致。
 */
watch(
  chat.messages,
  (list) => {
    const count = list.filter((m) => m.role === 'ai').length
    // 只认「变多」：内容追加不会改变条数，重试的 pop 也不会（它是先减后增）。
    // 条数减少（清空会话）不触发滚动 —— 那是离开这一屏，滚动没有意义。
    if (count > aiBubbleCount) onReplyStart()
    aiBubbleCount = count
  },
  { deep: true },
)

/**
 * 重试时的补位触发。
 *
 * <p>重试会先 pop 掉失败的一轮再重新 send（`stores/chat.ts` 的 `retry()`），
 * 若被 pop 掉的两条与重新 push 的两条数量相同，数组长度回到原值，
 * 上面那个「变多」判据就漏掉了。`sending` 的 true 上升沿在这里是可靠的。
 *
 * <p>用 `aiBubbleCount` 做闸，保证与上方 watch 不会重复触发同一轮：
 * 正常发送时上方的 watch 先把计数改掉，这里读到的就是「已处理过」的计数。
 */
watch(
  () => chat.sending,
  (now) => {
    if (!now) return
    const count = chat.messages.filter((m) => m.role === 'ai').length
    if (count <= aiBubbleCount) return
    onReplyStart()
    aiBubbleCount = count
  },
)


async function onSend() {
  const text = input.value.trim()
  if (!text || chat.sending || chat.sessionId === null) return
  input.value = ''
  const sid = chat.sessionId
  await chat.send(text)
  await maybeEnterRecommendFlow(sid)
}

async function sendPreset(text: string) {
  if (chat.sending || chat.sessionId === null) return
  const sid = chat.sessionId
  await chat.send(text)
  await maybeEnterRecommendFlow(sid)
}

/**
 * 重试最后一次失败的提问。
 * store 里会把失败的那一轮整体回退再重发，所以这里不需要清空输入框或另建气泡。
 */
async function onRetry() {
  const sid = chat.sessionId
  await chat.retry()
  await maybeEnterRecommendFlow(sid)
}

/* ==================== 推荐流程 ==================== */

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * 本轮结束后是否要进入推荐流程。
 *
 * <p>判据只有服务端给的那一个标记（`recommend` 事件）—— 前端不自己认关键词，
 * 因为「AI 问过没有」是这段判定的另一半，而那一半只有服务端看得到。
 *
 * @param sid 本轮开始时记下的会话 id。必须提前留住：动画结束后会话会被关闭，
 *            那时 `chat.sessionId` 已经变成 null，而跳转还需要它去取关联测评。
 */
async function maybeEnterRecommendFlow(sid: number | null) {
  if (!chat.recommendAccepted || sid === null) return
  // 消费掉标记：否则重试或再发一条消息会重复播一次动画
  chat.recommendAccepted = false
  await playAnalyzeAndJump(sid)
}

/**
 * 播「AI 正在分析」动画，结束后结束会话并跳转到推荐结果页。
 *
 * <p>动画放在前端而不是「后端算完再返回」：算推荐本身是毫秒级的，
 * 让用户等一个真实的慢请求既无意义又会拖长首屏。
 * 这里要表达的是**因果**（我看了你的测评、你的话，才挑出这几位医生），
 * 而因果是可以用文案讲清楚的，不必真等。
 */
async function playAnalyzeAndJump(sid: number) {
  analyzing.value = true
  analyzeStep.value = 0
  // 逐步点亮。用定时器而不是纯 CSS 动画：步骤文案要跟着状态变，
  // 而「走到第几步」还要参与「跳转前是否等够时间」的判断 —— 两件事共用一份状态。
  for (let i = 1; i <= ANALYZE_STEPS.length; i++) {
    await wait(ANALYZE_STEP_MS)
    analyzeStep.value = i
  }
  await wait(ANALYZE_TAIL_MS)

  /*
   * 这里**不再关闭会话**。
   *
   * 原先关它是为了「用户不会再回到这个页面，别在历史列表里留一条没关掉的记录」——
   * 而「历史会话」功能已经取消，聊天框展示的是这个居民唯一的一段对话，
   * 他随时可能再点「AI 问诊」接着聊。关掉会得到一个只能看不能发的会话
   * （后端按状态拒写），下次进来就成了「刚打开就坏了」。
   */
  router.push(`/ai-recommend?sessionId=${sid}`)
}

async function loadDoctors() {
  if (chat.sessionId === null) return
  doctorLoading.value = true
  try {
    doctors.value = await getRecommendDoctors(chat.sessionId, 3)
  } catch {
    // 推荐失败不影响对话主链路，右栏自行降级为空态
  } finally {
    doctorLoading.value = false
  }
}

/**
 * 开启新会话。
 *
 * <p>唯一的入口是「会话已结束」提示条上那个按钮 —— 正常情况下每个居民只有
 * 一条进行中的会话，进页面就是那一条，不需要也不该有「换个会话」的操作。
 *
 * <p>从测评页跳来的场景（URL 带 assessmentId）要沿用这个关联，否则新会话会丢掉
 * 测评背景 —— 那会让 AI 一上来就问「你最近怎么样」，而系统里明明有刚做完的测评。
 */
async function onNewSession() {
  const assessmentId = route.query.assessmentId ? Number(route.query.assessmentId) : null
  const sid = await chat.startNewSession(assessmentId)
  if (sid === null) {
    message.error('开启新会话失败，请稍后重试')
    return
  }
  input.value = ''
  await loadDoctors()
  // 新会话从空白开始，恢复跟滚意图 —— 否则上一段对话里「用户往上翻过」
  // 留下的 stickToBottom=false 会带过来，导致新会话的第一条回复不跟随。
  stickToBottom = true
  scheduleScrollToBottom()
}

/**
 * 头部「推荐医生」按钮：播分析动画，然后跳到 AI 推荐结果页。
 *
 * 与「用户答应接受推荐」走**同一条**路径（{@link playAnalyzeAndJump}）。
 * 只留一个实现，是因为两条路径分头写的话，其中一条迟早会漏掉「带上 sessionId」
 * 这一步，而那种漏不报错，只表现为推荐结果页拿不到关联测评。
 */
async function onClickRecommend() {
  const sid = chat.sessionId
  if (sid === null) return
  await playAnalyzeAndJump(sid)
}

/**
 * 「清空会话」是否可用。
 *
 * <p>判据刻意**不只是「有没有消息」**：`summary`（前情摘要）与 `consult_message`
 * 是两条独立的存储。用户选过「只清空聊天记录」之后，屏幕上一句话都没有了，
 * 但 AI 依然记得他讲过的内容 —— 若这时把按钮灰掉，「清空并重置记忆」
 * 就永远点不到了，而那恰恰是最需要它的场景。
 */
const canClear = computed(
  () => chat.sessionId !== null && !chat.sending && (chat.hasMessages || !!chat.summary),
)

/** 清空弹窗的开关 */
const clearOpen = ref(false)
/** 清空进行中：把三个按钮一起锁住，避免连点造成重复删除 / 重复建会话 */
const clearing = ref(false)

/**
 * 头部「清空会话」按钮：先开确认弹窗，再由用户在两种清空方式里选一个。
 *
 * <h3>为什么必须先确认</h3>
 * 这是本页唯一不可撤销的操作。后端是真的 `DELETE`（`consult_message` 没有
 * `is_deleted` 列），删掉就没了。而它又紧挨着「推荐医生」—— 两个按钮同为胶囊形，
 * 一旦误点，用户失去的是一整段倾诉记录，且无法恢复。二次确认的成本远低于此。
 *
 * <h3>为什么是「一个弹窗两个选项」，而不是两个并排按钮</h3>
 * 「只清空聊天记录」与「清空并重置记忆」只差一个词，后果却差得很远：
 * 前者删完 AI 还记得，后者删完从零开始。并排放在头部，用户会按
 * 「哪个更像我要的」随手点一个 —— 而这个差别不是按钮文案能承担的选择。
 * 放进弹窗里各配一句后果说明，是在**操作发生之前**把这个差别讲清楚。
 */
function openClearDialog() {
  if (!canClear.value) return
  clearOpen.value = true
}

/**
 * 选项一：只清空聊天记录，**保留上下文记忆**。
 *
 * <p>后端 `clear-messages` 只删 `consult_message` 的行，不碰
 * `consult_session.summary` —— 所以屏幕清空了，AI 下一轮拿到的 L4 前情摘要
 * 仍是原来那份，它依然记得用户说过什么。这正是这个选项的定义，不是「没清干净」。
 * 阶段（Redis `stageStore`）同样不动：用户已经讲过的内容不该因为清屏就倒退回开场，
 * 否则 AI 会重新问一遍问过的事，与保留下来的摘要自相矛盾。
 */
async function doClearKeepMemory() {
  if (clearing.value) return
  clearing.value = true
  try {
    const deleted = await chat.clearMessages()
    if (deleted === null) {
      message.error('清空失败，请稍后重试')
      return
    }
    clearOpen.value = false
    // 给的是「删掉了多少条」而不是笼统的「已清空」：用户能据此确认
    // 确实作用在了这次会话上，而不是点了个没反应的按钮
    message.success(deleted > 0 ? `已清空 ${deleted} 条聊天记录` : '当前没有可清空的记录')
    // 列表清空后内容高度归零，滚动位置失去意义，直接贴回顶部即可
    if (bodyRef.value) bodyRef.value.scrollTop = 0
  } finally {
    clearing.value = false
  }
}

/**
 * 选项二：清空聊天记录，并**彻底重置记忆** —— 相当于从零开始一段全新的咨询。
 *
 * <h3>为什么必须先 close 再 open，光把 sessionId 丢掉是不够的</h3>
 * 后端 `AiConsultService.openSession` 对每个居民**只保留一条 OPEN 会话**：
 * 已有就复用。所以「先清空本地 id 再开一条」（= store 的 `startNewSession`）
 * 是无效的 —— 后端会把原来那条连同它的 `summary`（记忆本体）和阶段一起还回来，
 * 界面看起来「重置过了」，AI 却仍然记得。必须先把旧会话置为 CLOSED，
 * 后端找不到 OPEN 的，才会真的 insert 一条新的。
 * （`closeSession` 顺带清掉 Redis 里的阶段状态 —— 那是记忆里「阶段」那一半。）
 *
 * <h3>顺序不能反</h3>
 * `close()` 之后 store 的 `sessionId` 就被置空了，再想删消息也没有 id 可用。
 * 所以是「先删消息 → 再关会话 → 最后开新的」。
 *
 * <h3>刻意不带 assessmentId</h3>
 * 这一项的定义就是「从零开始」。把上一段咨询的测评背景带进新会话，
 * 等于 AI 一开口就仍然知道你刚做完哪张量表、拿了多少分 —— 那不叫从零开始。
 */
async function doClearAndReset() {
  if (clearing.value) return
  const sid = chat.sessionId
  if (sid === null) return
  clearing.value = true
  try {
    // ① 先删消息（「清空会话」里「清空」的那一半）
    const deleted = await chat.clearMessages()
    if (deleted === null) {
      message.error('重置失败，请稍后重试')
      return
    }
    // ② 结束旧会话：后端置 CLOSED，并清掉 Redis 里的阶段状态
    try {
      await chat.close()
    } catch {
      /*
       * 关不掉（网络 / 5xx）时 store 的 sessionId 已被 close() 的 finally 置空，
       * 页面会停在「会话未建立」的假故障态：发不出消息，也没有可点的入口。
       * 这里重新 enter() 一次把它挂回去 —— 后端会复用那条仍然 OPEN 的旧会话，
       * 用户看到的是「对话还在，重置没成功」，而不是一个动弹不得的空壳。
       */
      await chat.enter()
      message.error('重置失败，请稍后重试')
      return
    }
    // ③ 开一条新会话。旧会话已 CLOSED → 后端找不到 OPEN 的，必定新建一条；
    //    新会话的 summary 为空 = 记忆清零
    const newId = await chat.startNewSession()
    if (newId === null) {
      // 同上：别把页面留在「没有会话」的空转状态
      await chat.enter()
      message.error('重置失败，请稍后重试')
      return
    }
    clearOpen.value = false
    message.success(
      deleted > 0 ? `已清空 ${deleted} 条记录，并开始一段全新的咨询` : '已开始一段全新的咨询',
    )
    input.value = ''
    await loadDoctors()
    // 新会话从空白开始，恢复跟滚意图 —— 否则上一段对话里「用户往上翻过」
    // 留下的 stickToBottom=false 会带过来，导致新会话的第一条回复不跟随。
    stickToBottom = true
    scheduleScrollToBottom()
  } finally {
    clearing.value = false
  }
}

onMounted(async () => {
  const assessmentId = route.query.assessmentId ? Number(route.query.assessmentId) : null
  // 关联测评卡依赖量表与历史，两个请求不阻塞会话建立
  store.loadScales()
  store.loadHistory().catch(() => undefined)

  /*
   * 进入页面只需要做一件事：把「这个居民的这段对话」装进 store。
   *
   * 走哪条路交给 `chat.enter()` 判断 —— 带 assessmentId（从测评结果页发起）
   * 就直接开：后端会复用同一条会话并把这次测评关联上，所以记录不会断；
   * 否则先恢复最近一段对话，确实没有才新建。
   *
   * 这里刻意不再自己写一遍分支：页面侧多一份判断，迟早会与 store 那套漂移，
   * 而漂移的表现恰好是「有时进来看不到之前的记录」—— 不报错，最难查。
   */
  if (!(await chat.enter(assessmentId))) {
    message.error('开启会话失败，请稍后重试')
    return
  }
  if (!doctors.value.length) await loadDoctors()
  // 进入页面时恢复跟滚意图。
  // 恢复出来的历史会话若曾「用户往上翻过」，上一轮留下的 stickToBottom=false
  // 会跟着页面生命周期残留下来 —— 表现是「刚进来时 AI 回复不往下跟」，
  // 而用户此时明明没有在翻旧记录。显式置回 true，与 onNewSession 同一处理。
  stickToBottom = true
  scheduleScrollToBottom()
})
</script>

<style scoped>
.chat-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  max-width: var(--content-max);
  margin: 0 auto;
  padding: 16px var(--content-pad) 24px;
  /* 100vh 减 40px 是页底留白；顶栏已移入侧栏，不再需要减 --header-h */
  height: calc(100vh - 40px);
  min-height: 520px;
  box-sizing: border-box;
}

/* ---------- 对话 ---------- */
/*
 * 窗口底色 = L1 白卡（2026-10-13 回退）。
 * 前一天试过「铺局部背景图 + 头/输入区毛玻璃 + 消息区透明 + 气泡投影」这一整套，
 * 用户评估后要求换回纯白。⚠️ 这四处是**一套**联动改法，回退必须一起回退 ——
 * 只退底色、留着透明的 .chat-body 和气泡阴影，会得到「没有衬托的浮空气泡」。
 *
 * 圆角 --radius-sm(12) → --radius-lg(20)：本页原有的小圆角与「科技感」方向相悖，
 * 也与整站页面级大卡（hero / ScaleCard）的规格不一致。
 */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--card-border);
  background: var(--card-bg);
}

.chat-head-id {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

/* AI 身份标。尺寸与底衬交给 `RoleAvatar`（48px），这里只管两件本页特有的事：
   ① 定位（涟漪要挂它身上，所以得是 relative）；② 那一圈外扩涟漪。 */
.ai-mark {
  position: relative;
  flex: none;
}

/* 外扩涟漪。周期取 4s —— 比心跳慢、比静止活：在余光里持续存在，
   但不会把注意力从对话上拽走。科技感不是「动得多」，是「动得克制但从不停」。 */
.ai-mark::after {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 50%;
  border: 1px solid var(--primary);
  animation: mark-ripple 4s var(--ease) infinite;
}

@keyframes mark-ripple {
  0% {
    transform: scale(0.92);
    opacity: 0.55;
  }
  70%,
  100% {
    transform: scale(1.28);
    opacity: 0;
  }
}

/* 标题行：绿点 + 标题 + 括注，三者同处一行。
   用 flex 而不是 inline 排 —— 6px 的绿点在 18px 文本里靠 vertical-align
   很难对准（不同字体基线不同），交给 align-items 一次解决。 */
.chat-title {
  display: flex;
  align-items: center;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
}

/* 括注：**紧贴**标题（不留 margin —— 全角括号自带视觉间隙，再加边距会显得松散）。
   字号写死 12.5px、不继承标题的 18px —— 它是注解，不是标题的一部分。
   字体显式回落到无衬线：标题走衬线是有意为之的「标题感」，
   但小字用宋体在屏幕上发虚，这里保持与正文一致的观感。
   字色退到 --text-3，视觉重心仍落在标题上。 */
.title-note {
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 400;
  color: var(--text-3);
}

/* 在线指示点。会话未建立时转灰并停掉呼吸 ——
   一个「一直绿着」的灯，会让旁边「会话未建立」那行字变成谎言 */
.live-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--risk-a);
  box-shadow: 0 0 0 3px var(--risk-a-soft);
  animation: live-breathe 2.4s var(--ease) infinite;
}

.live-dot.is-off {
  background: var(--text-3);
  box-shadow: none;
  animation: none;
}

@keyframes live-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.chat-head-ops {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 原生按钮：vendor-antd 预算只剩约 9 kB，能不加 antd 组件就不加 */
.ghost-btn {
  padding: 4px 12px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.ghost-btn:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.ghost-btn.is-primary {
  border-color: var(--primary);
  background: var(--primary-soft);
  /* ⚠️ 主色浅底上的文字必须深一档：--primary 落在 --primary-soft 上只有 4.44，
     差一点不到 AA 的 4.5；--primary-dark 是 5.76。与风险色阶「soft 底配 ink」
     同一条判据（全站硬约定）。 */
  color: var(--primary-dark);
}

/* ---------- 实心主色变体：「推荐医生」（本页的主行动召唤） ---------- */
.ghost-btn.is-solid {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-color: var(--primary);
  background: var(--primary);
  color: var(--on-primary);
  font-weight: 500;
  box-shadow: var(--shadow-primary);
}

/* 用 border 拼一个 5×5 的 chevron —— 与工单折叠箭头同一手法，零新增图标依赖 */
.ghost-btn.is-solid::after {
  content: '';
  width: 5px;
  height: 5px;
  border-top: 1.5px solid currentColor;
  border-right: 1.5px solid currentColor;
  transform: rotate(45deg);
  transition: transform var(--dur) var(--ease);
}

/* 必须覆盖 .ghost-btn:hover 的「描边 + 蓝字」—— 实心主色底上蓝字不可读 */
.ghost-btn.is-solid:hover:not(:disabled) {
  border-color: var(--primary-dark);
  background: var(--primary-dark);
  color: var(--on-primary);
  box-shadow: var(--shadow-hover);
  transform: translateY(-1px);
}

.ghost-btn.is-solid:hover:not(:disabled)::after {
  transform: rotate(45deg) translate(1px, -1px);
}

.ghost-btn.is-solid:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: var(--shadow-primary);
}

/* 会话未建立时禁用：退回中性灰面，不保留主色与投影，避免「看起来能点」 */
.ghost-btn.is-solid:disabled {
  border-color: var(--card-border);
  background: var(--panel-bg);
  color: var(--text-3);
  box-shadow: none;
  cursor: not-allowed;
}

.ghost-btn.is-block {
  display: block;
  width: 100%;
  padding: 8px 12px;
}

/* 描边变体的禁用态。
   上面那条 :disabled 只覆盖 .is-solid，而「清空会话」是素描边 —— 不补这条，
   它在禁用时仍带 hover 效果（.ghost-btn:hover 不看 disabled），
   看起来可点、点了没反应，比灰下去更让人困惑。 */
.ghost-btn:disabled {
  border-color: var(--card-border);
  color: var(--text-3);
  background: transparent;
  cursor: not-allowed;
}

.ghost-btn:disabled:hover {
  border-color: var(--card-border);
  color: var(--text-3);
}

/* ==================== 清空会话弹窗 ====================
   弹窗内容是 `a-modal` 默认插槽里的节点，编译时就带上了本组件的 scope 属性，
   所以 scoped 样式对它生效（Teleport 只搬 DOM，不会摘掉属性）。
   ⚠️ 这里刻意不写任何 antd 内部类（.ant-modal-*）的覆盖规则：那些类名不在
      scoped 作用域里，只能靠 :deep 穿透，而 antd 一旦升版改了类名就会静默失效 ——
      收益是几个像素的内边距，代价是一处「改坏了也不报错」的隐患。 */

.cm-lead {
  margin: 0 0 14px;
  font-size: 13.5px;
  line-height: 1.75;
  /* 弹窗正文是「说明」而不是「辅助小字」，走 --text-2；--text-3 更浅也更难读 */
  color: var(--text-2);
}

.cm-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 20px;
  padding: 0;
  list-style: none;
}

/*
 * 两个选项各占一块 L2 面。
 * 左侧留一条 3px 透明边：它是「更重的那一项」实色色条的位置 —— 两块都留、
 * 只给其中一块上色，否则两块内容会错开 3px，看起来像没对齐。
 * 用 border-left 而不是 inset 投影：卡内浅色分区不再叠阴影（面分级规则 ②）。
 */
.cm-item {
  padding: 10px 12px;
  border-left: 3px solid transparent;
  border-radius: var(--radius-ctrl);
  background: var(--panel-bg);
}

/* 不可逆、且影响更大的一项：借危险色阶表达「更重」。
   色相只承担权重，真正的区分依据是两段文案 —— 不靠颜色让用户猜。 */
.cm-item.is-strong {
  border-left-color: var(--risk-d);
  background: var(--risk-d-soft);
}

.cm-tag {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}

.cm-item.is-strong .cm-tag {
  /* ⚠️ soft 底上的文字必须走同族 ink：--risk-d 实色落在这里只有 4.21，AA 要 4.5 */
  color: var(--risk-d-ink);
}

.cm-desc {
  display: block;
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.65;
  /* ⚠️ 两块底都是浅面，--text-3 不达标（panel-bg 4.27 / d-soft 约 4.3）→ 用 --text-2 */
  color: var(--text-2);
}

.cm-ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/*
 * 原生按钮，不用 a-button：最重那一项的填充色必须是项目自己的 --risk-d-ink，
 * 而 antd 的 `danger` 默认色（#ff4d4f）配白字只有约 3.4:1，不达 AA ——
 * 要走 antd 就得改全局 colorError，那会连带动到 Alert / message 的整套红色。
 */
.cm-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-ctrl);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.cm-btn:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

/* 中间项：比「暂不清空」重一档，但比「清空并重置记忆」轻 */
.cm-btn.is-primary {
  border-color: var(--primary);
  color: var(--primary);
}

.cm-btn.is-primary:hover:not(:disabled) {
  background: var(--primary-soft);
  /* ⚠️ --primary 落在 --primary-soft 上只有 4.44（AA 要 4.5）→ 深一档 */
  color: var(--primary-dark);
}

/* 最重的一项：实心危险色。
   ⚠️ 底色用 --risk-d-ink 而不是 --risk-d 实色：后者配白字只有 4.21，不达 AA。
      这与「soft 底配 ink 文字」是同一条判据，只是这里反过来用在填充上。 */
.cm-btn.is-danger {
  border-color: var(--risk-d-ink);
  background: var(--risk-d-ink);
  color: var(--on-primary);
  font-weight: 500;
}

/* 悬停只做「抬起」，不换色：危险色阶里没有比 ink 再深的一档，
   硬造一个色值就又多一处没有出处、也没人记得住来历的颜色 */
.cm-btn.is-danger:hover:not(:disabled) {
  border-color: var(--risk-d-ink);
  color: var(--on-primary);
  box-shadow: var(--shadow-hover);
  transform: translateY(-1px);
}

.cm-btn.is-danger:active:not(:disabled) {
  transform: translateY(0);
  box-shadow: none;
}

/* 禁用态统一收口放在最后：三个变体与 `.cm-btn:disabled` 的特异性同为 (0,2,0)，
   写在前面会被后面同等特异性的变体规则盖掉（这条正是「顺序即优先级」的坑）。 */
.cm-btn:disabled,
.cm-btn.is-primary:disabled,
.cm-btn.is-danger:disabled {
  border-color: var(--card-border);
  background: var(--panel-bg);
  color: var(--text-3);
  box-shadow: none;
  transform: none;
  cursor: not-allowed;
}

/* ---------- 引导问诊阶段指示条 ----------
   已随模板一并移除（见模板里那段说明）。整块样式保留在这里是为了
   恢复时不必重画连接线 —— 那是纯 CSS 手画的，重来一遍要试几轮。
   当前没有任何元素使用这几个类，不会产出多余样式（构建时按选择器引用裁剪）。 */
/* .stage-bar / .stage-item / .stage-dot / .stage-label —— 已停用 */

/* ---------- 前情摘要 ---------- */
.summary-note {
  max-width: 86%;
  margin: 0 auto 16px;
  padding: 10px 14px;
  background: var(--panel-bg);
  border: 1px dashed var(--card-border);
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--text-3);
}

.sn-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 6px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  font-size: 11px;
  color: var(--text-3);
}

/* ---------- 已结束会话的提示条 ---------- */
.closed-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.closed-text {
  font-size: 13px;
  /* 输入区已回退为白卡（--card-bg），--text-3 在白底是 4.8，达标 */
  color: var(--text-3);
}

/* ---------- 「AI 正在分析」全屏动画 ---------- */
.analyze-mask {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: color-mix(in srgb, var(--card-bg) 88%, transparent);
  backdrop-filter: blur(6px);
}

.analyze-box {
  width: 100%;
  max-width: 380px;
  padding: 36px 30px 30px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  text-align: center;
}

/* ---------- 「AI 正在分析」等待动画：方块翻滚 ----------
 *
 * 结构：`::after` 是方块，`::before` 是地面上被压扁的椭圆影子。
 * 两者都是 0.5s 线性无限循环 —— 影子在方块落地时「摊开」、起跳时「收窄」，
 * 这是整个动画唯一让人读出「重量」的地方，别把它去掉。
 *
 * 关键技巧：方块的 100% 停在 `rotate(90deg)`，而不是 `rotate(0)`。
 * 正方形转 90° 与不转看起来**完全一样**，所以循环回到 0% 时没有跳帧；
 * 若把它写成 `rotate(0)`，每一轮都会从 90° 硬切回 0°，因为边框圆角在
 * 中途被改过（3px / 40px），那一下会看到明显的「抖一下」。
 *
 * 尺寸沿用样例的 48px：在 380px 宽的卡片里它只占 12%，视觉重量刚好与
 * 下方的三行文案持平；放大到 64px 会喧宾夺主，缩到 36px 又看不清翻滚。
 */
.analyze-loader {
  position: relative;
  width: 48px;
  height: 48px;
  margin: 0 auto 24px;
}

/* 地面影子：48×5 的椭圆，压在方块下方 12px 处。
   `top: 60px` 会溢出 48px 的容器（60+5=65），这是有意的 —— 影子本来就该
   落在这个方块「跳起来」的那块空地上，所以下面留了 24px 的间距给它。 */
.analyze-loader::before {
  content: '';
  position: absolute;
  top: 60px;
  left: 0;
  width: 48px;
  height: 5px;
  border-radius: 50%;
  /* 与方块同源的主色，透明度压到 22%：太深会像一块污渍，太浅看不出在呼吸 */
  background: color-mix(in srgb, var(--primary) 22%, transparent);
  animation: analyze-shadow 0.5s linear infinite;
}

/* 方块本体。
 * 颜色取「主色 45% + 白卡 55%」混出来的浅蓝（约 #9db9f6）——
 * 样例的 #f08080 是粉红，直接换色号会变成一个饱和的蓝，与「浅蓝色系」不是一回事；
 * 混白才是「浅」，且底色跟着 --card-bg 走，将来换卡片底色也自动协调。 */
.analyze-loader::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  border-radius: 4px;
  background: color-mix(in srgb, var(--primary) 45%, var(--card-bg));
  animation: analyze-jump 0.5s linear infinite;
}

/*
 * 翻滚轨迹。
 * 每 25% 转 22.5°（四段正好 90°），同时先下坠 9px、到 50% 落到 18px 并轻微压扁
 * （`scale(1, .9)` 就是「砸在地上」的那一下），再弹回。
 * 100% 回到 `translateY(0)`，位置与 0% 重合 —— 位置必须回到原点，
 * 否则每轮循环整体会往上漂。
 */
@keyframes analyze-jump {
  15% {
    border-bottom-right-radius: 3px;
  }
  25% {
    transform: translateY(9px) rotate(22.5deg);
  }
  50% {
    transform: translateY(18px) scale(1, 0.9) rotate(45deg);
    border-bottom-right-radius: 40px;
  }
  75% {
    transform: translateY(9px) rotate(67.5deg);
  }
  100% {
    transform: translateY(0) rotate(90deg);
  }
}

@keyframes analyze-shadow {
  0%,
  100% {
    transform: scale(1, 1);
  }
  50% {
    transform: scale(1.2, 1);
  }
}

/*
 * 减动效：把「翻滚」换成「缓慢呼吸」。
 * 不能直接停掉 —— 停掉的方块和一张静态图没有区别，用户会以为界面卡住了。
 * 也不能只保留影子（影子独自缩放反而更奇怪）。
 */
@keyframes analyze-breathe {
  50% {
    opacity: 0.45;
  }
}

@media (prefers-reduced-motion: reduce) {
  .analyze-loader::after {
    animation: analyze-breathe 1.6s ease-in-out infinite;
  }

  .analyze-loader::before {
    animation: none;
  }
}

.analyze-title {
  margin: 0 0 20px;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
}

.analyze-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  text-align: left;
}

.as-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  font-size: 13px;
  color: var(--text-3);
  transition: color var(--dur) var(--ease);
}

.as-mark {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--card-border);
  border-radius: 50%;
  font-size: 10px;
  line-height: 1;
}

.as-item.is-done {
  color: var(--text-2);
}

.as-item.is-done .as-mark {
  border-color: var(--primary);
  background: var(--primary);
  /* 主色实底上的文字一律走 --on-primary（全站唯一来源），不写 #fff */
  color: var(--on-primary);
}

.as-item.is-active {
  color: var(--text-1);
  font-weight: 600;
}

.as-item.is-active .as-mark {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

/* 遮罩进出：只做淡入，避免「唰」地一下糊在屏幕上 */
.analyze-enter-active,
.analyze-leave-active {
  transition: opacity 220ms var(--ease);
}

.analyze-enter-from,
.analyze-leave-to {
  opacity: 0;
}

/* 窄屏：头部操作区两个胶囊要放得下。挤不下时让它们换行而不是被压扁 ——
   「清空会话」与「推荐医生」都得可点，压小到点不准等于没有这个功能。 */
@media (max-width: 560px) {
  .chat-head {
    flex-wrap: wrap;
    row-gap: 8px;
  }
}

/* 消息区恢复 L2 次面（--panel-bg）。它同时是 AI 白气泡的**衬托**：
   白气泡压在白卡上只有 1.15:1，靠自身描边几乎看不出边界，必须有一层略深的面垫在下面。
   —— 这也是这一块不能跟着其他区域一起用 --card-bg 的原因。 */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 22px 24px;
  background: var(--panel-bg);
  /* Firefox 的细滚动条；-webkit- 分支见下 */
  scrollbar-width: thin;
  scrollbar-color: var(--card-border) transparent;
}

/* 细圆角滚动条。浏览器默认的 15px 直角滚动条，在一屏圆润的界面里是最扎眼的一处棱角 */
.chat-body::-webkit-scrollbar {
  width: 6px;
}

.chat-body::-webkit-scrollbar-track {
  background: transparent;
}

.chat-body::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: var(--card-border);
  transition: background var(--dur) var(--ease);
}

.chat-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-3);
}

.chat-empty {
  max-width: 420px;
  margin: 32px auto 0;
  text-align: center;
}

/* 光核：外层柔光呼吸 + 中层虚线环自转 + 内核渐变实心圆。
   三层各自动、周期互质（4.5s / 16s / 静止），叠起来才不会「整块一起闪」的廉价感 ——
   多层错频是纯 CSS 做出「有生命的机械」最省力的办法。 */
.empty-orb {
  position: relative;
  width: 84px;
  height: 84px;
  margin: 0 auto 20px;
}

.orb-halo {
  position: absolute;
  inset: -12px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--primary-soft) 0%, transparent 70%);
  animation: halo-breathe 4.5s var(--ease) infinite;
}

.orb-ring {
  position: absolute;
  inset: 7px;
  border: 1px dashed var(--primary);
  border-radius: 50%;
  opacity: 0.35;
  animation: orb-spin 16s linear infinite;
}

.orb-core {
  position: absolute;
  inset: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--grad-primary);
  color: var(--on-primary);
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 600;
  box-shadow: var(--shadow-primary);
}

@keyframes halo-breathe {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.9;
  }
  50% {
    transform: scale(1.14);
    opacity: 0.5;
  }
}

@keyframes orb-spin {
  to {
    transform: rotate(360deg);
  }
}

.empty-desc {
  margin: 0 0 24px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-2);
}

.empty-tips {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 引导胶囊：悬停**上浮 + 主色描边**，不走「加深底色」——
   底色一深，白卡上就会浮现一块与周围不同深浅的补丁，比不动更难看 */
.tip-chip {
  padding: 10px 16px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-size: 13.5px;
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.tip-chip:hover {
  border-color: var(--primary);
  color: var(--primary);
  transform: translateY(-1px);
  box-shadow: var(--shadow-card);
}

/* 消息行。align-items 由默认的 stretch 改成 flex-start，让 AI 头像与气泡**首行**对齐
   （长回复时头像停在顶部；跟着气泡底部走会显得「在追」）。
   进入动画幅度刻意压得很小（6px / 240ms）：切会话时历史消息会整批重建 DOM、
   一起播一遍，幅度一大就不是「新消息来了」而是「页面在抖」。 */
.msg {
  display: flex;
  align-items: flex-start;
  margin-bottom: 16px;
  animation: msg-in 0.24s var(--ease) both;
}

@keyframes msg-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.msg.user {
  justify-content: flex-end;
}

/* 消息两侧的身份标。底衬与图标由 `RoleAvatar` 提供（36px），这里只管间距。
   间距刻意左右镜像：AI 标在气泡左（margin-right），用户在气泡右（margin-left），
   这样两个头像都是「贴着自己的气泡、朝页面外侧留白」。 */
.msg-face {
  flex: none;
  margin: 3px 10px 0 0;
}

.msg.user .msg-face {
  margin: 3px 0 0 10px;
}

/* 圆角 14 → 18，尾部保留一个收角（4 → 6）指向说话人 ——
   全圆的泡泡会丢掉方向感，留一个收角是最省提示成本的做法 */
.bubble {
  max-width: 74%;
  padding: 12px 16px;
  border-radius: 18px;
  line-height: 1.7;
}

/* 用户气泡：主色**渐变**实心 + 主色同族投影。
   渐变给小块面带来体积感（纯色在深色屏幕上容易显得平）；
   投影用 --shadow-primary 而不是中性灰，看起来是「发亮」而不是「发脏」 */
.msg.user .bubble {
  background: var(--grad-primary);
  color: var(--on-primary);
  border-bottom-right-radius: 6px;
  box-shadow: var(--shadow-primary);
}

/* AI 气泡：白底 + 描边，**不加投影** —— 它已经坐在 L2 次面（--panel-bg）上，
   那一层就是它的衬托；再叠阴影会让整个消息区发闷（见硬约定 #111 ②） */
.msg.ai .bubble {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  color: var(--text-1);
  border-bottom-left-radius: 6px;
}

.msg.ai .bubble.is-crisis {
  background: var(--risk-d-soft);
  border-color: var(--risk-d-border);
  color: var(--risk-d);
}

.bubble-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 等待态（首帧到达前） ----------
   首帧占总耗时中位 83%，这一块承担的就是那 83% 的观感。
   宽度刻意**写死一个下限**：等待文案最长的一句是 15 个字，若让它随内容伸缩，
   从「正在思考…」切到「正在梳理你说的这些…」时气泡会突然变宽 —— 一次等待里
   面块跳来跳去，比一直窄着更让人不安。定住最小宽度，切文案时只换字不换形。 */
.think-wait {
  min-width: 168px;
}

.tw-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

/*
 * 八点旋转脉冲。
 *
 * <h3>它的原理不是「旋转」，是「延迟」</h3>
 * 8 个 `<i>` 各转 45° 均匀铺在圆周上，每个里面的圆点共用同一条 keyframes，
 * 只把 `animation-delay` 依次错开 -0.125 个周期 —— 于是「正在放大的那个点」
 * 看起来在沿圆周跑，形成旋转的错觉。**没有任何元素真的在转**，
 * 所以整段动画只有 scale + opacity 两个属性，可以直接走合成层，几乎不耗性能。
 *
 * <h3>与样例的三处差异</h3>
 * <ol>
 *   <li><b>尺寸 2.8rem → 28px</b>。2.8rem 是 44.8px，那是给独立加载页的；
 *       塞进一条 13px 文字的对话气泡里会把行高顶到 45px，
 *       一行等待文案被撑成一大块。28px 与 13px 文字在同一视觉量级。</li>
 *   <li><b>去掉 `box-shadow: 0 0 20px`</b>。样例是深色背景，光晕负责把点「托」起来；
 *       本气泡是白卡，深色光晕在浅底上只会晕成一团灰雾。
 *       而且缩放后模糊半径也是等比的，在 28px 的尺度上它会盖住一半的点。</li>
 *   <li><b>配色换 `--primary`</b>，不写死 #183153。</li>
 * </ol>
 */
.think-spin {
  /* 周期。改这里，8 个点的延迟会自动跟着算（它们都是这个值的倍数） */
  --think-speed: 0.9s;
  position: relative;
  flex: none;
  width: 28px;
  height: 28px;
}

.think-spin > i {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  /* 旋转基准就是盒子中心：点贴在左边缘，绕中心转 45°×n 即可铺满圆周 */
  transform-origin: 50% 50%;
}

.think-spin > i::before {
  content: '';
  width: 20%;
  height: 20%;
  border-radius: 50%;
  background: var(--primary);
  transform: scale(0);
  opacity: 0.5;
  /* ×1.111 是样例的系数：让「放大」占周期的 5/9，收缩更快、停顿更短，
     看起来是「一个个弹出来」而不是「均匀呼吸」 */
  animation: think-pulse calc(var(--think-speed) * 1.111) ease-in-out infinite;
}

/* 8 个方位。负延迟 = 从这个周期的中途开始播，所以越靠后的点「越领先」，
   连起来就是一个连续绕圈的脉冲。步长必须是 1/8 的倍数，否则接缝处会顿一下。 */
.think-spin > i:nth-child(2) {
  transform: rotate(45deg);
}

.think-spin > i:nth-child(2)::before {
  animation-delay: calc(var(--think-speed) * -0.875);
}

.think-spin > i:nth-child(3) {
  transform: rotate(90deg);
}

.think-spin > i:nth-child(3)::before {
  animation-delay: calc(var(--think-speed) * -0.75);
}

.think-spin > i:nth-child(4) {
  transform: rotate(135deg);
}

.think-spin > i:nth-child(4)::before {
  animation-delay: calc(var(--think-speed) * -0.625);
}

.think-spin > i:nth-child(5) {
  transform: rotate(180deg);
}

.think-spin > i:nth-child(5)::before {
  animation-delay: calc(var(--think-speed) * -0.5);
}

.think-spin > i:nth-child(6) {
  transform: rotate(225deg);
}

.think-spin > i:nth-child(6)::before {
  animation-delay: calc(var(--think-speed) * -0.375);
}

.think-spin > i:nth-child(7) {
  transform: rotate(270deg);
}

.think-spin > i:nth-child(7)::before {
  animation-delay: calc(var(--think-speed) * -0.25);
}

.think-spin > i:nth-child(8) {
  transform: rotate(315deg);
}

.think-spin > i:nth-child(8)::before {
  animation-delay: calc(var(--think-speed) * -0.125);
}

@keyframes think-pulse {
  0%,
  100% {
    transform: scale(0);
    opacity: 0.5;
  }
  50% {
    transform: scale(1);
    opacity: 1;
  }
}

.tw-text {
  font-size: 13px;
  /* ⚠️ 落在白卡上，--text-3 在这一档是达标的（约 4.8:1）；
     它落在 --panel-bg 或页面底上就不达标，所以这块**不能**改挂到别的面上 */
  color: var(--text-3);
}

.tw-slow {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-3);
}

/*
 * 减动效：把「绕圈脉冲」换成「整组同时呼吸」。
 * ⚠️ 不能直接停掉 —— 8 个点的 keyframes 停在 `scale(0)`，停掉之后屏幕上什么都不剩，
 *    等待态会变成一个空框，比不加这段还差。
 * ⚠️ 选择器必须写成 `:nth-child(n)`（特异性与上面 8 条 `:nth-child(2..8)` 相同），
 *    而且要写在它们**之后**，否则那 8 条 `animation-delay` 会以更高的特异性赢过这里的
 *    简写重置 —— 结果就是「换了个 keyframes，但错开的延迟还在」，点和点之间仍在绕着跑，
 *    等于没减动效。
 */
@media (prefers-reduced-motion: reduce) {
  .think-spin > i:nth-child(n)::before {
    animation: think-breathe 1.8s ease-in-out infinite;
    animation-delay: 0s;
  }
}

@keyframes think-breathe {
  0%,
  100% {
    transform: scale(0.45);
    opacity: 0.45;
  }
  50% {
    transform: scale(1);
    opacity: 1;
  }
}

.bubble-basis {
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px dashed var(--card-border);
  font-size: 11.5px;
  color: var(--text-3);
}

/* ---------- 气泡内的知识库引用卡片（Phase 4） ----------
   与 .bubble-basis 用同一种「正文下方的小字区」语言，但用实线边框区分：
   引用是「有出处的证据」，依据是「本轮上下文摘要」，两者性质不同。
   颜色全部取自 tokens.css 的语义变量，不硬编码色值（硬约定 #4）。 */
.bubble-cites {
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px solid var(--card-border);
}

.cites-label {
  display: inline-block;
  margin-bottom: 4px;
  font-size: 11px;
  letter-spacing: 0.04em;
  color: var(--text-3);
}

.cites-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.cite-item {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11.5px;
  line-height: 1.5;
}

/* 标题是卡片里最该被读到的东西，所以给正常字色；
   章节名是补充定位信息，退一级 */
.cite-title {
  color: var(--text-2);
  font-weight: 500;
}

.cite-section {
  color: var(--text-3);
  /* 章节名可能很长（「第 2 节 … / 2.3 第 9 题」），让它自己换行而不是撑破气泡 */
  word-break: break-word;
}

.cite-badge {
  flex: none;
  padding: 0 5px;
  border: 1px solid var(--risk-c-border);
  border-radius: 4px;
  font-size: 10px;
  line-height: 1.5;
  color: var(--risk-c);
  background: var(--risk-c-soft);
}

/* ---------- 气泡内的失败提示 + 重试 ---------- */
.msg.ai .bubble.is-error {
  border-color: var(--risk-b-border);
}

.bubble-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 10px 0 0;
  padding-top: 8px;
  border-top: 1px dashed var(--card-border);
  font-size: 12px;
}

.be-msg {
  color: var(--risk-b);
}

.be-code {
  font-size: 11px;
  color: var(--text-3);
}

.be-retry {
  padding: 2px 10px;
  border: 1px solid var(--risk-b-border);
  border-radius: var(--radius-pill);
  background: var(--risk-b-soft);
  color: var(--risk-b);
  font-size: 12px;
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.be-retry:hover:not(:disabled) {
  border-color: var(--risk-b);
}

.be-retry:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.cursor {
  animation: blink 1s step-start infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* 输入区回退为 L1 白面，同时把 antd 的两个控件「降棱角」——
   默认的 6px 小圆角与整页 18 / 20px 的圆角语言不是一套，放在一起会显出「拼装的痕迹」 */
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  /* 下边距比上边距小 2px：下划线是输入的视觉落点，离卡片边缘太远会显飘 */
  padding: 14px 24px 12px;
  border-top: 1px solid var(--card-border);
  background: var(--card-bg);
}

/*
 * 下划线输入框的外壳。
 *
 * 这里只承担一件事：给那条动画底线提供 `position: relative` 的定位基准。
 * 为什么不让 a-textarea 自己当基准 —— 它的内部结构与自动高度由 antd 计算，
 * 往上加定位会让「测量行高」这件事多一个不确定因素。
 *
 * `flex: 1` + `min-width: 0` 是 flex 布局里的固定搭配：前者让它占满剩余宽度，
 * 后者允许它被压缩（不写 min-width: 0 时，flex 项的默认最小尺寸是内容尺寸，
 * 长占位符会把它顶到溢出，发送按钮被挤出去）。
 */
.composer {
  position: relative;
  flex: 1;
  min-width: 0;
}

/*
 * 输入框本体：透明底 + 一条底线。
 *
 * 与原始样例的差异只有两处，都不是审美取舍：
 *  ① 去掉 `box-shadow: 0 4px 8px rgba(0,0,0,.1)`。样例是深色背景，那层投影负责
 *     让透明输入框「浮」起来；本项目输入区是白卡，同样的投影在浅底上只会显脏
 *     （一个没有填充的矩形投出的影子，看起来像污渍，而不是立体感）。
 *  ② 字号从 1.2rem 收到 15px。1.2rem ≈ 19px 是给独立表单大输入框的，
 *     放进一行高的对话输入条会显得突兀，且与 32px 的发送按钮不成比例。
 */
.chat-input :deep(.ant-input) {
  border: none;
  border-bottom: 2px solid var(--card-border);
  border-radius: 0;
  padding: 6px 8px;
  background-color: transparent;
  color: var(--text-1);
  font-size: 15px;
  caret-color: var(--primary);
  /* 清掉 antd 的聚焦光晕：焦点表达完全交给下面那条渐变线，两套同时出现会打架 */
  box-shadow: none;
}

/*
 * 聚焦：把 antd 的底色底线变透明，让位给叠加在上面的渐变线。
 * ⚠️ 这一步不能省。渐变线是 3px、底线是 2px，两者同时可见会叠成一条 5px 的
 * 双色边（下半 2px 是灰的、上半 3px 是渐变），看起来像描边错位。
 */
.chat-input :deep(.ant-input:focus),
.chat-input :deep(.ant-input-focused) {
  border-bottom-color: transparent;
  background-color: transparent;
  box-shadow: none;
}

/* 占位符落在白卡上，--text-3 是 4.8（达标）；不用 --text-2 是因为
   下划线输入框本身没有描边，占位符太黑会与已输入的正文抢重要性 */
.chat-input :deep(.ant-input::placeholder) {
  color: var(--text-3);
}

/* 禁用（会话已结束 / 无权发送）：底色必须显式声明，否则 antd 会填它自己的灰底，
   在一个透明底的输入框上会变成一块突兀的灰矩形 */
.chat-input :deep(.ant-input[disabled]) {
  background-color: transparent;
  color: var(--text-3);
  border-bottom-color: var(--card-border);
}

/*
 * 聚焦渐变线。
 *
 * 静止时宽度 0（不可见），聚焦时在 0.4s 内铺满。
 * `pointer-events: none` 是必须的：它压在输入框下沿、高 3px，
 * 不关掉命中测试的话，点这一条会点到这条 span 上而不是 textarea ——
 * 表现为「点输入框最下面 3px 没反应」。
 *
 * 缓动取 `cubic-bezier(0.42, 0, 0.58, 1)`（与原始样例的 input-alt 一致）：
 * 前后对称的缓入缓出，铺开的过程是匀的。样例另一条
 * `cubic-bezier(0.6, -0.28, 0.735, 0.045)` 带负控制点、起步会「先退再冲」，
 * 那是给 0.3s 短线的，配 3px 渐变线会显得毛躁。
 */
.composer-line {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 0;
  height: 3px;
  background: var(--grad-underline);
  pointer-events: none;
  transition: width 0.4s cubic-bezier(0.42, 0, 0.58, 1);
}

/*
 * 用 `:focus-within` 而不是样例的 `.input:focus + .input-border`。
 * 相邻兄弟选择器要求「span 紧挨在 input 后面」，而 antd 在不同版本 / 不同属性下
 * 可能给 textarea 套包裹层，那时 `+` 会静默失效（线永远不出现，且没有任何报错）。
 * `:focus-within` 只看「焦点在不在这个壳里」，与中间节点结构无关。
 */
.composer:focus-within .composer-line {
  width: 100%;
}

/* 减动效：不做「铺开」，直接给最终状态。
   ⚠️ 不能改成 display:none —— 那样焦点就完全不可见了。 */
@media (prefers-reduced-motion: reduce) {
  .composer-line {
    transition: none;
  }
}

/* ---------- 发送按钮：圆环 + 箭头 ----------
 *
 * 尺寸按「输入框一行高 ≈ 38px」反推，全部是可以算出来的，不是估的：
 *   按钮 40×40（与输入框基线对齐）
 *   圆环 inset 5px → 环的外框 30×30，border 3px → 内孔 24×24
 *   箭头 16×16 居中在 24 的内孔里
 *   每个 `.send-elem` 占位 = 16 + 12×2 = 40，恰好等于按钮高度
 *     → 一次 `translateY(-40px)` 刚好吃掉一个箭头，不会半格错位
 *
 * `overflow: hidden` 是这套滑动的裁切来源：两个箭头纵向排了 80px，
 * 只露出最上面那个 40px 的窗口。
 */
.send-btn {
  position: relative;
  flex: none;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  background: transparent;
  /* 环与箭头都吃 currentColor，悬停只改这一处，两处同时变 */
  color: var(--primary);
  cursor: pointer;
  overflow: hidden;
  transition: color var(--dur) var(--ease);
}

.send-btn::before,
.send-btn::after {
  content: '';
  position: absolute;
  inset: 5px;
  border-radius: 50%;
  /* 圆环是装饰，不能吃点击 —— 否则点环内侧空白处会落在伪元素上 */
  pointer-events: none;
}

/*
 * 静止环。缓动与时长照抄样例：`0.4s / 0.5s` 两条曲线配 80ms 延迟，
 * 让「旧环缩走」比「新环长出来」稍慢半拍，视觉上才像两个环在交错而不是一起闪。
 */
.send-btn::before {
  border: 3px solid currentColor;
  transition:
    opacity 0.4s cubic-bezier(0.77, 0, 0.175, 1) 80ms,
    transform 0.5s cubic-bezier(0.455, 0.03, 0.515, 0.955) 80ms;
}

/*
 * 悬停环。初始 `scale(1.25)` 而不是样例的 `1.3`：
 * 30×30 × 1.3 = 39，几乎顶到 40 的裁切边，动画起始那一瞬会被切掉 0.5px。
 * 1.25 留出余量，看起来更干净。
 *
 * 颜色取 `--primary-dark`（对白 6.4:1）而不是更浅的主色档：
 * 白底上再浅一档就掉到 3:1 以下，环会「变淡」而不是「变亮」。
 */
.send-btn::after {
  border: 3px solid var(--primary-dark);
  transform: scale(1.25);
  opacity: 0;
  transition:
    opacity 0.4s cubic-bezier(0.165, 0.84, 0.44, 1),
    transform 0.5s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}

/*
 * 用 `:not(:disabled)` 而不是靠规则顺序去压 disabled 的悬停态：
 * 原生 button 即使在 disabled 时**仍然会匹配 `:hover`**，
 * 只靠「把 disabled 规则写在后面」很脆 —— 以后任何人往中间插一段样式就会复现。
 */
.send-btn:not(:disabled):hover::before,
.send-btn:not(:disabled):focus-visible::before {
  opacity: 0;
  transform: scale(0.7);
  transition:
    opacity 0.4s cubic-bezier(0.165, 0.84, 0.44, 1),
    transform 0.5s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}

.send-btn:not(:disabled):hover::after,
.send-btn:not(:disabled):focus-visible::after {
  opacity: 1;
  transform: scale(1);
  transition:
    opacity 0.4s cubic-bezier(0.77, 0, 0.175, 1) 80ms,
    transform 0.5s cubic-bezier(0.455, 0.03, 0.515, 0.955) 80ms;
}

/*
 * 键盘焦点必须自己补。样例把 `:focus` 和 `:hover` 写在同一组选择器里，
 * 意味着「键盘焦点」和「鼠标悬停」长得一模一样 —— 但悬停是**瞬时**的、
 * 焦点是**持续**的，用户按 Tab 过来时无法分辨「我现在停在这个按钮上」。
 * 所以额外给一圈实心 outline。`overflow: hidden` 不会裁掉自身 outline。
 */
.send-btn:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
  border-radius: 50%;
}

/* 两个箭头的纵向轨道 */
.send-box {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  transition: transform 0.4s var(--ease-entry);
}

.send-elem {
  display: block;
  width: 16px;
  height: 16px;
  margin: 12px;
  /*
   * 样例的路径指向 +x（右）。朝上 = 逆时针 90°。
   * ⚠️ CSS 的 `rotate()` 正角是**顺时针**（y 轴朝下），
   * 所以是 `-90deg` 不是 `90deg` —— 写成正值箭头会朝下，正好反了。
   */
  transform: rotate(-90deg);
}

.send-elem svg {
  display: block;
  width: 100%;
  height: 100%;
  fill: currentColor;
}

.send-btn:not(:disabled):hover .send-box,
.send-btn:not(:disabled):focus-visible .send-box {
  transform: translateY(-40px);
}

/* ---------- 发送中 ---------- */
.send-spin {
  position: absolute;
  inset: 11px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--primary) 26%, transparent);
  border-top-color: var(--primary);
  opacity: 0;
  pointer-events: none;
  animation: send-spin 0.72s linear infinite;
}

@keyframes send-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 发送中：箭头让位给转圈，悬停换环的效果也停掉（那会让人以为还能再点一次） */
.send-btn.is-busy .send-box {
  opacity: 0;
  transition: opacity 0.15s linear;
}

.send-btn.is-busy .send-spin {
  opacity: 1;
}

.send-btn.is-busy::before,
.send-btn.is-busy::after {
  opacity: 0;
}

/* ---------- 不可用 ----------
 * 原生 button 的 disabled 不受 hover 影响（上面的规则已用 :not(:disabled) 排除），
 * 这里只需要把颜色压下去、光标改掉。禁用控件不参与 WCAG 对比度要求，
 * 所以用 `--card-border` 这种很浅的灰是合规的，视觉上也确实是「这里按不了」。
 */
.send-btn:disabled {
  color: var(--card-border);
  cursor: not-allowed;
}

/* 减动效：去掉滑移与缩放，只留「悬停换色」这个结果。
   ⚠️ 转圈**不关** —— 它是「正在发送」唯一的反馈，停掉之后用户无法判断
   按钮是否在工作，只能反复点击。这一处属于「表达状态所必需」，不是装饰。 */
@media (prefers-reduced-motion: reduce) {
  .send-btn::before,
  .send-btn::after,
  .send-box {
    transition: none;
  }

  .send-btn:not(:disabled):hover .send-box,
  .send-btn:not(:disabled):focus-visible .send-box {
    transform: none;
  }
}

/* ---------- 右栏 ---------- */
.chat-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  /* 与 .chat-body 用同一套细滚动条语言 */
  scrollbar-width: thin;
  scrollbar-color: var(--card-border) transparent;
}

/* 圆角 --radius-sm(12) → --radius-lg(20)：与 .chat-main 取同一档。
   左窗右栏的圆角不一致，会让人一眼看出是两块拼起来的 */
.side-card {
  padding: 16px 18px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  animation: card-in 0.3s var(--ease) both;
}

.side-card.is-muted {
  background: var(--panel-bg);
}

.side-card.is-crisis {
  background: var(--risk-d-soft);
  border-color: var(--risk-d-border);
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 四块依次浮现。错开的间隔（50ms）小于单块的动画时长（300ms），
   所以看到的是「一层层铺开」而不是「一块块排队跳出来」 */
.chat-side > :nth-child(1) {
  animation-delay: 40ms;
}

.chat-side > :nth-child(2) {
  animation-delay: 90ms;
}

.chat-side > :nth-child(3) {
  animation-delay: 140ms;
}

.chat-side > :nth-child(4) {
  animation-delay: 190ms;
}

.side-title {
  position: relative;
  margin: 0 0 14px;
  padding-left: 11px;
  font-family: var(--font-serif);
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-1);
}

/* 主色竖条：与首页右栏面板标题同一套语言（左侧竖条，不画下划线）。
   把「这里是一个区块」的信息交给图形，标题本身就不必再加重字号 */
.side-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 13px;
  margin-top: -6.5px;
  border-radius: 2px;
  background: var(--primary);
}

/* 危机卡的竖条跟着卡片语义转红：标题文字已经是 --risk-d，
   竖条若还是主色蓝，同一行里会出现两个互相打架的强调色 */
.side-card.is-crisis .side-title::before {
  background: var(--risk-d);
}

.side-card.is-crisis .side-title {
  color: var(--risk-d);
}

.side-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.side-name {
  font-size: 13.5px;
  color: var(--text-2);
}

.side-score {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.side-score-num {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-1);
}

.side-score-unit {
  font-size: 12.5px;
  color: var(--text-3);
}

.side-note {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-3);
}

.side-link {
  padding: 0;
  height: auto;
  font-size: 13px;
}

.hotline {
  display: block;
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--risk-d);
}

/* 推荐医生 */
.doc + .doc {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--card-border);
}

.doc-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 推荐医生头像：尺寸与配色走 `RoleAvatar`（40px，医生图标） */
.doc-avatar {
  flex-shrink: 0;
}

.doc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

.doc-dept {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}

.doc-reason {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
}

.side-footnote {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--text-3);
}

.side-footnote strong {
  color: var(--text-2);
}

/* 尊重系统「减少动态效果」设置。
   本页新增了四类持续/入场动画：涟漪（.ai-mark）、呼吸（.live-dot / .orb-halo）、
   自转（.orb-ring）、错峰进场（.msg / .side-card）。
   前庭功能敏感的用户即便幅度很小也可能不适，所以整体关掉。
   这是本页唯一用 !important 的地方 —— 元素自身的 animation 简写必须被整条覆盖，
   「把时长设为 0」仍会保留 animation-fill-mode 的首帧位移，等于没关掉。 */
@media (prefers-reduced-motion: reduce) {
  .ai-mark::after,
  .live-dot,
  .orb-halo,
  .orb-ring,
  .msg,
  .side-card {
    animation: none !important;
  }
}

@media (max-width: 1024px) {
  .chat-page {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  .chat-main {
    height: 70vh;
  }
}
</style>
