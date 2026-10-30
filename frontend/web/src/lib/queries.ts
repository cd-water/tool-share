import { useQuery } from '@tanstack/react-query'
import { userApi, type UserVO } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

export const userInfoQuery = {
  queryKey: ['user', 'info'] as const,
  queryFn: async (): Promise<UserVO> => {
    const user = await userApi.info()
    useAuthStore.getState().setProfile(user)
    return user
  },
  staleTime: 5 * 60_000,
  retry: false,
}

export function useUserInfo() {
  const tokenValue = useAuthStore((s) => s.tokenValue)
  return useQuery({
    ...userInfoQuery,
    enabled: Boolean(tokenValue),
  })
}
