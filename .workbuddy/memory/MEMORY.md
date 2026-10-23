# PsyCare 项目长期约定

社区心理健康评测与咨询系统。前端 `psycare-web`（Vue3+TS+Vite+antd-vue+Pinia+ECharts），
后端 `psycare-server`（Spring Boot+MyBatis-Plus，context-path `/api`，端口 18989）。

> 📕 **硬约定 1–140 全表（含每条「违反了会怎样」）→ `硬约定.md`，改任何代码前先扫一遍**
> 📗 为什么这样设计 / 踩坑叙事 → `决策与细节.md`；更早完整版 → `archive/MEMORY-full-20260929.md`
> 📘 每日日志 → `YYYY-MM-DD.md`（append-only，近期问题的根因与改动都在这里）
> ⚠️ 本文件只留「状态 + 红线一句话 + 索引」。细节查上面三处，**不要凭记忆推断**。

---

## 一、当前状态（截至 2026-10-22）

**已验收**：Phase 0–8.3；AI 模块 Phase 0/1/2/2.5 + Fix-1/Fix-2 + 未读红点；
Phase 3（RAG 离线管道，MySQL↔Milvus `chunk_id` 一致，子块 id 14–22）。

**已落码、待用户验收**：

| 批次 | 待办 / 关键点 |
|---|---|
| 整体视觉改版（10-13） | 见下「改版现状」，待目视验收 |
| 「清空会话」三选一弹窗（10-16） | 纯前端。「重置记忆」必须先 `close()` 再 `startNewSession()`（#125） |
| Phase 4 在线检索与引用 | 阈值初值 `0.5` 待 `eval_retrieval.py --grid threshold` 校准 |
| 首页轮播 banner | 前端待验收。两轮改动见 #133/#134 |
| 评分标准规范化 + PHQ-15/PSQI | v10 已执行完；只剩重启后端，确认后 `*_bak_v10` 可 drop |
| AI 首帧提速（10-16） | `ThinkingPolicy` 三态。判据：日志出现「思考模式=关闭」，首帧中位 1.53s → ~0.7s（#126/#127/#128） |
| 白屏修复（10-17） | 前端改动，刷新即见；`setup_smoke.mjs` 成回归网（#129） |
| 个人资料自助编辑（10-19，入口 10-21 改） | **必须先跑 v11 再重启后端**，否则登录挂。授权靠「入参无 id + getUserId」（#131/#135）。e2e 第 10 节 10 项待跑 |
| 图片存储接 OSS（10-21/22） | 迁移已完成（`my-sky-x`/`oss-cn-beijing`，6 张全 200），`provider` 已切 `oss`，待重启后端。`data/uploads/` 留两周作回退（#136/#137） |
| **AI 问诊页按参考页重构（10-22）** | 铺满视口 + 去顶栏 AI 头像 + `.btn` 三件套 + 右栏改通栏面板 + ≤1100px 抽屉。对话区**全宽透明**，背景见下一行（第 22 批起是马赛克，不再是 L1 白面、也不再透 `--app-bg`） |
| **输入区改浮层（10-22 第 18–19 批）** | `.chat-input` 改 `position: absolute; bottom: 0; z-index: 2`，消息铺满整列从下面穿过；宽度尺子 `--chat-col 760 + --composer-bleed 5×2`；浮层高度由 `ResizeObserver` 写 `--chat-foot`，驱动 `padding-bottom`（+8）。**底部 `mask-image` 渐隐已在第 19 批按要求整体删除**（用户要「不模糊，只有被胶囊/chip 盖住的看不见」），`--chat-fade` 变量随之删除。**第 20 批 chip 改半透明毛玻璃**（`70% 白 + blur(18px) saturate(180%) + --chip-hi` 内高光）：70% 是算出来的下限（深蓝气泡经过时 `--text-2` 4.75:1，65% 破 AA），且 chip 上**禁止 transform**（backdrop-filter+位移=第十七轮「发虚」）。**第 21 批给输入胶囊加四层阴影光晕**（结构层 `--text-1` 6/10%，光学层 `--primary` 12/14%，最大 44px/‑4px），聚焦加强走 `.composer::after`（`inset:-1px; z-index:-1; pointer-events:none`，56px/22%）**只过渡 opacity 240ms**，不插值 box-shadow；输入中不变化。**改 `.vue` 后刷新即可，后端未动** |
| **动态追问建议 followups（10-22）** | 前后端已改完、编译/构建通过。`sql/v13_ai_usage_kind.sql` **已于 10-22 执行成功**（`kind varchar(16) NOT NULL DEFAULT 'CHAT'`，历史 130 行全为 CHAT，符合预期——改动前摘要根本没记）。契约变更：**`done` = 正文结束，不再是末尾**（#143）。第二轮按用户反馈修掉「思考时发虚 + 变回固定三条」：新增 `followupsPending` 三态（#146）、删掉 backdrop-filter 元素上的 transform 与 0.55 禁用透明度（#145）。第三轮确认「回复完仍先亮固定三条」是**用户标签页的 store 没热更新**（#147），代码侧只补了「收到建议即放行」。建议不落库，历史回看回落静态三条 |
| **问诊页换马赛克背景（10-22 第 22 批）** | 复用登录页的 `MosaicLens`（WebGL2，双通道）当本页背景：`.chat-lens`（`absolute inset:0; z-index:0; pointer-events:none`）+ 34% 白纱 `.chat-lens-veil`；配色另走 `--lens-chat-bg/c1/c2`（青底 `#7ce2e3` + 浅青/浅紫），**不覆盖登录页那三个 `--lens-*`**；参数按 Originkit 预设 `speed 47 / size 153 / tile 6 / hover 82 / reach 187`，本页 `dpr-cap=1.5` 节流。`.chat-main` / `.chat-side` 显式 `z-index: 1`。**本页从此不再透 body 的 `--app-bg`**，改底色只改 `--lens-chat-*`（#149） |
| **空态粒子球 OrbNoise（10-22）** | 纯前端，替换原「AI」实心圆 + 细环。`components/OrbNoise.vue`（Canvas 2D，`interactive` 默认 false 以保住移动端滚动）。色值唯一入口 `--orb-dot: #6f52f0`（**别换 --primary**：青底上同色相会糊）。**有消息后**改到输入框左侧（`.composer-orb`，`v-if="hasMessages && orbFits"`，尺寸 = 胶囊高、`--orb-size`/`--orb-gap` 由 `syncComposerOrb()` 实测写入；放不下就隐藏）。**组件为装饰层，样式改动不涉及后端** |
| **顶部导航（10-22 起；第 33 批收回 3 枚；第 34 批接「医生」）** | `.chat-nav` 绝对定位 `top: 27px` = (110 − 56)/2。菜单是**透明玻璃胶囊**：`--chrome-bg-glass` + `backdrop-filter: blur(--glass-blur) saturate(180%)` + `--radius-pill`（不新造色值）。**三枚：对话 / 医生 / 报告**（64→120 悬停展开，整体 204×56；`padding 5` 与 `height 44` 绑死，同心算式见红线 #29）。三枚**全部是本页切视图**（`goChatView` / `openDoctors` / `openReport`），不路由；`NavKey = 'chat' \| 'doctor' \| 'report'`。医生视图与报告视图共用 `.chat-report, .chat-doctors` 容器规则（110px/32px；≤560 → 72px/14px），边距差 4px 切换就会「跳」。`goScalePage()` 保留给报告页的「去测评」。窄屏 56/104、height 40、`.chat-body` padding-top 72；减动效段**必须在样式表最后** |
| **共用展示组件（10-23）** | `RecommendedDoctors.vue`（匹配依据 + 骨架 + 医生卡网格 + 空态）同时服务 `/ai-recommend` 与问诊页「医生」视图；`AssessmentReport.vue` 同时服务 `/assessment/:id/result` 与「报告」视图。**同一份数据在两处出现时，抽组件而不是复制模板**；只差文案的（如「匹配依据」）抽函数（`utils/assessment.ts` 的 `formatMatchBasis`）。`立即咨询` 的跳转写死在组件内 —— 全站只有一个目的地 |
| ⚠️ 仍未做 | `AiUsageRecorder` 把 `retrieved_count` 硬编码 0；`startedAt` 在检索之后 —— 前置耗时（向量化实测 274ms）无法观测 |

**改版现状（10-13 批；视觉基准 `.workbuddy/demo/redesign-preview.html`）**：
页面底为纯低饱和蓝渐变（`--app-bg` 只挂 body，背景图/遮罩已下线）；导航是
`SideRail.vue` **64px 窄图标栏**（`--rail-w: 64px`，居民端顶栏已删，管理端 56px 玻璃顶栏）；
新增 `DotField.vue` / `BrandPanel.vue` / `directives/reveal.ts`；
圆角三级 `--radius-ctrl 10 / --radius-sm 16 / --radius-lg 28`；`PageHeader` 已补 L1 白面。

**待用户定夺**：① `RiskLevelTag`「实色字 + soft 底」不达 AA（既有实现）；
② banner `id=1「测试」` 仍在轮播；③ 未登录访客点 banner 是否跳 `/scales` 而非答题页；
④ **本轮全部改动尚未 commit，仓库是 public**。

**长期规矩**：服务一律用户自己启动，我只做只读探测；**改完只报告，不自己跑无头浏览器截图验收**（#15）。

---

## 二、设计口径速查（改 UI 前必看；全表见 `硬约定.md` §一）

- **三级面**：L0 页面底 → L1 白卡 `--card-bg` → L2 卡内分区 `--panel-bg` → L3 主色。
  ① 贴页面底的「面」只能白卡或主色；② 阴影只给 L1；③ 文字只落 `--text-1/2/3`。
- **对比度实测值**：`--text-3` 只能落白面（落 `--panel-bg` 4.27、落页面底 3.60 ✗）；
  `--primary` 落 `--primary-soft` 仅 4.44 → 一律换 `--primary-dark`(5.71)；
  `--risk-*` 实色落同族 soft 仅 2.49~3.94 → 文字用 `--risk-*-ink`，实色只给色块/圆点/描边；
  危险实心按钮填充用 `--risk-*-ink`（白字压 `--risk-d` 仅 4.21 ✗，压 `-ink` 6.23 ✓，#124）。
- **间距** `--space-block(32)` / `--space-card(20)` / `--space-inner(24)`。
- **字体**：h1–h6 走 `--font-serif`；**卡片小标题（14–16px）走 `--font-sans` + 600**；数字用 `.mono`。
- **AI 图标**：侧栏「AI 问诊」+ 问诊页顶部第一枚按钮共用 `components/IconAi.vue`（四角星芒），
  是全站**唯一**非 antd 的导航图标。**不要改回 `MessageOutlined` 之类** —— 在 20px 下与
  「我的消息」分不开。尺寸靠 svg 根节点 `width/height="1em"`：侧栏 `.rail-item` 是
  `font-size: 20px` → 20px；问诊页 `.nav-icon svg { 100% }` 覆盖 → 22px。
- **骨架屏**：必须成对用 `--skeleton-base` / `--skeleton-hi`（复用 `--panel-bg` 会让
  shimmer 静默退化成静止灰条，#112）。
- **下划线输入框**（仅 AI 问诊）：透明底 + 2px 底线；聚焦时底线必须设 `transparent`，
  3px `--grad-underline` 线用 `:focus-within` 铺满，且必须 `pointer-events: none`（#130）。
- **不写 `-webkit-` 前缀**（#113）。动效：`v-reveal` 挂区块、`data-anim` 挂元素，
  每区块一个语义化 recipe、每页 5~12 个元素；**消息气泡列表绝不挂动效**。

---

## 三、最高频红线（31 条，细则见 `硬约定.md`）

1. 起项目三依赖：MySQL 3306 / Redis 6379 / 后端 18989。缺 Redis → 登录后被踢回登录页。
2. 业务失败走 **HTTP 200 + `body.code`**；只有未登录走真 401（#63）。
3. **改后端必须真重启**；判进程用 `jcmd <pid> VM.uptime`（#7）。
4. 服务用户自己启动，我只做只读探测（#15）。
5. `vendor-antd` 预算 **800 kB**（实测 **665.72 kB**）→ 新增 antd 组件必须真构建看数字；
   `a-range-picker` 单个 +135 kB，替代品 `datetime-local`/`type=number`/`checkbox`（#9/#82/#96）。
6. 颜色只改 `tokens.css`，组件禁硬编码（内联 SVG 用 `currentColor`）；antd 映射在 `App.vue`（#4）。
7. 构建是 `vue-tsc -b && vite build`，不为过构建删 `vue-tsc`（#6）。
8. 管理端能力必须**后端独立断言角色**，前端隐藏按钮不算（#18）。
9. **外部调用绝不进 `@Transactional`**，用 `TransactionTemplate` 划边界（#76）。
10. 表结构/接口/模型名一律先实测或 `javap`（#19/#25/#70/#74）；新增外呼代码必须真跑一次（#69）。
11. **SSE 契约改动前后端同改**；危机态由服务端下发，前端禁止关键词判断（#23/#34）。
12. `<script setup>` 内 ref/computed **必须 `.value`**（`vue-tsc` 抓不到，#12）。
13. 自定义 MyBatis XML 必须手写 `is_deleted = 0`（#10）；分块表例外（#75）。
14. **「高风险」= `max(总分基线, 危机项覆写)`**；危机项分值不进总分（#105/#106）。
15. `App.vue` 错误边界的 key 必须是 **`route.path`**，不能 `fullPath`（#123）。
16. **Milvus 冷启动 3~4 分钟，端口先于服务就绪** → `DEADLINE_EXCEEDED` 先怀疑竞速（#109）。
17. **首帧延迟由「思考量」决定**（`corr=0.968`），与 prompt 长度/缓存命中无关；先查
    `ai_usage_log` 再优化（#126）。
18. 逐次请求的能力开关走**方法参数**，不做成员状态；重试沿用同一个值（#127）。
19. 用模型能力换速度时用「调用前已知的状态」分流；**关思考会丢危机兜底**，
    只能「默认关 + 风险条件开回来」（#128）。
20. **`watch` 写在其依赖的 `const` 之后**（TDZ → setup 崩 → 整页白屏，构建查不出）；
    白屏但 build 全绿 → 先跑 `setup_smoke.mjs`（#129）。
21. 「改自己资料」的越权防线是**「DTO 里没有 id」**；URL 字段必须前缀白名单（#131）。
22. MP 的 `updateById` 是 **NOT_NULL 策略**，清空必须 `LambdaUpdateWrapper.set(列, null)`（#132）。
23. 子元素做宽度动画时**父容器宽度必须写死**，否则症状是「别的元素在跳」（#135）。
24. `application.yml` **绝不能**给「可在 local 覆盖的开关」留同名默认值（导入文档优先级更低）；
    正解：Java 字段默认值 + `@ConditionalOnProperty(matchIfMissing = true)`（#136）。
25. OSS 三条硬约束：bucket **公共读**、**后端代传**、`public-domain` 带 `https://` 且作白名单前缀（#137）。
26. **媒体查询必须排在它要覆盖的基础规则之后**（同特异性后者胜，`@media` 不加权重）；
    断点改了没生效 → 先 grep 选择器出现顺序（#140）。
27. **`done` 只表示「正文结束」，不是流结束** —— `followups` 排在它之后。
    解锁放 `onDone`、兜底放 `finally`，两处都要 `myIdx` 守卫；
    解析层在 `done` 分支**不许 break**（#143）。
28. **组件根节点不许无条件写内联 `width/height`**（哪怕值是 `100%`）：内联优先级
    高于任何选择器，使用方就再也覆盖不了尺寸 → 症状是「元素整个看不见」而不是
    「尺寸不对」（`OrbNoise` 在输入框左侧被撑成 770×54 裁掉，见 10-22 第 25 批）。
29. **胶囊里做「半圆两端」时，圆角必须与父内壁同心**：子项 `border-radius: var(--radius-pill)`
    且 `父高/2 − 描边 − 内边距 == 子高/2`（导航胶囊：`56/2−1−5 = 22 = 44/2`）。
    因此 **padding 与 height 是绑死的一对**，单独改任一个 → 端点露出月牙形的缝
    （用户会描述成「边缘不整洁」）。窄屏 40 高时同式：`50/2−1−4 = 20`。
30. **新增文件必须与「引用它的旧文件」进同一个提交**。只提旧文件 → HEAD 是**坏的**：
    远端能 checkout、本地能跑（新增文件还在工作区里），但别人 clone 下来直接编译不过，
    而且本地 `git status` 只会显示「新增文件未跟踪」，看不出 HEAD 已经坏了。
    症状隐蔽、只在别人机器上出现。提交前自检：
    `git stash -u` 后再 build，或 `git clone` 到临时目录构建一次。
    （`360ea75` 就是这样漏了 7 处引用：IconAi/OrbNoise/ProfileModal/ScaleManage/
    FollowupSuggester/LlmCompletion/FileStorage，见 2026-10-23 日志。）
31. **写操作之后必须让对应的读缓存失效**。Pinia store 跨路由不销毁，凡是
    「`if (list.length && !force) return`」式短路的列表，提交/删除后不会自己更新，
    症状是「刚做完的事，页面显示的还是上一次的」——**不报错、刷新就好，最难查**。
    正解：写操作里打 `xxxStale = true` 并静默重取（成功才归 false，失败留着下次重试），
    不要直接清空数组（界面会先掉回空态再变满，闪一下）。
    `AssessmentResultResponse` 缺 `scaleId/createdAt/residentId`，所以也别想
    在本地凭响应拼一条记录 unshift 进去冒充真数据（见 2026-10-23 第 33 批）。

---

## 四、本机环境坑（一句话版；展开见 `硬约定.md` §二）

- 清 `dist` / `node_modules/.vite` 用 PowerShell `[System.IO.Directory]::Delete($p,$true)`；
  **不要从 Bash 里调 `powershell -Command`**（沙箱直接拒绝）。
- 本地 Maven 仓库在 `D:\develop\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo`，不是 `~/.m2`（#73）。
- Python 用 `C:\Users\Lenovo\.workbuddy\binaries\python\envs\default\Scripts\python.exe`
  （有 pymysql/PIL/numpy/requests）；**托管版自带 requests 是坏的**。
- `curl` 访问本机必须 `--noproxy '*'`；vite 默认只绑 `[::1]`。
- 精确字符串匹配莫名失败 → 查非 ASCII 字符（实测是 U+2011 连字符冒充 `-`）。

---

## 五、测试资产

- **账号**：`user01/user123`、`doctor_li|wang|zhao/doctor123`、`admin/admin123`。
- **真库演示数据** `sql/v6_demo_seed.sql`（24 医生/10 科室/43 居民），回滚
  `DELETE FROM sys_user WHERE id > 11;`。**演示数据不得进生产**。
- `.workbuddy/scripts/`：`e2e_api_check.py`(32 项)、`check_ref_value.py`、`check_form_fields.py`、
  `cdp-shot.mjs`、`run_sql_file.py`、`dump_table.py`、`redis_probe.py`、`llm_probe.py`、
  `milvus_probe.py`、`qwen_embed_probe.py`、`verify_index.py`、`eval_retrieval.py`、
  `prompt_probe.py`(改提示词后必跑)、`rehearse_v10.py`、`oss_migrate.py`、
  **`setup_smoke.mjs`（先 `cd psycare-web`，再传 `<相对路径>`；抓「白屏但 build 全绿」）**。
- 后端 `EmbeddingClientHarness`（改 `EmbeddingClient` 后必须跑，#69）。
- **语料**：`corpus/topics.yaml` + `synthetic_corpus.jsonl`；评测集 `queries_in/out.jsonl`。

---

## 六、文档地图

| 文档 | 内容 |
|---|---|
| `.workbuddy/memory/硬约定.md` | 硬约定 1–140 全表 + 环境坑 + 测试资产（本文件的展开） |
| `.workbuddy/memory/决策与细节.md` | 为什么这样设计；改设计前先读 |
| `开发文档.md` | 契约主文档：接口、数据库、缺口总表、Phase 各章、风险 R-1~R-13 |
| `SSE事件协议.md` | 9 类事件的流式契约；**改 `/ai-consult/chat` 必读** |
| `部署说明.md` | 构建、nginx（含 SSE）、环境变量、Milvus、排查、上线清单（含 `psycare.upload.*`） |
| `AI助手开发计划.md` | AI 里程碑；**起 Phase 编号前必读**（#45） |
| `.workbuddy/demo/redesign-preview.html` | 改版样板页，视觉基准 |
