import http from '@/api/request'
import type {
  PageResult,
  RentalCancelDTO,
  RentalQuery,
  RentalReturnDTO,
  RentalVO,
} from '@/api/types'

export const rentalApi = {
  list: (params: RentalQuery) =>
    http.get<PageResult<RentalVO>>('/admin/rentals', { params }),
  detail: (id: number) => http.get<RentalVO>(`/admin/rentals/${id}`),
  pickup: (id: number) => http.post<void>(`/admin/rentals/${id}/pickup`),
  return: (id: number, data: RentalReturnDTO) =>
    http.post<void>(`/admin/rentals/${id}/return`, data),
  cancel: (id: number, data: RentalCancelDTO) =>
    http.post<void>(`/admin/rentals/${id}/cancel`, data),
  export: (params: object) => http.download('/admin/rentals/export', params),
}
