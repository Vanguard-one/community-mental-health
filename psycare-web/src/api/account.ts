import { del, get, post, put } from './request'
import type {
  AccountQuery,
  AccountVO,
  AuditPayload,
  DoctorAuditStatus,
  PageResult,
  UserType,
} from '@/types'

/**
 * 管理端账号治理接口（全部限 ADMIN）。
 *
 * 路径里的 {userType} 只是「声明」——后端会按 id 回查账号真实角色并校验一致，
 * 传错类型的路径不会造成越权，但也拿不到数据。前端一律用列表行自带的 userType，
 * 不要从筛选条件里猜。
 */

/** 账号分页。userType 空 = 三端汇总 */
export function getAccountPage(query: AccountQuery): Promise<PageResult<AccountVO>> {
  return get<PageResult<AccountVO>>('/admin/account/page', { ...query })
}

/** 待审核医生数（菜单角标 / 审核 tab 提醒） */
export function getPendingAuditCount(): Promise<number> {
  return get<number>('/admin/account/pending-audit-count')
}

/** 启用(1) / 禁用(0) */
export function updateAccountStatus(
  userType: UserType,
  id: number,
  status: number,
): Promise<void> {
  return put<void>(`/admin/account/${userType}/${id}/status`, { status })
}

/** 逻辑删除：保留行与审计，业务数据不级联删除 */
export function deleteAccount(userType: UserType, id: number): Promise<void> {
  return del<void>(`/admin/account/${userType}/${id}`)
}

/**
 * 重置密码。
 * newPassword 留空则由后端生成随机强密码；
 * 返回明文供管理员转达（库中只有 BCrypt 哈希，无法反查）。
 */
export function resetAccountPassword(
  userType: UserType,
  id: number,
  newPassword?: string,
): Promise<{ password: string }> {
  return post<{ password: string }>(`/admin/account/${userType}/${id}/reset-password`, {
    newPassword,
  })
}

/**
 * 医生资质审核。
 * auditRemark 是可选文字意见 —— 当前审核界面**不采集**（只提交通过/驳回结论），
 * 保留该参数是为了后端契约不变、将来若要恢复填写无需改后端。
 * 传 undefined 时 JSON 不会带上该键，后端按「未填写」处理。
 */
export function auditDoctor(
  id: number,
  auditStatus: Extract<DoctorAuditStatus, 'APPROVED' | 'REJECTED'>,
  auditRemark?: string,
): Promise<void> {
  const payload: AuditPayload = { auditStatus, auditRemark }
  return put<void>(`/admin/account/DOCTOR/${id}/audit`, payload)
}
