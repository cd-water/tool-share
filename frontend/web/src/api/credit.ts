import http from '@/api/request'
import type { CreditOverview, PageParams } from '@/api/types'

export const creditApi = {
  overview: (params: PageParams) =>
    http.get<CreditOverview>('/user/credit', { params }),
}
