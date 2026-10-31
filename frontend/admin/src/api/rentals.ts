import http from '@/api/request'
import type { PageQuery, PageResult } from '@/api/types'

export type RentalStatus =
  | 'PENDING_PAYMENT'
  | 'RESERVED'
  | 'PICKED_UP'
  | 'RETURNED'
  | 'CANCELLED'
  | 'OVERDUE'

export type DepositStatus = 'UNPAID' | 'PAID' | 'REFUNDED'
export type DamageResult = 'INTACT' | 'MINOR' | 'SEVERE'

export interface RentalVO {
  id: number
  orderNo: string
  toolId: number
  toolCode: string
  toolName: string
  userId: number
  renterName: string
  renterPhone: string
  startTime: string
  endTime: string
  pickupPerson?: string
  depositAmount: number
  depositStatus: DepositStatus
  depositStatusName?: string
  status: RentalStatus
  statusName?: string
  actualReturnTime?: string
  overdueFee?: number
  damageFee?: number
  refundAmount?: number
  damageResult?: DamageResult
  damageResultName?: string
  returnRemark?: string
  cancelReason?: string
  cancelTime?: string
  createTime?: string
  updateTime?: string
}

export interface RentalQuery extends PageQuery {
  keyword?: string
  status?: string
  overdue?: string
}

export interface RentalReturnDTO {
  damageResult: DamageResult
  overdueFee?: number
  damageFee?: number
  remark?: string
}

export interface RentalCancelDTO {
  reason: string
}

export const rentalApi = {
  list: (params: RentalQuery) => http.get<PageResult<RentalVO>>('/admin/rentals', { params }),
  detail: (id: number) => http.get<RentalVO>(`/admin/rentals/${id}`),
  pickup: (id: number) => http.post<void>(`/admin/rentals/${id}/pickup`),
  return: (id: number, data: RentalReturnDTO) =>
    http.post<void>(`/admin/rentals/${id}/return`, data),
  cancel: (id: number, data: RentalCancelDTO) =>
    http.post<void>(`/admin/rentals/${id}/cancel`, data),
  export: (params: object) => http.download('/admin/rentals/export', params),
}
