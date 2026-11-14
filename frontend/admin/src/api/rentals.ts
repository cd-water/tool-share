/**
 * 租赁单管理接口（/admin/rentals）。
 *
 * 覆盖租赁单的查询、取件确认、归还验收、取消与导出。
 * 这些操作推进租赁状态机并触发押金/费用结算，成功后应刷新详情。
 */
import http from '@/api/request'
import type {
  PageResult,
  RentalCancelDTO,
  RentalQuery,
  RentalReturnDTO,
  RentalVO,
} from '@/api/types'

/**
 * 租赁单 API 集合。
 *
 * 状态流转类方法返回 void，resolve 即表示后端已完成状态变更；
 * 导出方法返回 Blob，供浏览器触发文件下载。
 */
export const rentalApi = {
  /**
   * 分页查询租赁单。
   *
   * @param params 关键词、状态、是否逾期、损坏结果与分页参数
   * @returns 租赁单分页结果
   * @throws 请求失败时由拦截器提示并 reject
   */
  list: (params: RentalQuery) =>
    http.get<PageResult<RentalVO>>('/admin/rentals', { params }),
  /**
   * 获取单条租赁单详情。
   *
   * @param id 租赁单 ID
   * @returns 完整租赁单，含费用明细与支付记录
   * @throws 租赁单不存在时由拦截器提示并 reject
   */
  detail: (id: number) => http.get<RentalVO>(`/admin/rentals/${id}`),
  /**
   * 确认取件，将租赁单推进到「使用中」。
   *
   * @param id 租赁单 ID
   * @returns 无响应体，resolve 表示取件成功
   * 成功后工具状态会变为已租出，列表页应与工具状态一起刷新。
   * @throws 未支付或状态不允许时由拦截器提示并 reject
   */
  pickup: (id: number) => http.post<void>(`/admin/rentals/${id}/pickup`),
  /**
   * 归还验收，提交损坏结果并结算费用。
   *
   * @param id 租赁单 ID
   * @param data 归还验收数据（损坏结果、押金/费用等）
   * @returns 无响应体，resolve 表示归还完成
   * @throws 状态不允许归还或参数非法时由拦截器提示并 reject
   */
  return: (id: number, data: RentalReturnDTO) =>
    http.post<void>(`/admin/rentals/${id}/return`, data),
  /**
   * 取消租赁单（通常由管理员代用户取消）。
   *
   * @param id 租赁单 ID
   * @param data 取消原因；原因必填，会展示在用户端
   * @returns 无响应体，resolve 表示取消成功
   * @throws 已取件等不可取消状态由拦截器提示并 reject
   */
  cancel: (id: number, data: RentalCancelDTO) =>
    http.post<void>(`/admin/rentals/${id}/cancel`, data),
  /**
   * 导出租赁单为文件（Excel 等二进制流）。
   *
   * @param params 与列表页一致的筛选条件，导出当前筛选结果
   * @returns Blob；响应拦截器对 responseType=blob 会直接返回二进制
   * 调用方需自行创建下载链接并处理文件名。
   * @throws 后端返回 JSON 错误 envelope 时，拦截器会解析并提示
   */
  export: (params: object) => http.download('/admin/rentals/export', params),
}
