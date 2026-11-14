/**
 * 评价接口。
 *
 * 用户可以对已完成的租赁提交评分与文字评价，评价会参与工具排序与推荐；
 * 列表接口用于「我的评价」页面分页展示。
 */
import http from '@/api/request'
import type {
  PageParams,
  PageResult,
  Review,
  ReviewCreatePayload,
} from '@/api/types'

export const reviewApi = {
  /**
   * 分页查询当前用户发表过的评价。
   *
   * @param params 分页参数
   * @returns 当前页评价及分页信息
   *
   * 缓存 key：`queryKeys.reviews.list(params)`。
   */
  list: (params: PageParams) =>
    http.get<PageResult<Review>>('/user/reviews', { params }),

  /**
   * 提交评价。
   *
   * @param payload 关联订单、评分、评价内容等
   * @returns 新建评价
   *
   * 副作用：评价会影响工具评分与推荐结果，成功后应失效评价列表，
   * 并视页面情况失效对应工具详情缓存。
   */
  create: (payload: ReviewCreatePayload) =>
    http.post<Review>('/user/reviews', payload),
}
