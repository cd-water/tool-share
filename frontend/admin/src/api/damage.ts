/**
 * 报修单管理接口（/admin/damage-reports）。
 *
 * 覆盖报修单的查询、核验、维修进度更新与最终验收。
 * 这些操作会推进报修状态机，并可能联动工具状态与费用结算，
 * 调用方应在成功后刷新详情或列表，避免展示过期状态。
 */
import http from '@/api/request'
import type {
  DamageAcceptDTO,
  DamageProgressDTO,
  DamageQuery,
  DamageReportVO,
  DamageVerifyDTO,
  PageResult,
} from '@/api/types'

/**
 * 报修单 API 集合。
 *
 * 写操作（verify/progress/accept）返回 void，Promise resolve 即代表
 * 后端已完成状态流转；失败提示由统一拦截器负责。
 */
export const damageApi = {
  /**
   * 分页查询报修单。
   *
   * @param params 关键词、状态与分页参数；空串字段会被调用方清洗
   * @returns 分页结果（records + 总数等契约字段）
   * @throws 请求失败时由拦截器提示并 reject
   */
  list: (params: DamageQuery) =>
    http.get<PageResult<DamageReportVO>>('/admin/damage-reports', { params }),
  /**
   * 获取单条报修单详情。
   *
   * @param id 报修单 ID
   * @returns 报修单完整信息，含核验、维修与验收各阶段字段
   * @throws 报修单不存在时由拦截器提示并 reject
   */
  detail: (id: number) => http.get<DamageReportVO>(`/admin/damage-reports/${id}`),
  /**
   * 核验报修单（确认属实、疑似或驳回）。
   *
   * @param id 报修单 ID
   * @param data 核验结果与备注
   * @returns 无响应体，resolve 表示核验完成
   * @throws 状态不允许核验或参数非法时由拦截器提示并 reject
   */
  verify: (id: number, data: DamageVerifyDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/verify`, data),
  /**
   * 更新维修进度（等待配件、维修中、完成或失败）。
   *
   * @param id 报修单 ID
   * @param data 新进度状态与说明
   * @returns 无响应体，resolve 表示进度已保存
   * @throws 报修单未进入维修阶段时由拦截器提示并 reject
   */
  progress: (id: number, data: DamageProgressDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/progress`, data),
  /**
   * 验收维修结果，决定工具后续状态（合格、部分合格或不合格）。
   *
   * @param id 报修单 ID
   * @param data 验收结论、费用等结算信息
   * @returns 无响应体，resolve 表示验收完成
   * 验收可能联动工具状态恢复或报废，成功后应重新拉取工具与报修单。
   * @throws 验收条件不满足时由拦截器提示并 reject
   */
  accept: (id: number, data: DamageAcceptDTO) =>
    http.post<void>(`/admin/damage-reports/${id}/accept`, data),
}
