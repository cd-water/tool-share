/** 与 docs/API.md 一一对应的传输类型 */

export type Role = 'RESIDENT' | 'STAFF' | 'ADMIN'

export interface UserInfo {
  id: number
  username: string
  realName: string
  phone?: string
  role: Role
  status?: string
  createTime?: string
}

export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface PageQuery {
  page?: number
  size?: number
}

// ---------- 认证 / 用户 / 配置 ----------

export interface LoginData {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  user: UserInfo
}

export interface UserQuery extends PageQuery {
  role?: Role
  keyword?: string
}

export interface ConfigItem {
  configKey: string
  configValue: string
  remark?: string
}

// ---------- 工具 ----------

export interface CategoryItem {
  id: number
  code: string
  name: string
}

export interface TimeRange {
  start: string
  end: string
}

export interface Availability {
  date: string
  busy: TimeRange[]
}

export interface ToolImage {
  id: number
  url: string
  sort: number
}

export interface ToolQuery extends PageQuery {
  keyword?: string
  categoryId?: number
  damageLevel?: string
  status?: string
  storageArea?: string
}

export interface ToolItem {
  id: number
  toolCode: string
  name: string
  model: string
  categoryId: number
  categoryName: string
  coverImage?: string
  damageLevel: string
  depositAmount: number
  status: string
  rentalCount: number
  lastRepairTime?: string
  auditStatus: string
}

export interface ToolDetail {
  id: number
  toolCode: string
  name: string
  model: string
  categoryId: number
  categoryName: string
  description: string
  damageLevel: string
  depositAmount: number
  storageArea: string
  purchaseDate?: string
  supplier?: string
  status: string
  rentalCount: number
  lastRepairTime?: string
  auditStatus: string
  auditRemark?: string
  images: ToolImage[]
}

export interface ToolCreateData {
  name: string
  model: string
  categoryId: number
  storageArea: string
  description: string
  damageLevel: string
  depositAmount: number
  purchaseDate?: string
  supplier?: string
}

export interface ToolCreated {
  id: number
  toolCode: string
}

// ---------- 租赁 ----------

export interface RentalCreateData {
  toolId: number
  startTime: string
  endTime: string
  pickupPerson?: string
}

export interface RentalCreated {
  id: number
  orderCode: string
  depositAmount: number
  status: string
}

export interface Suggestion {
  suggested: boolean
  startTime?: string
  endTime?: string
}

export interface PayResult {
  payCode: string
  amount: number
  payTime: string
}

export interface ReturnData {
  acceptResult: string
  damageLevel: string
  compensationFee?: number
}

export interface Settlement {
  overdueFee: number
  compensationFee: number
  refundAmount: number
}

export interface RentalOrderItem {
  id: number
  orderCode: string
  toolId: number
  toolCode: string
  toolName: string
  userName?: string
  userPhone?: string
  pickupPerson: string
  startTime: string
  endTime: string
  depositAmount: number
  depositStatus: string
  status: string
  pickupTime?: string
  returnTime?: string
  acceptResult?: string
  overdueFee: number
  compensationFee: number
  cancelReason?: string
  createTime: string
}

export interface RentalQuery extends PageQuery {
  userName?: string
  toolCode?: string
  acceptResult?: string
  status?: string
  overdue?: boolean
}

// ---------- 报修 ----------

export interface RepairCreateData {
  toolId: number
  orderId?: number
  damagePart: string
  description: string
  imageUrls?: string[]
}

export interface RepairCreated {
  id: number
  repairCode: string
}

export interface RepairItem {
  id: number
  repairCode: string
  toolId: number
  toolName: string
  reporterName?: string
  damagePart: string
  description: string
  status: string
  createTime: string
}

export interface RepairProgress {
  id: number
  content: string
  operatorName: string
  createTime: string
}

export interface RepairDetail {
  id: number
  repairCode: string
  toolId: number
  toolName: string
  orderId?: number
  orderCode?: string
  reporterId: number
  damagePart: string
  description: string
  status: string
  handlerName?: string
  estimatedDuration?: string
  repairFee?: number
  repairDescription?: string
  acceptResult?: string
  rejectReason?: string
  images: string[]
  progress: RepairProgress[]
}

export interface RepairQuery extends PageQuery {
  status?: string
  toolCode?: string
}

// ---------- 消息 / 知识库 / 问答 / 上传 ----------

export interface MsgItem {
  id: number
  type: string
  title: string
  content: string
  channel: string
  sendStatus: string
  sendTime?: string
  bizCode?: string
  createTime: string
}

export interface MsgQuery extends PageQuery {
  type?: string
  userId?: number
}

export interface KbDocCreated {
  id: number
  docName: string
  status: string
}

export interface KbDocItem {
  id: number
  docName: string
  docType: string
  toolId?: number
  toolName?: string
  status: string
  chunkCount: number
  createTime: string
}

export interface ChatResult {
  answer: string
  sources: { docName: string; content: string }[]
}

export interface UploadResult {
  url: string
}
