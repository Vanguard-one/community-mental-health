import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getUnread, markSessionRead } from '@/api/chat'
import { getUnreadNotificationCount } from '@/api/notification'
import { useAuthStore } from './auth'
import type { ChatUnread } from '@/types'

/**
 * 未读收件箱 —— 三端侧栏导航红点的**唯一**数据源。
 *
 * <h3>两类未读，两个字段，不合并</h3>
 *
 * | 字段 | 来源 | 口径 | 角色 |
 * |---|---|---|---|
 * | `sessionCount` / `messageCount` | `/chat/unread` | 有未读消息的**会话数** / 未读**消息条数** | DOCTOR / RESIDENT |
 * | `noticeCount` | `/notification/unread-count` | 未读通知的**条数** | 全部角色 |
 *
 * 为什么不合并成一个数字：一个会话里 5 条未读只算 1 个会话，而通知是逐条计数的。
 * 合并之后「点开一条通知该减多少」就再也说不清了，而且侧栏上本来就是
 * 两个菜单项、两个角标（Mail 与 Bell），各对应自己的口径。
 *
 * <h3>为什么要单独一个 store</h3>
 *
 * 在它之前，「有多少条没读」这件事是两个页面各算一遍的：
 * 医生端在 `AdminLayout` 里拿会话列表 `filter(s => s.awaitingMyReply).length`，
 * 居民端根本没有。居民端照抄一遍就会变成两份统计 —— 而它们读的
 * 甚至不是同一个接口（医生端 `/chat/sessions`、居民端 `/chat/doctors`），
 * 口径迟早漂移成两套。
 *
 * 收成一个 store 之后：数字从服务端来，一次 COUNT、三端同一个字段；
 * 前端只负责渲染。**三端红点表现一致因此是结构上的事实**，
 * 而不是三边各自小心的结果。
 *
 * <h3>「读过」由谁决定</h3>
 *
 * 由**看到内容的人**决定，不由「调了哪个接口」决定：聊天页在前台、有未读时才
 * 调 {@link markRead}；通知页打开某条的弹层时才调 {@link markNoticeRead}。
 * 所以标记动作放在页面（它知道用户真的看到了什么），
 * 而本 store 只负责把结果落到数字上。
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
  /** 未读通知条数 —— 「通知」菜单的角标。逐条计，与 sessionCount 不同口径 */
  const noticeCount = ref(0)

  const hasUnread = computed(() => sessionCount.value > 0 || noticeCount.value > 0)

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
   * 拉一次全部未读。
   *
   * 两类请求互不依赖，并发发出；某一类失败不影响另一类（各自 catch 兜住）——
   * 通知接口超时不该让消息角标跟着消失。
   */
  async function refresh() {
    await Promise.all([refreshChat(), refreshNotice()])
  }

  /**
   * 对话未读。
   *
   * 管理员直接清空：`/chat` 系列接口限定 DOCTOR/RESIDENT，管理员调它必定 403，
   * 而拦截器会把 403 弹成一条错误提示 —— 侧栏每次挂载都弹一次「无权限」是纯噪音。
   * 角色判断放在这里，调用方（两个布局）就不必各自写一遍。
   */
  async function refreshChat() {
    const role = useAuthStore().role
    if (role !== 'DOCTOR' && role !== 'RESIDENT') {
      sessionCount.value = 0
      messageCount.value = 0
      return
    }
    try {
      apply(await getUnread())
    } catch {
      // 红点只是提醒：拿不到就当没有（不亮），不该因为一个角标把界面打崩
    }
  }

  /**
   * 通知未读。
   *
   * 不判角色：`/notification` 是按 `user_id` 查的，管理员也有自己的站内信
   * （系统公告、预警）；它也没有 `requireRole`，三种角色都调得通。
   */
  async function refreshNotice() {
    try {
      noticeCount.value = (await getUnreadNotificationCount()) ?? 0
    } catch {
      // 同上：角标拿不到就当没有
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

  /**
   * 点开一条通知之后调用 —— 本地减一。
   *
   * <p>不再请求一次：调用方（通知页的 `open`）已经发过
   * `POST /notification/{id}/read`，而「点开一条 = 未读减一」在这里是**确定的** ——
   * 不像对话那样一个会话里可能有多条未读、减多少要看服务端返回的汇总。
   * 少一次往返，也就没有「已经读过了、红点还亮着」的中间态。
   *
   * <p>`Math.max(0, …)` 不是防御性编程：轮询还没回来时本地可能是 0，
   * 减成 -1 的话角标虽然不显示（`v-if="count > 0"`），但悬浮提示会写着「-1 条未读」。
   */
  function markNoticeRead() {
    noticeCount.value = Math.max(0, noticeCount.value - 1)
  }

  /** 登出时清空 —— 否则换账号登录会先看到上一个人的红点数字 */
  function reset() {
    sessionCount.value = 0
    messageCount.value = 0
    noticeCount.value = 0
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
    noticeCount,
    hasUnread,
    refresh,
    refreshNotice,
    markRead,
    markNoticeRead,
    reset,
    startPolling,
    stopPolling,
  }
})
