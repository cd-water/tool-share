import type { LoginData, LoginResult, UserInfo } from './types'
import { httpGet, httpPost } from '../utils/request'

export function login(data: LoginData) {
  return httpPost<LoginResult>('/auth/login', data)
}

export function logout() {
  return httpPost<void>('/auth/logout')
}

export function me() {
  return httpGet<UserInfo>('/auth/me')
}
