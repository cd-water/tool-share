import http from '@/api/request'
import type {
  DamageReport,
  DamageReportCreatePayload,
  DamageReportStatus,
  PageParams,
  PageResult,
} from '@/api/types'

export interface DamageReportQuery extends PageParams {
  keyword?: string
  status?: DamageReportStatus
}

export const damageApi = {
  list: (params: DamageReportQuery) =>
    http.get<PageResult<DamageReport>>('/user/damage-reports', { params }),
  detail: (id: number) => http.get<DamageReport>(`/user/damage-reports/${id}`),
  create: (payload: DamageReportCreatePayload) =>
    http.post<DamageReport>('/user/damage-reports', payload),
}
