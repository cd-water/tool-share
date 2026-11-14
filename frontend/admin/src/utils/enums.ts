/**
 * 枚举展示与语义标签映射。
 *
 * 该文件集中维护「后端枚举值 → 中文文案 → 徽标语义」：
 * - *_LABELS 只负责展示，key 必须与后端契约枚举保持一致；
 * - *Tag 函数只返回语义类型，具体颜色由 badge.ts 映射到设计系统；
 * - 所有查询函数对空值返回「—」、对未知值回退原值，
 *   保证后端新增枚举时界面不会出现空白或报错。
 */
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

/**
 * 业务语义标签类型。
 *
 * 与 Element Plus 的 type 对齐，再由 badge.ts 映射为设计系统样式；
 * 业务代码只关心语义（成功/警告/危险），不直接写颜色。
 */
export type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

/**
 * 工具分类中文名。
 *
 * key 来自契约 ToolCategory（WX/YY/QJ/QT），value 仅用于展示；
 * 筛选下拉、表格列和详情页都复用该映射。
 */
export const TOOL_CATEGORY_LABELS: Record<ToolCategory, string> = {
  // 维修类：水电、木工等维修工具。
  WX: '维修',
  // 园艺类：修剪、浇灌等户外工具。
  YY: '园艺',
  // 清洁类：扫除、清洗设备。
  QJ: '清洁',
  // 未归入以上分类的工具。
  QT: '其他',
}

/**
 * 工具磨损程度中文名。
 *
 * 描述工具当前品相（全新→严重损坏），用于工具详情与借用前的状态提示；
 * 与报修单的 DamageResult 不是同一概念，注意区分。
 */
export const DAMAGE_LEVEL_LABELS: Record<DamageLevel, string> = {
  // 全新：几乎无使用痕迹。
  NEW: '全新',
  // 轻微磨损：不影响正常使用。
  LIGHT: '轻微磨损',
  // 中度磨损：需要留意但通常仍可用。
  MEDIUM: '中度磨损',
  SEVERE: '严重损坏',
}

/**
 * 工具生命周期状态中文名。
 *
 * 状态影响可执行操作：只有 AVAILABLE 可发起租赁，
 * SCRAPPED 需走恢复流程；徽标颜色由 toolStatusTag 决定。
 */
export const TOOL_STATUS_LABELS: Record<ToolStatus, string> = {
  AVAILABLE: '可借用',
  RENTED: '已租出',
  MAINTENANCE: '维修中',
  SCRAPPED: '已报废',
}

/**
 * 工具审核状态中文名（数字编码）。
 *
 * 数据库沿用 TINYINT 编码：0 待审核、1 已通过、2 需修改，
 * 因此这里用 number 作 key；仅在后端返回数字编码时使用。
 * 日志/契约返回枚举名的场景请用 AUDIT_STATUS_NAME_LABELS。
 */
export const AUDIT_STATUS_LABELS: Record<number, string> = {
  // 0：尚未审核，详情页显示待审核。
  0: '待审核',
  // 1：审核通过，可进入正常业务流程。
  1: '已通过',
  // 2：需修改后重新提交审核。
  2: '需修改',
}

/**
 * 审核状态中文名（枚举名）。
 *
 * 审核日志按枚举名记录（PENDING/APPROVED/NEEDS_CHANGE），
 * 与数字编码的 AUDIT_STATUS_LABELS 分开维护，避免两者混用。
 */
export const AUDIT_STATUS_NAME_LABELS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  NEEDS_CHANGE: '需修改',
}

/**
 * 把数字审核状态编码转成中文。
 *
 * @param code 数据库 TINYINT 编码，允许 null/undefined
 * @returns 中文标签；空值返回「—」，未知编码回退为字符串本身
 * 回退原文而不是显示「未知」，便于发现后端新增的编码。
 */
export function auditStatusLabel(code?: number | null): string {
  if (code === null || code === undefined) return '—'
  return AUDIT_STATUS_LABELS[code] ?? String(code)
}

/**
 * 数字审核状态对应的徽标语义。
 *
 * @param code 审核状态编码
 * @returns 通过→success、需修改→warning、待审核/未知→info
 */
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

/**
 * 租赁单状态中文名。
 *
 * 对应租赁状态机：待支付→已预约→使用中→已归还，
 * 另有取消与逾期分支；状态决定可执行动作（取件、归还、取消）。
 */
export const RENTAL_STATUS_LABELS: Record<RentalStatus, string> = {
  // 尚未完成押金/租金支付。
  PENDING_PAYMENT: '待支付',
  // 支付完成并锁定库存，等待取件。
  RESERVED: '已预约',
  // 已取件；是否逾期由后端按归还时间标记。
  PICKED_UP: '使用中',
  // 已完成归还验收。
  RETURNED: '已归还',
  // 预约取消，可能同时触发退款。
  CANCELLED: '已取消',
  // 超过归还时间，用危险色提醒管理员跟进。
  OVERDUE: '已逾期',
}

/**
 * 押金状态中文名。
 *
 * 展示押金从「未支付」到「已支付 / 已退款」的流转；
 * 与租赁状态相互独立，退款可能滞后于归还。
 */
export const DEPOSIT_STATUS_LABELS: Record<DepositStatus, string> = {
  // 尚未支付押金。
  UNPAID: '未支付',
  // 押金已支付并冻结。
  PAID: '已支付',
  // 押金已退回用户。
  REFUNDED: '已退款',
}

/**
 * 归还验收的损坏结论中文名。
 *
 * 由管理员在归还时填写，影响押金扣减与报修单创建；
 * 与 DAMAGE_LEVEL_LABELS（工具品相）语义不同。
 */
export const DAMAGE_RESULT_LABELS: Record<DamageResult, string> = {
  // 归还时检查无损坏。
  INTACT: '完好',
  // 轻微损坏，通常仅扣减部分押金。
  MINOR: '轻微损坏',
  SEVERE: '严重损坏',
}

/**
 * 报修单主状态中文名。
 *
 * 覆盖提交、核验、维修、完成/报废的完整流程；
 * 列表筛选项与状态徽标共用该映射。
 */
export const REPORT_STATUS_LABELS: Record<DamageReportStatus, string> = {
  // 用户刚提交，等待管理员核验。
  SUBMITTED: '已提交',
  // 核验通过，等待进入维修。
  VERIFIED: '已核验',
  // 核验不通过，报修流程结束。
  REJECTED: '已驳回',
  // 维修进行中，可持续更新进度。
  REPAIRING: '维修中',
  // 维修完成并通过验收。
  COMPLETED: '已完成',
  SCRAPPED: '已报废',
}

/**
 * 维修进度子状态中文名。
 *
 * 仅在报修单进入维修阶段后出现，用于描述维修内部进展；
 * 与主状态 REPORT_STATUS_LABELS 配合展示。
 */
export const PROGRESS_STATUS_LABELS: Record<ProgressStatus, string> = {
  // 缺少配件，维修暂时停摆。
  WAITING_PARTS: '等待配件',
  // 正在实际维修。
  REPAIRING: '维修中',
  // 维修结束，等待最终验收。
  COMPLETED: '维修完成',
  // 维修失败，可能需要转报废。
  FAILED: '维修失败',
}

/**
 * 维修验收结论中文名。
 *
 * 验收是报修流程最后一步，结论决定工具恢复可用还是继续处理；
 * 颜色语义见 acceptResultTag。
 */
export const ACCEPT_RESULT_LABELS: Record<AcceptResult, string> = {
  // 维修质量达标，工具可恢复使用。
  QUALIFIED: '合格',
  // 未达标，需要返修或另行处理。
  UNQUALIFIED: '不合格',
  // 部分功能可用，按约定折中处理。
  PARTIAL: '部分合格',
}

/**
 * 报修核验结论中文名。
 *
 * 管理员核验用户提交的报修时填写；
 * 「不属实」会驳回报修，因此用危险色展示。
 */
export const VERIFY_RESULT_LABELS: Record<VerifyResult, string> = {
  // 核验确认用户描述属实。
  CONFIRMED: '确认属实',
  // 存在损坏迹象但无法完全确认。
  SUSPECTED: '疑似损坏',
  // 核验认为报修不成立，将驳回。
  FALSE: '不属实',
}

/**
 * 站内消息业务类型中文名。
 *
 * 类型由后端按业务事件生成（预约、取件、到期、逾期、报修等），
 * 前端只负责展示，不在本地推导或拼接。
 */
export const MESSAGE_TYPE_LABELS: Record<MessageType, string> = {
  // 用户预约成功后发送。
  RESERVATION: '预约通知',
  // 提醒用户取件时间与地点。
  PICKUP: '取件提醒',
  // 归还到期前提醒。
  RENT_DUE: '到期提醒',
  // 逾期后催还。
  OVERDUE: '逾期提醒',
  // 报修状态发生变更时通知。
  REPAIR: '报修通知',
  // 用户蹲守的工具重新可借。
  TOOL_AVAILABLE: '蹲守到货',
  // 管理员主动发布的公告。
  ANNOUNCEMENT: '公告',
  // 账号、系统级通知。
  SYSTEM: '系统消息',
}

/**
 * 消息投递通道中文名。
 *
 * 用于消息配置页展示各类型可选的发送渠道；
 * 站内消息必达，短信是否启用由配置决定。
 */
export const MESSAGE_CHANNEL_LABELS: Record<MessageChannel, string> = {
  // 站内消息：登录后即可查看，必达。
  SYSTEM: '站内消息',
  // 短信：受配置开关与用户手机号约束。
  SMS: '短信',
}

/**
 * 消息投递状态中文名。
 *
 * 描述消息从待发送到送达/失败的投递结果；
 * FAILED 需要用户可见，便于判断是否需要重发。
 */
export const DELIVERY_STATUS_LABELS: Record<DeliveryStatus, string> = {
  // 已创建但尚未投递。
  PENDING: '待发送',
  // 已交给通道，等待送达回执。
  SENT: '已发送',
  // 通道确认送达。
  DELIVERED: '已送达',
  // 投递失败，用户可能看不到，需要关注。
  FAILED: '发送失败',
}

/**
 * 管理员角色中文名。
 *
 * 仅用于界面展示；权限判断必须使用原始角色值（STAFF/SUPER_ADMIN），
 * 不要拿中文名做比较。
 */
export const ADMIN_ROLE_LABELS: Record<AdminRole, string> = {
  // 普通工作人员：处理租赁与报修。
  STAFF: '工作人员',
  // 系统管理员：额外拥有工具、知识库与账号管理权限。
  SUPER_ADMIN: '系统管理员',
}

/**
 * 工具操作日志动作中文名。
 *
 * key 是后端日志里的动作枚举，日志页按时间线展示；
 * 未知动作会由 enumLabel 回退显示原始 key，避免隐藏新日志。
 */
export const TOOL_LOG_ACTION_LABELS: Record<string, string> = {
  // 创建工具。
  CREATE: '新增工具',
  // 修改工具基础信息。
  UPDATE: '编辑工具',
  // 审核通过或要求修改。
  AUDIT: '审核',
  // 手动调整工具状态。
  STATUS_CHANGE: '状态变更',
  // 把报废工具恢复为可用。
  RESTORE: '报废恢复',
  // 租赁单确认取件。
  PICKUP: '确认取件',
  // 租赁单归还验收。
  RETURN: '归还验收',
  // 报修流程中的处理动作。
  REPAIR: '报修处理',
  // 删除工具。
  DELETE: '删除工具',
}

// ---- 通用查询与徽标工具 ----
// 下列函数统一遵循「空值占位、未知值回退原值」的约定。
/**
 * 通用枚举标签查询。
 *
 * @param map 枚举值到中文名的映射
 * @param value 后端返回的枚举值
 * @returns 中文名；空值返回「—」，未收录的值原样返回
 * 回退原文而不是「—」，避免新枚举值在界面上变成空白。
 */
export function enumLabel(map: Record<string, string>, value?: string | null): string {
  if (!value) return '—'
  return map[value] ?? value
}

/**
 * 把枚举映射转成下拉选项数组。
 *
 * @param map 枚举值到中文名的映射
 * @returns `{ value, label }[]`，顺序与对象键的插入顺序一致
 * 供 Element Plus 的 el-option 直接遍历，无副作用。
 */
export function optionList(map: Record<string, string>): Array<{ value: string; label: string }> {
  return Object.entries(map).map(([value, label]) => ({ value, label }))
}

/**
 * 工具状态徽标语义。
 *
 * 可借用→success（绿）、已租出→primary（蓝）、
 * 维修中→warning（橙）、已报废→danger（红）、未知→info（灰）。
 */
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

/**
 * 租赁状态徽标语义。
 *
 * 待支付→warning、已预约→primary、使用中→success、
 * 已归还/已取消→info、已逾期→danger；未知状态回退 info。
 */
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

/**
 * 报修单状态徽标语义。
 *
 * 已提交/维修中→warning、已核验→primary、已驳回→danger、
 * 已完成→success、已报废/未知→info。
 */
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

/**
 * 维修进度徽标语义。
 *
 * 等待配件→warning、维修中→primary、完成→success、失败→danger；
 * 未知状态回退 info，保证表格不出现空样式。
 */
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

/**
 * 验收结论徽标语义。
 *
 * 合格→success、不合格→danger、部分合格→warning；未知→info。
 */
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

/**
 * 工具磨损程度徽标语义。
 *
 * 全新→success、轻微→info、中度→warning、严重→danger；
 * 颜色随磨损加重而升级，便于列表快速识别。
 */
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

/**
 * 押金状态徽标语义。
 *
 * 未支付→warning、已支付→primary、已退款→success；未知→info。
 */
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

/**
 * 消息投递状态徽标语义。
 *
 * 已送达→success、已发送→primary、待发送→warning、失败→danger；
 * 允许 null，未产生投递记录时回退 info。
 */
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

/**
 * 兼容多种「已读」表示形式。
 *
 * 不同接口或历史数据可能返回 boolean、数字 1、字符串 '1'
 * 或 'READ'/'read'，这里统一判定，避免各页面各写一套。
 *
 * @param value 任意后端返回值
 * @returns 是否视为已读；其他值（包括 null）一律返回 false
 */
export function isReadStatus(value: unknown): boolean {
  return value === true || value === 1 || value === '1' || value === 'READ' || value === 'read'
}
