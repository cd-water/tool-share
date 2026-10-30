import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { userApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

export function useLogout() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: () => userApi.logout(),
    onSettled: () => {
      useAuthStore.getState().clear()
      queryClient.clear()
      navigate('/login', { replace: true })
    },
  })
}
