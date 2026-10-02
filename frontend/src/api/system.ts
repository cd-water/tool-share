import type { ConfigItem, PageResult, UserQuery, UserInfo } from './types'
import { httpGet, httpPut } from '../utils/request'

export function listUsers(params: UserQuery) {
  return httpGet<PageResult<UserInfo>>('/system/users', params)
}

export function updateUserStatus(id: number, status: 'ACTIVE' | 'DISABLED') {
  return httpPut<void>(`/system/users/${id}/status`, { status })
}

export function listConfigs() {
  return httpGet<ConfigItem[]>('/system/configs')
}

export function updateConfig(key: string, configValue: string) {
  return httpPut<void>(`/system/configs/${key}`, { configValue })
}
