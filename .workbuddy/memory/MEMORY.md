# PsyCare 项目长期约定

社区心理健康评测与咨询系统。前端 `psycare-web`（Vue3+TS+Vite+antd-vue+Pinia+ECharts），
后端 `psycare-server`（Spring Boot+MyBatis-Plus，context-path `/api`，端口 18989）。

> 📕 **硬约定 1–125 全表 → `硬约定.md`（含每条「违反了会怎样」）—— 改任何代码前先扫一遍**
> 📗 决策论证 / 踩坑叙事 → `决策与细节.md`；更早完整版 → `archive/MEMORY-full-20260929.md`
> 📘 每日日志 → `YYYY-MM-DD.md`（append-only；近期问题的根因与改动都在这里）
> ⚠️ 本文件只留「状态 + 高频红线 + 索引」。细节一律查上面三处，**不要凭记忆推断**。

---

## 一、当前状态（截至 2026-10-16）

**已验收**：Phase 0–8.3；AI 模块 Phase 0/1/2/2.5 + Fix-1/Fix-2 + 未读红点；
Phase 3（RAG 离线管道）—— MySQL↔Milvus `chunk_id` 集合一致（子块 id 14–22）。

**已落码、待用户重启验收**：

| 批次 | 内容 | 待办 / 关键点 |
|---|---|---|
| **整体视觉改版**（2026-10-13，本批最大） | 按样板页全站落地，见下「改版现状」 | 待重启目视验收 |
| AI 问诊「清空会话」弹窗（2026-10-16） | `Modal.confirm` → `<a-modal centered>` 三选一（暂不清空 / 只清空聊天记录 / 清空并重置记忆） | **纯前端**。「重置记忆」必须先 `close()` 再 `startNewSession()`，只丢 sessionId 是静默无效（#125） |
| Phase 4 在线检索与引用 | `RetrievalPipeline` + L6 接线 + `citations` 事件 + 前端引用卡片 | 阈值初值 `0.5` 待 `eval_retrieval.py --grid threshold` 校准 |
| 首页轮播 banner | 后端已验收 | 前端待验收；比例 2.5:1 三处同源（#101/#102） |
| 评分标准规范化 + PHQ-15/PSQI | `v10_scale_standard_and_new_scales.sql` 已在真库执行完（20 条全 OK） | 只剩「重启后端」；确认后备份 `*_bak_v10` 可 drop |
| **AI 首帧提速（2026-10-16，前后端都动）** | 新增 `ThinkingPolicy`：`thinking` 三态（默认 `auto` = 默认关思考、命中风险条件开回来）+ 等待态从「闪烁光标」改「阶段文案 + 不确定流动条」 | 待重启后端验收。**改对了的判据**：普通轮次日志出现「思考模式=关闭」，首帧中位 1.53s → 目标 ~0.7s。见 #126/#127/#128 |
| 白屏修复（2026-10-17） | 上一批把 `watch(() => chat.sending)` 写在了 `const chat = useChatStore()` 之前 → TDZ → `setup()` 崩 → AI 问诊页整页白屏。已整块移到 store 初始化之后（#129） | 前端改动，**刷新即可见**。新增 `.workbuddy/scripts/setup_smoke.mjs` 作回归网，全站 41 个 `.vue` 已全绿 |
| ⚠️ **仍未做**（用户本轮没选） | 观测修复：`AiUsageRecorder` 把 `retrieved_count` **硬编码为 0**；`startedAt` 在检索与历史加载**之后**，前置耗时全未计量 | 验收 A/B 效果靠 `ai_usage_log.reasoning_tokens` 即可（=0 即证明关掉了），但**前置链路（向量化实测 274 ms）至今无法观测** |

**改版现状（2026-10-13 批；视觉基准 = `.workbuddy/demo/redesign-preview.html`）**：

- **页面底**：纯低饱和蓝渐变（`--app-bg`，只挂 `body`）；背景图与遮罩**都已下线**，**不再叠遮罩**（#110/#111）。
- **导航**：新建 `components/SideRail.vue` —— **64px 窄图标栏**，三端共用，菜单名走纯 CSS 悬浮提示；
  `--rail-w: 64px`；管理端保留 56px 玻璃顶栏（`--header-h: 56px`）；**居民端顶栏已整个删除**
  （含 4 处 `calc(100vh - var(--header-h))` 连锁改 `100vh`）。
  → 历史遗留的「232px vs 64px 侧栏」冲突**已按 64px 收敛**。
- **新增文件**：`SideRail.vue` / `DotField.vue`（登录页 WebGL 白点漂移场）/ `BrandPanel.vue` /
  `directives/reveal.ts`（动效引擎：原生 WAAPI 替代 Motion One，recipe 体系）。
- **圆角三级**：`--radius-ctrl` 10 / `--radius-sm` 16 / `--radius-lg` 28；阴影压平为单层柔阴影。
- `PageHeader.vue` 已补 L1 白面（副标题 3.31:1 → 4.8 ✓）。
- **登录/注册页**：先选身份 → 白板滑动互换；`ROLES` 三张身份卡（描边墨色图标、悬停只变蓝）；
  **管理员隐藏注册入口**；`.auth-action` 用「右侧锚定 + 外扩 1px」（#122）。

**待用户定夺**：`RiskLevelTag` 自身「实色字 + soft 底」不达 AA —— 属既有实现，本次未动。

**长期规矩**：服务一律用户自己启动，我只做只读探测（`netstat`/`tasklist`/`curl --noproxy '*'`）；
**改完只报告，不自己跑无头浏览器截图验收**（硬约定 #15 一带）。

---

## 二、设计口径速查（改 UI 前必看；全表见 `硬约定.md` §一）

- **三级面**：L0 页面底（body 渐变）→ L1 白卡 `--card-bg` → L2 卡内分区 `--panel-bg` → L3 主色 `--primary`。
  ① 贴页面底的「面」只能是白卡或主色（`--primary-soft` vs 页面底仅 **1.04:1**，贴上去等于隐形）；
  ② 阴影只给 L1；③ 文字只落 `--text-1/2/3` 三档。
- **对比度红线（实测值，别凭感觉）**：
  - `--text-3` 只允许落在**白面**上 —— 落 `--panel-bg` 只有 **4.27**、落页面底 **3.60**，都不达 AA 4.5。
  - `--primary` 落 `--primary-soft` 仅 **4.44** → 一律换 `--primary-dark`（**5.71**）。
  - `--risk-a/b/c/d` 实色落同族 `-soft` 只有 **2.49~3.94** → 文字一律用 `--risk-*-ink`，
    **实色只给色块 / 圆点 / 描边**（`RiskLevelTag.vue` 是这套的样板）。
  - **危险实心按钮**（如「清空并重置记忆」）填充色同样只能用 `--risk-*-ink`：
    白字压 `--risk-d` 实色只有 **4.21** ✗，压 `--risk-d-ink` 是 **6.23** ✓（#124）。
    ⚠️ 也别用 antd `danger`（默认 `#ff4d4f` 白字 ~3.4）+ 改全局 `colorError` 会牵连 Alert/message。
- **间距**：块间 `--space-block(32)` / 卡间 `--space-card(20)` / 卡内 `--space-inner(24)`。
- **字体**：h1–h6 走 `--font-serif`；**卡片小标题（16px 级）走 `--font-sans` + 600**；数字/连字用 `.mono`。
- **骨架屏**：底色/高光必须成对取 `--skeleton-base` / `--skeleton-hi`
  （复用 `--panel-bg` 会让 shimmer **静默退化成静止灰条**，#112）。
- **下划线输入框**（当前仅 AI 问诊）：透明底 + `border-bottom: 2px`，聚焦时 `--grad-underline`
  的 3px 渐变线从 0 铺满（`.composer:focus-within`）。⚠️ 聚焦时**必须**把底线设 `transparent`，
  否则 2px 灰 + 3px 渐变叠成 5px 双色边；动画线**必须** `pointer-events: none`（否则下沿 3px 点不中）。
  `--grad-underline` 是**全站唯一一处装饰性彩色**，别再复制到第二处（#130）。
- **不写 `-webkit-` 前缀**，只写标准属性，构建链会自动补（#113）。
- **动效**：`v-reveal="'<recipe>'"` 挂**区块容器**、`data-anim="<kind>"` 挂**元素**；
  **每区块一个语义化 recipe，禁止统一 fade-up**；每页只标 5~12 个元素；
  **对话页的消息气泡列表绝不挂动效**（新消息会像整块重排）。

---

## 三、最高频红线（16 条，细则见 `硬约定.md`）

1. 起项目三依赖：MySQL 3306 / **Redis 6379** / 后端 18989。缺 Redis → 登录后立刻被踢回登录页。
2. 业务失败走 **HTTP 200 + `body.code`**（Milvus REST 同理 #63）；只有未登录走真 401。
3. **改后端必须真重启**（类与 MyBatis XML 仅启动时加载）；判进程用 `jcmd <pid> VM.uptime`（#7）。
4. **服务一律用户自己启动**，我只做只读探测；需重启只给命令（#15）。
5. `vendor-antd` 预算 **800 kB**（实测 **714.54 kB**）→ 新增 antd 组件/图标前先掂量，且必须真构建看数字。
   ⚠️ `a-range-picker` 单个就 +135 kB 直接顶爆；替代品：`datetime-local` / `type=number` / `type=checkbox`（#9/#82/#96）。
6. 颜色只改 `styles/tokens.css`，组件禁硬编码色值；**antd 映射在 `App.vue`，改色需两处同步**（#4）。
7. 构建是 `vue-tsc -b && vite build`；不为过构建删 `vue-tsc`（#6）。
8. 管理端能力**必须后端独立断言角色**（`SecurityUtil.requireRole`），前端隐藏按钮不算（#18）。
9. **外部调用（向量化 HTTP / Milvus RPC）绝不进 `@Transactional`**，用 `TransactionTemplate` 显式划边界（#76）。
10. 表结构 / 接口 / 模型名**一律先实测或 `javap`**，绝不凭记忆（#19/#25/#70/#74）；新增外呼代码**必须真跑一次**（#69）。
11. **SSE 事件契约改动前后端同改**；危机态由服务端显式下发（`crisis` + `reason`），前端**禁止**关键词判断（#23/#34）。
12. `<script setup>` 内引 ref/computed **必须 `.value`**（`vue-tsc` 抓不到，只能靠 `check_ref_value.py`，#12）。
13. 自定义 MyBatis XML **必须手写 `is_deleted = 0`**（#10）；**分块表例外**（重建先删后插，#75）。
14. **「高风险」唯一口径 = `max(总分基线, 危机项覆写)`**；危机项分值绝不参与总分加总（#105/#106）。
15. **`App.vue` 错误边界的 key 必须是 `route.path`，绝不能用 `route.fullPath`**（#123）——
    否则任何 `?query=` 变化都会销毁重建整棵 `router-view` 子树（重发请求 + 清空未保存表单）。
16. **Milvus 冷启动 3~4 分钟，端口比服务先就绪** → 报 `DEADLINE_EXCEEDED` 先怀疑竞速，别查防火墙/代理（#109）。
17. **推理 API 的首帧延迟由「思考量」决定，与提示词长度/缓存命中基本无关**（#126）——
    实测 `corr(首帧, 思考token)=0.968`，而 prompt 长度 0.192、缓存命中 0.016。
    **先拉 `ai_usage_log` 做回归再决定优化什么**，别按「prompt 长就慢」的直觉动手。
18. **逐次请求的能力开关走方法参数，不做成客户端成员状态**（#127）—— 否则并发下表现为
    「偶尔快、偶尔慢」，无法复现；**重试必须沿用同一个值**。
19. **用模型能力换速度时，用「调用前就已知道的状态」做分流**（#128）：风险等级与引导阶段
    都在调模型前已在手里（零额外请求）。⚠️ 关键红线：**关思考会丢掉危机语境下的热线与安全兜底**，
    所以只能「默认关 + 风险条件开回来」，不能一刀切。
20. **`watch` / `watchEffect` 必须写在其依赖的 `const` 之后**（#129）—— 它们**定义时就执行 getter**，
    而 `const` 有暂时性死区 → `ReferenceError` → `setup()` 崩 → **整页白屏**，且
    `vue-tsc` / `vite build` **都查不出**（两者都不执行 setup）。
    「点了某页就白屏但 build 全绿」→ 先跑 `.workbuddy/scripts/setup_smoke.mjs`，别先怀疑路由或接口。

---

## 四、本机环境坑（一句话版；展开见 `硬约定.md` §二）

- Vite/构建报 safe-delete 拦截：清 `dist` / `node_modules/.vite` 用 PowerShell 的
  `[System.IO.Directory]::Delete($p,$true)`；**不要从 Bash 里调 `powershell -Command`**（沙箱直接拒绝）。
- 本地 Maven 真实仓库在 `D:\develop\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo`，**不是 `~/.m2`**（#73）。
- Python 用隔离 venv `C:\Users\Lenovo\.workbuddy\binaries\python\envs\default\Scripts\python.exe`
  （有 pymysql / PIL / numpy / requests）；**托管版 Python 自带的 requests 是坏的**。
- `curl` 访问本机必须加 `--noproxy '*'`；vite 默认只绑 `[::1]`。
- 字符串精确匹配莫名失败 → 查**非 ASCII 字符**（实测语料里是 `User‑Agent` 的 U+2011 连字符）。

---

## 五、测试资产

- **账号**：`user01/user123`(RESIDENT)、`doctor_li|wang|zhao/doctor123`、`admin/admin123`(SUPER)。
- **真库演示数据**（`sql/v6_demo_seed.sql`）：24 医生 / 10 科室 / 43 居民；回滚
  `DELETE FROM sys_user WHERE id > 11;`（快照 `*_bak_20260929`）。**演示数据不得进生产**。
- `.workbuddy/scripts/`：`e2e_api_check.py`(32 项)、`check_ref_value.py`、`check_form_fields.py`、
  `cdp-shot.mjs`、`run_sql_file.py`、`dump_table.py`、`redis_probe.py`、`llm_probe.py`、
  `milvus_probe.py`(四断言)、`qwen_embed_probe.py`、`verify_index.py`、
  `eval_retrieval.py`(检索质量 + `--grid threshold`)、`prompt_probe.py`(**改提示词后必跑**)、
  `rehearse_v10.py`（评分标准脚本演练台：跑两次验幂等）、
  **`setup_smoke.mjs`（把 `<script setup>` 真执行一遍，抓「白屏但 build 全绿」，#129）**。
- 后端验收程序 `EmbeddingClientHarness`（**改 `EmbeddingClient` 后必须跑**，#69）。
- **语料**：`corpus/topics.yaml`（6 域 81 主题）+ `synthetic_corpus.jsonl`；
  评测集 `corpus/queries_in.jsonl`(库内 24) / `queries_out.jsonl`(库外 10)。

---

## 六、文档地图

| 文档 | 内容 |
|---|---|
| `.workbuddy/memory/硬约定.md` | 硬约定 1–123 全表 + 环境坑 + 测试资产（**本文件的展开**） |
| `.workbuddy/memory/决策与细节.md` | 为什么这样设计；改设计前先读 |
| `开发文档.md` | 契约主文档：接口契约、数据库、缺口总表、Phase 各章、风险 R-1~R-13 |
| `SSE事件协议.md` | 前后端流式契约（9 类事件）；**改 `/ai-consult/chat` 必读** |
| `部署说明.md` | 构建、nginx（含 SSE）、环境变量、Milvus、排查、上线清单 |
| `AI助手开发计划.md` | AI 落地计划与里程碑；**起 Phase 编号前必读**（#45） |
| `.workbuddy/demo/redesign-preview.html` | 改版样板页（单文件 / 零依赖 / 4 视图可切换），视觉基准 |

> ⚠️ 本文件刻意只保留「状态 + 高频红线 + 索引」。任何「为什么这样设计」→ 查 `硬约定.md` /
> `决策与细节.md`，不要凭记忆推断。
