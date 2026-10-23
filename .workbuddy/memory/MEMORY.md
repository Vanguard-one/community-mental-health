# PsyCare 项目长期约定

社区心理健康评测与咨询系统。前端 `psycare-web`（Vue3+TS+Vite+antd-vue+Pinia+ECharts），
后端 `psycare-server`（Spring Boot+MyBatis-Plus，context-path `/api`，端口 18989）。

> 📕 硬约定 1–140 全表（含每条「违反了会怎样」）→ `硬约定.md`，改代码前先扫
> 📗 为什么这样设计 / 踩坑叙事 → `决策与细节.md`
> 📘 每日日志 `YYYY-MM-DD.md`（append-only，近期根因与改动都在这里）
> ⚠️ 本文件只留「状态 + 红线一句话 + 索引」，细节查上面三处，**不要凭记忆推断**

---

## 一、当前状态（截至 2026-10-23）

**已验收**：Phase 0–8.3；AI 模块 Phase 0/1/2/2.5 + Fix-1/Fix-2 + 未读红点；
Phase 3（RAG 离线管道，MySQL↔Milvus `chunk_id` 一致）。

**待用户验收（按时间倒序，近期为主）**：

| 主题 | 关键点 |
|---|---|
| **预约问诊功能（10-23 进行中）** | 原型 `.workbuddy/demo/appointment-preview.html` 已定稿（居民端一二级 / 医生端配置 / 通知页三段）。方案 `预约问诊开发方案.md`。**极简版：2 表**（`doctor_schedule` 带 `capacity` + `appointment`），不预生成号源，`BOOKED`/`CANCELLED` 两态落库。下一步写实施计划再开发 |
| 共用展示组件（10-23） | `RecommendedDoctors.vue` / `AssessmentReport.vue` 各服务两处；**同数据两处出现就抽组件，只差文案抽函数**（`utils/assessment.ts#formatMatchBasis`） |
| 问诊页按钮居中 + 下移（10-23 第 35/36 批） | `.report-ops` 用 `justify-content:center`（`margin:auto` 换行失效）；`.btn{flex:none}` 防挤扁；`.chat-doctors .report-ops{margin-top}` 单独补医生视图 |
| 顶部导航三枚（10-22 第 33/34 批） | `.chat-nav` 透明玻璃胶囊，`top:27px`；**对话/医生/报告全是本页切视图不路由**；`NavKey='chat'\|'doctor'\|'report'`；医生、报告视图共用 `.chat-report,.chat-doctors` 容器边距 |
| 空态粒子球 `OrbNoise.vue`（10-22） | Canvas 2D，色值唯一入口 `--orb-dot:#6f52f0`（别换 `--primary`）；有消息后挪到输入框左侧 `v-if="hasMessages && orbFits"` |
| 问诊页马赛克背景（10-22 第 22 批） | 复用 `MosaicLens`；配色走 `--lens-chat-*`（不覆盖登录页 `--lens-*`）；本页不再透 `--app-bg` |
| 输入区浮层（10-22 第 18–21 批） | `position:absolute; bottom:0`；`--chat-foot` 由 `ResizeObserver` 写入；chip 半透毛玻璃 70% 白（下限，65% 破 AA）；**chip 上禁 transform**；底部 mask 渐隐已按要求删除 |
| followups 动态追问（10-22） | `v13_ai_usage_kind.sql` 已执行；**`done` = 正文结束不是末尾**；`followupsPending` 三态；建议不落库 |
| OSS 图片存储（10-21/22） | `my-sky-x`/`oss-cn-beijing`，provider 已切 `oss`；`data/uploads/` 留两周回退 |
| 个人资料自助编辑（10-19） | 越权防线是「DTO 里没有 id」+ `getUserId`；URL 字段前缀白名单 |
| 更早待办 | 清空会话三选一弹窗（#125）、Phase 4 阈值 0.5 待标定、banner `id=1` 仍是「测试」 |

**改版现状（10-13）**：页面底低饱和蓝渐变；`SideRail.vue` 64px 窄图标栏；圆角三级
`--radius-ctrl 10 / --radius-sm 16 / --radius-lg 28`；新增 `DotField.vue`/`BrandPanel.vue`/`directives/reveal.ts`。

**长期规矩**：服务用户自己启动，我只做只读探测；**改完只报告，不自己跑无头截图验收**（#15）。

---

## 二、设计口径速查（改 UI 前必看）

- **三级面**：L0 页面底 → L1 白卡 `--card-bg` → L2 卡内分区 `--panel-bg` → L3 主色。
  ① 贴页面底的「面」只能白卡或主色；② 阴影只给 L1；③ 文字只落 `--text-1/2/3`
- **页面底分两端**（10-23）：居民端 `public/app-bg-resident.jpg` 整页插画
  （`DefaultLayout` 的 `.bg-art` 固定层，遮罩只调 `--bg-art-veil`，实测对照表在 `tokens.css`）；
  管理端/登录页仍是 `--app-bg` 渐变。这是硬约定 #110 的**唯一例外**，别「顺手统一」回去
- **对比度实测**：`--text-3` 只能落白面（`--panel-bg` 4.27、页面底 3.60 ✗）；
  `--primary` 落 `--primary-soft` 仅 4.44 → 换 `--primary-dark`(5.71)；
  `--risk-*` 实色落同族 soft 2.49~3.94 → 文字用 `--risk-*-ink`；
  危险实心按钮用 `--risk-*-ink` 填充（白字压 `--risk-d` 仅 4.21 ✗）
- **间距** `--space-block 32` / `--space-card 20` / `--space-inner 24`
- **字体**：h1–h6 走 `--font-serif`；卡片小标题（14–16px）走 `--font-sans`+600；数字 `.mono`
- **AI 图标** `components/IconAi.vue` 是全站唯一非 antd 导航图标，**别改回 `MessageOutlined`**
- **骨架屏**：成对用 `--skeleton-base`/`--skeleton-hi`（用 `--panel-bg` 会静默退化）
- **下划线输入框**（仅问诊页）：聚焦时底线 `transparent` + 3px `--grad-underline` 铺满 + `pointer-events:none`
- **不写 `-webkit-` 前缀**；`v-reveal` 挂区块、`data-anim` 挂元素；**消息气泡列表绝不挂动效**

---

## 三、最高频红线（31 条，细则见 `硬约定.md`）

1. 三依赖：MySQL 3306 / Redis 6379 / 后端 18989。缺 Redis → 登录后被踢回登录页
2. 业务失败走 **HTTP 200 + `body.code`**；只有未登录走真 401（#63）
3. **改后端必须真重启**；判进程用 `jcmd <pid> VM.uptime`（#7）
4. 服务用户自己启动，我只做只读探测（#15）
5. `vendor-antd` 预算 800 kB（实测 665.72）→ 新增 antd 组件必真构建看数字；
   `a-range-picker` +135 kB，替代 `datetime-local`/`type=number`/`checkbox`（#9/#82/#96）
6. 颜色只改 `tokens.css`，组件禁硬编码；antd 映射在 `App.vue`（#4）
7. 构建 `vue-tsc -b && vite build`，不为过构建删 `vue-tsc`（#6）
8. 管理端能力必须**后端独立断言角色**，前端隐藏按钮不算（#18）
9. **外部调用绝不进 `@Transactional`**，用 `TransactionTemplate` 划边界（#76）
10. 表结构/接口/模型名先实测或 `javap`；新增外呼代码必须真跑一次（#19/#25/#69/#70/#74）
11. **SSE 契约改前后端同改**；危机态服务端下发，前端禁关键词判断（#23/#34）
12. `<script setup>` 内 ref/computed **必须 `.value`**（`vue-tsc` 抓不到，#12）
13. 自定义 MyBatis XML 手写 `is_deleted = 0`（#10）；分块表例外（#75）
14. **「高风险」= `max(总分基线, 危机项覆写)`**；危机项分值不进总分（#105/#106）
15. `App.vue` 错误边界 key 必须是 `route.path`，不能 `fullPath`（#123）
16. **Milvus 冷启动 3~4 分钟，端口先于服务就绪** → `DEADLINE_EXCEEDED` 先怀疑竞速（#109）
17. **首帧延迟由「思考量」决定**（corr .968），与 prompt 长度/缓存无关；先查 `ai_usage_log`（#126）
18. 逐次请求的能力开关走**方法参数**不做成员状态；重试沿用同值（#127）
19. 关思考会丢危机兜底，只能「默认关 + 风险条件开回来」（#128）
20. **`watch` 写在其依赖的 `const` 之后**（TDZ → 白屏，build 查不出）；白屏但 build 绿 → 跑 `setup_smoke.mjs`（#129）
21. 「改自己资料」越权防线 = **DTO 里没有 id**；URL 字段前缀白名单（#131）
22. MP `updateById` 是 **NOT_NULL 策略**，清空必须 `LambdaUpdateWrapper.set(列, null)`（#132）
23. 子元素做宽度动画时**父容器宽度必须写死**（#135）
24. `application.yml` **不能**给「可 local 覆盖的开关」留同名默认值；正解 Java 字段默认值 +
    `@ConditionalOnProperty(matchIfMissing=true)`（#136）
25. OSS：bucket 公共读、后端代传、`public-domain` 带 `https://` 且作白名单前缀（#137）
26. **媒体查询必须排在它要覆盖的基础规则之后**（`@media` 不加权重，#140）
27. **`done` 只表示正文结束**，`followups` 排其后；解锁放 `onDone`、兜底放 `finally`；
    解析层 `done` 分支**不许 break**（#143）
28. **组件根节点不许无条件写内联 `width/height`**（内联优先于任何选择器 → 使用方改不了尺寸，
    症状是「整个看不见」，见 `OrbNoise` 被撑成 770×54）
29. **胶囊半圆两端圆角必须与父内壁同心**：`父高/2 − 描边 − 内边距 == 子高/2`
    （`56/2−1−5=22=44/2`；窄屏 `50/2−1−4=20`）。padding 与 height 是绑死的一对
30. **新增文件必须与引用它的旧文件同一提交**。只提旧文件 → HEAD 是坏的（本地能跑、别人 clone 编译不过），
    `git status` 还看不出来。提交前 `git stash -u` 后再 build（`360ea75` 漏了 7 处引用）
31. **写操作后必须让读缓存失效**。`if (list.length && !force) return` 式短路的列表不会自更新，
    症状「刚做完的事显示的还是上一次」——不报错、刷新就好，最难查。正解：打 `xxxStale=true`
    静默重取（成功才归 false），别清空数组（会闪）。`AssessmentResultResponse` 缺
    `scaleId/createdAt/residentId`，别在本地拼假记录 unshift

---

## 四、本机环境坑（展开见 `硬约定.md` §二）

- 清 `dist` / `node_modules/.vite` 用 PowerShell `[System.IO.Directory]::Delete($p,$true)`；
  **不要从 Bash 里调 `powershell -Command`**（沙箱拒绝）。Git Bash 下 `rm -f a b c` 会被 safe-delete 拦掉
- 本地 Maven 仓库 `D:\develop\apache-maven-3.9.4-bin\apache-maven-3.9.4\mvn_repo`，不是 `~/.m2`（#73）
- Python 用 `C:\Users\Lenovo\.workbuddy\binaries\python\envs\default\Scripts\python.exe`
  （pymysql/PIL/numpy/requests 全可用）；**托管版自带 requests 是坏的**
- `curl` 访问本机必须 `--noproxy '*'`；vite 默认只绑 `[::1]`
- 精确字符串匹配莫名失败 → 查非 ASCII 字符（实测 U+2011 冒充 `-`）

---

## 五、测试资产

- **账号**：`user01/user123`、`doctor_li|wang|zhao/doctor123`、`admin/admin123`
- **演示数据** `sql/v6_demo_seed.sql`（24 医生/10 科室/43 居民），回滚 `DELETE FROM sys_user WHERE id > 11;`
- `.workbuddy/scripts/`：`e2e_api_check.py`(32 项)、`run_sql_file.py`、`dump_table.py`、`check_ref_value.py`、
  `prompt_probe.py`(改提示词必跑)、`eval_retrieval.py`、`llm_probe.py`、`milvus_probe.py`、
  **`setup_smoke.mjs`**（先 `cd psycare-web` 再传相对路径；抓「白屏但 build 全绿」）
- 后端 `EmbeddingClientHarness`（改 `EmbeddingClient` 后必须跑，#69）

---

## 六、文档地图

| 文档 | 内容 |
|---|---|
| `硬约定.md` | 硬约定 1–140 全表 + 环境坑 + 测试资产 |
| `决策与细节.md` | 为什么这样设计；改设计前先读 |
| `预约问诊开发方案.md` | 预约流程现状核对、状态机、DDL、风险登记册 R-APT-1~7 |
| `开发文档.md` | 契约主文档：接口、数据库、缺口、Phase 各章、R-1~R-13 |
| `SSE事件协议.md` | 9 类事件流式契约；改 `/ai-consult/chat` 必读 |
| `部署说明.md` | 构建、nginx（含 SSE）、环境变量、Milvus、上线清单 |
| `AI助手开发计划.md` | AI 里程碑；起 Phase 编号前必读（#45） |
| `.workbuddy/demo/redesign-preview.html` | 改版样板页，视觉基准 |
| `.workbuddy/demo/appointment-preview.html` | 预约原型（居民端/医生端/通知页三段） |
