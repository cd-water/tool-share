/**
 * 仪表盘统计接口（/admin/dashboard）。
 *
 * 一次性返回首页需要的关键指标与图表数据，避免前端多次聚合请求。
 * 数据为只读，无副作用。
 */
import http from '@/api/request'
import type { AdminDashboardVO } from '@/api/types'

/**
 * 仪表盘 API 集合。
 */
export const dashboardApi = {
  /**
   * 获取仪表盘汇总数据。
   *
   * @returns 工具、租赁、报修等统计快照
   * @throws 请求失败时由拦截器提示并 reject
   */
  get: () => http.get<AdminDashboardVO>('/admin/dashboard'),
}
