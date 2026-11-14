import type { components } from './generated'

type Schemas = components['schemas']

/** 契约中 VO 字段均为可选；后端实际会返回全部字段，这里恢复为必填。 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/** 在契约类型上收窄前端已知的可空字段，其余字段仍由契约维护。 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

/** 请求体保持契约中的必填/可选结构，只收窄前端补充的字段。 */
type Payload<Name extends keyof Schemas, Overrides> = Omit<Schemas[Name], keyof Overrides> &
  Overrides

/** 从契约枚举字段派生业务枚举，避免三端漂移。 */
type SchemaEnum<Name extends keyof Schemas, Key extends keyof Schemas[Name]> = NonNullable<
  Schemas[Name][Key]
>

// ---- 业务枚举：统一从 OpenAPI 契约派生 ----

export type ToolCategory = SchemaEnum<'ToolVO', 'category'>
export type DamageLevel = SchemaEnum<'ToolVO', 'damageLevel'>
export type ToolStatus = SchemaEnum<'ToolVO', 'status'>
export type RentalStatus = SchemaEnum<'RentalVO', 'status'>
export type DepositStatus = SchemaEnum<'RentalVO', 'depositStatus'>
export type DamageResult = SchemaEnum<'RentalVO', 'damageResult'>
export type DamageReportStatus = SchemaEnum<'DamageReportVO', 'status'>
export type ProgressStatus = SchemaEnum<'DamageReportVO', 'progressStatus'>
export type AcceptResult = SchemaEnum<'DamageReportVO', 'acceptResult'>
export type VerifyResult = SchemaEnum<'DamageReportVO', 'verifyResult'>
export type ToolStatusAfter = SchemaEnum<'DamageReportVO', 'toolStatusAfter'>
export type MessageType = SchemaEnum<'MessageVO', 'type'>
export type MessageChannel = SchemaEnum<'MessageVO', 'channel'>
export type DeliveryStatus = SchemaEnum<'MessageVO', 'deliveryStatus'>
export type AdminRole = SchemaEnum<'AdminVO', 'role'>

// ---- 分页：统一请求参数与响应结构 ----

export interface PageParams {
  page?: number
  size?: number
}

type PageSchema = Required<Schemas['PageVOToolVO']>

export type PageResult<T> = Omit<PageSchema, 'records'> & { records: T[] }

// ---- 契约类型 ----

export type LoginVO = Api<'LoginVO'>
export type AdminVO = Api<'AdminVO'>
export type AdminDashboardVO = Api<'AdminDashboardVO'>
export type UploadVO = Api<'FileVO'>

export type ToolVO = With<
  'ToolVO',
  {
    model: string | null
    storageArea: string | null
    usageGuide: string | null
    imageUrl: string | null
    purchaseDate: string | null
    supplier: string | null
    auditStatusName: string | null
    auditRemark: string | null
    createdByName: string | null
    lastRepairTime: string | null
  }
>

export type ToolLogVO = Api<'ToolLogVO'>

export type RentalFee = Api<'RentalFeeVO'>
export type PaymentRecord = Api<'PaymentRecordVO'>

export type RentalVO = With<
  'RentalVO',
  {
    fees: RentalFee[]
    payments: PaymentRecord[]
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
    toolStatusAfter: ToolStatusAfter | null
    remark: string | null
  }
>

export type MessageVO = With<'MessageVO', { deliveryStatus: DeliveryStatus | null }>

export type KnowledgeDocVO = Api<'KnowledgeDocVO'>

// ---- 查询参数：前端请求形状，分页字段统一继承 PageParams ----

/** 管理端工具查询：筛选项允许空串表示「全部」。 */
export type ToolQuery = Omit<Schemas['ToolAdminQuery'], 'category' | 'status'> & {
  category?: ToolCategory | ''
  status?: ToolStatus | ''
}

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

export type ToolSaveDTO = Payload<'ToolSaveDTO', { imageUrls: string[]; purchaseDate?: string | null }>

export type ToolAuditDTO = Schemas['ToolAuditDTO']

export type ToolStatusDTO = Schemas['ToolStatusDTO']

export type ToolRestoreDTO = Schemas['ToolRestoreDTO']

export type RentalReturnDTO = Schemas['RentalReturnDTO']

export type RentalCancelDTO = Payload<'CancelDTO', { reason: string }>

export type DamageVerifyDTO = Schemas['DamageVerifyDTO']

export type DamageProgressDTO = Schemas['DamageProgressDTO']

export type DamageAcceptDTO = Schemas['DamageAcceptDTO']

export type AdminLoginDTO = Schemas['AdminLoginDTO']

export type AdminRoleUpdateDTO = Schemas['AdminRoleUpdateDTO']

export type MessageConfigVO = Api<'MessageConfigVO'>

export type MessageConfigUpdateDTO = Schemas['MessageConfigUpdateDTO']
