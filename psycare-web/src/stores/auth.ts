import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  login as loginApi,
  logout as logoutApi,
  registerDoctor as registerDoctorApi,
  registerResident as registerResidentApi,
} from '@/api/auth'
import type {
  DoctorRegisterPayload,
  LoginResponse,
  ResidentRegisterPayload,
  UserType,
} from '@/types'
import { useChatStore } from './chat'
import { useInboxStore } from './inbox'

function readUserInfo(): LoginResponse | null {
  try {
    const raw = localStorage.getItem('userInfo')
    return raw ? (JSON.parse(raw) as LoginResponse) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<LoginResponse | null>(readUserInfo())

  const isLoggedIn = computed(() => !!token.value)
  const role = computed(() => userInfo.value?.role || '')
  const realName = computed(() => userInfo.value?.realName || userInfo.value?.username || '')
  const userId = computed(() => userInfo.value?.userId ?? null)
  /** 角色差异化信息（医生科室、管理员分级等），登录时由后端按角色表带出 */
  const extra = computed(() => userInfo.value?.extra || null)

  /**
   * 登录。
   * userType 可选：登录页做了身份切换就带上，后端会校验身份与账号实际角色是否一致，
   * 避免「拿居民账号走医生入口」这类误导性报错。不带则后端自行路由。
   */
  async function login(username: string, password: string, userType?: UserType) {
    const res = await loginApi({ username, password, userType })
    token.value = res.token
    userInfo.value = res
    localStorage.setItem('token', res.token)
    localStorage.setItem('userInfo', JSON.stringify(res))
    return res
  }

  /** 居民注册：注册后即可登录 */
  async function registerResident(payload: ResidentRegisterPayload) {
    return registerResidentApi(payload)
  }

  /** 医生注册：返回 auditStatus=PENDING，需管理员审核后才能登录 */
  async function registerDoctor(payload: DoctorRegisterPayload) {
    return registerDoctorApi(payload)
  }

  async function logout() {
    try {
      await logoutApi()
    } finally {
      token.value = ''
      userInfo.value = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      /*
       * 聊天状态必须跟着一起清。
       *
       * chat store 是全局单例，而 AI 问诊页**不再在离开时 reset**
       * （「历史会话」功能取消后，聊天框要一直显示这段对话）。
       * 少了这一句，换账号登录时新用户会先看到上一个人的倾诉内容。
       */
      useChatStore().reset()
      /*
       * 未读红点同样要清。
       *
       * inbox store 是全局单例，数字只在布局挂载后才会被下一次轮询覆盖 ——
       * 而新账号登录进来时布局可能已经挂载过了（同一次会话里换账号），
       * 少了这一句就会先看到上一个人的未读数。
       */
      useInboxStore().reset()
    }
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    role,
    realName,
    userId,
    extra,
    login,
    registerResident,
    registerDoctor,
    logout,
  }
})
