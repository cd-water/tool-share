import http from '@/api/request'
import type {
  Message,
  MessageType,
  NotificationPref,
  NotificationPrefUpdatePayload,
  PageParams,
  PageResult,
} from '@/api/types'

export type MessageQuery = PageParams

export const messagesApi = {
  list: (params: MessageQuery) =>
    http.get<PageResult<Message>>('/user/messages', { params }),
  unreadCount: () => http.get<number>('/user/messages/unread-count'),
  read: (id: number) => http.post<void>(`/user/messages/${id}/read`),
  readAll: () => http.post<void>('/user/messages/read-all'),
}

export const notificationPrefsApi = {
  list: () => http.get<NotificationPref[]>('/user/notification-prefs'),
  update: (type: MessageType, payload: NotificationPrefUpdatePayload) =>
    http.put<NotificationPref>(`/user/notification-prefs/${type}`, payload),
}
