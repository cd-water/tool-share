/**
 * 租赁订单接口。
 *
 * 覆盖用户端租赁主流程：列表/详情查询、下单、支付、取消。
 * 归还与损坏申报分别由租赁详情页与 `damageApi` 完成。
 *
 * 约定：所有写操作成功后，调用方需失效租赁列表与对应详情缓存，
 * 因为订单状态会同时影响列表 badge、详情按钮可用性与工具可用性。
 */
import http from '@/api/request'
import type {
  PageParams,
  PageResult,
  Rental,
  RentalCreatePayload,
  RentalStatus,
} from '@/api/types'

/**
 * 租赁列表查询参数。
 *
 * keyword 由后端按工具名/订单号等模糊搜索；status 用于按订单状态筛选，
 * 取值来自 OpenAPI 契约派生的 `RentalStatus`。
 */
export interface RentalQuery extends PageParams {
  keyword?: string
  status?: RentalStatus
}

export const rentalsApi = {
  /**
   * 分页查询当前用户的租赁订单。
   *
   * @param params 分页 + 关键字 + 状态筛选
   * @returns 当前页订单及分页信息
   *
   * 缓存 key：`queryKeys.rentals.list(params)`。
   */
  list: (params: RentalQuery) =>
    http.get<PageResult<Rental>>('/user/rentals', { params }),

  /**
   * 查询订单详情。
   *
   * @param id 租赁订单 ID
   * @returns 含费用明细、支付记录、损坏结果、提醒时间等完整字段
   *
   * 详情页在支付/取消/归还后应失效 `queryKeys.rentals.detail(id)`。
   */
  detail: (id: number) => http.get<Rental>(`/user/rentals/${id}`),

  /**
   * 创建租赁订单。
   *
   * @param payload 工具 ID、租期、提醒设置等下单参数
   * @returns 新建订单（通常初始状态为待支付）
   *
   * 错误：工具不可用、时间冲突或信用不足等业务错误由请求层 toast。
   */
  create: (payload: RentalCreatePayload) =>
    http.post<Rental>('/user/rentals', payload),

  /**
   * 支付订单。
   *
   * @param id 订单 ID
   *
   * 副作用：后端完成扣款并把状态推进为已预约；前端需刷新订单详情、
   * 订单列表以及工具的可用性信息。
   */
  pay: (id: number) => http.post<void>(`/user/rentals/${id}/pay`),

  /**
   * 取消订单。
   *
   * @param id 订单 ID
   * @param reason 取消原因，写入订单供后台与工具方查看
   *
   * 副作用：后端根据支付状态决定是否退款；成功后同样需要刷新相关缓存。
   */
  cancel: (id: number, reason: string) =>
    http.post<void>(`/user/rentals/${id}/cancel`, { reason }),
}
