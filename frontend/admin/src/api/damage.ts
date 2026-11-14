import http from '@/api/request'
import type {
  DamageAcceptDTO,
  DamageProgressDTO,
  DamageQuery,
  DamageReportVO,
  DamageVerifyDTO,
  PageResult,
} from '@/api/types'

export const damageApi = {
  list: (params: DamageQuery) =>
    http.get<PageResult<DamageReportVO>>('/admin/damage-reports', { params }),
  detail: (id: number) => http.get<DamageReportVO>(`/admin/damage-reports/${id}`),
  verify: (id: number, data: DamageVerifyDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/verify`, data),
  progress: (id: number, data: DamageProgressDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/progress`, data),
  accept: (id: number, data: DamageAcceptDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/accept`, data),
}
