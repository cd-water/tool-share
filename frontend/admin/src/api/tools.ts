import http from '@/api/request'
import type { PageQuery, PageResult } from '@/api/types'

export type ToolCategory = 'WX' | 'YY' | 'QJ' | 'QT'
export type DamageLevel = 'NEW' | 'LIGHT' | 'MEDIUM' | 'SEVERE'
export type ToolStatus = 'AVAILABLE' | 'RENTED' | 'MAINTENANCE' | 'SCRAPPED'

export interface ToolVO {
  id: number
  code: string
  name: string
  model?: string
  category: ToolCategory
  categoryName?: string
  damageLevel: DamageLevel
  damageLevelName?: string
  status: ToolStatus
  statusName?: string
  deposit: number
  storageArea?: string
  usageGuide?: string
  imageUrl?: string
  imageUrls?: string[]
  purchaseDate?: string
  supplier?: string
  rentalCount?: number
  lastRepairTime?: string
  createTime?: string
  updateTime?: string
}

export interface ToolQuery extends PageQuery {
  keyword?: string
  category?: string
  status?: string
  storageArea?: string
}

export interface ToolSaveDTO {
  name: string
  model?: string
  category: ToolCategory
  damageLevel: DamageLevel
  deposit: number
  storageArea?: string
  usageGuide?: string
  imageUrls: string[]
  purchaseDate?: string | null
  supplier?: string
  status?: ToolStatus
}

export interface ToolLogVO {
  id: number
  toolId: number
  action: string
  detail?: string
  operatorId?: number
  createTime?: string
}

export const toolApi = {
  list: (params: ToolQuery) => http.get<PageResult<ToolVO>>('/admin/tools', { params }),
  create: (data: ToolSaveDTO) => http.post<ToolVO>('/admin/tools', data),
  update: (id: number, data: ToolSaveDTO) => http.put<ToolVO>(`/admin/tools/${id}`, data),
  updateStatus: (id: number, status: ToolStatus) =>
    http.patch<void>(`/admin/tools/${id}/status`, { status }),
  logs: (id: number) => http.get<ToolLogVO[]>(`/admin/tools/${id}/logs`),
}
