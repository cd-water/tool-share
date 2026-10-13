import { download, get, post } from './request'
import type {
  PageResult,
  RentalCreateDTO,
  RentalCreateVO,
  RentalOrderVO,
  RentalPickupDTO,
  RentalQueryDTO,
  RentalReturnDTO,
  TimeSlotVO,
} from './types'

/** 查询工具可预约时段 */
export function getAvailableTimes(toolId: number, date: string) {
  return get<TimeSlotVO[]>('/rental/available-times', { toolId, date })
}

/** 创建预约 */
export function createRental(data: RentalCreateDTO) {
  return post<RentalCreateVO>('/rental', data)
}

/** 押金缴纳（支付沙箱） */
export function payRental(id: number) {
  return post<void>(`/rental/${id}/pay`)
}

/** 取消预约（取件前 24 小时） */
export function cancelRental(id: number) {
  return post<void>(`/rental/${id}/cancel`)
}

/** 取件登记（工作人员） */
export function pickupRental(id: number, data?: RentalPickupDTO) {
  return post<void>(`/rental/${id}/pickup`, data)
}

/** 归还验收（工作人员） */
export function returnRental(id: number, data: RentalReturnDTO) {
  return post<void>(`/rental/${id}/return`, data)
}

/** 租赁记录分页（居民=本人，工作人员全量） */
export function pageRentals(query: RentalQueryDTO) {
  return get<PageResult<RentalOrderVO>>('/rental/page', query)
}

/** 租赁单详情 */
export function getRental(id: number) {
  return get<RentalOrderVO>(`/rental/${id}`)
}

/** 导出租赁记录 Excel（工作人员） */
export function exportRentals(query: RentalQueryDTO) {
  return download('/rental/export', query)
}
