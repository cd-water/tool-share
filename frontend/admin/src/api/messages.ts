/**
 * 消息中心与消息配置接口。
 *
 * messageApi 面向站内消息：分页列表、未读数、单条/全部已读。
 * messageConfigApi 面向消息通道配置：读取与整体更新。
 * 未读数常用于导航角标，前端可轮询或在关键操作后主动刷新。
 */
import http from '@/api/request'
import type {
  MessageConfigUpdateDTO,
  MessageConfigVO,
  MessageQuery,
  MessageVO,
  PageResult,
} from '@/api/types'

/**
 * 站内消息 API 集合。
 *
 * 已读操作是幂等的 POST：重复调用不会产生额外副作用。
 */
export const messageApi = {
  /**
   * 分页查询当前管理员的消息。
   *
   * @param params 分页参数；查询范围由后端按当前登录人限定
   * @returns 消息分页结果
   * @throws 请求失败时由拦截器提示并 reject
   */
  list: (params: MessageQuery) =>
    http.get<PageResult<MessageVO>>('/admin/messages', { params }),
  /**
   * 获取未读消息数，用于导航栏角标。
   *
   * @returns 未读条数（直接是 number，不是分页结构）
   * @throws 请求失败时由拦截器提示并 reject
   */
  unreadCount: () => http.get<number>('/admin/messages/unread-count'),
  /**
   * 将单条消息标记为已读。
   *
   * @param id 消息 ID
   * @returns 无响应体；幂等，重复标记不会报错
   * @throws 消息不存在时由拦截器提示并 reject
   */
  read: (id: number) => http.post<void>(`/admin/messages/${id}/read`),
  /**
   * 将当前管理员的全部消息标记为已读。
   *
   * @returns 无响应体
   * 成功后未读数应归零，调用方可据此直接更新角标或重新拉取。
   * @throws 请求失败时由拦截器提示并 reject
   */
  readAll: () => http.post<void>('/admin/messages/read-all'),
}

/**
 * 消息配置 API 集合。
 *
 * 配置包含各消息类型的开关与通道选择；更新采用 PUT 整体覆盖，
 * 调用方需提交完整配置而不是局部字段。
 */
export const messageConfigApi = {
  /**
   * 读取当前消息配置。
   *
   * @returns 消息配置（各类型开关、渠道等）
   * @throws 请求失败时由拦截器提示并 reject
   */
  get: () => http.get<MessageConfigVO>('/admin/message-config'),
  /**
   * 整体更新消息配置。
   *
   * @param payload 完整配置对象（PUT 语义，未提交的字段可能被覆盖）
   * @returns 更新后的配置
   * @throws 配置非法或无权限时由拦截器提示并 reject
   */
  update: (payload: MessageConfigUpdateDTO) =>
    http.put<MessageConfigVO>('/admin/message-config', payload),
}
