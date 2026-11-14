import http from '@/api/request'
import type { LoginResult, UserProfile } from '@/api/types'

export const userApi = {
  sendCode: (phone: string) => http.post<string>('/user/send-code', { phone }),
  loginByCode: (phone: string, code: string) =>
    http.post<LoginResult>('/user/login/by-code', { phone, code }),
  loginByPassword: (phone: string, password: string) =>
    http.post<LoginResult>('/user/login/by-pwd', { phone, password }),
  logout: () => http.post<void>('/user/logout'),
  info: () => http.get<UserProfile>('/user/info'),
}
