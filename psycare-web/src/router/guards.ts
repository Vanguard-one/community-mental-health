import type { Router } from 'vue-router'

function getRole(): string {
  try {
    const raw = localStorage.getItem('userInfo')
    return raw ? (JSON.parse(raw)?.role || '') : ''
  } catch {
    return ''
  }
}

function homeByRole(): string {
  const role = getRole()
  if (role === 'ADMIN') return '/admin/dashboard'
  if (role === 'DOCTOR') return '/admin/alerts'
  return '/home'
}

export function setupGuards(router: Router) {
  router.beforeEach((to, _from, next) => {
    const token = localStorage.getItem('token')

    // 公开页面：已登录访问登录/注册时直接进入对应首页
    if (to.meta.public) {
      if (token) {
        next(homeByRole())
        return
      }
      next()
      return
    }

    // 受保护页面：未登录跳转登录页
    if (!token) {
      next('/login')
      return
    }

    /**
     * 角色权限：校验整条匹配链上的所有 roles 约束。
     *
     * 必须遍历 `to.matched` 而不是 `find` 出第一条 —— `matched` 是父 → 子顺序，
     * find 会先命中父路由（/admin 的 ['ADMIN','DOCTOR']）就返回，
     * 子路由更严的约束（如账号治理的 ['ADMIN']）永远不会被检查。
     */
    const constrained = to.matched.filter((r) => r.meta.roles)
    if (constrained.length > 0) {
      const role = getRole()
      const denied = constrained.some(
        (r) => !(r.meta.roles as string[]).includes(role),
      )
      if (denied) {
        next(homeByRole())
        return
      }
    }

    next()
  })
}