/**
 * Admin 端 API 类型汇总。
 *
 * 所有业务类型都从 `generated.ts` 的 OpenAPI 契约派生，避免手写类型与后端漂移。
 * 本文件只在契约基础上做三类调整：
 * - Required：后端实际返回的 VO 字段视为必填；
 * - With：把已知可空字段显式标成 `| null`；
 * - Payload：请求体保持契约的必填/可选结构，仅补充前端需要的字段。
 * `generated.ts` 由工具生成，禁止手工修改。
 */
import type { components } from './generated'

// 取出 OpenAPI 契约的全部 schema，后续按名字索引。
type Schemas = components['schemas']

/**
 * 契约生成时 VO 字段通常都是可选的；
 * 后端实际会返回完整对象，这里统一恢复为 Required，
 * 页面代码因此不必到处写非空断言。
 *
 * @template Name schema 名称
 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/**
 * 在契约 VO 上收窄前端已知的可空字段。
 *
 * 先用 Omit 去掉要覆盖的键，再交叉上显式的可空类型；
 * 其余字段仍完全由契约维护，后端新增字段时无需改这里。
 *
 * @template Name schema 名称
 * @template Overrides 需要覆盖的字段及类型
 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

/**
 * 请求体类型。
 *
 * 与 With 不同，这里不做 Required：请求体的必填/可选语义
 * 必须与后端校验保持一致，只允许收窄前端补充的字段。
 *
 * @template Name schema 名称
 * @template Overrides 需要覆盖的字段及类型
 */
type Payload<Name extends keyof Schemas, Overrides> = Omit<Schemas[Name], keyof Overrides> &
  Overrides

/**
 * 从契约的枚举字段派生业务枚举。
 *
 * NonNullable 去掉契约中可能存在的 null，得到纯枚举联合类型；
 * 后端枚举变更后重新生成契约即可同步，避免三端各写一份常量。
 *
 * @template Name schema 名称
 * @template Key schema 内的字段名
 */
type SchemaEnum<Name extends keyof Schemas, Key extends keyof Schemas[Name]> = NonNullable<
  Schemas[Name][Key]
>

// ---- 业务枚举：统一从 OpenAPI 契约派生 ----
// 不要手写字符串字面量：后端新增枚举值后重新生成契约即可同步，
// 避免三端各维护一份常量而产生漂移。
// ---- 业务枚举：统一从 OpenAPI 契约派生 ----

/** 工具分类：维修、园艺、清洁、其他。 */
export type ToolCategory = SchemaEnum<'ToolVO', 'category'>
/** 工具损坏程度：从全新到严重损坏，用于展示与报修判断。 */
export type DamageLevel = SchemaEnum<'ToolVO', 'damageLevel'>
/** 工具生命周期状态：可借用、已租出、维修中、已报废。 */
export type ToolStatus = SchemaEnum<'ToolVO', 'status'>
/** 租赁单状态：待支付、已预约、使用中、已归还、已取消、已逾期。 */
export type RentalStatus = SchemaEnum<'RentalVO', 'status'>
/** 押金状态：未支付、已支付、已退款。 */
export type DepositStatus = SchemaEnum<'RentalVO', 'depositStatus'>
/** 归还验收时的损坏结论：完好、轻微损坏、严重损坏。 */
export type DamageResult = SchemaEnum<'RentalVO', 'damageResult'>
/** 报修单主状态：已提交、已核验、已驳回、维修中、已完成、已报废。 */
export type DamageReportStatus = SchemaEnum<'DamageReportVO', 'status'>
/** 维修进度子状态：等待配件、维修中、维修完成、维修失败。 */
export type ProgressStatus = SchemaEnum<'DamageReportVO', 'progressStatus'>
/** 维修验收结论：合格、不合格、部分合格。 */
export type AcceptResult = SchemaEnum<'DamageReportVO', 'acceptResult'>
/** 报修核验结论：确认属实、疑似损坏、不属实。 */
export type VerifyResult = SchemaEnum<'DamageReportVO', 'verifyResult'>
/** 验收后工具的预期状态，用于决定工具恢复还是报废。 */
export type ToolStatusAfter = SchemaEnum<'DamageReportVO', 'toolStatusAfter'>
/** 站内消息业务类型：预约、取件、到期、逾期、报修等。 */
export type MessageType = SchemaEnum<'MessageVO', 'type'>
/** 消息投递通道：站内消息或短信。 */
export type MessageChannel = SchemaEnum<'MessageVO', 'channel'>
/** 消息投递状态：待发送、已发送、已送达、发送失败。 */
export type DeliveryStatus = SchemaEnum<'MessageVO', 'deliveryStatus'>
/** 管理员角色：普通工作人员或系统管理员，用于前端权限控制。 */
export type AdminRole = SchemaEnum<'AdminVO', 'role'>

// ---- 分页：统一请求参数与响应结构 ----
// 所有列表接口共享同一套分页字段，避免每个模块各定义一份。
// ---- 分页：统一请求参数与响应结构 ----

/**
 * 通用分页请求参数。
 *
 * 字段均可选，未传时由后端使用默认值；
 * 页码从 1 开始，与后端契约保持一致。
 */
export interface PageParams {
  page?: number
  size?: number
}

// 从契约中取分页响应结构；records 的具体类型在 PageResult 中泛型化。
type PageSchema = Required<Schemas['PageVOToolVO']>

/**
 * 泛型分页响应。
 *
 * @template T 单条记录类型
 * 除 records 外，总数、页码等分页字段沿用契约定义。
 */
export type PageResult<T> = Omit<PageSchema, 'records'> & { records: T[] }

// ---- 契约类型 ----
// VO 表示后端返回给前端的视图对象；可空字段在下方用 With 显式收窄。
// ---- 契约类型 ----

/** 登录响应；前端主要消费 tokenValue，并据此恢复会话。 */
export type LoginVO = Api<'LoginVO'>
/** 管理员账号信息：用户名、角色、状态等。 */
export type AdminVO = Api<'AdminVO'>
/** 仪表盘聚合统计，一次请求返回首页所需全部指标。 */
export type AdminDashboardVO = Api<'AdminDashboardVO'>
/** 文件上传结果；契约中名为 FileVO，这里按业务语义重命名。 */
export type UploadVO = Api<'FileVO'>

/**
 * 工具详情。
 *
 * 契约里的字段默认必填，这里把已知可空字段（型号、图片、供应商等）
 * 显式标成 `| null`，页面可以据此安全地做空值渲染。
 */
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

/** 工具操作日志，用于详情页的时间线展示。 */
export type ToolLogVO = Api<'ToolLogVO'>

/** 租赁费用项（租金、押金、逾期费等）。 */
export type RentalFee = Api<'RentalFeeVO'>
/** 支付流水，归还与结算时用于展示支付记录。 */
export type PaymentRecord = Api<'PaymentRecordVO'>

/**
 * 租赁单详情。
 *
 * 除契约字段外，这里显式声明费用、支付记录数组以及
 * 归还/取消相关的可空字段，便于页面直接渲染而无需断言。
 */
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

/**
 * 报修单详情。
 *
 * 核验、维修、验收各阶段字段在流程推进前为空，
 * 因此统一标成 `| null`；对应状态字段也允许为空。
 */
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

/** 站内消息；投递状态在消息刚创建时可能尚未产生。 */
export type MessageVO = With<'MessageVO', { deliveryStatus: DeliveryStatus | null }>

/** 知识库文档的元信息（标题、文件地址、上传时间等）。 */
export type KnowledgeDocVO = Api<'KnowledgeDocVO'>

// ---- 查询参数：前端请求形状，分页字段统一继承 PageParams ----
// 查询参数允许比契约更宽松，以便表单用空串表示「不筛选」。
// ---- 查询参数：前端请求形状，分页字段统一继承 PageParams ----

/**
 * 管理端工具筛选条件。
 *
 * category/status 允许空串，表示「全部」；发送前由 cleanParams 清洗。
 */
export type ToolQuery = Omit<Schemas['ToolAdminQuery'], 'category' | 'status'> & {
  category?: ToolCategory | ''
  status?: ToolStatus | ''
}

/**
 * 租赁单筛选条件。
 *
 * 关键词、状态、是否逾期与损坏结果都可选；
 * 空值由调用方用 cleanParams 清洗后再发送。
 */
export interface RentalQuery extends PageParams {
  keyword?: string
  status?: string
  overdue?: string
  damageResult?: string
}

/**
 * 报修单筛选条件。
 *
 * @see RentalQuery 分页字段与清洗规则保持一致。
 */
export interface DamageQuery extends PageParams {
  keyword?: string
  status?: string
}

/** 消息查询目前只支持分页，后续可在此扩展类型/已读筛选。 */
export type MessageQuery = PageParams

// ---- 请求体 ----
// 请求体尽量直接复用契约类型；只有前端形状不同（如图片数组）时才用 Payload 收窄。
// ---- 请求体 ----

/**
 * 工具新建/编辑保存体。
 *
 * 图片以 URL 数组提交（先走上传接口），购买日期允许为空。
 */
export type ToolSaveDTO = Payload<'ToolSaveDTO', { imageUrls: string[]; purchaseDate?: string | null }>

/** 工具审核请求体：审核结论与备注。 */
export type ToolAuditDTO = Schemas['ToolAuditDTO']

/** 工具状态调整请求体；status 字段类型供调用方复用。 */
export type ToolStatusDTO = Schemas['ToolStatusDTO']

/** 报废工具恢复请求体：恢复原因等。 */
export type ToolRestoreDTO = Schemas['ToolRestoreDTO']

/** 租赁归还验收请求体：损坏结论与费用结算信息。 */
export type RentalReturnDTO = Schemas['RentalReturnDTO']

/**
 * 取消租赁请求体。
 *
 * 契约中取消原因可能可选，管理端要求必填，便于向用户说明。
 */
export type RentalCancelDTO = Payload<'CancelDTO', { reason: string }>

/** 报修核验请求体：核验结论与备注。 */
export type DamageVerifyDTO = Schemas['DamageVerifyDTO']

/** 维修进度更新请求体：进度状态与说明。 */
export type DamageProgressDTO = Schemas['DamageProgressDTO']

/** 维修验收请求体：验收结论、费用与工具后续状态。 */
export type DamageAcceptDTO = Schemas['DamageAcceptDTO']

/** 管理员登录请求体：用户名与密码。 */
export type AdminLoginDTO = Schemas['AdminLoginDTO']

/** 修改管理员角色请求体。 */
export type AdminRoleUpdateDTO = Schemas['AdminRoleUpdateDTO']

/** 消息配置：各消息类型的开关与投递通道。 */
export type MessageConfigVO = Api<'MessageConfigVO'>

/** 消息配置更新请求体（PUT 整体覆盖）。 */
export type MessageConfigUpdateDTO = Schemas['MessageConfigUpdateDTO']
