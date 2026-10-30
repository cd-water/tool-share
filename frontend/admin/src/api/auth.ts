import http from '@/api/request'

export interface LoginVO {
  tokenName: string
  tokenValue: string
}

export interface AdminVO {
  id: number
  username: string
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginVO>('/admin/login', { username, password }),
  logout: () => http.post<void>('/admin/logout'),
  info: () => http.get<AdminVO>('/admin/info'),
}
