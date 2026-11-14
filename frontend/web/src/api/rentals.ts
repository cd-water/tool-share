import http from '@/api/request'
import type {
  PageParams,
  PageResult,
  Rental,
  RentalCreatePayload,
  RentalStatus,
} from '@/api/types'

export interface RentalQuery extends PageParams {
  keyword?: string
  status?: RentalStatus
}

export const rentalsApi = {
  list: (params: RentalQuery) =>
    http.get<PageResult<Rental>>('/user/rentals', { params }),
  detail: (id: number) => http.get<Rental>(`/user/rentals/${id}`),
  create: (payload: RentalCreatePayload) =>
    http.post<Rental>('/user/rentals', payload),
  pay: (id: number) => http.post<void>(`/user/rentals/${id}/pay`),
  cancel: (id: number, reason: string) =>
    http.post<void>(`/user/rentals/${id}/cancel`, { reason }),
}
