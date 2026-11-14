/**
 * 损坏报告接口。
 *
 * 用户端可以：分页查看自己相关的报告、查看单条详情、在归还/使用过程中
 * 提交新的损坏报告。报告后续的审核、维修、验收等流转由后台完成，
 * 前端只读取对应状态字段（status/progressStatus/acceptResult）。
 */
import http from '@/api/request'
import type {
  DamageReport,
  DamageReportCreatePayload,
  DamageReportStatus,
  PageParams,
  PageResult,
} from '@/api/types'

/**
 * 损坏报告列表查询参数。
 *
 * 在通用分页参数上增加关键字与状态筛选；关键字由后端按工具名/报告描述
 * 等字段模糊匹配，status 对应 `DamageReportStatus` 枚举。
 */
export interface DamageReportQuery extends PageParams {
  keyword?: string
  status?: DamageReportStatus
}

export const damageApi = {
  /**
   * 分页查询损坏报告。
   *
   * @param params 分页 + 关键字 + 状态筛选，作为 axios params 原样传给后端
   * @returns 当前页报告记录与分页元信息
   *
   * React Query 的缓存 key 由 `queryKeys.damageReports.list(params)` 生成，
   * 参数对象变化会产生新的缓存条目。
   */
  list: (params: DamageReportQuery) =>
    http.get<PageResult<DamageReport>>('/user/damage-reports', { params }),

  /**
   * 查询单条损坏报告详情。
   *
   * @param id 报告 ID
   * @returns 含进度、验收结果、费用等完整字段
   *
   * 错误：报告不存在或不属于当前用户时后端返回业务错误，统一请求层 toast。
   */
  detail: (id: number) => http.get<DamageReport>(`/user/damage-reports/${id}`),

  /**
   * 提交损坏报告。
   *
   * @param payload 关联租赁/工具、损坏程度、描述与图片等
   * @returns 新建后的报告（包含后端生成的 ID 与初始状态）
   *
   * 副作用：成功后应由调用方使报告列表与相关租赁详情缓存失效。
   */
  create: (payload: DamageReportCreatePayload) =>
    http.post<DamageReport>('/user/damage-reports', payload),
}
