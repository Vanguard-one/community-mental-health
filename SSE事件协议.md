# SSE 事件协议（前后端契约）

> `POST /api/ai-consult/chat` 是 PsyCare 全项目**唯一**的流式接口，也是前后端耦合最紧的一处：
> 后端多写一个事件名、前端少解析一个 `event:`，都不会编译报错，只会在用户屏幕上表现为
> 「AI 不回话」或「聊天里冒出一段 JSON」。所以这份契约单独成文，改动必须两端同步。

- **适用版本**：Phase 2 + Fix-1 + **Phase 2.5** + **Phase 4**（在线检索与引用，2026-10-09）
- **后端**：`AiConsultController#chat` 返回 `Flux<ServerSentEvent<String>>`
- **前端**：`psycare-web/src/api/consult.ts` 的 `chatStream()`
- **Phase 2 增量**：`meta` 事件新增 5 个阶段字段（事件类型不变，向后兼容）；新增两个非 SSE 的会话读取接口（见第七节）
- **Fix-1 增量**：新增 `crisis` 事件；`override` 的触发条件由「命中危机词」收窄为「命中安全护栏」（见第四.3 节）
- **Phase 2.5 增量**：新增 `recommend` 事件（用户同意接受医生推荐）；**阶段由 5 段压到 3 段**（`stageTotal` 由 `5` 变 `3`，`FUNCTION_IMPACT` / `RISK_SCREENING` 两个枚举值**已删除**）
- **Phase 4 增量**：新增 `citations` 事件（RAG 引用卡片，`meta` 之后、`token` 之前）；`AiConsultService` 接入 `RetrievalPipeline`（此前检索层虽已建好但**未接线**，L6 恒走「无资料」分支）
- **第 16 批增量（2026-10-22）**：新增 `followups` 事件（动态追问建议，**`done` 之后**）；**`done` 的语义从「末尾」改为「正文结束」**（见一、二节）；`ai_usage_log` 增加 `kind` 列区分 `CHAT` / `SUMMARY` / `FOLLOWUP` 三类调用成本（迁移脚本 `sql/v13_ai_usage_kind.sql`）

---

## 一、事件清单

| event | data | 时机 | 前端行为 |
|---|---|---|---|
| `meta` | `{"sessionId":12,"userMessageId":88,"traceId":"a1b2…","model":"deepseek-flash","stage":"CHIEF_COMPLAINT","stageLabel":"主诉澄清","stageStep":2,"stageTotal":3,"stageGoal":"围绕困扰追问一到两个关键点，把程度与对生活的影响问清楚"}` | **首个事件**，一定先到 | 记录 `traceId`（排障时对齐服务端日志）；用 `stage*` 五个字段驱动阶段指示条（见下节） |
| `token` | 增量正文（**纯文本，不是 JSON**） | 生成过程中，0~N 次 | 追加到当前气泡 |
| `usage` | `{"promptTokens":57,"completionTokens":337,"cachedTokens":0,"reasoningTokens":14,"totalTokens":394}` | 正常结束前，通常 1 次 | 存进气泡的 `usage`（调试面板）；`cachedTokens` 是成本指标，`reasoningTokens` 是推理模型的隐性开销 |
| `override` | **服务端改写后的最终正文**（纯文本） | 正文被安全护栏拦下而替换时 | **整体覆盖**气泡内容，不是追加 |
| `crisis` | `{"reason":"INPUT"}` | 判定为危机时（`token` 之前） | 气泡转危机态；`reason` 决定侧栏能不能承诺「已为你转接人工」（见下节） |
| `recommend` | `{"sessionId":12}` | 用户**同意**接受医生推荐时（**`token` 之后、`done` 之前**） | 本轮回复结束后播「AI 正在分析」动画 → 结束会话 → 跳 `/ai-recommend?sessionId=12` |
| `citations` | `{"items":[{"chunkId":14,"docId":1,"docTitle":"爬虫知识","section":null,"snippet":"我用Python写过基础的数据采集爬虫…","score":0.032,"denseScore":0.71,"synthetic":false}]}` | **`meta` 之后、第一个 `token` 之前**；**没有资料时整个事件不出现** | 挂到本轮 AI 气泡上，在正文下方渲染「参考资料」列表；`synthetic=true` 必须打「合成示例」角标 |
| `error` | `{"code":"LLM_RATE_LIMITED","msg":"模型服务繁忙，请稍后重试","retryable":true}` | 任何阶段出错 | 气泡内联错误条；`retryable` 为真时显示「重试」 |
| `done` | `[DONE]` | **正文结束**，正常路径必有 | 结束 `streaming` 态、**解锁输入框**（不要等流关闭，见下） |
| `followups` | `{"items":["能给我一个今晚就能试的具体做法吗？","这种情况需要去看医生吗？"]}` | **`done` 之后**，且**本轮可能整个不出现** | 覆盖输入框上方的三条追问建议；条数 1~3，不足 3 条时视图按「有几条显示几条」渲染，多余槽位不留空块 |

> [!warning] `done` 不再是「最后一个事件」（2026-10-22 起）
> `followups` 是唯一排在 `done` **之后**的事件：它要额外调一次模型，
> 与正文生成是两件事。把 `done` 当「流结束」的旧写法会带来一个具体后果 ——
> 输入框要等追问建议生成完（1~2s）才解锁，用户已经读完回复却点不了发送。
>
> 因此前端**必须在 `done` 处解锁**，并用「消息数组长度」这类守卫丢弃晚到的旧事件（见四.4）。
> 反过来说，`followups` 也**绝不能**提到 `done` 之前 —— 它会把解锁时刻整体推迟。

> [!note] 为什么 `citations` 反而排在 `token` **前面**
> 引用卡片是「这条回复的依据」，用户开始读正文时就该看到它；
> 放到回复之后（比如跟 `usage` 一起）会出现「回复读完了，忽然从下面顶出一块引用框」，
> 观感上像后补的证据。而它在服务端**本来就是正文生成之前就确定的** ——
> 检索发生在组装提示词时，比第一个 token 早得多。
>
> **没有资料时后端不发这个事件**，而不是发 `{"items":[]}`：后者只会让前端多走一遍
> 空渲染分支。「没有资料」的载体就是「事件不出现」。

### 引用卡片：字段与边界

| 字段 | 说明 |
|---|---|
| `chunkId` / `docId` | 命中的子块与所属文档（与后端 `[RAG]` 日志对齐用） |
| `docTitle` | 文档标题；为空时前端显示「未命名资料」 |
| `section` | 章节路径。**纯文本粘贴的文档这里是空串**（没有标题层级可解析），前端须容忍 |
| `snippet` | 正文摘要，**最多 60 字**。引用卡是「证据」不是「内容」——想看全文去知识库页面 |
| `score` | RRF 融合分，**只用于排序，不是相似度**，前端不要拿它当「匹配度」展示 |
| `denseScore` | 稠密余弦相似度，**阈值过滤比的就是它**。为 `null` 表示这条由稀疏（关键词）路召回 |
| `synthetic` | 是否合成语料。`true` 时必须打角标（风险 R5） |

> [!warning] `denseScore` 为 null 不是「相似度为 0」
> 它表示这条是**稀疏（BM25）路单独命中**的 —— 向量路没看上，但关键词精确命中了。
> 这类命中恰恰是混合检索存在的理由（「PHQ-9 第 9 题」「舍曲林」这种查询），
> 前端不要把它渲染成「匹配度 0」。后端也不会因它为 null 而丢弃该条。

> [!note] 历史消息**不带引用**，这是有意的
> `consult_message` 只存正文，没存「当时引了哪几条资料」。前端恢复历史会话时
> `citations` 一律为空数组，而不是按当前语料反查补全 —— 补出来的是
> 「现在会命中什么」而非「当时用的是什么」，把后者展示成引用属于伪造证据。

### 危机信号：为什么必须由服务端显式下发

`crisis` 的存在是为了替掉一个**必然误判**的前端启发式。前端原先按
「气泡正文里出现 `12356` 就算危机」判断 —— 而 L2 第 3 条恰恰**要求**模型在风险语境下
建议拨打 12356。拿一个业务上**鼓励出现**的内容当危机标志，会把每一次合规的热线提示
都渲染成「聊得好好的，AI 突然变红说已为你转接人工」。危机与否只有服务端知道
（是否走了短路、是否发生了替换），就该由服务端说，前端不该反推。

| `reason` | 含义 | 是否建了 CRISIS 工单 | 前端可否承诺「已为你转接人工」 |
|---|---|---|---|
| `INPUT` | 居民自己表达了危机 → 输入侧短路，不调模型 | ✅ 已建 | ✅ 可以 |
| `OUTPUT` | 模型输出被安全护栏拦下 | ❌ 未建 | ❌ 不可以（见下） |

> [!warning] 没有工单就不能承诺转接
> `OUTPUT` 意味着「模型自己说错了话」，而居民的输入未必构成危机
> （例如他只是在问一个知识性问题）。凭模型的一次失言就给他建危机工单、
> 贴上危机标签，是另一种伤害。既然没有工单在流转，就不能说「已为你转接」：
> **一个不会兑现的承诺，在心理场景里比不承诺更伤人。**
> 所以两条路径的固定文案不同（`crisisResponse()` / `safetyNotice()`），
> 差异只有最后一句。前端按 `transferPromised` 决定渲染哪一版。

⚠️ **前端不要维护自己的危机词表或关键词标记**：两份词表必然漂移，
而这次漂移的代价是「正常对话被判成危机」。危机态只认 `crisis` 事件 +
历史消息里的 `crisisFlag`。

### 推荐信号：为什么同样必须由服务端下发

`recommend` 与 `crisis` 是同一类取舍，理由比 `crisis` 更硬一层：
**收到它就要结束会话**（不可逆）。

判据是「一问一答」，缺一半都不成立：

1. AI 上一轮**问过**「需要我为你推荐合适的医生吗？」（`ReferralRules.askedForReferral`）；
2. 用户本轮给了**肯定**答复（`ReferralRules.accepted`，且否定词优先）。

前端手上只有第 2 半 —— 任何一轮里的一句「好的」都长得像应允，
而它可能只是在回答「最近睡得怎么样」。**第 1 半只有服务端看得到**
（要回看上一轮 AI 回复的原文），所以判定必须在服务端。

> [!note] 「用户主动要医生」是例外，不必等 AI 先问
> 用户说「帮我推荐医生」时已明确表达诉求，再让他多答一轮「需要我推荐吗」是纯粹的官僚。
> 此时只看第 2 半。但仍有轮次下限（≥ 2 轮）：第一句就说要找医生时不该立刻跳走 ——
> 他还没说任何情况，AI 也还没回应过。

> [!warning] 前端不要把「收到 `recommend`」当成消息发出去
> 它是**动作信号**，不是内容。前端收到后只做一件事：记标记，等本轮 `done`
> 再播动画 → 结束会话 → 跳转。把动画直接挂在事件回调里，
> 会让遮罩盖住还在流的最后几个字。

### 阶段字段（Phase 2 引入，Phase 2.5 由 5 段压到 3 段）

| 字段 | 取值 | 用途 |
|---|---|---|
| `stage` | `OPENING` / `CHIEF_COMPLAINT` / `SUMMARY_REFERRAL` / `ENDED` | 阶段机内部名，前端用它比对 `STAGE_FLOW` 判断「已走过 / 当前」 |
| `stageLabel` | 中文名（如「主诉澄清」） | 指示条上的文字，**不在前端硬编码** |
| `stageStep` | 1 ~ 3（`ENDED` 时 4） | 指示条进度 |
| `stageTotal` | 恒 `3` | 前端不写死数字，列数按它生成 —— 阶段数变过一次，写死 5 的写法当时确实会错位 |
| `stageGoal` | 该阶段的一句话目标 | 悬停说明「为什么问这些」 |

**为什么塞在 `meta` 里而不是新开 `stage` 事件**：阶段是在请求开始**之前**就定下来的
（先推进阶段、再组装提示词），属于「本轮元信息」，与 `sessionId` / `model` 同类。
每新增一种事件类型，两端就多一处必须同步的契约；能不加就不加。
只有「流进行到一半才出现」的信息才值得独立事件（比如 `override` / `recommend`）。

> [!note] 阶段只增不减，但会「换话题回退」
> 正常路径每轮最多进一级；当用户已在「建议转介」阶段又抛出新主诉（命中换话题词）时，
> 会退回 `CHIEF_COMPLAINT` —— 这是**唯一**的倒退路径，
> 指示条要能正确往回走，不能只做单调递增的动画。

**前端不要在本地推进阶段**：`stage` 由服务端严格给出，前端只做展示。
自己在本地 `++` 会在「换话题回退」「Redis 丢失后重放」两种情况下与服务端漂移，
而这两种情况都不会报错，只会让指示条长期显示错误阶段。

> [!warning] Phase 2.5 删除的两个枚举值是**破坏性变更**
> `FUNCTION_IMPACT` 与 `RISK_SCREENING` 已从 `InterviewStage` 移除。
> 老前端若拿它们比对 `STAGE_FLOW`，只会匹配不到（显示为「都没走到」），不会报错 ——
> 但 Redis 里残留的旧值会被后端 `InterviewStage.of()` 解析成 `OPENING`，
> 方向是**保守**的：重新走一遍引导，而不是把用户直接扔进「建议转介」。
> 前端必须先于后端上线，或同时上线。

---

## 二、顺序约束

```
meta ──► (token | usage)* ──► (crisis)? ──► (override)? ──► (recommend)? ──► done ──► (followups)?
```

- `meta` 必须最先，且**一定**存在（含危机短路路径）。
- `token` / `usage` 交错出现，次数不限；两者都不出现也是合法的（见「三类收尾」的空回复）。
- `crisis` 最多一次，且**一定在产生它的 `token` / `override` 之前** ——
  前端收到它时立刻把当前气泡标成危机态，不必等正文到齐。
- `override` 最多一次，且**只在有正文时**出现。
- `recommend` 最多一次，且**一定在全部 `token` 之后、`done` 之前**。
  这个位置不是随便定的：前端收到它就会在「本轮回复结束」时弹全屏分析动画，
  提前下发会让动画盖住还在往外流的字。它与 `crisis(OUTPUT)` / `override` **互斥** ——
  模型刚被安全护栏拦下，此时顺势把人推进推荐流程，等于用一次正常流程掩盖一次安全事件。
- `error` 最多一次；出现时 `token` 仍会跟一条**降级话术**（见下）。
- `done` 是**正文结束**，不是流结束：它之后最多还有一帧 `followups`。
- `followups` 最多一次，**只在 `done` 之后**，且以下情况**整个不出现**（而不是发空数组）：
  正文为空、命中安全护栏被 `override`、本轮有 `error`、本轮已下发 `recommend`、
  危机短路（服务端不诱导继续追问）、生成超时（4s）或生成的条目全被安全词表拦掉。
  **前端必须有静态兜底。**

---

## 三、三类收尾（前端必须都能正确收尾）

| 场景 | 事件序列 | 落库 `finish_reason` |
|---|---|---|
| **正常** | `meta → token* → usage → done` | `DONE` |
| **截断**（整体超时 60s 被 `take()` 砍断） | `meta → token* → done` | `INTERRUPTED` |
| **降级**（上游 401/429/5xx/超时） | `meta → error → token(降级话术) → done` | `ERROR` |
| **空回复**（上游正常结束但零正文，如 `max_tokens` 被思考吃光） | `meta → token(兜底话术) → done` | `ERROR` |
| **输出护栏**（模型给出了可操作的自伤指引） | `meta → token* → crisis(OUTPUT) → override(安全文案) → done` | `CRISIS` |
| **危机短路**（用户输入命中危机词，不调模型） | `meta → crisis(INPUT) → token(危机文案) → done` | `CRISIS`（居民消息侧 `crisis_flag=1`） |
| **转介**（用户同意接受推荐） | `meta → token* → recommend → done` | `DONE`（前端接动画 → 结束会话 → 跳推荐页） |

> [!note] 表中每一条序列的 `done` 之后都可能再跟一帧 `followups`
> 具体会不会有，只看上面那条排除清单：**正常 / 截断**两种会带；**降级 / 空回复 /
> 输出护栏 / 危机短路 / 转介**五种不带。所以「收尾」的判定永远以 `done` 为准，
> 不要等 `followups` —— 它可能永远不来。

> [!warning] 「截断」在协议层看不出来 —— 前端拿不到它
> `take(Duration)` 到点会以**正常完成**收尾，信号类型上没有区别，
> 所以后端用「有没有收到上游的 `finish_reason`」自行判定并写进数据库；
> SSE 侧不额外发事件（多发一个 `truncated` 字段会让前端多一个必须处理的分支，
> 而它对用户没有可操作的意义）。截断率是**运维指标**，走 `consult_message.finish_reason`
> 与 `ai_usage_log`，不在用户面前体现。

---

## 四、六个必须遵守的实现约定

### 1. `error` 之后仍会有一条降级话术

错误事件的 `data` 只放**归类后的中文文案与错误码**，不放上游响应体
（上游 body 带 `request_id` 与内部细节，回显到浏览器只是增加信息泄露面；技术细节进日志与
`ai_usage_log.error_detail`）。用户看到的「一段能读的话」通过后续的 `token` 事件下发。

> 心理健康场景下，只回一个红字报错是不合格的：用户可能正处于危机中，
> 一句冷冰冰的失败提示会直接终结求助行为。因此**所有降级路径都带人工出口**
> （「我的消息」+ 全国心理援助热线 12356）。

### 2. `override` 必须整体覆盖，不能追加

服务端一旦判断「这段正文不能让用户看下去」（当前唯一来源是输出侧安全护栏命中），
就把整段替换为固定文案。但**原始文字已经流到浏览器了，撤不回来** ——
用 `override` 覆盖气泡，落库也用同一个值，「用户读到的」与「库里存的」由构造保证一致。

不这样做，事后审计会读到一段用户从未看过的对话。这是**合规问题，不是显示问题**。

### 3. ⚠️ 输出侧判据不能用「出现危机词」

这一条是**线上故障换来的**。原先输出侧与输入侧共用 `hit()`（「文本出现危机词」），
结果是：模型按 L2 第 3 条给出合规的热线建议 → 命中 → 整段被替换成危机文案并标红。
用户视角就是「聊得好好的，AI 突然说我已为你转接人工」。

两侧的目标其实是**相反**的，判据必须分开：

| | 检测对象 | 判据 | 口径 | 依据 |
|---|---|---|---|---|
| `hit` | 居民自己说的话 | 危机词出现 | **宁宽**：多提示一次热线只多一句废话，漏一次可能是一条命 | — |
| `harmful` | 模型生成的回复 | 危机词 **+** 操作性词（方法/步骤/剂量…）在同一短窗口共现 | **宁窄**：真正要拦的是「可操作的自伤指引与教唆」 | L2 第 3 条**要求**模型提热线，所以「提到」不能算异常 |

配套的两条纪律：
- 输出侧的操作性词**不收「怎么 / 如何」这类疑问副词** —— 它们会与
  「你怎么会有这种念头」这类共情问句共现，是高频误报源；
- 前端**不许再维护关键词标记**（历史包袱是 `CRISIS_MARK = '12356'`）。

### 4. `done` 只表示正文结束：立刻解锁，之后只认 `followups`

这是一条**改过的**约定。旧版写的是「`done` 之后的任何事件都应被忽略」，
现在后半句仍然成立（除了一个例外），但前半句的做法必须反过来：

| | 旧做法 | 现做法 | 为什么 |
|---|---|---|---|
| 解锁时机 | 等流关闭（`finally`） | **收到 `done` 立刻**解锁 | `followups` 要额外调一次模型（1~2s）。等流关闭 = 用户读完回复还点不了发送 |
| 忽略后续 | 收到 `done` 就 `break` | **不 break**，继续读到服务端关闭 | 一 break 就把 `followups` 一起丢了 |
| 防串轮次 | 无需处理 | 用「消息数组长度 === 本轮气泡下标」守卫 | 解锁后用户可能已经发了下一轮，旧流的事件不能写到新一轮上 |

「忽略」的正确实现位置不是解析层，而是**回调里**：`onDone` / `onFollowups` 都先判
`messages.length - 1 === myIdx`，不等就整批丢弃。这样物理上仍然收完所有帧，
逻辑上只有属于本轮的事件生效 —— 网络分帧导致的残余帧也被同一个守卫挡住。

⚠️ 反过来，`finally` 里的解锁**必须保留**，但降级成「兜底」：
异常断开时 `done` 不会来，只有它能放掉发送按钮。留着它不会造成重复解锁，
因为两处写的是同一个值（`sending = false`），而且都有同一套守卫。

### 5. 没有 `event:` 字段的帧按 `token` 处理（兼容旧后端）

旧后端返回 `Flux<String>`，Spring 只发 `data:` 行。保留这个兜底分支，
可以让前后端**分批上线**而不至于出现「一个 `data:` 都读不到、用户看到消息发出去了但没有回复」。

例外：`data` 为 `[DONE]` 时按 `done` 处理，不要当正文 —— 否则气泡末尾会多出一行 `[DONE]`。

### 6. 动作信号在回调里只记标记，动作留给本轮结束之后

`crisis` / `recommend` 都会改变界面甚至改变路由，但**都不该在事件回调里直接动手**：
- `recommend` 收到时本轮正文可能还在流，立刻弹全屏遮罩会盖住最后几个字；
- 回调运行在流解析的同步链路上，里面做 `await`（关闭会话、跳转）会阻塞后续帧的解析。

统一做法：回调里只写 store 的标记位，由视图在 `send()` 返回之后统一处理。

---

## 五、错误码表（`error.code`）

| code | 触发 | 是否可重试 | 后端动作 |
|---|---|---|---|
| `LLM_KEY_INVALID` | 401 / 403 | ❌ | 立即降级（重试必然还失败） |
| `LLM_RATE_LIMITED` | 429 | ✅ | 指数退避重试 1 次（500ms 起） |
| `LLM_BAD_REQUEST` | 400（上下文超长） | ✅ | 压缩历史后重试 1 次 |
| `LLM_MODEL_NOT_FOUND` | 400 且 body 指明模型名 | ❌ | 降级 + 启动探活时已提前告警 |
| `LLM_TIMEOUT` | 首帧 15s 内无响应 / 整体超时 | ✅ | 重试 1 次 → 降级 |
| `LLM_UPSTREAM_ERROR` | 5xx / 连接失败 | ✅ | 重试 1 次 → 降级 |
| `LLM_NO_CONTENT` | 上游正常结束但无正文 | ❌ | 直接给空回复兜底话术 |
| `LLM_UNKNOWN` | 兜底（404 路径错、402 余额不足等） | ❌ | 降级 |
| `NETWORK` | **前端自造**（fetch 抛错 / 非事件流响应） | ✅ | 无（后端还没参与） |

`retryable` 由后端给出，前端**只读它**、不自己判断 —— 重试策略属于服务端知识，
放前端会在两端各存一份且必然漂移。

---

## 六、验证方式

### 6.1 直接看字节（最有说服力）

```bash
TOKEN=$(curl -s --noproxy '*' -X POST http://127.0.0.1:18989/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user01","password":"user123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

SID=$(curl -s --noproxy '*' -X POST http://127.0.0.1:18989/api/ai-consult/open \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['sessionId'])")

# ⚠️ -N 关缓冲，否则看不出流式；--noproxy '*' 绕开本机代理
curl -N --noproxy '*' -X POST http://127.0.0.1:18989/api/ai-consult/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"sessionId\":$SID,\"content\":\"最近总是睡不着\"}"
```

**预期肉眼可见**：`event:meta` → 若干 `event:token` → `event:usage` → `event:done` →
（隔 1~2 秒）`event:followups`。
`meta` 的 `data` 应含 `"stage":"OPENING"`（首轮）与 `"stageStep":1`。

> `done` 与 `followups` 之间那段**空白是有内容的等待**：服务端正在拿这一轮问答去生成三条建议。
> 看到 `done` 之后 curl 还在挂着，不是卡死。

**`followups` 的验证口径**：`data` 是 `{"items":[...]}`，条数 1~3、每条 ≤ 20 字。
拿它和这一轮的正文对读 —— 三条应当分别指向正文里的不同落点（做法 / 就医 / 沟通），
若三条都在重复「多休息、别焦虑」这类套话，说明提示词退化了，检查 `PromptAssets.FOLLOWUP_SYSTEM`。

拿到 `sessionId` 后可直接验证两个读取接口（**注意用第二个终端，别复用上面的变量**）：

```bash
curl -s --noproxy '*' http://127.0.0.1:18989/api/ai-consult/sessions \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool | head -30

curl -s --noproxy '*' http://127.0.0.1:18989/api/ai-consult/sessions/$SID/messages \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool | head -40
```

### 6.2 逐条故障注入（累积验收清单：Phase 1 第 1–11 条，Phase 2.5 第 12–18 条，Fix-2 第 19–21 条）

| # | 制造方法 | 预期 |
|---|---|---|
| 1 | `application.yml` 里的 `psycare.llm.api-key` 改成 `sk-invalid` | 启动日志 ERROR「鉴权失败」；发消息得到 `error.code=LLM_KEY_INVALID` + 降级话术（含 12356），**气泡不是空的** |
| 2 | 模型名改成 `deepseek-chat` | 启动探活 WARN/ERROR 指出模型名不在服务端列表；发消息得 `LLM_MODEL_NOT_FOUND` |
| 3 | 断网（断开网卡或改 base-url 为不可达域名） | `LLM_UPSTREAM_ERROR` + 降级话术 |
| 4 | `startup-probe` 保持开，把 `api-key` 清空后重启 | 启动日志明确说「未配置 API Key，AI 将全部走降级话术」 |
| 4b | 把 `api-key` 写成 `sk-xxxx`（占位符没换） | 启动日志报「**仍是占位符**」而非笼统的「鉴权失败」——前者改配置、后者要去控制台重置，两者下一步动作不同 |
| 5 | 发消息后立刻关页面 | 重进会话，**已生成部分在**，且 `finish_reason='INTERRUPTED'` |
| 6 | 用他人 `sessionId` 调 `/chat` | HTTP 200 + `body.code=403`（**JSON 而非事件流**），前端抛「无权访问该会话」 |
| 7 | `first-token-seconds` 改 1 | 触发 `LLM_TIMEOUT`，退避重试 1 次后降级 |
| 8 | 用他人 `sessionId` 调 `GET /sessions/{id}/messages` | HTTP 200 + `body.code=403`（与 `/chat` 同一套 `requireMember`，不是只保护流式接口） |
| 9 | 连发 6 条消息，看每轮 `meta.stage` | 阶段依次推进且**每轮最多进一级**；指示条不跳级 |
| 10 | 推进到「建议转介」后发一句新主诉（如「其实我最难受的是胃疼」） | `meta.stage` **退回** `CHIEF_COMPLAINT`，指示条往回走（阶段压到 3 段后，可回退的起点只剩这一段） |
| 11 | 刷新页面 | `chat.enter()` 走了恢复分支：消息与阶段都回来（数据源是服务端列表，不再依赖 localStorage）。新账号没有任何记录时停在空态，不报错 |
| 12 | **正常聊到「建议转介」阶段**（最多 3 个问题就会到，不要自己说危机词） | AI 可能会建议拨打 12356 —— 这是 L2 要求的合规行为。气泡**必须是正常样式**，右侧**不出现**「需要立即帮助」红卡。若变红即本类 bug 复发 |
| 13 | 自己发一句「我不想活了」 | 走 `crisis(INPUT)`：气泡为危机文案、转红，右侧红卡出现且文案含「已为你转接人工」；`alert_record` 新增一条 `CRISIS` 工单；`ai_usage_log` **无**本轮记录（没调模型） |
| 14 | 发一条消息后看 Redis：`redis-cli GET ai:stage:<sid>` | 必须能读到 JSON，且含 `"updatedAt"`。**若为空**：说明阶段状态没写进去（历史上是 `LocalDateTime` 未注册 Jackson 模块），后端日志里每轮都会有一条「按历史消息重放重建」，**回看条数会逐轮 +1** |
| 15 | 停止 Redis 后再发一条消息 | 阶段由历史重放重建，结果与停之前一致；恢复 Redis 后下一轮正常写入 |
| 16 | 聊到「建议转介」阶段，AI 问「需要我为你推荐合适的医生吗？」后回答「好的」 | 本轮 `done` 之前出现 `event:recommend`；前端播约 2.8 s「AI 正在分析」动画 → 跳 `/ai-recommend?sessionId=<sid>`。**会话保持 `OPEN`**（Fix-2 起不再关闭）：用户从这里回「AI 问诊」时那段记录还在，而且还能继续发消息 |
| 17 | 同上，但回答「先不用了」 | **不出现** `recommend`，对话继续。否定优先是这条判定的关键分支 —— 中文的「不用」含「用」、「不要」含「要」，不先判否定就会把拒绝读成同意 |
| 18 | 聊到「建议转介」后问一句「你最近怎么样」，再答「好的」 | **不出现** `recommend`：AI 上一轮问的不是推荐，此时「好的」只是回应。这条用例守的是「一问一答」这个判据的另一半 |
| 19 | 反复进出 AI 问诊页（菜单进出 + F5）共 5 次，然后查库 | `consult_session` 中该居民 `consult_type='AI' AND status='OPEN'` 始终只有 **1** 行；界面上那段消息每次进出后都还在 |
| 20 | 做完一次测评，从结果页点「和心理助手聊聊」 | 复用同一条会话（记录**不断**），且 `consult_session.assessment_id` 被更新为这次测评；右栏「关联测评」卡显示新测评 |
| 21 | 登出后用另一个账号登录，进 AI 问诊 | 看不到上一个账号的任何消息（`logout()` 里清了 chat store） |

### 6.3 数据侧核对

```sql
-- 每次调用的 token / 首帧延迟 / 错误码（reasoning_tokens 是推理模型的隐性开销）
SELECT id, session_id, model, effort, prompt_tokens, cached_tokens, completion_tokens,
       reasoning_tokens, first_token_ms, total_ms, error_code, created_at
  FROM ai_usage_log ORDER BY id DESC LIMIT 20;

-- 落库内容是否与用户所见一致（CRISIS/INTERRUPTED/ERROR 都应出现在这里）
SELECT id, session_id, sender, finish_reason, crisis_flag, CHAR_LENGTH(content) AS len
  FROM consult_message WHERE sender = 'AI' ORDER BY id DESC LIMIT 20;
```

---

## 七、配套的会话读取接口（Phase 2 新增，非 SSE）

页面上要显示「这个居民一直在聊的那段对话」，需要读「最近是哪条会话」和「它里面说过什么」，
这两个接口是**普通 JSON**，不返回事件流。放在同一份契约里，是因为它们与 `/chat`
共用 `requireMember` 归属校验 —— 只保护流式接口而放过读取接口，等于把最敏感的数据敞开。

> [!note] Phase 2.5 的 Fix-2 起，调用方不再是「历史会话抽屉」
> 抽屉已取消 —— 一个居民只有一段连续对话（见 `开发文档.md` 16.7）。
> 列表接口现在的用途是：`chat.restoreLast()` 找最近一段对话，并取回它的 `assessmentId`。

| 接口 | 方法 | 返回 | 说明 |
|---|---|---|---|
| `/api/ai-consult/sessions` | GET | `Result<List<AiSessionVO>>` | 当前登录居民的会话列表，**最多 5 条**，按 `id` 倒序 |
| `/api/ai-consult/sessions/{sessionId}/messages` | GET | `Result<AiSessionDetailVO>` | 单会话明细，**最多 200 条**，按 `id` 升序 |

> [!note] 列表为什么只有 5 条
> 这个接口的语义是「把最近一段对话接回来」，不是归档检索。
> 前端只取第一条（必要时在这 5 条里挑一条 `OPEN` 的），第 6 条以后没有用途。
> **截断在后端**（`AiConsultService.SESSION_LIST_LIMIT`），前端不做二次截断：
> 两处各限一次，改一处忘一处就会不一致，而且不会报错。

**`AiSessionVO`**（会话列表项）

| 字段 | 说明 |
|---|---|
| `sessionId` / `status` / `assessmentId` / `createdAt` | 基本信息 |
| `messageCount` | 消息条数（标量子查询，不额外发起请求） |
| `lastMessage` / `lastAt` | 最后一句 + 时间（抽屉取消后前端已不展示，保留给排查用） |

> [!warning] 列表接口**不返回 `stage`**，这是有意的
> 阶段存在 Redis（`ai:stage:{sessionId}`），也可能由历史重放得出。若列表要显示阶段，
> 就得为**每一条**会话各查一次 Redis / 各重放一次历史 —— 5 条列表变成 5 次额外计算，
> 而列表本身只需要回答「什么时候聊过、聊了几条、最后一句是什么」。阶段留给明细接口。

**`AiSessionDetailVO`**（会话明细）

| 字段 | 说明 |
|---|---|
| `sessionId` / `status` | `OPEN` / `CLOSED` |
| `stage` / `stageLabel` / `stageStep` | 当前阶段（与 `meta` 同名同义） |
| `summary` | 早远对话摘要，会话太短时为 `null` |
| `messages` | `AiMessageVO` 列表：`id` / `sender`(RESIDENT·AI) / `content` / `crisisFlag` / `finishReason` / `createdAt` |

> [!note] `stage` 放进明细接口是必要的
> 进页面恢复那段对话时，阶段指示条要**立刻**显示正确状态。缺了它，指示条会一直停在
> 「建立联结」直到用户再发一条消息才纠正 —— 那种「一进来就显示错」的界面会让人
> 以为上下文丢了，而实际上只是没读。

**归属校验**：两个接口都只查当前登录用户自己的数据。`residentId` 直接取自登录态
（`SecurityUtil.getUserId()`），请求里**没有任何可用来指定「查谁」的参数** ——
这类「列表接口」最容易漏校验，而漏了就是全量泄露他人的倾诉记录。
明细接口额外过 `requireMember`：非本人返回 403。

---

## 八、变更记录

| 版本 | 变更 | 兼容性 |
|---|---|---|
| Phase 1（2026-09-29） | 由「纯 `data:` 行」升级为 `meta`/`token`/`usage`/`override`/`error`/`done` 六类事件 | **破坏性**：前端必须同改。已保留「无 `event:` 时按 `token`」的兜底分支，两端可分两批上线 |
| Phase 2（2026-09-29） | `meta` 新增 `stage` / `stageLabel` / `stageStep` / `stageTotal` / `stageGoal`；新增两个会话读取接口（第七节） | 向后兼容：事件类型未变，老前端忽略新增字段即可；`stage` 曾为 `null`，新前端需按 `OPENING` 兜底 |
| Fix-1（2026-09-29） | 新增 `crisis` 事件（`reason` = INPUT / OUTPUT）；输出侧判据由「出现危机词」改为「危机词 + 操作性词共现」；两条危机路径文案分叉（`crisisResponse` / `safetyNotice`） | **对前端是行为修复**：删掉 `CRISIS_MARK = '12356'` 子串判定改认 `crisis` 事件。老前端不认新事件名会漏掉危机态（漏报），比误报轻，可分两批上线 |
| **Phase 2.5（2026-09-29）** | 新增 `recommend` 事件（用户同意接受医生推荐）；**阶段由 5 段压到 3 段**（删除 `FUNCTION_IMPACT` / `RISK_SCREENING`，`stageTotal` 由 5 变 3）；会话列表上限由 30 收到 **5** | **阶段数是破坏性变更**：前端 `STAGE_FLOW` 必须同步改 3 项，指示条列数不能写死。`recommend` 是新增事件类型，老前端忽略它只会「不跳转」，不报错 —— 但用户会觉得「答应了却没反应」，两端应同时上线 |
| **Fix-2（2026-09-29）** | 取消「历史会话」入口；`POST /sessions` 语义变为**幂等复用** —— 复用该居民最近的 `OPEN` 会话并更新其 `assessmentId` / `alertId`，不再每次新建；`GET /sessions` 的调用方由抽屉变为「恢复最近一段对话」 | **服务端行为变更**：同一居民反复调用不会再多开会话。若某处仍依赖「调一次就拿一条新会话 id」，需同步改造；老前端不会报错，只是不再产生新会话 |
| **Phase 4（2026-10-09）** | 新增 `citations` 事件（`meta` 之后、`token` 之前；无资料时不发）；`AiConsultService` 接通 `RetrievalPipeline` —— 检索层此前**已建好但未接线**，L6 恒走「无资料」分支 | 向后兼容：老前端忽略未知事件名即可（只是不显示引用卡）。**后端行为有实质变化**：此前 AI 从不读知识库，此后会读 —— 这是「AI 答不上来爬虫问题」的直接修复 |
