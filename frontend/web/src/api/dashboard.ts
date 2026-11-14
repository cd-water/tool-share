/**
 * 用户首页看板接口。
 *
 * 看板聚合了当前用户的租赁概览、待办提醒、信用与统计数据，
 * 后端一次返回完整对象，前端无需再拼装多次请求。
 */
import http from '@/api/request'
import type { UserDashboard } from '@/api/types'

export const dashboardApi = {
  /**
   * 获取首页看板数据。
   *
   * @returns 聚合后的看板 VO，字段由 OpenAPI 契约维护
   *
   * 该接口无参数；登录态由请求拦截器统一注入。
   * 401 时会触发全局登出流程，其余错误统一 toast。
   */
  get: () => http.get<UserDashboard>('/user/dashboard'),
}
