/**
 * 当前登录用户信息查询。
 *
 * 为什么单独抽 `userInfoOptions`：
 * - 路由守卫、layout、资料页等多个位置需要同一份用户信息，集中定义
 *   queryKey / queryFn / staleTime，避免各页面写出不一致的缓存策略；
 * - queryFn 在拿到资料后顺手写入 zustand，让同步渲染的位置（如顶栏）
 *   无需额外订阅 React Query；
 * - `retry: false`：401 重试没有意义，且统一请求层已经处理了登录失效。
 *
 * 启用条件由 token 是否存在决定：未登录时不发请求，避免登录页产生 401 噪音。
 */
import { queryOptions, useQuery } from '@tanstack/react-query'
import { userApi } from '@/api/auth'
import type { UserProfile } from '@/api/types'
import { queryKeys } from '@/lib/queryKeys'
import { useAuthStore } from '@/stores/auth'

/**
 * 用户信息的可复用查询配置。
 *
 * - queryKey：`['user', 'info']`，与 `queryKeys.user.info` 保持一致；
 * - queryFn：请求 `/user/info` 并把结果同步到 auth store；
 * - staleTime：5 分钟内视为新鲜，避免页面切换时重复请求；
 * - retry：关闭自动重试，鉴权失败应尽快进入登出流程。
 */
export const userInfoOptions = queryOptions({
  queryKey: queryKeys.user.info,
  queryFn: async (): Promise<UserProfile> => {
    const user = await userApi.info()
    // 侧写：把最新资料同步到持久化 store，供非 Query 场景直接读取。
    useAuthStore.getState().setProfile(user)
    return user
  },
  staleTime: 5 * 60_000,
  retry: false,
})

/**
 * 订阅当前用户信息。
 *
 * @returns React Query 查询结果；未登录时 `enabled=false`，不会发起请求。
 *
 * 使用 `useAuthStore` 的 selector 订阅 token，token 变化会自动切换
 * 查询的启用状态（例如退出登录后停止请求）。
 */
export function useUserInfo() {
  const tokenValue = useAuthStore((s) => s.tokenValue)
  return useQuery({ ...userInfoOptions, enabled: Boolean(tokenValue) })
}
