/**
 * 退出登录 hook。
 *
 * 设计要点：
 * - 使用 `useMutation` 包一层，让调用方可以拿到 `isPending` 控制按钮禁用；
 * - 清理逻辑放在 `onSettled` 而不是 `onSuccess`：即使后端注销请求失败
 *   （例如 token 已过期、网络中断），也必须完成本地登出，避免用户卡死；
 * - `queryClient.clear()` 清空全部 React Query 缓存，防止下一个登录用户
 *   看到上一个用户的敏感数据；
 * - 使用 `replace` 跳转，避免用户点浏览器返回又回到需要登录的页面。
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { userApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

/**
 * 返回退出登录的 mutation。
 *
 * @returns TanStack Query mutation；`mutate()` 触发注销。
 *
 * 副作用：
 * 1. 请求后端 `/user/logout`（失败也继续）；
 * 2. 清空 zustand 持久化的 token/昵称/头像；
 * 3. 清空所有查询缓存；
 * 4. 以 replace 方式跳转到 `/login`。
 *
 * 错误处理：本地清理不依赖请求结果，因此页面不会因为注销失败而报错。
 */
export function useLogout() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  return useMutation({
    // 注销接口本身不需要参数，token 由请求拦截器注入。
    mutationFn: () => userApi.logout(),
    onSettled: () => {
      // 无论成功失败都执行：先清会话，再清缓存，最后跳转。
      useAuthStore.getState().clear()
      queryClient.clear()
      navigate('/login', { replace: true })
    },
  })
}
