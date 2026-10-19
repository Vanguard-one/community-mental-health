import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getUnread, markSessionRead } from '@/api/chat'
import { useAuthStore } from './auth'
import type { ChatUnread } from '@/types'

/**
 * 未读收件箱 —— 医生端侧栏与居民端顶部导航红点的**唯一**数据源。
 *
 * <h3>为什么要单独一个 store</h3>
 *
 * 在它之前，「有多少条没读」这件事是两个页面各算一遍的：
 * 医生端在 `AdminLayout` 里拿会话列表 `filter(s => s.awaitingMyReply).length`，
 * 居民端根本没有。居民端照抄一遍就会变成两份统计 —— 而它们读的
 * 甚至不是同一个接口（医生端 `/chat/sessions`、居民端 `/chat/doctors`），
 * 口径迟早漂移成两套。
 *
 * 收成一个 store 之后：数字从服务端 `/chat/unread` 来，一次 COUNT、
 * 两端同一个字段；前端只负责渲染。**两端红点表现一致因此是结构上的事实**，
 * 而不是两边各自小心的结果。
 *
 * <h3>「读过」由谁决定</h3>
 *
 * 由**看到消息的人**决定，不由「调了哪个接口」决定：聊天页在前台、有未读时才
 * 调 {@link markRead}。所以标记动作放在 ChatPanel（它知道页面可见性与消息列表），
 * 而本 store 只负责把结果落到数字上并广播出去。
 */

/**
 * 轮询间隔 15 秒。
 *
 * 比 ChatPanel 的 5 秒慢三倍：这个接口带聚合子查询，而「有人给我发消息」
 * 这件事本身发生频率很低（分钟级以上）。15 秒足够让红点看起来是"及时"的，
 * 又不至于让每个在线用户都持续打一条 COUNT。
 */
const POLL_MS = 15000

export const useInboxStore = defineStore('inbox', () => {
  /** 有未读消息的会话数 —— 导航角标显示它 */
  const sessionCount = ref(0)
  /** 未读消息总条数 —— 列表顶部的汇总文案用 */
  const messageCount = ref(0)

  const hasUnread = computed(() => sessionCount.value > 0)

  /**
   * 写入一次查询结果。
   *
   * 独立成一个方法而不是散在各处赋值：`/chat/unread` 与「标记已读」的返回值
   * 是同一个结构，两处都该走同一条赋值路径 —— 否则将来加字段时只会改一处。
   */
  function apply(vo: ChatUnread | null) {
    sessionCount.value = vo?.sessionCount ?? 0
    messageCount.value = vo?.messageCount ?? 0
  }

  /**
   * 拉一次未读汇总。
   *
   * 管理员直接返回：/chat 系列接口限定 DOCTOR/RESIDENT，管理员调它必定 403，
   * 而拦截器会把 403 弹成一条错误提示 —— 侧栏每次挂载都弹一次「无权限」是纯噪音。
   * 角色判断放在这里，调用方（两个布局）就不必各自写一遍。
   */
  async function refresh() {
    const role = useAuthStore().role
    if (role !== 'DOCTOR' && role !== 'RESIDENT') {
      reset()
      return
    }
    try {
      apply(await getUnread())
    } catch {
      // 红点只是提醒：拿不到就当没有（不亮），不该因为一个角标把界面打崩
    }
  }

  /**
   * 标记某个会话已读，并用返回值直接更新数字。
   *
   * 后端返回的是**标记后**的汇总，所以这里不需要再调一次 refresh() ——
   * 少一次往返，也就没有「已经读过了、红点还亮着」的中间态。
   */
  async function markRead(sessionId: number) {
    if (useAuthStore().role === 'ADMIN') return
    try {
      apply(await markSessionRead(sessionId))
    } catch {
      // 同上：标不上不影响继续聊天
    }
  }

  /** 登出时清空 —— 否则换账号登录会先看到上一个人的红点数字 */
  function reset() {
    sessionCount.value = 0
    messageCount.value = 0
  }

  /*
   * 轮询用模块级变量而不是 store 内的 ref：
   * 定时器不是界面状态，放进 ref 会被 Pinia 的响应式包裹（毫无用处），
   * 而且在 devtools 里会显示成一个乱转的对象。
   *
   * 用引用计数而不是「各自开一个」：两个布局不会同时挂载，但路由守卫、
   * 页面重载、HMR 都可能让 onMounted/onBeforeUnmount 不成对，
   * 引用计数保证最终一定剩 0 个定时器，而布尔开关做不到。
   */
  let timer: ReturnType<typeof setInterval> | null = null
  let holders = 0

  /** 布局挂载时调用；立即拉一次，再按 POLL_MS 轮询 */
  function startPolling() {
    holders += 1
    if (timer !== null) return
    refresh()
    timer = setInterval(refresh, POLL_MS)
  }

  function stopPolling() {
    holders = Math.max(0, holders - 1)
    if (holders === 0 && timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  return {
    sessionCount,
    messageCount,
    hasUnread,
    refresh,
    markRead,
    reset,
    startPolling,
    stopPolling,
  }
})
