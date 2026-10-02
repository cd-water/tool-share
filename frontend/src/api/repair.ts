import type {
  PageResult,
  RepairCreateData,
  RepairCreated,
  RepairDetail,
  RepairItem,
  RepairQuery,
} from './types'
import { httpGet, httpPost } from '../utils/request'

export function createRepair(data: RepairCreateData) {
  return httpPost<RepairCreated>('/repair/orders', data)
}

export function myRepairs(params: { page?: number; size?: number; status?: string }) {
  return httpGet<PageResult<RepairItem>>('/repair/orders/my', params)
}

export function listRepairs(params: RepairQuery) {
  return httpGet<PageResult<RepairItem>>('/repair/orders', params)
}

export function getRepair(id: number) {
  return httpGet<RepairDetail>(`/repair/orders/${id}`)
}

export function verifyRepair(id: number, data: { result: string; rejectReason?: string }) {
  return httpPost<void>(`/repair/orders/${id}/verify`, data)
}

export function startRepair(id: number, data: { handlerId?: number; estimatedDuration?: string; status?: string }) {
  return httpPost<void>(`/repair/orders/${id}/repair`, data)
}

export function addRepairProgress(id: number, content: string) {
  return httpPost<void>(`/repair/orders/${id}/progress`, { content })
}

export function acceptRepair(id: number, data: {
  result: string
  repairDescription: string
  repairFee?: number
  damageLevel: string
}) {
  return httpPost<void>(`/repair/orders/${id}/accept`, data)
}
