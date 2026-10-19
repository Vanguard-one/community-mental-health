// ===== 后端统一响应 =====
export interface Result<T> {
  code: number
  msg: string
  data: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// ===== 枚举 =====
export type RiskLevel = 'A' | 'B' | 'C' | 'D'

export type AlertType = 'THRESHOLD' | 'TREND' | 'CRISIS'

export type AlertStatus =
  | 'PENDING'
  | 'REJECTED'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'FOLLOWED'
  | 'CLOSED'

export type ConsultType = 'PHONE' | 'DOCTOR' | 'AI'

// 风险等级中文描述（与后端 RiskLevel.desc 一致）
export const RISK_LEVEL_DESC: Record<RiskLevel, string> = {
  A: '低风险',
  B: '中风险',
  C: '高风险',
  D: '危机',
}

// 预警类型中文描述
export const ALERT_TYPE_DESC: Record<AlertType, string> = {
  THRESHOLD: '阈值预警',
  TREND: '趋势预警',
  CRISIS: '危机预警',
}

// 预警状态中文描述
export const ALERT_STATUS_DESC: Record<AlertStatus, string> = {
  PENDING: '待确认',
  REJECTED: '已驳回',
  CONFIRMED: '已确认',
  PROCESSING: '处置中',
  FOLLOWED: '已回访',
  CLOSED: '已关闭',
}

// ===== 认证 =====
/** 账号类型。角色拆分后由 sys_user.role 区分，三张角色表挂在同一 id 下 */
export type UserType = 'RESIDENT' | 'DOCTOR' | 'ADMIN'

export const USER_TYPE_DESC: Record<UserType, string> = {
  RESIDENT: '居民',
  DOCTOR: '医生',
  ADMIN: '管理员',
}

export interface LoginRequest {
  username: string
  password: string
  /** 可选：登录页身份切换用。不传则后端按账号实际角色自行路由 */
  userType?: UserType
}

export interface LoginResponse {
  token: string
  userId: number
  username: string
  realName: string
  role: string
  /** 角色差异化信息：医生 department/title/auditStatus，管理员 adminLevel */
  extra?: {
    auditStatus?: DoctorAuditStatus
    department?: string
    title?: string
    adminLevel?: AdminLevel
  } | null
}

/** 医生资质审核状态。PENDING 时后端会直接拒绝登录 */
export type DoctorAuditStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export const AUDIT_STATUS_DESC: Record<DoctorAuditStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export type AdminLevel = 'SUPER' | 'NORMAL'

// ===== 注册分流 =====
/** 居民注册入参。差异字段全部可选 —— 存量居民档案为空，强校验会挡住老用户 */
export interface ResidentRegisterPayload {
  username: string
  password: string
  confirmPassword: string
  realName: string
  phone?: string
  gender?: 'M' | 'F' | 'U' | ''
  birthYear?: number | null
  occupation?: string
  emergencyContact?: string
}

/** 医生注册入参。注册后 auditStatus = PENDING，需管理员审核后才能登录 */
export interface DoctorRegisterPayload {
  username: string
  password: string
  confirmPassword: string
  realName: string
  phone?: string
  licenseNo: string
  department?: string
  title?: string
  expertiseTags?: string
}

export interface RegisterResponse {
  userId: number
  userType: UserType
  auditStatus?: DoctorAuditStatus
  tip?: string
}


// ===== 量表 =====
export interface Scale {
  id: number
  code: string
  name: string
  dimension: string
  maxScore: number
  description: string
  status: number
  /** 题目数量。后端列表接口聚合填充，旧版本可能不返回 */
  itemCount?: number
  createdAt: string
}

export interface ScaleOption {
  id: number
  itemId: number
  content: string
  score: number
  sort: number
}

export interface ScaleItemView {
  id: number
  itemNo: number
  content: string
  options: ScaleOption[]
}

// GET /scale/{id}/detail 的返回结构
export interface ScaleDetail {
  scale: Scale
  items: ScaleItemView[]
}

// ===== 测评 =====
export interface AnswerItem {
  itemId: number
  optionId: number
}

export interface AssessmentSubmitRequest {
  scaleId: number
  answers: AnswerItem[]
}

export interface AssessmentResultResponse {
  assessmentId: number
  totalScore: number
  rawLevel: string
  riskLevel: RiskLevel
  riskLevelDesc: string
  crisis: boolean
  alertTriggered: boolean
  advice: string
}

export interface Assessment {
  id: number
  residentId: number
  scaleId: number
  totalScore: number
  rawLevel: string
  riskLevel: RiskLevel
  crisisFlag: number
  answerJson: string
  createdAt: string
}

// ===== 预警工单 =====
export interface AlertRecord {
  id: number
  residentId: number
  assessmentId: number
  alertType: AlertType
  alertLevel: RiskLevel
  status: AlertStatus
  /** 处理人。**管理员重新开启后会被清空**（退回「待确认」= 还没人处置），所以可为 null */
  handlerId: number | null
  /** 处理备注，同上可被清空 */
  handleNote: string | null
  createdAt: string
  updatedAt: string
}

export interface AlertStatusLog {
  id: number
  alertId: number
  fromStatus: string
  toStatus: string
  operatorId: number
  remark: string
  createdAt: string
}

/** 状态维度计数（去标识化聚合，仅计数不含个人信息） */
export interface StatusCount {
  status: AlertStatus
  count: number
}

// ===== 管理端驾驶舱（全部为聚合数，无个人标识字段） =====
export interface DashboardStats {
  /** 服务居民数，仅统计 RESIDENT 角色账号 */
  userCount: number
  /** 历史测评总量 */
  assessmentCount: number
  /** 待确认（PENDING）预警工单数 */
  pendingAlertCount: number
  /** 今日新增测评数 */
  todayNew: number
}

export interface TrendPoint {
  /** yyyy-MM-dd */
  date: string
  count: number
  /** 当日平均总分；当日无数据为 null */
  avgScore: number | null
}

export interface RiskCount {
  riskLevel: RiskLevel
  count: number
}

/**
 * 工单状态机的合法流转表 —— 前端唯一副本。
 * 必须与后端 AlertService.TRANSITIONS 保持一致；
 * 后端对非法流转返回 400，前端按钮仅作预防性禁用。
 */
export const ALERT_TRANSITIONS: Record<AlertStatus, AlertStatus[]> = {
  PENDING: ['CONFIRMED', 'REJECTED'],
  CONFIRMED: ['PROCESSING'],
  PROCESSING: ['FOLLOWED'],
  FOLLOWED: ['CLOSED'],
  REJECTED: [],
  CLOSED: [],
}

/** 流转动作按钮文案 */
export const ALERT_TRANSITION_LABEL: Record<string, string> = {
  CONFIRMED: '确认工单',
  REJECTED: '驳回（误报）',
  PROCESSING: '开始处置',
  FOLLOWED: '标记已回访',
  CLOSED: '结案',
}

/** 医生端列表的状态分组（后端 /alert/page 的 status 支持逗号分隔多值） */
export interface AlertGroup {
  key: string
  label: string
  /** CSV，空串表示不过滤 */
  statuses: string
}

export const ALERT_GROUPS: AlertGroup[] = [
  { key: 'ALL', label: '全部', statuses: '' },
  { key: 'PENDING', label: '待确认', statuses: 'PENDING' },
  { key: 'DOING', label: '处理中', statuses: 'CONFIRMED,PROCESSING' },
  { key: 'DONE', label: '已办结', statuses: 'FOLLOWED,CLOSED,REJECTED' },
]

// ===== AI 咨询 =====
export interface ChatMessage {
  role: 'user' | 'ai' | 'system'
  content: string
  time: string
}

// ===== 医生推荐 =====
export interface RecommendResult {
  doctorId: number
  userId: number
  doctorName: string
  department: string
  title: string
  expertiseTags: string
  rating: number
  currentLoad: number
  score: number
  reason: string
}

// ===== 通知 =====
export interface Notification {
  id: number
  userId: number
  title: string
  content: string
  type: string
  readFlag: number
  createdAt: string
}

// ===== 首页轮播 banner =====
/**
 * 运营物料。与 `Notification` 是**两张表**：那个是按用户一对一推送的站内信，
 * 这个是面向全体访客的轮播位（见后端 `sql/v9_banner_ddl.sql` 的论证）。
 *
 * `imageUrl` 是**相对路径**（`/api/uploads/banner/...`），需由前端拼上请求
 * baseURL 才能访问；存绝对 URL 会在换域名时全部失效。
 *
 * `startAt` / `endAt` 均可为 `null`，语义是开放区间：都为空 = 永久展示。
 */
export interface Banner {
  id: number
  title: string
  imageUrl: string
  /** 跳转链接；为空表示不可点击（纯展示图） */
  linkUrl: string | null
  sort: number
  /** 1 启用 0 停用 */
  status: number
  /** 展示开始时间；null = 立即生效 */
  startAt: string | null
  /** 展示结束时间；null = 永不过期 */
  endAt: string | null
  createdAt: string
  updatedAt: string
}

/** banner 的新增/编辑请求体（不含 id，编辑走路径参数） */
export interface BannerRequest {
  title: string
  imageUrl: string
  linkUrl?: string | null
  sort?: number
  status?: number
  startAt?: string | null
  endAt?: string | null
}

// ===== 账号治理（管理端） =====
/**
 * 三端统一的账号视图。
 * 刻意做成一个宽接口而非三个：管理端「全部」tab 需要同构展示，
 * 按 userType 决定渲染哪些列。**后端保证不含 password**。
 */
export interface AccountVO {
  id: number
  userType: UserType
  username: string
  realName: string
  /** 已脱敏，如 138****0001 */
  phone: string
  status: number
  createdAt: string
  // 医生专有
  auditStatus?: DoctorAuditStatus
  auditRemark?: string
  department?: string
  title?: string
  expertiseTags?: string
  licenseNo?: string
  // 管理员专有
  adminLevel?: AdminLevel
  // 居民专有
  gender?: string
  birthYear?: number
}

export interface AccountQuery {
  userType?: UserType | ''
  status?: number | ''
  auditStatus?: DoctorAuditStatus | ''
  keyword?: string
  current?: number
  size?: number
}

export interface AuditPayload {
  auditStatus: 'APPROVED' | 'REJECTED'
  auditRemark?: string
}

// ===== 评测做题记录 =====
/** 单题作答。题干与选项文案由 scale_item / scale_option join 得到 */
export interface AssessmentAnswerItem {
  itemNo: number
  question: string
  answer: string
  /** 该题得分。选项行缺失时后端 COALESCE 也可能取不到值，故允许 null */
  score: number | null
  /** 该题是否危机项 */
  crisisFlag: number
}

/** 一次测评的完整做题记录（医生端 / 管理端展示） */
export interface AssessmentRecordVO {
  id: number
  scaleId: number
  scaleCode: string
  scaleName: string
  totalScore: number
  rawLevel: string
  riskLevel: RiskLevel
  crisisFlag: number
  completedAt: string
  answers: AssessmentAnswerItem[]
}

/** 历史测评摘要（工单右栏纵向对比用） */
export interface AssessmentHistoryItem {
  id: number
  scaleCode: string
  scaleName: string
  totalScore: number
  riskLevel: RiskLevel
  completedAt: string
}

/**
 * 工单视角的做题记录。
 * 工单无关联测评时（趋势类）record 为 null，history 仍有值。
 */
export interface AlertAssessmentVO {
  alertId: number
  residentId: number
  residentName: string
  assessmentId: number | null
  record: AssessmentRecordVO | null
  history: AssessmentHistoryItem[]
}

// ==================== 医生端「用户信息」与医患聊天 ====================

/**
 * 医生端「用户信息」列表项（对应后端 ResidentInfoVO）。
 * 姓名与手机号后端已脱敏，前端直接展示即可，不要再做二次处理。
 */
export interface ResidentInfo {
  id: number
  username: string
  /** 已脱敏，如 张* */
  realName: string
  /** 已脱敏，如 138****0001 */
  phone: string
  gender: string | null
  birthYear: number | null
  /** 后端按 birthYear 推算 */
  age: number | null
  occupation: string | null
  assessmentCount: number
  lastAssessmentAt: string | null
  /** 无测评记录时为 null */
  lastRiskLevel: RiskLevel | null
  /** 危机项阳性次数 */
  crisisCount: number
  /** 当前医生与该居民**已存在**的会话 id，没有则为 null */
  sessionId: number | null
}

/**
 * 聊天会话（医生端与居民端共用）。
 * peerName / peerDesc 由后端按当前登录角色填好，前端不做角色分支。
 */
export interface ChatSession {
  id: number
  residentId: number
  doctorId: number
  /** 对端展示名：医生视角是居民（脱敏），居民视角是医生（真实姓名） */
  peerName: string
  /** 对端补充信息：居民视角为医生职称，医生视角为 null */
  peerDesc: string | null
  /**
   * 对端科室：居民视角为医生所在科室，医生视角为 null。
   *
   * 下面三个 peer* 字段与 peerName 走同一个视角分支，由后端按角色填好，
   * 前端不做角色判断。它们存在的意义是：**聊天页拿到会话就能渲染标题栏**，
   * 不必再拉一次带聚合子查询的医生目录。
   */
  peerDepartment: string | null
  /** 对端专长标签（逗号分隔）：居民视角为医生擅长方向 */
  peerExpertise: string | null
  /** 对端评分：居民视角为医生历史评分 */
  peerRating: number | null
  /** OPEN / CLOSED */
  status: string
  createdAt: string
  /** 最近一条消息预览（超 60 字后端已截断） */
  lastMessage: string | null
  lastMessageAt: string | null
  messageCount: number
  /** 最近一条消息的发送者：RESIDENT / DOCTOR / AI；无消息为 null */
  lastSender: string | null
  /**
   * **对端发来、我还没读**的条数。
   *
   * 与 `awaitingMyReply` 是两个不同的判断，别混用：
   * - `unreadCount > 0` —— 有我没看过的消息（红点）；我点开看过就归零；
   * - `awaitingMyReply` —— 最后一条是对方发的，即「轮到我说话了」（待回复标签）；
   *   我读过了但还没回，它依然为真。
   *
   * 分开的理由：红点表达「有新东西」，待回复表达「有债没还」。
   * 用后者当红点会导致「读过了却一直亮着」，用户为了灭掉它只能回一句废话。
   */
  unreadCount: number
  /** 最近一条消息是不是对方发的 —— 即「等着我回」 */
  awaitingMyReply: boolean
}

/**
 * 未读汇总 —— 两端导航红点的**唯一**数字来源。
 *
 * 服务端一次 COUNT 给出，前端不自己数：医生端读 `/chat/sessions`、
 * 居民端读 `/chat/doctors`，两个列表的数据源本来就不同，
 * 各自统计迟早会漂成两套口径（见后端 `ChatUnreadVO` 的说明）。
 */
export interface ChatUnread {
  /** 有未读消息的会话数 —— 导航角标显示它 */
  sessionCount: number
  /** 未读消息总条数 —— 列表文案用 */
  messageCount: number
}

/**
 * 居民端「我的消息」的选人列表项：可聊天的医生 + 我与他的会话状态。
 *
 * 与医生端的 ResidentInfo（居民档案）互为镜像。
 */
export interface ChatPeer {
  /** 医生对应的 userId，发给 openSession 作为 targetId */
  doctorId: number
  doctorName: string
  doctorUsername: string
  department: string | null
  title: string | null
  /** 专长标签，逗号分隔 */
  expertiseTags: string | null
  rating: number | null
  currentLoad: number | null
  /** 与该医生的进行中会话 id；从未聊过为 null */
  sessionId: number | null
  lastMessage: string | null
  lastMessageAt: string | null
  messageCount: number | null
  /** 该医生发来、我还没读的条数；从未聊过为 0 */
  unreadCount: number
}

/** 聊天消息 */
export interface ChatMessage {
  id: number
  sessionId: number
  /** RESIDENT / DOCTOR / AI */
  sender: string
  senderId: number | null
  content: string
  crisisFlag: number
  createdAt: string
  /**
   * 接收方读这条消息的时间；`null` = 还没读。
   *
   * 只有**对端**会写它（服务端按 `sender <> 我` 批量置值），
   * 所以我自己发的消息才有已读/未读可言；对方发来的消息这一列永远是 null，
   * 界面上不能拿它给对方的气泡标「未读」—— 那不是「对方没读」，是「没人会去读」。
   */
  readAt: string | null
}

/**
 * 按科室分组后的医生目录（「医生咨询」页的一级列表）。
 *
 * 分组在服务端完成：需要的是「分组 + 组内完整对象列表」，
 * SQL 只能给行级结果，前端拿到还要自己分一次 ——
 * 那么分组规则就有两份，迟早会漂移。
 */
export interface DepartmentGroup {
  /** 科室名，如「心理科」；医生未填科室时为「其他」 */
  department: string
  doctorCount: number
  doctors: ChatPeer[]
}

/* ==================== 知识库（RAG 索引层） ==================== */

/**
 * 文档索引状态。
 *
 * `PENDING` 只在「刚建行、还没开始索引」的瞬间出现；
 * `INDEXING` 正常应长期为 0，不为 0 说明有任务卡住（概览页会把它标出来）。
 * `FAILED` **可以直接重建** —— 后端把 FAILED 当 PENDING 处理，不需要人工改库。
 */
export type KbStatus = 'PENDING' | 'INDEXING' | 'READY' | 'FAILED'

/** 知识库文档（对应后端 `kb_document`） */
export interface KbDocument {
  id: number
  title: string
  /** SYNTHETIC（AI 合成，仅验链路）/ AUTHORITATIVE（权威）/ UPLOAD（人工上传） */
  sourceType: string
  sourcePath: string | null
  publishYear: number | null
  status: KbStatus
  /** 子块数 = 该文档在 Milvus 中的向量条数 */
  chunkCount: number
  /** 建索引时用的向量模型；换过模型后与当前配置不一致即提示需要重建 */
  embedModel: string | null
  dim: number | null
  errorMsg: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 分块列表项（**不含全文**，只有 preview） */
export interface KbChunkItem {
  id: number
  parentId: number | null
  chunkType: 'PARENT' | 'CHILD'
  ordinal: number
  section: string | null
  charCount: number | null
  tokenCount: number | null
  contentHash: string | null
  preview: string
}

/**
 * 知识库概览。
 *
 * MySQL 侧与 Milvus 侧的数字**分开给**：合成一个总数就再也回答不了
 * 「哪一边少了数据」，而那正是索引不一致时唯一需要知道的事。
 */
export interface KnowledgeOverview {
  docCount: number
  readyCount: number
  failedCount: number
  indexingCount: number
  /** MySQL 侧子块总数（不含父块） */
  childChunkCount: number
  /** Milvus 侧向量行数；-1 = 不可用 */
  milvusRows: number
  milvusEnabled: boolean
  milvusOk: boolean
  milvusState: string
  embedModel: string
  dim: number
}

/** 切分预览里的单个样本 */
export interface KbChunkSample {
  type: 'PARENT' | 'CHILD'
  ordinal: number
  parentOrdinal: number
  section: string
  charCount: number
  tokenCount: number
  preview: string
}

/** 切分预览结果（零成本：不调模型、不入库） */
export interface KnowledgePreview {
  parentCount: number
  childCount: number
  totalChars: number
  totalTokens: number
  normalizedChars: number
  samples: KbChunkSample[]
}

/** 建档 / 导入 / 预览共用的请求体 */
export interface KnowledgeRequest {
  title?: string
  content?: string
  /** 决定解析方式：`.jsonl` 按语料切多篇，其余整篇作为一篇 */
  fileName?: string
  sourceType?: string
  publishYear?: number
  childSize?: number
  parentSize?: number
  overlap?: number
}

/** 一次索引（或导入）的结果 */
export interface KnowledgeIndexReport {
  docId: number
  status: string
  parentCount: number
  childCount: number
  milvusRows: number
  failedVectors: number
  milvusOk: boolean
  message: string | null
  details?: KnowledgeIndexReport[]
}
