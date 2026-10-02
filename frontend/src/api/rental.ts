import type {
  PageResult,
  PayResult,
  RentalCreateData,
  RentalCreated,
  RentalOrderItem,
  RentalQuery,
  ReturnData,
  Settlement,
  Suggestion,
} from './types'
import { http, httpGet, httpPost } from '../utils/request'

export function createRental(data: RentalCreateData) {
  return httpPost<RentalCreated>('/rental/orders', data)
}

export function getSuggestion(params: { toolId: number; startTime: string; endTime: string }) {
  return httpGet<Suggestion>('/rental/orders/suggestion', params)
}

export function payRental(id: number) {
  return httpPost<PayResult>(`/rental/orders/${id}/pay`)
}

export function cancelRental(id: number) {
  return httpPost<void>(`/rental/orders/${id}/cancel`)
}

export function pickupRental(id: number, pickupPerson: string) {
  return httpPost<void>(`/rental/orders/${id}/pickup`, { pickupPerson })
}

export function returnRental(id: number, data: ReturnData) {
  return httpPost<Settlement>(`/rental/orders/${id}/return`, data)
}

export function myRentals(params: { page?: number; size?: number; status?: string }) {
  return httpGet<PageResult<RentalOrderItem>>('/rental/orders/my', params)
}

export function listRentals(params: RentalQuery) {
  return httpGet<PageResult<RentalOrderItem>>('/rental/orders', params)
}

/** Excel 导出（blob，信封不适用） */
export function exportRentals(params: RentalQuery) {
  return http<Blob>({ method: 'get', url: '/rental/orders/export', params, responseType: 'blob' })
}
