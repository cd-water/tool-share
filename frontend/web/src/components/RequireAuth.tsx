/**
 * 文件职责：居民端路由守卫（RequireAuth）。
 *
 * 作为布局路由使用：在 router 中包裹需要登录的页面，未登录时重定向到 /login，
 * 并把当前路径写入 location.state.from，登录成功后由 LoginPage 跳回原页面。
 *
 * 该组件只做“是否持有 token”的判断，不校验 token 是否过期，也不拉取用户信息；
 * token 失效由 API 拦截器统一处理（清理登录态并跳转登录）。因此它是轻量的
 * 第一道门，而不是完整的权限系统。
 */
import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 认证守卫组件。
 *
 * @returns 已登录时渲染 <Outlet />（子路由）；未登录时渲染 <Navigate> 重定向。
 *
 * 副作用：未登录时触发一次 replace 导航，浏览器历史中不会留下受保护页面；
 * 同时把来源路径（含 query）放入 state，便于登录后恢复深链接。
 *
 * 注意：仅凭内存中的 token 判断，刷新后由 auth store 的持久化策略决定，
 * 如果 store 未恢复 token，会视为未登录并跳转。
 */
export function RequireAuth() {
  const tokenValue = useAuthStore((s) => s.tokenValue)
  const location = useLocation()

    // 未登录分支：使用 replace 避免用户按后退键回到受保护页面形成循环。
    // state.from 同时保留 pathname 与 search，登录后可回到带筛选参数的页面。
    // 这里不渲染任何占位 UI，避免受保护内容短暂闪现（FOUC）。
  if (!tokenValue) {
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  }

  // 已登录分支直接把子路由交给 Outlet 渲染。
  // 守卫本身不包裹额外 DOM，因此不会影响页面布局与样式。
  // 权限细分（如管理员）应在上层路由或页面内单独判断，而不是加在这里。
  return <Outlet />
}
