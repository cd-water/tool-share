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

export const toolsApi = {
  list: (params: ToolQuery) =>
    http.get<PageResult<Tool>>('/user/tools', { params }),
  detail: (id: number) => http.get<Tool>(`/user/tools/${id}`),
  availability: (id: number, startTime: string, endTime: string) =>
    http.get<AvailabilityResult>(`/user/tools/${id}/availability`, {
      params: { startTime, endTime },
    }),
}
