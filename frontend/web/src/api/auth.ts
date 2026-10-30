import http from '@/api/request'

export interface LoginResult {
  tokenName: string
  tokenValue: string
}

export interface UserProfile {
  id: number
  phone: string
  nickname: string
  avatar: string
}

export const userApi = {
  sendCode: (phone: string) => http.post<void>('/user/send-code', { phone }),
  loginByCode: (phone: string, code: string) =>
    http.post<LoginResult>('/user/login/by-code', { phone, code }),
  loginByPassword: (phone: string, password: string) =>
    http.post<LoginResult>('/user/login/by-pwd', { phone, password }),
  logout: () => http.post<void>('/user/logout'),
  info: () => http.get<UserProfile>('/user/info'),
}
