import { queryOptions, useQuery } from '@tanstack/react-query'
import { userApi, type UserProfile } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

export const userInfoOptions = queryOptions({
  queryKey: ['user', 'info'] as const,
  queryFn: async (): Promise<UserProfile> => {
    const user = await userApi.info()
    useAuthStore.getState().setProfile(user)
    return user
  },
  staleTime: 5 * 60_000,
  retry: false,
})

export function useUserInfo() {
  const tokenValue = useAuthStore((s) => s.tokenValue)
  return useQuery({ ...userInfoOptions, enabled: Boolean(tokenValue) })
}
