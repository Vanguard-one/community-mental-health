# PsyCare 前端实现方案

## 上下文

PsyCare 是一个完整的社区心理健康后端系统（Spring Boot 3.3.5 + MyBatis-Plus + MySQL + Redis + JWT + DeepSeek AI），已实现测评闭环、预警工单、AI 流式咨询、医生推荐、站内信等 7 大模块。当前缺少前端界面。用户要求：

- 前端放在同一项目内但独立一个包，与后端分隔
- Vue 3 + TypeScript + Ant Design Vue 4
- 居民端、管理端、医生端均用 Web 桌面端
- 后端代码移到 `psycare-server/`，前端新建 `psycare-web/`
- 提供分步骤的实现方案，后续逐步开发

## 最终目录结构

```
community-mental-health/
├── psycare-server/             ← 当前后端整体移入
│   ├── pom.xml
│   ├── sql/
│   │   └── schema.sql
│   └── src/main/...
├── psycare-web/                ← 新建前端项目
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.ts
│       ├── App.vue
│       ├── router/index.ts
│       ├── stores/             (Pinia)
│       ├── api/                (Axios 封装 + 各模块 API)
│       ├── layouts/            (布局组件)
│       ├── views/              (页面组件，按模块分目录)
│       └── components/         (公共组件)
└── README.md
```

## 技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3.4+ (Composition API + `<script setup>`) | 用户指定 |
| 语言 | TypeScript 5.x | 类型安全 |
| 构建 | Vite 5.x | 快速 HMR |
| UI 组件库 | Ant Design Vue 4.x | 用户指定，企业级桌面组件 |
| 图标 | @ant-design/icons-vue | Ant Design 图标 |
| 路由 | Vue Router 4.x | 嵌套路由 + 路由守卫 |
| 状态管理 | Pinia | Vue 3 官方推荐 |
| HTTP 客户端 | Axios | 拦截器统一注入 JWT |
| SSE 流式 | fetch + ReadableStream | AI 对话流式接收 |
| 日期 | Day.js | Ant Design Vue 内置依赖 |
| 图表 | ECharts 5 | 仪表盘统计可视化 |

## 后端需要调整的部分

### 0. 后端目录重组（前期准备）

把当前项目根目录下所有内容移到 `psycare-server/` 子目录：
- 直接 `mv` 除 `.git` 外的所有内容到 `psycare-server/`
- 修改 `application.yml` 中 CORS 允许的 origin（指向前端 dev server `http://localhost:5173`）
- 确认 `WebConfig.java` 的 CORS 配置对前端地址开放

### 后端需要新增的 API（仪表盘阶段）

在第七步 Dashboard 阶段，需要在后端新增一个 `AdminController`：

```
GET  /api/admin/dashboard/stats     → 总用户数、总测评数、待处理预警数、今日新增
GET  /api/admin/dashboard/trend     → 近30天测评趋势（每天测评数 / 平均分）
GET  /api/admin/dashboard/risk-dist → 风险等级分布（A/B/C/D 饼图数据）
GET  /api/admin/dashboard/alert-sum → 预警状态汇总（PENDING/CONFIRMED/PROCESSING/CLOSED 数量）
```

需要新建：
- `AdminController.java`
- `AdminService.java`
- `AdminDashboardMapper.java`（聚合查询）

---

## 分步实现计划（共 9 步）

---

### 第一步：项目脚手架搭建

**目标**：Vite + Vue 3 + TypeScript + Ant Design Vue 项目骨架跑起来，能访问空白页面。

**操作**：
1. 在 `community-mental-health/` 下执行 `npm create vite@latest psycare-web -- --template vue-ts`
2. 安装依赖：
   ```bash
   cd psycare-web
   npm install
   npm install ant-design-vue @ant-design/icons-vue
   npm install vue-router pinia axios
   npm install echarts vue-echarts   # 仪表盘阶段用，先装上
   npm install -D @types/node        # Vite 配置需要 path 模块
   ```
3. 配置 `vite.config.ts`：设置 `resolve.alias`（`@` → `src/`），配置 dev server 端口 5173，配置代理将 `/api` 转发到 `http://localhost:18989`
4. 配置 `tsconfig.json`：路径别名与 Vite 一致
5. 在 `main.ts` 中注册 Ant Design Vue、Vue Router、Pinia
6. 创建最小 `App.vue`：`<router-view />`
7. 创建 `router/index.ts`：空路由表，后续逐步添加
8. 验证：`npm run dev` 能访问 `http://localhost:5173`

**涉及文件**：
- `psycare-web/package.json`
- `psycare-web/vite.config.ts`
- `psycare-web/tsconfig.json`
- `psycare-web/tsconfig.app.json`
- `psycare-web/src/main.ts`
- `psycare-web/src/App.vue`
- `psycare-web/src/router/index.ts`
- `psycare-web/index.html`

---

### 第二步：后端目录重组 + CORS 连通

**目标**：后端代码移入 `psycare-server/`，前后端能通过代理联调。

**操作**：
1. 在 `community-mental-health/` 下创建 `psycare-server/` 目录
2. 将以下内容移入 `psycare-server/`：
   - `pom.xml`
   - `sql/`
   - `src/`
   - `.gitignore`（如有）
3. 在 `psycare-server/src/main/resources/application.yml` 中确认端口为 18989
4. 检查后端 `WebConfig.java` 的 CORS 配置，确保允许 `http://localhost:5173`
5. 验证：启动后端，前端通过 proxy 调用 `/api/scale/list` 能拿到数据

**涉及文件**：
- 整个项目移动到 `psycare-server/`
- `psycare-server/src/main/resources/application.yml`
- `psycare-server/src/main/java/com/cmh/psycare/config/WebConfig.java`

---

### 第三步：认证模块（登录/注册/登出 + 路由守卫）

**目标**：用户能注册账号、登录获取 Token、Token 过期自动跳转登录页。

**操作**：
1. 创建 `src/api/request.ts` — Axios 实例：
   - `baseURL: '/api'`
   - 请求拦截器：从 localStorage 读取 token，注入 `Authorization: Bearer xxx` 头
   - 响应拦截器：401 时清除 token 并跳转 `/login`
2. 创建 `src/api/auth.ts` — 封装 3 个接口：
   - `login(username, password)` → `LoginResponse`
   - `register(username, password, realName, phone)` → `userId`
   - `logout()`
3. 创建 `src/stores/auth.ts` — Pinia store：
   - `token`, `userInfo`（LoginResponse 字段）
   - `login()`, `register()`, `logout()`, `fetchUserInfo()`
   - 持久化 token 到 localStorage
4. 创建页面：
   - `src/views/auth/Login.vue` — 用户名密码表单，登录成功按角色跳转（admin→/admin/alerts, doctor→/home, resident→/home）
   - `src/views/auth/Register.vue` — 注册表单（用户名、密码、真实姓名、手机号）
5. 创建路由守卫 `src/router/guards.ts`：
   - 白名单：`/login`, `/register`
   - 其他路由：无 token → 跳转 `/login`
6. 路由表中添加 auth 路由
7. 验证：注册→登录→看 token→登出→登出后无法访问受保护页面

**涉及文件**（新建）：
- `src/api/request.ts`
- `src/api/auth.ts`
- `src/stores/auth.ts`
- `src/views/auth/Login.vue`
- `src/views/auth/Register.vue`
- `src/router/guards.ts`
- `src/router/index.ts`（更新）

---

### 第四步：量表浏览 + 问卷作答 + 测评结果

**目标**：居民能看到量表列表、点进去做问卷、提交后看到风险等级和结果建议。这是系统核心入口。

**操作**：
1. 创建 `src/api/assessment.ts`：
   - `getScaleList()` → `List<Scale>`
   - `getScaleDetail(id)` → `{ scale, items, options }`（嵌套结构）
   - `submitAssessment(scaleId, answers)` → `AssessmentResultResponse`
2. 创建 `src/stores/assessment.ts`：当前测评流程状态（选中的量表、作答、结果）
3. 创建布局 `src/layouts/DefaultLayout.vue`：
   - 顶部导航栏：Logo "PsyCare" + 菜单（首页、量表测评、AI咨询、医生推荐、通知、右上角用户头像下拉菜单含退出）
   - 使用 Ant Design Vue 的 `<a-layout>`, `<a-layout-header>`, `<a-layout-content>`
4. 创建页面：
   - `src/views/assessment/ScaleList.vue` — 卡片列表展示启用量表（量表名称、描述、题目数），点击进入问卷
   - `src/views/assessment/Questionnaire.vue` — 逐题展示（单题卡片，选项为 Radio），底部进度条 + "提交"按钮
   - `src/views/assessment/Result.vue` — 结果展示：总分、风险等级（颜色区分 A绿/B蓝/C橙/D红）、等级描述、建议文案。底部操作按钮：去 AI 咨询、查看推荐医生
5. 路由表添加：`/scales`, `/scales/:id`, `/assessment/:id/result`
6. 验证：浏览量表→做 PHQ-9 问卷→提交→看到风险等级和结果建议

**涉及文件**（新建）：
- `src/api/assessment.ts`
- `src/stores/assessment.ts`
- `src/layouts/DefaultLayout.vue`
- `src/views/assessment/ScaleList.vue`
- `src/views/assessment/Questionnaire.vue`
- `src/views/assessment/Result.vue`
- `src/router/index.ts`（更新）

---

### 第五步：AI 咨询（SSE 流式对话）

**目标**：居民能开启 AI 咨询会话、发送消息、看到流式回复逐字输出。这是系统的核心亮点功能。

**操作**：
1. 创建 `src/api/consult.ts`：
   - `openSession(assessmentId?, alertId?)` → `sessionId`
   - `chatStream(sessionId, content)` — 使用 `fetch` + `ReadableStream` 解析 SSE，返回 `ReadableStream` 或使用 async generator
   - `closeSession(sessionId)`
2. 创建 `src/stores/chat.ts`：
   - 消息列表 `Message[]`（每条：role: user/ai, content, time）
   - 当前 sessionId
   - `sendMessage(content)` — POST 到 `/chat`，逐 token 追加到最后一条 AI 消息
3. 创建页面：
   - `src/views/consult/AiChat.vue` — 经典聊天界面：
     - 顶部：会话标题 + 关闭按钮
     - 中间：消息列表（气泡样式，用户右侧蓝色、AI 左侧白色），支持滚动到底部
     - 底部：输入框 + 发送按钮，发送中禁用
     - AI 回复时显示闪烁光标表示正在输入
     - 流式效果：逐字追加到气泡
   - `src/views/consult/SessionStart.vue` — 开始页面：选择关联测评（可选），点击"开始咨询"
4. 路由表添加：`/ai-chat`, `/ai-chat/:sessionId`
5. 验证：开始咨询→发送消息→看到 DeepSeek 逐字回复→关闭会话

**流式实现要点**：
- 使用 `fetch` 而非 Axios（Axios 不支持流式）
- 解析 `text/event-stream`：按 `\n\n` 分割事件，提取 `data:` 行
- 将 token 通过 callback 追加到消息列表，触发 Vue 响应式更新
- 危机输入检测：后端返回固定安全话术时不再转发 LLM，前端正常展示

**涉及文件**（新建）：
- `src/api/consult.ts`
- `src/stores/chat.ts`
- `src/views/consult/AiChat.vue`
- `src/views/consult/SessionStart.vue`
- `src/router/index.ts`（更新）

---

### 第六步：预警工单管理（管理端/医生端）

**目标**：管理员和医生能看到预警工单列表、查看详情、执行工单状态流转。

**操作**：
1. 创建 `src/api/alert.ts`：
   - `getAlertPage(current, size, status?)` → `PageResult<AlertRecord>`
   - `handleAlert(id, toStatus, note?)` → `void`
   - `getAlertLogs(id)` → `List<AlertStatusLog>`
2. 创建 `src/stores/alert.ts`：分页状态、筛选条件
3. 创建管理端布局 `src/layouts/AdminLayout.vue`：
   - 侧边栏导航（预警工单、仪表盘）+ 顶部 Header
4. 创建页面：
   - `src/views/alert/AlertList.vue` — Ant Design Vue `<a-table>`，列：居民、预警类型标签（CRISIS红/THRESHOLD橙/TREND蓝）、风险等级、触发时间、当前状态。操作栏：查看详情/处置按钮。顶部筛选：状态下拉。分页。
   - `src/views/alert/AlertDetail.vue` — 预警详情 + 状态流转操作区：
     - 基本信息卡片（居民信息、关联测评分数、触发原因）
     - 状态流转按钮（根据当前状态显示合法下一状态：PENDING→确认/驳回→处理中→已跟进→结案）
     - 操作备注输入框
     - 流转日志时间线（使用 Ant Design `<a-timeline>`）
5. 路由表添加：`/admin/alerts`, `/admin/alerts/:id`
6. 路由守卫增强：admin/doctor 角色才能访问管理路由
7. 更新 `DefaultLayout.vue`：根据角色显示不同导航项
8. 验证：admin 登录→看到预警列表→点击处置→状态流转→日志可见

**涉及文件**（新建）：
- `src/api/alert.ts`
- `src/stores/alert.ts`
- `src/layouts/AdminLayout.vue`
- `src/views/alert/AlertList.vue`
- `src/views/alert/AlertDetail.vue`
- `src/router/index.ts`（更新）
- `src/router/guards.ts`（更新）

---

### 第七步：管理端仪表盘 + 后端统计 API

**目标**：管理员登录后看到系统数据大盘，展示测评趋势、风险分布、预警概况。

**操作**：
1. **后端新增**（在 `psycare-server` 中）：
   - `src/main/java/com/cmh/psycare/controller/AdminController.java` — 4 个统计接口
   - `src/main/java/com/cmh/psycare/service/AdminService.java` — 聚合查询编排
   - `src/main/java/com/cmh/psycare/mapper/AdminDashboardMapper.java` — SQL 聚合（GROUP BY、COUNT、AVG）
   - 可选：新建 `src/main/resources/mapper/AdminDashboardMapper.xml` 写复杂 SQL
2. **前端新增**：
   - `src/api/admin.ts` — 封装 4 个统计接口
   - `src/stores/dashboard.ts`
   - `src/views/admin/Dashboard.vue`：
     - 顶部 4 个统计卡片（总用户、总测评、待处理预警、今日新增）
     - 中间行：30 天测评趋势折线图（ECharts）+ 风险等级分布饼图
     - 底部行：预警状态汇总柱状图
     - 使用 ECharts 通过 `vue-echarts` 封装，Ant Design `<a-card>` 包裹每个图表
3. 路由表添加：`/admin/dashboard`
4. 验证：admin 登录→仪表盘→各图表数据与实际数据一致

**涉及文件**（新建）：

后端：
- `psycare-server/src/main/java/com/cmh/psycare/controller/AdminController.java`
- `psycare-server/src/main/java/com/cmh/psycare/service/AdminService.java`
- `psycare-server/src/main/java/com/cmh/psycare/mapper/AdminDashboardMapper.java`
- `psycare-server/src/main/resources/mapper/AdminDashboardMapper.xml`（可选）

前端：
- `src/api/admin.ts`
- `src/stores/dashboard.ts`
- `src/views/admin/Dashboard.vue`
- `src/router/index.ts`（更新）

---

### 第八步：医生推荐 + 测评历史 + 通知

**目标**：补齐居民端剩余三个模块——做完测评后看到推荐医生、查看历史趋势、收取站内通知。

**操作**：
1. 创建 `src/api/recommend.ts`：
   - `getRecommendDoctors(sessionId?, topN?)` → `List<RecommendResult>`
2. 创建 `src/api/history.ts`：`getHistory()` → `List<Assessment>`
3. 创建 `src/api/notification.ts`：`getMyNotifications()` → `List<Notification>`
4. 创建页面：
   - `src/views/recommend/DoctorRecommend.vue` — 医生卡片列表：
     - 每张卡片：姓名、科室、职称、擅长标签（Tag 展示）、评分星级、当前负载
     - 推荐理由（折叠/展开）
     - 按综合得分排序，Top 3 高亮
   - `src/views/history/AssessmentHistory.vue` — 测评历史时间线：
     - `<a-timeline>` 展示每次测评：时间、量表名、总分、风险等级标签
     - 点击可查看该次结果详情（复用 Result 组件或跳转）
   - `src/views/notification/NotificationList.vue` — 通知列表：
     - `<a-list>` 展示，已读/未读状态区分
5. 路由表添加：`/doctors`, `/history`, `/notifications`
6. 验证：做完测评→点击推荐医生→看到推荐列表；查看历史→时间线展示；通知列表可查看

**涉及文件**（新建）：
- `src/api/recommend.ts`
- `src/api/history.ts`
- `src/api/notification.ts`
- `src/views/recommend/DoctorRecommend.vue`
- `src/views/history/AssessmentHistory.vue`
- `src/views/notification/NotificationList.vue`
- `src/router/index.ts`（更新）

---

### 第九步：首页 + 样式打磨 + 错误处理 + 打包

**目标**：各角色有对应首页、全局 UI 打磨、错误边界处理、生产构建。

**操作**：
1. 创建首页：
   - `src/views/home/ResidentHome.vue` — 居民首页：
     - 欢迎语 + 用户名
     - 快捷入口卡片：心理测评、AI 咨询、医生推荐、我的历史
     - 最新通知摘要
     - 如有待处理预警，顶部提示 banner
   - `src/views/home/AdminHome.vue` — 管理员首页（或直接重定向到仪表盘）
   - `src/views/home/DoctorHome.vue` — 医生首页（待处理预警概览）
2. 全局样式打磨：
   - Ant Design Vue ConfigProvider 统一主题色（建议柔和蓝绿色，符合心理健康主题）
   - 全局 CSS 变量定义在 `src/styles/global.less` 或 `src/styles/variables.css`
   - 统一页面过渡动画
3. 错误处理：
   - 全局错误边界组件 `src/components/ErrorBoundary.vue`
   - 后端 4xx/5xx 统一 toast 提示（已在 request.ts 拦截器中做）
   - 网络断开/超时友好提示
4. 生产构建：
   - `npm run build` → `dist/` 目录
   - 配置 `vite.config.ts` 的 build 选项（输出目录、代码分割）
   - 可选：配置 nginx 将 `/api` 代理到后端，静态文件指向前端 dist
5. 整体验证：各角色登录→完整流程走通→无控制台报错→样式一致

**涉及文件**（新建）：
- `src/views/home/ResidentHome.vue`
- `src/views/home/AdminHome.vue`
- `src/views/home/DoctorHome.vue`
- `src/styles/global.less`
- `src/components/ErrorBoundary.vue`

---

## 前端目录结构总览（完成后）

```
psycare-web/src/
├── main.ts                          # 入口：注册 Vue/ADV/Router/Pinia
├── App.vue                          # 根组件：<router-view />
├── router/
│   ├── index.ts                     # 路由表（嵌套路由）
│   └── guards.ts                    # 导航守卫（JWT 鉴权 + 角色权限）
├── stores/                          # Pinia 状态管理
│   ├── auth.ts                      #   用户认证状态
│   ├── assessment.ts                #   测评流程状态
│   ├── chat.ts                      #   AI 对话状态
│   ├── alert.ts                     #   预警工单列表状态
│   └── dashboard.ts                 #   仪表盘数据状态
├── api/                             # API 封装层（每个模块一个文件）
│   ├── request.ts                   #   Axios 实例 + 拦截器
│   ├── auth.ts                      #   认证接口
│   ├── assessment.ts                #   量表 + 测评接口
│   ├── consult.ts                   #   AI 咨询 SSE 接口
│   ├── alert.ts                     #   预警工单接口
│   ├── recommend.ts                 #   医生推荐接口
│   ├── notification.ts              #   通知接口
│   ├── history.ts                   #   测评历史接口
│   └── admin.ts                     #   仪表盘统计接口
├── layouts/
│   ├── DefaultLayout.vue            # 居民端：顶部导航 + 内容区
│   └── AdminLayout.vue              # 管理端/医生端：侧边栏 + 内容区
├── views/
│   ├── auth/
│   │   ├── Login.vue
│   │   └── Register.vue
│   ├── home/
│   │   ├── ResidentHome.vue
│   │   ├── AdminHome.vue
│   │   └── DoctorHome.vue
│   ├── assessment/
│   │   ├── ScaleList.vue
│   │   ├── Questionnaire.vue
│   │   └── Result.vue
│   ├── consult/
│   │   ├── SessionStart.vue
│   │   └── AiChat.vue
│   ├── alert/
│   │   ├── AlertList.vue
│   │   └── AlertDetail.vue
│   ├── admin/
│   │   └── Dashboard.vue
│   ├── recommend/
│   │   └── DoctorRecommend.vue
│   ├── history/
│   │   └── AssessmentHistory.vue
│   └── notification/
│       └── NotificationList.vue
├── components/                      # 可复用组件
│   ├── ErrorBoundary.vue
│   ├── RiskLevelTag.vue             # 风险等级 A/B/C/D 彩色标签
│   └── PageHeader.vue               # 页面通用标题栏
├── styles/
│   ├── global.less                  # 全局样式 + CSS 变量
│   └── variables.less               # Ant Design 主题变量覆盖
└── types/                           # TypeScript 类型定义（与后端 DTO 对应）
    └── index.ts                     #   LoginResponse, AssessmentResultResponse, PageResult<T> 等
```

## 完整路由表

```
/login                    → auth/Login.vue        (公开)
/register                 → auth/Register.vue     (公开)
/                         → 按角色重定向
/home                     → home/{role}.vue       (需登录)
/scales                   → assessment/ScaleList.vue   (居民)
/scales/:id               → assessment/Questionnaire.vue (居民)
/assessment/:id/result    → assessment/Result.vue      (居民)
/ai-chat                  → consult/SessionStart.vue   (居民)
/ai-chat/:sessionId       → consult/AiChat.vue         (居民)
/doctors                  → recommend/DoctorRecommend.vue (居民)
/history                  → history/AssessmentHistory.vue (居民)
/notifications            → notification/NotificationList.vue (居民)
/admin/dashboard          → admin/Dashboard.vue        (管理员/医生)
/admin/alerts             → alert/AlertList.vue        (管理员/医生)
/admin/alerts/:id         → alert/AlertDetail.vue      (管理员/医生)
```

## 关键设计决策

1. **Axios 不用于 SSE**：AI 流式对话使用 `fetch` + `ReadableStream` 手动解析 SSE，Axios 不支持流式响应。

2. **角色路由守卫**：在 `router/guards.ts` 的 `beforeEach` 中，从 Pinia auth store 获取 `userInfo.role`，对 `/admin/*` 路由校验角色为 admin 或 doctor。

3. **危机输入处理**：前端无需特殊逻辑，后端 CrisisDetector 命中后返回固定文案（包含心理热线），SSE 流中正常展示即可。不额外弹窗，避免二次刺激。

4. **状态机展示**：预警处置按钮根据 `AlertRecord.status` 动态渲染合法下一状态，非法流转后端已拦截（400），前端做按钮禁用即可。

5. **主题色选择**：建议使用柔和蓝绿色调（`#5B8C9E` 或类似），符合心理健康领域的专业、温暖、信任感。通过 Ant Design Vue ConfigProvider 的 `theme.token.colorPrimary` 设置。

## 验证方式

每步完成后：
- 启动后端 `cd psycare-server && mvn spring-boot:run`（端口 18989）
- 启动前端 `cd psycare-web && npm run dev`（端口 5173）
- 浏览器打开 `http://localhost:5173`
- 按该步骤描述的验证路径走通完整流程
- 检查浏览器控制台无报错
- 检查网络请求正常（200/201，Token 注入正确）

最终整体验证：
- 注册居民账号 → 登录 → 做 PHQ-9 问卷 → 查看结果和风险等级
- 从结果页进入 AI 咨询 → 发送消息 → 看到流式回复
- 查看推荐医生列表
- 查看测评历史时间线
- 查看通知
- 退出登录
- 换 admin 登录 → 仪表盘统计 → 预警工单列表 → 处置工单 → 查看流转日志