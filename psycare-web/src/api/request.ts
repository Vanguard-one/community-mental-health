import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { message } from 'ant-design-vue'
import type { Result } from '@/types'

const BASE_URL = '/api'
const TIMEOUT = 30000

function injectToken(config: InternalAxiosRequestConfig) {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}

/* ==================== 提示去重 ==================== */

/** 同名提示在窗口期内只弹一次 */
const TOAST_DEDUPE_MS = 1500
const lastToastAt = new Map<string, number>()

function toastOnce(msg: string) {
  const now = Date.now()
  const prev = lastToastAt.get(msg)
  if (prev !== undefined && now - prev < TOAST_DEDUPE_MS) return
  lastToastAt.set(msg, now)
  message.error(msg)
}

/* ==================== 会话过期 ==================== */

let redirectingToLogin = false

/**
 * 会话失效：清凭证 + 跳登录。
 *
 * `redirectingToLogin` 是必需的 —— 页面进入时常常并发发起多个请求
 * （如首页同时拉历史/量表/通知），token 一旦失效会同时 401，
 * 没有这个闩就会连弹三条「登录已过期」并且重复触发跳转。
 */
function onSessionExpired(msg = '登录已过期，请重新登录') {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  if (redirectingToLogin) return
  redirectingToLogin = true
  message.error(msg)
  window.location.href = '/login'
}

/* ==================== 错误归一化 ==================== */

/**
 * 把 axios 的原始错误翻成用户能看懂的中文。
 *
 * axios 的原始 message 是英文（`timeout of 30000ms exceeded`、`Network Error`），
 * 直接弹给用户等于没提示。优先级：后端 msg > 网络态 > 状态码 > 兜底。
 */
function describeError(error: AxiosError<{ msg?: string }>): string {
  const serverMsg = error.response?.data?.msg
  if (serverMsg) return serverMsg

  if (typeof navigator !== 'undefined' && !navigator.onLine) {
    return '网络已断开，请检查网络连接后重试'
  }

  switch (error.code) {
    case 'ECONNABORTED':
    case 'ETIMEDOUT':
      return '请求超时，服务器响应较慢，请稍后重试'
    case 'ERR_NETWORK':
      return '无法连接服务器，请确认后端服务已启动'
    default:
      break
  }

  const status = error.response?.status
  if (status === 404) return '接口不存在（404），请确认后端已更新到最新版本'
  if (status && status >= 500) return `服务端异常（${status}），请稍后重试`
  return '请求失败，请稍后重试'
}

function handleError(error: AxiosError<{ msg?: string }>) {
  // HTTP 401：JwtAuthFilter 直接写入的响应
  if (error.response?.status === 401) {
    onSessionExpired()
    return Promise.reject(error)
  }
  toastOnce(describeError(error))
  return Promise.reject(error)
}

/* ==================== 实例 ==================== */

/** 标准实例：自动解包 Result<T>，业务失败直接 reject */
const instance = axios.create({ baseURL: BASE_URL, timeout: TIMEOUT })
instance.interceptors.request.use(injectToken)
instance.interceptors.response.use((response) => {
  const res = response.data as Result<unknown>
  // 业务 401：GlobalExceptionHandler 走的是 HTTP 200 + body.code，需要单独识别，
  // 否则表现为「提示已过期但不跳登录」，用户停在打不开的页面上反复重试。
  if (res.code === 401) {
    onSessionExpired(res.msg || '登录已过期，请重新登录')
    return Promise.reject(new Error(res.msg || '登录已过期'))
  }
  if (res.code !== 200) {
    toastOnce(res.msg || '请求失败')
    return Promise.reject(new Error(res.msg || '请求失败'))
  }
  return res.data as never
}, handleError)

/**
 * 裸响应实例：只做鉴权与错误处理，**不解包 Result**，原样返回响应体。
 *
 * 用途：历史上少数接口（如 `/ai-consult/open`）返回未包装的裸对象，
 * 走标准实例会被 `code !== 200` 拦下。调用方拿到原始响应体后自行归一化。
 */
const rawInstance = axios.create({ baseURL: BASE_URL, timeout: TIMEOUT })
rawInstance.interceptors.request.use(injectToken)
rawInstance.interceptors.response.use((response) => response.data, handleError)

/** 类型化请求方法（拦截器已解包，直接返回 data） */
export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return instance.get(url, { params }) as Promise<T>
}

export function post<T>(url: string, data?: unknown): Promise<T> {
  return instance.post(url, data) as Promise<T>
}

export function put<T>(url: string, data?: unknown): Promise<T> {
  return instance.put(url, data) as Promise<T>
}

/** DELETE 也允许带 body：Spring 的 @RequestBody 在部分场景下需要它 */
export function del<T>(url: string, data?: unknown): Promise<T> {
  return instance.delete(url, { data }) as Promise<T>
}

/** 原样返回响应体的请求方法 */
export function postRaw<T>(url: string, data?: unknown): Promise<T> {
  return rawInstance.post(url, data) as Promise<T>
}

/**
 * 带文件的 multipart 上传。
 *
 * 两个必须显式处理的地方：
 * 1. **不要手动设 `Content-Type`**。浏览器需要自己给 FormData 加上
 *    `multipart/form-data; boundary=----xxxx` 的 boundary 段；一旦手写成
 *    `'multipart/form-data'`（没有 boundary），后端解析出的文件恒为空，
 *    报的是「请选择要上传的图片」——看着像前端没选文件，实际是头写错了。
 *    所以这里传 `undefined` 让 axios 自行决定。
 * 2. **超时单独放宽**。图片上传比普通 JSON 慢得多（本地开发还好，走内网/公网时
 *    几 MB 的文件超过默认 30s 很常见）。沿用 30s 会让大图在弱网下必失败。
 *
 * 入参 `field` 是后端 `@RequestParam("file")` 里的那个名字，默认 `file`。
 */
export function postForm<T>(
  url: string,
  form: FormData,
  field = 'file',
  file?: File,
): Promise<T> {
  if (file && !form.has(field)) {
    form.append(field, file)
  }
  return instance.post(url, form, {
    timeout: 120000,
    headers: { 'Content-Type': undefined },
  }) as Promise<T>
}

/**
 * 归一化解包：后端已包 `Result` 则取 `data`，未包装则原样返回。
 * 用于契约正在收敛的过渡期接口。
 */
export function unwrapResult<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'data' in body && 'code' in body) {
    return (body as Result<T>).data
  }
  return body as T
}

export default instance
