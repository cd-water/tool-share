/**
 * 工具浏览与可用性接口。
 *
 * 工具列表/详情是只读查询；`watch` 系列接口提供「蹲守」能力：
 * 当工具归还变为可用时，后端会按通知偏好提醒用户。
 * 租赁历史与可用性查询用于详情页展示占用时间轴和下单校验。
 */
import http from '@/api/request'
import type {
  AvailabilityResult,
  PageParams,
  PageResult,
  Tool,
  ToolCategory,
  ToolRentalHistory,
  ToolStatus,
} from '@/api/types'

/**
 * 工具列表查询参数。
 *
 * - keyword：按名称/标签模糊搜索
 * - category/status：枚举筛选，取值由 OpenAPI 契约派生
 * - orderBy：后端支持的排序字段，如评分、最新上架等
 */
export interface ToolQuery extends PageParams {
  keyword?: string
  category?: ToolCategory
  status?: ToolStatus
  orderBy?: string
}

export const toolsApi = {
  /**
   * 分页查询工具列表。
   *
   * @param params 搜索、筛选、排序与分页参数
   * @returns 当前页工具与分页信息
   *
   * 缓存 key：`queryKeys.tools.list(params)`；筛选变化即产生新缓存条目。
   */
  list: (params: ToolQuery) =>
    http.get<PageResult<Tool>>('/user/tools', { params }),

  /**
   * 查询工具详情。
   *
   * @param id 工具 ID
   * @returns 工具完整信息（描述、图片、损坏等级、评分等）
   *
   * 缓存 key：`queryKeys.tools.detail(id)`。
   */
  detail: (id: number) => http.get<Tool>(`/user/tools/${id}`),

  /**
   * 查询工具的历史租赁时间窗。
   *
   * @param id 工具 ID
   * @returns 已完成租赁次数与各时间窗，用于详情页时间轴展示
   *
   * 注意：这里只暴露已脱敏的时间窗，不包含其他租客的身份信息。
   */
  history: (id: number) => http.get<ToolRentalHistory>(`/user/tools/${id}/history`),

  /**
   * 查询指定时间段内工具是否可用。
   *
   * @param id 工具 ID
   * @param startTime 租期开始时间（ISO 字符串）
   * @param endTime 租期结束时间（ISO 字符串）
   * @returns 可用性结果与冲突时间窗
   *
   * 下单前调用，用于禁用不可用时段；后端仍会在创建订单时二次校验。
   */
  availability: (id: number, startTime: string, endTime: string) =>
    http.get<AvailabilityResult>(`/user/tools/${id}/availability`, {
      params: { startTime, endTime },
    }),

  /** 蹲守（工具可用时提醒我） */
  /** 开启蹲守：工具变为可用时按通知偏好提醒当前用户。 */
  watch: (id: number) => http.post<void>(`/user/tools/${id}/watch`),

  /** 关闭蹲守：取消该工具的可用提醒。 */
  unwatch: (id: number) => http.delete<void>(`/user/tools/${id}/watch`),

  /**
   * 查询当前用户是否已蹲守该工具。
   *
   * @returns `{ watched: boolean }`，用于详情页按钮状态
   *
   * 缓存 key：`queryKeys.tools.watch(id)`。
   */
  watched: (id: number) => http.get<{ watched: boolean }>(`/user/tools/${id}/watch`),
}
