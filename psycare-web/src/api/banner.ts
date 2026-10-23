import { del, get, post, postForm, put } from './request'
import type { Banner, BannerRequest } from '@/types'

/**
 * 首页轮播 banner 接口。
 *
 * 权限分两档（后端逐个方法断言）：
 * - `getActiveBanners`：**登录即可**。它是首页的运营展示位，居民与医生都要看。
 * - 其余全部：**仅管理员**。前端把菜单藏起来是「不给入口」，不是安全边界。
 */

/** 首页轮播数据：仅「启用中 + 在时间窗口内」的，已按 sort 升序 */
export function getActiveBanners(): Promise<Banner[]> {
  return get<Banner[]>('/banner/active')
}

/* ==================== 管理端 ==================== */

/** 全量列表（含停用/过期的）。管理端必须能看到全部，否则停用后就找不回那条记录 */
export function listBanners(): Promise<Banner[]> {
  return get<Banner[]>('/banner/list')
}

export function createBanner(req: BannerRequest): Promise<Banner> {
  return post<Banner>('/banner', req)
}

export function updateBanner(id: number, req: BannerRequest): Promise<Banner> {
  return put<Banner>(`/banner/${id}`, req)
}

export function deleteBanner(id: number): Promise<void> {
  return del<void>(`/banner/${id}`)
}

/** 启用/停用切换（不需传目标值，后端按当前状态取反） */
export function toggleBannerStatus(id: number): Promise<Banner> {
  return put<Banner>(`/banner/${id}/status`)
}

/** 上移/下移。返回**重排后的完整列表**，调用方直接整体替换即可 */
export function moveBanner(id: number, direction: 'up' | 'down'): Promise<Banner[]> {
  return put<Banner[]>(`/banner/${id}/move?direction=${direction}`)
}

/**
 * 上传 banner 图片，返回可直接写入 `imageUrl` 的地址。
 *
 * 返回值的形态由后端存储后端决定（本地相对路径 / 对象存储绝对地址），
 * 前端不需要区分，原样存库、原样放进 `<img src>` 即可。
 *
 * 走 `postForm`（multipart），**不是** JSON —— 这是本项目第一个文件上传接口，
 * request.ts 里为此新增了 postForm。
 */
export function uploadBannerImage(file: File): Promise<{ url: string; size: number }> {
  const form = new FormData()
  return postForm<{ url: string; size: number }>('/upload/banner', form, 'file', file)
}
