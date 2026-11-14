import type { components } from './generated'

type Schemas = components['schemas']

/** 契约中 VO 字段均为可选；后端实际会返回全部字段，这里恢复为必填。 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/** 在契约类型上收窄前端已知的枚举字段，其余字段仍由契约维护。 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

// ---- 业务枚举：前端唯一来源（OpenAPI 中这些字段以 string 暴露） ----

export type ToolCategory = 'WX' | 'YY' | 'QJ' | 'QT'
export type ToolStatus = 'AVAILABLE' | 'RENTED' | 'MAINTENANCE' | 'SCRAPPED'
export type DamageLevel = 'NEW' | 'LIGHT' | 'MEDIUM' | 'SEVERE'
export type RentalStatus =
  | 'PENDING_PAYMENT'
  | 'RESERVED'
  | 'PICKED_UP'
  | 'RETURNED'
  | 'CANCELLED'
  | 'OVERDUE'
export type DepositStatus = 'UNPAID' | 'PAID' | 'REFUNDED'
export type DamageResult = 'INTACT' | 'MINOR' | 'SEVERE'
export type DamageReportStatus =
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'REJECTED'
  | 'REPAIRING'
  | 'COMPLETED'
  | 'SCRAPPED'
export type ProgressStatus = 'WAITING_PARTS' | 'REPAIRING' | 'COMPLETED' | 'FAILED'
export type AcceptResult = 'QUALIFIED' | 'UNQUALIFIED' | 'PARTIAL'
export type MessageType =
  | 'RESERVATION'
  | 'PICKUP'
  | 'RENT_DUE'
  | 'OVERDUE'
  | 'REPAIR'
  | 'TOOL_AVAILABLE'
  | 'SYSTEM'
export type MessageChannel = 'SYSTEM' | 'SMS'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'DELIVERED' | 'FAILED'

// ---- 分页：统一请求参数与响应结构 ----

export interface PageParams {
  page?: number
  size?: number
}

type PageSchema = Required<Schemas['PageVOToolVO']>

export type PageResult<T> = Omit<PageSchema, 'records'> & { records: T[] }

// ---- 契约类型 ----

export type Tool = With<
  'ToolVO',
  {
    category: ToolCategory
    status: ToolStatus
    damageLevel: DamageLevel
    lastRepairTime: string | null
  }
>

export type Rental = With<
  'RentalVO',
  {
    status: RentalStatus
    depositStatus: DepositStatus
    actualReturnTime: string | null
    overdueFee: number | null
    damageFee: number | null
    refundAmount: number | null
    damageResult: DamageResult | null
    damageResultName: string | null
    returnRemark: string | null
    cancelReason: string | null
    cancelTime: string | null
    remindBeforeMinutes: number | null
    remindAt: string | null
  }
>

export type DamageReport = With<
  'DamageReportVO',
  {
    status: DamageReportStatus
    verifier: string | null
    verifyTime: string | null
    verifyResult: string | null
    repairPerson: string | null
    repairEstimate: string | null
    repairStartTime: string | null
    repairCost: number | null
    progressStatus: ProgressStatus | null
    progressStatusName: string | null
    progressUpdateTime: string | null
    progressNote: string | null
    acceptor: string | null
    acceptTime: string | null
    acceptResult: AcceptResult | null
    acceptResultName: string | null
    toolStatusAfter: string | null
    remark: string | null
  }
>

export type Message = With<
  'MessageVO',
  {
    type: MessageType
    channel: MessageChannel
    deliveryStatus: DeliveryStatus
  }
>

export type NotificationPref = With<
  'NotificationPrefVO',
  {
    type: MessageType
    channel: MessageChannel
  }
>

export type NotificationPrefUpdatePayload = {
  enabled: boolean
  channel: MessageChannel
}

export type Review = Api<'ReviewVO'>
export type ReviewCreatePayload = Schemas['ReviewCreateDTO']

export type CreditEvent = Api<'CreditEventVO'>
export type CreditOverview = With<'CreditVO', { events: PageResult<CreditEvent> }>

export type UserDashboard = Api<'UserDashboardVO'>
export type UserProfile = Api<'UserVO'>
export type LoginResult = Api<'LoginVO'>
export type UploadResult = Api<'FileVO'>

export type AiSource = Api<'SourceVO'>
export type AiChatResult = With<'AiChatVO', { sources: AiSource[] }>

export type ToolRecommendRequest = Schemas['ToolRecommendDTO'] & { category?: ToolCategory }
/** 契约暂未包含评分字段；后端返回时展示，未返回则忽略。 */
export type ToolRecommendItem = Api<'Item'> & {
  ratingAvg?: number | null
  reviewCount?: number | null
}
export type ToolRecommendResult = With<
  'ToolRecommendVO',
  { recommendationId: number | null; items: ToolRecommendItem[] }
>

export type ToolWatchItem = With<'ToolWatchVO', { status: ToolStatus }>
export type AvailabilityResult = Api<'AvailabilityVO'>

export type RentalCreatePayload = Schemas['RentalCreateDTO']
export type DamageReportCreatePayload = Schemas['DamageCreateDTO']
export type ProfileUpdatePayload = Schemas['ProfileUpdateDTO']
