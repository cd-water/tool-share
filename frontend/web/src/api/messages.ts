import http from '@/api/request'
import type { Message, PageResult } from '@/api/types'

export interface MessageQuery {
  page?: number
  size?: number
}

export const messagesApi = {
  list: (params: MessageQuery) =>
    http.get<PageResult<Message>>('/user/messages', { params }),
  unreadCount: () => http.get<number>('/user/messages/unread-count'),
  read: (id: number) => http.post<void>(`/user/messages/${id}/read`),
  readAll: () => http.post<void>('/user/messages/read-all'),
}
