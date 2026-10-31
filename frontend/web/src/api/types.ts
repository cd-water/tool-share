export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export type ToolCategory = 'WX' | 'YY' | 'QJ' | 'QT'
export type ToolStatus = 'AVAILABLE' | 'RENTED' | 'MAINTENANCE' | 'SCRAPPED'
export type DamageLevel = 'NEW' | 'LIGHT' | 'MEDIUM' | 'SEVERE'

export interface Tool {
  id: number
  code: string
  name: string
  model: string
  category: ToolCategory
  categoryName: string
  damageLevel: DamageLevel
  damageLevelName: string
  status: ToolStatus
  statusName: string
  deposit: number
  storageArea: string
  usageGuide: string
  imageUrl: string
  imageUrls: string[]
  purchaseDate: string
  supplier: string
  rentalCount: number
  lastRepairTime: string | null
  createTime: string
  updateTime: string
}

export type RentalStatus =
  | 'PENDING_PAYMENT'
  | 'RESERVED'
  | 'PICKED_UP'
  | 'RETURNED'
  | 'CANCELLED'
  | 'OVERDUE'

export type DepositStatus = 'UNPAID' | 'PAID' | 'REFUNDED'
export type DamageResult = 'INTACT' | 'MINOR' | 'SEVERE' | null

export interface Rental {
  id: number
  orderNo: string
  toolId: number
  toolCode: string
  toolName: string
  userId: number
  renterName: string
  renterPhone: string
  startTime: string
  endTime: string
  pickupPerson: string
  depositAmount: number
  depositStatus: DepositStatus
  depositStatusName: string
  status: RentalStatus
  statusName: string
  actualReturnTime: string | null
  overdueFee: number | null
  damageFee: number | null
  refundAmount: number | null
  damageResult: DamageResult
  damageResultName: string | null
  returnRemark: string | null
  cancelReason: string | null
  cancelTime: string | null
  createTime: string
  updateTime: string
}

export type DamageReportStatus =
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'REJECTED'
  | 'REPAIRING'
  | 'COMPLETED'
  | 'SCRAPPED'

export type ProgressStatus = 'WAITING_PARTS' | 'REPAIRING' | 'COMPLETED' | 'FAILED' | null
export type AcceptResult = 'QUALIFIED' | 'UNQUALIFIED' | 'PARTIAL' | null

export interface DamageReport {
  id: number
  reportNo: string
  rentalId: number
  toolId: number
  toolCode: string
  toolName: string
  userId: number
  reporterName: string
  reporterPhone: string
  damagePart: string
  description: string
  images: string[]
  status: DamageReportStatus
  statusName: string
  verifier: string | null
  verifyTime: string | null
  verifyResult: string | null
  repairPerson: string | null
  repairEstimate: string | null
  repairStartTime: string | null
  repairCost: number | null
  progressStatus: ProgressStatus
  progressStatusName: string | null
  progressUpdateTime: string | null
  progressNote: string | null
  acceptor: string | null
  acceptTime: string | null
  acceptResult: AcceptResult
  acceptResultName: string | null
  toolStatusAfter: string | null
  remark: string | null
  createTime: string
  updateTime: string
}

export type MessageType =
  | 'RESERVATION'
  | 'PICKUP'
  | 'RENT_DUE'
  | 'OVERDUE'
  | 'REPAIR'
  | 'SYSTEM'

export interface Message {
  id: number
  type: MessageType
  typeName: string
  title: string
  content: string
  channel: string
  readStatus: boolean | number | string
  createTime: string
}

export interface AiSource {
  title: string
  snippet: string
}

export interface AiChatResult {
  answer: string
  sources: AiSource[]
}

export interface UserDashboard {
  availableToolCount: number
  pendingPaymentCount: number
  rentingCount: number
  unreadCount: number
}

export interface UserProfile {
  id: number
  phone: string
  nickname: string
  avatar: string
  realName: string
  idCard: string
}

export interface UploadResult {
  url: string
  name: string
}

export interface RentalCreatePayload {
  toolId: number
  startTime: string
  endTime: string
  pickupPerson: string
}

export interface DamageReportCreatePayload {
  rentalId: number
  damagePart: string
  description: string
  images: string[]
}

export interface ProfileUpdatePayload {
  nickname: string
  avatar: string
  realName: string
  idCard?: string
}
