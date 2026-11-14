/**
 * 工具管理接口（/admin/tools）。
 *
 * 覆盖工具的增删改查、审核、状态调整、报废恢复，
 * 以及单工具的租赁记录与操作日志。多数写操作仅系统管理员可用。
 */
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

/**
 * 工具 API 集合。
 *
 * 返回 ToolVO/PageResult 的方法直接产出展示数据；
 * 状态类方法返回 void，由调用方在成功后刷新列表或详情。
 */
export const toolApi = {
  /**
   * 分页查询工具。
   *
   * @param params 关键词、分类、状态、审核状态与分页参数
   * @returns 工具分页结果
   * @throws 请求失败时由拦截器提示并 reject
   */
  list: (params: ToolQuery) => http.get<PageResult<ToolVO>>('/admin/tools', { params }),
  /**
   * 新建工具。
   *
   * @param data 工具保存体，图片以 URL 数组提交
   * @returns 新建后的工具信息
   * 新建后通常处于待审核状态，需通过 audit 流转。
   * @throws 参数校验失败时由拦截器提示并 reject
   */
  create: (data: ToolSaveDTO) => http.post<ToolVO>('/admin/tools', data),
  /**
   * 编辑已有工具。
   *
   * @param id 工具 ID
   * @param data 与新建共用的保存体
   * @returns 更新后的工具信息
   * @throws 工具不存在或无权限时由拦截器提示并 reject
   */
  update: (id: number, data: ToolSaveDTO) => http.put<ToolVO>(`/admin/tools/${id}`, data),
  /**
   * 审核工具（通过 / 需修改）。
   *
   * @param id 工具 ID
   * @param data 审核结论与备注
   * @returns 审核后的工具信息
   * 审核结果会写入工具日志，可在 logs 中查看时间线。
   * @throws 状态不允许审核时由拦截器提示并 reject
   */
  audit: (id: number, data: ToolAuditDTO) =>
    http.patch<ToolVO>(`/admin/tools/${id}/audit`, data),
  /**
   * 调整工具状态（可借用、维修中、已报废等）。
   *
   * @param id 工具 ID
   * @param status 目标状态；以 DTO 的 status 字段类型收窄，避免枚举漂移
   * @returns 无响应体，resolve 表示状态已更新
   * @throws 当前状态不允许该流转时由拦截器提示并 reject
   */
  updateStatus: (id: number, status: ToolStatusDTO['status']) =>
    http.patch<void>(`/admin/tools/${id}/status`, { status } satisfies ToolStatusDTO),
  /**
   * 恢复已报废工具。
   *
   * @param id 工具 ID
   * @param data 恢复原因等补充信息
   * @returns 恢复后的工具信息
   * @throws 工具不是报废状态时由拦截器提示并 reject
   */
  restore: (id: number, data: ToolRestoreDTO) =>
    http.post<ToolVO>(`/admin/tools/${id}/restore`, data),
  /**
   * 查询指定工具的历史租赁记录。
   *
   * @param id 工具 ID
   * @param params 分页参数（页码从 1 开始）
   * @returns 该工具的租赁单分页结果
   * @throws 工具不存在时由拦截器提示并 reject
   */
  rentals: (id: number, params: { page: number; size: number }) =>
    http.get<PageResult<RentalVO>>(`/admin/tools/${id}/rentals`, { params }),
  /**
   * 获取指定工具的操作日志时间线。
   *
   * @param id 工具 ID
   * @returns 按时间排列的日志列表（新增、编辑、审核、状态变更等）
   * @throws 工具不存在时由拦截器提示并 reject
   */
  logs: (id: number) => http.get<ToolLogVO[]>(`/admin/tools/${id}/logs`),
}
