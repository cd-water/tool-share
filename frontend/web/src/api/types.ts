/**
 * 业务类型统一出口。
 *
 * 数据流：OpenAPI 契约 → `generated.ts`（自动生成，禁止手改）→ 本文件
 * 做「前端视角」的收窄与补充 → 业务 API/页面消费。
 *
 * 为什么不直接在页面用 generated 类型：
 * 1. 契约里的 VO 字段通常全部可选（后端序列化省略 null），但接口实际会
 *    返回完整对象，页面逐字段判空噪声很大，这里用 `Api` 恢复为必填；
 * 2. 部分字段在契约中无法表达「可为 null」或「前端已知更精确的结构」，
 *    用 `With` 在不 fork 契约的前提下覆盖；
 * 3. 枚举统一从契约派生，避免前端手写字符串联合导致三端漂移。
 *
 * 约束：本文件只做类型运算，不产生运行时代码。
 */
import type { components } from './generated'

/** OpenAPI 生成的全部 schema 集合，后续所有类型运算的根。 */
type Schemas = components['schemas']

/**
 * 把契约 VO 的所有字段恢复为必填。
 *
 * @typeParam Name schema 名称
 *
 * 前提：后端 VO 在成功响应中会返回全部字段（可为 null），只是契约生成器
 * 把它们标成了 optional。若某个字段确实可能整个缺失，应改用 `With` 覆盖。
 */
/** 契约中 VO 字段均为可选；后端实际会返回全部字段，这里恢复为必填。 */
type Api<Name extends keyof Schemas> = Required<Schemas[Name]>

/**
 * 在必填契约类型上覆盖指定字段。
 *
 * @typeParam Name schema 名称
 * @typeParam Overrides 需要覆盖的字段集合
 *
 * 实现方式：先从 `Api<Name>` 中剔除同名 key，再与 Overrides 求交，
 * 这样覆盖字段的 nullability/结构由前端显式声明，其余字段继续跟随契约。
 */
/** 在契约类型上收窄前端已知的可空字段，其余字段仍由契约维护。 */
type With<Name extends keyof Schemas, Overrides> = Omit<Api<Name>, keyof Overrides> & Overrides

/**
 * 从契约的枚举字段派生字符串联合类型。
 *
 * @typeParam Name schema 名称
 * @typeParam Key 该 schema 内的字段名
 *
 * 用 NonNullable 去掉 null 分支，得到纯枚举值联合；
 * 后端新增枚举值时生成文件会同步变化，前端 switch 的 default 负责兜底。
 */
/** 从契约枚举字段派生业务枚举，避免三端漂移。 */
type SchemaEnum<Name extends keyof Schemas, Key extends keyof Schemas[Name]> = NonNullable<
  Schemas[Name][Key]
>

// ---- 业务枚举：统一从 OpenAPI 契约派生 ----

/** 工具分类（电动/手动/测量等，具体取值由后端维护）。 */
export type ToolCategory = SchemaEnum<'ToolVO', 'category'>
/** 工具状态：AVAILABLE / RENTED / MAINTENANCE / SCRAPPED。 */
export type ToolStatus = SchemaEnum<'ToolVO', 'status'>
/** 工具损坏等级：NEW / LIGHT / MEDIUM / SEVERE。 */
export type DamageLevel = SchemaEnum<'ToolVO', 'damageLevel'>
/** 租赁订单状态：待支付、已预约、已取件、已归还、逾期、已取消。 */
export type RentalStatus = SchemaEnum<'RentalVO', 'status'>
/** 归还时的损坏判定结果（无损坏/需维修/报废等）。 */
export type DamageResult = SchemaEnum<'RentalVO', 'damageResult'>
/** 损坏报告主状态：已提交、已核验、维修中、已完成、驳回、报废。 */
export type DamageReportStatus = SchemaEnum<'DamageReportVO', 'status'>
/** 损坏报告的维修进度状态，用于详情页进度条。 */
export type ProgressStatus = SchemaEnum<'DamageReportVO', 'progressStatus'>
/** 损坏报告的验收结果（通过/不通过等）。 */
export type AcceptResult = SchemaEnum<'DamageReportVO', 'acceptResult'>
/** 站内消息类型：预约、取件、临期、逾期、维修、到货提醒等。 */
export type MessageType = SchemaEnum<'MessageVO', 'type'>
/** 消息投递渠道：站内信、邮件、推送等。 */
export type MessageChannel = SchemaEnum<'MessageVO', 'channel'>
/** 消息投递状态：待发送、已发送、失败等。 */
export type DeliveryStatus = SchemaEnum<'MessageVO', 'deliveryStatus'>

// ---- 分页：统一请求参数与响应结构 ----

/**
 * 通用分页请求参数。
 *
 * 两个字段都可选，后端会使用默认页码/页大小；各领域查询类型通过
 * `extends PageParams` 复用它，保证分页参数命名一致。
 */
export interface PageParams {
  /** 页码，通常从 1 开始。 */
  page?: number
  /** 每页条数，后端会限制最大值。 */
  size?: number
}

/** 以工具分页响应为模板，提取分页元信息（总数、页码、页大小等）。 */
type PageSchema = Required<Schemas['PageVOToolVO']>

/**
 * 通用分页响应。
 *
 * @typeParam T 记录类型
 *
 * 保留契约中的分页元信息，仅把 records 替换为具体类型；
 * 这样所有列表接口共享同一分页结构，表格组件无需感知领域差异。
 */
export type PageResult<T> = Omit<PageSchema, 'records'> & { records: T[] }

// ---- 契约类型 ----

/**
 * 工具视图对象。
 *
 * 在契约基础上显式声明 `lastRepairTime` 可为 null：
 * 从未维修过的工具该字段为空，而不是缺省。
 */
export type Tool = With<
  'ToolVO',
  {
    lastRepairTime: string | null
  }
>

/** 单条租赁费用明细（租金、押金、逾期费等）。 */
export type RentalFee = Api<'RentalFeeVO'>
/** 单条支付记录（支付时间、金额、渠道、状态）。 */
export type PaymentRecord = Api<'PaymentRecordVO'>

/**
 * 租赁订单视图对象。
 *
 * 补充两类前端需要的结构：
 * - 聚合子列表：费用明细与支付记录随详情一次性返回；
 * - 可空业务字段：归还、损坏、退款、取消、提醒等只在特定阶段有值，
 *   显式允许 null，页面可直接用 `formatDateTime` 等工具判空。
 */
export type Rental = With<
  'RentalVO',
  {
    /** 费用明细列表，详情页费用区块使用。 */
    fees: RentalFee[]
    /** 支付流水列表，可能为空数组。 */
    payments: PaymentRecord[]
    /** 实际归还时间；未归还时为 null。 */
    actualReturnTime: string | null
    /** 逾期费用；未逾期或未结算时为 null。 */
    overdueFee: number | null
    /** 损坏扣费；无损坏时为 null。 */
    damageFee: number | null
    /** 退款金额；取消/结算后才可能有值。 */
    refundAmount: number | null
    /** 损坏判定结果；未申报损坏时为 null。 */
    damageResult: DamageResult | null
    /** 损坏结果的展示文案。 */
    damageResultName: string | null
    /** 归还备注。 */
    returnRemark: string | null
    /** 取消原因。 */
    cancelReason: string | null
    /** 取消时间；未取消时为 null。 */
    cancelTime: string | null
    /** 提前多少分钟提醒归还。 */
    remindBeforeMinutes: number | null
    /** 计算出的提醒时间点；未设置提醒时为 null。 */
    remindAt: string | null
  }
>

/**
 * 损坏报告视图对象。
 *
 * 报告会经历「提交 → 核验 → 维修 → 验收」多个阶段，契约把所有阶段字段
 * 平铺在一个 VO 中；这里显式标注阶段性字段可为 null，避免页面误判。
 */
export type DamageReport = With<
  'DamageReportVO',
  {
    /** 核验人姓名；未核验时为 null。 */
    verifier: string | null
    /** 核验时间。 */
    verifyTime: string | null
    /** 核验结论。 */
    verifyResult: SchemaEnum<'DamageReportVO', 'verifyResult'> | null
    /** 维修人。 */
    repairPerson: string | null
    /** 维修估价（后端可能是字符串，保留原始精度）。 */
    repairEstimate: string | null
    /** 维修开始时间。 */
    repairStartTime: string | null
    /** 实际维修费用。 */
    repairCost: number | null
    /** 当前维修进度状态。 */
    progressStatus: ProgressStatus | null
    /** 进度状态展示文案。 */
    progressStatusName: string | null
    /** 进度更新时间。 */
    progressUpdateTime: string | null
    /** 进度备注。 */
    progressNote: string | null
    /** 验收人。 */
    acceptor: string | null
    /** 验收时间。 */
    acceptTime: string | null
    /** 验收结论。 */
    acceptResult: AcceptResult | null
    /** 验收结论展示文案。 */
    acceptResultName: string | null
    /** 处理完成后工具的状态（维修完成/报废）。 */
    toolStatusAfter: SchemaEnum<'DamageReportVO', 'toolStatusAfter'> | null
    /** 报告备注。 */
    remark: string | null
  }
>

/** 站内消息视图对象。 */
export type Message = Api<'MessageVO'>

/** 单个消息类型的通知偏好。 */
export type NotificationPref = Api<'NotificationPrefVO'>

/** 更新通知偏好的请求体。 */
export type NotificationPrefUpdatePayload = Schemas['NotificationPrefUpdateDTO']

/** 评价视图对象。 */
export type Review = Api<'ReviewVO'>
/** 创建评价的请求体。 */
export type ReviewCreatePayload = Schemas['ReviewCreateDTO']

/** 单条信用事件（加分/扣分流水）。 */
export type CreditEvent = Api<'CreditEventVO'>
/** 信用概览：总分/等级 + 事件分页。 */
export type CreditOverview = With<'CreditVO', { events: PageResult<CreditEvent> }>

/** 用户首页看板聚合数据。 */
export type UserDashboard = Api<'UserDashboardVO'>
/** 用户完整资料。 */
export type UserProfile = Api<'UserVO'>
/** 登录结果：token 与基础用户信息。 */
export type LoginResult = Api<'LoginVO'>
/** 文件上传结果。 */
export type UploadResult = Api<'FileVO'>

/** AI 回答引用的来源片段。 */
export type AiSource = Api<'SourceVO'>

/** AI 工具推荐请求体。 */
export type ToolRecommendRequest = Schemas['ToolRecommendDTO']
/** 单条推荐结果。 */
export type ToolRecommendItem = Api<'Item'>
/**
 * AI 推荐响应。
 *
 * recommendationId 可能为空（例如降级到纯规则推荐时不下发埋点 ID）；
 * items 为本次推荐的工具列表。
 */
export type ToolRecommendResult = With<
  'ToolRecommendVO',
  { recommendationId: number | null; items: ToolRecommendItem[] }
>

/** 工具在指定时间段的可用性结果。 */
export type AvailabilityResult = Api<'AvailabilityVO'>

/** 工具历史租赁的一个占用时间窗。 */
export interface ToolRentalHistoryWindow {
  /** 占用开始时间。 */
  startTime: string
  /** 占用结束时间。 */
  endTime: string
}

/**
 * 工具历史租赁概览。
 *
 * 只暴露统计与脱敏时间窗，不包含其他租客信息；
 * 详情页据此绘制时间轴并估算可租时段。
 */
export interface ToolRentalHistory {
  /** 工具 ID。 */
  toolId: number
  /** 已完成租赁次数。 */
  completedCount: number
  /** 历史占用时间窗列表。 */
  windows: ToolRentalHistoryWindow[]
}

/** 创建租赁订单的请求体。 */
export type RentalCreatePayload = Schemas['RentalCreateDTO']
/** 创建损坏报告的请求体。 */
export type DamageReportCreatePayload = Schemas['DamageCreateDTO']
/** 更新用户资料的请求体。 */
export type ProfileUpdatePayload = Schemas['ProfileUpdateDTO']
