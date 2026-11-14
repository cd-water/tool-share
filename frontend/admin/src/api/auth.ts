import http from '@/api/request'
import type { AdminLoginDTO, AdminVO, LoginVO } from '@/api/types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginVO>('/admin/login', { username, password } satisfies AdminLoginDTO),
  logout: () => http.post<void>('/admin/logout'),
  info: () => http.get<AdminVO>('/admin/info'),
}
