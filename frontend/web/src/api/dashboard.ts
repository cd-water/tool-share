import http from '@/api/request'
import type { UserDashboard } from '@/api/types'

export const dashboardApi = {
  get: () => http.get<UserDashboard>('/user/dashboard'),
}
