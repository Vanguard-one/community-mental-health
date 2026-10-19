import { post } from './request'
import type {
  DoctorRegisterPayload,
  LoginRequest,
  LoginResponse,
  RegisterResponse,
  ResidentRegisterPayload,
} from '@/types'

/** 登录：返回 JWT 与用户信息。带 userType 时后端会校验身份一致性 */
export function login(data: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/login', data)
}

/**
 * 居民注册：写 sys_user + user，注册后即可登录。
 *
 * 与医生注册拆成两个接口而不是共用一个：两者入参差异大
 * （医生要执业证书号，居民要性别/出生年等），共用只能退回 Map 接收，
 * 而 Map 拿不到 @Valid，脏数据全靠 Service 兜。
 */
export function registerResident(data: ResidentRegisterPayload): Promise<RegisterResponse> {
  return post<RegisterResponse>('/auth/register/resident', data)
}

/** 医生注册：注册后 auditStatus=PENDING，需管理员审核通过后才能登录 */
export function registerDoctor(data: DoctorRegisterPayload): Promise<RegisterResponse> {
  return post<RegisterResponse>('/auth/register/doctor', data)
}

/** 登出：后端将 token 加入 Redis 黑名单 */
export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}
