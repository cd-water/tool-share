import http from '@/api/request'
import type { AdminDashboardVO } from '@/api/types'

export const dashboardApi = {
  get: () => http.get<AdminDashboardVO>('/admin/dashboard'),
}
