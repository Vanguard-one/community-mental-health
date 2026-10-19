# 社区心理健康评测与问诊系统（PsyCare）

面向社区居民的心理测评、风险预警与多渠道问诊一体化系统。
核心是**测评结果驱动 AI 分诊与医生推荐的闭环**，四个"智能引擎"（计分分级、预警闭环、危机检测、医生推荐）已完整实现，CRUD 类模块给出参考实现。

---

## 一、技术栈与运行环境

| 层 | 技术 |
|---|---|
| 后端 | SpringBoot 3.3 + MyBatis-Plus 3.5 |
| 数据库 | MySQL 8 + Redis 6+ |
| 鉴权 | JWT + Redis 黑名单 |
| AI | LLM API（DeepSeek，OpenAI 兼容，SSE 流式） |
| 构建 | Maven，**JDK 17+** |

> ⚠️ 如果你本机只有 JDK 8：请降级到 SpringBoot 2.7.x，并把代码里的 `jakarta.*` 全部改成 `javax.*`，MyBatis-Plus 换成 `mybatis-plus-boot-starter`（非 boot3 版）。新项目建议直接用 JDK 17。

### 启动步骤

```bash
# 1. 准备 MySQL 与 Redis（本地或 Docker）
# 2. 执行建库与量表种子数据
mysql -uroot -p < sql/schema.sql

# 3. 配置数据库/Redis 连接与 LLM Key
#    直接改 src/main/resources/application.yml（api-key 已填，无需环境变量）
#    如需外部覆盖（生产不落盘密钥），注入 PSYCARE_LLM_API_KEY 即可，优先级高于 yml
# export PSYCARE_LLM_API_KEY=sk-你的key

# 4. 启动
mvn spring-boot:run
```

首次启动时 `DataInitializer` 会自动初始化账号：

| 账号 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 管理员 |
| doctor_li / doctor_wang / doctor_zhao | doctor123 | 医生（示例，含专长标签） |

居民账号通过 `/api/auth/register` 注册。

---

## 二、分层架构与依赖方向

```
controller ──▶ service ──▶ engine / ai ──▶ mapper ──▶ DB
    │            │
    └────────────┴──▶ common(result/enums/util) / config / security
```

- **依赖方向自上而下**，严禁下层调用上层（mapper 不依赖 service，entity 不依赖任何业务类）。
- `engine`（规则/算法）与 `ai`（模型接入）被 `service` 编排，自身不依赖 controller。
- `common` 与 `config` 是横切支撑，可被各层引用，但不反向依赖业务。

## 三、项目包结构目录树

```
community-mental-health/
├── pom.xml                          # Maven 依赖与构建
├── sql/                             # 建库与演进脚本，按序号顺序执行
│   ├── schema.sql                   # 全部建表语句 + 量表种子数据
│   └── v2..v5_*.sql                 # 角色拆分 / 账号治理 / finish_reason / 可观测性（幂等 DDL）
└── src/main/
    ├── resources/
    │   └── application.yml          # 数据源/Redis/JWT/LLM/预警规则配置
    └── java/com/cmh/psycare/
        ├── PsycareApplication.java  # 启动类
        ├── common/                  # 通用层：与业务无关
        │   ├── result/              #   Result / PageResult 统一响应包装
        │   ├── enums/               #   RiskLevel/AlertType/AlertStatus/ConsultType 枚举
        │   ├── exception/           #   BizException / GlobalExceptionHandler 全局异常
        │   └── util/                #   JwtUtil / SecurityUtil(登录上下文) / LoginUser
        ├── config/                  # 配置层：Bean 与横切配置
        │   ├── WebConfig.java       #   CORS + 注册 JWT 过滤器
        │   ├── JwtAuthFilter.java   #   JWT 解析 + 黑名单校验 + 写入登录上下文
        │   ├── MybatisPlusConfig.java #  分页插件
        │   ├── RedisConfig.java     #   Redis 序列化
        │   ├── CommonBeansConfig.java #  PasswordEncoder(BCrypt)
        │   └── DataInitializer.java #   启动初始化管理员/示例医生
        ├── entity/                  # 实体层：与表一一对应（DO）
        │   └── SysUser/ResidentProfile/DoctorProfile/Scale/ScaleItem/ScaleOption/
        │       ScoringRule/Assessment/AssessmentAnswer/AlertRecord/AlertStatusLog/
        │       ConsultSession/ConsultMessage/RecommendationLog/Notification
        ├── mapper/                  # 持久层：MyBatis-Plus BaseMapper
        │   └── 各实体对应的 Mapper 接口（AssessmentMapper 含自定义趋势查询）
        ├── dto/                     # 传输层：请求/响应对象，隔离 entity 与前端
        │   └── LoginRequest/LoginResponse/AssessmentSubmitRequest/
        │       AssessmentResultResponse/AlertHandleRequest/AiChatRequest/RecommendResult
        ├── engine/                  # 引擎层：规则与算法（系统"智能"所在）
        │   ├── ScoringEngine.java   #   计分与 ABCD 分级（切分点入库、反向计分、危机强制D）
        │   ├── ScoreResult.java     #   计分输出（record）
        │   ├── AlertEngine.java     #   三类预警评估（危机/阈值/趋势）+ 幂等建单
        │   ├── CrisisDetector.java  #   危机词本地检测（不依赖模型）
        │   └── RecommendationEngine.java # 医生三级匹配 + 加权排序
        ├── ai/                      # AI 接入层：模型供应商隔离 + 提示词 + 引导问诊
        │   ├── AiChatClient.java    #   对话客户端接口（Flux 流式）—— 换模型只换实现
        │   ├── OpenAiCompatClient.java # OpenAI 兼容 SSE 实现（DeepSeek；base-url/path 全配置化）
        │   ├── ChatMessage/LlmChunk/LlmUsage.java # 对话消息、流式分片、用量
        │   ├── LlmProperties/LlmErrorCode/LlmErrorMapper/LlmException.java # 6 类错误码映射
        │   ├── LlmStartupProbe/LlmProbeResult.java # 启动探活：Key 无效 / 模型名失效在启动日志暴露
        │   ├── PromptAssets.java    #   L1–L4 提示词常量块（前缀字节级稳定 → 命中上下文缓存）
        │   ├── ContextAssembler.java#   阶梯式上下文组装 + token 预算裁剪 + 摘要注入 + L5b 医生名单
        │   ├── TranscriptSummarizer.java # 超预算的早远对话压进 consult_session.summary
        │   ├── InterviewStage/StageRules/StageState/StageStore.java # 三段引导问诊（纯函数 + Redis）
        │   └── ReferralRules.java   #   一问一答式「是否要推荐医生」应允判定
        ├── service/                 # 业务层：编排引擎与持久层
        │   ├── AuthService.java     #   登录/注册/登出（含角色分流注册）
        │   ├── AssessmentService.java#  测评闭环编排（计分→落库→预警）
        │   ├── AlertService.java    #   预警工单 + 状态机处置
        │   ├── AiConsultService.java#   AI 问诊会话 + 流式 + 危机拦截 + 阶段推进 + 推荐信号
        │   ├── ChatService.java     #   医患聊天（会话归属校验 + 可咨询医生名单/科室分组）
        │   ├── RecommendService.java#   医生推荐编排
        │   ├── ScaleService.java    #   问卷页结构组装
        │   ├── NotificationService.java # 站内信
        │   └── AccountService/AdminService/ResidentInfoService.java # 账号治理 / 驾驶舱聚合 / 居民档案
        └── controller/              # 接口层：REST + SSE，参数校验，不含业务
            └── Auth/Assessment/Alert/AiConsult/Chat/Recommend/Scale/Notification/
                Account/Admin/ResidentInfo Controller
```

## 四、各包职责与核心类

| 包 | 职责 | 核心类与作用 |
|---|---|---|
| `common.result` | 统一响应 | `Result`/`PageResult`：所有接口返回一致结构 |
| `common.enums` | 业务字典 | `RiskLevel`(A-D)、`AlertStatus`(工单状态机)、`AlertType`、`ConsultType` |
| `common.exception` | 异常兜底 | `GlobalExceptionHandler`：把异常统一转 Result，不泄堆栈 |
| `common.util` | 工具 | `JwtUtil`(签发/解析)、`SecurityUtil`(ThreadLocal 取当前用户) |
| `config` | 装配 | `JwtAuthFilter`(鉴权)、`DataInitializer`(初始账号)、各配置 Bean |
| `entity` | 数据载体 | 与表对应的 DO，仅含字段，无业务逻辑 |
| `mapper` | 持久化 | `BaseMapper` 通用 CRUD；`AssessmentMapper.listRecent` 趋势查询 |
| `dto` | 接口契约 | 隔离 entity 与前端，带 `@Valid` 校验 |
| `engine` | **智能核心** | 见下方"四大引擎" |
| `ai` | 模型隔离 | `AiChatClient` 接口 + `DeepSeekClient` 实现，换模型不改业务 |
| `service` | 业务编排 | 串联引擎、AI、持久层，承载事务 |
| `controller` | 入口 | 参数校验、调用 service、SSE 透出 |

## 五、四大智能引擎（系统的技术亮点）

### 1. ScoringEngine —— 计分与风险分级
- 切分点（`scoring_rule`）与题目/选项**全部入库**，管理端可配置，不写死
- 支持反向计分题（`该题最大选项分 - 选项分`）
- **危机项（PHQ-9 第9题）得分 > 0 → 风险等级强制 D**，不走普通分级
- ABCD 映射存在 `scoring_rule.risk_level`，数据驱动

### 2. AlertEngine —— 三类预警
- `CRISIS`：危机项阳性，最高优先级
- `THRESHOLD`：单次测评达到 B/C/D
- `TREND`：**时间窗口内相邻两次测评总分涨幅超阈值**（纵向趋势，区别于单次阈值）
- 幂等：同居民同类型 PENDING 工单不重复创建

### 3. CrisisDetector —— 危机词本地检测
- 危机识别**必须用本地规则**，不能依赖大模型自觉（答辩红线）
- AI 问诊的用户输入与 AI 回复**双端检测**
- 命中返回固定危机文案（含援助热线），不交给模型生成

### 4. RecommendationEngine —— 医生三级匹配
- 风险等级 → 目标科室优先级
- 测评维度 → 专长标签匹配
- 综合排序：评分加成 − 负载惩罚 + 标签加成 + 科室优先级
- 推荐理由落库（`recommendation_log.reason`），演示可点开解释

## 六、关键业务流程与数据流转

### 流程 1：测评闭环（系统起点）
```
POST /api/assessment/submit
  → AssessmentService.submit
    → 校验量表/题目/作答完整性
    → ScoringEngine.score            （总分 + 原始等级 + ABCD + 危机标记）
    → 落库 assessment + assessment_answer
    → AlertEngine.evaluate           （可能生成 CRISIS/THRESHOLD/TREND 工单）
    → 返回 AssessmentResultResponse  （等级 + 建议文案）
  说明：assessment.id 是后续问诊、推荐、预警的关联锚点，这就是"测评驱动"。
```

### 流程 2：预警处置闭环（状态机）
```
GET  /api/alert/page                （管理端看待办）
POST /api/alert/{id}/handle         （状态机流转 + 写流转日志）
  合法流转：PENDING→CONFIRMED/REJECTED → PROCESSING → FOLLOWED → CLOSED
  非法流转直接 400；每次流转落 alert_status_log，全程可追溯。
```

### 流程 3：AI 问诊（SSE 流式 + 危机拦截）
```
POST /api/ai-consult/open           （开会话，可关联 assessmentId/alertId）
POST /api/ai-consult/chat  (SSE)
  → 用户输入先做 CrisisDetector 本地检测
      命中 → 不走模型，返回固定危机文案 + 触发 CRISIS 工单（转人工）
      未命中 → PromptBuilder 注入测评上下文 → DeepSeekClient 流式调用
             → 逐 token 透出 → 累计完整回复 → 回复侧再检测 → 落库
POST /api/ai-consult/{id}/close     （结束会话，可接推荐）
```

### 流程 4：医生推荐
```
GET /api/recommend/doctors?sessionId=&topN=3
  → RecommendService 取最近测评等级/维度
  → RecommendationEngine 三级匹配 + 加权排序 → 写 recommendation_log → 返回
```

## 七、配置文件说明（application.yml）

| 配置 | 作用 |
|---|---|
| `server.port / context-path` | 服务端口 18080，统一前缀 `/api` |
| `spring.datasource` | MySQL 连接（库名 psycare） |
| `spring.data.redis` | Redis 连接（会话上下文/JWT黑名单/预警待办） |
| `mybatis-plus.*` | 驼峰映射、逻辑删除、SQL 日志 |
| `psycare.jwt.secret / expire-hours` | JWT 密钥与有效期（生产必改密钥） |
| `psycare.llm.*` | LLM 供应商/base-url/api-key/model/超时/上下文轮数；`api-key` **直接写在 yml 里**，可用 `PSYCARE_LLM_API_KEY` 环境变量覆盖 |
| `psycare.alert.trend-score-rise / trend-window-days` | 趋势预警：涨幅阈值与比较窗口 |

## 八、已实现 vs. 待扩展

**已完整实现**：四大引擎、AI 流式问诊（含危机拦截）、测评闭环、预警状态机、医生推荐、鉴权、量表组装、站内信、量表种子数据、启动账号初始化。

**参考实现/待扩展**（照现有模式补齐即可）：
- 电话问诊（预约登记 + 回拨记录）：新增 `appointment` 相关接口
- 医生图文问诊（WebSocket）：`consult_session` 已预留 DOCTOR 类型与消息表
- 管理端可视化统计接口：基于 `assessment/alert_record` 做聚合查询
- 隐私脱敏切面：姓名/手机号展示层脱敏（可加 Jackson 序列化器）
- 操作审计日志：AOP 拦截管理端敏感接口

## 九、安全与合规红线（务必保留）

1. 危机词检测必须本地规则实现，**不能依赖模型自觉**
2. AI 不诊断、不开药，回答带边界声明，危机输入强制转人工
3. 心理数据属敏感个人信息：单独同意、展示脱敏、统计去标识化
4. 生产环境务必更换 `jwt.secret`，密码只用 BCrypt 哈希
