/**
 * 站内消息与通知偏好接口。
 *
 * 消息列表按时间倒序分页；未读数是顶栏红点的数据来源，通常配合
 * React Query 的轮询/聚焦刷新使用。通知偏好按消息类型逐项开关，
 * 更新接口使用 PUT 以保证幂等。
 */
import http from '@/api/request'
import type {
  Message,
  MessageType,
  NotificationPref,
  NotificationPrefUpdatePayload,
  PageParams,
  PageResult,
} from '@/api/types'

/**
 * 消息列表查询参数。
 *
 * 目前仅支持分页；若后续增加「已读/未读」筛选，可在此扩展字段，
 * 同时所有调用方通过该别名自动获得类型约束。
 */
export type MessageQuery = PageParams

export const messagesApi = {
  /**
   * 分页查询消息列表。
   *
   * @param params 分页参数
   * @returns 当前页消息与分页元信息
   *
   * 缓存 key：`queryKeys.messages.list(params)`。
   */
  list: (params: MessageQuery) =>
    http.get<PageResult<Message>>('/user/messages', { params }),

  /**
   * 查询未读消息数量。
   *
   * @returns 未读条数，用于导航栏角标
   *
   * 该接口返回数字而非对象，调用方需注意 envelope 解包后即为 number。
   */
  unreadCount: () => http.get<number>('/user/messages/unread-count'),

  /**
   * 将单条消息标记为已读。
   *
   * 副作用：后端更新已读状态；成功后调用方应同时失效消息列表与
   * 未读数量缓存，保证角标即时刷新。
   */
  read: (id: number) => http.post<void>(`/user/messages/${id}/read`),

  /**
   * 一键全部标记已读。
   *
   * 副作用同上，但影响当前用户所有未读消息。
   */
  readAll: () => http.post<void>('/user/messages/read-all'),
}

export const notificationPrefsApi = {
  /**
   * 查询当前用户的通知偏好列表。
   *
   * @returns 每种消息类型的开关配置
   */
  list: () => http.get<NotificationPref[]>('/user/notification-prefs'),

  /**
   * 更新某个消息类型的通知偏好。
   *
   * @param type 消息类型枚举，作为路径参数定位配置项
   * @param payload 需要更新的偏好字段（如站内信/邮件/推送开关）
   * @returns 更新后的偏好对象
   *
   * PUT 语义为整体覆盖该类型的配置，调用方需传入完整 payload。
   */
  update: (type: MessageType, payload: NotificationPrefUpdatePayload) =>
    http.put<NotificationPref>(`/user/notification-prefs/${type}`, payload),
}
