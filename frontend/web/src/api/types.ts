import type { components } from './generated'

type Schemas = components['schemas']

/** 契约中 VO 字段均为可选；后端实际会返回全部字段，这里恢复为必填。 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/** 在契约类型上收窄前端已知的可空字段，其余字段仍由契约维护。 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

/** 从契约枚举字段派生业务枚举，避免三端漂移。 */
type SchemaEnum<Name extends keyof Schemas, Key extends keyof Schemas[Name]> = NonNullable<
  Schemas[Name][Key]
>

// ---- 业务枚举：统一从 OpenAPI 契约派生 ----

export type ToolCategory = SchemaEnum<'ToolVO', 'category'>
export type ToolStatus = SchemaEnum<'ToolVO', 'status'>
export type DamageLevel = SchemaEnum<'ToolVO', 'damageLevel'>
export type RentalStatus = SchemaEnum<'RentalVO', 'status'>
export type DamageResult = SchemaEnum<'RentalVO', 'damageResult'>
export type DamageReportStatus = SchemaEnum<'DamageReportVO', 'status'>
export type ProgressStatus = SchemaEnum<'DamageReportVO', 'progressStatus'>
export type AcceptResult = SchemaEnum<'DamageReportVO', 'acceptResult'>
export type MessageType = SchemaEnum<'MessageVO', 'type'>
export type MessageChannel = SchemaEnum<'MessageVO', 'channel'>
export type DeliveryStatus = SchemaEnum<'MessageVO', 'deliveryStatus'>

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
    lastRepairTime: string | null
  }
>

export type RentalFee = Api<'RentalFeeVO'>
export type PaymentRecord = Api<'PaymentRecordVO'>

export type Rental = With<
  'RentalVO',
  {
    fees: RentalFee[]
    payments: PaymentRecord[]
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
    verifier: string | null
    verifyTime: string | null
    verifyResult: SchemaEnum<'DamageReportVO', 'verifyResult'> | null
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
    toolStatusAfter: SchemaEnum<'DamageReportVO', 'toolStatusAfter'> | null
    remark: string | null
  }
>

export type Message = Api<'MessageVO'>

export type NotificationPref = Api<'NotificationPrefVO'>

export type NotificationPrefUpdatePayload = Schemas['NotificationPrefUpdateDTO']

export type Review = Api<'ReviewVO'>
export type ReviewCreatePayload = Schemas['ReviewCreateDTO']

export type CreditEvent = Api<'CreditEventVO'>
export type CreditOverview = With<'CreditVO', { events: PageResult<CreditEvent> }>

export type UserDashboard = Api<'UserDashboardVO'>
export type UserProfile = Api<'UserVO'>
export type LoginResult = Api<'LoginVO'>
export type UploadResult = Api<'FileVO'>

export type AiSource = Api<'SourceVO'>

export type ToolRecommendRequest = Schemas['ToolRecommendDTO']
export type ToolRecommendItem = Api<'Item'>
export type ToolRecommendResult = With<
  'ToolRecommendVO',
  { recommendationId: number | null; items: ToolRecommendItem[] }
>

export type AvailabilityResult = Api<'AvailabilityVO'>

export interface ToolRentalHistoryWindow {
  startTime: string
  endTime: string
}

export interface ToolRentalHistory {
  toolId: number
  completedCount: number
  windows: ToolRentalHistoryWindow[]
}

export type RentalCreatePayload = Schemas['RentalCreateDTO']
export type DamageReportCreatePayload = Schemas['DamageCreateDTO']
export type ProfileUpdatePayload = Schemas['ProfileUpdateDTO']
