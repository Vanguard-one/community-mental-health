import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { setupGuards } from './guards'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/Register.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    redirect: '/home',
    children: [
      { path: 'home', name: 'home', component: () => import('@/views/home/Home.vue') },
      { path: 'scales', name: 'scales', component: () => import('@/views/assessment/ScaleList.vue') },
      { path: 'scales/:id', name: 'questionnaire', component: () => import('@/views/assessment/Questionnaire.vue') },
      { path: 'assessment/:id/result', name: 'result', component: () => import('@/views/assessment/Result.vue') },
      { path: 'ai-chat', name: 'ai-chat', component: () => import('@/views/consult/AiChat.vue') },
      // 我的消息：与医生的沟通记录。本页只负责「找得到人」——列表 + 点进对话。
      { path: 'messages', name: 'messages', component: () => import('@/views/chat/MyMessages.vue') },
      /*
        专有聊天页：居民端所有「和医生聊天」的按钮都跳这里。
        参数是 doctorId 而不是 sessionId —— 从「医生咨询」页进来时会话还不存在
        （会话在点按钮那一刻才建），而 POST /chat/sessions 是幂等的，
        页面内调一次即可建或取，不必在跳转前多一次往返。
      */
      {
        path: 'messages/:doctorId',
        name: 'doctor-chat',
        component: () => import('@/views/chat/DoctorChat.vue'),
      },
      // 医生咨询：两级结构，用 ?dept= 同步（科室分类 → 该科室医生列表）。
      // 与 /ai-recommend 分工不同：那边是「AI 替你挑好了」，这边是「自己按科室挑」。
      { path: 'doctors', name: 'doctors', component: () => import('@/views/recommend/DoctorConsult.vue') },
      // AI 推荐结果页：AI 问诊中用户同意接受推荐后跳到这里（带 ?sessionId=）
      {
        path: 'ai-recommend',
        name: 'ai-recommend',
        component: () => import('@/views/recommend/AiRecommendResult.vue'),
      },
      { path: 'history', name: 'history', component: () => import('@/views/history/AssessmentHistory.vue') },
      { path: 'notifications', name: 'notifications', component: () => import('@/views/notification/NotificationList.vue') },
    ],
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/admin/dashboard',
    meta: { roles: ['ADMIN', 'DOCTOR'] },
    children: [
      // 驾驶舱仅管理端可见，医生端进入时由 AdminLayout 菜单过滤 + 页面自身兜底
      { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/admin/Dashboard.vue') },
      // 账号治理：纯管理职能，医生不得进入（子路由 meta.roles 已收紧，守卫按匹配链逐层校验）
      {
        path: 'accounts',
        name: 'admin-accounts',
        component: () => import('@/views/admin/AccountManage.vue'),
        meta: { roles: ['ADMIN'] },
      },
      // 知识库：RAG 的索引层。同样是纯管理职能 —— 索引是**全局资源**，
      // 医生改了会影响所有居民拿到的回答，所以只给 ADMIN。
      {
        path: 'knowledge',
        name: 'admin-knowledge',
        component: () => import('@/views/admin/KnowledgeBase.vue'),
        meta: { roles: ['ADMIN'] },
      },
      // 首页轮播管理：运营物料（首页右下角那一块）。纯管理职能，
      // 医生改不了也不该改 —— 它是面向全体访客的统一展示位。
      {
        path: 'banners',
        name: 'admin-banners',
        component: () => import('@/views/admin/BannerManage.vue'),
        meta: { roles: ['ADMIN'] },
      },
      // 工单处置台：单页左右分栏（设计稿 3:410）。
      // 两条路径共用同一组件 —— 分栏是主从结构，不是两个页面；
      // /:id 仅用于深链直达与刷新还原，选中工单用 router.replace 同步 URL，不污染历史。
      { path: 'alerts', name: 'admin-alerts', component: () => import('@/views/alert/AlertConsole.vue') },
      { path: 'alerts/:id', name: 'admin-alert-detail', component: () => import('@/views/alert/AlertConsole.vue') },
      // 消息列表：医患对话的收件箱（谁发来了消息、谁还没回）。
      // 与「用户信息」分工不同：那边是主动找人看病历，这边是被动接收。
      // 同样收紧为 DOCTOR —— 后端 /chat 只放行 DOCTOR 与 RESIDENT。
      {
        path: 'messages',
        name: 'admin-messages',
        component: () => import('@/views/doctor/MessageList.vue'),
        meta: { roles: ['DOCTOR'] },
      },
      // 用户信息：医生看患者档案 + 所属评测问卷。
      // 子路由 roles 收紧为 DOCTOR —— 管理员的账号治理是另一套职能
      //（那边管「账号」，这里看「患者」），守卫会逐层校验这条约束。
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/doctor/ResidentInfo.vue'),
        meta: { roles: ['DOCTOR'] },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/home',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

setupGuards(router)

export default router