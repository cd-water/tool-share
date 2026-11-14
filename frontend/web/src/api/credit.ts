/**
 * 信用分相关接口。
 *
 * 信用概览包含总分与信用事件分页列表，因此查询参数直接复用统一的
 * `PageParams`；分页结构 `PageResult` 由响应 envelope 的 data 字段给出。
 */
import http from '@/api/request'
import type { CreditOverview, PageParams } from '@/api/types'

export const creditApi = {
  /**
   * 查询信用概览及信用事件流水。
   *
   * @param params 分页参数（page/size），缺省由后端使用默认页大小
   * @returns 信用总分、等级说明以及当前页的加减分事件
   *
   * 错误：未登录（C401）由请求层统一清理会话；其余业务错误统一 toast。
   */
  overview: (params: PageParams) =>
    http.get<CreditOverview>('/user/credit', { params }),
}
