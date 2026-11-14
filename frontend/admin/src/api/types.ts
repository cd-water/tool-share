import type { components } from './generated'

type Schemas = components['schemas']

/** 契约中 VO 字段均为可选；后端实际会返回全部字段，这里恢复为必填。 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/** 在契约类型上收窄前端已知的枚举字段，其余字段仍由契约维护。 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

/** 请求体保持契约中的必填/可选结构，只收窄枚举字段。 */
type Payload<Name extends keyof Schemas, Overrides> = Omit<
  Schemas[Name],
  keyof Overrides
> & Overrides

// ---- 业务枚举：前端唯一来源（OpenAPI 中这些字段以 string 暴露） ----

export type ToolCategory = 'WX' | 'YY' | 'QJ' | 'QT'
export type DamageLevel = 'NEW' | 'LIGHT' | 'MEDIUM' | 'SEVERE'
export type ToolStatus = 'AVAILABLE' | 'RENTED' | 'MAINTENANCE' | 'SCRAPPED'
export type AuditStatus = 'PENDING' | 'APPROVED' | 'NEEDS_CHANGE'
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
export type VerifyResult = 'CONFIRMED' | 'SUSPECTED' | 'FALSE'
export type ToolStatusAfter = 'AVAILABLE' | 'MAINTENANCE' | 'SCRAPPED'
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
export type AdminRole = 'STAFF' | 'SUPER_ADMIN'

// ---- 分页：统一请求参数与响应结构 ----

export interface PageParams {
  page?: number
  size?: number
}

type PageSchema = Required<Schemas['PageVOToolVO']>

export type PageResult<T> = Omit<PageSchema, 'records'> & { records: T[] }

// ---- 契约类型 ----

export type LoginVO = Api<'LoginVO'>
export type AdminVO = With<'AdminVO', { role: AdminRole }>
export type AdminDashboardVO = Api<'AdminDashboardVO'>
export type UploadVO = Api<'FileVO'>

export type ToolVO = With<
  'ToolVO',
  {
    category: ToolCategory
    damageLevel: DamageLevel
    status: ToolStatus
    model: string | null
    storageArea: string | null
    usageGuide: string | null
    imageUrl: string | null
    purchaseDate: string | null
    supplier: string | null
    auditStatus: number
    auditStatusName: string | null
    auditRemark: string | null
    createdByName: string | null
    lastRepairTime: string | null
  }
>

export type ToolLogVO = Api<'ToolLogVO'>

export type RentalVO = With<
  'RentalVO',
  {
    status: RentalStatus
    depositStatus: DepositStatus
    toolImageUrl: string | null
    renterIdCardMasked: string | null
    pickupPerson: string | null
    createdByName: string | null
    updatedByName: string | null
    actualReturnTime: string | null
    overdueFee: number | null
    damageFee: number | null
    refundAmount: number | null
    damageResult: DamageResult | null
    damageResultName: string | null
    returnRemark: string | null
    cancelReason: string | null
    cancelTime: string | null
  }
>

export type DamageReportVO = With<
  'DamageReportVO',
  {
    status: DamageReportStatus
    verifier: string | null
    verifyTime: string | null
    verifyResult: VerifyResult | null
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

export type MessageVO = With<
  'MessageVO',
  {
    type: MessageType
    channel: MessageChannel
    deliveryStatus: DeliveryStatus | null
  }
>

export type KnowledgeDocVO = Api<'KnowledgeDocVO'>

// ---- 查询参数：前端请求形状，分页字段统一继承 PageParams ----

export type ToolOrderBy = 'default' | 'rentalCount' | 'lastRepairTime'

export type ToolQuery = Payload<
  'ToolAdminQuery',
  { category?: string; status?: string; orderBy?: ToolOrderBy }
>

export interface RentalQuery extends PageParams {
  keyword?: string
  status?: string
  overdue?: string
  damageResult?: string
}

export interface DamageQuery extends PageParams {
  keyword?: string
  status?: string
}

export type MessageQuery = PageParams

// ---- 请求体 ----

export type ToolSaveDTO = Payload<
  'ToolSaveDTO',
  {
    category: ToolCategory
    damageLevel: DamageLevel
    status?: ToolStatus
    imageUrls: string[]
    purchaseDate?: string | null
  }
>

export type ToolAuditDTO = Payload<
  'ToolAuditDTO',
  { auditStatus: Exclude<AuditStatus, 'PENDING'> }
>

export type ToolStatusDTO = Payload<'ToolStatusDTO', { status: ToolStatus }>

export type ToolRestoreDTO = Schemas['ToolRestoreDTO']

export type RentalReturnDTO = Payload<'RentalReturnDTO', { damageResult: DamageResult }>

export type RentalCancelDTO = Payload<'CancelDTO', { reason: string }>

export type DamageVerifyDTO = Payload<'DamageVerifyDTO', { verifyResult: VerifyResult }>

export type DamageProgressDTO = Payload<'DamageProgressDTO', { progressStatus: ProgressStatus }>

export type DamageAcceptDTO = Payload<
  'DamageAcceptDTO',
  { acceptResult: AcceptResult; toolStatusAfter: ToolStatusAfter }
>

export type AdminLoginDTO = Schemas['AdminLoginDTO']

export type AdminRoleUpdateDTO = Payload<'AdminRoleUpdateDTO', { role: AdminRole }>
