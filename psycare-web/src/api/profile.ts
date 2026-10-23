import { get, postForm, put } from './request'
import type { ChangePasswordPayload, ProfileUpdatePayload, ProfileVO } from '@/types'

/**
 * 个人资料接口。三端（居民 / 医生 / 管理员）共用同一组端点。
 *
 * <h3>为什么这里没有 userId 参数</h3>
 * <p>后端一律以 token 反查当前登录者，**入参里不存在 id**。
 * 前端也不提供这个能力 —— 一旦多想一层「支持传 id」，就等于在
 * 客户端留了一个可以改别人资料的入口，而服务端若不接这个参数，
 * 前端多写的那层就是纯噪音。
 */

/** 当前登录者的资料 */
export function getMyProfile(): Promise<ProfileVO> {
  return get<ProfileVO>('/profile/me')
}

/** 保存资料。返回保存后的完整资料，调用方直接替换本地状态即可 */
export function updateMyProfile(payload: ProfileUpdatePayload): Promise<ProfileVO> {
  return put<ProfileVO>('/profile/me', payload)
}

/**
 * 修改密码。需要旧密码。
 *
 * 成功后**不会**下发新 token —— token 有效期与密码无关（见后端 ProfileService 注释）。
 * 调用方应主动登出并要求用新密码重新登录，否则「改完密码仍用旧密码登录也成功」的
 * 错觉会让人以为没改成功。
 */
export function changeMyPassword(payload: ChangePasswordPayload): Promise<void> {
  return put<void>('/profile/password', payload)
}

/**
 * 上传头像，返回可直接写入 `avatarUrl` 的地址。
 *
 * 地址形态由后端存储后端决定（本地相对路径 / 对象存储绝对地址），前端不区分。
 * 「默认头像」不经过本接口 —— 清空 `avatarUrl` 即回落成 RoleAvatar 内置的角色图标。
 *
 * 上传与保存分开两步：选完图先拿到 URL 但**不落地**，用户点保存才写进资料。
 * 这样误点一次不会直接覆盖掉旧头像。
 */
export function uploadAvatar(file: File): Promise<{ url: string; size: number }> {
  const form = new FormData()
  return postForm<{ url: string; size: number }>('/upload/avatar', form, 'file', file)
}
