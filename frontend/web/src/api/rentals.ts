import http from '@/api/request'
import type {
  PageResult,
  Rental,
  RentalCreatePayload,
} from '@/api/types'

export interface RentalQuery {
  status?: string
  page?: number
  size?: number
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
