# PsyCare 项目长期约定

社区心理健康评测与咨询系统。前端 `psycare-web`（Vue3+TS+Vite+antd-vue+Pinia+ECharts），
后端 `psycare-server`（Spring Boot+MyBatis-Plus，context-path `/api`，端口 18989）。

**状态**：Phase 0–8.3 完成，**AI 模块 Phase 0/1/2/2.5 完成**（2026-09-29）；
居民端医患聊天已收敛到**专有页** `/messages/:doctorId`（同日，硬约定 #55–#58）。
真库已灌演示数据（`psycare-server/sql/v6_demo_seed.sql`）：**24 位医生 / 10 个科室 / 43 位居民** ——
医生密码 `doctor123`、居民密码 `user123`；回滚 `DELETE FROM sys_user WHERE id > 11;`；
执行前快照 `sys_user_bak_20260929` 等三张 `_bak_20260929` 表。**演示数据不得进生产**。
设计稿 8 屏落地、接口全链路 32/32、构建与视觉验收通过。
Phase 7 = 角色数据拆分/注册分流/账号治理/工单看做题记录（迁移已上真库 `psycare`：2 居民/3 医生/1 管理员）。
Phase 8/8.1 = 医患聊天（医生端「用户信息」+「消息列表」收件箱，居民端「我的消息」可挑医生），
复用 `consult_session`/`consult_message`（`consult_type='DOCTOR'`），**零建表**。
AI Phase 0 = 归属校验(403)/`doFinally` 落库/`scaleName` 修真/异常码映射 + `consult_message.finish_reason`。
AI Phase 1 = LLM 层企业化（`OpenAiCompatClient` 泛化、`LlmProperties` 全配置化、8 类错误码、
四道防线、SSE 六类事件、`ai_usage_log`、启动探活）。**DDL 已上真库，代码须重启后端**。
AI Phase 2 = 提示词分层（`PromptAssets` L1–L4）+ 引导问诊（`StageRules` 纯函数 +
`StageStore` Redis/重放）+ `ContextAssembler` 预算裁剪 + `TranscriptSummarizer` 异步摘要 +
历史会话接口与前端抽屉/阶段条。**删除 `PromptBuilder`**。零 DDL。
**Fix-1（2026-09-29）= 修「正常对话被误判为危机」**：危机判据拆输入/输出两套、新增 `crisis` 事件、
删掉前端 `12356` 子串启发式、修 `StageStore` 的 `LocalDateTime` 序列化（此前阶段状态从未写进 Redis）、
修本轮消息在 prompt 里重复、推荐理由去掉内部标签；并新增「AI 在对话正文里推荐医生」（L5b 注入）。
**AI Phase 2.5（2026-09-29）= 问诊提效与推荐闭环**（Phase 2 的追加增量，**不占用 Phase 3 编号**）：
阶段 **5 段 → 3 段**（删 `FUNCTION_IMPACT`/`RISK_SCREENING`，2~3 问即转介）+ `ReferralRules` 一问一答应允 +
SSE `recommend` 事件 + 分析动画 → 新推荐页 `/ai-recommend` + 「医生咨询」科室分类页 `/doctors`；
**删除 `DoctorRecommend.vue`**。零 DDL。
**Fix-2（2026-09-29）= 取消「历史会话」，改成一个居民一段连续对话**：`openSession` 改**幂等**
（复用该居民最近 `status='OPEN'` 的会话并更新 `assessmentId`）+ 前端 `chat.enter()` 统一入口 +
`restoreLast()` 改从服务端取（**删掉 localStorage 那套**）+ 不再关闭会话 + 删掉整个抽屉
（`AiChat` 18.68→**10.05 kB**）+ `logout()` 里清 chat store。
**AI Phase 3 阻塞在外部依赖**：通义 embedding 模型名+Key、本机 Milvus（先跑四断言探针，不过则停）。

---

## 一、硬约定

| # | 约定 | 违反后果 |
|---|---|---|
| 1 | 起项目前三依赖在跑：MySQL 3306、**Redis 6379**、后端 18989 | 缺 Redis → 登录成功立刻被弹回登录页 |
| 2 | 业务失败走 **HTTP 200 + `body.code`**，只有未登录走真 401 | 用 HTTP 码断言业务必误判 |
| 3 | 不要恢复 `app.use(Antd)` | vendor-antd 从 745KB 涨回 1.47MB |
| 4 | 颜色只改 `styles/tokens.css`，组件禁硬编码色值；antd 映射在 `App.vue`，改色需两处同步 | 出现两套色阶 |
| 5 | 改 antd 主题必须核对 token 名 | 写错静默忽略（曾有三处死代码） |
| 6 | 构建是 `vue-tsc -b && vite build`，不要为过构建删 `vue-tsc` | 类型检查是唯一能抓到 antd token 名写错的手段 |
| 7 | **改后端必须真重启**（类与 MyBatis XML 只在启动时加载）。本机后端从 **IDEA 启动**，IDEA 不自动重启 | 新路由全部不存在，旧兜底处理器还会把 404 报成「系统异常」，看着像业务 bug |
| 7.1 | 判断进程是否含最新代码：`jcmd <pid> VM.uptime`（秒数应小于最新源文件 mtime 距今）、`jcmd <pid> VM.system_properties \| grep java.class.path` 应指向 `target/classes` | 命令行起的实例会占住 18989，用户再点 Run 报端口占用 |
| 8 | 改路由 `meta.roles` 必须遍历 `to.matched` 全层，不能用 `find` | 子路由更严的约束永远不生效（`/admin/accounts` 曾拦不住医生） |
| 9 | `vendor-antd` 预算 **800 kB**，不为过构建上调阈值 | **实测 790.77 kB，仅剩约 9 kB**。新增 antd 组件/图标前先掂量，能纯 CSS 就纯 CSS；要加就先把不必要的换掉（`a-result`→手写块、`a-input-number`/`a-input-search`→`a-input`） |
| 10 | 自定义 MyBatis XML **必须手写 `is_deleted = 0`** | 自定义 SQL 不走 `@TableLogic`，已删账号照常出现在列表 |
| 11 | 账号治理路径变量 `{userType}` **不可信**，Service 须按 id 回查 `sys_user.role` 再校验 | 可构造越权请求 |
| 12 | `<script setup>` 内引 ref/computed **必须 `.value`** | 漏了条件恒真、永远走第一分支；`vue-tsc`/构建都不报错，只能用 `check_ref_value.py` 扫 |
| 13 | 表单字段**三处齐做**：`formState` / `rules` / 模板 `a-form-item name=` | 只写前两处 = 页面根本没有输入框，提交必失败，构建不报错 |
| 14 | 前后端校验是一对：改字段前先 `grep` 字段名，同步改后端必填校验 | 界面看着对、点下去报 400 |
| 15 | **服务一律用户自己启动**（MySQL/Redis/dev server/后端 18989），我只做只读探测（`netstat`/`tasklist`/`curl --noproxy '*'`），不调 `run-server.sh`；需重启只给命令 | 脚本起的实例占住 18989 = 用户点 Run 报端口占用；交还启动权时必须**显式 taskkill** 旧进程 |
| 16 | **同一文件的多处 `Edit` 必须串行**（并行发出会读-改-写互相覆盖，报成功但内容消失） | 本轮 `AdminLayout.vue` 的 import 就这样丢了，直到 vue-tsc 报 `Cannot find name 'MessageOutlined'` |
| 17 | **MyBatis-Plus `updateById` 默认忽略 `null` 字段**，要清空某列必须用 `LambdaUpdateWrapper.set(field, null)` 显式写 NULL | 以为「把字段设 null 再 updateById」能清空 → 数据库里值原封不动，界面与库不一致却毫无报错 |
| 18 | 管理端专属能力**必须后端独立断言角色**（`SecurityUtil.requireRole("ADMIN")`），前端隐藏按钮只算「不给入口」 | 医生拿自己的 token 直接 POST 就能执行管理员动作 |
| 19 | **DeepSeek 模型名以 `/models` 返回的为准**：`deepseek-flash`（= DeepSeek-V4.1-Flash）、`deepseek-v4-pro`。`deepseek-v4-flash` 能调通但是**别名**；`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 退役 | 用退役名调用**直接失败、无回退**。靠记忆写模型名必错，**先 `curl /models` 再写配置**（`.workbuddy/scripts/llm_probe.py`） |
| 20 | **DeepSeek 没有 embedding 接口**，向量化必须用第二家（本项目定通义，可切 bge-m3） | 凭记忆写「用 DeepSeek 做 embedding」→ 架构返工。这也要求 `EmbeddingClient` 从第一天就是接口 |
| 21 | AI 提示词的**固定前缀（人格/边界/输出/流程）严禁含时间戳、id、随机值** | DeepSeek 自动上下文缓存靠前缀字节级一致，写进去 100% 不命中，**不报错只在账单上体现** |
| 22 | **推理模型的 `max_tokens` 是「思考 + 正文」共享预算**。DeepSeek-V4 先流 `reasoning_content` 再流 `content`，预算被思考吃光时**正文为空**，而 `finish_reason` 仍是 `length`（看不出异常） | 前端表现为「AI 不回话」，日志看着像正常结束。判据只能是**本次实际产出的正文长度**。默认给足 8192 |
| 23 | **SSE 事件契约改动必须前后端同改**：后端发 `event:` 行，前端只认 `data:` 的话会把 `error` 事件的 JSON 当正文拼进气泡（用户在聊天里看到 `{"code":...}`） | 契约见 `SSE事件协议.md`。保留「无 `event:` 按 token」兜底以便分批上线 |
| 24 | **LLM 密钥直写 `application.yml` 的 `psycare.llm.api-key`**（用户 2026-09-29 决策），**不搞 `${LLM_API_KEY:}` 占位符**。覆盖靠 Spring Boot 宽松绑定：`PSYCARE_LLM_API_KEY` / `-DPSYCARE_LLM_API_KEY=` / `--psycare.llm.api-key=`，优先级高于 yml，**无需在 yml 里写占位符接收** | 占位符方案忘配环境变量 = 启动正常、接口全 200、用户发消息才发现 AI 不回话。另：`apiKey` 字段必须 `@ToString.Exclude`（`@Data` 的 toString 会打印明文），读取必须走 `resolvedApiKey()`（**trim**，yml 引号会保留尾随空格 → Bearer 头带空格 → 401 且报错只说「鉴权失败」） |
| 25 | **OpenAI 兼容 API 里 `models` 与 `chat` 是同级资源**：models 路径 = `path` 去掉末尾**两**段（资源段+动作段）再接 `/models`。`/chat/completions`→`/models`（base-url 已含 /v1）、`/v1/chat/completions`→`/v1/models` | 只去一段会推出 `/chat/models` → **404**，且报错只说「检查 base-url/path/models-path」，看着像整条地址配错。**更硬的教训：推导逻辑必须用自己产物跑一遍验证** —— 这次是用 Python 探针（`/v1/models` 写死）验的连通性，Java 侧推导从没被验过，导致启动探活第一次真跑就炸。另 base-url 必须去**尾斜杠**，否则拼出 `//chat/completions` |
| 26 | **服务端改写了回复内容，就必须发 `override` 事件让前端整体覆盖**，且落库用同一个值 | 输出侧危机词替换（词表含「自杀」，而 prompt 要求模型建议拨热线 → 合规回答必命中）会造成「用户看到的」≠「库里存的」，属**合规问题** |
| 27 | **累计落库内容要接在「下发给前端的事件」上，不要接在上游 chunk 上** | 接上游则降级话术/兜底文案不入库，事后审计读到一段用户从未看过的对话 |
| 28 | **AI 阶段机必须是纯函数**（`StageRules.advance(prev, input)` 不读 Redis/不查库/不看时钟），在线推进与 Redis 丢失后的**重放重建共用同一个它** | 两条路径各写一份判定，差异不报错，只表现为「偶尔跳到别的阶段」—— 最难复现、最难被相信的 bug |
| 29 | ⚠️ **顺序陷阱：先 `stageStore.load()` 再落库本轮用户消息** | 重放会把库里已有消息全算进去；先落库再 load，重放已推进一次 + 调用方再 `advance` 一次 = **推进两次**。只在 Redis 冷启动 / TTL 到期时出现，有状态时完全看不出 |
| 30 | **`turnsInStage` 必须与 `turns` 分开计数**（跨阶段时归零），且 `decide` 每轮**最多进一级** | 用总轮数做阈值 → 阶段一路冲到结尾，五阶段退化成一次性问答；不限制每轮一级 → 一轮连跳两阶段，指示条闪一下就到尾 |
| 31 | **阶段只由服务端给，前端禁止本地推进**；`AiSessionDetailVO` **必须**带 `stage`/`stageLabel`/`stageStep`，而会话**列表**接口**故意不返回** `stage` | 前端本地 `++` 在「换话题回退」「Redis 重放」两种情况下与服务端漂移且不报错；明细不带阶段 → 切历史会话时指示条停在「建立联结」直到再发一条才纠正；列表带阶段 → 30 条列表变 30 次 Redis 查询/重放 |
| 32 | **`FIELD_WORDS` 这类关键词词表只能靠真实语料回归**，不能凭想（自检已抓到漏词：「个月」） | 漏词**不报错**，只让阶段推进比预期慢，表现为「AI 怎么老在一个阶段打转」 |
| 33 | ⚠️ **危机检测必须两套判据，输入侧与输出侧口径相反**：`CrisisDetector.hit()` 判居民输入（出现危机词即命中，**宁宽**）；`CrisisDetector.harmful()` 判模型输出（危机词 **+** 方法/剂量类操作性词在 ±10 字窗口共现，**宁窄**）。**输出侧绝不能用 `hit()`** | L2 第 3 条**要求**模型在风险语境下建议拨打 12356、L4 第 4 阶段要求它询问自伤念头 —— 用「出现危机词」判输出，每一段合规回复都会被替换成固定危机文案，用户看到「聊得好好的 AI 突然说我已为你转接人工」。这是 2026-09-29 的真实线上故障 |
| 34 | **危机态由服务端显式下发**（SSE `crisis` 事件，带 `reason=INPUT/OUTPUT`），**前端禁止用关键词/子串判断** | 历史包袱 `CRISIS_MARK = '12356'`（「正文含热线号码即危机」）是同一类错误：12356 正是 prompt 要求模型提供的。危机与否只有服务端知道，前端不该反推 |
| 35 | **两条危机路径文案必须分叉**：`INPUT`（真建 CRISIS 工单）用 `crisisResponse()` 可承诺「已为你转接人工」；`OUTPUT`（模型失言被护栏拦下，**不建工单**）用 `safetyNotice()` **不承诺** | 凭模型一次失言就给居民建危机工单、贴危机标签是另一种伤害；而没有工单却承诺「已转接」＝一句不会兑现的话，在心理场景里比不承诺更伤人 |
| 36 | ⚠️ **`LocalDateTime` 进 Redis 必须显式注册 `JavaTimeModule`**（`JsonMapper.builder().addModule(new JavaTimeModule())`，不用裸 `new ObjectMapper()`） | 裸 mapper 序列化 `LocalDateTime` 抛异常，而 `StageStore.save` 把它 warn 吞掉 → **阶段状态永远写不进 Redis**，每轮退化为全量重放。功能看起来是对的（重放等价），所以只体现在日志 `回看=N条` 从 0 逐轮 +1 上。见硬约定 #37 |
| 37 | **「功能看起来是对的」不是验收标准**：凡是「吞异常 + 有等价兜底路径」的组合，都必须在日志里留下**可核对的痕迹**（如 `回看=N条`），否则缺陷会静默存活 | 本轮 `StageStore` 的序列化 bug 就是这样活下来的 —— 代码评审、编译、接口自检全绿 |
| 38 | **先落库用户消息、再组装上下文时，`loadHistory` 必须排除本轮那条**（`ne(id, currentUserMsgId)`） | 不排除则 prompt 里本轮输入出现两次（历史一条 + L8 当前输入一条），模型倾向合并作答，且白付一份 token。`Context.history` 的注释一直写「不含本轮」，实现曾与注释不符 |
| 39 | **面向用户的文案不得含内部标签**（风险等级、评分公式细节）。内部评分依据进 `recommendation_log`，对外**另写一份**（`RecommendationEngine` 的 `reason` / `humanReason` 双份） | 「风险等级A→科室[全科]；维度[抑郁]与专长匹配」这类字符串会直接显示在侧栏与推荐页，违反「不向居民透露系统评级」的产品约定。同理：注入给模型的医生名单**只给**姓名/科室/职称/擅长方向，不给内部 reason —— 否则模型会原样念出来 |
| 40 | **能直接算出来的上下文就别让模型自己查**：医生推荐已经由 `RecommendService` 算好，注入 L5b 即可（零额外调用）；真正的工具调用留给 Phase 5 | 不注入 → 模型只能说「建议你去看看医生」，用户拿不到姓名与理由。注入位置必须在 L1–L4 常量**之后**，且块内要写清**使用时机**（否则模型第一轮就把医生全抖出来，五阶段引导作废） |
| 41 | **复用已有服务方法当「素材来源」前，先看它有没有写库副作用**：AI 上下文注入必须走 **只算不记** 入口（`RecommendService.recommendForContext` → `RecommendationEngine.recommend(..., record=false)`），不要用面向用户那个入口 | `RecommendationEngine.recommend()` 会给每位医生写一条 `recommendation_log`；对话每轮注入 = 每轮多 3 行，几轮就把「推荐了什么、有没有被接受」的统计口径稀释掉。**侧栏/推荐页那次才是真实推荐事件，必须记** |
| 42 | ⚠️ **阶段推进发生在「用户发言之后、组装提示词之前」**，所以「用户第 N 条之后的阶段」＝「模型第 N 次回复所处的阶段」。**`OPENING` 的推进阈值必须 >= 2** | 写 `>= 1` → 模型**第一次回复就直接落在主诉澄清**，`OPENING`（共情、确认来意）永远轮不到一次回复，三个阶段里有一段空转。**不报错、不崩，只是少一句共情，只有断言抓得到**（本轮真踩） |
| 43 | **AI 应允类判定必须「一问一答」且否定优先**：`ReferralRules.accepted` 要同时拿到「AI 上一轮问过」（`askedForReferral`）与「用户给肯定」（`YES_WORDS`）；`NO_WORDS` 必须**先**判。前端只看得到后半 | 前端看不到上一轮 AI 原文 → 任何一轮的「好的」都长得像应允（可能只是在回答「最近睡得怎么样」）。中文否定与肯定**共享字形**（「不用」含「用」、「不要」含「要」、「不好」含「好」）→ 不先判否定，「不用了谢谢」会被读成应允，动画弹出、会话被关，用户一脸茫然 |
| 44 | **动作信号（`recommend` / `crisis`）在事件回调里只记标记**，真正的动作（动画 → 关会话 → 跳转）放到 `chat.send()` **返回之后** | 回调里 `await` 会**阻塞后续 SSE 帧解析**，遮罩还会盖住**还在流的字**。`recommend` 必须**全部 `token` 之后、`done` 之前**下发，且与 `crisis(OUTPUT)`/`override` **互斥**（模型刚被护栏拦下时顺势推进推荐流程 = 用正常流程掩盖一次安全事件） |
| 45 | **跨文档给新工作起「Phase N」编号前，先去源头（`AI助手开发计划.md`）核对那个编号有没有被占用**；增量就记作 `N.5`，不要挤占后续编号 | 本轮曾把 `开发文档.md`/`SSE事件协议.md` 的 5 处写成「Phase 3」，而计划文档的 Phase 3 = RAG 离线管道 → 同一文档集里两个「Phase 3」。改名只花几条 Edit，但**编号错会让后来人按错的里程碑排期** |
| 46 | **UI 列数不要写死**：阶段指示条用 `grid-template-columns: repeat(${STAGE_FLOW.length}, minmax(0, 1fr))` 内联生成；`stageTotal` 恒由服务端给 | 写死 `repeat(5, ...)` 在阶段压到 3 段时，三个点会挤在左侧三分之一。阶段数已经变过一次（5→3），**同一个坑不要踩两次** |
| 47 | **一页里的按钮只留一套骨架**：手写按钮统一走 `.ghost-btn` + 变体（`.is-primary` 浅底 / `.is-solid` 实心主色），不要再混 antd `a-button`。手写按钮**必须写 `font-family: inherit`** | 原生 `<button>` 不继承 body 字体 → 落回系统 UI 字体，与页面字体不一致，两个按钮并排能看出两套字体。实心变体的 hover **必须提高特异性覆盖 `.ghost-btn:hover`**（它把文字改成 `--primary`，在实心底上不可读） |
| 48 | **CSS 变量名拼错是静默失效**：颜色/阴影会直接落回默认值，不报错、类型检查也不管。改完用脚本核对组件里用到的 `var(--x)` 都在 `styles/*.css` 有定义 | 与 antd token 名写错（约定 #5）是同一类坑：**唯一能抓到的手段是自己扫一遍**，`vue-tsc` 帮不上 |
| 49 | **新增颜色/阴影一律先进 `tokens.css` 再引用**，组件内不写 `rgba(...)` / `#xxxxxx`（含主色投影） | 组件里写 `rgba(37,99,235,.3)` 之类会绕过「颜色唯一来源」，改主色时漏掉它 → 出现第二套色阶。这也是 `--on-primary` / `--shadow-primary` 存在的理由 |
| 50 | **全局 store 里的数据活多久，由产品需求决定，不由页面生命周期决定**：AI 问诊是「一个居民一段连续对话」，所以 `AiChat.vue` **不能**有 `onUnmounted` 清理，也不能在任何"离开页面"的时机 `reset()`；代价是**登出必须清**（`stores/auth.ts` 的 `logout()` 里 `useChatStore().reset()`，这是防换账号串记录的唯一防线） | 上一版的方向正好相反（离开即 `reset()`，好让"点菜单进来=新会话"），用户要的却是"记录一直显示"。**store 是全局单例、不随路由销毁 —— 这个性质在本需求里被来回改过两次方向，动手前先确认产品要哪一种**。「凡新建实体先清视图」这条仍然成立（`open()` 内调 `clearConversation()`） |
| 51 | **「开一条新记录」的接口要么真新建、要么真幂等，别让调用方猜**：`openSession` 现在是**幂等**的（复用最近一条 `OPEN` 会话），所以前端 `open()` 的真实语义是「确保有一段可聊的对话」。**但 `Result.vue` 跳转前的 `chat.reset()` 不能删** —— 它把 `sessionId` 清成 `null`，否则 `open()` 早返回，这次测评关联不上 | 前端若仍按"调一次 = 拿一条新会话 id"来写，「做完测评来问诊」会复用旧会话且不更新 `assessment_id` —— **不报错**，只是右栏的「关联测评」卡一直空着、用户觉得 AI 没读他的测评 |
| 52 | **需要密码哈希的种子 / 演示数据，只能从已有账号「搬运」BCrypt 密文，不要在 SQL 里硬编码**：`SET @pwd = (SELECT password FROM sys_user WHERE username = 'doctor_li' AND is_deleted = 0 LIMIT 1)` | BCrypt 不可逆，SQL 生成不出来；硬编码一份哈希虽然也能登录，但它与实际密码的对应关系从此无人知晓。**代价：脚本依赖被搬运的账号存在**（`doctor_li` / `user01`），必须先启动过一次后端，否则 `@pwd` 为 NULL，直接报 `Column 'password' cannot be null` |
| 53 | **分组展示的顺序必须显式排序，不能继承 SQL 的 `ORDER BY`**：`doctorCatalog()` 改为 ① 有进行中会话 ② 医生数降序 ③ 名称升序 | 继承 SQL 顺序时，「科室顺序」实际由「该科室最高评分」隐式决定 —— 加一个医生、调一次评分，整个科室就挪位。3 个科室时看不出来，10 个科室就是「每次顺序都可能不一样」。同类：#46（UI 列数不写死） |
| 54 | **写一次性脚本前先 `ls .workbuddy/scripts/`** —— `run_sql_file.py`（执行 SQL）、`dump_table.py`（表级备份）、`redis_probe.py`、`llm_probe.py` 都已存在 | 本轮手写了一份「读 SQL 文件按分号执行」的临时脚本，写完才想起 `run_sql_file.py` 正是干这个的（还多支持 `--dry-run` 与 `--db` 空库演练）—— 白写 60 行，临时脚本还得删。测试资产表存在的意义就是避免这件事 |
| 55 | **一个「聊天 / 对话」类功能只留一个容器**：居民端任何新增的「去聊天」按钮，实现方式必须只是 `router.push('/messages/:doctorId')`，**不要再写抽屉** | 多入口各自实现抽屉会分叉成 N 套 DOM + N 套样式（本项目曾有三套：医生咨询 / AI 推荐结果 / 我的消息），连标题栏显示的信息都不一致；420px 宽也让正常长度的话频繁折行。**判断标准：入口页里出现 `ChatPanel` 就是错的方向** |
| 56 | **入口页不预建资源**：跳转前**不**调 `openSession`，会话由目标页调幂等接口建或取 | 「点了但没进去」（用户中途返回、页面被刷掉、组件挂载失败）会在库里留下没有消息的空会话。它不会显示在「我的消息」里（SQL 有 `EXISTS` 过滤），所以**不会被发现，只会慢慢积累**。同理适用于任何「打开即创建」的资源 |
| 57 | **按视角分裂的下发字段，在 Service 里显式置 `null`，不要依赖「SQL 没查就不填」**：`ChatSessionVO` 的 `peer*` 族是下发字段，`doctor*` 原文列带 `@JsonIgnore` | 医生视角不填 `peerDepartment` 也能得到 null —— 但那只在「SQL 恰好没 join」时成立。哪天 SQL 改成 `INNER JOIN` 或补一列，不该下发的字段就会悄悄漏出去，且**没有任何测试会失败**。显式置 null 让「视角规则」集中在一个 `if` 里 |
| 58 | **组件要适配不同父容器宽度时用 `@container`，不用 `@media`**：`ChatPanel` 加了 `container-type: inline-size`，420px 以下隐藏头像 | `@media` 看的是**视口**：医生端抽屉在 1280px 视口下只有 420px 宽，但 `@media (max-width: 440px)` 永不触发。`ChatPanel` 同时被 940px 的整页卡片与 420px 的抽屉使用，只有容器查询能同时照顾两侧（不支持时退化为「一直显示头像」，不致命） |
| 59 | **「已读/未读」与「待回复」是两个信号，不许合并**：红点/角标用 `unreadCount > 0`（看过即灭），待回复用 `awaitingMyReply`（回了才灭） | 合并会让红点在读过之后一直亮 —— 用户为了灭掉它只能回一句废话。写这类判断时先问一句「这个标记表达的是『有我没看的』还是『有我欠的』」 |
| 60 | **两端（医生端/居民端）共有的 UI 元素必须抽成组件 + 单一数据源**：红点走 `components/UnreadBadge.vue` + `stores/inbox.ts`（服务端 `/chat/unread`），样式只留一份 | 需求常写成「与 X 端现有实现完全一致」。靠"两边照着写一遍"做不到：颜色/尺寸/触发条件任一处改了另一处不会跟着变，且**不报错，只会长期挂在界面上**。同理，数字由服务端算，别让两端各自 `filter().length`（它们读的接口本来就不同） |
| 61 | **增量轮询拿不到「既有行被更新」，必须周期性全量同步**：`ChatPanel` 每 4 轮（20s）做一次 `loadAll(silent)` | `sinceId` 只回新消息，而「对方把我的消息读掉了」是对既有行的 `read_at` 更新，增量永远取不到 → 已读状态只有自己这侧会动。**配套：全量刷新前必须先判 `nearBottom()`**，否则用户正在往上翻记录时会被拽回底部；静默刷新也不置 `loading`（会每 20s 闪一次） |
| 62 | **标记已读是显式 POST，且只在页面可见时调**：`POST /chat/sessions/{id}/read`，前置条件为 ① `!document.hidden` ② 确有未读 ③ 不在标记中 | 让 GET 消息顺带标已读 → 轮询/预取/重试都会带着写副作用，未读数会以不可预期的方式掉下去（用户看到「没打开对话，红点却自己灭了」）。接口**返回标记后的汇总**，省一次往返，也就没有「已读但红点还亮」的闪动 |

## 二、本机环境坑

| 现象 | 解法 |
|---|---|
| `vite build` 报 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` / `rm` 挂住 | safe-delete shim 拦批量删除（>50 文件）。**`mv dist <临时目录>`**，重命名不算删除 |
| 删 `dist/`、`node_modules/.vite`：`rm -rf` 被挂起、PowerShell `Remove-Item -Recurse` 静默失败 | `[System.IO.Directory]::Delete($p, $true)` |
| 无头 Chrome 截图零产物零报错 | `--screenshot` 要 **Windows 原生路径** `C:\...\x.png` |
| 浏览器连不上 dev server | vite 默认只绑 `[::1]`；有 HTTP 代理 → `npm run dev -- --host 127.0.0.1` + Chrome `--no-proxy-server`、curl `--noproxy '*'` |
| Maven 找不到 classworlds Launcher | PATH 上是 JRE1.8 + Git Bash 传 POSIX 路径 → JDK17 + classworlds launcher（见 `maven-compile-win` skill） |

## 三、测试资产

**账号**：`user01/user123`(RESIDENT)、`doctor_li|wang|zhao/doctor123`、`admin/admin123`(SUPER)

| 脚本（`.workbuddy/scripts/`） | 作用 |
|---|---|
| `e2e_api_check.py` | 32 项接口断言（**Phase 7/8 新接口尚未并入**） |
| `e2e_dashboard_db_check.py` | 驾驶舱数字 × 直连 MySQL 交叉核对（需 venv+pymysql） |
| `rehearse_role_split.py` / `apply_role_split.py` | 克隆库演练 / 真库应用角色拆分迁移 |
| `check_ref_value.py` | 扫 `.value` 漏写（7650 文件 0 处） |
| `check_form_fields.py` | 扫表单三处不一致（32 个 .vue 全绿） |
| `cdp-shot.mjs` | 交互后 UI 截图（登录态→导航→按文本点击→截图+innerText）。**必须配 dev server（有 /api 代理），不能用 preview**；antd 两字按钮 DOM 是「审 核」需去空白匹配；`--after <ms>` 控最后一次点击后等待；**Git Bash 下必须先 `export MSYS_NO_PATHCONV=1`**，否则 `--path /admin/x` 被转成 `C:/Program Files/Git/admin/x` |
| `run-server.sh` | 命令行重启后端（JDK17 直调 java，需先 compile 并生成 `target/cp.txt`） |
| `dump_table.py` | 表级备份（`dump_table.py consult_message --tag before-v4`）。**本机 PATH 上没有 `mysqldump`**，删改数据前用它 |
| `run_sql_file.py` | 执行 `.sql` 脚本（按分号切分；`--dry-run` 先看切分）。**本机 PATH 上没有 `mysql` 客户端**。`--db psycare_ddl_check` = 在一次性空库**演练**（把脚本里的 `USE psycare;` 改写到目标库），符合「先演练后上真库」的约定 |
| `redis_probe.py` | 零依赖 Redis 探针（直连 RESP；`--keys` 用 `SCAN` 统计前缀，不用 `KEYS`） |
| `llm_probe.py` | DeepSeek/OpenAI 兼容接口探针（零依赖）：模型列表、**流式三个关键时刻**（首个任意帧/首个思考帧/首个 content 帧）、故障注入（错 key→401、错模型名→400）。`--max-tokens` 必调大，否则推理预算被思考吃光量不出 TTFT。**Key 走环境变量 `LLM_API_KEY`，不写进文件** |

Python 隔离环境：`C:\Users\Lenovo\.workbuddy\binaries\python\envs\default`（含 pymysql）

## 四、待办（非阻塞）

| 缺口 | 成本 |
|---|---|
| `AccountManage` 操作列在 ≤1280 视口被 `.table-wrap{overflow:hidden}` 裁掉（「删除」在视口外，弹层锚点贴边→表现为提示乱跑） | 小：`.table-wrap` 加横向滚动或收窄操作列 |
| `e2e_api_check.py` 未覆盖 Phase 7/8 新接口 | 小 |
| `scale_item` 无 `dimension` 列（结果页维度条无数据源）／「辖区内工单」未实现／PSS-10 无种子数据 | 中 / 中 / 小 |

> ✅ AI 模块 Phase 0 已落地（2026-09-29）：会话归属校验（越权 403）、`doFinally` 落库、
> `scaleName` 修真、`MethodArgumentTypeMismatchException` → 400、Redis 探针，
> 外加 `consult_message.finish_reason` 列。详见 `开发文档.md` 第十二章。
>
> ✅ AI 模块 Phase 1 已落地（2026-09-29）：`LlmProperties`/`OpenAiCompatClient`/`LlmErrorMapper`/
> `LlmChunk`/`LlmUsage`/`LlmProbeResult`/`LlmStartupProbe`/`AiUsageRecorder` + `sql/v5_observability_ddl.sql`
> （**已上真库**）+ 前端三文件。详见 `开发文档.md` 第十三章与 `SSE事件协议.md`。
>
> ✅ AI 模块 Phase 2 已落地（2026-09-29）：`PromptAssets`（L1–L4 前缀）/`InterviewStage`/`StageRules`（纯函数）/
> `StageState`/`StageStore`（Redis + 重放）/`ContextAssembler`/`TranscriptSummarizer` +
> `ConsultSessionMapper(.xml)` + 3 个 DTO + 2 个 GET 接口 + 前端 `STAGE_FLOW`/抽屉/阶段条。
> **删除 `PromptBuilder`**。**零 DDL**，无新外部依赖。详见 `开发文档.md` 第十四章。
> 自检 35 项断言全 PASS（临时脚本已删）；`vendor-antd` 790.77 kB **未变**（阶段条与抽屉全手写 CSS）。
>
> ✅ AI 模块 **Phase 2.5** 已落地（2026-09-29）：`InterviewStage`（3 段）/`StageRules`（`KEY_FIELDS` 7 项 +
> `MAX_QUESTIONS = 3`）/`ReferralRules`（一问一答）`/DepartmentVO` + `ChatMapper.selectDoctorCatalog` +
> `ChatService.doctorCatalog` + `GET /chat/doctor-catalog`；前端新增 `AiRecommendResult.vue`(`/ai-recommend`)、
> `DoctorConsult.vue`(`/doctors`)，**删除 `DoctorRecommend.vue`**，`STAGE_FLOW` 改 3 项 + `STAGE_TOTAL`。
> **零 DDL、零新增 antd 组件**（动画全手写 CSS，`vendor-antd` 未变）。详见 `开发文档.md` 第十六章。
> 自检 **47 项断言全 PASS**（首轮 2 项 FAIL 抓到真缺陷，见硬约定 #42）。
>
> ⚠️ **AI 模块每次改动都需重启后端**：新路由/新 bean/新 yml 只在启动时加载；
> 类与 MyBatis XML 同理（见硬约定 #7）。
> ⚠️ 密钥直写 `application.yml` 的 `psycare.llm.api-key`（不再是环境变量，见硬约定 #24）。
>
> ⏸ **AI Phase 3（RAG 离线）阻塞在外部依赖**，需要用户先提供：
> ① 通义 embedding 的**模型名 + Key**；② 本机 **Milvus 服务**（用户启动，我只做只读探测）。
> Milvus 起来后**先跑 `milvus_probe` 四断言**（连接/建集合/读写回 / **中文 BM25 召回**），**不过则停止后续开发**。

## 五、文档地图

| 文档 | 内容 |
|---|---|
| `.workbuddy/memory/决策与细节.md` | **必读展开卷**：架构/账号、前端路由、数据业务规则、医患聊天、打包体积、协作习惯的完整决策记录与踩坑。改设计前先读 |
| `.workbuddy/memory/YYYY-MM-DD.md` | 每日工作日志（append-only，最近：2026-09-24 / 09-28 / 09-29） |
| `开发文档.md` | 契约主文档：token 表、接口契约、数据库、缺口总表、Phase 0–8.3 记录、风险清单（R-1~**R-13**）、**第十二 / 十三 / 十四章 = AI Phase 0 / 1 / 2**、**第十五章 = Fix-1 危机误判修复**、**第十六章 = AI Phase 2.5 问诊提效与推荐闭环 + Fix-2 会话模型 + 16.9 医患聊天专有页** |
| `SSE事件协议.md` | **前后端流式契约**：8 类事件（`meta`/`token`/`usage`/`override`/`crisis`/**`recommend`**/`error`/`done`）；`meta` 带 5 个阶段字段（`stageTotal` 恒 **3**）、`crisis` 带 `reason`、**第四节第 3 条讲清输出侧判据为什么不能用「出现危机词」**；第七节 = 两个会话读取接口；**18 条故障注入**（16–18 条是推荐闭环）。**改 `/ai-consult/chat` 必读** |
| `部署说明.md` | 构建、nginx（含 SSE）、环境变量、排查（含 AI 阶段状态丢失 / **记录丢失或每进一次多一条会话 / 换账号串记录 / 答应推荐却没跳转 / 科室页空 / 聊天页标题栏空 / 空会话堆积**）、上线清单 |
| `角色拆分与账号治理设计.md` | 四项改造设计（已实施），配套 `sql/v2_role_split_ddl.sql`、`sql/v3_role_split_migration.sql` |
| `AI助手开发计划.md` | **AI 模块落地计划（2026-09-29 定稿）**：DeepSeek-V4 生成 + Milvus 2.6 检索 + 通义向量化。整体架构/模块划分/技术选型/AI 助手详细设计（提示词分层、引导问诊、RAG 离线+在线、SSE 事件协议、四道防线、工具与 MCP、观测成本）/Phase 0–6 里程碑（**另有追加的 Phase 2.5**）/风险登记册/交付物清单。**动 AI 模块前必读**；**给新工作起 Phase 编号前也要先读它**（见硬约定 #45） |
| `AI问诊改造设计.md` | AI 模块**现状评估基线（约 45%）**+ 早期改造设计。其中「通义千问接入」「MySQL 存向量」两节已被上面的计划取代；现状评估与验证方式仍有效 |
| `DESIGN.md` | 设计稿原始说明 |

> ⚠️ 本文件刻意只保留「必须记住的硬约定」。任何"为什么这样设计"的问题 → 查 `决策与细节.md`，不要凭记忆推断。
