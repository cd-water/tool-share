import http from '@/api/request'
import type { AdminRole, AdminRoleUpdateDTO, AdminVO } from '@/api/types'

export const adminApi = {
  list: () => http.get<AdminVO[]>('/admin/admins'),
  updateRole: (id: number, role: AdminRole) =>
    http.patch<void>(`/admin/admins/${id}/role`, { role } satisfies AdminRoleUpdateDTO),
}
