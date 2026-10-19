import { get, post } from './request'
import type {
  ChatMessage,
  ChatPeer,
  ChatSession,
  ChatUnread,
  DepartmentGroup,
} from '@/types'

/**
 * 医患一对一聊天。医生端与居民端共用这一套接口 ——
 * 「我是哪一侧」由后端从 JWT 判断，前端不需要传身份参数。
 */

/** 我的会话列表（医生：我接诊的；居民：我的） */
export function listSessions(): Promise<ChatSession[]> {
  return get<ChatSession[]>('/chat/sessions')
}

/**
 * 居民视角：可聊天的医生列表（已通过资质审核者）。
 *
 * 医生端的对端列表走 `/doctor/resident/page`（那边要带患者档案），
 * 所以这个接口仅居民可用。
 */
export function listChatDoctors(): Promise<ChatPeer[]> {
  return get<ChatPeer[]>('/chat/doctors')
}

/**
 * 「医生咨询」页：按科室分组的**全部**可咨询医生。
 *
 * 与 {@link listChatDoctors} 的区别是「可聊的人」vs「聊过的人」：
 * 后者用于「我的消息」（没聊过的不该占位），前者用于「医生咨询」
 * （新居民必须从这里发起第一句话，否则他永远找不到入口）。
 */
export function listDoctorCatalog(): Promise<DepartmentGroup[]> {
  return get<DepartmentGroup[]>('/chat/doctor-catalog')
}

/**
 * 未读汇总：有未读消息的会话数 + 未读消息条数。
 *
 * 医生端侧栏与居民端顶部导航的角标都读这一个接口 —— 红点的数字因此只有一个来源，
 * 不可能出现「一边算了另一边没算」。
 *
 * 仅 DOCTOR / RESIDENT：管理员调它会拿到 403，调用方（inbox store）要接住这个失败。
 */
export function getUnread(): Promise<ChatUnread> {
  return get<ChatUnread>('/chat/unread')
}

/**
 * 把某个会话里**对方发来的**消息标成已读。
 *
 * 刻意是独立的 POST，而不是「拉消息顺带标」：拉消息是幂等读操作，
 * 让 GET 带写副作用，轮询和重试都会把未读数悄悄清掉。
 *
 * @returns 标记后的未读汇总 —— 调用方直接拿它更新红点，不必再请求一次
 */
export function markSessionRead(sessionId: number): Promise<ChatUnread> {
  return post<ChatUnread>(`/chat/sessions/${sessionId}/read`)
}

/**
 * 打开与某人的会话。已有进行中的会直接复用，不会重复建。
 *
 * @param targetId 医生端传 residentId，居民端传 doctorId
 */
export function openSession(targetId: number): Promise<ChatSession> {
  return post<ChatSession>('/chat/sessions', { targetId })
}

/** 会话详情（刷新后还原标题栏） */
export function getSession(sessionId: number): Promise<ChatSession> {
  return get<ChatSession>(`/chat/sessions/${sessionId}`)
}

/**
 * 会话消息，时间正序。
 *
 * @param sinceId 增量拉取，只返回 id 比它大的消息（轮询用）；不传取全量
 */
export function listMessages(sessionId: number, sinceId?: number): Promise<ChatMessage[]> {
  return get<ChatMessage[]>(
    `/chat/sessions/${sessionId}/messages`,
    sinceId ? { sinceId } : undefined,
  )
}

/** 发送消息，返回落库后的实体（前端据此上屏，不用本地伪造 id） */
export function sendMessage(sessionId: number, content: string): Promise<ChatMessage> {
  return post<ChatMessage>(`/chat/sessions/${sessionId}/messages`, { content })
}
