import http from '@/api/request'
import type { MessageQuery, MessageVO, PageResult } from '@/api/types'

export const messageApi = {
  list: (params: MessageQuery) =>
    http.get<PageResult<MessageVO>>('/admin/messages', { params }),
  unreadCount: () => http.get<number>('/admin/messages/unread-count'),
  read: (id: number) => http.post<void>(`/admin/messages/${id}/read`),
  readAll: () => http.post<void>('/admin/messages/read-all'),
}
