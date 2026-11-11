import http from '@/api/request'
import type { PageResult, Tool } from '@/api/types'

export interface ToolQuery {
  keyword?: string
  category?: string
  status?: string
  page?: number
  size?: number
}

export interface AvailabilityResult {
  available: boolean
  message?: string
  suggestions?: string[]
}

export interface ToolWatchItem {
  toolId: number
  code: string
  name: string
  status: string
  statusName: string
  deposit: number
  imageUrl?: string
}

export const toolsApi = {
  list: (params: ToolQuery) =>
    http.get<PageResult<Tool>>('/user/tools', { params }),
  detail: (id: number) => http.get<Tool>(`/user/tools/${id}`),
  availability: (id: number, startTime: string, endTime: string) =>
    http.get<AvailabilityResult>(`/user/tools/${id}/availability`, {
      params: { startTime, endTime },
    }),
  /** 蹲守（工具可用时提醒我） */
  watch: (id: number) => http.post<void>(`/user/tools/${id}/watch`),
  unwatch: (id: number) => http.delete<void>(`/user/tools/${id}/watch`),
  watched: (id: number) => http.get<{ watched: boolean }>(`/user/tools/${id}/watch`),
  watchList: () => http.get<ToolWatchItem[]>('/user/tools/watch/list'),
}
