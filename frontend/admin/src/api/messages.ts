import http from '@/api/request'
import type { PageQuery, PageResult } from '@/api/types'

export interface MessageVO {
  id: number
  type: string
  typeName?: string
  title: string
  content: string
  channel: string
  readStatus: number | boolean | string
  createTime?: string
}

export const messageApi = {
  list: (params: PageQuery) =>
    http.get<PageResult<MessageVO>>('/admin/messages', { params }),
  unreadCount: () => http.get<number>('/admin/messages/unread-count'),
  read: (id: number) => http.post<void>(`/admin/messages/${id}/read`),
  readAll: () => http.post<void>('/admin/messages/read-all'),
}
