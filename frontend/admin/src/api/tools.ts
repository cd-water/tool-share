import http from '@/api/request'
import type {
  PageResult,
  RentalVO,
  ToolAuditDTO,
  ToolLogVO,
  ToolQuery,
  ToolRestoreDTO,
  ToolSaveDTO,
  ToolStatusDTO,
  ToolVO,
} from '@/api/types'

export const toolApi = {
  list: (params: ToolQuery) => http.get<PageResult<ToolVO>>('/admin/tools', { params }),
  create: (data: ToolSaveDTO) => http.post<ToolVO>('/admin/tools', data),
  update: (id: number, data: ToolSaveDTO) => http.put<ToolVO>(`/admin/tools/${id}`, data),
  audit: (id: number, data: ToolAuditDTO) =>
    http.patch<ToolVO>(`/admin/tools/${id}/audit`, data),
  updateStatus: (id: number, status: ToolStatusDTO['status']) =>
    http.patch<void>(`/admin/tools/${id}/status`, { status } satisfies ToolStatusDTO),
  restore: (id: number, data: ToolRestoreDTO) =>
    http.post<ToolVO>(`/admin/tools/${id}/restore`, data),
  rentals: (id: number, params: { page: number; size: number }) =>
    http.get<PageResult<RentalVO>>(`/admin/tools/${id}/rentals`, { params }),
  logs: (id: number) => http.get<ToolLogVO[]>(`/admin/tools/${id}/logs`),
}
