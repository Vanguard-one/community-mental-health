<template>
  <div class="chat-page">
    <!--
      ==================== 背景：马赛克透镜 ====================

      与登录页**同一个组件**（components/MosaicLens.vue，双通道 WebGL2），
      只是换了输入色和参数 —— 配色另走 `--lens-chat-*`（tokens.css），
      不覆盖登录页那三个 `--lens-*`。

      参数取 Originkit 预设：speed 47（≈ 原速）、size 153（构图缩放）、
      tile 6px（基础格子）、hover 82 / reach 187（指针透镜的强度与半径）。

      `dpr-cap="1.5"` 是本页特有的节流：本页压着三块 backdrop-filter 玻璃、
      一整圈大半径阴影、还有会滚动的正文，pass 2 的填充率必须让一让。
      马赛克本来就是碎格，少 25% 分辨率看不出来。

      ⚠️ 三条不能动的约束：
      1. 这一层必须 `pointer-events: none` —— 组件内部监听的是 window 的
         pointermove，不依赖 canvas 命中，所以挡不挡点击与「透镜跟不跟手」无关；
      2. `.chat-page` 从此**不再透出 body 的 --app-bg**（canvas 是不透明的），
         要改底色就改 `--lens-chat-bg`，不要再往 `.chat-page` 上叠背景
         （叠了就变成两块颜色，正是前几轮反复撤掉的那类问题）；
      3. 白纱（`.chat-lens-veil`）只压浓度、不改色 —— 它是整层均一的
         `color-mix`，不会产生任何接缝。
    -->
    <div class="chat-lens" aria-hidden="true">
      <MosaicLens :speed="47" :size="153" :tile-size="6" :hover="82" :reach="187" :dpr-cap="1.5" />
      <div class="chat-lens-veil"></div>
    </div>

    <!-- ============ 左：对话 ============ -->
    <section class="chat-main">
      <!--
        顶栏已整块移除（2026-10-22 第三轮）。

        原先这里是一条 16×28 内边距的白色横条：左边「AI 智能问诊 + 状态副标题」，
        右边「清空会话 / 推荐医生」。问题不在内容而在**它的存在本身** ——
        对话区底色改成页面渐变之后，一条顶到两边的白条会在视口顶部切出一道
        与整页版式无关的横线；而标题说的「AI 智能问诊」，用户在导航栏点进来时
        就已经知道了，属于「同一句话说两遍」。

        两个动作按钮移到右栏顶部（见下方 `.side-ops`），状态文案随顶栏一并删除：
        「会话未建立 / 已结束」这类信息，界面已经用别的信号表达了
        （空态文案、`.closed-bar`、发送按钮禁用），不需要再占一行字。

        注意：这不是「把顶栏做成透明」—— 那样标题与按钮仍会在渐变上浮着，
        与目标版式（左区只剩对话内容）依然不同。
      -->

      <!--
        窄屏（≤1100px）右栏收成抽屉，这个按钮是唯一的唤出入口。
        宽屏由 CSS 完全隐藏 —— 那时右栏常驻，没有「打开」这回事。
        它不再属于某个顶栏，所以走 fixed 悬浮在右上角（见样式）。
      -->
      <button
        type="button"
        class="btn btn-ghost side-toggle"
        title="打开信息面板"
        aria-label="打开信息面板"
        @click="sideOpen = !sideOpen"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <path d="M15 4v16" />
        </svg>
      </button>

      <!--
        引导问诊阶段指示条已移除。
        它展示的是后端阶段机（meta 事件每轮下发当前阶段），纯 CSS 画点与连线。
        移除原因：三个阶段的推进对居民来说是内部实现细节 —— 他关心的是
        「AI 有没有听懂我在说什么」，而不是「现在进行到第几步」。
        后端阶段机本身不受影响（仍驱动追问策略与提示词的 L5 分层），
        只是不再占用界面顶部这一行。需要恢复时把这段与 .stage-bar 的样式一起拿回来即可。
      -->

      <!--
        顶部导航（Radio 组，三个按钮）。

        ⚠️ **现在没有任何功能**：点击只切换本地高亮，不路由、不发请求。
           接功能时只需把三处 `@click` 换成 `router.push(...)`，其余不用动。

        位置：`position: absolute` 浮在 `.chat-main` 顶部，不占 flex 流。
        原因和第二十轮把输入区改成浮层是同一个 —— 这一条如果参与排版，
        消息区就得在它下面再开一块空白，而 `.chat-body` 顶部那 110px 已经
        是给它留的：110 / 2 - 66 / 2 = 22，菜单正好落在这条带子的正中。
        滚动时消息从它**下面**穿过去（胶囊自带白底 + 阴影，压得住）。

        语义：`role="group"` + `aria-pressed` 而不是 `role="radiogroup"`。
        真正的 radiogroup 要求 roving tabindex 与方向键操作，一个还没接线的
        占位控件不该先背上那套键盘约定；等它真的能切换视图时再换。
      -->
      <nav class="chat-nav" aria-label="AI 问诊导航">
        <div class="nav-menu">
          <button
            type="button"
            class="nav-link"
            :class="{ 'is-active': activeNav === 'chat' }"
            :aria-pressed="activeNav === 'chat'"
            aria-label="对话"
            @click="activeNav = 'chat'"
          >
            <span class="nav-icon" aria-hidden="true">
              <!--
                图标换成 AI 星芒（`components/IconAi.vue`，与左侧栏「AI 问诊」同一份几何）。
                原来这里是气泡 SVG，与「我的消息」「医生咨询」在 22px 下太接近，
                而这一枚代表的是「AI」，不是「聊天」。

                尺寸不用管：`.nav-icon` 是 22×22，`.nav-icon svg { width/height: 100% }`
                会盖掉组件里那个 `width="1em"` 的兜底值 —— 子组件根节点同时带父级
                的 scope 标记，所以这条 scoped 规则能命中。
              -->
              <IconAi />
            </span>
            <span class="nav-title">对话</span>
          </button>

          <button
            type="button"
            class="nav-link"
            :class="{ 'is-active': activeNav === 'scale' }"
            :aria-pressed="activeNav === 'scale'"
            aria-label="测评"
            @click="activeNav = 'scale'"
          >
            <span class="nav-icon" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M8 6h13M8 12h13M8 18h13" />
                <path d="M3 6h.01M3 12h.01M3 18h.01" />
              </svg>
            </span>
            <span class="nav-title">测评</span>
          </button>

          <button
            type="button"
            class="nav-link"
            :class="{ 'is-active': activeNav === 'doctor' }"
            :aria-pressed="activeNav === 'doctor'"
            aria-label="医生"
            @click="activeNav = 'doctor'"
          >
            <span class="nav-icon" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            </span>
            <span class="nav-title">医生</span>
          </button>
        </div>
      </nav>

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

            图形本身又换过一版：原来是「柔光 + 细环 + 蓝色实心圆（AI 两个字）」
            的三层光核，现在是**点阵粒子球**（components/OrbNoise.vue）。
            换的理由是那三个字其实没有信息量 —— 顶栏写着 AI，气泡头像也是 AI，
            再立一块写着「AI」的牌子是第三次重复；而一颗自转的粒子球不解释任何事，
            只表达「有东西在」，注意力交给下面那句「这里不评判、不贴标签」。

            底下那层 halo 留着：粒子是半透明的小点，直接压在马赛克背景上会
            「散」；一层同色系的柔光把它们兜在一起，球才有体积感。
          -->
          <div class="empty-orb" aria-hidden="true">
            <span class="orb-halo"></span>
            <OrbNoise
              class="orb-field"
              :density="210"
              :dot-size="105"
              :speed="32"
              :spin-turns="1"
              :dpr-cap="2"
            />
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

      <!--
        输入区是**浮层**（2026-10-22 第十八轮）：`position: absolute` 贴在 `.chat-main`
        底部，消息区在它下面继续滚动并被渐隐隐去（见 `.chat-body` 的 mask）。
        之前它是 flex 的一行，消息滚到边界就被硬切一刀 —— 那正是用户说的「断层」。

        `ref` 只为量它的高度：消息区的 `padding-bottom` 与渐隐起点都要跟着它走
        （多行输入、建议行出现、窄屏重排都会让高度变），见 syncFootHeight()。
      -->
      <footer ref="footRef" class="chat-input">
        <!-- 已结束的会话（从历史里打开的）只能看不能发：后端对 CLOSED 会话返回 400，
             不提前拦住的话，用户会看到一条「看起来像故障」的报错 -->
        <div v-if="chat.sessionStatus === 'CLOSED'" class="closed-bar">
          <span class="closed-text">该会话已结束，只能查看历史消息。</span>
          <button type="button" class="btn btn-primary" @click="onNewSession">开启新会话</button>
        </div>
        <template v-else>
          <!--
            输入行：负责「与输入胶囊同宽、居中」这一件事。

            宽度是 `--chat-col + --composer-bleed × 2`（比消息列左右各宽 5px，见
            `.chat-page` 的变量注释）。外扩不是为了好看：输入条是浮在消息之上的一层，
            边缘与正文严格齐平的话，看上去仍像「正文的一部分」，分不出层级。

            ⚠️ 发送按钮在这一层**之内**（2026-10-22 第五轮），不再并排在外侧：
            并排时它是「胶囊旁边的一颗圆钮」，按钮落在白胶囊之外；
            放进来才是一体化的输入条 —— 按钮本身就是胶囊的右端。
          -->
          <!--
            追问建议：贴在输入胶囊**右上方**，2~3 条，右对齐。
            位置选在输入框上方而不是消息区里，是因为它服务的是「下一句说什么」，
            视线本该落在输入框附近；右对齐则与发送键同侧，读起来是「往右下走」的动作。

            只做「填入」不做「发送」—— 详见脚本里 applyQuickPrompt 的注释。

            内容来自 `quickPrompts`：AI 回答完以后由后端 `followups` 事件下发，
            没有就用静态三条兜底。

            ⚠️ 这里**刻意不做换内容的过渡动画**（2026-10-22 第三版）。前两版的
            `mode="out-in"` + `translateY` 会让人看到一层抹开的「虚影」：
            chip 上有 `backdrop-filter: blur(8px)`，对带 backdrop 模糊的元素做
            transform，Chrome 会拿变换前的画面去采背景，合成出来就是重影。
            现在改成「整行先藏、拿到最终内容再显示」（见 `promptsReady`），
            一回合最多出现一次，没有可动画的中间态。
          -->
          <div
            v-if="showQuickPrompts"
            class="quick-row"
            :class="{ 'quick-row--idle': !promptsReady }"
          >
            <button
              v-for="(q, qi) in quickPrompts"
              :key="qi"
              type="button"
              class="quick-chip"
              :title="promptsReady ? `填入输入框：${q}` : undefined"
              @click="applyQuickPrompt(q)"
            >
              {{ q }}
            </button>
          </div>

          <div class="input-row">
          <!--
            输入胶囊（样式见文件末 `.composer`）。
            它承担四件事：白底、26px 圆角、左右内边距、以及把「输入 / 提示 / 发送」
            三个子项排成一行。聚焦态由 `.composer:focus-within` 统一表达
            （整圈描边 + 柔光）—— 胶囊没有底线可铺，不用渐变线那一套。

            `ref` 只为量高度给右下角那颗粒子球用（见 syncComposerOrb）。
          -->
          <div ref="composerBoxRef" class="composer">
            <a-textarea
              ref="composerRef"
              v-model:value="input"
              :auto-size="{ minRows: 1, maxRows: 4 }"
              placeholder="说说你的感受…"
              :disabled="!chat.canSend"
              @pressEnter="onSend"
            />
            <!--
              快捷键提示从 placeholder 里拆出来，放在胶囊内右侧。
              为什么：胶囊形输入框的占位符区只有左侧一小段，
              把「（回车发送，Shift+回车换行）」塞进去会让 placeholder 长到
              撑满整个输入框，看起来像正文而不是提示；而且这段话是**规则**，
              与「说说你的感受…」这个引导语不是同一类信息。
              窄屏由 CSS 隐藏（375px 上两段文字会挤在一起）。
            -->
            <span class="composer-hint" aria-hidden="true">回车发送，Shift+回车换行</span>
            <!--
              发送按钮（Uiverse / xopc333 样例）。与样例的**四处**差异，都不是单纯换色：

              ① **箭头朝上**：样例的路径指向 +x，配 `rotate(180deg)` 得到「朝左」。
                 朝上要 `rotate(-90deg)`（CSS 正角是顺时针，负角才是逆时针）。

              ② **滑动方向改纵向**：样例是横向 `translateX(-56px)` —— 朝左的箭头左右换位，
                 逻辑自洽；但箭头朝上之后若还横向滑动，读起来是「横向平移」而不是「往上送出」。
                 改成 `flex-direction: column` + `translateY(-44px)`，第二个箭头从下方顶上来。

              ③ **两枚圆环的配色按本项目的蓝色分层**：样例是「浅灰环 → 浅青环」，
                 那是给深色底的；白胶囊上浅灰环只有 1.1:1，等于没有。
                 现在静息环 = 主色 55%（看得见、但不抢箭头），悬停环 = 主色实色。
                 注意**方向与样例一致**：细的让位、实的收进来。

              ④ **补 loading / disabled**：原来是 a-button，这两个状态由 antd 提供；
                 换成原生 button 之后必须自己做，否则发送期间按钮看不出在工作、
                 也能被重复点击。见 `.send-spin` 与 `.send-btn:disabled`。

              ⚠️ 环的缩放上限是 1.12 而不是样例的 1.3 —— 按钮只有 44px（样例 56px），
                 1.3 倍会让环被 `overflow: hidden` 切成四段弧。推导见样式里那段注释。
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
          </div>
          <!--
            输入框**左侧**的粒子球（只在**已有消息**时出现）。

            为什么放在这里：空态时那颗大球是「这里什么都没有」的视觉锚点；
            一旦开始对话，它就该退场 —— 但直接删掉又会让页面左下角重新变成
            一整片空的渐变。于是缩成一颗小球，落到输入胶囊左侧，成为「AI 在旁边」
            的在场提示：不占版式空间、不解释任何事，只是让那一侧不至于空。

            三个约束：
              · `v-if="chat.hasMessages"` —— 空态已经有居中的大球，不能同时出现两颗；
              · `v-if="orbFits"` —— 尺寸/位置是「胶囊左侧 + gap」，而胶囊是居中的，
                走廊宽度 = 胶囊右/左侧的余量，窗口一窄就没了。硬放会被
                `.chat-main` 的 `overflow: hidden` 切掉一条边。
                放不放得下由 syncComposerOrb() 用真实几何量出来，
                并且**优先缩小球而不是直接隐藏**（见那里的注释）；
              · 尺寸跟着胶囊走（`--orb-size`，JS 写），所以「高度与输入框持平」
                在多行输入时也成立，上限 88px 防止输到 4 行时长出一颗巨球。
          -->
          <OrbNoise
            v-if="chat.hasMessages && orbFits"
            class="composer-orb"
            aria-hidden="true"
            :fill="0.48"
            :density="120"
            :dot-size="100"
            :speed="24"
            :spin-turns="1"
            :dpr-cap="2"
          />
          </div>
        </template>
      </footer>
    </section>

    <!-- ============ 右：上下文 ============ -->
    <!--
      `is-open` 只在 ≤1100px 有意义：那时右栏变成从右侧滑出的抽屉（见文件末媒体查询）。
      宽屏下这层 class 不产生任何样式，右栏就是正常的第三列。
    -->
    <aside class="chat-side" :class="{ 'is-open': sideOpen }">
      <!-- 抽屉态专用关闭键。宽屏隐藏 —— 那时右栏常驻，没有「关闭」这回事。
           它绝对定位在栏右上角，不占 flex 流，所以不会把下面的按钮组顶下去。 -->
      <button
        type="button"
        class="btn btn-ghost side-close"
        aria-label="关闭信息面板"
        @click="sideOpen = false"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M18 6 6 18" />
          <path d="M6 6l12 12" />
        </svg>
      </button>

      <!--
        动作组：清空会话 + 推荐医生。

        位置从顶栏搬到右栏顶部 —— 目标版式里左区只有对话本身，
        所有「对这次会话的操作」都收进右栏，与上下文卡片排在同一列。

        布局：两枚**等宽**胶囊各占一半，整行铺满栏的左右内边距 ——
        与下方卡片的左右边缘严格对齐（2026-10-22 第二十七轮，用户指定）。
        上一版是「按内容宽、右对齐」，理由是「重心贴住推荐医生、不会读成第三张卡片」；
        实际看下来那一版的上沿参差、左右都缩进，悬在卡片上方像被挤到一边的附注。
        铺满之后它更像一排「栏级操作」，与卡片同宽同轴，读起来是这一栏的头部。

        用原生 button 而不是 a-button —— vendor-antd 预算只剩约 9 kB（硬约定 #9）。
      -->
      <div class="side-ops">
        <button
          type="button"
          class="btn btn-ghost"
          :disabled="!canClear"
          @click="openClearDialog"
        >
          <span>清空会话</span>
        </button>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="chat.sessionId === null"
          @click="onClickRecommend"
        >
          <span>推荐医生</span>
        </button>
      </div>

      <!--
        关联测评 / 最近一次测评。

        2026-10-22 第二十八轮：内容从「本次会话关联的那一条」改成**居民最近一次测评**
        （表名 + 分数），这样没从结果页发起对话的人也能在右栏看到自己的近况。
        两条数据不能混用，所以这里刻意分成两个 computed：
          · `linkedAssessment` —— 本次会话关联的那条，只服务「AI 的依据小字」与危机判定，
            一旦被「最近一次」顶掉，依据就会指向一次跟这段对话无关的测评；
          · `latestAssessment` —— 历史里最新的那条，只用于这张卡。
        卡片脚注也据此分叉：确实是本次关联的那条才写「已作为对话背景」，
        否则写完成日期（对一条旧记录说「已作为对话背景」是假话）。
      -->
      <div v-if="sideAssessment" class="side-card">
        <h3 class="side-title">最近一次测评</h3>
        <div class="side-row">
          <span class="side-name">{{ sideScale?.name ?? '心理自评' }}</span>
          <RiskLevelTag :level="sideAssessment.riskLevel" />
        </div>
        <div class="side-score">
          <span class="side-score-num mono">{{ sideAssessment.totalScore }}</span>
          <span class="side-score-unit">{{ sideScale ? `/ ${sideScale.maxScore} 分` : '分' }}</span>
        </div>
        <p class="side-note">{{ sideNote }}</p>
      </div>
      <!-- 从未测评过：与改动前完全一致（同样的标题、文案与「去测评」入口） -->
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
import IconAi from '@/components/IconAi.vue'
import MosaicLens from '@/components/MosaicLens.vue'
import OrbNoise from '@/components/OrbNoise.vue'
import RiskLevelTag from '@/components/RiskLevelTag.vue'
import RoleAvatar from '@/components/RoleAvatar.vue'
import { getRecommendDoctors } from '@/api/recommend'
import { useAssessmentStore } from '@/stores/assessment'
import { useChatStore } from '@/stores/chat'
import { RISK_LEVEL_DESC } from '@/types'
import type { Assessment, RecommendResult } from '@/types'

const PRESET_PROMPTS = [
  '最近总是睡不好，该怎么办？',
  '我该怎么跟医生描述我的情况？',
  '有什么办法能缓解紧张情绪？',
]

/**
 * 追问建议的**静态兜底**（3 条）。
 *
 * 正常情况下这里的内容轮不到上场 —— AI 每轮回答完会随 `followups` 事件下发三条
 * 与刚才那段回答相关的建议（见 `FollowupSuggester`）。但以下几类轮次**不产生**该事件，
 * 此时必须还有话说，否则输入框上方会突然空出一块：
 *
 * - 危机轮次（服务端把回复替换成固定文案，不再引导用户继续追问）；
 * - 追问生成失败 / 超时（后端 4s 就放弃），或生成的条目全被安全词表拦掉；
 * - 历史会话回看（建议不落库，`loadSession` 会把它清空）。
 *
 * 措辞刻意用「能…吗 / 需要…吗 / 我该怎么…」这类**可直接发送的完整问句**，
 * 而不是「睡眠」「焦虑」这种标签词 —— 填进输入框后用户可以原样回车，
 * 也可以在此基础上改几个字，两种用法都成立。
 */
const FALLBACK_FOLLOWUPS = [
  '能给我一个今晚就能试的具体做法吗？',
  '这种情况需要去看医生吗？',
  '我该怎么跟家人开口说这件事？',
]

/**
 * 当前该显示的三条建议：**动态优先，静态兜底**。
 *
 * 不做「语义匹配」也不做占位补齐：后端下发几条就显示几条（可能只有 2 条，
 * 也可能因为两条太像被去重掉一条）。补空白槽位反而会让用户看见「AI 没想出来」。
 *
 * 长度也直接信任后端（已截到 3 条）；这里再 `slice` 一次只是防御 —— 视图不承担
 * 「多发了怎么办」的兜底责任，但多一行成本换来的是布局不会因为 4 条而换行错位。
 */
const quickPrompts = computed<string[]>(() => {
  const dynamic = chat.followups
  if (dynamic.length) return dynamic.slice(0, 3)
  return FALLBACK_FOLLOWUPS
})

/**
 * 建议行「有内容可说」的条件：`hasMessages` 保证不与空态那三条开场引导重复；
 * `sessionStatus !== 'CLOSED'` 保证会话结束后不再劝用户提问（那时候点了也发不出去）。
 *
 * 注意它只管**行在不在**，不管**内容亮不亮** —— 后者是 `promptsReady`
 * （行保持占位，但整行 `visibility: hidden`，所以输入框不会上下跳）。
 */
const showQuickPrompts = computed(
  () => chat.hasMessages && chat.sessionStatus !== 'CLOSED',
)

/**
 * 什么时候可以**看见**这三条。
 *
 * 一个回合分三段，取值各不相同：
 *
 * | 阶段 | sending | followupsPending | 结果 |
 * |---|---|---|---|
 * | 正文流式中（"AI 正在思考"） | true | true | 藏 |
 * | `done` 之后、流关闭前（正在生成建议，1~2s） | false | true | **仍然藏** |
 * | 流关闭 | false | false | 显示最终内容 |
 *
 * 第二行是这次修复的核心。`sending` 在 `done` 就变 false（必须如此，否则输入框被多锁一两秒），
 * 但那时 `followups` 还是空的 —— 若按 `sending` 判断，会先亮出静态兜底三条、
 * 一两秒后被真建议替换，用户看到的就是「提示自己变回固定消息了」。
 * 所以判据必须用 `followupsPending`（挂到整条流关闭），而不是 `sending`。
 */
const promptsReady = computed(() => !chat.sending && !chat.followupsPending)

/**
 * 点击追问建议：**只填入输入框，不自动发送**。
 *
 * 为什么不做「点击即发」：这类建议落在输入框紧邻位置，误触概率远高于页面中间的按钮，
 * 而一条消息一旦发出就收不回来（后端会把它写进会话历史、也会进入 AI 的上下文）。
 * 填进去之后用户仍可修改措辞、补充细节，回车或不回车由他决定 ——
 * 这既符合「提升效率」，又不越过「用户没打算发就不发」这条线。
 *
 * 覆盖 vs 追加：这里**直接覆盖**。理由是它是一个显式动作（用户点的是具体某句话），
 * 而「追加」会造出「我最近很焦虑 + 能给我一个今晚就能试的具体做法吗？」
 * 这种没人会这么说的话；如果用户已经写了草稿，他会自己判断要不要点。
 */
function applyQuickPrompt(text: string) {
  if (chat.sending || chat.sessionStatus === 'CLOSED') return
  input.value = text
  // 填入后把焦点交给 textarea，光标落到末尾 —— 用户可以直接改，也可以直接回车
  nextTick(() => composerRef.value?.focus())
}

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
/**
 * textarea 实例引用，只为一件事：点击追问建议后把焦点交回输入框。
 *
 * 类型写成最小接口而不是 `InstanceType<typeof ATexarea>` —— 那样要把 antd 的
 * 组件类型 import 进来（`TextAreaRef`），而本页其余地方都没有直接依赖 antd 类型，
 * 为一次 `focus()` 引入它不值得。结构类型只要满足「有 focus 方法」即可。
 */
const composerRef = ref<{ focus: (options?: FocusOptions) => void }>()
/**
 * 输入胶囊的 DOM 引用，只为给右下角那颗粒子球量尺寸与余量（见 syncComposerOrb）。
 * 与上面那个分开：一个要的是**组件实例**（focus），一个要的是**盒子**（offsetHeight）。
 */
const composerBoxRef = ref<HTMLElement>()
/**
 * 粒子球此刻放不放得下。
 *
 * <p>初始 `false` 是刻意的：宁可第一帧不出现，也不要先画到边界外再收回来。
 * `ResizeObserver` 在 observe 时会立刻回调一次（布局已完成、绘制尚未发生），
 * 所以正常情况下它不会让球「晚一拍」出现。
 */
const orbFits = ref(false)
const bodyRef = ref<HTMLElement>()
/**
 * 输入浮层的 DOM 引用，**只用来量高度**。
 *
 * <p>消息区要让出这块高度（`padding-bottom`）并按同一数值收渐隐（`mask`），
 * 而浮层高度不是常量：textarea 从 1 行长到 4 行、追问建议整行出现/隐藏、
 * ≤560px 的窄屏重排都会改它。写成固定值一定会在某个状态下露出「最后一条消息
 * 被输入框盖住」或「底部凭空多出一大块空白」。
 */
const footRef = ref<HTMLElement>()
/** 测量器。存在引用里是为了卸载时能断开 —— 不断开等于把 DOM 节点钉在内存里 */
let footObserver: ResizeObserver | undefined
const doctors = ref<RecommendResult[]>([])
const doctorLoading = ref(false)

/* ---- 推荐流程（收到 recommend 事件后播放的分析动画） ---- */
const analyzing = ref(false)
const analyzeStep = ref(0)

/**
 * 右栏抽屉开关（仅 ≤1100px 生效）。
 *
 * <p>宽屏时右栏是常驻的第三列，这个 ref 对布局没有任何影响 —— 没有写成
 * 「根据窗口宽度决定是否渲染右栏」，是因为那样要挂 resize 监听并维护
 * 一个与 CSS 断点重复的宽度常量（两处阈值一旦不同步就会漏渲染）。
 * 交给媒体查询做，JS 只负责「用户点了没」。
 */
const sideOpen = ref(false)

/**
 * 顶部导航当前高亮项（占位）。
 *
 * <p>`'chat' | 'scale' | 'doctor'` 三选一，**只影响样式，不做任何事** ——
 * 不路由、不发请求。等接线时把它换成 `activeNav` + `watch` 跳转，或者干脆
 * 只用路由的 `route.name` 推导（那样刷新后高亮才对得上）。
 *
 * <p>默认 `'chat'`：这一页本来就是对话页，进来看见「对话」亮着是符合预期的。
 */
const activeNav = ref<'chat' | 'scale' | 'doctor'>('chat')

const topDoctors = computed(() => doctors.value.slice(0, 2))

/**
 * ⚠️ `headNote` 已随顶栏一并删除（2026-10-22 第三轮）。
 *
 * 它原先给顶栏副标题提供「正在建立会话… / 会话未建立 / 已结束 / 内容仅用于本次咨询」
 * 四种文案。顶栏移除后这些信息仍有出口，而且更贴身：
 *   · 「正在建立会话」→ 空态由 `!chat.hasMessages && !chat.opening` 控制，
 *     建会话期间本来就不显示空态；
 *   · 「已结束」→ 输入区的 `.closed-bar` 明确写着「该会话已结束」并给出口；
 *   · 「会话未建立」→ 发送按钮 disabled；
 *   · 「内容仅用于本次咨询」→ 右栏页脚那句边界声明。
 * 保留一个没人读的 computed 只会让 `noUnusedLocals` 报错，故直接删掉。
 */

/** 本次会话关联的测评记录（用于右栏上下文卡与「依据」小字） */
const linkedAssessmentId = computed(() =>
  route.query.assessmentId ? Number(route.query.assessmentId) : chat.linkedAssessmentId,
)
const linkedAssessment = computed(() => store.findHistory(linkedAssessmentId.value))
const linkedScale = computed(() => {
  const scaleId = linkedAssessment.value?.scaleId
  return scaleId ? store.scales.find((s) => s.id === scaleId) : undefined
})

/**
 * 居民**最近一次**测评（右栏「最近一次测评」卡的数据源）。
 *
 * <h3>为什么不直接用 `history[0]`</h3>
 * 接口返回的顺序不是契约的一部分 —— 换个后端实现、加个分页、甚至只改个排序，
 * 「最近一次」就会静默变成某一次。这里按 `createdAt` 取最大，时间戳相同（同一秒
 * 提交两次）再按 id 兜底。数据量是「一个居民做过的所有测评」，几十条以内，
 * 一次线性扫描比排序更省事，也不会每次访问都新建数组。
 *
 * <h3>为什么与 `linkedAssessment` 并存而不是替换</h3>
 * 那个服务于「AI 的依据」与危机判定，必须是**本次会话真正关联**的那条；
 * 一旦被「最近一次」顶掉，首条回复下方的依据就会指向一次跟这段对话无关的测评。
 */
const latestAssessment = computed<Assessment | undefined>(() => {
  let best: Assessment | undefined
  for (const a of store.history) {
    if (!best) {
      best = a
      continue
    }
    const ta = Date.parse(a.createdAt) || 0
    const tb = Date.parse(best.createdAt) || 0
    if (ta > tb || (ta === tb && a.id > best.id)) best = a
  }
  return best
})

/**
 * 卡片实际展示的那条：优先「最近一次」，历史还没回来（或请求失败）时回落到
 * 本次会话关联的那条 —— 一次网络失败不该让卡片突然变回「未关联测评」。
 */
const sideAssessment = computed(() => latestAssessment.value ?? linkedAssessment.value)
const sideScale = computed(() => {
  const scaleId = sideAssessment.value?.scaleId
  return scaleId ? store.scales.find((s) => s.id === scaleId) : undefined
})

/** 卡上这条是不是本次会话关联的那条 —— 决定脚注写「已作为对话背景」还是完成日期 */
const sideIsLinked = computed(
  () => !!sideAssessment.value && sideAssessment.value.id === linkedAssessment.value?.id,
)

/**
 * `2026-10-22T10:30:00` → `10.22`。
 *
 * 与 `views/history/AssessmentHistory.vue` 的口径保持一致（那边叫 `monthDay`）：
 * 300px 的栏宽里塞不下完整时间，而「最近一次」只需要看出新鲜度。
 * 脏数据（空串 / `0000-00-00`）返回空串，由调用方决定是否省略这段 —— 不要让
 * `NaN.NaN` 上屏。
 */
function monthDay(s: string): string {
  if (!s || s.length < 10) return ''
  const mo = Number(s.slice(5, 7))
  const day = Number(s.slice(8, 10))
  if (!mo || !day) return ''
  return `${mo}.${day}`
}

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
 *
 * 2026-10-22 起改为按**卡片展示的那条**（`sideAssessment`）算，不再是「会话关联的那条」。
 */
const sideStatusText = computed(() => {
  const a = sideAssessment.value
  if (!a) return ''
  return a.crisisFlag === 1 ? '危机项阳性，需人工介入' : RISK_LEVEL_DESC[a.riskLevel]
})

/** 卡片脚注：是本次关联的就写「已作为对话背景」，否则写完成日期 */
const sideNote = computed(() => {
  const a = sideAssessment.value
  if (!a) return ''
  const when = sideIsLinked.value ? '已作为对话背景' : monthDay(a.createdAt) ? `完成于 ${monthDay(a.createdAt)}` : ''
  return when ? `${sideStatusText.value} · ${when}` : sideStatusText.value
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
 * 把输入浮层的实际高度写回 CSS 变量 `--chat-foot`。
 *
 * <h3>为什么必须量，不能写死</h3>
 * 输入区是浮层（`position: absolute`），消息区在它下面继续滚动。消息区的
 * `padding-bottom` 必须是「当前真实值」，否则最后一条消息会被输入框盖住。
 * 高度会变：textarea 从 1 行到 4 行、追问建议整行显隐、≤560px 重排、
 * 会话结束时换成 `.closed-bar`。任何一个状态用固定值都会露馅。
 *
 * <h3>高度变化后为什么要再滚一次</h3>
 * `padding-bottom` 变大 = 内容底部下移，原本贴着底部的视图会「差一点点」——
 * 表现是输入框张开时，最后一行字被推到了浮层后面。
 * 只在用户本来就贴着底部时才补滚（`stickToBottom`）：他在往上翻旧记录时被拽回底部更糟。
 *
 * <h3>顺带同步右下角的粒子球</h3>
 * 球的尺寸与「放不放得下」都依赖胶囊的当前几何，而这里已经是唯一的
 * footer 尺寸变化入口（ResizeObserver 同时覆盖窗口缩放与 textarea 长高），
 * 所以放在这里一起算，不另挂监听。
 */
function syncFootHeight() {
  const el = footRef.value
  if (!el || !bodyRef.value) return
  bodyRef.value.style.setProperty('--chat-foot', `${el.offsetHeight}px`)
  syncComposerOrb()
  if (stickToBottom) scheduleScrollToBottom()
}

/* ---------- 输入框左侧的粒子球（尺寸 = 胶囊高度，位置 = 胶囊左侧） ---------- */

/** 球与胶囊之间的间距 */
const ORB_GAP = 12
/** 球的下限：再小就没有「点阵」可言，只剩一团噪点 —— 低于它就干脆不显示 */
const ORB_MIN = 40
/** 球的上限：textarea 最多 4 行（约 140px），没有上限就会长出一颗巨球 */
const ORB_MAX = 88

/**
 * 量出胶囊的几何，决定粒子球的尺寸以及「这一侧还剩不剩地方」。
 *
 * <h3>为什么不用媒体查询，而是实测</h3>
 * 胶囊是 `max-width: var(--chat-col) + bleed×2` 且**居中**的，它左侧的走廊
 * = `(可用宽 - 胶囊宽) / 2`。这个值既受视口宽度影响，也受右栏是常驻还是抽屉影响
 * （同一视口宽度下两者差 300px），还与胶囊是否已经顶到 max-width 有关 ——
 * 用断点抄一遍等于把三套规则重新推导一次，任何一处改了都要同步改常量，
 * 漏改的症状是「球被 `.chat-main` 的 overflow 切掉一条边」。
 * 直接读真实盒子：胶囊左缘到 footer 左缘的距离就是走廊宽度，无论布局怎么变都成立。
 *
 * <h3>为什么「优先缩小」而不是「放不下就隐藏」</h3>
 * 走廊宽度随窗口变化：1440 宽时每侧约 150px（够 54px 的球），1280 宽时只剩约 70px，
 * 更窄时只有那 32px 内边距。第一版判据是「够 54+16 才显示」，结果窗口一窄球就
 * **整个消失**，看起来像功能没做。现在改成：只要能塞下 `ORB_MIN`（40px）就显示，
 * 尺寸取 `min(胶囊高, 走廊宽 - 间距)` —— 球会跟着走廊变瘦，但始终在场。
 */
function syncComposerOrb() {
  const foot = footRef.value
  const box = composerBoxRef.value
  if (!foot || !box) {
    orbFits.value = false
    return
  }

  /*
   * 走廊宽度 = 胶囊左缘 - footer 左缘。
   * 注意**不减 footer 的 paddingLeft**：`.chat-input` 没有底色也没有裁剪，
   * 内边距那一圈同样是可见区域，球可以踩进去（真减了会在 1280~1340 这一带
   * 白白藏起一颗本来放得下的球 —— 上一版就是栽在这个多余的减法上的）。
   */
  const footRect = foot.getBoundingClientRect()
  const boxRect = box.getBoundingClientRect()
  const corridor = boxRect.left - footRect.left

  const room = corridor - ORB_GAP
  if (room < ORB_MIN) {
    // 走廊连最小值都放不下（约 1200px 以下）：只能不显示，否则会被切边
    orbFits.value = false
    return
  }

  const size = Math.round(Math.min(ORB_MAX, Math.max(ORB_MIN, Math.min(box.offsetHeight, room))))
  /*
   * 写到 footer 上，由 `.composer-orb` 继承（球本身不改布局，不会引起二次测量）。
   * 尺寸和间距都从这里出去 —— CSS 里那条 `right: calc(100% + var(--orb-gap))`
   * 与这里的 `room = corridor - ORB_GAP` 必须用同一个数，否则「算着放得下、
   * 实际却被切掉一条边」（两处各写一个 16 就是这种隐患的温床）。
   */
  foot.style.setProperty('--orb-size', `${size}px`)
  foot.style.setProperty('--orb-gap', `${ORB_GAP}px`)
  orbFits.value = true
}

onBeforeUnmount(() => {
  footObserver?.disconnect()
  footObserver = undefined
})

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
  /*
   * 先挂输入浮层的高度观测，再去做网络请求。
   *
   * 顺序有讲究：`chat.enter()` 要等接口回来，期间页面已经渲染了首帧 ——
   * 这一帧的 `--chat-foot` 还是 CSS 兜底值。观测越早挂上，纠正得越早，
   * 用户看到「最后一条消息被输入框压住」的窗口就越短。
   * （`ResizeObserver` 在 observe 时会立刻回调一次，所以不用额外先量一遍。）
   */
  if (footRef.value && typeof ResizeObserver !== 'undefined') {
    footObserver = new ResizeObserver(() => syncFootHeight())
    footObserver.observe(footRef.value)
  }

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
/*
 * 页面容器：**满屏铺满**（对齐参考页 `.layout { display: flex; height: 100vh }`）。
 *
 * 上一版这里是 `grid` + `max-width: var(--content-max)` 居中 + 16px 内边距 +
 * `height: calc(100vh - 40px)`，结果是「窗口里浮着一张卡」—— 四边各留一条页面底色，
 * 与参考页「顶到视口四边」的版式完全不同，也吃掉了约 40px 高度。
 *
 * 现在改成 flex 两列 + 100vh：
 *   · 外层不再限宽/内边距 —— 居民端 `.app-body` 已经给了 64px 的侧栏偏移，
 *     内容区本身不加 padding（见 DefaultLayout 注释），所以这里能直接顶边。
 *   · `overflow: hidden` 兜住「输入区随 textarea 长高」这类内部增长，
 *     避免整页出现第二根滚动条。
 *   · 高度用 100vh 而不是 `calc(100vh - var(--header-h))`：居民端顶栏已删除。
 */
.chat-page {
  display: flex;
  align-items: stretch;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  /* 背景层（.chat-lens）是 absolute，必须以本页为定位参照 */
  position: relative;
  /*
   * 对话列的两把尺子（2026-10-22 第十八轮）。
   *
   * `--chat-col`：消息列宽。气泡、摘要条、空态都落在这条竖轴上。
   * `--composer-bleed`：输入浮层比消息列**左右各外扩**的量。
   *   用户的要求是「输入框长度大于上方聊天消息区 5px」。这里取的是
   *   **每侧 5px**（总宽 +10）—— 2.5px 一侧在 760 宽的列上属于「改了也看不见」。
   *   若你要的是总宽只多 5px，把这里改成 2.5px 即可，其它地方全是 `calc`，不用动。
   *
   * ⚠️ 三个消费者必须共用这两个变量（`.chat-body > .msg`、`.quick-row`、
   * `.input-row` / `.closed-bar`）：任何一处硬编码 760，输入框与消息列就会错轴。
   */
  --chat-col: 760px;
  --composer-bleed: 5px;
  /*
   * 输入浮层高度的兜底值。真实值由 `syncFootHeight()` 量完写到 `.chat-body`
   * 的行内样式上（CSS 变量可以逐元素覆盖，所以行内那份会盖掉这里）。
   * 这个默认值只在「首帧、观测还没回调」的极短窗口里起作用，取的是
   * 单行输入 + 建议行都在时的近似高度。
   */
  --chat-foot: 132px;
  /*
   * 「消息在输入区附近渐隐」这套已经**整条撤掉**（2026-10-22 第十九轮，用户明确要求）。
   *
   * 原先这里有一个 `--chat-fade: 40px`，配合 `.chat-body` 的 `mask-image` 让消息
   * 在浮层上沿之前「化掉」。用户的口径是：**不要模糊**，消息往下滑时只有被
   * 输入框和追问建议**实际盖住**的地方看不到，其余地方都该看得见。
   *
   * 所以现在没有渐隐带，遮挡完全由浮层的实体部分（白胶囊、建议 chip）承担。
   * ⚠️ 不要为了「好看」把它加回来 —— 那会把「遮挡」重新变成「淡化」，
   * 正是用户这次要撤掉的效果。真要改观感只能改胶囊/chip 的不透明度，
   * 不能给滚动容器加遮罩。
   */
  /*
   * 白纱浓度：盖在 WebGL 背景之上的一层**均一**白。
   *
   * 为什么需要它，而不是让背景原样透出来：
   *   · 底色 #7ce2e3 是高明度青，光丝峰值接近浅紫 #e7a9fd —— 直接压 `--text-2`
   *     在峰值区只有约 4.2:1（15px 正文勉强，12px 小字不够）。34% 白把峰值抬到约 5.3:1、
   *     底色区约 5.9:1，两处都过 AA；
   *   · 同时把「青 + 紫」两档色拉回本站的低饱和口径，白卡片浮上去不会打架。
   *
   * 0 = 原图直出；0.55 以上基本只剩一层浅纹。改的是**浓度**不是颜色，
   * 整层一个 alpha、没有渐变，所以取任何值都不会产生接缝。
   */
  --chat-veil: 34%;
  /*
   * 底色**仍然不在这里画** —— 但理由变了（2026-10-22 第 22 批）。
   *
   * 旧理由：透出 body 的 --app-bg，避免 135deg 渐变在「视口宽 - 64px 侧栏」
   * 这个更窄的盒子上重新起算、与其他页面错位。
   *
   * 现在：背景换成了 `.chat-lens`（WebGL 马赛克，画布不透明，整层盖住本页）。
   * body 那层渐变在本页已经**完全看不见**，所以「透出」这件事对本页不再生效，
   * 但 `transparent` 必须保留：一旦在这里补一层色，它会盖在 canvas 之上，
   * 变成「一块纯色盖一张图」—— 也就是前几轮反复撤掉的「两块颜色拼起来」。
   * 要改本页背景，改 `--lens-chat-bg`，不要改这里。
   *
   * ⚠️ 与布局层的约定仍然成立（`.app-body` / `.content-layer` 保持无背景）：
   * 它们若有底色，会从 `.chat-lens` **四周**（比如 1px 抗锯齿缝、或画布还没
   * 完成首帧的那几十毫秒）透出来。改布局层时记得回来确认。
   */
  background: transparent;
}

/* ---------- 背景层：WebGL 马赛克 ---------- */
/*
 * 覆盖整页（含右栏底下的那段），`z-index: 0`。
 * 右栏 `.chat-side` 是 --card-bg 白面、`z-index: 1`，会把它盖住 —— 这是对的：
 * 背景只服务消息区，不该从右栏的卡片缝里透出来。
 *
 * 三个 `--lens-*` 覆盖：组件内部用 `getComputedStyle(root)` 读这三个名字，
 * 而自定义属性会从祖先继承，所以在这里赋值即可只影响本页，不动登录页那份。
 * 值是 `var(--lens-chat-*)`（间接一层）—— 组件对这种写法会再查一层，
 * 不依赖「浏览器是否替我们展开 var()」这种实现差异。
 */
.chat-lens {
  position: absolute;
  inset: 0;
  z-index: 0;
  /*
   * 透镜跟手靠组件内部的 window 级 pointermove，与 canvas 命不命中无关，
   * 所以可以放心吃掉这一层的指针事件（否则它会挡住下面所有交互）。
   */
  pointer-events: none;
  --lens-bg: var(--lens-chat-bg);
  --lens-c1: var(--lens-chat-c1);
  --lens-c2: var(--lens-chat-c2);
  /* canvas 首帧之前露出的是这个色；与画面底色同值，接手时不会闪一下白 */
  background-color: var(--lens-chat-bg);
  overflow: hidden;
}

/*
 * 白纱。整层均一 alpha —— **不要在这里加渐变**：
 * 一旦有了方向，它就会在某个位置形成一条肉眼可辨的「色带结束线」，
 * 而那正是前几轮反复撤掉的东西（背景连续性的唯一依据是「没有任何拼接」）。
 */
.chat-lens-veil {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--card-bg) var(--chat-veil), transparent);
}

/* ---------- 对话 ---------- */
/*
 * 对话主列**没有自己的面**：底色由 `.chat-lens` 提供 → 这里透明。
 *
 * 上一版它是 --card-bg 的白面，靠「白卡 vs 次面消息区」两层差给气泡当衬托；
 * 现在整页是一张马赛克图，白气泡浮在它上面（峰谷亮度差约 1.3:1）—— 边界靠
 * 「1px 描边 + 轻投影」补足（见 .msg.ai .bubble），不再依赖底色差。
 *
 * `z-index: 1` 是必须的：`.chat-lens` 是 `position: absolute; z-index: 0`，
 * 而同值/auto 的定位元素按**树序**决定先后 —— 靠树序太脆（有人把背景层挪到
 * 模板末尾就全错）。这里写死 1，把「内容在背景之上」变成声明而不是巧合。
 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: transparent;
  overflow: hidden;
  /* 输入浮层的定位上下文（它自己 `position: absolute; bottom: 0`） */
  position: relative;
  z-index: 1;
}

/* ---------- 顶部导航（Radio 组，Uiverse / Admin12121） ----------
 *
 * 结构：外层 `.chat-nav` 只负责「横向居中 + 浮在顶部」，玻璃胶囊是内层 `.nav-menu`。
 * 分两层是为了 `pointer-events`：外层铺满整行但**不接收事件**（否则它会把
 * 消息区顶部一整条横带的点击/选中吃掉），只有菜单本体接。
 *
 * 形状与尺寸（第三十轮按用户要求：白卡 → 透明玻璃胶囊，整体收窄拉长）：
 *   单项 64×44（原 70×50）、悬停展开 120（原 130）、内边距 5（原 8）、1px 描边、
 *   圆角 `--radius-pill`。整体从 226×66 收到 **204×56**，长宽比 3.42 → 3.64 ——
 *   所以「更细长」是算出来的，不是错觉。
 *   高度停在 44 不再往下压：44px 是触控目标的下限，再薄就不好点了。
 *
 * ⚠️ 5px 内边距与 44px 高度是**绑死**的一对，不能单独改：
 *     父胶囊左端内壁半径 = 56 / 2 - 1(描边) - 5(内边距) = 22
 *     子按钮自身半径     = 44 / 2 = 22
 *   两者严格同心，悬停/选中的色块才会严丝合缝地贴着胶囊内壁。
 *   只动其中一个，端点就会露出一条月牙形的缝 —— 用户说的「边缘不整洁」正是这个。
 *   窄屏那档是同一套算式：50 / 2 - 1 - 4 = 20 = 40 / 2。
 *
 * 质感：走 tokens 里**结构性玻璃**那一档（`--chrome-bg-glass` + `--glass-blur`），
 * 与左侧 64px 窄栏、管理端 56px 顶栏同一套材质，不新造色值（硬约定 #114）。
 * 不用 `--card-bg-glass`（0.82）：那是内容面的玻璃，比这一档透，压在会动的马赛克上
 * 会盖不住图标。0.62 这一档实测够用：背后最深的青底穿过 34% 白纱后约 #a9ec，
 * 玻璃合成面约 #e5f9f9，`--text-2` 仍有约 6.8:1；图标是图形（需 3:1），更宽松。
 *
 * 展开时整组会左右各让 28px（宽度 204 → 260，居中排布）—— 这是样例本身的
 * 行为，不是 bug。觉得悬停时「邻座在动」的话，把 `.nav-link` 的宽度改成固定
 * 120 并只动 `::before` 与标题即可，代价是静止态三枚图标之间会空出一大片。
 */
.chat-nav {
  position: absolute;
  /* (110 - 56) / 2：110px 是 `.chat-body` 顶部留给这一条的高度，56 是胶囊总高
     （44 按钮 + 5×2 内边距 + 1×2 描边），上下各 27px 后菜单正好落在带子正中。
     改胶囊高度必须同步改这里，否则会整体偏上/偏下。 */
  top: 27px;
  left: 0;
  right: 0;
  z-index: 2;
  display: flex;
  justify-content: center;
  /* 见上：整条横带都不吃事件，只让菜单本体吃 */
  pointer-events: none;
}

.nav-menu {
  pointer-events: auto;
  display: flex;
  justify-content: center;
  padding: 5px;
  /*
   * 玻璃三件套：半透明底 + backdrop 模糊 + 顶边 1px 内高光。
   *
   * `backdrop-filter` 不是美观选项而是可读性的必要条件（实测写在 tokens.css
   * `--card-bg-glass` 上方）：纯靠 alpha 无论提多白都压不住背后不断变色的马赛克。
   * `saturate(180%)` 与 `.quick-chip` 取同一值 —— 只 blur 不提饱和会发灰，像磨砂塑料；
   * `blur(18px)` 直接把背后的图形搅成均匀的明暗，才像玻璃而不是"半透明贴纸"。
   *
   * ⚠️ 这一层**绝不加 transform**（硬约定 + 第十七轮那个「发虚」）：Chrome 对带
   * backdrop 模糊的元素做位移时会拿变换**前**的画面采背景，合成出一层抹开的残影。
   * 居中的办法是 `justify-content: center`，不是 `translateX(-50%)`。
   *
   * 描边沿用 `.quick-chip` 的思路：主色 12% 混进 `--card-border`。纯灰像表单控件，
   * 纯主色太重；混一点主色相让它与发送键、选中态同族。
   */
  background: var(--chrome-bg-glass);
  backdrop-filter: blur(var(--glass-blur)) saturate(180%);
  border: 1px solid color-mix(in srgb, var(--primary) 12%, var(--card-border));
  border-radius: var(--radius-pill);
  /* 顶边内高光：玻璃的"厚度"。做成变量是因为它必须跟在每一处 box-shadow 里 ——
     漏掉的话某个状态下顶边高光会消失，玻璃立刻塌成一张透明贴纸。 */
  --nav-hi: inset 0 1px 0 color-mix(in srgb, var(--card-bg) 92%, transparent);
  box-shadow:
    var(--nav-hi),
    var(--shadow-hover);
}

.nav-link {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  /*
   * 两档宽度走自定义属性而不是写死：窄屏要整体收小、减动效要「不展开」，
   * 三个需求落在同一个 `width` 上。写成变量后，「不展开」只要把 open 指回 base
   * 即可，不需要在每个断点里复制一遍 `width: 64px`（复制的那份迟早会漏改）。
   *
   * 圆角必须是 `--radius-pill` 而不是 `--radius-ctrl`：胶囊两端的半圆来自
   * 「子按钮半径 = 父胶囊内壁半径」这条同心关系（算式见上方导航那段注释）。
   */
  --nav-w: 64px;
  --nav-w-open: 120px;
  width: var(--nav-w);
  height: 44px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  cursor: pointer;
  /* 标题被推到按钮外，靠这里裁掉 —— 少了它，静止态会看到三个字挤在图标右边 */
  overflow: hidden;
  transform-origin: center left;
  transition:
    width 0.2s var(--ease-entry),
    color var(--dur) var(--ease);
}

/* 悬停/聚焦时从右侧滑进来的底色块。
   `z-index: -1` 能落在按钮**背景之上、图标文字之下**，靠的是 `.nav-link` 自己
   `position: relative + z-index: 1` 建立了层叠上下文 —— 负值只会沉到这一层的
   最底，不会掉到父级（玻璃胶囊）下面去。少了 `z-index: 1` 的话它会直接沉到
   胶囊背后，等于整块底色看不见。 */
.nav-link::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: var(--radius-pill);
  /*
   * 悬停那一片是「再糊一层白」，不是实心色块 —— 玻璃上压实色会立刻穿帮成贴纸。
   * 58% 与底下的 62% 叠起来约 84% 白：色块边界看得出来，但背后的光还透得出来。
   */
  background: color-mix(in srgb, var(--card-bg) 58%, transparent);
  transform: translateX(100%);
  transform-origin: center right;
  transition: transform 0.2s var(--ease-entry);
}

.nav-link:hover,
.nav-link:focus-visible {
  width: var(--nav-w-open);
  color: var(--text-1);
  outline: 0;
}

.nav-link:hover::before,
.nav-link:focus-visible::before,
.nav-link:hover .nav-title,
.nav-link:focus-visible .nav-title {
  transform: translateX(0);
}

.nav-link:hover .nav-title,
.nav-link:focus-visible .nav-title {
  opacity: 1;
}

/* 图标绝对定位在左端：展开时标题往右让，图标不动 —— 否则整枚按钮的内容
   会随着宽度变化左右晃（样例也是这么处理的）。
   left 的取值让图标在**静止态 64px 按钮里正好居中**：(64 - 22) / 2 = 21。
   上一版是 20（对应 70px 宽），收窄后没跟着改会偏左 1px —— 1px 在居中的
   小图标上看得出来。 */
.nav-icon {
  position: absolute;
  left: 21px;
  display: block;
  width: 22px;
  height: 22px;
}

.nav-icon svg {
  display: block;
  width: 100%;
  height: 100%;
}

.nav-title {
  display: block;
  width: 100%;
  /* 让出图标那一格，标题才在「图标右侧的剩余空间」里居中。
     34 = 2 × (77 - 60)：展开态 120 宽时文字中心落在 77px，
     图标右缘在 43px，中间留 21px 呼吸 —— 与上一版 130 宽时的手感一致。 */
  text-indent: 34px;
  text-align: center;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  opacity: 0;
  transform: translateX(100%);
  transform-origin: center right;
  transition:
    transform 0.2s var(--ease-entry),
    opacity 0.2s var(--ease-entry);
}

/* 选中态：底色常驻（`--primary-soft`），图标与文字走主色。
   与 hover 的底色块抢同一个 `::before`，所以选中规则必须写在 hover 之后 ——
   同特异性下后者胜（#140），否则悬停没选中的项时选中项会被刷成灰白。

   文字色必须是 `--primary-dark` 而不是 `--primary`：`--primary` 落在
   `--primary-soft` 上只有 4.44:1，不到 AA（硬约定「主色落 soft 一律用 -dark」）。
   而标题恰好只在悬停/聚焦时才可见，也就是说**唯一会显示出来的那一帧正好是最差的那一帧**。
   上一版这里写的是 `--primary`，顺手改掉了。

   选中块保持**实心** `--primary-soft`，不做成半透明：它上面要压 13px 的字，
   一旦透出背后的马赛克，`--primary-dark` 的对比度就变成不确定的了。 */
.nav-link.is-active {
  color: var(--primary-dark);
}

.nav-link.is-active::before {
  transform: translateX(0);
  background: var(--primary-soft);
}

/* 减动效（顶部导航）：**必须放在文件末尾**（见下方那一段），这里不再重复。
   理由：要压掉的是 `width` 展开，而 ≤560 那档也改同一个属性，同特异性只能靠
   「位置更后」生效 —— 写在这里会被后面的窄屏规则盖掉。 */

/* 顶栏已删除（2026-10-22 第三轮）。
   原先这里有 .chat-head / .chat-head-text / .chat-title / .chat-subtitle /
   .live-dot / @keyframes live-breathe / .chat-head-ops 共七段样式。
   模板里已无对应节点，留着只会编译进 bundle 并让后来者以为界面还有这么一条横条。 */

/* ==================== 按钮：参考页 `.btn` 三件套 ====================
 * 原生 button 而不是 a-button —— vendor-antd 预算只剩约 9 kB（硬约定 #9）。
 *
 * 形状语言从「胶囊」（999 圆角、4×12 内边距）换成参考页的「圆角矩形」
 * （--radius-ctrl 10 / 8×16 内边距 / 13px·600 / 14px 线性图标）。
 * 不是纯审美：本页顶栏只有两个按钮，胶囊形在这个尺寸下更像「标签」，
 * 且与消息气泡的 18px 圆角不成体系；10px 正好是站内「控件」这一档，
 * 与输入框、标签页、小方块同一族。
 */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 18px;
  /*
   * 圆角回到胶囊（--radius-pill）：目标版式里「清空会话 / 推荐医生」是两枚
   * 全圆端子，与下方输入胶囊、引导胶囊同一族形状。
   * 上一轮取 --radius-ctrl(10) 是照另一份参考页搬的，那份页面的按钮是圆角矩形；
   * 本版以用户给的目标图为准，形状语言统一成「胶囊」。
   */
  border-radius: var(--radius-pill);
  font-family: inherit; /* 原生 button 不继承 body 字体，不写会落回系统 UI 字体 */
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.btn svg {
  width: 14px;
  height: 14px;
  flex: none;
}

/* 次要：白底 + 描边。悬停只换描边与文字色、**不换底色** ——
   白卡上换底色会浮出一块与周围不同深浅的补丁。
   文字取 --primary-dark 而不是 --primary：主色落浅底只有 4.44，不到 AA 4.5。 */
.btn-ghost {
  border: 1px solid var(--card-border);
  background: var(--card-bg);
  color: var(--text-2);
}

.btn-ghost:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary-dark);
}

/* 主要：实心主色。投影走 --shadow-primary（主色同族，看起来「发亮」而非「发脏」） */
.btn-primary {
  border: 1px solid var(--primary);
  background: var(--primary);
  color: var(--on-primary);
  box-shadow: var(--shadow-primary);
}

.btn-primary:hover:not(:disabled) {
  border-color: var(--primary-dark);
  background: var(--primary-dark);
}

.btn-primary:active:not(:disabled) {
  box-shadow: none;
}

/* 禁用：两个变体统一退回中性灰面，不保留主色与投影，避免「看起来能点」。
   写在变体之后，靠「特异性更高 + 位置更后」覆盖，不需要 !important。 */
.btn:disabled {
  border-color: var(--card-border);
  background: var(--panel-bg);
  color: var(--text-3);
  box-shadow: none;
  cursor: not-allowed;
}

/* 窄屏唤出右栏的按钮：宽屏下右栏常驻，这个入口不该出现。
   顶栏删除后它不再有宿主，改为 fixed 悬浮在视口右上角（见 1100 断点）。 */
.side-toggle {
  display: none;
}

/* 抽屉态专用的关闭键：宽屏下同理不出现。
   `position: absolute` —— 它在抽屉里浮在栏的右上角，不占 flex 流，
   否则会把「清空会话 / 推荐医生」这一行整体往下顶一截。 */
.side-close {
  display: none;
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 2;
  padding: 6px;
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
/* 前情摘要：底色改白（原来是 --panel-bg）。
   消息区透明之后，--panel-bg 的近白压在浅蓝底上没问题，但 --text-3 落在
   --panel-bg 只有 4.27（不到 AA 4.5）—— 既然要改，就一次改到白面 + --text-2，
   两处都达标，也不再多出一种「灰白条」的面。 */
.summary-note {
  max-width: 86%;
  margin: 0 auto 16px;
  padding: 10px 14px;
  background: var(--card-bg);
  border: 1px dashed var(--card-border);
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--text-2);
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
  /* 与输入行同宽居中：这条提示条就长在输入框的位置上，
     宽度不跟 `.input-row` 对齐会让人以为输入区换了一套版式 */
  width: 100%;
  max-width: calc(var(--chat-col) + var(--composer-bleed) * 2);
  margin: 0 auto;
}

.closed-text {
  font-size: 13px;
  /* 输入区已透明，这句话直接压在马赛克背景上。
     --text-3 落在页面底只有 3.60（不到 AA 4.5）→ 必须用 --text-2。
     背景换成马赛克 + 34% 白纱之后实测 ≈5.8:1（光丝峰值区 ≈5.3:1），仍然守得住。 */
  color: var(--text-2);
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

/* ⚠️ 窄屏（≤560px）断点**不在这里**，而在文件最末尾。
   它原先就写在这个位置（基础 `.chat-body` / `.chat-input` 之前），
   而媒体查询里的选择器与基础规则**特异性相同**，同特异性下后者胜 ——
   于是那几行 padding 一直被下面的 `padding: 22px 32px` 整段覆盖，
   属于「写了但从未生效」的死代码。挪到文件末尾即可，无需提高特异性。 */

/* 消息区**透明**，让底下的 `.chat-lens`（WebGL 马赛克）透上来。
   上一版它是 --panel-bg 的 L2 次面（用来衬托白气泡），现在衬托改由气泡自身的
   1px 描边 + 轻投影承担 —— 一层「更浅的灰面」压在图上会把画面切断。 */
.chat-body {
  flex: 1;
  /* ⚠️ min-height: 0 不能省。flex 子项的默认 min-height 是 auto（= 内容高度），
     内容一多它就不肯收缩，最终把父容器撑破 —— 输入区被顶出视口，
     而页面上看不出是谁超了。参考页 `.chat-scroll` 同样写了这一行。 */
  min-height: 0;
  overflow-y: auto;
  /*
   * 消息区现在**铺满整个对话列**（输入区是浮层，压在它上面）——
   * 于是两条边距必须按浮层高度算，不能再写死：
   *   顶部 110px：第一条消息与视口顶的间距（用户指定值，顶部要预留后续内容）；
   *   底部 = 浮层高 + 8：保证**滚到底时**最后一条消息完整停在浮层上方
   *          （+8 是呼吸；浮层自身顶部还有 12px 内边距，合计约 20px 间隙）。
   *
   * ⚠️ 这个 padding 只影响「滚到底」那一帧的落点，不影响滚动途中的遮挡：
   * 中途的消息会直接从浮层底下穿过去，被胶囊/chip 实体盖住的部分看不见，
   * 露在它们之间的部分照常显示 —— 这是用户第二十轮明确要的观感。
   */
  padding: 110px 32px calc(var(--chat-foot) + 8px);
  background: transparent;
  /*
   * 这里**没有** mask / 渐隐 —— 消息在底部是「被盖住」而不是「被淡化」。
   *
   * 历史：第十八轮曾用 `mask-image` 做 40px 底部渐隐，让消息在浮层上沿「化掉」；
   * 第十九轮按要求撤掉 —— 用户的判据是「只有被输入框和建议 chip **实际盖住**的
   * 地方看不见，其余都该看得见」，渐隐带会把本该看见的那几十像素一起化掉。
   *
   * 撤掉后顺带解决三件事：
   *   ① 不用再给最右侧 12px 补一层 mask 去救滚动条（遮罩属于滚动容器的渲染内容，
   *      会把滚动条一起渐隐；滚到底时拇指落进渐隐带，整根滚动条看起来像消失了）；
   *   ② `--chat-fade` 这个变量随之删除，少一个「改了没反应」的旋钮；
   *   ③ 背景连续性变得更显然 —— 整条链一层底色都没有，不存在拼接。
   *
   * ⚠️ 不要再加回来。它看着「柔和」，实际是把「遮挡」换成了「淡化」，
   * 而用户明确不要糊。遮挡只能由浮层的实体（白胶囊、建议 chip）承担。
   */
  /* Firefox 的细滚动条；-webkit- 分支见下 */
  scrollbar-width: thin;
  scrollbar-color: var(--text-3) transparent;
}

/*
 * 消息列限宽并居中 —— 参考页 `.msg-list { max-width: 760px; margin: 0 auto }`。
 *
 * 为什么必须限宽：气泡是 `max-width: 74%`（相对消息行），行宽一旦跟着窗口长，
 * AI 的一条长回复能拉到 900px 以上，中文长段落的回扫距离太大，读起来很累。
 * 760 是参考页给出的取值，也大致等于「一屏能舒适读完的行宽」。
 *
 * 只作用于 `.msg`，不动 `.chat-empty`（它自己限 420 居中）和 `.summary-note`
 * （自己限 86% 居中）—— 那两块本来就有各自的宽度语言，统一成 760 反而会让
 * 空态的图形离文字过远。
 */
.chat-body > .msg {
  max-width: var(--chat-col);
  margin-inline: auto;
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

/*
 * 细圆角滚动条。
 *
 * ⚠️ 拇指色从 --card-border 换成 --text-3：背景改成浅蓝渐变之后，
 * --card-border（近白）压在 #c6d9f5 上几乎看不出 —— 滚动条等于不存在，
 * 用户以为「这一屏已经到底了」。--text-3 是页面底上唯一达标的深色档之一，
 * 且与消息正文的灰阶同族，不会跳出来抢注意力。
 * 宽度 6px 略收窄，是因为透明背景上不需要给滚动条留「轨道」的视觉余量。
 */
.chat-body::-webkit-scrollbar {
  width: 6px;
}

.chat-body::-webkit-scrollbar-track {
  background: transparent;
}

.chat-body::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: var(--text-3);
  transition: background var(--dur) var(--ease);
}

.chat-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-2);
}

/*
 * 空态：目标版式里它是**垂直居中**的一列（光核 → 一句引导 → 三条胶囊），
 * 而不是贴在顶部。用 `margin: 10vh auto 0` 而不是 flex 居中：
 * 消息区本身要保持块级流（消息、摘要、分析态都按块排），
 * 为一次居中把它改成 flex 容器会连带影响所有子元素的宽度计算。
 */
.chat-empty {
  max-width: 420px;
  margin: 10vh auto 0;
  text-align: center;
}

/*
 * 粒子球：外层柔光呼吸 + 中层点阵球（components/OrbNoise.vue，Canvas 2D）。
 *
 * 尺寸 148：粒子是半透明小点，视觉重量比原来那个 120px 的实心圆轻得多，
 * 沿用 120 会在版式里「塌」下去；148 撑回了同一个视觉重心，高度上只多了 28px
 * （`.chat-empty` 是 10vh 起排，不影响首屏可见的三条胶囊）。
 *
 * 删掉了原来的细环（`.orb-ring`）和实心内核（`.orb-core`）：点阵球自带球形轮廓，
 * 再套一个 1px 圆环就变成「两个同心圆」，反而显得是加载动画。
 */
.empty-orb {
  position: relative;
  width: 148px;
  height: 148px;
  margin: 0 auto 24px;
}

/* 柔光：原来铺 `--primary-soft`（浅蓝），现在跟着粒子的色走 ——
   紫色粒子 + 蓝白柔光会出现「冷底暖核」的脏边，同色系才像一团发光的雾。 */
.orb-halo {
  position: absolute;
  inset: -20px;
  border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, var(--orb-dot) 20%, transparent) 0%, transparent 68%);
  animation: halo-breathe 4.5s var(--ease) infinite;
}

/* 画布层：绝对定位填满 `.empty-orb`；组件内部已处理 DPR 与尺寸测量。
   同样带父级前缀，理由见 `.input-row .composer-orb` 上方那段。 */
.empty-orb .orb-field {
  position: absolute;
  inset: 0;
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

.empty-desc {
  margin: 0 0 24px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-2);
}

/* 引导胶囊：三条**等宽居中**堆叠（目标版式），而不是随文字长短参差。
   等宽的理由：它们是一组「入口」而不是三段引用，宽度不齐会让人以为
   第二条更重要；`align-items: center` 让整组在空态里居中。 */
.empty-tips {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

/* 引导胶囊：悬停**上浮 + 主色描边**，不走「加深底色」——
   底色一深，就会在浅蓝底上浮现一块与周围不同深浅的补丁。
   白底 + 轻投影是它在渐变上「浮起来」的依据（目标图同款）。 */
.tip-chip {
  width: 320px;
  max-width: 100%;
  padding: 12px 20px;
  border: 1px solid var(--card-border);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13.5px;
  text-align: center;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: all var(--dur) var(--ease);
}

.tip-chip:hover {
  border-color: var(--primary);
  color: var(--primary-dark);
  transform: translateY(-1px);
  box-shadow: var(--shadow-hover);
}

/*
 * 消息行：气泡 + 身份标 + 头像，头像**贴气泡底边**排在页面外侧
 * （AI 在左下方、用户在右下方，见目标截图）。
 *
 * `align-items: flex-end` 是三档里唯一正确的：
 *   · `stretch`   —— 头像会被拉成一整列高，不再是圆的；
 *   · `flex-start` —— 头像与首行齐平，长回复时悬在顶部，读起来像「站在气泡头上」；
 *   · `flex-end`  —— 头像底边与气泡底边对齐，是聊天界面的通用语汇
 *                    （头像属于「谁说的」这个结论，跟着结尾走）。
 *
 * 进入动画幅度刻意压得很小（6px / 240ms）：切会话时历史消息会整批重建 DOM、
 * 一起播一遍，幅度一大就不是「新消息来了」而是「页面在抖」。
 */
.msg {
  display: flex;
  align-items: flex-end;
  /* 18 → 20：目标截图里相邻气泡的间隙约 20px（同一说话人的连续消息会更紧，
     但本页每条消息都是一个独立的 `<div>`，没有「同一人连发」的分组概念）。 */
  margin-bottom: 20px;
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
   间距左右镜像：AI 标在气泡左（margin-right），用户在气泡右（margin-left），
   两个头像都「贴着自己的气泡、朝页面外侧」。
   ⚠️ 不能再写 `margin-top: 3px` —— 父级已是 `align-items: flex-end`，
   那一行会让头像比气泡底边低 3px（改成顶对齐时留下的微调，现在没有意义）。 */
.msg-face {
  flex: none;
  margin: 0 12px 0 0;
}

.msg.user .msg-face {
  margin: 0 0 0 12px;
}

/*
 * 气泡宽度：取 `min(640px, 82%)` —— 640 在满屏（如 1920 宽）下正好是
 * 「一屏舒适读完」的行宽，而百分比那一档保证窄屏时气泡不会贴着两侧留白。
 * 两者取小，宽屏靠固定值、窄屏靠比例。
 *
 * 圆角统一 16px（`--radius-sm`），**四个角一样**：原先给「贴头像那一侧的下角」
 * 留了 6px 的收角（`border-bottom-*-radius`），配顶对齐的头像是「指向说话人」的
 * 小心思；但头像改到底部之后，收角正好落在头像旁边 —— 一个尖角贴着一颗圆头像，
 * 看起来像两个图形撞在一起。目标截图里也是四角同圆。
 *
 * 字号 14 → 15px：聊天正文是「要读进去」的内容，14px 在 1440 宽的屏幕上偏小；
 * 15px 配 1.75 行高，中文一行约 40 字，回扫距离刚好。
 */
.bubble {
  max-width: min(640px, 82%);
  padding: 14px 18px;
  border-radius: var(--radius-sm);
  font-size: 15px;
  line-height: 1.75;
}

/* 用户气泡：主色**渐变**实心 + 主色同族投影。
   渐变给小块面带来体积感（纯色在深色屏幕上容易显得平）；
   投影用 --shadow-primary 而不是中性灰，看起来是「发亮」而不是「发脏」 */
.msg.user .bubble {
  background: var(--grad-primary);
  color: var(--on-primary);
  box-shadow: var(--shadow-primary);
}

/* AI 气泡：白底 + **轻投影**，去掉描边。
   目标截图里的白气泡没有可见边框，边界完全由投影交代 ——
   一条 1px 灰线在大面积白气泡上反而会显脏（尤其多条消息排下来，
   每一圈描边都在强调"这是个盒子"，而不是"这是一段话"）。 */
.msg.ai .bubble {
  background: var(--card-bg);
  color: var(--text-1);
  box-shadow: var(--shadow-card);
}

/* 危机气泡是唯一保留描边的一种：它的底色是浅红（--risk-d-soft），
   白底上的轻投影托不住一块带色相的面，需要描边把边界说清楚。 */
.msg.ai .bubble.is-crisis {
  background: var(--risk-d-soft);
  border: 1px solid var(--risk-d-border);
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

/* 输入区**没有自己的面**：透明 + 无分隔线 + **浮在消息之上**。
   目标版式里底部只有一枚浮在渐变上的白色胶囊输入框，没有横贯全宽的底栏；
   保留 border-top 会在窗口底部切出一条与版式无关的线。

   ⚠️ 2026-10-22 第十八轮：从「flex 的最后一行」改成**绝对定位的浮层**。
   之前消息区到它上沿就被裁断，滚动时看上去像两个盒子拼在一起（用户的「断层」）；
   现在消息区铺满整列、从它下面穿过去，再由 `.chat-body` 的 mask 渐隐 ——
   视觉上是一个连续的空间，输入框只是停在上面的一个物件。

   `z-index: 2` 是用户点名要求的层级：输入框（含 placeholder、建议胶囊、快捷键提示）
   在最上层，AI 与用户的消息统一在下一层。消息区因为 mask 自带层叠上下文，
   两者不会互相穿插。

   底部留白 32px（原 22）：胶囊是「停下来的一块」，贴着视口底边会显得被切掉一截；
   32px 让它在视觉上离开地面，也把整条输入区往上抬了一档（用户要求「整体上移一点」）。
   ⚠️ 不要把这份留白挪进 `.composer` 的 margin —— 那样输入区仍占满原高度，
   移动的只是胶囊内部，看上去不会有任何变化。 */
.chat-input {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  padding: 12px 32px 32px;
  /* ⚠️ 这里**不许**加 background / border / box-shadow。
     底部之所以与上方的渐变是「同一张」，靠的就是这一层什么都不画 ——
     一旦补了色（哪怕是同款渐变 + fixed），就变成「两块钱拼起来」，
     而且 fixed 在祖先有 transform 时会失效成 scroll，渐变会重新起算。

     另外：这一层的**透明是故意的**。消息从底下穿过去，只有被胶囊和 chip
     实体压住的那部分看不见，露在缝隙里的部分照常显示（用户第二十轮要的就是这个）。
     所以既不能补底色、也不能加 mask —— 遮挡交给 `.composer` / `.quick-chip` 自己。 */
  background: transparent;
}

/* ---------- 追问建议（输入框右上方） ----------
 *
 * 样式取向是「轻、薄、可忽略」：它是提示不是内容，不能与气泡抢视觉重量。
 * 三处细节：
 *   ① **半透明毛玻璃**：70% 白 + `backdrop-filter: blur(18px) saturate(180%)`，
 *      背后会透出一层被搅散的色与光。为什么是 70% 而不是更透 —— 实测过：
 *      最坏情况是用户那条深蓝气泡（#1d4fd8）从下面经过，70% 合成底 #bbcaf3，
 *      `--text-2` 压上去 **4.75:1**（亮端 #2563eb 时 5.01）；降到 65% 直接掉到
 *      4.34，破 AA。**70% 是这条线的下限**，想更透就得先换更深的字色。
 *      玻璃落在页面渐变上时其实很安全（6.98 / 7.14），瓶颈只在彩色气泡经过的瞬间；
 *   ② 描边用「主色 20% 混进 --card-border」——纯灰描边像表单控件，
 *      纯主色描边又太重；混一点主色相让它与发送键同族。
 *      玻璃面上再加一道 1px 内高光（`--chip-hi`），玻璃才有「厚度」而不是「半透明贴纸」；
 *   ③ 左侧 5px 渐变圆点 + 3px 柔光外圈：与右栏卡片标题的圆点、空态光核是同一支渐变，
 *      让「这是 AI 给的提示」不需要用文字说明。
 *
 * ⚠️ 玻璃的代价要先讲清：背后滑过的消息不再是「完全看不见」，而是变成一团
 * 模糊光斑透出来 —— 这是材质本身的性质，不是 bug。要「彻底盖住」就得回到实心白，
 * 两者不可兼得（见 `.quick-chip` 里第十九/二十轮的取舍记录）。
 */
.quick-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  /* 与输入胶囊同宽同轴（比消息列左右各宽 --composer-bleed）：
     提示贴着输入框的右缘，不能各自居中 */
  max-width: calc(var(--chat-col) + var(--composer-bleed) * 2);
  margin: 0 auto 10px;
}

/*
 * 「等建议中」：整行藏起来，但**保留高度**。
 *
 * 为什么是 `visibility` 而不是 `v-if` / `display: none`：
 * 这一行在输入胶囊正上方，把它从布局里摘掉，胶囊会往下掉 34px 再弹回来 ——
 * 一个回合至少抖两次，比空着更难忍。`visibility: hidden` 不改变布局，
 * 只是让内容不可见、不可点（也就不会有任何「虚影」）。
 *
 * 为什么这期间干脆不显示「上一轮的三条」：
 * 它们是对上一段回答成立的，用户刚问了别的，灰着摆在那里既点不动、
 * 又让人误以为点得动。宁可空着，也不要摆一段过期的建议。
 */
.quick-row--idle {
  visibility: hidden;
}

.quick-chip {
  /*
   * 毛玻璃三件套：半透明底 + backdrop 模糊 + 顶边 1px 内高光。
   *
   * 70% 白是「透得出来 / 压得住字」的平衡点，推导写在 `.quick-row` 上方那段注释里
   * （最坏情况是深蓝用户气泡从下面经过，70% 时 `--text-2` 仍有 4.75:1；65% 就破了 AA）。
   * `saturate(180%)` 把透出来的浅蓝提一点色相 —— 纯 blur 会发灰，像磨砂塑料。
   * `blur(18px)` 给得比常见值大：模糊半径小的时候，背后的字还能看出"是个字"，
   * 那个状态最难看（半懂不懂的鬼影）；18px 直接把字搅成均匀的明暗，才像玻璃。
   *
   * 🔁 这个文件里它被改了三轮，留个沿革免得反复横跳：
   *   第十七轮 84% + blur(8px) → 用户报「思考时发虚」（真因是 chip 上还挂了 transform）
   *   第十九轮 改实心白、去掉 backdrop-filter → 为了「被盖住就看不见」
   *   第二十轮 按用户要求改回玻璃，但**只做玻璃、不挂 transform**，
   *            并把透明度从 84% 提到 70% + 模糊加到 18px，避开当年那个坑
   */
  background: color-mix(in srgb, var(--card-bg) 70%, transparent);
  backdrop-filter: blur(18px) saturate(180%);
  border: 1px solid color-mix(in srgb, var(--primary) 20%, var(--card-border));
  border-radius: var(--radius-pill);
  /* 顶边内高光：玻璃的"厚度"。做成变量是因为 hover 要换外层阴影但必须保留它 */
  --chip-hi: inset 0 1px 0 color-mix(in srgb, var(--card-bg) 90%, transparent);
  color: var(--text-2);
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.35;
  white-space: nowrap;
  cursor: pointer;
  box-shadow:
    var(--chip-hi),
    var(--shadow-card);
  display: inline-flex;
  align-items: center;
  padding: 7px 14px;
  /*
   * ⚠️ 不要给 `transform` 加过渡，也不要在 `:hover` 里位移 / 缩放。
   * chip 带 `backdrop-filter`，Chrome 对带背景模糊的元素做位移时会拿**变换前**的
   * 画面去采背景，合成出一层抹开的残影 —— 第十七轮那个「思考时发虚」就是这么来的。
   * 悬停的「浮起来」改用加重阴影表达，手感差别很小。
   */
  transition:
    border-color var(--dur) var(--ease),
    color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.quick-chip::before {
  content: '';
  flex: none;
  width: 5px;
  height: 5px;
  margin-right: 8px;
  border-radius: 50%;
  background: var(--grad-primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
  /* 只过渡柔光外圈：圆点不做 scale —— 一排 chip 的圆点同时缩放在视觉上很吵 */
  transition: box-shadow var(--dur) var(--ease);
}

.quick-chip:hover {
  border-color: var(--primary);
  /* 悬停文字必须换 --primary-dark：--primary 落在近白底上只有 4.44，不到 AA */
  color: var(--primary-dark);
  /* 只换外层阴影，`--chip-hi` 必须原样带上 —— 漏了的话悬停瞬间玻璃的顶边高光会消失，
     看起来像贴纸被人抽走了一层 */
  box-shadow:
    var(--chip-hi),
    var(--shadow-hover);
}

.quick-chip:hover::before {
  box-shadow: 0 0 0 4px var(--primary-soft);
}

/*
 * 这里原先有 `.quick-chip:disabled { opacity: 0.55 }`，已删除（2026-10-22）。
 *
 * 它是「AI 思考中」那层虚影的第二个来源：0.55 的半透明白片压在浅蓝渐变上，
 * 看起来就是一张发白的残影。而现在的做法是整个回合把行 `visibility: hidden`，
 * 既然看不见，就不需要用「压暗」去表达「暂时别点」——
 * 半透明的禁用态反而会在其它场景（如窄屏重排）里重新露出来。
 */

/*
 * 输入行：宽度、居中，以及「右侧粒子球」的定位参照。
 *
 * 发送按钮已经放进胶囊内部（见模板），所以这里**不是** flex 容器 ——
 * `.composer` 自己就是 760 宽、居中、内含三个子项的一整条。
 * 与 `.chat-body > .msg` 用同一个 max-width，左缘落在同一根竖线上。
 *
 * `position: relative` 只为一件事：让 `.composer-orb` 以**本行右缘**为基准往右放。
 * 球是绝对定位，不参与本行的宽度计算 —— 所以胶囊的宽度、居中、与消息列的对齐
 * 全都不受它影响（真用 flex 并排就会把胶囊挤窄，输入框就不再比消息区宽 5px）。
 */
.input-row {
  position: relative;
  /* 比消息列左右各宽 --composer-bleed：输入条是这屏唯一「浮起来」的物件，
     边缘略微超出正文列，才读得出「它在上面一层」而不是「正文的一部分」。 */
  max-width: calc(var(--chat-col) + var(--composer-bleed) * 2);
  margin: 0 auto;
}

/*
 * 输入框**左侧**的粒子球（会话已有消息时出现，见模板注释）。
 *
 * 尺寸与间距由 JS 写进 `--orb-size` / `--orb-gap`（单一口径在 syncComposerOrb 里），
 * 这里只管摆放：右缘贴住本行左缘再让出 gap（`right: calc(100% + gap)`），
 * 垂直方向用 `top: 50% + 负 margin` 居中 —— 与胶囊里的发送键同一条中轴。
 *
 * ⚠️ 居中**不用** `translateY(-50%)`：不是这里不能转，而是本项目的约定 ——
 *    第十八轮出过「backdrop-filter 元素带 transform → Chrome 拿变换前的画面采背景
 *    → 重影」（见 `.quick-chip` 上方那段）。负 margin 一样准，且不引入这条风险，
 *    也让「这层永远不加 transform」成为可以直接 grep 的规矩。
 *
 * ⚠️ 选择器带 `.input-row` 前缀是有意的：球组件自己的根节点也带 `position: relative`
 *    （`.orb`），两条规则特异性相同（类 + 作用域属性），谁生效取决于**两个 CSS 块
 *    在产物里的先后顺序** —— 那是由模块图决定的，没法保证。这里多一层前缀把它压死，
 *    否则某次重构后球会掉进普通流、跑到输入框上面单独占一行（典型的 #140 号坑）。
 */
.input-row .composer-orb {
  position: absolute;
  right: calc(100% + var(--orb-gap, 12px));
  top: 50%;
  width: var(--orb-size, 54px);
  height: var(--orb-size, 54px);
  margin-top: calc(var(--orb-size, 54px) / -2);
  /* 装饰层，且它落在胶囊左侧的空白区：不能吃掉任何指针事件，
     也不能让「从这里开始拖动选中文字」失效 */
  pointer-events: none;
}

/*
 * 输入胶囊。
 *
 * 形状从「透明底 + 一条下划线」换成「白底 + 全圆角胶囊」（目标版式）：
 * 下划线输入在透明渐变上会失去「输入框在哪」的边界，而胶囊自带面与边界，
 * 与上方的引导胶囊、右侧的操作胶囊是同一族形状。
 *
 * 一行三件：textarea（flex:1）/ 快捷键提示 / 44px 发送圆钮。
 * 三者都在**胶囊内部**，右内边距只有 6px —— 按钮与胶囊右缘之间是「紧贴」的，
 * 这样它读起来是胶囊的右端，而不是旁边多出来的一颗圆钮。
 *
 * ⚠️ 圆角写 26px 而不是 --radius-pill(999)：胶囊内是自动增高的 textarea，
 * 最多 4 行。999 会被浏览器钳到高度的一半，多行时左右弧形会啃进文字；
 * 26px 在单行高（约 54px）下等效于完美胶囊（钳到 27 附近），多行时退化成圆角矩形，
 * 两种状态都成立 —— 一处取值同时满足两个高度，不需要媒体查询或 JS 判断。
 */
.composer {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  padding: 5px 6px 5px 22px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 26px;
  /*
   * 四层阴影 = 四个「层次」，从贴身到散开（2026-10-22 第二十一轮）：
   *
   *   ① 接触影   0 1px 2px           --text-1  6%   交代「胶囊贴着什么东西」
   *   ② 近影     0 6px 16px -4px     --text-1 10%   中性灰，负责结构
   *   ③ 中景影   0 16px 34px -8px    --primary 12%  开始带色，阴影过渡成「光」
   *   ④ 扩散晕   0 0 44px -4px       --primary 14%  无方向的大范围柔光
   *
   * 三点取舍：
   *   · **蓝而不是黑**：黑色大范围投影压在低饱和浅蓝底上会发灰（看着像脏了），
   *     主色混透明才是「发光」。所以 ③④ 用 `--primary`，①② 仍用中性 `--text-1`
   *     收结构 —— 全蓝会飘，全灰会脏，混着才对。
   *   · **不用 `--shadow-card`**：那是卡片语汇（单层中性影），托不住这里要的层次。
   *   · **负 spread（-4 / -8）**：浮层左右只有 32px 内边距，正 spread 的大模糊会顶到
   *     `.chat-main` 的 `overflow: hidden` 上。实测外缘 alpha 已不到 1%（看不到），
   *     但往里收几像素更稳，也避免「光晕比胶囊大一圈」的笨重感。
   *
   * ⚠️ 阴影是**静态**的（不进 transition）。聚焦时的加强由 `::after` 承担，
   * 原因见下面那段注释 —— 别为了「一步到位」把 box-shadow 塞回过transition。
   */
  box-shadow:
    0 1px 2px color-mix(in srgb, var(--text-1) 6%, transparent),
    0 6px 16px -4px color-mix(in srgb, var(--text-1) 10%, transparent),
    0 16px 34px -8px color-mix(in srgb, var(--primary) 12%, transparent),
    0 0 44px -4px color-mix(in srgb, var(--primary) 14%, transparent);
  transition: border-color var(--dur) var(--ease);
}

/*
 * 聚焦加强：单独一层光晕，**只过渡 opacity**。
 *
 * 为什么不直接让 `.composer` 的 box-shadow 在两态之间插值：
 * box-shadow 的动画每帧都要重新光栅化整块模糊区域（约 770×160），
 * 低配机上会掉帧；而 opacity 是合成器直接做的，阴影只需光栅化一次。
 * 圆角、范围、颜色全都不变量，只让「亮度」渐变 —— 视觉上也更像光在慢慢亮起来。
 *
 * `z-index: -1` 为什么能把光画到胶囊**背后**：`.composer` 有 position 但没 z-index，
 * 不是层叠上下文，所以这个伪元素落到 `.chat-input`（`z-index: 2`）那一层里，
 * 排在胶囊自身背景之下、消息之上 —— 正是「从胶囊底下透出来的光」。
 * `inset: -1px` 是为了贴住 border box（绝对定位的包含块是 padding box，差一圈 border）。
 * `pointer-events: none` 必须有：光晕盖住胶囊四周，不能吃掉那一带的点击。
 */
.composer::after {
  content: '';
  position: absolute;
  inset: -1px;
  z-index: -1;
  border-radius: inherit;
  pointer-events: none;
  box-shadow:
    /* 紧贴环：原来的聚焦表达，只是改为随光晕一起淡入 */
    0 0 0 4px var(--primary-soft),
    /* 扩散晕：比静止态更亮更大（14% → 22%，44px → 56px） */
    0 0 56px -4px color-mix(in srgb, var(--primary) 22%, transparent);
  opacity: 0;
  /* 240ms 比 --dur 稍长：光「亮起来」比「切过去」慢一点才自然 */
  transition: opacity 240ms var(--ease);
}

/*
 * 聚焦：外圈主色描边 + 一圈柔光。
 * 原来的焦点表达是「渐变线从 0 铺满」，那是下划线输入框的语汇；
 * 胶囊没有底线可铺，改成「整圈亮起」—— 同样的动效时长与缓动，
 * 只是把「线的生长」换成「环的显现」。
 *
 * 描边单独用 --dur 过渡（它只重绘 1px 边框，很便宜），光晕走 240ms，
 * 两者不同步是故意的：边框先说「我在这里」，光随后漫上来。
 */
.composer:focus-within {
  border-color: var(--primary);
}

.composer:focus-within::after {
  opacity: 1;
}

/*
 * 输入框本体：完全去壳（透明、无边框、无内边距），
 * 面与内边距全部由 `.composer` 承担 —— 这样光标区与胶囊的左右留白是同一套数值，
 * 不会出现「文字离左边 22px、胶囊弧线却按 6px 算」的错位。
 */
.chat-input :deep(.ant-input) {
  flex: 1;
  min-width: 0;
  border: none;
  border-radius: 0;
  padding: 0;
  background-color: transparent;
  color: var(--text-1);
  font-size: 15px;
  line-height: 1.6;
  caret-color: var(--primary);
  /* 清掉 antd 的聚焦光晕：焦点表达交给 `.composer:focus-within` */
  box-shadow: none;
  resize: none;
}

.chat-input :deep(.ant-input:focus),
.chat-input :deep(.ant-input-focused) {
  border: none;
  background-color: transparent;
  box-shadow: none;
}

/* 占位符落在白胶囊上，--text-3 是 4.8（达标） */
.chat-input :deep(.ant-input::placeholder) {
  color: var(--text-3);
}

/* 禁用（会话已结束 / 无权发送）：底色必须显式声明，否则 antd 会填它自己的灰底 */
.chat-input :deep(.ant-input[disabled]) {
  background-color: transparent;
  color: var(--text-3);
}

/*
 * 快捷键提示：胶囊内右侧的小字。
 * `flex: none` + `nowrap` 保证它不被 textarea 挤压折行；
 * 窄屏由媒体查询隐藏（375px 上引导语与提示会挤成一团）。
 */
.composer-hint {
  flex: none;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-3);
  white-space: nowrap;
  user-select: none;
}

/* ---------- 发送按钮：双层圆环 + 上箭头（Uiverse / xopc333） ----------
 *
 * 形状从「主色实心圆」换回样例的**双层圆环**（2026-10-22 第二十六轮，用户指定）：
 * 静息是一圈淡蓝细环，悬停时它缩掉、一圈更实的主色环从 1.12 倍收进来，
 * 同时下方那支箭头滑上来 —— 环的「一进一出」就是按压感的来源，实心圆做不出来。
 *
 * 尺寸 44×44：
 *   胶囊单行高 ≈ 54（上下各 5px 内边距 + 44 的按钮撑起内容区）
 *   按钮比胶囊内沿略小、四周留 5px，视觉上「嵌在里面」而不是「比它高」
 *   箭头 16×16 居中 → 四周留 14px，不需要额外定位
 *
 * 三处与样例的必要差异：
 *   ① **箭头朝上**：样例的路径指向 +x（右），朝上要 `rotate(-90deg)`
 *      （CSS 正角是顺时针，写正值会朝下）；相应地「两支箭头」的轨道从横向
 *      改成纵向（`flex-direction: column` + `translateY(-44px)`），
 *      否则朝上的箭头横向平移，读起来是「横着挪」而不是「往上送」。
 *   ② **缩放上限 1.12**：样例按钮 56px、环 inset 7px，环放大到 1.3 倍（54.6）
 *      仍小于按钮；我们只有 44px、环 inset 3px（外径 38），1.3 倍是 49.4 ——
 *      会被 `overflow: hidden` 切成四段弧。44/38 ≈ 1.157，取 1.12 留一点余量。
 *   ③ **曲线改用本项目的两档缓动**（`--ease-entry` / `--ease-prod`）：
 *      样例的三条 cubic-bezier 是它自己的动效语言，与本页其余动效不同族；
 *      但**保留 80ms 的延迟不对称** —— 环缩回去时先让、再让新环进场，
 *      这个先后关系是手感的一部分，换曲线也不该丢。
 */
.send-btn {
  position: relative;
  flex: none;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  /* 透明底：形状完全由两枚伪元素圆环承担 */
  background: transparent;
  color: var(--primary);
  cursor: pointer;
  /* 裁掉箭头滑动时的「另一半」；环的最大缩放 1.12 仍在圈内，不会切到 */
  overflow: hidden;
  transition:
    color var(--dur) var(--ease),
    transform var(--dur) var(--ease);
}

.send-btn:before,
.send-btn:after {
  content: '';
  position: absolute;
  border-radius: 50%;
  /* 44 - 6 = 38 外径；再扣 3px 边框 → 内径 32，16px 的箭头四周还有 8px */
  inset: 3px;
  pointer-events: none;
}

/* 静息环：主色 55% —— 再淡就和「禁用态那圈灰环」分不出来了（这是这个样式最大的坑），
   再浓又和悬停环没有区别，一眼看不出可点 */
.send-btn:before {
  border: 3px solid color-mix(in srgb, var(--primary) 55%, transparent);
  transition:
    opacity 0.4s var(--ease-prod) 80ms,
    transform 0.5s var(--ease-entry) 80ms;
}

/* 悬停环：主色实色，先以 1.12 倍隐身待命，悬停时收回 1 倍并显形 */
.send-btn:after {
  border: 3px solid var(--primary);
  opacity: 0;
  transform: scale(1.12);
  transition:
    opacity 0.4s var(--ease-entry),
    transform 0.5s var(--ease-prod);
}

.send-btn:not(:disabled):hover,
.send-btn:not(:disabled):focus-visible {
  color: var(--primary-dark);
}

.send-btn:not(:disabled):hover:before,
.send-btn:not(:disabled):focus-visible:before {
  opacity: 0;
  transform: scale(0.7);
  transition:
    opacity 0.4s var(--ease-entry),
    transform 0.5s var(--ease-prod);
}

.send-btn:not(:disabled):hover:after,
.send-btn:not(:disabled):focus-visible:after {
  opacity: 1;
  transform: scale(1);
  transition:
    opacity 0.4s var(--ease-prod) 80ms,
    transform 0.5s var(--ease-entry) 80ms;
}

.send-btn:not(:disabled):active {
  transform: scale(0.94);
}

/* 键盘焦点：环的「一进一出」本身就是可见变化，但那只是颜色深浅之差；
 * 键盘用户需要一条**位置明确**的指示，所以额外补一圈外环。
 * offset 3px 仍在胶囊内（胶囊右内边距 6px），不会顶到胶囊边缘。 */
.send-btn:focus-visible {
  outline: 2px solid var(--primary-dark);
  outline-offset: 3px;
}

/* 两个箭头的纵向轨道。44 宽下箭头 16 + 左右各 14 = 44，正好一格 */
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
  margin: 14px;
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
  transform: translateY(-44px);
}
/* ---------- 发送中 ---------- */
/* 按钮现在是透明底，转圈直接骑在白胶囊上 —— 所以环用主色（原来是 on-primary 白，
   那是配实心主色底才对的；透明底上说白环等于看不见）。 */
.send-spin {
  position: absolute;
  inset: 10px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--primary) 28%, transparent);
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

/* 发送中：箭头让位给转圈，两枚圆环同时隐去（否则两圈叠在一起像描边没对齐），
   悬停变化也停掉（那会让人以为还能再点一次） */
.send-btn.is-busy .send-box {
  opacity: 0;
  transition: opacity 0.15s linear;
}

.send-btn.is-busy:before,
.send-btn.is-busy:after {
  opacity: 0;
  transition: opacity 0.15s linear;
}

.send-btn.is-busy .send-spin {
  opacity: 1;
}

/* ---------- 不可用 ----------
 * 原生 button 的 disabled 不受 hover 影响（上面的规则已用 :not(:disabled) 排除）。
 * 透明底之后不能再靠「灰面」表达禁用，改成：环褪成中性灰、箭头同色
 * —— 形状还在，只是整颗「熄了」。禁用控件不参与 WCAG 对比度要求。
 */
.send-btn:disabled {
  color: var(--text-3);
  cursor: not-allowed;
}

.send-btn:disabled:before {
  border-color: color-mix(in srgb, var(--text-3) 26%, transparent);
}

.send-btn:disabled:after {
  opacity: 0;
}

/* 减动效：去掉滑移与环的缩放，只留「静息环 / 悬停环」这个结果（形状变、不位移）。
   ⚠️ 转圈**不关** —— 它是「正在发送」唯一的反馈，停掉之后用户无法判断
   按钮是否在工作，只能反复点击。这一处属于「表达状态所必需」，不是装饰。 */
@media (prefers-reduced-motion: reduce) {
  .send-box,
  .send-btn:before,
  .send-btn:after {
    transition: none;
  }

  .send-btn:not(:disabled):hover .send-box,
  .send-btn:not(:disabled):focus-visible .send-box {
    transform: none;
  }

  .send-btn:not(:disabled):hover:before,
  .send-btn:not(:disabled):focus-visible:before,
  .send-btn:not(:disabled):hover:after,
  .send-btn:not(:disabled):focus-visible:after {
    transform: none;
  }
}

/* ---------- 右栏 ---------- */
/*
 * 右栏：**白面通栏面板**（目标版式里它是整页唯一的一块白）。
 *
 * 与刚删除的顶栏正是同一套判断的反面：顶栏是一条横贯对话区的白条，
 * 会把渐变切断；右栏是一列纵向的白面，与左侧渐变形成「深底 / 白栏」的分区，
 * 这是目标图里最重要的一处结构关系 —— 左边是内容，右边是上下文。
 *
 * 底色从 --panel-bg 换回 --card-bg：浅蓝渐变旁边再放一层「近白灰」会显脏，
 * 纯白才有目标图那种干净的分栏感。卡片压在纯白上靠 1px 描边分层，
 * 不再需要底色差。
 *
 * 滚动条：右栏内容（四张卡 + 页脚）在 900px 高的视口里通常会溢出，
 * 而这一列的滚动条必须**看得见** —— 用户反馈「上下滚动时右侧要出现滚动条」。
 * 拇指取 --text-3（白底上 4.8:1），宽度给到 8px，比消息区那根 6px 的更粗：
 * 它滚动的是「上下文」而不是正文，粗一点更容易在余光里被摸到。
 */
.chat-side {
  position: relative;
  /* 压在背景层（.chat-lens, z-index: 0）之上；窄屏抽屉态由媒体查询改成 30 */
  z-index: 1;
  flex: 0 0 300px;
  width: 300px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 16px 20px;
  background: var(--card-bg);
  border-left: 1px solid var(--card-border);
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: var(--text-3) transparent;
}

.chat-side::-webkit-scrollbar {
  width: 8px;
}

.chat-side::-webkit-scrollbar-track {
  background: transparent;
}

.chat-side::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 4px;
  background: var(--text-3);
  background-clip: padding-box;
}

.chat-side::-webkit-scrollbar-thumb:hover {
  background: var(--text-2);
  background-clip: padding-box;
}

/*
 * 动作组（清空会话 / 推荐医生）：右栏第一行，右对齐。
 *
 * `flex: 0 0 auto` 是必需的：右栏是纵向 flex 且 `overflow-y: auto`，
 * 滚动时这一行会跟着滚走 —— 这正是要的（它属于栏内容，不是浮动工具条）。
 * 右对齐让两枚胶囊贴住卡片右缘，与下方卡片的**左对齐**形成对照，
 * 不会被读成「第三张卡片」。
 */
/*
 * 动作组：两枚等宽胶囊铺满整行。
 *
 * `flex: 1 1 0` + `min-width: 0` 是「等宽」的正解：基准宽度取 0 而不是 auto，
 * 两枚才会**严格对半**；否则「推荐医生」比「清空会话」宽一点点（内容宽不同），
 * 分割线就不在正中间，对齐卡片边缘时看着歪。
 *
 * `min-width: 0` 不能省：flex 子项默认 `min-width: auto`（= 内容宽），
 * 而 `.btn` 上有 `white-space: nowrap`，一旦栏宽变窄，按钮宁可溢出也不收缩。
 *
 * 宽度来源：`.chat-side` 的左右内边距（16px），与 `.side-card` 用的是同一组 ——
 * 所以按钮的左右边缘天然与卡片对齐，不需要任何额外的负 margin。
 */
.side-ops {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
}

.side-ops .btn {
  flex: 1 1 0;
  min-width: 0;
  /* 等宽之后文字必须居中，否则会各自贴左，看上去像两个对齐失败的表单项 */
  justify-content: center;
}

/* 卡片：圆角 --radius-sm(16)、内边距 16。
   右栏底色回到纯白之后，卡片靠「1px 描边 + 极轻投影」分层 ——
   白压白没有底色差可用，描边是唯一的边界依据，不能省。 */
.side-card {
  flex: 0 0 auto;
  padding: 16px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-card);
  animation: card-in 0.3s var(--ease) both;
}

/* 「本次未关联测评」是空态提示，不是内容卡：底色不再退回 --panel-bg
   （退回去就跟栏面同色、整张卡消失了），改用虚线描边表达「这里还没东西」 */
.side-card.is-muted {
  border-style: dashed;
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

/* 卡片依次浮现。错开的间隔（50ms）小于单块的动画时长（300ms），
   所以看到的是「一层层铺开」而不是「一块块排队跳出来」。
   ⚠️ 用 `:nth-of-type` 且**从 2 开始**：右栏第一个 `div` 现在是顶部的
   动作组（`.side-ops`，清空会话 / 推荐医生），它也是一个 div，会把
   四张卡片的序号整体推后一位。写成 1..4 会让第一张卡拿到 90ms 的延迟、
   最后一张拿不到延迟 —— 顺序还看得见，但不是想要的节奏。 */
.side-card:nth-of-type(2) {
  animation-delay: 40ms;
}

.side-card:nth-of-type(3) {
  animation-delay: 90ms;
}

.side-card:nth-of-type(4) {
  animation-delay: 140ms;
}

.side-card:nth-of-type(5) {
  animation-delay: 190ms;
}

/*
 * 卡片小标题：参考页 `.panel-card h3`（14px / 700 / 无衬线，前面一枚 6px 渐变圆点）。
 * 上一版是「衬线 + 左侧 3px 竖条」—— 竖条与圆点说的事一样（标记区块起点），
 * 但圆点和参考页的其余部分（`.dot`、状态点）同族，竖条只在这一处出现。
 *
 * ⚠️ 字号 14px 属于「卡片小标题」这一档，按站内规矩走 --font-sans + 600，
 * 不跟着 h1–h6 的衬线走 —— 否则右栏四行小字全是宋体，在屏幕上发虚。
 */
.side-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}

/* 渐变圆点：用 --grad-primary（与空态光核、主按钮同一支渐变） */
.side-title::before {
  content: '';
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--grad-primary);
}

/* 危机卡的圆点跟着卡片语义转红：标题文字已经是 --risk-d，
   圆点若还是主色蓝，同一行里会出现两个互相打架的强调色 */
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

/* 卡片内说明文字：--text-2（参考页 `.panel-card .desc` 取 --text-sub）。
   虽然卡片是白面、--text-3 在白面上有 4.8 达标，但右栏的字都比正文小一档，
   12.5px 还压到 --text-3 会明显发灰 —— 参考页在这一处用的是「正文色小一号」。 */
.side-note {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
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

/* 免责声明：钉到栏底（`margin-top: auto`）。
   这段在版式上属于「页脚」，不是「第五张卡片」—— 跟着卡片浮动时，
   内容少就会发现它悬在半空，像少了一张卡的内容。

   ⚠️ 颜色取 --text-2 而不是 --text-3：它直接坐在**白栏面**上，
   白底上 --text-3 虽然 4.8 达标，但 11.5px 的小字配这一档灰明显发虚；
   而右栏其余说明文字都是 --text-2，保持一致。
   容器宽度由 `.chat-side` 的 flex 列确定，`auto` 在这里一定生效。 */
.side-footnote {
  margin: auto 0 0;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--text-2);
}

.side-footnote strong {
  color: var(--text-1);
}

/* 抽屉关闭键的定位已在基础规则里写死（absolute 右上），这里不再补 padding/对齐。
   原先那条 `align-self: flex-end` 是为「按钮作为 flex 子项参与排版」写的，
   改绝对定位后参与排版会让按钮组多出一行高度。 */

/* 尊重系统「减少动态效果」设置。
   本页有三类持续/入场动画：呼吸（.orb-halo）、错峰进场（.msg / .side-card），
   以及粒子球自转（components/OrbNoise.vue，**组件内部自己冻结首帧**，
   不归这条规则管 —— 它是 Canvas 绘制，CSS 的 animation 关不掉它）。
   前庭功能敏感的用户即便幅度很小也可能不适，所以整体关掉。
   这是本页唯一用 !important 的地方 —— 元素自身的 animation 简写
   必须被整条覆盖，「把时长设为 0」仍会保留 animation-fill-mode 的首帧位移，
   等于没关掉。
   ⚠️ `.orb-ring`（随细环删除）、`.live-dot`（随顶栏删除）、`.ai-mark`（更早一轮删除）
      都已没有对应节点，不要留死选择器。 */
@media (prefers-reduced-motion: reduce) {
  /* 冻结后停在呼吸的中点，而不是默认的满不透明度 —— 满亮度比动画平均更亮，
     会把旁边那颗静态的粒子球压过去 */
  .orb-halo {
    animation: none !important;
    opacity: 0.75;
  }

  .msg,
  .side-card {
    animation: none !important;
  }
}

/* ==========================================================
   响应式：右栏抽屉（参考页 1100px 断点）
   ==========================================================
   中屏以下把 300px 的常驻右栏收成从右侧滑出的抽屉。用 CSS 做位移动画 +
   一个 `is-open` 类，而不是按宽度决定「渲染/不渲染」：
   后者要在 JS 里维护一份与媒体查询重复的断点常量，两处一旦不同步就会漏渲染。

   ⚠️ 抽屉状态下 `.chat-side` 是 `position: fixed`，所以它的
   `flex: 0 0 300px` 不再参与父级分配 —— 宽度靠同时写死的 `width: 300px` 兜住。 */
@media (max-width: 1100px) {
  .chat-side {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    /* 必须高于唤出键（25），否则抽屉展开后唤出键会浮在抽屉上 */
    z-index: 30;
    transform: translateX(100%);
    transition: transform 0.25s var(--ease);
    box-shadow: var(--shadow-hover);
    /*
     * 顶部让出一条 44px 的「关闭键车道」（2026-10-22 第二十七轮）。
     * 上一版是给动作组 `padding-right: 44px` 来避开关闭键 —— 按钮改为铺满整行后
     * 那种做法会让按钮的右缘比卡片短 44px，「与下方对齐」就断在半路。
     * 改成给**整栏**加顶部内边距：关闭键独占上面一条，动作组与卡片都能占到满宽。
     */
    padding-top: 46px;
  }

  .chat-side.is-open {
    transform: translateX(0);
  }

  /* 唤出键：顶栏删除后它没有宿主，改为悬浮在视口右上角。
     白底胶囊压在浅蓝渐变上边界清晰；`z-index` 低于抽屉，抽屉打开后被盖住
     （此时关闭入口换成抽屉内的 ×）。 */
  .side-toggle {
    position: fixed;
    top: 14px;
    right: 14px;
    z-index: 25;
    display: inline-flex;
    background: var(--card-bg);
    box-shadow: var(--shadow-card);
  }

  .side-close {
    display: inline-flex;
  }
}

/* ==========================================================
   小屏（≤560px）：留白收紧、次要按钮只留图标
   ==========================================================
   ⚠️ 这一段必须放在**文件末尾**（所有基础规则之后）：这里的 `.chat-body` /
   `.chat-input` 与基础规则特异性相同，靠「位置更后」才生效。 */
@media (max-width: 560px) {
  /* 32px 的内边距在 375px 宽的屏上要吃掉 64px（16%），
     留给气泡的宽度只剩约 300px —— 一句话要折三四行 */
  /* 110px 在 375 宽的屏上会吃掉近三分之一可用高度，窄屏收到 72px；
     桌面端仍是指定的 110px。
     ⚠️ 72 不是随手写的：顶部导航在窄屏从 12px 起、高 50（40 按钮 + 上下各 4 内边距
     + 上下各 1 描边），底部落在 62 —— 72 是「导航底 + 10px 呼吸」，
     再小第一条消息就会钻到菜单下面。胶囊尺寸一变，这个数必须跟着变。 */
  .chat-body {
    /* 底部必须仍然跟着浮层高度走：窄屏的输入区（单行胶囊 + 建议横向滑动）
       高度与宽屏不同，抄一个固定值会让最后一条消息被压住。 */
    padding: 72px 14px calc(var(--chat-foot) + 40px);
  }

  /* 顶部导航同步收小：375 宽下 178px 的三枚胶囊放得下，
     但桌面那档的厚度在窄屏会显得笨，也会让上沿那条留白显得更厚。 */
  .chat-nav {
    top: 12px;
  }

  .nav-menu {
    padding: 4px;
  }

  /* 只改这几个值：宽度、高度、图标位置、标题缩进都是从它们推出来的。
     同心算式与桌面端同一套：50 / 2 - 1 - 4 = 20 = 40 / 2。
     高度停在 40 而不再往下压：手指点得到比「更细长」重要。 */
  .nav-link {
    --nav-w: 56px;
    --nav-w-open: 104px;
    height: 40px;
  }

  .nav-icon {
    left: 18px;
    width: 20px;
    height: 20px;
  }

  .nav-title {
    font-size: 12px;
    text-indent: 30px;
  }

  .chat-input {
    padding: 10px 14px 24px;
  }

  /* 追问建议在 375px 上放不下三条（每条都 nowrap），改成横向滑动、左起。
     ⚠️ 这里必须把 `justify-content` 从 flex-end 改回 flex-start：
     overflow 容器 + flex-end 时，超出的部分会从**左侧**溢出且滚不到，
     第一句会被永久裁掉。隐藏滚动条是因为它是辅助区，一根滚动条
     会把视线从输入框拉走。 */
  .quick-row {
    flex-wrap: nowrap;
    justify-content: flex-start;
    overflow-x: auto;
    padding-bottom: 2px;
    scrollbar-width: none;
  }

  .quick-row::-webkit-scrollbar {
    display: none;
  }

  .quick-chip {
    flex: none;
  }

  /* 快捷键提示在窄屏隐藏：375px 上「说说你的感受…」与「回车发送，Shift+回车换行」
     挤在同一行胶囊里，两段文字都会变成省略号，不如只留引导语。
     输入规则不丢 —— 回车发送本来就是主流习惯，Shift+回车在桌面端才用得上。 */
  .composer-hint {
    display: none;
  }

  /* ⚠️ 这里原先有一条 `.btn-ghost span { display: none }`（参考页的「窄屏只留图标」）。
     本版「清空会话」按钮**只有文字、没有图标**（按钮从顶栏搬到右栏后，
     图标在 300px 的栏宽里是多余的装饰），套上那条规则会让按钮变成空白方块。
     窄屏下右栏是抽屉、宽度充足，文字本来也不需要收。故整条删除。 */
  .btn {
    padding: 9px 14px;
  }

  .chat-empty {
    margin-top: 6vh;
  }

  .empty-orb {
    width: 116px;
    height: 116px;
    margin-bottom: 18px;
  }
}

/* ==========================================================
   顶部导航的减动效
   ==========================================================
   ⚠️ **必须放在整个样式表最后**：下面这条要压掉 `width` 的悬停展开，而
   ≤560 那一档也改 `--nav-w-open`，两者特异性相同 —— 谁生效只看文件位置
   （#140）。写在导航那一段旁边的版本会被窄屏规则盖掉，症状是
   「开了减少动态效果，窄屏上悬停仍然横移」。

   不展开 = 没有「邻座被挤开」，也不会有标题滑入；选中态与悬停的底色变化
   仍然保留 —— 那是颜色，不是位移。 */
@media (prefers-reduced-motion: reduce) {
  .nav-link,
  .nav-link::before,
  .nav-title {
    transition: none;
  }

  /* 把「展开宽度」指回基础宽度：一处改动同时管住桌面与窄屏两档 */
  .nav-link {
    --nav-w-open: var(--nav-w);
  }

  /* 不展开之后，标题就没有地方可待 —— 必须按回「推到按钮外」的状态，
     否则它会在 64px 的按钮里露出一截被裁掉的字 */
  .nav-link:hover .nav-title,
  .nav-link:focus-visible .nav-title {
    opacity: 0;
    transform: translateX(100%);
  }
}
</style>
