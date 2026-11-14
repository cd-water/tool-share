import http from '@/api/request'
import type {
  AvailabilityResult,
  PageParams,
  PageResult,
  Tool,
  ToolCategory,
  ToolRentalHistory,
  ToolStatus,
} from '@/api/types'

export interface ToolQuery extends PageParams {
  keyword?: string
  category?: ToolCategory
  status?: ToolStatus
  orderBy?: string
}

export const toolsApi = {
  list: (params: ToolQuery) =>
    http.get<PageResult<Tool>>('/user/tools', { params }),
  detail: (id: number) => http.get<Tool>(`/user/tools/${id}`),
  history: (id: number) => http.get<ToolRentalHistory>(`/user/tools/${id}/history`),
  availability: (id: number, startTime: string, endTime: string) =>
    http.get<AvailabilityResult>(`/user/tools/${id}/availability`, {
      params: { startTime, endTime },
    }),
  /** 蹲守（工具可用时提醒我） */
  watch: (id: number) => http.post<void>(`/user/tools/${id}/watch`),
  unwatch: (id: number) => http.delete<void>(`/user/tools/${id}/watch`),
  watched: (id: number) => http.get<{ watched: boolean }>(`/user/tools/${id}/watch`),
}
