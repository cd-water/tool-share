import http from '@/api/request'
import type { PageQuery, PageResult } from '@/api/types'

export type ReportStatus =
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'REJECTED'
  | 'REPAIRING'
  | 'COMPLETED'
  | 'SCRAPPED'

export type ProgressStatus = 'WAITING_PARTS' | 'REPAIRING' | 'COMPLETED' | 'FAILED'
export type AcceptResult = 'QUALIFIED' | 'UNQUALIFIED' | 'PARTIAL'
export type VerifyResult = 'CONFIRMED' | 'SUSPECTED' | 'FALSE'
export type ToolStatusAfter = 'AVAILABLE' | 'MAINTENANCE' | 'SCRAPPED'

export interface DamageReportVO {
  id: number
  reportNo: string
  rentalId?: number
  toolId: number
  toolCode?: string
  toolName?: string
  userId?: number
  reporterName?: string
  reporterPhone?: string
  damagePart?: string
  description?: string
  images?: string[]
  status: ReportStatus
  statusName?: string
  verifier?: string
  verifyTime?: string
  verifyResult?: VerifyResult
  repairPerson?: string
  repairEstimate?: string
  repairStartTime?: string
  repairCost?: number
  progressStatus?: ProgressStatus
  progressStatusName?: string
  progressUpdateTime?: string
  progressNote?: string
  acceptor?: string
  acceptTime?: string
  acceptResult?: AcceptResult
  acceptResultName?: string
  toolStatusAfter?: string
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface DamageQuery extends PageQuery {
  keyword?: string
  status?: string
}

export interface DamageVerifyDTO {
  verifyResult: VerifyResult
  repairPerson?: string
  repairEstimate?: string
  remark?: string
}

export interface DamageProgressDTO {
  progressStatus: ProgressStatus
  progressNote: string
  repairCost?: number
  repairEstimate?: string
  repairPerson?: string
}

export interface DamageAcceptDTO {
  acceptResult: AcceptResult
  toolStatusAfter: ToolStatusAfter
  remark?: string
  repairCost?: number
}

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
