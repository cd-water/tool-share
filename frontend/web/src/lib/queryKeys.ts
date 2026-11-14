/**
 * React Query 缓存 key 的唯一来源。
 *
 * 约定：`[领域, 范围, 参数...]`，并从粗到细排列。
 * 这样 `invalidateQueries({ queryKey: queryKeys.tools.all })` 可以按前缀
 * 一次性失效整个领域（列表、详情、蹲守状态等），而
 * `queryKeys.tools.detail(id)` 又能精确定位单个工具的缓存。
 *
 * 注意事项：
 * - React Query 会对参数对象做稳定序列化（与对象引用无关），因此只需保证
 *   不同查询的参数值确实不同，不要把时间戳等每次变化的值放进 key；
 * - 所有 key 都加 `as const`，让 TypeScript 推导出只读元组，避免被误改；
 * - id 允许为 null，便于详情页在路由参数尚未就绪时先构造 key。
 */
import type { DamageReportQuery } from '@/api/damage'
import type { MessageQuery } from '@/api/messages'
import type { RentalQuery } from '@/api/rentals'
import type { ToolQuery } from '@/api/tools'
import type { PageParams } from '@/api/types'

/**
 * 统一 query key 约定：`[领域, 范围, 参数...]`。
 * 失效时只传领域前缀（如 `queryKeys.tools.all`）即可清除该领域全部缓存。
 */
/**
 * 使用示例：
 * - 精确失效某个列表：`invalidateQueries({ queryKey: queryKeys.rentals.list(params) })`
 * - 失效整个领域：`invalidateQueries({ queryKey: queryKeys.rentals.all })`
 * - 读取详情缓存：`getQueryData(queryKeys.rentals.detail(id))`
 *
 * 注意 `all` 前缀必须是其他 key 的前缀，否则批量失效不会命中。
 */
export const queryKeys = {
  /** 首页看板：聚合统计与待办，无参数。 */
  dashboard: ['dashboard'] as const,

  user: {
    /** 当前登录用户的基础信息，登录态恢复时使用。 */
    info: ['user', 'info'] as const,
  },

  /** 完整用户资料页数据。 */
  profile: ['profile'] as const,

  tools: {
    /** 工具领域前缀：失效它会同时清掉列表、详情及派生查询。 */
    all: ['tools'] as const,
    /** 工具列表：参数（搜索/筛选/分页）不同即不同缓存。 */
    list: (params: ToolQuery) => ['tools', 'list', params] as const,
    /** 单个工具详情。 */
    detail: (id: number | null) => ['tools', 'detail', id] as const,
    /** 当前用户对某工具的蹲守状态，挂在详情 key 之下便于一起失效。 */
    watch: (id: number | null) => ['tools', 'detail', id, 'watch'] as const,
    /** 工具历史租赁时间窗。 */
    history: (id: number | null) => ['tools', 'detail', id, 'history'] as const,
    /** 指定时间段的可用性查询，时间参数参与 key 以保证结果隔离。 */
    availability: (id: number | null, startTime: string, endTime: string) =>
      ['tools', 'detail', id, 'availability', startTime, endTime] as const,
  },

  rentals: {
    /** 租赁领域前缀。 */
    all: ['rentals'] as const,
    /** 租赁订单列表。 */
    list: (params: RentalQuery) => ['rentals', 'list', params] as const,
    /** 单个订单详情。 */
    detail: (id: number | null) => ['rentals', 'detail', id] as const,
  },

  damageReports: {
    /** 损坏报告领域前缀。 */
    all: ['damage-reports'] as const,
    /** 损坏报告列表。 */
    list: (params: DamageReportQuery) => ['damage-reports', 'list', params] as const,
    /** 单条损坏报告详情。 */
    detail: (id: number | null) => ['damage-reports', 'detail', id] as const,
  },

  messages: {
    /** 消息领域前缀，失效时同时影响列表与未读数。 */
    all: ['messages'] as const,
    /** 消息列表（分页）。 */
    list: (params: MessageQuery) => ['messages', 'list', params] as const,
    /** 未读消息数，供导航栏角标轮询。 */
    unreadCount: ['messages', 'unread-count'] as const,
  },

  /** 通知偏好列表，更新后整体失效即可。 */
  notificationPrefs: ['notification-prefs'] as const,

  credit: {
    /** 信用领域前缀。 */
    all: ['credit'] as const,
    /** 信用概览 + 事件分页。 */
    overview: (params: PageParams) => ['credit', 'overview', params] as const,
  },

  reviews: {
    /** 评价领域前缀。 */
    all: ['reviews'] as const,
    /** 当前用户的评价列表。 */
    list: (params: PageParams) => ['reviews', 'list', params] as const,
  },
}
