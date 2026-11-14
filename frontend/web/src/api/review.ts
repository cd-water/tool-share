import http from '@/api/request'
import type {
  PageParams,
  PageResult,
  Review,
  ReviewCreatePayload,
} from '@/api/types'

export const reviewApi = {
  list: (params: PageParams) =>
    http.get<PageResult<Review>>('/user/reviews', { params }),
  create: (payload: ReviewCreatePayload) =>
    http.post<Review>('/user/reviews', payload),
}
