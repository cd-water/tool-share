import http from '@/api/request'
import type {
  DamageReport,
  DamageReportCreatePayload,
  PageResult,
} from '@/api/types'

export interface DamageReportQuery {
  page?: number
  size?: number
}

export const damageApi = {
  list: (params: DamageReportQuery) =>
    http.get<PageResult<DamageReport>>('/user/damage-reports', { params }),
  detail: (id: number) => http.get<DamageReport>(`/user/damage-reports/${id}`),
  create: (payload: DamageReportCreatePayload) =>
    http.post<DamageReport>('/user/damage-reports', payload),
}
