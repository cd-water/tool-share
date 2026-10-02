import type {
  Availability,
  CategoryItem,
  PageResult,
  ToolCreateData,
  ToolCreated,
  ToolDetail,
  ToolImage,
  ToolItem,
  ToolQuery,
} from './types'
import { httpDelete, httpGet, httpPost, httpPut } from '../utils/request'

export function listCategories() {
  return httpGet<CategoryItem[]>('/tool/categories')
}

export function listTools(params: ToolQuery) {
  return httpGet<PageResult<ToolItem>>('/tool/tools', params)
}

export function getTool(id: number) {
  return httpGet<ToolDetail>(`/tool/tools/${id}`)
}

export function getAvailability(id: number, date: string) {
  return httpGet<Availability>(`/tool/tools/${id}/availability`, { date })
}

export function createTool(data: ToolCreateData) {
  return httpPost<ToolCreated>('/tool/tools', data)
}

export function auditTool(id: number, data: { result: 'APPROVED' | 'REJECTED'; auditRemark?: string }) {
  return httpPost<void>(`/tool/tools/${id}/audit`, data)
}

export function bindToolImages(id: number, urls: string[]) {
  return httpPost<ToolImage[]>(`/tool/tools/${id}/images`, { urls })
}

export function removeToolImage(imageId: number) {
  return httpDelete<void>(`/tool/images/${imageId}`)
}

export function changeToolStatus(id: number, data: { status: string; reason: string }) {
  return httpPut<void>(`/tool/tools/${id}/status`, data)
}
