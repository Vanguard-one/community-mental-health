# AI 问诊模块：进度评估与改造设计

> 一份「先体检、后开方」的方案。前半是对现有代码的实测评估（有文件与行号为证），
> 后半是把模块从「能聊天的 Demo」推到「医疗知识问答助手」的改造设计。
>
> 评估时间：2026-09-29 · 对象：`psycare-server` / `psycare-web` 的 AI 问诊链路

---

## 结论速览

| 维度 | 完成度 | 一句话结论 |
|---|---|---|
| 会话与消息持久化 | **100%** | 复用 `consult_session` / `consult_message`（`consult_type='AI'`），零冗余表 |
| LLM 客户端抽象 | **90%** | `AiChatClient` 接口已把供应商隔离开，但只有一个 DeepSeek 实现 |
| 流式对话链路 | **85%** | 端到端能跑（后端 `Flux<String>` → 前端 fetch 手解 SSE），但缺流内错误协议 |
| 多轮上下文 | **70%** | 已注入历史 + 落库；缺 token 预算、缺历史会话恢复入口 |
| 提示词工程 | **40%** | 有安全边界与测评注入；**缺引导问诊流程**，且 `scaleName` 恒为 null |
| 异常与超时 | **45%** | 有整体超时 + 前端兜底；缺首 token 超时、重试、降级、流内错误码 |
| 安全 | **70%** | 危机词本地拦截很扎实；⚠️ **但有会话越权漏洞（P0）** |
| **通义千问接入** | **0%** | 当前接的是 DeepSeek，未接 DashScope |
| **RAG 知识检索** | **0%** | 无 embedding、无向量库、无文档管道 |
| **MCP / 工具调用** | **0%** | 无 function calling，无工具注册表 |

**总评：约 45%。**

分两句话理解这个数字：

- 若目标只是「**可交互的聊天助手**」——**85%，基本可用**。流式、多轮、危机拦截都已落地，是这一轮里质量最高的部分，注释甚至写着「答辩红线」。
- 若目标是你在需求里写的**完整形态**（通义千问 + RAG + 引导问诊 + 工具调用）——**45%**。缺的三块（RAG、工具调用、通义千问）恰好是工作量最大的三块，RAG 单独就占一半以上。

---

## 一、现有进度评估

### 1.1 已经做完的（有代码为证）

| 能力 | 落点 | 评价 |
|---|---|---|
| 供应商抽象 | `ai/AiChatClient.java` | ✅ 设计正确。接口只暴露 `Flux<String> chatStream(List<ChatMessage>)`，业务层不感知供应商 |
| 流式 LLM 客户端 | `ai/DeepSeekClient.java` | ✅ WebClient + SSE，`takeWhile(![DONE])` 收流、`extractDelta` 取 `choices[0].delta.content` |
| 消息模型 | `ai/ChatMessage.java` | ✅ record + `system/user/assistant` 静态工厂，够用 |
| 危机词本地拦截 | `engine/CrisisDetector.java` | ✅ **设计最扎实的一环**：命中即不走模型，直接返回固定文案 + 建 CRISIS 工单。注释写明「危机识别必须用本地规则，不能依赖大模型自觉」 |
| 双端危机检测 | `AiConsultService.persistAssistantReply` | ✅ 用户输入 + AI 回复各查一次，AI 侧命中则替换为安全文案（双保险） |
| 多轮上下文 | `AiConsultService.buildMessages` | ✅ system + 最近 `max-history*2` 条历史 + 当前 user，按时间正序重排，`limit` 在 SQL 层 |
| 会话管理 | `AiConsultService.openSession/closeSession` | ✅ 可关联 `assessmentId` / `alertId`，`status` OPEN/CLOSED |
| 流式接口 | `AiConsultController` | ✅ `POST /ai-consult/chat`，`produces=text/event-stream` |
| 前端 SSE 解析 | `api/consult.ts` `chatStream` | ✅ **手写解析质量很高**：处理 `\n\n` 分帧、同事件多 `data:` 行按 `\n` 拼接、`\r\n` 归一化。这三条是 SSE 客户端最容易漏的 |
| 前端交互 | `views/consult/AiChat.vue` | ✅ 左右布局、预设提示词、关联测评卡、危机热线卡、AI 推荐医生、`streaming` 光标 |
| 错误兜底（前端） | `stores/chat.ts` `send()` catch | ✅ 中断时在气泡里写「（对话中断：…）」，不抛给调用方 |

### 1.2 遗留问题（按优先级）

| # | 级别 | 问题 | 位置 | 影响 |
|---|---|---|---|---|
| 1 | **P0 安全** | **会话越权**：`chatStream` / `closeSession` 只校验 `status`，**不校验 `session.residentId == 当前用户`** | `AiConsultService:74-77`、`101-108` | 任何登录居民改一下 `sessionId` 就能读别人与 AI 的全部对话、甚至关闭他人会话。`ChatService` 有 `requireMember`，这里漏了 |
| 2 | P1 | **回复可能丢失不落库**：落库写在 `doOnComplete()` | `AiConsultService:97` | 客户端中途断开（关页面/断网）→ `doOnComplete` 不触发 → AI 已生成的内容**不入库**，下次进来看不到 |
| 3 | P1 | **流内无错误协议**：SSE 已建立后 HTTP 200 无法改，LLM 失败只能靠流中断体现 | `AiConsultService:95-97` | 前端只能显示「对话中断」，拿不到错误码，无法区分「超时」和「限流」 |
| 4 | P1 | **无首 token 超时**：只有整体 `.timeout(60s)` | `DeepSeekClient:60` | LLM 端挂死时，用户要干等 60 秒才看到失败 |
| 5 | P2 | **`scaleName` 恒传 null** | `AiConsultService:116-119` | system prompt 里永远写「心理测评」而不是「PHQ-9」，测评上下文注入打折 |
| 6 | P2 | **无 token 预算控制**：`max-history*2` 按条数切 | `AiConsultService:126` | 一条 3000 字的长消息就能顶掉整个上下文窗口，且无法预估成本 |
| 7 | P2 | **无历史会话恢复**：`stores/chat.ts` 的 `open()` 只建新会话 | `stores/chat.ts:39-51` | 刷新页面即丢上下文，用户看不到历史问诊记录（数据在库里，但没有读的口子） |
| 8 | P2 | **无重试 / 降级 / 熔断** | `DeepSeekClient` | LLM 不可用时体验为「对话中断」，没有降级话术 |
| 9 | P3 | `refresh` 后 `sessionId` 丢失导致重复建会话 | `stores/chat.ts` | 库里有冗余空会话 |

> ⚠️ **第 1 条建议立刻修，与本次改造无关也该修。** 修法见 3.5，约 15 行代码。

---

## 二、改造涉及模块

| 层 | 模块 | 新增/改造 | 说明 |
|---|---|---|---|
| 配置 | `application.yml` | 改造 | `psycare.llm` 扩成多供应商；新增 `psycare.rag.*` |
| AI 客户端 | `ai/AiChatClient` | 改造 | 方法签名加 `tools` 参数（为 MCP 预留） |
| AI 客户端 | `ai/OpenAiCompatClient` | **新增** | 泛化 `DeepSeekClient`：一个类吃下 DeepSeek / 通义千问 / GLM |
| AI 客户端 | `ai/QwenClient` | 可选 | 若不想泛化，就新增这个实现类（最小改动路线） |
| 提示词 | `ai/PromptBuilder` | 改造 | 加**引导问诊五阶段**、RAG 引用指令、防幻觉条款 |
| 提示词 | `ai/InterviewStage` | **新增** | 问诊阶段枚举 + 阶段目标文案 |
| 服务 | `AiConsultService` | 改造 | 加归属校验、检索注入、流内事件、token 预算、摘要压缩 |
| 服务 | `ai/RagService` | **新增** | 检索编排：改写 → 检索 → 重排 → 拼上下文 |
| RAG 离线 | `rag/DocumentLoader` | **新增** | 文档解析（PDF/DOCX/MD/HTML） |
| RAG 离线 | `rag/TextSplitter` | **新增** | 父子分块 + 元数据 |
| RAG 在线 | `rag/EmbeddingClient` | **新增** | DashScope `text-embedding-v3` |
| RAG 在线 | `rag/VectorStore`（接口） | **新增** | 抽象存储，先落 MySQL 实现，预留 Milvus/Qdrant |
| RAG 在线 | `rag/JdbcVectorStore` | **新增** | MySQL 存向量 + 应用层余弦相似度 |
| RAG 在线 | `rag/Reranker` | **新增** | 可选，`gte-rerank` 精排 |
| 工具 | `ai/tool/ToolRegistry` | **新增（Phase C）** | 工具注册表，为 MCP 铺路 |
| 数据 | `kb_document` / `kb_chunk` | **新增表** | RAG 知识库（见 4.5） |
| 数据 | `consult_session` | 改造 | 建议加 `stage`、`summary` 启用、`token_used` |
| 管理端 | `views/admin/KnowledgeBase.vue` | **新增** | 知识库上传/切分预览/重建索引 |
| 居民端 | `views/consult/AiChat.vue` | 改造 | 引用来源展示、历史会话入口、错误态细化 |
| 前端 | `api/consult.ts` | 改造 | SSE 解析支持 `event:` 字段（token/error/done） |
| 前端 | `stores/chat.ts` | 改造 | 会话恢复、错误分级、引用列表 |

**不动的**：`engine/CrisisDetector`（危机拦截逻辑正确，只扩词表）、`consult_message` 表结构、`ChatService`（医患聊天与 AI 问诊继续隔离）。

---

## 三、关键改动点

### 3.1 通义千问接入

**好消息：现有架构几乎已经支持，`application.yml` 的注释就写着 `deepseek | qwen | glm`。**
通义千问的 DashScope 提供 **OpenAI 兼容接口**，所以 `DeepSeekClient` 的代码可以原样复用。

| 项 | DeepSeek（现状） | 通义千问 |
|---|---|---|
| base-url | `https://api.deepseek.com` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 路径 | `/v1/chat/completions` | `/chat/completions` |
| 模型 | `deepseek-chat` | `qwen-plus` / `qwen-max` / `qwen-turbo` / `qwen-flash` |
| 鉴权 | `Bearer <key>` | `Bearer <DASHSCOPE_API_KEY>`（同格式）|
| 流式 | `stream:true` + `[DONE]` | 完全一致 |
| 增量字段 | `choices[0].delta.content` | 完全一致 |

> ⚠️ **必踩的坑：`base-url` 与 `.uri()` 会双拼 `/v1`。**
> 现在 `DeepSeekClient` 是 `baseUrl=https://api.deepseek.com` + `.uri("/v1/chat/completions")`。
> 若把 base-url 直接换成 `.../compatible-mode/v1` 而 `.uri()` 不动，会请求到
> `.../compatible-mode/v1/v1/chat/completions` → 404。
> **解法：base-url 带 `v1`，`.uri()` 改成相对路径 `/chat/completions`。**

**推荐路线 A（泛化，代码更少）**：把 `DeepSeekClient` 重命名为 `OpenAiCompatClient`，去掉 `@ConditionalOnProperty` 上的供应商硬绑，改由配置决定；`.uri("/chat/completions")`。

```java
@Slf4j
@Component
public class OpenAiCompatClient implements AiChatClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${psycare.llm.model}") private String model;
    @Value("${psycare.llm.timeout-seconds:60}") private Long timeoutSeconds;
    @Value("${psycare.llm.first-token-seconds:15}") private Long firstTokenSeconds;

    public OpenAiCompatClient(@Value("${psycare.llm.base-url}") String baseUrl,
                              @Value("${psycare.llm.api-key}") String apiKey) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)                       // 例：.../compatible-mode/v1
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public Flux<String> chatStream(List<ChatMessage> messages) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", toBody(messages),
                "stream", true,
                "stream_options", Map.of("include_usage", true));   // 拿 token 用量，便于成本核算
        return webClient.post()
                .uri("/chat/completions")                   // 相对路径，v1 已在 base-url 里
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapLlmError)   // 见 3.2
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .takeWhile(d -> !"[DONE]".equals(d.trim()))
                .mapNotNull(this::extractDelta);
    }
}
```

**路线 B（最小改动）**：保留 `DeepSeekClient`，新增 `QwenClient` 抄一份改两行，`provider=qwen` 时生效。适合「只想先跑通通义千问」。

> 两条路线的差别：路线 A 以后再接 Kimi / GLM / OpenAI 都是改配置；路线 B 每接一家抄一个类。
> **建议 A**，理由是该类没有任何 DeepSeek 专有逻辑，`extractDelta` 对 OpenAI 兼容协议是通用的。

**配套配置**（`application.yml`）：

```yaml
psycare:
  llm:
    provider: qwen
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${DASHSCOPE_API_KEY:sk-xxxx}
    model: qwen-plus          # 医疗问答建议 qwen-plus；追求质量可换 qwen-max
    timeout-seconds: 60
    first-token-seconds: 15
    max-history: 10
    max-context-tokens: 6000  # 上下文预算，见 3.4
```

### 3.2 API 调用异常与超时（四道防线）

现在的处置是「超时 60s + 前端 catch 兜底」，对生产不够。补齐四层：

**① 错误归类**：把 HTTP 状态与异常翻成业务可用的错误码，`retrieve()` 后立刻映射。

```java
private Mono<Throwable> mapLlmError(ClientResponse resp) {
    int code = resp.statusCode().value();
    String reason = switch (code) {
        case 401, 403 -> "LLM_KEY_INVALID";        // 密钥错/权限不足 → 不该重试
        case 429       -> "LLM_RATE_LIMITED";      // 限流 → 可退避重试
        case 400       -> "LLM_BAD_REQUEST";       // 上下文超长等 → 应触发摘要压缩后重试
        default        -> code >= 500 ? "LLM_UPSTREAM_ERROR" : "LLM_UNKNOWN";
    };
    return resp.bodyToMono(String.class).defaultIfEmpty("")
            .map(body -> new LlmException(reason, code, body));
}
```

| 错误码 | 该做什么 | 不该做什么 |
|---|---|---|
| `LLM_KEY_INVALID` | 立即降级，告警运维 | **不要重试**（重试必然还失败） |
| `LLM_RATE_LIMITED` | 指数退避重试 1 次（500ms 起） | 不要并发重试 |
| `LLM_BAD_REQUEST` | 若是上下文超长 → 压缩历史后重试 1 次 | 不要原样重试 |
| `LLM_UPSTREAM_ERROR` / 超时 | 重试 1 次 → 仍失败则降级 | — |

**② 首 token 超时**（当前缺失，体验提升最大的一项）：

```java
.timeout(Duration.ofSeconds(timeoutSeconds))                        // 整体
// 首字节：拿第一个元素前用 timeout 单独约束
Flux<String> body = ...;
return body.switchOnFirst((signal, flux) -> flux)
           .timeout(Duration.ofSeconds(firstTokenSeconds));
```

> 更严谨的写法是 `Flux.timeout(第一个信号, 后续信号)` 双参数重载。要点是：
> **「15 秒没吐第一个字」和「吐了一半卡住 60 秒」是两种故障**，前者该立刻降级，后者该断流。

**③ 流内错误协议**（关键改动）：SSE 一旦建立，HTTP 状态码已固定为 200，错误只能走**事件类型**。

后端由 `Flux<String>` 改为 `Flux<ServerSentEvent<String>>`：

```java
public Flux<ServerSentEvent<String>> chatStream(Long sessionId, String content) {
    return Flux.concat(
        Flux.just(sse("meta", "{\"sessionId\":" + sessionId + "}")),
        llmFlux.map(t -> sse("token", t)),
        Flux.just(sse("done", "[DONE]")))
      .onErrorResume(e -> Flux.just(
          sse("error", toJson(errorCode(e), userMessage(e)))));   // 错误也走事件，不中断连接
}

private ServerSentEvent<String> sse(String event, String data) {
    return ServerSentEvent.<String>builder().event(event).data(data).build();
}
```

前端 `chatStream` 解析时读 `event:` 行（现在只读 `data:`，需要改）：

```ts
export type SseEvent =
  | { type: 'token'; data: string }
  | { type: 'error'; data: { code: string; msg: string } }
  | { type: 'done' }

// 在 drain() 里：
let evtName = 'message'
for (const rawLine of event.split('\n')) {
  const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
  if (line.startsWith('event:')) evtName = line.slice(6).trim()
  else if (line.startsWith('data:')) parts.push(...)
}
if (evtName === 'error') onError(JSON.parse(parts.join('\n')))
else if (evtName === 'token') onToken(parts.join('\n'))
else if (evtName === 'done') onDone()
```

> ⚠️ **兼容性**：现有 `api/consult.ts` 只认 `data:`，`event:` 会被忽略 → 改后端不改前端，
> 前端会把 `error` 事件的 data 当成正常 token 拼进气泡。**两端必须同时改。**

**④ 降级话术**（医疗场景必须是「安全降级」，不能沉默）：

```java
private static final String FALLBACK =
    "抱歉，我暂时无法连接到智能服务。你可以稍后重试，"
    + "也可以直接在「我的消息」里联系医生。如果情况比较紧急，请拨打心理援助热线 12356。";
```

> 降级话术必须**带人工出口**。只写「服务不可用」在心理健康场景是不合格的。

**⑤ 落库改为 `doFinally`**（修 P1 第 2 条）：客户端断开时 `doOnComplete` 不触发，导致回复丢库。
改为累计内容 + `doFinally` 落库，且**只落已生成的部分**（前缀），并在库里标记 `interrupted`。

### 3.3 引导问诊流程的提示词

现状 `PromptBuilder.buildSystemMessage` 只有「边界 4 条 + 测评上下文」，没有**流程引导** ——
模型会退化成「百科全书式问答」：用户问什么答什么，不会主动采集症状、评估风险、给出转介建议。

设计一个**五阶段引导流程**，写进 system prompt：

| 阶段 | 目标 | 典型话术要求 |
|---|---|---|
| 1 建立联结 | 共情、确认来意、说明边界 | 先接情绪，不急着问问题；一次只问一个 |
| 2 主诉澄清 | 症状是什么、**持续多久**、**频率**、**强度** | 用开放式问题；一次只问一个 |
| 3 功能影响 | 睡眠 / 食欲 / 工作学习 / 人际是否受影响 | 落到具体场景，避免抽象打分 |
| 4 风险筛查 | 自伤、自杀意念（**本地词表前置拦截**） | 直接但不生硬地问，问完明确回应 |
| 5 小结与转介 | 复述理解、给自助建议、必要时推荐医生 | 必须带边界声明；给下一步动作 |

实现方式两条路，按投入选：

**路线 A（零 DDL，先跑起来）**：把五阶段目标 + 行为约束写进 system prompt，让模型自己判断阶段。

```java
private static final String FLOW_GUIDE = """
        【问诊流程】按以下顺序推进，不要跳步，不要一次问多个问题：
        1) 先共情并确认来意（1-2 轮），不急于提问；
        2) 澄清主诉：症状表现、起始时间、频率、强度；
        3) 了解功能影响：睡眠、食欲、工作学习、人际交往；
        4) 温和地评估风险：是否出现伤害自己的念头（若已命中危机词，本地已拦截，无需再问）；
        5) 小结：复述你理解到的困扰，给出 1-2 条可操作建议，并说明何时需要就医。
        【行为约束】
        - 每轮回复不超过 120 字，最多问 1 个问题；
        - 不重复用户已经答过的信息，也不要一次罗列所有问题；
        - 用户主动换话题时跟随用户，不强行拉回流程。
        """;
```

**路线 B（阶段状态机，可控可测）**：服务端记录当前阶段，每轮把「当前阶段 + 本阶段目标 + 已收集字段」注入。

```java
public enum InterviewStage {
    RAPPORT("建立联结"), CHIEF_COMPLAINT("主诉澄清"),
    FUNCTION_IMPACT("功能影响"), RISK_SCREENING("风险筛查"), SUMMARY("小结转介");
}
```

阶段推进用**规则 + LLM 双确认**：规则先判（如已确认「持续 3 个月 + 每周 3 次」→ 可进下一阶段），
LLM 在结构化输出里返回 `suggestNextStage`。存 `consult_session.stage`（加列）或 Redis（项目已依赖 Redis，
`pom.xml` 注释就写着「Redis：AI会话上下文」，但当前**并未真的使用** —— 这是现成的落点）。

> **建议先 A 后 B**：路线 A 能在一轮内验证，路线 B 的收益（可断言、可回放）要等有了评测集才体现。
> 题目/答辩场景下，A 已足够体现「设计了引导流程」，B 是加分项。

**同时修掉第 5 条遗留问题**：`scaleName` 现在恒传 null，应从 `scale` 表按 `assessment.scaleId` 查名字传入。

### 3.4 上下文与历史

| 改动 | 做法 | 理由 |
|---|---|---|
| token 预算 | 按 **token** 而非条数裁剪：从最近往前累加，超过 `max-context-tokens` 就停 | 一条长消息就能顶掉整个窗口 |
| 估算方式 | 中文按「1 字 ≈ 0.7 token」粗估，或用模型 tokenizer | 精确 tokenizer 是可选优化 |
| 摘要压缩 | 超出预算的**更早**对话，用一次低成本 LLM 调用压成 200 字「既往情况小结」，放进 system | 保留长程信息，避免「聊到第 20 轮忘了开头」 |
| 落库依赖 | `consult_session.summary` 字段**已存在但未使用** | 正好用作摘要落点，零 DDL |
| 历史恢复 | 新增 `GET /ai-consult/sessions`（我的历史会话）+ `GET /ai-consult/sessions/{id}/messages` | 补上「历史记录」的读口子 |

> ⚠️ 历史恢复接口必须做归属校验（同 3.5）。

### 3.5 ⚠️ P0：会话越权修复

现在 `AiConsultService.chatStream` 只判断 `status`，不判断**会话是不是你的**：

```java
// 现状（AiConsultService:74-77）—— 缺少归属校验
ConsultSession session = sessionMapper.selectById(sessionId);
if (session == null || !"OPEN".equals(session.getStatus())) {
    return Flux.error(new BizException(400, "会话不存在或已关闭"));
}
```

`ChatService` 有 `requireMember`，`AiConsultService` 漏了。居民 A 拿到居民 B 的 `sessionId`（自增 id，可枚举）
就能读写其全部问诊记录 —— 在心理健康场景里，这属于**最敏感的数据**。

修法（对齐 `ChatService.requireMember`）：

```java
private ConsultSession requireMember(Long sessionId) {
    ConsultSession session = sessionMapper.selectById(sessionId);
    if (session == null) {
        throw new BizException(404, "会话不存在");
    }
    if (!SecurityUtil.getUserId().equals(session.getResidentId())) {
        throw new BizException(403, "无权访问该会话");
    }
    return session;
}
```

`chatStream` / `closeSession` / 新增的历史接口都要过这一关。

### 3.6 为 MCP 工具调用预留（本轮不实现，但要先把口子留对）

用户已明确「后续要通过 MCP 添加工具」。**现在就把签名设计对，以后不用改业务代码。**

**① 客户端接口加 `tools` 参数**（默认空列表，不破坏现有调用）：

```java
public interface AiChatClient {
    Flux<String> chatStream(List<ChatMessage> messages);
    /** 带工具定义与工具选择的流式对话（Phase C 使用） */
    Flux<ChatStreamChunk> chatStream(List<ChatMessage> messages, List<ToolDef> tools);
}
```

**② 工具注册表**：统一描述「工具名 + JSON Schema + 执行器」，MCP 工具与本地工具**用同一套抽象**。

```java
public interface AiTool {
    String name();                    // 如 "queryMyAssessments"
    String description();             // 给 LLM 看的自然语言描述
    Map<String, Object> schema();     // JSON Schema，OpenAI function calling 格式
    Object execute(Map<String, Object> args);   // 入参审计 + 脱敏在这里做
}
```

**③ 通义千问的 function calling 是 OpenAI 兼容格式**，与 MCP 对接有两条路：

| 路线 | 做法 | 适用 |
|---|---|---|
| **A. 应用侧 MCP Client**（推荐） | 后端作为 MCP Client 连 MCP Server（stdio/SSE），把返回的工具列表注册进 `ToolRegistry`，自己跑 Agent 循环 | 医疗场景**必须可审计**：每次工具调用要留痕、要能拦、要能脱敏。字段可控 |
| B. 模型侧直连 MCP | DashScope **Responses API** 支持 `tools:[{type:"mcp", server_url, ...}]`，模型直接连 MCP Server | 代码最少，但**仅 Responses API + 特定模型支持**，且调用链在厂商侧，审计与拦截能力弱 |

**④ 本项目值得先做的工具**（都是已有能力的封装，不需要新写业务）：

| 工具 | 复用什么 | 价值 |
|---|---|---|
| `queryMyAssessments` | `AssessmentService` | 让 AI 能引用「你上次 PHQ-9 是 12 分」 |
| `recommendDoctors` | `RecommendationEngine` | 对话中直接给医生推荐（现在只在右栏静态展示） |
| `searchMedicalKnowledge` | Phase B 的 RAG | **RAG 工具化**：见 4.8 |
| `triggerCrisisAlert` | `AlertEngine` | 危机转人工（现在是规则触发，可升级为工具） |

> **架构上的一个重要判断：RAG 本身就是一种工具。**
> Phase B 先做「每轮预检索 + 高阈值过滤」（可靠、低延迟），
> Phase C 再演进为「LLM 决定何时调用 `searchMedicalKnowledge`」（Agentic RAG，更省 token、更准）。
> 用同一套 `AiTool` 抽象，这个演进不需要重写。

---

## 四、RAG 方案

### 4.0 目标与边界

**目标**：支撑医疗专业领域的知识问答 —— 用户问「舍曲林有什么副作用」「双相障碍和抑郁症怎么区分」时，
回答应**来自权威知识库并带出处**，而不是模型的参数记忆。

**边界（必须写进 prompt）**：RAG 只提升「知识准确性」，**不改变产品边界** ——
不做诊断、不开药、不替代医生。知识库中涉及用药的内容要**标注「遵医嘱」并建议咨询医生**。

### 4.1 全流程一图

```
【离线：构建索引】一次性 / 增量
  知识源(PDF/DOCX/MD/HTML)
    → ① 加载 Load      解析为纯文本 + 元数据
    → ② 清洗 Clean     去页眉页脚/目录/乱码，修正跨页断句
    → ③ 切分 Split     父子分块 + overlap + 元数据
    → ④ 向量化 Embed   text-embedding-v3 → 1024 维
    → ⑤ 入库 Store     kb_document + kb_chunk

【在线：问答】每轮对话
  用户问题
    → ⑥ 改写 Rewrite  口语 → 检索友好的表达（可选，1 次低成本 LLM 调用）
    → ⑦ 检索 Retrieve 向量召回 Top-20（+ 关键词召回，混合）
    → ⑧ 重排 Rerank   精排取 Top-3~5（可选）
    → ⑨ 过滤 Filter   相似度 < 阈值 → 视为无相关知识，不注入
    → ⑩ 生成 Generate 注入 prompt（带来源）→ 流式输出 → 前端展示引用
```

### 4.2 知识源（Load）

| 类型 | 建议内容 | 注意 |
|---|---|---|
| 权威指南 | 《中国抑郁障碍防治指南》《焦虑障碍防治指南》 | **必须带出版年份**，医学知识有时效性 |
| 量表说明 | PHQ-9 / GAD-7 / PSS-10 的条目解释与计分含义 | 与库里的 `scale` / `scale_item` 数据对齐 |
| 科普材料 | 卫健委心理健康科普、危机干预手册 | 语言通俗，适合直接生成回答 |
| 平台自有 | 医生专长标签、常见 FAQ | 纯文本最省事 |

**解析库**：Apache Tika（`tika-core` + `tika-parsers-standard-package`，PDF/DOCX/HTML 通吃），
或先用 Python 侧（`pdfplumber` / `python-docx`）离线转成 Markdown 再入库 —— **更推荐后者**，
因为中文 PDF 的双栏、页眉页脚、跨页表格清洗在 Python 生态里成熟得多，且离线处理不占 Java 运行时。

> ⚠️ 中文 PDF 三个必查项：① 双栏是否被按行交错拼接；② 页眉页脚是否混入正文；
> ③ 「抑郁」是否被 OCR 成「抑有阝」。**抽 3-5 页人工比对原文**，这一步省不得。

### 4.3 文档分割与切分（Split）—— 最关键的一步

**策略：父子分块（parent-child / small-to-big）**

- **子块**（检索单元）：**300–500 字**，用于向量化与相似度比对 —— 小，语义聚焦，检索准。
- **父块**（生成上下文）：**1200–1500 字**（或完整的「小节」），检索命中子块后返回其父块给 LLM —— 大，信息完整，回答不残缺。

> 为什么不用「单层 500 字」：小块检索准但上下文碎（回答缺前因后果），大块上下文全但向量被稀释（检索不准）。
> 父子分块是二者的折中，也是当前中文医疗 RAG 的主流做法。

**切分算法**（按优先级递归切）：

```
1. 先按章节标题切（Markdown 的 #/##、PDF 的「第三章」「一、」）→ 保证不跨主题
2. 段落内按 \n\n 切
3. 再按单 \n 切
4. 再按中文句末标点切：。！？
5. 仍超长则硬切（兜底，避免死循环）
```

**overlap：子块之间重叠 10–15%（约 50 字）**，防止关键句恰好被切断在两块之间。

**元数据必须一起存**（这是后面能做引用与过滤的前提）：

| 字段 | 作用 |
|---|---|
| `doc_id` / `doc_title` | 引用出处 |
| `section` | 章节标题，如「第 3 章 药物治疗 / 3.2 SSRIs」 |
| `page_from` / `page_to` | 页码，便于人工核查 |
| `publish_year` / `source_type` | **医疗时效性与权威度加权**；「指南」应比「科普」权重高 |
| `chunk_index` / `parent_id` | 父子关系 |
| `token_count` | 预算控制 |

> ⚠️ **切分后必须人工抽检 10 块**。常见翻车：把表格切成一行一行的碎片、
> 把「禁忌症」和「适应症」切到同一块导致语义混淆。

### 4.4 向量化（Embed）—— 参数已核实

**模型**：通义千问 `text-embedding-v3`（与 LLM 同一供应商，一个 API Key 搞定）。

| 参数 | 值 | 说明 |
|---|---|---|
| 接口 | `POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings` | OpenAI 兼容 |
| 维度 | `1024`（v3 默认，可选 768 / 512） | 1024 够用；显存/存储紧张可降 512 |
| 最大输入 | **8192 token / 条** | 子块 500 字远低于上限，安全 |
| **批量上限** | **10 条 / 次** ⚠️ | 一条免费额度也要注意：**不是 25，是 10**，超了报错 |
| `encoding_format` | `float` | 兼容接口仅支持 float |

> ⚠️ **两个真实存在的坑，会直接决定选型**：
>
> **坑 1：OpenAI 兼容接口不支持稀疏向量。** 若需要「稠密 + 稀疏」混合检索（医疗术语精确匹配很吃这个），
> 必须走 DashScope **原生**接口 `POST /api/v1/services/embeddings/text-embedding/text-embedding`，
> 并设 `output_type: "dense&sparse"`。走兼容接口传这个参数会返回 HTTP 200 但 **embedding 为空** ——
> 静默失败，极难排查。
>
> **坑 2：原生接口有 `text_type: query | document` 非对称检索参数。**
> 建库时传 `document`、查询时传 `query`，检索质量比不区分明显更好。
> 兼容接口没有这个参数。**若重视检索质量，建议 embedding 走原生接口、LLM 走兼容接口。**

**归一化**：入库前对向量做 **L2 归一化**，之后余弦相似度退化为点积，检索时可省一次除法。

**成本估算**：`text-embedding-v3` 按 token 计费，1 万块 × 500 字 ≈ 2000 万 token 量级；
**全量重建是可承受的，日常只做增量**。因此索引重建不需要复杂的增量更新算法。

### 4.5 向量存储（Store）

**选型对比**：

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **MySQL 存向量 + 应用层暴力检索** | 零新组件、可解释、SQL 可调试 | 全表扫，> 5 万块性能下降 | ✅ **起步推荐**（本项目知识库规模在千~万级） |
| Redis Stack / RediSearch | 项目已有 Redis，内存检索快 | 普通 Redis **不支持**向量索引，需换 Redis Stack | 中期可选 |
| Milvus / Qdrant / pgvector | 专业向量库，亿级 + ANN 索引 | 要起新服务、多一套运维 | 量大后换 |

**抽象先行**：定义接口，MySQL 先实现，将来换库只加实现类：

```java
public interface VectorStore {
    void upsert(List<ChunkVector> chunks);
    List<ScoredChunk> search(float[] queryVector, int topK, ChunkFilter filter);
    void deleteByDocId(Long docId);
}
```

**MyBatis-Plus 适配的注意点**：`flushCache=true` 的 XML 查询要点见项目硬约定 #10（自定义 SQL 必须手写 `is_deleted = 0`）。

**表结构草案**：

```sql
-- 文档维表
CREATE TABLE kb_document (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  title         VARCHAR(200)  NOT NULL,
  source_type   VARCHAR(32)   NOT NULL,        -- GUIDELINE / SCIENCE / FAQ / SCALE
  publish_year  INT            NULL,           -- 医疗知识时效性，参与加权
  file_path     VARCHAR(500)  NULL,
  file_hash     CHAR(64)       NULL,           -- 去重用（同文件不重复入库）
  chunk_count   INT            NOT NULL DEFAULT 0,
  status        VARCHAR(16)   NOT NULL DEFAULT 'READY',  -- INDEXING / READY / FAILED
  created_at    DATETIME      NOT NULL,
  is_deleted    TINYINT       NOT NULL DEFAULT 0,
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 切块维表（含向量）
CREATE TABLE kb_chunk (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  doc_id        BIGINT       NOT NULL,
  parent_id     BIGINT       NULL,             -- 父子分块：父块行 parent_id 为 NULL
  chunk_index   INT          NOT NULL,
  section       VARCHAR(200) NULL,
  page_from     INT          NULL,
  page_to       INT          NULL,
  content       TEXT         NOT NULL,         -- 子块正文（也是父块正文）
  token_count   INT          NOT NULL DEFAULT 0,
  embedding     JSON         NULL,             -- 1024 维 float 数组；父块不存向量
  -- MySQL 8.0.17+ 支持 JSON 数组；若嫌 JSON 解析慢，改用 VARBINARY + float 拼字节
  embed_model   VARCHAR(32)  NULL,             -- 记录模型名，换模型时可判断需重建
  dim           INT          NULL,
  created_at    DATETIME     NOT NULL,
  KEY idx_doc (doc_id),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> ⚠️ **父块不存向量**（`embedding` 为 NULL）—— 它只被 `parent_id` 引用，不参与检索。
> 这样既省一半存储，也避免「父块被当成候选」污染检索结果。
>
> ⚠️ **`embed_model` + `dim` 必须存**。将来从 v3 换 v4 或改维度时，
> 不做全量重建就会出现「新旧向量混在同一空间」—— 检索结果诡异且极难定位。

**1 万块 × 1024 维 × 4 byte ≈ 40 MB**，含 JSON 开销约 60–80 MB，MySQL 完全无压力。

### 4.6 相似度检索（Retrieve）

**检索公式**（归一化后）：

```
cos(q, d) = q · d = Σ qᵢ·dᵢ        （已 L2 归一化，余弦退化为点积）
```

**混合检索（Hybrid）—— 医疗场景建议上**：

| 召回通道 | 擅长 | 实现 |
|---|---|---|
| **稠密向量** | 语义近似（「心情低落」↔「情绪抑郁」） | 余弦相似度 Top-20 |
| **关键词/稀疏** | 术语精确匹配（「舍曲林」「5-HT」） | MySQL 全文索引（`ngram` 分词器）或 DashScope 稀疏向量 |

两路结果用 **RRF（Reciprocal Rank Fusion）** 融合，无需调权重：

```
score(d) = Σ通道 1 / (k + rank通道(d))     k 通常取 60
```

> 只用稠密向量在医疗场景会翻车：专有名词（药名、DSM 编码）语义上彼此接近，
> 向量分不出「舍曲林」和「氟西汀」的区别，但关键词能。

**重排（Rerank）**：召回 20 → 精排取 3–5。DashScope 提供 `gte-rerank`（Cross-Encoder，
把 query 与 chunk 拼一起打分，比向量点积准得多）。
> ⚠️ Rerank 接口**不在 OpenAI 兼容模式里**，走 DashScope 原生
> `POST /api/v1/services/rerank/text-rerank/text-rerank`，需单独封装。
> 这是可选优化项，**没有它 RAG 也能跑**，别被它卡住进度。

**阈值过滤（防幻觉的关键）**：

```java
public static final double MIN_SCORE = 0.50;   // 归一化余弦，需用真实数据校准
// 低于阈值 → 返回空结果 → prompt 里明确写「知识库中未找到相关内容」
```

> ⚠️ **阈值必须用真实语料校准**，不能拍脑袋。做法：拿 20 个「知识库内问题」和
> 20 个「知识库外问题」各跑一遍，看图找分离点（见 5.2）。
> 阈值定高了会「该答不答」，定低了会拿无关材料编答案 —— **医疗场景宁可前者**。

### 4.7 生成（Generate）—— 防幻觉与引用

**注入格式**（把来源与年份一并给模型，它才能说清可信度）：

```
【参考资料】
[1] 来源：中国抑郁障碍防治指南（2023）· 第 3 章 药物治疗 / 3.2 SSRIs
    内容：……
[2] 来源：PHQ-9 量表说明
    内容：……

【回答要求】
- 只依据上述资料回答；资料不足以回答时，明确说「知识库中暂未收录相关内容」并建议咨询医生；
- 引用资料时标注序号，如「根据指南[1]」；
- 不得编造药品剂量、诊断标准或指南条款；
- 涉及用药必须补充「具体用药请遵医嘱」。
```

**三道防幻觉措施**：

1. **阈值过滤**：检索不到就不注入，并让模型说「不知道」。
2. **显式拒答指令**：把「资料不足时说什么」写死在 prompt 里（模型的默认行为是硬答）。
3. **回答后校验**（可选）：检查回答里的药品名是否都在检索到的资料中出现过，不在则拦截重生成。

**前端展示**：`AiChat.vue` 已有「依据：{量表 · 等级}」的小字 UI（`.bubble-basis`），
把它扩成**引用列表**即可 —— 现成的落点，不用新设计组件。

### 4.8 与现有 `AiConsultService` 的接缝

改动集中在 `buildMessages`，**不动流式主链路**：

```java
private List<ChatMessage> buildMessages(ConsultSession session, String currentContent) {
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(promptBuilder.buildSystemMessage(scaleName, totalScore, riskLevel, stage));

    // ★ 新增：RAG 检索（阻塞 ~200ms，必须在流开始前完成）
    List<ScoredChunk> hits = ragService.retrieve(currentContent);
    if (!hits.isEmpty()) {
        messages.add(ChatMessage.system(ragService.asContextBlock(hits)));  // 带来源
    } else {
        messages.add(ChatMessage.system(NO_KNOWLEDGE_HINT));   // 资料不足时明确告知
    }

    messages.addAll(historyMessages(session));   // 历史（含 token 预算裁剪）
    messages.add(ChatMessage.user(currentContent));
    return messages;
}
```

**四个必须想清楚的点**：

| 问题 | 决策 | 理由 |
|---|---|---|
| 每轮都检索吗？ | **先每轮都检索 + 高阈值过滤**；Phase C 改由 LLM 决定 | 闲聊也检索但阈值挡掉，代价是一次 embedding（~50ms）；比让 LLM 先判断意图更简单可靠 |
| 检索失败怎么办？ | **降级为无 RAG 继续对话**，不阻断 | RAG 是增强不是依赖；知识库挂了不该让聊天也挂 |
| 检索能流式吗？ | **不能**。检索必须在流开始前完成 | 流已经开始才决定要不要检索，客户端无法回退已吐出的内容 |
| 检索结果要不要过危机检测？ | **要** | 知识库内容也可能包含自伤描述，必须与用户输入同标准 |

**Phase C 的演进（Agentic RAG）**：把 `ragService.retrieve` 包成 `searchMedicalKnowledge` 工具，
让 LLM 自己决定何时调用。收益：闲聊不烧检索、多轮追问可改写查询、可组合其他工具。
代价：多一轮 LLM 决策（延迟 + 成本）。**先把 Phase B 跑稳再谈。**

---

## 五、验证方式

### 5.1 分层验证矩阵

| 层 | 验证项 | 方法 | 通过标准 |
|---|---|---|---|
| 单元 | 切分器 | 空文档 / 无标点长文 / 超长表格 / 中英混排 | 不抛异常；块长在 [300,500]±20%；无空块 |
| 单元 | 相似度 | 同向量自比、正交向量 | `cos(v,v)=1.0`；正交 = 0 |
| 单元 | 归属校验 | 用他人 sessionId | 403 |
| 单元 | `PromptBuilder` | 有/无测评上下文、各阶段 | 不出现 `null` 字样（现有的条件注入别退化） |
| 集成 | Embedding | 真实调用（Key 在环境变量） | 返回 1024 维；批量 11 条应报错（验证批量上限 10） |
| 集成 | 入库→检索往返 | 写入已知文本再检索原文 | 自身块排第 1，相似度 > 0.9 |
| 端到端 | 多轮对话 | 第 3 轮问「我刚才说了什么」 | 能复述前文 → 证明上下文生效 |
| 端到端 | 流式 | `curl -N -X POST .../ai-consult/chat` | 看到逐条 `data:`；末尾 `[DONE]`；`event:done` |

### 5.2 检索质量量化（RAG 唯一有意义的验收）

**这是整个 RAG 里最容易被跳过、但最该做的一步。**

1. **建标注集**：从知识库里挑 **20–30 个问题**，人工标注「正确答案在第几块」。
   再准备 **10 个知识库外的问题**（如「今天股市怎么样」）。
2. **指标**：

| 指标 | 定义 | 目标 |
|---|---|---|
| **Recall@5** | Top-5 里含正确块的比例 | ≥ 0.85 |
| **MRR** | 正确块排名倒数的均值 | ≥ 0.7 |
| **拒答准确率** | 库外问题被阈值挡掉的比例 | ≥ 0.9（医疗场景宁严勿松） |

3. **参数调优网格**：块大小 {300, 400, 500} × overlap {0, 50} × 阈值 {0.4, 0.5, 0.6}，
   跑矩阵取最优。**每次只动一个参数**，否则说不清是哪个起了作用。
4. **回归**：把指标写进脚本（扩展现有 `e2e_api_check.py` 的风格），改切分或换模型后必须重跑。

### 5.3 异常与超时验证（逐条制造故障）

| 制造方式 | 期望行为 | 不该出现 |
|---|---|---|
| `LLM_API_KEY` 改成 `sk-wrong` | 降级话术（带 12356 热线） | 500 页面 / 白屏 |
| `timeout-seconds` 改成 1 | 触发超时 → 降级话术 | 请求悬挂不返回 |
| `first-token-seconds` 改成 1，正常调用 | 15s 内无首 token 即降级 | 干等 60 秒 |
| 断网（禁用网卡） | 流内 `event:error` → 前端提示可重试 | 气泡永远停在「▍」 |
| 发送中关掉页面再打开 | AI 已生成部分**已落库**（`doFinally`） | 记录凭空消失 |
| 连续发 3 条消息（不等待） | 前端 `sending` 互斥，只发 1 条 | 并发写乱序 |
| 超长输入（5000 字） | 被 `@NotBlank` 之外的长度校验挡下，或触发摘要压缩 | 上下文超长 400 未处理 |

### 5.4 安全验证

| 验证项 | 期望 |
|---|---|
| 居民 A 用 B 的 `sessionId` 调 `/chat`、`/close`、历史接口 | **403**（当前会通过 —— 修完后必须回归） |
| 输入「我想自杀」（危机词） | **不调用 LLM**，直接返回固定文案 + 生成 CRISIS 工单 + 前端显示热线卡 |
| 审计日志 | 每次 RAG 检索与工具调用可追溯（谁、何时、检索了什么、命中哪块） |
| 知识库内容含自伤描述 | 生成的回答不得复述细节 |

### 5.5 手工验收清单

1. 用 `user01/user123` 登录 → AI 问诊 → 连续对话 5 轮，观察是否**按五阶段推进**（先共情 → 再问症状 → 再问功能影响）。
2. 第 3 轮问「我刚才说了什么」→ 能复述。
3. 问一个知识库内问题（如「PHQ-9 第 9 题是什么」）→ 回答**带来源**。
4. 问一个知识库外问题（如「推荐一家火锅店」）→ 回答「知识库中暂未收录」而非编造。
5. 输入「我不想活了」→ 立刻出固定危机文案 + 右栏热线卡点亮（**不应有 LLM 生成内容**）。
6. 刷新页面 → 能看到历史会话并在原上下文继续。

---

## 六、实施路线

| 阶段 | 内容 | 依赖 | 可独立验证 |
|---|---|---|---|
| **Phase 0（立即，~15 行）** | 修 P0 越权；`doOnComplete`→`doFinally`；补 `scaleName` | 无 | ✅ 403 与落库 |
| **Phase A** | 通义千问接入 + 四道防线（错误码/首 token 超时/流内事件/降级）+ 五阶段提示词 | **DASHSCOPE_API_KEY** | ✅ 5.3 故障注入 |
| **Phase B** | RAG 离线管道（加载/切分/向量化/入库）+ 在线检索（阈值过滤 + 注入）+ 引用展示 | 同上 + 知识源文档 | ✅ 5.2 Recall@5 |
| **Phase B+** | 混合检索 + Rerank + 阈值校准 | Phase B | ✅ 指标提升 |
| **Phase C** | 工具注册表 + Function Calling + MCP Client | Phase A/B | ✅ 工具调用审计日志 |

**建议顺序：Phase 0 先做**（无依赖、修的是真漏洞），**Phase A 与 B 可并行**（一个动 LLM 层、一个动检索层），
**Phase C 最后**（它依赖前两者提供的工具）。

> ⚠️ **Phase A 与 B 都需要 `DASHSCOPE_API_KEY`**。目前 `application.yml` 里是
> `api-key: ${LLM_API_KEY:sk-xxxx}` —— 请配置环境变量后再启动，否则启动不报错但调用必失败
> （这解释了为什么 `MEMORY.md` 里记着「AI 普通对话正文未验（需 LLM_API_KEY）」）。

---

## 七、MCP 扩展路线（后续）

1. **先做本地工具**（Phase C 前半）：用 `AiTool` 接口把手头能力（查测评、推荐医生、查知识库、触发工单）注册进去，
   跑通 Function Calling 的多轮 Agent 循环。
   - ⚠️ 流式与工具调用的**固有冲突**：工具调用需要完整 JSON 参数，**中间态不能流式**。
     做法：① 非流式判定「是否要调工具」→ ② 有则执行 → ③ 拿到结果后**流式**生成最终回答。
2. **再接 MCP**（Phase C 后半）：`McpToolAdapter implements AiTool`，把 MCP Server 的 `tools/list`
   映射成 `AiTool`，`tools/call` 映射成 `execute`。**业务代码一行都不用改** —— 这正是先做本地工具的意义。
3. **医疗场景的硬要求**：每个工具调用都要**审计 + 入参脱敏 + 白名单**。
   例如 `queryMyAssessments` 只允许查**当前登录用户**，参数里的 `userId` 必须由服务端注入而非模型生成
   —— 否则模型可能被诱导去查别人的数据。**这条与 3.5 的越权修复是同一类问题，不能只防接口不防工具。**

---

## 附：与现有代码的对照速查

| 现有文件 | 本次改造 |
|---|---|
| `ai/AiChatClient.java` | 加 `tools` 重载（Phase C 用） |
| `ai/DeepSeekClient.java` | 泛化为 `OpenAiCompatClient`，`.uri` 改相对路径，加错误映射与首 token 超时 |
| `ai/ChatMessage.java` | 可能需要加 `tool_calls` / `tool_call_id` 字段（Phase C） |
| `ai/PromptBuilder.java` | 加五阶段流程、RAG 引用指令、防幻觉条款；`scaleName` 传真实值 |
| `service/AiConsultService.java` | 加 `requireMember`、RAG 注入、流内事件、token 预算、`doFinally` 落库 |
| `controller/AiConsultController.java` | 返回类型改 `Flux<ServerSentEvent<String>>`；新增历史会话接口 |
| `engine/CrisisDetector.java` | 只扩词表，逻辑不动 |
| `api/consult.ts` | SSE 解析加 `event:` 分支；错误分级回调 |
| `stores/chat.ts` | 会话恢复、错误分级、引用列表 |
| `views/consult/AiChat.vue` | 引用来源展示、历史会话入口、错误态细化 |
| `application.yml` | 多供应商配置 + `psycare.rag.*` |
