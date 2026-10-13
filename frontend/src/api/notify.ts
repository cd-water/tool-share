import { get, put } from './request'
import type { Message, NotifyConfigDTO, NotifyQueryDTO, PageResult } from './types'

/** 我的消息分页 */
export function pageNotify(query: NotifyQueryDTO) {
  return get<PageResult<Message>>('/notify/page', query)
}

/** 标记已读 */
export function readNotify(id: number) {
  return put<void>(`/notify/${id}/read`)
}

/** 全部标记已读 */
export function readAllNotify() {
  return put<void>('/notify/read-all')
}

/** 查询默认提醒方式配置（工作人员） */
export function getNotifyConfig() {
  return get<NotifyConfigDTO>('/notify/config')
}

/** 修改默认提醒方式（工作人员） */
export function updateNotifyConfig(data: NotifyConfigDTO) {
  return put<void>('/notify/config', data)
}
