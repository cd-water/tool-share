import { get, post } from './request'
import type {
  PageResult,
  RepairAcceptDTO,
  RepairCreateDTO,
  RepairCreateVO,
  RepairOrderVO,
  RepairProgressDTO,
  RepairQueryDTO,
  RepairVerifyDTO,
} from './types'

/** 提交报修申请 */
export function createRepair(data: RepairCreateDTO) {
  return post<RepairCreateVO>('/repair', data)
}

/** 报修单分页 */
export function pageRepairs(query: RepairQueryDTO) {
  return get<PageResult<RepairOrderVO>>('/repair/page', query)
}

/** 报修单详情（含维修进度） */
export function getRepair(id: number) {
  return get<RepairOrderVO>(`/repair/${id}`)
}

/** 现场核验（工作人员） */
export function verifyRepair(id: number, data: RepairVerifyDTO) {
  return post<void>(`/repair/${id}/verify`, data)
}

/** 更新维修进度（工作人员） */
export function updateRepairProgress(id: number, data: RepairProgressDTO) {
  return post<void>(`/repair/${id}/progress`, data)
}

/** 维修验收（工作人员） */
export function acceptRepair(id: number, data: RepairAcceptDTO) {
  return post<void>(`/repair/${id}/accept`, data)
}
