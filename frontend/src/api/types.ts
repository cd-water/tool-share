/**
 * 与后端 docs/API.md 一一对应的类型定义（日期均为 ISO 字符串）
 */

// ---------- 通用 ----------

/** 分页查询基类（后端 common/dto/PageQuery） */
export interface PageQuery {
  page: number
  size: number
}

/** 分页响应（后端 common/result/PageResult） */
export interface PageResult<T> {
  total: number
  records: T[]
  page: number
  size: number
}

// ---------- 用户模块 ----------

export interface LoginDTO {
  username: string
  password: string
}

export interface LoginVO {
  token: string
  userId: number
  realName: string
  /** 0居民 1社区工作人员 2系统管理员 */
  role: number
}

export interface RegisterDTO {
  username: string
  password: string
  realName: string
  phone: string
  idCard: string
}

export interface UserVO {
  id: number
  username: string
  realName: string
  /** 脱敏后手机号 */
  phone: string
  /** 0居民 1社区工作人员 2系统管理员 */
  role: number
}

export interface UserQueryDTO extends PageQuery {
  realName?: string
  role?: number
}

export interface UserRoleDTO {
  role: number
}

export interface UserStatusDTO {
  /** 1启用 0停用 */
  status: number
}

// ---------- 工具模块 ----------

export interface ToolDTO {
  name: string
  modelSpec: string
  /** 1维修 2园艺 3清洁 4其他 */
  category: number
  /** 1全新 2轻微磨损 3中度磨损 4严重损坏 */
  damageLevel: number
  /** 押金（元） */
  deposit: number
  storageLocation: string
  usageInstruction: string
  images: string[]
  purchaseDate?: string
  supplierInfo?: string
}

export interface ToolQueryDTO extends PageQuery {
  keyword?: string
  category?: number
  status?: number
  storageLocation?: string
  /** rentalCount 按租赁次数 / lastRepairTime 按最近维修时间 */
  orderBy?: string
}

export interface Tool {
  id: number
  toolCode: string
  name: string
  modelSpec: string
  category: number
  damageLevel: number
  deposit: number
  storageLocation: string
  usageInstruction: string
  images: string[]
  purchaseDate: string
  supplierInfo: string
  /** 1可用 2已借出 3维修中 4报废 5下架 */
  status: number
  /** 1待审核 2已通过 3需修改 */
  auditStatus: number
  auditOpinion: string
  createBy: number
  createTime: string
  updateTime: string
}

export interface ToolCreateVO {
  id: number
  toolCode: string
}

export interface ToolStatusDTO {
  /** 1可用 2已借出 3维修中 4报废 5下架 */
  status: number
  remark?: string
}

export interface ToolAuditDTO {
  /** 2已通过 3需修改 */
  auditStatus: number
  auditOpinion?: string
}

export interface ToolStatusLog {
  id: number
  toolId: number
  oldStatus: number
  newStatus: number
  operatorId: number
  remark: string
  createTime: string
}

// ---------- 租赁模块 ----------

export interface TimeSlotVO {
  startTime: string
  endTime: string
}

export interface RentalCreateDTO {
  toolId: number
  startTime: string
  endTime: string
  pickupName: string
}

export interface RentalCreateVO {
  id: number
  orderNo: string
}

export interface RentalPickupDTO {
  pickupName?: string
}

export interface RentalReturnDTO {
  /** 1完好 2轻微损坏 3严重损坏 */
  acceptResult: number
  overdueFee: number
  depositRefundAmount: number
}

export interface RentalQueryDTO extends PageQuery {
  userName?: string
  toolCode?: string
  acceptResult?: number
  overdue?: boolean
}

export interface RentalOrderVO {
  id: number
  orderNo: string
  toolId: number
  toolCode: string
  toolName: string
  userId: number
  userName: string
  userPhone: string
  pickupName: string
  startTime: string
  endTime: string
  depositAmount: number
  /** 1未缴 2已缴 3已退还 */
  depositStatus: number
  /** 1待支付 2已预约 3已取件 4已归还 5已取消 */
  status: number
  cancelTime: string
  returnTime: string
  /** 1完好 2轻微损坏 3严重损坏 */
  acceptResult: number
  overdueFee: number
  depositRefundAmount: number
  createTime: string
  updateTime: string
}

// ---------- 报修模块 ----------

export interface RepairCreateDTO {
  rentalOrderId: number
  toolId: number
  damagePart: string
  damageDesc: string
  /** 1-3 张图片 URL */
  images?: string[]
}

export interface RepairCreateVO {
  id: number
  repairNo: string
}

export interface RepairQueryDTO extends PageQuery {
  toolCode?: string
  /** 1待配件 2维修中 3维修完成 4维修失败（报废） */
  repairStatus?: number
}

export interface RepairProgress {
  id: number
  repairId: number
  status: number
  description: string
  operatorId: number
  createTime: string
}

export interface RepairOrderVO {
  id: number
  repairNo: string
  rentalOrderId: number
  orderNo: string
  toolId: number
  toolCode: string
  toolName: string
  reporterId: number
  reporterName: string
  reporterPhone: string
  damagePart: string
  damageDesc: string
  images: string[]
  createTime: string
  verifyBy: number
  verifyByName: string
  verifyTime: string
  /** 1确认损坏 2疑似损坏 3误报 */
  verifyResult: number
  repairerName: string
  expectDuration: string
  repairStartTime: string
  /** 1待配件 2维修中 3维修完成 4维修失败（报废） */
  repairStatus: number
  repairFee: number
  acceptBy: number
  acceptByName: string
  acceptTime: string
  /** 1合格 2不合格 3部分合格 */
  acceptResult: number
  remark: string
  progressList: RepairProgress[]
}

export interface RepairVerifyDTO {
  verifyResult: number
  remark?: string
}

export interface RepairProgressDTO {
  status: number
  description?: string
}

export interface RepairAcceptDTO {
  acceptResult: number
  /** 验收后工具状态：1可用 3维修中 4报废 */
  toolStatus: number
  remark?: string
}

// ---------- 消息提醒模块 ----------

export interface NotifyQueryDTO extends PageQuery {
  /** 1取件 2到期 3逾期 4维修完成 */
  type?: number
  /** 0未读 1已读 */
  readStatus?: number
}

export interface Message {
  id: number
  userId: number
  type: number
  title: string
  content: string
  /** 1系统内 2短信 */
  channel: number
  /** 1待发送 2已发送 3发送失败 */
  sendStatus: number
  sendTime: string
  bizNo: string
  readStatus: number
  createTime: string
}

export interface NotifyConfigDTO {
  /** 1系统内 2短信 */
  channel: number
}

// ---------- AI 模块 ----------

export interface ChatDTO {
  question: string
}

export interface ChatSourceVO {
  content: string
  documentName: string
}

export interface ChatVO {
  answer: string
  sources: ChatSourceVO[]
}

export interface KbDocument {
  id: number
  fileName: string
  fileUrl: string
  fileSize: number
  chunkCount: number
  /** 1解析中 2完成 3失败 */
  status: number
  uploadBy: number
  createTime: string
}

// ---------- 文件模块 ----------

export interface FileVO {
  url: string
}
