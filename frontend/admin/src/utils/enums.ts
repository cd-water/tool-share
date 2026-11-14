import type {
  AcceptResult,
  AdminRole,
  DamageLevel,
  DamageReportStatus,
  DamageResult,
  DeliveryStatus,
  DepositStatus,
  MessageChannel,
  MessageType,
  ProgressStatus,
  RentalStatus,
  ToolCategory,
  ToolStatus,
  VerifyResult,
} from '@/api/types'

export type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const TOOL_CATEGORY_LABELS: Record<ToolCategory, string> = {
  WX: '维修',
  YY: '园艺',
  QJ: '清洁',
  QT: '其他',
}

export const DAMAGE_LEVEL_LABELS: Record<DamageLevel, string> = {
  NEW: '全新',
  LIGHT: '轻微磨损',
  MEDIUM: '中度磨损',
  SEVERE: '严重损坏',
}

export const TOOL_STATUS_LABELS: Record<ToolStatus, string> = {
  AVAILABLE: '可借用',
  RENTED: '已租出',
  MAINTENANCE: '维修中',
  SCRAPPED: '已报废',
}

/** 工具审核状态：数据库沿用 TINYINT 编码。 */
export const AUDIT_STATUS_LABELS: Record<number, string> = {
  0: '待审核',
  1: '已通过',
  2: '需修改',
}

/** 审核日志按枚举名记录，需要单独的标签映射。 */
export const AUDIT_STATUS_NAME_LABELS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  NEEDS_CHANGE: '需修改',
}

export function auditStatusLabel(code?: number | null): string {
  if (code === null || code === undefined) return '—'
  return AUDIT_STATUS_LABELS[code] ?? String(code)
}

export function auditStatusTag(code?: number | null): TagType {
  switch (code) {
    case 1:
      return 'success'
    case 2:
      return 'warning'
    case 0:
      return 'info'
    default:
      return 'info'
  }
}

export const RENTAL_STATUS_LABELS: Record<RentalStatus, string> = {
  PENDING_PAYMENT: '待支付',
  RESERVED: '已预约',
  PICKED_UP: '使用中',
  RETURNED: '已归还',
  CANCELLED: '已取消',
  OVERDUE: '已逾期',
}

export const DEPOSIT_STATUS_LABELS: Record<DepositStatus, string> = {
  UNPAID: '未支付',
  PAID: '已支付',
  REFUNDED: '已退款',
}

export const DAMAGE_RESULT_LABELS: Record<DamageResult, string> = {
  INTACT: '完好',
  MINOR: '轻微损坏',
  SEVERE: '严重损坏',
}

export const REPORT_STATUS_LABELS: Record<DamageReportStatus, string> = {
  SUBMITTED: '已提交',
  VERIFIED: '已核验',
  REJECTED: '已驳回',
  REPAIRING: '维修中',
  COMPLETED: '已完成',
  SCRAPPED: '已报废',
}

export const PROGRESS_STATUS_LABELS: Record<ProgressStatus, string> = {
  WAITING_PARTS: '等待配件',
  REPAIRING: '维修中',
  COMPLETED: '维修完成',
  FAILED: '维修失败',
}

export const ACCEPT_RESULT_LABELS: Record<AcceptResult, string> = {
  QUALIFIED: '合格',
  UNQUALIFIED: '不合格',
  PARTIAL: '部分合格',
}

export const VERIFY_RESULT_LABELS: Record<VerifyResult, string> = {
  CONFIRMED: '确认属实',
  SUSPECTED: '疑似损坏',
  FALSE: '不属实',
}

export const MESSAGE_TYPE_LABELS: Record<MessageType, string> = {
  RESERVATION: '预约通知',
  PICKUP: '取件提醒',
  RENT_DUE: '到期提醒',
  OVERDUE: '逾期提醒',
  REPAIR: '报修通知',
  TOOL_AVAILABLE: '蹲守到货',
  SYSTEM: '系统消息',
}

export const MESSAGE_CHANNEL_LABELS: Record<MessageChannel, string> = {
  SYSTEM: '站内消息',
  SMS: '短信',
}

export const DELIVERY_STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  DELIVERED: '已送达',
  FAILED: '发送失败',
}

export const ADMIN_ROLE_LABELS: Record<AdminRole, string> = {
  STAFF: '工作人员',
  SUPER_ADMIN: '系统管理员',
}

export const TOOL_LOG_ACTION_LABELS: Record<string, string> = {
  CREATE: '新增工具',
  UPDATE: '编辑工具',
  AUDIT: '审核',
  STATUS_CHANGE: '状态变更',
  RESTORE: '报废恢复',
  PICKUP: '确认取件',
  RETURN: '归还验收',
  REPAIR: '报修处理',
  DELETE: '删除工具',
}

export function enumLabel(map: Record<string, string>, value?: string | null): string {
  if (!value) return '—'
  return map[value] ?? value
}

export function optionList(map: Record<string, string>): Array<{ value: string; label: string }> {
  return Object.entries(map).map(([value, label]) => ({ value, label }))
}

export function toolStatusTag(status?: string): TagType {
  switch (status) {
    case 'AVAILABLE':
      return 'success'
    case 'RENTED':
      return 'primary'
    case 'MAINTENANCE':
      return 'warning'
    case 'SCRAPPED':
      return 'danger'
    default:
      return 'info'
  }
}

export function rentalStatusTag(status?: string): TagType {
  switch (status) {
    case 'PENDING_PAYMENT':
      return 'warning'
    case 'RESERVED':
      return 'primary'
    case 'PICKED_UP':
      return 'success'
    case 'RETURNED':
      return 'info'
    case 'CANCELLED':
      return 'info'
    case 'OVERDUE':
      return 'danger'
    default:
      return 'info'
  }
}

export function reportStatusTag(status?: string): TagType {
  switch (status) {
    case 'SUBMITTED':
      return 'warning'
    case 'VERIFIED':
      return 'primary'
    case 'REJECTED':
      return 'danger'
    case 'REPAIRING':
      return 'warning'
    case 'COMPLETED':
      return 'success'
    case 'SCRAPPED':
      return 'info'
    default:
      return 'info'
  }
}

export function progressStatusTag(status?: string): TagType {
  switch (status) {
    case 'WAITING_PARTS':
      return 'warning'
    case 'REPAIRING':
      return 'primary'
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

export function acceptResultTag(result?: string): TagType {
  switch (result) {
    case 'QUALIFIED':
      return 'success'
    case 'UNQUALIFIED':
      return 'danger'
    case 'PARTIAL':
      return 'warning'
    default:
      return 'info'
  }
}

export function damageLevelTag(level?: string): TagType {
  switch (level) {
    case 'NEW':
      return 'success'
    case 'LIGHT':
      return 'info'
    case 'MEDIUM':
      return 'warning'
    case 'SEVERE':
      return 'danger'
    default:
      return 'info'
  }
}

export function depositStatusTag(status?: string): TagType {
  switch (status) {
    case 'UNPAID':
      return 'warning'
    case 'PAID':
      return 'primary'
    case 'REFUNDED':
      return 'success'
    default:
      return 'info'
  }
}

export function deliveryStatusTag(status?: string | null): TagType {
  switch (status) {
    case 'DELIVERED':
      return 'success'
    case 'SENT':
      return 'primary'
    case 'PENDING':
      return 'warning'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

export function isReadStatus(value: unknown): boolean {
  return value === true || value === 1 || value === '1' || value === 'READ' || value === 'read'
}
