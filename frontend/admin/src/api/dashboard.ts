import http from '@/api/request'

export interface AdminDashboardVO {
  toolTotal: number
  availableToolCount: number
  rentedToolCount: number
  maintenanceToolCount: number
  activeRentalCount: number
  overdueRentalCount: number
  pendingDamageCount: number
  userCount: number
  todayRentalCount: number
}

export const dashboardApi = {
  get: () => http.get<AdminDashboardVO>('/admin/dashboard'),
}
