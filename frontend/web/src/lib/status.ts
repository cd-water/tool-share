/**
 * 业务枚举到 Badge 语义 tone 的映射。
 *
 * 页面只负责展示，不内联 switch；所有「状态颜色」的决策集中在这里，
 * 保证同一状态在列表、详情、消息等位置的视觉一致。
 *
 * 数据来源：枚举来自 OpenAPI 契约（见 `@/api/types`），新增状态时
 * TypeScript 不会强制这里补 case，因此 default 统一回落到 neutral，
 * 避免未知状态导致渲染异常；新状态上线时应同步补充映射。
 *
 * 与 `badgeClass` 的分工：本文件只输出语义 tone（success/warning/...），
 * 真正的 CSS class 由 `@/lib/badge` 负责，两个映射互不依赖，便于主题替换。
 *
 * 所有函数都是纯函数：相同输入必得相同输出，不读取 store、不发请求。
 */
import type { BadgeTone } from '@/components/ui/badge'
import type {
  DamageLevel,
  DamageReportStatus,
  MessageType,
  RentalStatus,
  ToolStatus,
} from '@/api/types'

/**
 * 工具状态 → 徽标颜色。
 *
 * @param status 工具状态枚举
 * @returns 语义 tone，供 `badgeClass` 转成设计系统 class
 *
 * 颜色语义：
 * - 可租：绿色（success），表示当前可操作；
 * - 已租出：黄色（warning），表示暂时不可用但会恢复；
 * - 维修中：蓝色（info），表示已知的临时状态；
 * - 已报废：红色（danger），表示永久不可用。
 */
export function toolStatusTone(status: ToolStatus): BadgeTone {
  switch (status) {
    case 'AVAILABLE':
      // 当前可直接下单。
      return 'success'
    case 'RENTED':
      // 被他人租用，可蹲守等待归还。
      return 'warning'
    case 'MAINTENANCE':
      // 维修中，恢复时间不确定。
      return 'info'
    case 'SCRAPPED':
      // 永久退出流通。
      return 'danger'
    default:
      // 契约新增状态时的安全兜底。
      return 'neutral'
  }
}

/**
 * 租赁订单状态 → 徽标颜色。
 *
 * @param status 订单状态枚举
 * @returns 语义 tone
 *
 * 覆盖下单到归还的完整生命周期：
 * 待支付/已预约表示「进行中」，已取件用品牌色强调，
 * 已归还是成功终态，逾期用危险色，取消为中性终态。
 */
export function rentalStatusTone(status: RentalStatus): BadgeTone {
  switch (status) {
    case 'PENDING_PAYMENT':
      // 需要用户操作的待办状态。
      return 'warning'
    case 'RESERVED':
      // 已支付、等待取件。
      return 'info'
    case 'PICKED_UP':
      // 使用中，是当前最需要关注的订单。
      return 'primary'
    case 'RETURNED':
      // 正常完结。
      return 'success'
    case 'OVERDUE':
      // 已逾期，可能产生费用与信用影响。
      return 'danger'
    case 'CANCELLED':
      // 用户或系统取消，无需进一步操作。
      return 'neutral'
    default:
      return 'neutral'
  }
}

/**
 * 损坏报告状态 → 徽标颜色。
 *
 * @param status 报告状态枚举
 * @returns 语义 tone
 *
 * 审批流越长，颜色越能帮助用户快速判断「是否需要我处理」：
 * 已提交等待审核用 info，审核通过用品牌色，维修中用 warning，
 * 完成用 success，驳回/报废用 danger。
 */
export function damageStatusTone(status: DamageReportStatus): BadgeTone {
  switch (status) {
    case 'SUBMITTED':
      // 等待管理员核验。
      return 'info'
    case 'VERIFIED':
      // 已核验，进入维修流程。
      return 'primary'
    case 'REPAIRING':
      // 维修进行中，费用/时间可能变化。
      return 'warning'
    case 'COMPLETED':
      // 流程正常结束。
      return 'success'
    case 'REJECTED':
    case 'SCRAPPED':
      // 驳回或工具报废，均属于负面终态。
      return 'danger'
    default:
      return 'neutral'
  }
}

/**
 * 消息类型 → 徽标颜色。
 *
 * @param type 站内消息类型枚举
 * @returns 语义 tone
 *
 * 用于消息列表左侧的类型标签，颜色表达紧急程度：
 * 预约/取件为提示性 info，临期用 warning，逾期用 danger，
 * 维修用品牌色，工具可用提醒用 success。
 */
export function messageTypeTone(type: MessageType): BadgeTone {
  switch (type) {
    case 'RESERVATION':
    case 'PICKUP':
      // 预约成功、待取件：提示性信息。
      return 'info'
    case 'RENT_DUE':
      // 临近归还时间，需要用户行动。
      return 'warning'
    case 'OVERDUE':
      // 已逾期，最高优先级。
      return 'danger'
    case 'REPAIR':
      // 维修进度类通知。
      return 'primary'
    case 'TOOL_AVAILABLE':
      // 蹲守到货提醒，属于好消息。
      return 'success'
    default:
      return 'neutral'
  }
}

/**
 * 工具损坏程度 → 徽标颜色。
 *
 * @param level 损坏等级枚举
 * @returns 语义 tone
 *
 * 从全新到严重损坏形成 green → blue → yellow → red 的渐进色阶，
 * 让用户在工具卡片上一眼判断成色。
 */
export function damageLevelTone(level: DamageLevel): BadgeTone {
  switch (level) {
    case 'NEW':
      // 近乎全新。
      return 'success'
    case 'LIGHT':
      // 轻微使用痕迹，不影响使用。
      return 'info'
    case 'MEDIUM':
      // 中度磨损，需留意。
      return 'warning'
    case 'SEVERE':
      // 严重损坏，可能影响安全或功能。
      return 'danger'
    default:
      return 'neutral'
  }
}
