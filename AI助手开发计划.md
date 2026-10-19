# PsyCare AI 助手开发计划

> 定稿选型：**DeepSeek-V4 做生成 + Milvus 2.6 做检索 + 通义做向量化**。>   
> 本文档是**落地排期与工程设计**，替换 `AI问诊改造设计.md` 中关于「通义千问接入」与「MySQL 存向量」两节，>   
> 但保留其**现状评估**部分作为基线（评估结论：AI 模块完成度约 45%，其中 P0 越权漏洞必须优先修）。
>
> 编制时间：2026-09-29 · 事实核实时间：2026-09-29（DeepSeek 与 Milvus 均为当日实查，非记忆）



---

## 零、先看这三条已核实的事实

这三条不是背景介绍，它们直接决定了架构形状，写错了整个方案都要返工。

### 事实 1：DeepSeek 没有 embedding 接口

官方 API 只有三个模型：`deepseek-v4-flash`（默认主力）、`deepseek-v4-pro`（重推理/Agent）、  
`deepseek-v4-flash-vision-exp`（实验性，支持图片）。**没有 embeddings 端点，没有 `deepseek-embed` 这个模型。**

> [!warning] 旧的 `deepseek-chat` / `deepseek-reasoner` 别名已于 2026-07-24 退役>   
> 现在用这两个名字调用**直接失败，没有回退**。项目 `application.yml` 里配的正是 `deepseek-chat` ——>   
> 也就是说**当前的 AI 对话在你没改配置之前就已经调不通了**。这一条要在 Phase 1 第一件事里修掉。

**架构后果**：RAG 必须拆成「生成侧用 DeepSeek」+「向量化侧用第二家」的两段式。  
所以 `EmbeddingClient` 必须**从第一天就抽象成可替换的**，而不是写死某一家 —— 你后续换 embedding 模型时，只改配置。

### 事实 2：Milvus 2.6 自带 BM25，混合检索不用买额外接口

Milvus 2.6 支持把 `VARCHAR` 原文用服务端 `Function type=BM25` 自动转成稀疏向量，  
建 `SPARSE_INVERTED_INDEX` 索引后，检索时**直接传原始文本**，再用 `RRFRanker` 融合稠密与稀疏两路。

这把「医疗术语精确匹配」（舍曲林 / 5-HT / DSM-5）这件事从"额外供应商"降成了"一次 schema 配置"。  
**正好补上 DeepSeek 缺 embedding 的短板** —— 因为稀疏向量这一路根本不用外部模型。

### 事实 3：Milvus 的向量字段不能为 NULL

Milvus 2.6.2 起向量字段**不可为空**（`nullable vector` 到 2.6.18 才支持）。  
而我们原本的父子分块设计里，**父块是不存向量的**。若把父子块塞进同一个 collection，父块就没有值可填。

**架构后果**：**父块不进 Milvus，只留 MySQL。** Milvus 存「检索单元」，MySQL 存「事实与正文」。  
这不是为了绕开限制而做的妥协 —— 它恰好是更正确的分层（见 2.3）。

---

## 一、关键决策登记

| #  | 决策     | 结论                                                         | 依据                                                                           | 后续影响                                      |
| -- | ------ | ---------------------------------------------------------- | ---------------------------------------------------------------------------- | ----------------------------------------- |
| D1 | 生成 LLM | **DeepSeek-V4**，主力 `deepseek-v4-flash`                     | 你已定；支持原生 function calling / structured output                                | 模型名配置化，启动探活                               |
| D2 | 推理档位   | 实时问诊用 `thinking=disabled`；复杂分析可切 `enabled + effort=medium` | V4 提供 non-think / think / think high / think max 四档，thinking 会显著增加首 token 延迟 | 档位进配置，A/B 对比后再定默认值                        |
| D3 | 向量化    | **通义 embedding（OpenAI 兼容协议接入）**，模型名与 Key 由你提供              | 你已定；DeepSeek 无此能力                                                            | 抽象 `EmbeddingClient`，可切 bge-m3 兜底         |
| D4 | 向量库    | **Milvus 3.0.0，本机单容器内嵌 etcd**（`E:\Milvus\standalone.bat`）      | 你已部署，2026-10-08 探针四断言全绿                                                    | **无** etcd/MinIO 独立容器（`ETCD_USE_EMBED=true` + `COMMON_STORAGETYPE=local`）；Java SDK 对齐 3.0.5 |
| D5 | 稀疏检索   | **Milvus 内置 BM25**（不引外部稀疏向量）                               | 事实 2                                                                         | 中文需配分词器，见 5.5 的坑                          |
| D6 | 事实源    | **MySQL 是事实源，Milvus 是可重建索引层**                              | 事实 3 + 可运维性                                                                  | 提供 rebuild 接口 + 校验脚本                      |
| D7 | 语料     | **AI 合成语料**，全部标记 `source_type=SYNTHETIC`                   | 你已定                                                                          | 只能验链路，不能当医学依据；上线前强制替换                     |
| D8 | 会话状态   | **Redis**（项目已依赖但从未使用）                                      | `pom.xml` 注释写着「Redis：AI会话上下文」                                                | 阶段机、限流、检索缓存都落这里                           |
| D9 | 工具扩展   | 先本地工具，再 `McpToolAdapter`                                   | 你提过后续要做 MCP                                                                  | 两阶段调用（非流式判定 → 执行 → 流式生成）                  |

---

## 二、整体架构

### 2.1 分层视图

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 接入层                                                                     │
│   AiConsultController          POST /ai-consult/chat（SSE 事件流）         │
│   AiHistoryController          GET  /ai-consult/sessions[/{id}/messages]   │
│   KnowledgeAdminController     /admin/kb/**（上传 / 切分预览 / 重建索引）    │
├──────────────────────────────────────────────────────────────────────────┤
│ 编排层（一次问诊请求的生命周期）                                             │
│   AiOrchestrator                                                          │
│     ① SafetyGate          危机词本地拦截 —— 命中即短路，不调模型             │
│     ② StageMachine        读取/推进问诊阶段（Redis）                        │
│     ③ RetrievalPipeline   改写 → 混合检索 → RRF → 阈值 → 回表取父块          │
│     ④ ContextAssembler    分层 prompt 组装 + token 预算裁剪 + 摘要注入        │
│     ⑤ LlmInvoker          调用 + 错误归类 + 首 token 超时 + 重试 + 降级       │
│     ⑥ StreamWriter        SSE 事件协议 + usage 记账 + doFinally 落库         │
├──────────────────────────────────────────────────────────────────────────┤
│ 能力层（全部面向接口，可替换）                                                │
│   AiChatClient        → OpenAiCompatClient（DeepSeek）                     │
│   EmbeddingClient     → QwenEmbeddingClient / BgeM3EmbeddingClient         │
│   VectorStore         → MilvusVectorStore（预留 JdbcVectorStore 兜底）      │
│   Reranker            → NoopReranker / QwenReranker（可选）                 │
│   ToolRegistry        → 内置工具 + McpToolAdapter                          │
├──────────────────────────────────────────────────────────────────────────┤
│ 数据层                                                                     │
│   MySQL   consult_session / consult_message    对话事实源（既有）           │
│           kb_document / kb_chunk               知识事实源（新增）           │
│           ai_usage_log / ai_tool_call_log      观测与审计（新增）           │
│   Milvus  psycare_kb_chunk                     检索索引层（可重建）          │
│   Redis   ai:stage:{sid} / ai:rl:{uid} / ai:cache:emb:{hash}               │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 一次问诊请求的时序

```
用户输入 "最近总是睡不着，还老想哭"
  │
  ├─1 前置安全网（0ms，本地规则）
  │    CrisisDetector 扫描 → 未命中 → 继续
  │    （命中则：直接返回固定安全文案 + 建 CRISIS 工单，LLM 完全不参与）
  │
  ├─2 会话与归属校验
  │    requireMember(sessionId) → 非本人 403；已关闭 400
  │    StageMachine 取当前阶段（Redis miss 则按消息数重建）
  │
  ├─3 检索（阻塞，必须在流开始前完成）
  │    embedding(问题) ──50~200ms──┐
  │    Milvus hybridSearch ─RRF──┐ │
  │    RRF 融合 → 阈值过滤 → 命中 3~5 块
  │    MySQL 回表取父块正文（IN 查询，1 次）
  │    命中为空 → 注入「知识库未收录」指令，不硬答
  │
  ├─4 组装 prompt（分层，见 5.2）
  │    [固定前缀·可缓存] 人格 + 边界 + 流程 + 输出约束
  │    [半稳定]           用户档案摘要 + 测评上下文 + 会话摘要
  │    [易变·放最后]       本轮检索资料 + 最近历史 + 当前输入
  │
  ├─5 调 LLM（流式）
  │    首 token 超时 15s → 降级话术
  │    元素间超时 / 整体 60s 上限
  │
  ├─6 流式输出（SSE 事件）
  │    event:meta → event:citations → event:token ×N → event:usage → event:done
  │    任一阶段异常 → event:error（连接不中断，HTTP 已是 200，改不了）
  │
  └─7 收尾（doFinally，无论正常结束还是客户端断开都执行）
       累计文本落库 consult_message
       回复侧二次危机检测（含检索资料，同标准）
       usage 记账 ai_usage_log + Redis 限流计数
       阶段推进判定 → 写回 Redis
```

### 2.3 三类存储的职责边界（本方案最核心的一条原则）

| 数据               | 落点            | 为什么是它                              |
| ---------------- | ------------- | ---------------------------------- |
| 文档元数据、父块正文、子块正文  | **MySQL**     | **事实源**。可审计、可人工修订、可 diff、重建索引不会丢数据 |
| 子块向量 + 检索所需标量    | **Milvus**    | **索引层**。ANN 检索是它的专长；随时可 drop 重建    |
| 对话记录             | **MySQL**（既有） | 已有表，不重复建设                          |
| 阶段状态 / 限流 / 检索缓存 | **Redis**     | 有 TTL 语义、高频读写、丢了可重建                |

> [!note] 一句话记住这个原则>   
> **Milvus 挂了，重建索引就能恢复；MySQL 挂了，知识就真没了。**>   
> 所以所有写入**先落 MySQL 再写 Milvus**，Milvus 的写入失败只记警告不阻断 —— 后台补一轮 rebuild 即可。>   
> 这条决定了 5.5 里「双写」的实现顺序，也是 Phase 3 验收里「校验脚本」存在的理由。

### 2.4 五个架构原则（写出来是为了后面能据此拒绝需求）

1. **能力层面向接口** —— `AiChatClient` / `EmbeddingClient` / `VectorStore` / `Reranker` 全部是接口 + 工厂选择。换供应商 = 加实现类 + 改配置，不动业务。
2. **安全前置** —— 危机拦截在调模型之前；越权校验在取数据之前。两件事都不能"先做了再检查"。
3. **检索不可流式** —— 流一旦开始吐字就无法回退。所以检索必须在 `buildMessages` 同步完成。
4. **降级必须有出路** —— 任何失败路径的终点都是「带 12356 人工出口的话术」，不能是白屏或沉默。
5. **一切可观测** —— 每次调用记 usage，每次检索记命中的 chunk id，每次工具调用记入参与结果摘要。

---

## 三、模块划分

### 3.1 后端（`psycare-server`，包根 `com.cmh.psycare`）

**A 组 · 接入层**

| 文件                                    | 类型 | 职责                                                    |
| ------------------------------------- | -- | ----------------------------------------------------- |
| `controller/AiConsultController`      | 改造 | 返回 `Flux<ServerSentEvent<String>>`；新增 `/stream` 语义化路径 |
| `controller/AiHistoryController`      | 新增 | 历史会话列表 / 历史消息（**必须过归属校验**）                            |
| `controller/KnowledgeAdminController` | 新增 | 知识库文档 CRUD、切分预览、重建索引、索引状态（**仅 ADMIN**）                |

**B 组 · 编排层**（新增包 `ai/orchestrator`）

| 文件                              | 职责                                      |
| ------------------------------- | --------------------------------------- |
| `orchestrator/AiOrchestrator`   | 唯一入口，串起 1~7 步；不写业务细节，只做流程与异常编排          |
| `orchestrator/StageMachine`     | 阶段读取/推进/回退，状态存 Redis                    |
| `orchestrator/ContextAssembler` | 分层 prompt 组装 + token 预算 + 摘要注入          |
| `orchestrator/LlmInvoker`       | 调用 + 错误归类 + 超时 + 重试 + 降级                |
| `orchestrator/StreamWriter`     | SSE 事件封装 + usage 记账 + `doFinally` 落库    |
| `orchestrator/SafetyGate`       | 复用 `engine/CrisisDetector`，补"检测检索资料"这一路 |

**C 组 · 能力层**（`ai/client`、`ai/rag`、`ai/tool`）

| 文件                                          | 类型          | 职责                                                                                                        |
| ------------------------------------------- | ----------- | --------------------------------------------------------------------------------------------------------- |
| `ai/client/AiChatClient`                    | 改造          | 加 `tools` 重载（为 MCP 留口）                                                                                    |
| `ai/client/OpenAiCompatClient`              | 新增          | 由 `DeepSeekClient` 泛化；base-url / path 全配置化                                                                |
| `ai/client/LlmProperties`                   | 新增          | `@ConfigurationProperties("psycare.llm")`                                                                 |
| `ai/client/LlmException` / `LlmErrorMapper` | 新增          | 错误码归类（见 5.8）                                                                                              |
| `ai/embedding/EmbeddingClient`              | 新增          | 接口：`float[] embed(String)` / `List<float[]> embedBatch(List<String>)`                                     |
| `ai/embedding/OpenAiCompatEmbeddingClient`  | 新增          | 通义兼容协议实现（**你填模型名即可用**）                                                                                    |
| `ai/embedding/QwenNativeEmbeddingClient`    | 可选          | DashScope 原生接口，支持 `text_type` 非对称检索 + 稀疏向量                                                                |
| `ai/rag/DocumentParser`                     | 新增          | 解析 PDF/DOCX/MD/HTML → 纯文本 + 页码 + 章节                                                                       |
| `ai/rag/TextSplitter`                       | 新增          | 父子分块 + 元数据 + overlap                                                                                      |
| `ai/rag/RagProperties`                      | 新增          | `@ConfigurationProperties("psycare.rag")`                                                                 |
| `ai/rag/RetrievalPipeline`                  | 新增          | 检索编排（查询改写 → 混合检索 → RRF → 重排 → 阈值）                                                                         |
| `ai/rag/VectorStore`（接口）                    | 新增          | `upsert` / `search` / `deleteByDoc` / `recreate`                                                          |
| `ai/rag/MilvusVectorStore`                  | 新增          | Milvus 实现（含 collection schema / 索引 / hybridSearch）                                                        |
| `ai/rag/KnowledgeIndexService`              | 新增          | 离线管道编排：解析→清洗→分块→嵌入→双写                                                                                     |
| `ai/rag/JdbcVectorStore`                    | 可选          | 兜底实现（Milvus 不可用时降级为暴力检索）                                                                                  |
| `ai/tool/AiTool` / `ToolRegistry`           | 新增          | 工具抽象与注册表                                                                                                  |
| `ai/tool/builtin/*`                         | 新增          | `QueryMyAssessmentsTool` / `RecommendDoctorsTool` / `SearchMedicalKnowledgeTool` / `EscalateToDoctorTool` |
| `ai/tool/McpToolAdapter`                    | 新增（Phase 5） | MCP Server 工具 → `AiTool` 映射                                                                               |
| `ai/PromptBuilder`                          | 改造          | 分层重构 + 五阶段流程 + RAG 引用指令 + 防幻觉条款                                                                           |
| `ai/InterviewStage`                         | 新增          | 阶段枚举                                                                                                      |
| `service/AiConsultService`                  | 改造          | 瘦身为「薄壳」，逻辑搬到 orchestrator；补 `requireMember`                                                               |

**D 组 · 数据与实体**

| 文件                                          | 类型 | 说明                                                |
| ------------------------------------------- | -- | ------------------------------------------------- |
| `entity/KbDocument` / `KbChunk`             | 新增 | MyBatis-Plus 实体                                   |
| `entity/AiUsageLog` / `AiToolCallLog`       | 新增 | 观测与审计                                             |
| `mapper/KbDocumentMapper` / `KbChunkMapper` | 新增 | 含自定义 SQL（**手写 `is_deleted = 0`**，硬约定 #10）         |
| `entity/ConsultSession`                     | 改造 | 启用 `summary`（已存在未用）；新增 `stage` / `last_active_at` |
| `sql/v4_rag_ddl.sql`                        | 新增 | 知识库两表                                             |
| `sql/v5_observability_ddl.sql`              | 新增 | 观测两表                                              |
| `sql/v6_alter_consult_session.sql`          | 新增 | `consult_session` 加列                              |

**E 组 · 配置与跨切面**

| 文件                                        | 改造点                                                          |
| ----------------------------------------- | ------------------------------------------------------------ |
| `application.yml`                         | `psycare.llm.*` / `psycare.embedding.*` / `psycare.rag.*` 三段 |
| `common/util/SecurityUtil`                | 复用 `requireRole` / `getUserId`，不新增                           |
| `common/exception/GlobalExceptionHandler` | 补 `MethodArgumentTypeMismatchException`（既有待办，顺手修）            |

### 3.2 前端（`psycare-web/src`）

| 文件                              | 类型 | 改动                                      |
| ------------------------------- | -- | --------------------------------------- |
| `api/consult.ts`                | 改造 | SSE 解析增加 `event:` 分支；回调改为事件分发           |
| `api/knowledge.ts`              | 新增 | 知识库管理接口                                 |
| `stores/chat.ts`                | 改造 | 阶段展示、引用列表、错误分级、历史会话恢复                   |
| `views/consult/AiChat.vue`      | 改造 | 引用来源卡片、阶段指示、历史会话抽屉、错误态细化                |
| `views/admin/KnowledgeBase.vue` | 新增 | 文档列表 / 上传 / 切分预览 / 重建索引 / 索引进度          |
| `types/index.ts`                | 改造 | `SseEvent` 联合类型、`Citation`、`KbDocument` |
| `layouts/AdminLayout.vue`       | 改造 | 新增「知识库」菜单（**仅 ADMIN**）                  |


> [!warning] 前端有硬预算，别再随手加 antd 组件
> `vendor-antd` 当前 **790.77 kB / 800 kB，只剩约 9 kB**。知识库页需要表格 → 复用现有 `a-table`；
> 上传用原生 `<input type="file">` + 手写样式，**不要引 `a-upload`**（会超预算）。
> 引用卡片、阶段指示条一律纯 CSS。

---

## 四、技术选型

| 维度 | 选型 | 版本 | 理由 | 备选 |
|---|---|---|---|---|
| 生成 LLM | DeepSeek-V4 | `deepseek-v4-flash` 主力 | 你已定；中文强；价格低（闲时半价）；原生 function calling | `deepseek-v4-pro`（复杂推理档） |
| 调用协议 | OpenAI 兼容 | `POST {base}/chat/completions` | 与现有 `WebClient` 实现一致，零迁移成本 | Anthropic 格式（**不支持 MCP tool-use blocks**，不用） |
| 向量化 | 通义 embedding | 模型名由你提供 | 你已定 | 硅基流动 bge-m3（同协议，改 3 行配置即可切） |
| 向量库 | Milvus | **3.0.0**（实测环境，2026-10-08 核实） | 你已部署且探针四断言全绿；3.0 起向量字段可为 NULL | Qdrant / pgvector |
| Milvus SDK | `milvus-sdk-java` | **3.0.5**（官方对照表 Milvus 3.0.0 一行） | 官方版本映射：Milvus 3.0.0 ↔ Java SDK 3.0.5。**中仓已核实存在**（3.0.0~3.0.10 全部可达） | 2.6.x 会缺 3.0 的 API；`milvus-sdk-java-bulkwriter` 本项目不需要 |
| 度量 | COSINE（稠密）/ BM25（稀疏） | — | COSINE 免手工归一化，IP 归一化后等价但多个前置条件 | IP |
| 融合 | `RRFRanker(K=60)` | — | 无需调权重，医疗场景优先稳健 | `WeightedRanker` |
| 文档解析 | **Python 侧离线**（pdfplumber / python-docx / markdown） | — | 中文 PDF 双栏、页眉页脚、跨页表格在 Python 生态成熟得多；且离线处理不占 Java 运行时 | Apache Tika（若必须纯 Java） |
| 分块 | 父子分块（small-to-big） | 子 300–500 字 / 父 1200–1500 字 | 兼得检索精度与上下文完整性 | 语义分块（成本高，暂不上） |
| 状态存储 | Redis（既有） | — | `pom.xml` 已依赖，从未使用；TTL 语义正好 | MySQL 表（不必要） |
| 观测 | Micrometer + 结构化日志 + 自建报表表 | — | 项目无 Prometheus 环境，先用日志 + 表最务实 | Prometheus + Grafana（上线后加） |
| 限流 | Redis 计数器（滑动窗口） | — | 单用户日配额 + 每分钟速率双限 | Bucket4j（可选） |

---

## 五、AI 助手模块详细设计（核心）

### 5.1 提示词分层架构（企业化的第一个抓手）

**目标：让多轮对话的 prompt 前缀字节级稳定，最大化 DeepSeek 的自动上下文缓存命中。**

DeepSeek 的定价里 cache-hit 输入比 cache-miss **便宜一个数量级**。而缓存生效的前提是**前缀完全一致**。
所以 prompt 的组装顺序不是随意的，必须按「稳定性」排序：

| 层 | 内容 | 稳定性 | 位置 |
|---|---|---|---|
| L1 人格与身份 | 你是谁、服务谁、语气 | **永久不变** | 最前 |
| L2 安全与边界 | 不做诊断/不开药/不替代医生；危机处理指令 | **永久不变** | 紧接 L1 |
| L3 输出约束 | 字数上限、引用格式、拒答话术模板 | **永久不变** | 紧接 L2 |
| L4 流程引导 | 五阶段定义与推进规则 | **永久不变** | 紧接 L3 |
| L5 档案与摘要 | 测评上下文、会话摘要、阶段状态 | 会话级，变化少 | 中间 |
| L6 检索资料 | 本轮命中的知识块 | **每轮变** | 靠后 |
| L7 对话历史 | 最近 N 轮（含预算裁剪） | **每轮变** | 更后 |
| L8 当前输入 | 用户这句话 | **每轮变** | 最后 |

> [!warning] L1–L4 里**严禁**出现时间戳、随机 id、会话 id、用户昵称
> 一旦写进去，每轮 prompt 前缀都不同，缓存 100% 不命中 —— 而且**不会有任何报错**，
> 只会在账单上体现。这是最典型的"静默失效"。把易变信息全部下推到 L5 之后。

**实现骨架**：

```java
public List<ChatMessage> build(AssembleContext ctx) {
    List<ChatMessage> msgs = new ArrayList<>(8);
    msgs.add(ChatMessage.system(PromptAssets.IDENTITY));        // L1 常量
    msgs.add(ChatMessage.system(PromptAssets.SAFETY_BOUNDARY)); // L2 常量
    msgs.add(ChatMessage.system(PromptAssets.OUTPUT_RULES));    // L3 常量
    msgs.add(ChatMessage.system(PromptAssets.INTERVIEW_FLOW));  // L4 常量

    msgs.add(ChatMessage.system(profileBlock(ctx)));            // L5 会话级
    if (!ctx.retrieved().isEmpty()) {
        msgs.add(ChatMessage.system(ragBlock(ctx.retrieved())));// L6 带来源
    } else {
        msgs.add(ChatMessage.system(NO_KNOWLEDGE_HINT));        // L6 明确告知无资料
    }
    msgs.addAll(trimToBudget(ctx.history(), ctx.budget()));     // L7 预算裁剪
    msgs.add(ChatMessage.user(ctx.userInput()));                // L8
    return msgs;
}
```

四个常量放 `ai/PromptAssets.java`（`static final String`），**改文案不改代码**，也便于将来外置成资源文件做热更新。

### 5.2 引导问诊阶段机

现状 `PromptBuilder` 只有边界约束，没有流程 —— 模型会退化成"百科全书式问答"：用户问什么答什么。

**五阶段定义**：

| 阶段 | 枚举 | 目标 | 进入下一阶段的条件 |
|---|---|---|---|
| 1 建立联结 | `OPENING` | 共情、确认来意、说明边界 | 用户已表达具体困扰（非寒暄），或轮次 ≥ 2 |
| 2 主诉澄清 | `CHIEF_COMPLAINT` | 症状表现 / 起始时间 / 频率 / 强度 | 关键字段齐（≥3 项），或轮次 ≥ 4 |
| 3 功能影响 | `FUNCTION_IMPACT` | 睡眠 / 食欲 / 工作学习 / 人际 | 至少覆盖 2 个维度，或轮次 ≥ 3 |
| 4 风险筛查 | `RISK_SCREENING` | 自伤/自杀意念（**危机词已在本地前置拦截**） | 已完成一次询问并得到回应 |
| 5 小结转介 | `SUMMARY_REFERRAL` | 复述理解 + 1–2 条建议 + 何时就医 | 结束或用户换话题回退 |
| — | `ENDED` | 会话关闭 | 用户主动结束 |

**推进决策用「规则 + LLM 双确认，服务端裁决」**：

```java
// 规则先判 —— 可测、可解释
StageDecision byRule = StageRules.evaluate(stage, turnCount, collectedFields);
// LLM 建议 —— 结构化输出，只作为输入
StageDecision byLlm = llmSuggestion;   // {"suggestNextStage":"FUNCTION_IMPACT","confidence":0.7}
// 服务端裁决：规则说"该进"且 LLM 不反对 → 进；规则说"没到时候" → 一律不进
return decide(byRule, byLlm);
```

> [!note] 为什么不让 LLM 单独决定阶段
> 一是不可测（同一个输入两次可能给不同阶段），二是可被诱导（用户在 prompt 里写"我们现在进入第五阶段"）。
> **规则是权威，LLM 只是建议** —— 这条与 5.9 的工具参数「服务端注入 userId」是同一个安全思路。

**状态存储（Redis）**：

```
key   ai:stage:{sessionId}
value {"stage":"CHIEF_COMPLAINT","turns":3,"fields":{"duration":"3个月","frequency":"每周3次"},"updatedAt":...}
ttl   2h（滑动续期）
miss  → 按 consult_message 条数与关键词重建（幂等），避免 Redis 重启丢状态就废掉会话
```

### 5.3 上下文与记忆

| 项 | 现状 | 目标设计 |
|---|---|---|
| 裁剪依据 | 按**条数**（`max-history*2`） | 按 **token 预算**（`max-context-tokens`），从最近往前累加 |
| 早远内容 | 直接丢弃 | **摘要压缩**写入 `consult_session.summary`（字段已存在未用，零 DDL） |
| 摘要时机 | — | 触发压缩后异步执行，**不阻塞**当前回复；下次请求才生效 |
| 历史读取 | 无入口 | `GET /ai-consult/sessions` + `/sessions/{id}/messages` |
| 长消息 | 一条 3000 字可顶掉整个窗口 | 单条超 `single-message-max-chars` 时截断并提示 |

**token 估算**（不引入 tokenizer 依赖的务实做法）：

```java
// 中文按 1 字 ≈ 1 token 保守估（DeepSeek 中文实际约 0.6~0.7，宁可高估）
static int estimate(String text) {
    int cjk = 0, other = 0;
    for (char c : text.toCharArray()) { if (c >= 0x4E00 && c <= 0x9FFF) cjk++; else other++; }
    return cjk + (other / 4) + 8;   // +8 为消息结构开销
}
```

> [!note] 为什么不接真 tokenizer
> 引入 `jtokkit` / `tiktoken` 会多一个依赖和一次编码开销，而估算偏保守 10–20% 对成本影响可忽略。
> **等你实测发现预算经常估偏再换** —— 这是个可以推迟的优化。

### 5.4 RAG 离线管道

```
【阶段一：语料生成】AI 合成（因你尚无文档）
  topics.yaml（主题清单）
    → gen_synthetic_corpus.py 调 DeepSeek 生成结构化 JSONL
    → 人工抽检 10 条
    → corpus/*.jsonl

【阶段二：解析与清洗】
  PDF/DOCX → pdfplumber / python-docx → 纯文本 + 页码 + 章节树
    清洗：去页眉页脚、去目录页、修跨页断句、合并双栏错行
    ⚠️ 抽 3–5 页与原文人工比对（中文 PDF 三个必查项见 5.4.2）

【阶段三：父子分块】
  按章节切 → 段落 → 单行 → 中文句末标点 → 硬切兜底
  子块 300–500 字（检索单元，存向量）
  父块 1200–1500 字（生成上下文，不存向量）
  overlap 10–15%（约 50 字）

【阶段四：向量化】
  EmbeddingClient.embedBatch(parentTexts) —— 批量 10 条/次（通义限制）
  失败重试 3 次（指数退避），单条永久失败则记 FAILED 不阻断整批

【阶段五：入库（双写）】
  ① MySQL：kb_document + 首次写入父块与子块正文（事实源，先写）
  ② Milvus ：子块向量 + 检索元数据（索引层，后写；失败仅告警）
  ③ 回填 kb_document.status = READY / chunk_count
```

#### 5.4.2 中文 PDF 的三个必查项

| 问题 | 表现 | 检查方式 |
|---|---|---|
| 双栏错行 | 左右栏按行交错拼接，句子错乱 | 抽 3–5 页与原文逐句比对 |
| 页眉页脚混入 | 每块都带"第 12 页 / 中国抑郁障碍防治指南" | 统计高频重复行并剔除 |
| OCR 错字 | 「抑郁」→「抑有阝」 | 对关键词做词频异常检测（"抑郁" 出现 0 次即异常） |

#### 5.4.3 合成语料的设计（你选的路线）

> [!warning] 合成语料只能验链路，不能作为医学依据
> 它本质是"模型关于医疗知识的记忆的再表述"，**可能包含错误**。
> 它的合法用途有三个：① 打通链路 ② 构建检索评测集 ③ 演示。
> **不得用于任何对外回答**。因此全链路强制标记 `source_type = 'SYNTHETIC'`，
> 前端引用卡片必须显示「合成示例数据」角标，上线前由 `rebuild` 流程整体替换。

**主题清单要与项目现有数据对齐**（这样 AI 能引用「你上个月 PHQ-9 是 12 分」这类具体事实）：

| 主题域 | 条目数 | 与现有表的关系 |
|---|---|---|
| 量表条目解释 | 3 表 × 条目数 | 对齐 `scale` / `scale_item` |
| 计分与分级含义 | ~15 | 对齐 `scale.level` 分级规则 |
| 常见症状科普 | ~30 | 与 `AlertEngine` 的预警类型对应 |
| 危机干预流程 | ~10 | 与 `CrisisDetector` 词表分工（知识 vs 拦截） |
| 药物常识 | ~20 | **必须含「遵医嘱」限定语** |
| 就诊与转介指引 | ~10 | 与医生推荐逻辑对应 |

**输出契约**（JSONL，一行一条）：

```json
{"doc_title":"PHQ-9 患者健康问卷条目说明","source_type":"SYNTHETIC","publish_year":2024,
 "section":"第 2 节 条目解读 / 2.3 第 9 题","content":"第 9 题询问……","tags":["PHQ-9","自伤意念"]}
```

**一个必须说清的判断**：用合成语料做检索评测（Recall@5）是**有效的** —— 因为我们测的是"能不能从库中找回对应块"这个检索能力，与内容是否正确无关。但**医学正确性无法用这套数据验证**，需要真实权威语料 + 人工审核。这两件事不能混为一谈。

### 5.5 Milvus 集成设计

#### 5.5.1 Collection schema

集合名 `psycare_kb_chunk`，主键复用 MySQL 的 `kb_chunk.id`（**不 autoID** —— 双库 id 一致是回表查询的前提）。

| 字段 | Milvus 类型 | 说明 |
|---|---|---|
| `chunk_id` | Int64, PK, autoID=false | = MySQL `kb_chunk.id` |
| `doc_id` | Int64 | 过滤 + `deleteByDoc` |
| `parent_id` | Int64 | 父块 id（回表取正文用） |
| `text` | VarChar(4096), `enableAnalyzer=true` | 子块正文，供 BM25 分析 |
| `dense_vector` | FloatVector(dim=1024) | 通义 embedding 输出 |
| `sparse_vector` | SparseFloatVector | **由 BM25 Function 自动生成，不手工写** |
| `doc_title` | VarChar(256) | 引用展示 |
| `source_type` | VarChar(32) | 过滤 + 权威度加权 |
| `publish_year` | Int32 | 医疗时效性加权 |
| `page_from` / `page_to` | Int32 | 溯源 |
| `token_count` | Int32 | 预算控制 |

> [!warning] 父块不进 Milvus
> 因为向量字段**不能为 NULL**（事实 3）。父块正文只存 MySQL，检索命中子块后用 `parent_id` 回表取。
> 回表是**一次 `IN` 查询**，批量取 3–5 个父块，成本可忽略。

#### 5.5.2 索引与检索

```java
// 稠密：HNSW + COSINE（免手工归一化）
IndexParam.builder().fieldName("dense_vector")
    .indexType(IndexParam.IndexType.HNSW)
    .metricType(IndexParam.MetricType.COSINE)
    .extraParams(Map.of("M", 16, "efConstruction", 200)).build();

// 稀疏：BM25 倒排
IndexParam.builder().fieldName("sparse_vector")
    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
    .metricType(IndexParam.MetricType.BM25)
    .extraParams(Map.of("inverted_index_algo", "DAAT_MAXSCORE")).build();

// 标量索引：加速过滤
IndexParam.builder().fieldName("doc_id").indexType(IndexParam.IndexType.INVERTED).build();
IndexParam.builder().fieldName("source_type").indexType(IndexParam.IndexType.INVERTED).build();
```

**BM25 Function 声明**（把 `text` 自动转成 `sparse_vector`）：

```java
schema.addFunction(CreateCollectionReq.Function.builder()
    .functionType(FunctionType.BM25)
    .name("bm25_text_to_sparse")
    .inputFieldNames(Collections.singletonList("text"))
    .outputFieldNames(Collections.singletonList("sparse_vector"))
    .build());
```

**混合检索**：

```java
AnnSearchReq dense = AnnSearchReq.builder()
    .vectorFieldName("dense_vector")
    .vectors(Collections.singletonList(new FloatVec(queryVector)))   // 向量走 FloatVec
    .metricType(IndexParam.MetricType.COSINE)
    .params("{\"ef\": 128}").topK(20).build();

AnnSearchReq sparse = AnnSearchReq.builder()
    .vectorFieldName("sparse_vector")
    .vectors(Collections.singletonList(new EmbeddedText(queryText))) // 文本走 EmbeddedText
    .metricType(IndexParam.MetricType.BM25)
    .params("{\"drop_ratio_search\": 0.2}").topK(20).build();

SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
    .collectionName("psycare_kb_chunk")
    .searchRequests(List.of(dense, sparse))
    .ranker(new RRFRanker(60))
    .topK(finalTopK)
    .outFields(List.of("chunk_id","doc_id","parent_id","doc_title","source_type","publish_year"))
    .build());
```

> [!warning] 四个会踩的坑，都已核实
> **坑 1 —— 稀疏那一路必须用 `new EmbeddedText(text)`**，用 `FloatVec` 会抛
> `ParamException: Search target vector type is illegal`（这是官方 issue #1402 的原样报错）。
> **坑 2 —— 中文 BM25 必须配分词器。** 默认 analyzer 是标准分词（按空格/标点切），
> 中文没有空格 → 整句被当成一个 token → **BM25 退化成整句精确匹配，几乎必然召回 0**。
> 需在 `analyzer_params` 里配置中文分词（jieba 一类）。
> 这一条**必须在 Phase 3 的环境探针里实测确认**，不能假设。若当前 Milvus 版本不支持，
> 兜底方案：入库前在 Java 侧用分词结果拼一个 `text_tokenized` 字段供 BM25 用。
> **坑 3 —— collection 必须 `loadCollection` 之后才能 search**，且刚写完立刻检索建议显式 `flush`，
> 否则因一致性级别看不到数据（表现为"明明写进去了却检索不到"）。
> **坑 4 —— COSINE 与 IP 不能混用**：索引建 COSINE，查询也必须是 COSINE。用 L2 索引发 COSINE 查询会直接报错。


#### 5.5.3 环境探针（Phase 3 第一件事）

一个 `milvus_probe.py`（或 Java 侧等价类），**四个断言依次通过才进入开发**：

> [!success] 已于 2026-10-08 在本机 `milvusdb/milvus:v3.0.0` 上**实测四断言全绿**（4/4）
> 脚本：`.workbuddy/scripts/milvus_probe.py`（零依赖，走 REST v2）。
> 实测结论：**中文 BM25 无需 Java 侧预分词兜底**（坑 2 的兜底方案不启用）；
> REST v2 在 3.0 上路径与参数**完全向后兼容**，探针与 Java SDK 语义一致。

| # | 断言 | 通过标准 | 失败时的排障方向 |
|---|---|---|---|
| 1 | 连接 | `MilvusClientV2(uri)` 成功、版本号可见 | 容器是否在跑（本机为**单容器**，etcd 内嵌、无 MinIO）；19530 是否监听 |
| 2 | 建集合 | schema + BM25 Function 创建成功 | SDK 版本与服务版本是否对齐（**Milvus 3.0.0 ↔ Java SDK 3.0.5**） |
| 3 | 写入 + 读回 | 插入 10 条中文，按 `chunk_id` 查得回 | consistency level；是否 `flush` |
| 4 | **中文 BM25 召回** | 用中文关键词命中对应块 | 分词器配置（坑 2） |

### 5.6 在线检索流程

```
问题 "舍曲林有什么副作用"
  │
  ├─ 查询改写（可选，开关控制）
  │    口语 → 检索友好表达；闲聊时跳过（用启发式：长度 < 6 字或命中闲聊词表则跳过改写）
  │
  ├─ 并行两路召回
  │    dense  : embed(query) → HNSW COSINE topK=20
  │    sparse : EmbeddedText(raw) → BM25 topK=20
  │
  ├─ RRF 融合（服务端 K=60）→ 候选 20
  │
  ├─ 阈值过滤：score < MIN_SCORE → 丢弃（医疗场景宁可不答）
  │
  ├─ 重排（可选，默认关）：取 top 5
  │
  └─ 回表：MySQL 取父块正文 → 组装 [1][2][3] 带来源
```

**四个必须想清楚的决策**：

| 问题 | 决策 | 理由 |
|---|---|---|
| 每轮都检索吗？ | **是**，靠阈值挡掉闲聊 | 一次 embedding ~50–200ms；比让 LLM 先判意图更简单可测 |
| 检索失败怎么办？ | **降级为无 RAG 继续对话**，不阻断 | RAG 是增强不是依赖；Milvus 挂了不该让聊天也挂 |
| 检索结果要过危机检测吗？ | **要** | 知识库内容也可能含自伤描述，必须与用户输入同标准 |
| 检索缓存吗？ | **是**，Redis `ai:cache:emb:{sha256(text)}`，TTL 24h | 同一问题重复问很常见；embedding 是外部调用，最该缓存 |

**注入格式**（防幻觉的关键写在 prompt 里）：

```
【参考资料】
[1] 来源：XXX 文档（2024）· 第 3 章 / 3.2 节 ｜ 类型：合成示例数据
    内容：……
[2] ……

【回答要求】
- 只依据上述资料回答；资料不足以回答时，明确说「知识库中暂未收录相关内容」并建议咨询医生；
- 引用时标注序号，如「根据资料[1]」；
- 不得编造药品剂量、诊断标准或指南条款；
- 涉及用药必须补充「具体用药请遵医嘱」；
- 若资料来源标记为「合成示例数据」，必须说明这是示例内容、不可作为医疗依据。
```

### 5.7 SSE 事件协议（前后端契约，必须同时改）

**后端返回 `Flux<ServerSentEvent<String>>`**：

| event | data | 时机 | 前端行为 |
|---|---|---|---|
| `meta` | `{"sessionId":12,"messageId":88,"stage":"CHIEF_COMPLAINT"}` | 首个事件 | 记录 id、渲染阶段指示条 |
| `citations` | `[{"docId":3,"title":"…","section":"…","score":0.82,"synthetic":true}]` | 检索完成、token 之前 | 渲染引用卡片 |
| `token` | 增量文本 | 生成中 | 追加到气泡 |
| `usage` | `{"promptTokens":…,"completionTokens":…,"cachedTokens":…}` | 正常结束前 | 记入调试面板 |
| `error` | `{"code":"LLM_RATE_LIMITED","msg":"…"}` | 任何阶段出错 | 气泡内联错误 + 重试按钮 |
| `done` | `[DONE]` | 正常结束 | 结束 `streaming` 态 |

```java
private ServerSentEvent<String> sse(String event, String data) {
    return ServerSentEvent.<String>builder().event(event).data(data).build();
}
```

> [!warning] 这是**破坏性协议变更**，两端必须同时上线
> 现有 `api/consult.ts` 只认 `data:` 行、忽略 `event:`。若只改后端，
> 前端会把 `error` 事件里的 JSON **当成正常 token 拼进气泡** —— 用户会看到 `{"code":"LLM_...` 出现在聊天内容里。
> 兼容做法：Phase 1 先只加 `error`/`done` 两类（前端已是 JSON 字符串兜底），`citations` 等到 Phase 4 再启用。

### 5.8 异常、超时与降级（四道防线）

**① 错误归类**（`LlmErrorMapper`）

| 错误码 | 触发 | 动作 | 绝不做 |
|---|---|---|---|
| `LLM_KEY_INVALID` | 401 / 403 | 立即降级 + 告警 | **不重试**（重试必然还失败） |
| `LLM_RATE_LIMITED` | 429 | 指数退避重试 1 次（500ms 起） | 不并发重试 |
| `LLM_BAD_REQUEST` | 400 | 疑似上下文超长 → 压缩历史后重试 1 次 | 不原样重试 |
| `LLM_MODEL_NOT_FOUND` | 400 且 body 含 model | 提示模型名失效（**旧别名退役就属这类**） | 不静默吞掉 |
| `LLM_TIMEOUT` | 超时 | 重试 1 次 → 降级 | 不无限等 |
| `LLM_UPSTREAM_ERROR` | 5xx | 重试 1 次 → 降级 | — |

> [!note] DeepSeek 的 400 有两种语义
> "Model Not Exists" 与"上下文超长"都返回 400，**必须看 body 区分**，
> 否则会把「模型名写错」当成「上下文超长」去压缩历史，越压越错。

**② 首 token 超时**（当前完全缺失，体验提升最大）

```java
return upstream
    .timeout(Duration.ofSeconds(props.getFirstTokenSeconds()))  // 元素间超时，天然覆盖首 token
    .take(Duration.ofSeconds(props.getTimeoutSeconds()));       // 整体上限，到点截断
```

> 更正一处此前文档的表述错误：Reactor 的 `timeout(Duration)` 语义是**元素间**超时（含首个元素），
> 不存在 `timeout(首信号, 后续信号)` 的双参数重载（那是 RxJava 的 API）。
> 上面这两行组合就是 Reactor 下的正确写法，**不需要引入额外依赖**。
> 核心要点不变：**「15 秒没吐第一个字」与「吐到一半卡住」是两种故障**，前者立刻降级、后者断流。

**③ 流内错误协议** —— 见 5.7，错误走 `event:error`，不靠中断连接表达。

**④ 降级话术**（医疗场景必须是"安全降级"）

```java
private static final String FALLBACK =
    "抱歉，智能助手暂时无法连接。你可以稍后重试，"
  + "也可以直接在「我的消息」里联系医生。"
  + "如果情况比较紧急，请拨打全国心理援助热线 12356。";
```

> 降级话术**必须带人工出口**。只写"服务不可用"在心理健康场景是不合格的 —— 用户可能正处于危机中，
> 一句冷冰冰的报错会直接终结求助行为。

**⑤ 落库改 `doFinally`** —— 客户端断开时 `doOnComplete` 不触发，导致已生成内容丢失。
改为累计缓冲区 + `doFinally` 落库，**只落已生成的部分**，并标记该条消息 `interrupted`。

### 5.9 工具调用与 MCP 路线

**接口**（从第一天就设计对，以后接 MCP 不改业务代码）：

```java
public interface AiTool {
    String name();                        // queryMyAssessments
    String description();                 // 给 LLM 看的自然语言描述
    Map<String, Object> schema();         // JSON Schema（OpenAI function calling 格式）
    ToolResult execute(ToolContext ctx, Map<String, Object> args);
}

public record ToolContext(Long userId, Long sessionId, String traceId) {}  // ★ userId 由服务端注入
```

> [!warning] 医疗场景工具调用的三条铁律
> **① `userId` 绝不从模型生成的参数里取** —— 必须来自 `ToolContext`（服务端注入）。
> 否则用户说"帮我查一下 user02 的测评"就可能被模型照做。**这与 5.10 的越权问题是同一类，不能只防接口不防工具。**
> **② 每个工具调用都要审计** —— 入参脱敏后写 `ai_tool_call_log`。
> **③ 工具白名单** —— 注册表之外的名字一律拒绝执行，不做"动态发现即信任"。

**首批 4 个工具**（都是封装已有能力，不需要新写业务）：

| 工具 | 复用 | 价值 |
|---|---|---|
| `queryMyAssessments` | `AssessmentService` | AI 能引用「你上次 PHQ-9 是 12 分」 |
| `recommendDoctors` | `RecommendationEngine` | 对话中直接推荐，而非仅右栏静态展示 |
| `searchMedicalKnowledge` | Phase 4 的 `RetrievalPipeline` | **Agentic RAG**：让模型自己决定何时检索 |
| `escalateToDoctor` | `ChatService` | 危机时主动建立医患会话 |

> [!note] RAG 本身就是一种工具
> Phase 4 是「每轮预检索 + 高阈值」（可靠、低延迟），Phase 5 演进为「LLM 决定何时调用 `searchMedicalKnowledge`」
> （省 token、可多轮改写查询）。**用同一套 `AiTool` 抽象，演进不需要重写检索逻辑。**

**流式与工具调用的固有冲突**：工具调用需要完整 JSON 参数，**中间态不能流式**。做法是三段式：

```
① 非流式判定（带 tools 调用一次，只问"要不要用工具、用哪个、参数是什么"）
② 执行工具（服务端，带审计与权限）
③ 拿到结果后，启用流式生成最终回答
```

**MCP 接入**（Phase 5 后半）：

```java
public class McpToolAdapter implements AiTool {
    // MCP tools/list  → AiTool.name/description/schema
    // MCP tools/call  → AiTool.execute
}
```

两种 transport：stdio（本地进程）与 SSE/HTTP（远程）。医疗场景**推荐 stdio + 本地白名单 Server** —— 远程 MCP Server 的调用链会离开你的审计范围。

> DeepSeek 本身不提供 MCP connector，但它的 tool-calling 是标准 OpenAI 结构，
> 与任何 GPT/Claude 形状的 MCP Client 都能互通。**MCP 的适配层要写在你的应用侧**（`McpToolAdapter`），
> 不要试图让模型直连 MCP Server —— 那样审计和拦截能力都会丢失。

### 5.10 安全与合规

| 项 | 设计 | 位置 |
|---|---|---|
| **会话越权（P0）** | `requireMember(sessionId)`：非本人 403 | 所有会话相关接口，含历史接口 |
| 危机拦截 | 本地词表前置，命中不调模型 | `SafetyGate`（复用 `CrisisDetector`） |
| 回复侧二次检测 | AI 回复 + **检索资料**一并扫 | `StreamWriter` 收尾 |
| 工具越权 | `userId` 服务端注入 + 白名单 + 审计 | `ToolRegistry` |
| 管理端接口 | `SecurityUtil.requireRole("ADMIN")` 后端独立断言 | `KnowledgeAdminController` |
| 数据脱敏 | AI 日志里用户输入仅记 hash 与长度，不记全文 | `ai_usage_log` |
| 合规边界 | 不做诊断、不开药、不替代医生；涉药必带"遵医嘱" | L2 常量层 |
| 免责声明 | 会话首条固定播报 | 前端 + L1 层 |
| 审计留痕 | 谁、何时、问诊了什么会话、检索命中哪些块、调了什么工具 | `ai_usage_log` + `ai_tool_call_log` |

### 5.11 可观测性与成本

**落表**：

```sql
CREATE TABLE ai_usage_log (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id     BIGINT       NULL,
  user_id        BIGINT       NOT NULL,
  model          VARCHAR(64)  NOT NULL,
  prompt_tokens  INT          NOT NULL DEFAULT 0,
  cached_tokens  INT          NOT NULL DEFAULT 0,   -- 缓存命中，成本差异大，必须单独记
  completion_tokens INT       NOT NULL DEFAULT 0,
  first_token_ms INT          NULL,
  total_ms       INT          NOT NULL DEFAULT 0,
  retrieved_count TINYINT     NOT NULL DEFAULT 0,
  error_code     VARCHAR(32)  NULL,
  created_at     DATETIME     NOT NULL,
  KEY idx_user_time (user_id, created_at),
  KEY idx_error (error_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_tool_call_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id  BIGINT       NULL,
  user_id     BIGINT       NOT NULL,
  tool_name   VARCHAR(64)  NOT NULL,
  args_digest VARCHAR(512) NULL,      -- 脱敏 + 截断，不存原始入参
  ok          TINYINT      NOT NULL,
  latency_ms  INT          NOT NULL DEFAULT 0,
  created_at  DATETIME     NOT NULL,
  KEY idx_session (session_id),
  KEY idx_tool_time (tool_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**核心指标**（先落日志与报表，不急于上 Prometheus）：

| 指标 | 目标 | 意义 |
|---|---|---|
| 首 token 延迟 P95 | < 2.5s | 流式体验的命门 |
| 端到端 P95 | < 12s | 用户可忍受上限 |
| 降级率 | < 2% | 稳定性 |
| 检索命中率（非空占比） | 40–70% | **过低=阈值太高，过高=阈值太低在硬凑** |
| 缓存命中率 | > 30% | 直接对应成本 |
| 单问诊 token 成本 | 有基线即可 | 成本控制 |

**成本策略**：

- **保前缀稳定**（5.1）—— 这是唯一"免费"的降本手段，收益可能超过其他所有优化加起来。
- **索引重建走闲时** —— DeepSeek 闲时价格减半，批量 embedding 与语料生成排到闲时。
- **单用户日配额** —— Redis `ai:rl:{uid}`，滑动窗口；医疗助手不需要无限量。
- **上下文硬上限** —— `max-context-tokens` 是**硬闸**，超出直接裁，不靠"模型自己把握"。

---

## 六、开发阶段与里程碑

### 关键路径

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 2.5 ─┬──► Phase 4 ──► Phase 5
 安全加固     LLM企业化    对话智能     问诊提效   │    在线检索      工具/MCP
  ✅已完成     ✅已完成     ✅已完成     ✅已完成   │
                                                │
Phase 3（RAG 离线）──────────────────────────────┘
        （可与 1、2、2.5 并行，但 Milvus 环境探针必须先过）
                                          Phase 6 观测/评测 全程并行收口
```

**Phase 2.5 是追加插入的，不改变 3–6 的编号**：它是 Phase 2 交付后的试用反馈修正
（缩短问诊轮次 + 推荐闭环），与 RAG 无交集，故记作「2.5」而不占用 Phase 3。

**串行的是同层改动**（Phase 1、2、2.5 都改 LLM 链路与提示词组装，并行会互相覆盖；
Phase 2 起 `PromptBuilder` 已删除，职责拆到 `PromptAssets` + `ContextAssembler`）；
**可并行的是不同层**（Phase 3 只动检索与数据层，与 LLM 层无交集）。

---

### Phase 0 · 安全加固与地基

**为什么排第一**：这三件事与本次改造无关，但都是**已经在生产代码里的真问题**，且成本极低。
先修掉，后面的改建立在正确的地基上。

| 交付物 | 具体内容 | 验收 |
|---|---|---|
| `AiConsultService.requireMember()` | 会话归属校验，`chatStream` / `closeSession` / 历史接口全过 | 用他人 `sessionId` 调三个接口 → **403** |
| `doOnComplete` → `doFinally` | 客户端断开时已生成内容仍落库，标记 `interrupted` | 发送中关页面再打开 → 内容在 |
| `scaleName` 修真 | 从 `scale` 表按 `assessment.scaleId` 查名称传入 | prompt 里出现「PHQ-9」而非「心理测评」 |
| `GlobalExceptionHandler` 补异常类型 | `MethodArgumentTypeMismatchException` → 400 | `/alert/abc` 返回 400 而非 500 |
| Redis 连通性验证 | `ai:stage:*` 读写探针 | 写入读回一致 |

**预估**：约 60 行代码，无外部依赖。**性价比最高的一阶段。**

> [!success] 状态：✅ 已完成（2026-09-29）
> 实施记录见 `开发文档.md` 第十二章（含 8 条验收步骤）。
>
> | 文件 | 改动 |
> |---|---|
> | `AiConsultService.java` | `requireMember`（403 / 404）+ `doFinally` 落库（`AtomicBoolean` 防重、空内容不落库）+ `scaleNameOf` + `openSession` 校验 `assessmentId`/`alertId` 归属 |
> | `GlobalExceptionHandler.java` | 补 `MethodArgumentTypeMismatchException` → 400 |
> | `api/consult.ts` | 识别「非事件流」的失败响应，透出后端 `msg`（否则越权提示不可见） |
> | `sql/v4_ai_message_finish_reason.sql` | **新增**，幂等 DDL：`consult_message.finish_reason` |
> | `.workbuddy/scripts/dump_table.py` | **新增**，无 `mysqldump` 时的表级备份（删改数据前必备份） |
> | `.workbuddy/scripts/redis_probe.py` | **新增**，零依赖 Redis 探针（直连 RESP） |
>
> `finish_reason` 取值：`DONE` / `INTERRUPTED` / `CRISIS` / `ERROR`（非 AI 消息为 `NULL`）。
> 记在消息上而不只记日志：「中断率」是要长期看的指标，只写日志就没法统计 ——
> 与 `ai_usage_log` 不重复，那边记的是**调用**，这边记的是**这条消息本身完不完整**。
>
> ⚠️ DDL **已直接应用到真库**，代码与 DDL 必须同时部署（顺序：先 DDL，后重启后端）。



---

### Phase 1 · LLM 层企业化（DeepSeek 正式接入）

| 交付物 | 具体内容 |
|---|---|
| `OpenAiCompatClient` | 由 `DeepSeekClient` 泛化；`base-url` 与 `path` 全配置化，**不硬编码 `/v1`** |
| 模型名迁移 | `deepseek-chat` → `deepseek-v4-flash`（旧别名已退役） |
| `LlmProperties` | `@ConfigurationProperties`，含 thinking 档位 |
| `LlmErrorMapper` | 6 类错误码映射，400 需读 body 区分语义 |
| 首 token 超时 + 重试退避 + 降级话术 | 见 5.8 |
| SSE 事件协议 | 先上 `meta` / `token` / `error` / `done` 四类（`citations` 留到 Phase 4） |
| `api/consult.ts` 事件解析 | 加 `event:` 分支；错误分级 |
| `ai_usage_log` + 写库 | 每次调用记 token 与延迟 |
| 启动探活 | 应用启动时做一次轻量调用；Key 无效 / 模型名失效**在启动日志里就可见**，不等到用户来问 |

**验收**：5.8 的 7 条故障注入全部符合预期（见 8.2）。

> [!success] 状态：✅ 代码完成（2026-09-29）· 契约 `SSE事件协议.md` 已定稿 · **需重启后端**
> 实施记录见 `开发文档.md` 第十三章。三处与计划的偏离（均为实测后的修正）：
>
> | 偏离 | 原因 |
> |---|---|
> | 启动探活只调 `/models`，不发对话请求 | 「Key 是否有效 / 模型名是否存在」一个 GET 就能判定；用**会计费**的对话接口验最便宜的属性是反的。且探活失败**不阻止启动** |
> | 累计缓冲区接在「下发给前端的事件」上，而非上游 `LlmChunk` | 接上游时降级话术不入库 → 「库里存的」≠「用户看到的」，事后审计会读到从未发生过的对话 |
> | 新增 `override` 事件 + `LLM_NO_CONTENT` 错误码 | 前者解决输出侧危机替换造成的**合规不一致**；后者：上游正常结束但零正文（`max_tokens` 被思考吃光）从 `finish_reason` 上看不出异常 |
>
> **实测修正的三条配置默认值**（原计划未覆盖）：
> ① 模型正式名是 **`deepseek-flash`**（`deepseek-v4-flash` 是别名）；
> ② 推理模型的 **`max_tokens` 是「思考+正文」共享预算**，设 256 时正文为空 —— 默认给 8192；
> ③ `effort: low` 实测思考量 507→374 字、TTFT 1.78s→1.50s，陪伴式问诊取低延迟。
>
> 新增交付物（计划外）：`sql/v5_observability_ddl.sql` 已应用真库（先演练后上真库）；
> `ai/LlmProbeResult.java`、`ai/LlmChunk.java`、`ai/LlmUsage.java`、`service/AiUsageRecorder.java`、
> `.workbuddy/scripts/llm_probe.py`（模型/流式计时/故障注入三合一探针）。

> [!warning] 配置里的 `api-key: ${LLM_API_KEY:sk-xxxx}` 是当前"AI 对话未验证"的根因
> 默认占位符会让请求必然失败。启动探活就是为了让这类问题**在启动时暴露**，而不是在用户发消息时暴露。
> 环境变量由你配置，我不代持密钥。
>
> **2026-09-29 收口**：已按项目实际需要改为**密钥直写 `application.yml`**（本地开发「clone 下来就能跑」优先），
> 同时保留 `PSYCARE_LLM_API_KEY` 环境变量覆盖（优先级高于 yml，生产仍可做到密钥不落盘）；
> `LlmProperties.hasKey()` 增加占位符识别，`@ToString.Exclude` 防止密钥被 `toString()` 打进日志。

---

### Phase 2 · 提示词分层与对话智能

| 交付物 | 具体内容 |
|---|---|
| `PromptAssets` | L1–L4 四个常量块（人格/边界/输出约束/流程），前缀稳定 |
| `ContextAssembler` | 阶梯式组装 + token 预算裁剪 + 摘要注入 + **L5b 医生名单注入（Fix-1 新增，让 AI 能在正文里推荐医生）** |
| `InterviewStage` + `StageRules` + `StageStore` | 五阶段纯函数规则 + Redis 状态 + miss 重放重建 |
| 摘要压缩 | 超预算的早远对话 → `consult_session.summary`（零 DDL，字段已有） |
| 历史会话接口 | `GET /ai-consult/sessions`、`/sessions/{id}/messages`（含归属校验） |
| 前端历史入口 | `AiChat.vue` 会话抽屉 + 阶段指示条 |

**验收**：
1. 连续 5 轮，阶段按 OPENING → CHIEF_COMPLAINT → FUNCTION_IMPACT 推进（可断言）
2. 第 3 轮问「我刚才说了什么」→ 能复述
3. 刷新页面 → 恢复历史会话与原上下文
4. 用户在第 4 轮换话题 → 阶段能回退，不卡死

> [!success] 状态：✅ 代码完成（2026-09-29）· 契约 `SSE事件协议.md` 已同步 · **需重启后端**
> 实施记录见 `开发文档.md` 第十四章。五处与计划的偏离（均为实现时的判断）：
>
> | 偏离 | 原因 |
> |---|---|
> | `StageMachine` 拆成 `StageRules`（纯函数）+ `StageStore`（Redis） | 计划里的「规则/LLM 双确认」取消：LLM 判定阶段需额外一次调用（**每次对话成本翻倍**），而规则侧已能在 5 轮内走完五阶段。拆分后「在线推进」与「Redis 丢失后的重放重建」**共用同一个纯函数** —— 否则两条路径必然漂移，表现为「偶尔跳级」，且不报错 |
> | 阶段计的是 `turnsInStage` 而非总轮数 | 用总轮数做阈值会让阶段一路冲到结尾，五阶段退化成一次性问答。两个计数器必须分开 |
> | 删除 `PromptBuilder` | 职责被 `PromptAssets`（常量）+ `ContextAssembler`（组装）完整接管，留着就是两套提示词逻辑 |
> | `crisisStream` 也推进阶段 | 危机短路不调模型，但用户确实说了一句话；若不推进，危机后阶段会停在原地 |
> | 单条消息超长在**服务端**截断（`single-message-max-chars`） | 前端 `maxlength` 只是「不给输入框」，粘贴与接口直调都绕得过 |
>
> **新增的 4 个配置项**（`psycare.llm.*`）：`max-context-tokens: 6000`、
> `single-message-max-chars: 1000`、`summary-trigger-messages: 24`、`summary-step-messages: 8`。
>
> **自检发现并修掉的真实缺陷**：`FIELD_WORDS` 的「时长」组只收了「几个月」，
> 漏掉最常见的「个月」→「大概两个月了」抽不到 `duration`，主诉阶段无法判定信息是否够。
> 这类漏词不会报错，只会让阶段推进比预期慢；词表需要靠**真实语料**回归，不能靠想。

> [!warning] Fix-1（2026-09-29）· 真机跑出来的三个缺陷
> Phase 2 交付后用户实测，暴露三条**代码评审看不出来**的问题，已全部修掉
> （代码级记录：`开发文档.md` 第十五章）：
>
> | # | 缺陷 | 教训 |
> |---|---|---|
> | 1 | 输出侧危机判据与输入侧共用「出现危机词」，而 L2 **要求**模型提热线 → 每段合规回复都被替换成危机文案 | **用一个业务上鼓励出现的内容当异常标志，必然把正常判成异常。** 输入侧宁宽、输出侧宁窄，两侧目标相反，判据必须分开 |
> | 2 | 前端 `CRISIS_MARK = '12356'` 子串启发式，同一类错误 | 危机与否只有服务端知道，就该由服务端显式下发（新增 `crisis` 事件），前端不该反推 |
> | 3 | `StageStore` 的 `new ObjectMapper()` 未注册 `JavaTimeModule` → `LocalDateTime` 序列化抛异常被 warn 吞掉 → **阶段状态从未写进 Redis**，每轮全量重放 | **「功能看起来是对的不是验收标准」**：重放本来等价，所以这个 bug 只体现在日志的 `回看=N条`（从 0 逐轮 +1）与一次多余的历史回溯上。吞异常 + 有等价兜底路径 = 静默失效的完美配方 |
>
> 顺带修掉：本轮用户消息在 prompt 里**出现两次**（先落库再组装，`loadHistory` 没排除刚存的那条）；
> 推荐理由把「风险等级A→科室[全科]」这类**内部标签**直接显示给居民。
> 新增能力：把 `RecommendService` 的结果注入 **L5b** 上下文块，让 AI 在对话正文里
> 直接点名医生并说明理由（此前只存在于右侧栏）。

---

### Phase 2.5 · 问诊提效与推荐闭环

> **为什么插在 2 与 3 之间**：RAG（Phase 3）是**加信息**，这一段是**减摩擦**，两者互不依赖。
> 真实试用反馈是「AI 问得太久、推荐来得太晚、推荐完还得自己去找人」——
> 这个问题在第 3 轮就劝退用户，比「回答不够权威」更致命，所以先修。

| 交付物 | 具体内容 |
|---|---|
| 阶段压缩 | `InterviewStage` 由 5 段压到 **3 段**（`OPENING` / `CHIEF_COMPLAINT` / `SUMMARY_REFERRAL`）。删 `FUNCTION_IMPACT`（并入主诉追问要点）、删 `RISK_SCREENING`（并入安全边界，**出现即处置，不专门追问**） |
| 提问上限 | `StageRules.MAX_QUESTIONS = 3`；`CORE_FIELDS` + `FUNCTION_FIELDS` 合并为 7 项 `KEY_FIELDS`，信息够即提前转介 |
| `ReferralRules` | **一问一答**式应允判定（`askedForReferral` + `accepted`），否定词优先 |
| SSE `recommend` 事件 | 在全部 `token` 之后、`done` 之前下发；与 `crisis(OUTPUT)` / `override` **互斥** |
| 分析动画 + 跳转 | 前端 3 步点亮动画 → 跳 `/ai-recommend?sessionId=N`（**Fix-2 起不再关闭会话**，见下方） |
| 新推荐页 `AiRecommendResult.vue` | 承接推荐卡片 + 「立即咨询」→ 原地开聊天抽屉 |
| 「医生咨询」页 `DoctorConsult.vue` | **科室分类 → 医生列表** 两级；`GET /chat/doctor-catalog`（不过滤「聊过的人」） |
| 历史会话抽屉重设计 | 只留 **最近 5 条**（`SESSION_LIST_LIMIT` 30 → 5），视觉与全站统一（**该抽屉已在同日 Fix-2 中整体移除**，见下方） |

**验收**：见 `开发文档.md` 第十六章 16.6（15 条）+ `SSE事件协议.md` § 6.2 第 12–18 条。

> [!success] 状态：✅ 代码完成（2026-09-29）· 契约 `SSE事件协议.md` 已同步 · **需重启后端**
> 实施记录见 `开发文档.md` 第十六章。三处关键判断：
>
> | # | 判断 | 理由 |
> |---|---|---|
> | 1 | `OPENING` 推进阈值**必须 >= 2** | 推进发生在「用户发言之后、组装提示词之前」，所以「用户第 N 条之后的阶段」＝「模型第 N 次回复所处的阶段」。写 `>= 1` 会让模型**第一次回复就落在主诉澄清**，`OPENING` 永远轮不到一次回复 —— 三个阶段里有一段空转。**自检才抓得到，不报错** |
> | 2 | 应允判定必须「一问一答」 | 前端只看得到后半（任何一轮的「好的」都长得像应允）。第 1 半「AI 上一轮问过没有」只有服务端能看到 |
> | 3 | 动作只在回调里记标记 | 回调里 `await` 会阻塞后续 SSE 帧解析，遮罩还会盖住**还在流的字**；动作挪到 `chat.send()` 返回之后才卡得准 |
>
> 需求侧的两个澄清（用户拍板）：阶段骨架 = **三段**；动画时机 = **AI 主动询问 → 用户应允 → 播动画**（原始表述里还有「自动结束会话」，那一步已被 Fix-2 取消）。

### Fix-2 · 取消「历史会话」，改成一段连续对话（2026-09-29）

> **动因**：Phase 2.5 上线后用户连续两条反馈 —— 先是「跳走再回来会看到上一次的记录」，
> 接着是「每次刷新都新建会话」，最后直接要求「把历史会话删了，记录一直显示在聊天框」。
> 前两版都在优化「多会话怎么切」，而真实诉求是**根本没有多会话**。

| 交付物 | 具体内容 |
|---|---|
| 服务端幂等 | `AiConsultService.openSession` 改为「查最近一条 `status='OPEN'` 的会话，有就复用」并更新 `assessmentId` / `alertId`（只 `set` 非空字段）。**一个居民 = 一条进行中会话** |
| 前端统一入口 | `stores/chat.ts` 新增 `enter(assessmentId)`：带测评 → `open()`（后端复用并关联）；不带 → `restoreLast()` 恢复最近一段对话，确实没有才新建 |
| 去掉 localStorage | `restoreLast()` 的数据源换成服务端列表（按 `id` 倒序取第一条）。少一个本地状态源，换浏览器 / 清缓存后记录也还在 |
| 不再关闭会话 | `playAnalyzeAndJump()` 删掉 `chat.close()`：历史功能取消后，「别留一条没关掉的记录」这个理由也不存在了 |
| UI 删除 | 头部「历史会话」按钮 + 整个抽屉（模板 / 样式 / `openDrawer` / `onSwitchSession` / `previewOf` / `formatDateTime` / `SESSION_SHOWN`）。产物 `AiChat` **18.68 → 10.05 kB** |
| 登出清 store | `stores/auth.ts` 的 `logout()` 补 `useChatStore().reset()` —— 页面不再于离开时清 store，这条是防换账号串号的唯一防线 |

**验收**：`开发文档.md` 第十六章 16.6 第 12–15 条 + `SSE事件协议.md` § 6.2 第 19–21 条。

> [!success] 状态：✅ 代码完成（2026-09-29）· **需重启后端**（`openSession` 行为变了）
> 三条不能破的约束与五条进入路径的走查见 `开发文档.md` 16.7。
>
> **接口一个都没删**：`GET /ai-consult/sessions`、`/sessions/{id}/messages` 都保留，
> 只是调用方从「抽屉」变成「恢复最近一段对话」；`SESSION_LIST_LIMIT = 5` 也保留。

> [!warning] Phase 2 的验收第 1、4 条已被本阶段取代
> 「阶段按 OPENING → CHIEF_COMPLAINT → FUNCTION_IMPACT 推进」「第 4 轮换话题」里的
> **阶段名与轮数都按 5 段写的**，现行是 3 段（2~3 问即转介），不要按旧值验收。

---

### Phase 3 · RAG 离线管道与语料

| 交付物 | 具体内容 |
|---|---|
| **`milvus_probe` 探针** | 四断言（连接/建集合/写入读回/**中文 BM25 召回**）—— **不过则停止后续开发** |
| 语料生成 | `topics.yaml` + `gen_synthetic_corpus.py`（调 DeepSeek 生成结构化 JSONL） |
| `v4_rag_ddl.sql` | `kb_document` + `kb_chunk`（含 `is_deleted`、`embed_model`、`dim`） |
| `DocumentParser` / `TextSplitter` | 解析 + 父子分块 + 元数据 |
| `EmbeddingClient` + 兼容实现 | 通义 OpenAI 兼容协议，批量 10 条/次 |
| `MilvusVectorStore` | schema / 索引 / hybridSearch / upsert / deleteByDoc |
| `KnowledgeIndexService` | 双写编排（**先 MySQL 后 Milvus**）+ 状态机（INDEXING/READY/FAILED） |
| 管理端 `KnowledgeBase.vue` | 文档列表 / 上传 / 切分预览 / 重建索引 |
| 校验脚本 | MySQL ↔ Milvus 条数与 checksum 比对 |

**验收**：
1. 探针四断言全绿（尤其**中文 BM25 能召回**）
2. 100 个块入库，写已知句子再检索该句 → **自身块排第 1，相似度 > 0.9**
3. 故意 drop 掉 Milvus collection → 调 rebuild → 索引完整恢复（验证"Milvus 可重建"）
4. 抽检 10 个切分块，无跨主题混切、无表格碎片化

---

#### Phase 3 进度 · **代码全部完成（待重启后端验收）**

两条外部依赖分两批就位：**向量化**（模型名 + Key）先到，**Milvus**（用户改用 v3.0.0 并启动）后到。
`milvus_probe.py` 在 v3.0.0 上**四断言全绿**（含**中文 BM25 召回 4/4**）后，才动 Milvus 侧代码 ——
所以下面 5.5.3 的「不过则停止开发」是**真的执行了**，不是流程装饰。

第一步（不依赖 Milvus 的地基）：

| 已完成 | 说明 |
|---|---|
| `sql/v8_rag_ddl.sql` | `kb_document` + `kb_chunk`（**已上真库**）。⚠️ 计划 8.2 原写 `v4_rag_ddl.sql`，但 `v4` 已被 `v4_ai_message_finish_reason.sql` 占用 → 顺延为 v8 |
| `EmbeddingClient` + `OpenAiCompatEmbeddingClient` | 通义 OpenAI 兼容 `/embeddings`；切片 / 重试 / 逐条降级。**已真实联调通过** |
| `EmbeddingProperties` + `application.yml` | `psycare.embedding.*`：`model=qwen3.7-text-embedding-flash`、`dim=1024`、**`batch-size=25`**（实测上限，**不是下面 5.4 阶段四写的 10** —— 那是 v4 的值） |
| `EmbeddingStartupProbe` | 启动探活，失败只 WARN（与 LLM 侧分层） |
| `EmbeddingClientHarness`（`src/test`） | 真调通的六断言验收程序 |

第二步（Milvus 侧 + 编排 + 界面，2026-10-08 当晚）：

| 已完成 | 说明 |
|---|---|
| `MilvusProperties` + `application.yml` | `psycare.milvus.*`。**刻意没有 dim** —— 维度唯一来源是 `embedding.dim`，再写一份就等于允许「上游 1024 / 集合 768」这种只能靠报错发现的配置。独立 `database: psycare`（见硬约定 #72：本机实例**非项目独占**） |
| `MilvusVectorStore` | 懒连接 + **幂等建 database/collection**；schema 含 **jieba 分词 + BM25 Function**（不写 `sparse_vector`，由服务端生成）；HNSW/COSINE + SPARSE_INVERTED/BM25 + 标量 INVERTED；`hybridSearch`（**RRF**）；`upsert`（幂等，重建可安全重入）；`deleteByDoc`；**集合已存在时校验维度，不一致直接拒绝服务**（硬约定 #79） |
| `MilvusStartupProbe` | 启动探活，**失败只 WARN**（与 LLM 侧 ERROR 分层：索引挂了不该让在线对话也挂） |
| `TextSplitter` | 父子分块（默认 **子 400 / 父 1400 / overlap 50**），章节识别 → 段落聚合 → 中句切分 → 硬切兜底。**纯函数**，无 Spring 依赖 —— 调参不需要清库、不需要起服务 |
| `DocumentParser` | txt / md / jsonl（按 `doc_title` 分组成多篇）；**PDF/DOCX 明确拒绝**并提示先离线转 txt（硬约定 #81） |
| `KnowledgeIndexService` | 双写编排：**先 MySQL 后 Milvus**；`TransactionTemplate` 显式划事务边界（外部调用不进事务，硬约定 #76）；状态机 `PENDING/INDEXING/READY/FAILED`；**重建的原文由 PARENT 块按 `ordinal` 拼回**（硬约定 #77） |
| `KnowledgeService` + `KnowledgeController` | `/knowledge/**`（概览 / 列表 / 详情 / 分块 / 预览 / 建档 / 批量导入 / 重建 / 删除 / Milvus 探测），**每个方法独立断言 ADMIN** |
| `KnowledgeBase.vue` + `api/knowledge.ts` + 路由 + 侧栏 | 概览 / 列表 / 建档 / 批量导入 / **切分预览（零成本）** / 分块查看 / 重建 / 删除。**零新增 antd 组件**（硬约定 #82），图标仅加 `DatabaseOutlined`（vendor-antd 790.77 → **791.81 kB**，仍 < 800 kB） |
| `corpus/topics.yaml` + `gen_synthetic_corpus.py` | 6 个主题域 / **81 个主题**；生成脚本**断点续跑**（每写一条即 flush，重跑按 `(doc_title, section)` 跳过）—— 81 次调用里失败一次就要全量重跑是不可接受的 |
| `verify_index.py` | MySQL ↔ Milvus 一致性：**条数 / 逐篇 chunk_id 集合 / 正文抽样**三层。⚠️ **从不比较向量**（#68） |

> ✅ **语料已生成完毕**：`psycare-server/corpus/synthetic_corpus.jsonl` = **81 行 / 6 篇文档**
> （心理量表条目说明 16 节、量表计分与分级 12 节、常见症状科普 16 节、危机干预 13 节、
> 抗抑郁药物常识 12 节、就诊与转介 12 节），全部 `source_type=SYNTHETIC`，逐行 JSON 校验 0 非法。
> 第一批 78 条里 5 条超时失败 → 靠**断点续跑**补跑一轮（21 秒）补齐，没有重跑任何已完成的条目。
>
> ⚠️ **2026-10-08 11:12 订正：首批语料里有 11 条正文是断在半句处的**（最短 **23 字**）。
> 根因是 `call_llm` 的 `max_tokens=2048` —— 推理模型的思考与正文**共享预算**（#22）——
> 而代码**只判「正文是否为空」，从没检查 `finish_reason`**，于是「半句话」被当成成功写进了文件：
> **HTTP 200、JSON 合法、字段齐全，从生成到切分到向量化全程不报错**。
> 已修（`max_tokens` → 4096；`finish_reason=length` 即失败重试；长度低于配置下限 ×0.7 即失败重试）
> 并按「正文 < 380 字」剔除 11 条后续跑重生成（46 秒 / 0 失败）。
> 复验：**394–633 字（平均 500），无一条低于 380，全部以句末标点收尾，药物篇 12/12 含「遵医嘱」**。
> 旧文件留档 `synthetic_corpus.jsonl.bak-20261008`。**若已导入过旧语料，需重新导入一次。**
> → 已提为硬约定 **#83**。
>
> ⚠️ 合成语料**只用于验证链路**，`topics.yaml` 里也写明了「不得进生产」。

**待用户验收**（重启后端后）：

1. 启动日志出现 `[Milvus 启动探活] ✅ 通过：uri=http://localhost:19530 state=psycare / psycare_kb_chunk / 1024 维 / 服务端 v3.0.0`；
   **不启动 Milvus 时应只有 WARN，且 AI 对话、测评、医患聊天全部照常**；
2. 管理端侧栏出现「知识库」（仅管理员可见），概览显示两侧数字；
3. 导入 `synthetic_corpus.jsonl` → 文档 READY、子块数正确、概览里 Milvus 行数追上 MySQL；
4. `python verify_index.py` 报「✓ 一致」；
5. **故意 drop collection → 点「重建」→ `verify_index.py` 再次一致**（= 验收第 3 条「Milvus 可重建」）；
6. 抽检 10 个切分块，无跨主题混切（= 验收第 4 条）。

> [!warning] 上线前必须修的两处外部接口事实（实测，已写进代码注释）
> ① 百炼响应里的 **`index` 字段恒为 0**（25 条批量每个元素都返回 0），**不能据它回填**；
> ② 同一文本**放进批里 vs 单独调用会得到 cos≈0.998 的两个版本**（服务端非逐位可复现）
> → 后续 `verify_index.py` 只能用相似度阈值（≥0.99），不能拿「向量相等 / checksum 相等」当判据。

---

### Phase 4 · 在线检索与引用

| 交付物 | 具体内容 |
|---|---|
| `RetrievalPipeline` | 改写（可关）→ 混合检索 → RRF → 阈值 → 回表 |
| 检索缓存 | Redis `ai:cache:emb:{hash}`，TTL 24h |
| 注入 `ContextAssembler` | L6 带来源的资料块 + 拒答指令 |
| `citations` 事件 + 前端引用卡片 | 复用 `AiChat.vue` 已有的 `.bubble-basis` UI |
| **评测集** | `queries_in.jsonl`（20–30 库内）+ `queries_out.jsonl`（10 库外） |
| `eval_retrieval.py` | 指标计算 + 参数网格 |
| 阈值校准报告 | 分离点分析 |

**验收**（本阶段是 RAG 唯一有意义的量化验收）：

| 指标 | 目标 |
|---|---|
| **Recall@5** | ≥ 0.85 |
| **MRR** | ≥ 0.7 |
| **拒答准确率**（库外问题被挡） | ≥ 0.9 |

参数网格：块大小 {300, 400, 500} × overlap {0, 50} × 阈值 {0.4, 0.5, 0.6}，
**每次只动一个参数**，否则说不清是哪个起了作用。

---

### Phase 5 · 工具调用与 MCP

| 交付物 | 具体内容 |
|---|---|
| `AiTool` + `ToolRegistry` | 注册表 + 白名单 + `ToolContext`（userId 服务端注入） |
| 4 个内置工具 | `queryMyAssessments` / `recommendDoctors` / `searchMedicalKnowledge` / `escalateToDoctor` |
| 两阶段调用循环 | 非流式判定 → 执行 → 流式生成 |
| `ai_tool_call_log` | 审计 + 入参脱敏 |
| `McpToolAdapter` | MCP tools/list → `AiTool`；tools/call → `execute` |
| `AiChatClient` 的 `tools` 重载 | 沿用 Phase 1 预留的签名 |


**验收**：
1. 「我上次测评多少分」→ 触发 `queryMyAssessments` 并回答出真实分数
2. 用户输入「帮我查 user02 的测评」→ **工具只返回当前用户数据**（越权参数被拦）
3. `ai_tool_call_log` 有完整记录，入参已脱敏
4. 接入一个本地 MCP Server → 其工具可被调用，**业务代码零改动**

---

### Phase 6 · 观测、评测与成本（可与 4/5 并行）

| 交付物 | 具体内容 |
|---|---|
| 指标采集 | 首 token P95 / 端到端 P95 / 降级率 / 检索命中率 / 缓存命中率 |
| 用量报表 | 按用户/按天聚合 `ai_usage_log` |
| 回归套件 | `eval_retrieval.py` 并入 `e2e_api_check.py` 风格，改切分/换模型必须重跑 |
| 限流 | Redis 滑动窗口，单用户日配额 + 每分钟速率 |
| 成本基线 | 单问诊 token 成本 + 月成本估算 |

**验收**：改一次块大小 → 回归脚本给出指标变化 → 能据此判断改好还是改坏。

---

## 七、依赖与风险

### 7.1 外部依赖清单

| 依赖 | 谁提供 | 阻塞哪些阶段 | 缺失时的兜底 |
|---|---|---|---|
| **DeepSeek API Key** | 你（现直写 `application.yml`） | Phase 1 起全部 | 无兜底，必须提供。也可用 `PSYCARE_LLM_API_KEY` 环境变量覆盖（生产不落盘） |
| **通义 embedding 模型名 + Key** | 你（你说自己找） | Phase 3 起 | 可切 bge-m3（同协议，改配置） |
| **Milvus 服务（本机 Docker）** | 你启动（我给命令） | Phase 3 起 | `JdbcVectorStore` 暴力检索兜底 |
| **MySQL + Redis** | 你启动（硬约定 #1） | 全程 | 无 |
| 合成语料 | AI 生成 + 人工抽检 | Phase 3 | 先用手写 FAQ 20 条跑通 |
| 真实权威语料 | 你（上线前） | 对外发布 | 无 —— **合成语料不得对外** |

### 7.2 风险登记册

| # | 风险 | 概率 | 影响 | 缓解措施 | 触发信号 |
|---|---|---|---|---|---|
| R1 | DeepSeek 模型名再次变更／退役 | 中 | **高** | 模型名配置化 + 启动探活 + 契约测试断言模型可用；`LLM_MODEL_NOT_FOUND` 单独错误码 | 启动探活失败 |
| R2 | DeepSeek 无 embedding 导致供应商分裂 | **确定** | 中 | `EmbeddingClient` 抽象 + 双实现；embedding 用量独立记账 | — |
| R3 | **中文 BM25 分词器不支持** | 中 | 中 | 探针第 4 断言前置暴露；兜底：Java 侧分词后写 `text_tokenized` 字段 | 探针中文检索召回 0 |
| R4 | 本机 Docker 拉镜像失败（代理坑） | **高** | 中 | 检查 `settings-store.json` 的 `ProxyHTTPMode`；用国内镜像源；先验证 `quay.m.daocloud.io` 可达 | `docker pull` 报 connection refused |
| R5 | 合成语料被误当真实医疗依据 | 中 | **高** | 全链路 `SYNTHETIC` 标记 + 前端角标 + prompt 强制声明 + 上线前 rebuild 替换 | 引用卡片无「合成示例」角标 |
| R6 | 心理健康危机场景合规 | 低 | **极高** | 危机词本地短路 + 降级话术带 12356 + 不做诊断/用药 + 免责声明 + 回复侧二次检测。⚠️ **Fix-1 修正了「回复侧二次检测」的口径**：原先与输入侧共用「出现危机词」判据，把模型合规的**热线建议**也拦了（正常对话被判成危机、AI 突然说「已为你转接人工」）。现输出侧改判「危机词 + 方法/剂量类操作性词同窗共现」，只拦可操作的指引与教唆；两条路径的文案也分叉（`INPUT` 才承诺转接人工，因为只有它真建工单）。详见 `开发文档.md` 第十五章 | 危机输入出现模型生成内容；或**反向**：正常对话出现红字与转人工提示 |
| R7 | 前后端 SSE 协议不一致 | 中 | 中 | 先定协议文档，两端同批改；分两批上线（先 error/done，后 citations） | 气泡里出现 `{"code":` |
| R8 | Milvus 与 MySQL 双写不一致 | 中 | 中 | **以 MySQL 为准**；Milvus 写失败仅告警；提供 rebuild + checksum 校验脚本 | 校验脚本条数不等 |
| R9 | 前端打包超预算（现剩 9 kB） | 中 | 低 | 知识库页复用现有 `a-table`，上传用原生 input，引用卡片纯 CSS | `vite build` 的 `vendor-antd` > 800 kB |
| R10 | 成本失控 | 低 | 中 | 前缀稳定保缓存 + 上下文硬上限 + 单用户日配额 + usage 报表 | 缓存命中率 < 15% |
| R11 | 检索质量不达标（Recall@5 < 0.85） | 中 | 中 | 阈值/块大小网格调优；上 Rerank；改父子块比例 | Phase 4 指标报告 |
| R12 | Redis 不可用（会话状态丢失） | 低 | 低 | ✅ **Phase 2 已实现**：`StageStore.replay()` 按历史消息用**同一个** `StageRules.advance` 重放重建（幂等）；Redis 只存可重建状态，故障时只告警不影响对话 | 日志出现「阶段状态重建」 |
| R13 | 流式改动回归影响医患聊天 | 低 | 中 | AI 链路与 `ChatService` 完全隔离，共用 `ConsultMessage` 但入口不同；改后回归医患聊天 | 医患消息发送异常 |

### 7.3 本机环境的三个已知坑（直接相关）

| 现象 | 解法 | 来源 |
|---|---|---|
| Docker `pull` 报 `dial tcp 127.0.0.1:7999: connection refused` | Docker Desktop 曾配 manual 代理指向 `127.0.0.1:7999`，本机无该端口监听。查 `%APPDATA%\Docker\settings-store.json` 的 `ProxyHTTPMode` / `OverrideProxyHTTP` | 用户记忆 |
| `docker info` 只显示 `http.docker.internal:3128`，看不到真实代理 | 该字段不可靠，以上面的 json 为准 | 用户记忆 |
| 镜像源里混入 U+2011 伪装的域名 | 从网上复制配置后必须查非 ASCII 字符 | 用户记忆 |

**Milvus 启动命令**（**由你自己执行**，我只做只读探测）：

> [!success] 2026-10-08 已就绪，本节留作换机/重建参照
> 本机实际用官方 Windows 脚本：`E:\Milvus\standalone.bat`（`start` / `stop` / `delete` / `upgrade`）。
> 它起**单容器**：`ETCD_USE_EMBED=true`（etcd 内嵌，无独立容器）+ `COMMON_STORAGETYPE=local`
> （本地文件系统，**无 MinIO**），数据在 `E:\Milvus\volumes\milvus`，
> 映射 `19530 / 9091 / 2379`，镜像 `milvusdb/milvus:v3.0.0`。
>
> 等价的 compose 版本见 `deploy/milvus/docker-compose.yml`（已按 `docker inspect` 的真实参数改写）。
> **本机不要 up 那个 compose** —— 19530 已被现有实例占用。

```bash
# 换机重建时的官方脚本方式（与本机现状一致）
curl -sfL https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh -o standalone_embed.sh
#   脚本内 IMAGE 默认 milvusdb/milvus:vX.Y.Z —— 卡到 v3.0.0，别用 latest
bash standalone_embed.sh start
#   ⚠️ 只改镜像 tag 做降级（3.0 → 2.6）是不安全的：3.0 写过数据后可能读不出
```

```bash
# 三容器 compose 方式（**不是本机现状**，仅在需要独立 etcd/MinIO 时用）
docker compose -f deploy/milvus/docker-compose.yml up -d
```

验证（单容器模式只有一个容器）：

```bash
docker ps --format '{{.Image}} | {{.Names}} | {{.Status}}'   # 应见 milvus-standalone ... (healthy)
curl -s http://127.0.0.1:9091/healthz                        # 应返回 OK
```

启动后我会做只读探测确认 19530 与 9091 可达，再跑环境探针。

> 资源要求：CPU 4 核+、内存 8GB+、磁盘 50GB+。若本机吃紧，可考虑远程实例或 Zilliz Cloud 托管。
> Milvus Lite（嵌入式）**不支持 Windows**，别在这条路上浪费时间。

---

## 八、关键交付物清单

### 8.1 代码与脚本

| 类别 | 交付物 | 阶段 |
|---|---|---|
| 后端 · 安全 | `requireMember` + `doFinally` + `scaleName` + 异常处理器 | 0 |
| 后端 · LLM | `OpenAiCompatClient` / `LlmProperties` / `LlmErrorMapper` / `StreamWriter` | 1 |
| 后端 · 提示词 | `PromptAssets` / `ContextAssembler` / `InterviewStage` / `StageMachine` | 2 |
| 后端 · RAG | `DocumentParser` / `TextSplitter` / `EmbeddingClient` / `VectorStore` / `MilvusVectorStore` / `KnowledgeIndexService` | 3–4 |
| 后端 · 工具 | `AiTool` / `ToolRegistry` / 4 个内置工具 / `McpToolAdapter` | 5 |
| 后端 · 观测 | `ai_usage_log` 写入 / 限流 / 报表查询接口 | 1、6 |
| 前端 | `api/consult.ts` 事件解析 / `stores/chat.ts` / `AiChat.vue` / `KnowledgeBase.vue` / `types/index.ts` | 1–4 |
| 脚本 | `milvus_probe.py` / `gen_synthetic_corpus.py` / `build_index.py` / `eval_retrieval.py` / `verify_index.py` | 3–4 |

### 8.2 SQL 脚本

| 文件 | 内容 |
|---|---|
| `sql/v4_rag_ddl.sql` | `kb_document` / `kb_chunk`（含 `is_deleted`、`embed_model`、`dim`、`parent_id`） |
| `sql/v5_observability_ddl.sql` | `ai_usage_log` / `ai_tool_call_log` |
| `sql/v6_alter_consult_session.sql` | `stage` / `last_active_at` 加列（`summary` 已有，直接启用） |

> 沿用项目既有约定：写 DDL 后**先在克隆库演练**再上真库（见 `mysql-migration-rehearsal` skill 与
> `.workbuddy/scripts/rehearse_role_split.py` 的既有做法）。本项目**无外键约束**，删改列前需全库 `grep` 列名。

### 8.3 文档

| 文档 | 内容 |
|---|---|
| `AI助手开发计划.md` | 本文档（落地排期与工程设计） |
| `AI问诊改造设计.md` | 保留作现状评估基线；其「通义接入」「MySQL 存向量」两节由本文档取代 |
| `SSE事件协议.md` | 前后端契约。Phase 1 定稿六类事件；Phase 2 补阶段字段与两个会话读取接口；Phase 2.5 新增 `recommend` 事件并把 `stageTotal` 由 5 改为 3（破坏性） |
| `RAG语料规范.md` | 语料格式、元数据字段、合成标记要求、抽检清单 |
| `开发文档.md` | 补 AI 模块章节 + 接口表 + Phase 记录（沿用既有体例）。第十二 / 十三 / 十四 / 十六 / 十五章 = Phase 0 / 1 / 2 / **2.5** / Fix-1 |
| `部署说明.md` | 补 Milvus 部署、三容器运维、镜像源排障、索引重建流程 |

### 8.4 评测资产

| 资产 | 内容 | 用途 |
|---|---|---|
| `eval/queries_in.jsonl` | 20–30 条库内问题 + 正确块标注 | Recall@5 / MRR |
| `eval/queries_out.jsonl` | 10 条库外问题 | 拒答准确率 |
| `eval/report.md` | 指标报告 + 参数网格结果 | 阈值校准依据 |
| `corpus/*.jsonl` | 合成语料（标记 SYNTHETIC） | 链路验证与评测 |

---

## 九、验证方式汇总

### 9.1 分层验证矩阵

| 层 | 验证项 | 方法 | 通过标准 |
|---|---|---|---|
| 单元 | `TextSplitter` | 空文档/无标点长文/超长表格/中英混排 | 不抛异常；块长在 [300,500]±20%；无空块 |
| 单元 | 相似度 | 同向量自比、正交向量 | `cos(v,v)≈1.0`；正交 ≈ 0 |
| 单元 | `requireMember` | 他人 `sessionId` | 403 |
| 单元 | `LlmErrorMapper` | 401/429/400(模型名)/400(超长)/5xx | 5 类映射正确，**400 两种语义要分开** |
| 单元 | `StageRules` | 各阶段边界输入 | 推进/不推进符合规则表 |
| 集成 | Embedding | 真实调用 | 返回 1024 维；11 条批量应报错（验证上限 10） |
| 集成 | Milvus 探针 | 四断言 | 全绿，**含中文 BM25 召回** |
| 集成 | 入库→检索往返 | 写已知文本再检索 | 自身块排第 1，相似度 > 0.9 |
| 集成 | 索引重建 | drop collection → rebuild | 条数与校验和一致 |
| 端到端 | 多轮上下文 | 第 3 轮问「我刚才说了什么」 | 能复述前文 |
| 端到端 | 流式协议 | `curl -N -X POST .../ai-consult/chat` | 见 9.2 |
| 端到端 | 工具调用 | 「我上次测评多少分」 | 返回真实分数 |

### 9.2 流式协议验证（curl 可直接看）

```bash
curl -N --noproxy '*' -X POST http://127.0.0.1:18989/api/ai-consult/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"sessionId":1,"content":"最近总是睡不着"}'
```

期望输出形态（事件类型清晰可辨）：

```
event:meta
data:{"sessionId":1,"messageId":88,"stage":"CHIEF_COMPLAINT"}

event:citations
data:[{"docId":3,"title":"...","score":0.82,"synthetic":true}]

event:token
data:听起来这段时间

event:token
data:你过得挺辛苦的

event:usage
data:{"promptTokens":1204,"completionTokens":86,"cachedTokens":1024}

event:done
data:[DONE]
```

### 9.3 故障注入（逐条制造故障）

| 制造方式 | 期望行为 | 不该出现 |
|---|---|---|
| `psycare.llm.api-key` 改成 `sk-wrong` | 降级话术（带 12356） | 500 / 白屏 |
| `psycare.llm.api-key` 写成 `sk-xxxx`（占位符没换） | 启动日志报「**仍是占位符**」 | 与「鉴权失败」混为一谈（两者下一步动作不同） |
| 模型名改成 `deepseek-chat`（已退役） | `LLM_MODEL_NOT_FOUND` + 明确提示模型名失效 | 被当成"上下文超长"去压缩历史 |
| `timeout-seconds` 改 1 | 触发超时 → 降级 | 请求悬挂不返回 |
| `first-token-seconds` 改 1 | 15s 内无首 token 即降级 | 干等 60 秒 |
| 停掉 Milvus | **无 RAG 继续对话**（不报错） | 整个聊天不可用 |
| 断网 | `event:error` → 前端提示可重试 | 气泡永远停在光标处 |
| 发送中关页面再打开 | 已生成部分**已落库** | 记录消失 |
| 连发 3 条消息 | 前端 `sending` 互斥，只发 1 条 | 并发乱序 |

### 9.4 安全验证

| 验证项 | 期望 |
|---|---|
| 居民 A 用 B 的 `sessionId` 调 `/chat`、`/close`、历史接口 | **403** |
| 输入「我想自杀」 | **不调 LLM**，固定文案 + CRISIS 工单 + 热线卡点亮 |
| 「帮我查 user02 的测评」走工具 | 只返回当前用户数据 |
| 医生 token 调知识库管理接口 | **403**（后端独立断言，前端隐藏按钮不算） |
| 审计完整性 | 每次检索命中块、每次工具调用均可追溯 |

### 9.5 手工验收清单

1. `user01/user123` 登录 → AI 问诊 → 连续 5 轮，观察**按五阶段推进**（先共情 → 再问症状 → 再问功能影响）
2. 第 3 轮问「我刚才说了什么」→ 能复述
3. 问库内问题（如「PHQ-9 第 9 题是什么」）→ 回答**带来源卡片**
4. 问库外问题（如「推荐一家火锅店」）→ 回答「知识库中暂未收录」而非编造
5. 输入「我不想活了」→ 立刻固定危机文案 + 热线卡点亮（**无 LLM 生成内容**）
6. 刷新页面 → 能看到历史会话并在原上下文继续
7. 故意停掉 Milvus → 对话仍可用，只是没有引用卡片

---

## 十、需要你提供或确认的事项

按阻塞程度排序：

| # | 事项 | 阻塞 | 说明 |
|---|---|---|---|
| 1 | ~~**DeepSeek API Key**~~ | ~~Phase 1~~ | ✅ **已解决**：密钥直写 `application.yml`（`psycare.llm.api-key`），并实测可用；环境变量 `PSYCARE_LLM_API_KEY` 仍可覆盖 |
| 2 | ~~**通义 embedding 的模型名 + Key**~~ | ~~Phase 3~~ | ✅ **已解决**：模型 `qwen3.7-text-embedding-flash`、维度 1024、批量上限 25、Key 直写 `application.yml`（`psycare.embedding.api-key`）；探针四断言全过，`EmbeddingClientHarness` 六项断言全过 |
| 3 | ~~**Milvus 版本号**~~ | ~~Phase 3~~ | ✅ **已解决**：`milvusdb/milvus:v3.0.0`（单容器内嵌 etcd）。Java SDK 对齐 **3.0.5**。探针四断言全绿（含中文 BM25 召回 4/4） |
| 4 | 知识库管理页放哪个菜单 | Phase 3 | 建议 `AdminLayout` 新增「知识库」（仅 ADMIN），复用现有表格样式 |
| 5 | 是否需要 Rerank | Phase 4 | 通义 `gte-rerank` 不在兼容模式里，需单独封装。**建议先不上**，等 Recall@5 不达标再加 |

**一个明确的建议**：Phase 0 今天就能做，无任何依赖，修的是真漏洞（越权 + 回复丢库）。
要不要先把它落掉，再按 Phase 1 往下走？

