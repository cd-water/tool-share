import { get, post, put } from './request'
import type {
  PageResult,
  Tool,
  ToolAuditDTO,
  ToolCreateVO,
  ToolDTO,
  ToolQueryDTO,
  ToolStatusDTO,
  ToolStatusLog,
} from './types'

/** 工具分页筛选 */
export function pageTools(query: ToolQueryDTO) {
  return get<PageResult<Tool>>('/tool/page', query)
}

/** 工具详情 */
export function getTool(id: number) {
  return get<Tool>(`/tool/${id}`)
}

/** 创建工具（编号自动生成） */
export function createTool(data: ToolDTO) {
  return post<ToolCreateVO>('/tool', data)
}

/** 更新工具信息 */
export function updateTool(id: number, data: ToolDTO) {
  return put<void>(`/tool/${id}`, data)
}

/** 更新工具状态（自动记录状态日志） */
export function updateToolStatus(id: number, data: ToolStatusDTO) {
  return put<void>(`/tool/${id}/status`, data)
}

/** 录入审核（管理员） */
export function auditTool(id: number, data: ToolAuditDTO) {
  return put<void>(`/tool/${id}/audit`, data)
}

/** 工具状态变更日志 */
export function getToolStatusLogs(id: number) {
  return get<ToolStatusLog[]>(`/tool/${id}/status-logs`)
}
