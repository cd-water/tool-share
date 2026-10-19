/**
 * 前端 Mock 数据（内存存储，不发真实请求）
 *
 * 切换方式：把下方的 USE_MOCK 改为 false 即恢复真实后端。
 * 数据结构与 api/types.ts 保持一致，由 api/request.ts 在请求层拦截。
 */
import type {
  ChatSourceVO,
  ChatVO,
  KbDocument,
  LoginVO,
  Message,
  PageResult,
  RepairCreateVO,
  RepairOrderVO,
  RentalCreateVO,
  RentalOrderVO,
  TimeSlotVO,
  Tool,
  ToolStatusLog,
  UserVO,
} from '../api/types'

export const USE_MOCK = false

// ---------- 账号（与 V2__init_seed.sql 一致） ----------
const USERS: { username: string; password: string; vo: LoginVO }[] = [
  { username: 'admin', password: 'Aa123456', vo: { token: 'mock-token-admin', userId: 1, realName: '管理员', role: 2 } },
  { username: 'lisi', password: 'Aa123456', vo: { token: 'mock-token-staff', userId: 2, realName: '李四', role: 1 } },
  { username: 'zhangsan', password: 'Aa123456', vo: { token: 'mock-token-resident', userId: 3, realName: '张三', role: 0 } },
]

// ---------- 工具（12 条，覆盖分页、筛选、各种状态） ----------
type MockTool = Tool & {
  rentalCount: number
  lastRepairTime: string
}

const TOOLS: MockTool[] = [
  {
    id: 1, toolCode: 'SQ-G-WX-0001', name: '手电钻', modelSpec: '博世 GSB550（550W）',
    category: 1, damageLevel: 2, deposit: 200, storageLocation: '社区服务站 2 号工具柜',
    usageInstruction: '操作前检查电源线，钻孔时垂直对准目标位置。',
    images: [], purchaseDate: '2024-03-15', supplierInfo: '鼎力机电设备有限公司',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-15 09:25:00', updateTime: '2026-09-12 17:40:00',
    rentalCount: 12, lastRepairTime: '2026-09-12 17:40:00',
  },
  {
    id: 2, toolCode: 'SQ-G-WX-0002', name: '电动螺丝刀', modelSpec: '绿巨力充电式（3.6V）',
    category: 1, damageLevel: 2, deposit: 150, storageLocation: '社区服务站 2 号工具柜',
    usageInstruction: '使用前确认电量，拧螺丝时保持垂直。',
    images: [], purchaseDate: '2024-05-20', supplierInfo: '鼎力机电设备有限公司',
    status: 2, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-15 09:40:00', updateTime: '2026-10-16 09:10:00',
    rentalCount: 8, lastRepairTime: '2026-07-08 10:00:00',
  },
  {
    id: 3, toolCode: 'SQ-G-WX-0003', name: '电动扳手', modelSpec: '得力 EDL-580（350N·m）',
    category: 1, damageLevel: 4, deposit: 150, storageLocation: '社区服务站 2 号工具柜',
    usageInstruction: '根据螺母规格选择套筒，握持平稳后启动。',
    images: [], purchaseDate: '2023-11-08', supplierInfo: '鼎力机电设备有限公司',
    status: 3, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-15 09:50:00', updateTime: '2026-10-04 14:20:00',
    rentalCount: 5, lastRepairTime: '2026-10-04 14:20:00',
  },
  {
    id: 4, toolCode: 'SQ-G-YY-0001', name: '修枝剪', modelSpec: '张小泉园林剪（210mm）',
    category: 2, damageLevel: 3, deposit: 50, storageLocation: '社区服务站 3 号工具柜',
    usageInstruction: '修剪前确认刀口锋利，粗枝用剪口根部发力。',
    images: [], purchaseDate: '2023-04-12', supplierInfo: '绿洲园艺用品商行',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-15 10:00:00', updateTime: '2026-09-22 18:10:00',
    rentalCount: 9, lastRepairTime: '2026-05-30 09:00:00',
  },
  {
    id: 5, toolCode: 'SQ-G-YY-0002', name: '割草机', modelSpec: '本田 UMK450（幅宽 450mm）',
    category: 2, damageLevel: 2, deposit: 300, storageLocation: '社区服务站 3 号工具柜',
    usageInstruction: '使用前检查油液位，远离火源加油。',
    images: [], purchaseDate: '2022-09-01', supplierInfo: '绿洲园艺用品商行',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-15 10:10:00', updateTime: '2026-08-26 09:50:00',
    rentalCount: 6, lastRepairTime: '2026-08-26 09:50:00',
  },
  {
    id: 6, toolCode: 'SQ-G-YY-0003', name: '浇水喷壶', modelSpec: '乐扣 3L 气压式',
    category: 2, damageLevel: 1, deposit: 20, storageLocation: '社区服务站 3 号工具柜',
    usageInstruction: '使用前打压，喷头可调节雾状或柱状。',
    images: [], purchaseDate: '2025-02-18', supplierInfo: '绿洲园艺用品商行',
    status: 1, auditStatus: 3, auditOpinion: '请补充喷壶口径规格照片后重新提交审核', createBy: 2,
    createTime: '2026-09-28 15:00:00', updateTime: '2026-09-29 10:00:00',
    rentalCount: 2, lastRepairTime: '',
  },
  {
    id: 7, toolCode: 'SQ-G-QJ-0001', name: '高压洗车水枪', modelSpec: '凯驰 K2（110bar）',
    category: 3, damageLevel: 2, deposit: 100, storageLocation: '社区服务站 1 号工具柜',
    usageInstruction: '连接水源后启动，避免空转。',
    images: [], purchaseDate: '2023-06-11', supplierInfo: '洁美清洁设备行',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-16 09:00:00', updateTime: '2026-10-13 09:00:00',
    rentalCount: 15, lastRepairTime: '2026-06-18 11:20:00',
  },
  {
    id: 8, toolCode: 'SQ-G-QJ-0002', name: '家用吸尘器', modelSpec: '小米无线 K10（120AW）',
    category: 3, damageLevel: 3, deposit: 120, storageLocation: '社区服务站 1 号工具柜',
    usageInstruction: '定期清理尘盒与滤网。',
    images: [], purchaseDate: '2024-01-20', supplierInfo: '洁美清洁设备行',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-16 09:10:00', updateTime: '2026-09-30 16:30:00',
    rentalCount: 4, lastRepairTime: '2026-09-30 16:30:00',
  },
  {
    id: 9, toolCode: 'SQ-G-QT-0001', name: '家用折叠梯', modelSpec: '稳固达四步铝梯（150kg）',
    category: 4, damageLevel: 2, deposit: 80, storageLocation: '社区服务站 1 号工具柜',
    usageInstruction: '展开后确认卡扣锁紧，禁止侧向受力。',
    images: [], purchaseDate: '2024-08-08', supplierInfo: '安居五金商行',
    status: 1, auditStatus: 1, auditOpinion: '', createBy: 2,
    createTime: '2026-10-17 14:00:00', updateTime: '2026-10-17 14:00:00',
    rentalCount: 3, lastRepairTime: '',
  },
  {
    id: 10, toolCode: 'SQ-G-QT-0002', name: '手推车', modelSpec: '金鸿平板车（300kg）',
    category: 4, damageLevel: 4, deposit: 100, storageLocation: '社区服务站 1 号工具柜',
    usageInstruction: '装载时重心居中，坡道推行注意制动。',
    images: [], purchaseDate: '2021-12-01', supplierInfo: '安居五金商行',
    status: 4, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-16 09:20:00', updateTime: '2026-10-10 10:00:00',
    rentalCount: 7, lastRepairTime: '2026-10-10 10:00:00',
  },
  {
    id: 11, toolCode: 'SQ-G-QJ-0003', name: '地板打蜡机', modelSpec: '家用型 配蜡套装',
    category: 3, damageLevel: 4, deposit: 180, storageLocation: '社区服务站 1 号工具柜',
    usageInstruction: '打蜡前清理地面，匀速推行。',
    images: [], purchaseDate: '2022-05-05', supplierInfo: '洁美清洁设备行',
    status: 5, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-16 09:30:00', updateTime: '2026-10-01 09:00:00',
    rentalCount: 2, lastRepairTime: '2026-05-30 09:00:00',
  },
  {
    id: 12, toolCode: 'SQ-G-WX-0004', name: '冲击钻套装', modelSpec: '26 电锤 3 用款',
    category: 1, damageLevel: 3, deposit: 280, storageLocation: '社区服务站 2 号工具柜',
    usageInstruction: '钻孔前确认墙体无暗管，佩戴护目镜。',
    images: [], purchaseDate: '2023-09-19', supplierInfo: '鼎力机电设备有限公司',
    status: 1, auditStatus: 2, auditOpinion: '', createBy: 2,
    createTime: '2026-08-16 09:40:00', updateTime: '2026-07-22 15:00:00',
    rentalCount: 11, lastRepairTime: '2026-07-22 15:00:00',
  },
]

// ---------- 知识库文档（内存） ----------
const KB_DOCS: KbDocument[] = [
  {
    id: 1, fileName: '充电钻使用手册.pdf', fileUrl: '', fileSize: 2.4 * 1024 * 1024,
    chunkCount: 12, status: 2, uploadBy: 1, createTime: '2026-03-16 10:30:00',
  },
  {
    id: 2, fileName: '社区工具租赁规则.docx', fileUrl: '', fileSize: 1.1 * 1024 * 1024,
    chunkCount: 8, status: 2, uploadBy: 1, createTime: '2026-03-16 10:35:00',
  },
  {
    id: 3, fileName: '高空作业安全指引.pdf', fileUrl: '', fileSize: 3.2 * 1024 * 1024,
    chunkCount: 15, status: 2, uploadBy: 1, createTime: '2026-04-02 09:12:00',
  },
  {
    id: 4, fileName: '园艺工具养护指南.pdf', fileUrl: '', fileSize: 1.8 * 1024 * 1024,
    chunkCount: 0, status: 1, uploadBy: 1, createTime: '2026-10-18 20:41:00',
  },
]

// 模拟解析中的文档在 4 秒后完成
setTimeout(() => {
  const doc = KB_DOCS.find((d) => d.id === 4)
  if (doc && doc.status === 1) {
    doc.status = 2
    doc.chunkCount = 9
  }
}, 4000)

// ---------- 用户（内存） ----------
interface MockUser {
  id: number
  username: string
  realName: string
  phone: string
  role: number
  status: number
  createTime: string
}

const USER_LIST: MockUser[] = [
  { id: 1, username: 'admin', realName: '系统管理员', phone: '135****0001', role: 2, status: 1, createTime: '2026-01-01 09:00' },
  { id: 2, username: 'lisi', realName: '李四', phone: '137****1234', role: 1, status: 1, createTime: '2026-01-02 10:20' },
  { id: 3, username: 'zhangsan', realName: '张三', phone: '138****5678', role: 0, status: 1, createTime: '2026-02-11 14:35' },
  { id: 4, username: 'wangwu', realName: '王五', phone: '139****2210', role: 0, status: 0, createTime: '2026-03-08 09:12' },
  { id: 5, username: 'zhaoliu', realName: '赵六', phone: '136****3345', role: 0, status: 1, createTime: '2026-04-19 16:40' },
  { id: 6, username: 'sunyi', realName: '孙阿姨', phone: '136****7788', role: 0, status: 1, createTime: '2026-05-02 08:55' },
]

function pageUsersMock(payload: Record<string, unknown>): PageResult<UserVO> {
  const realName = String(payload.realName ?? '').trim()
  const role =
    payload.role === undefined || payload.role === null || payload.role === ''
      ? -1
      : Number(payload.role)

  let rows = USER_LIST.slice()
  if (realName) rows = rows.filter((u) => u.realName.includes(realName))
  if (role >= 0) rows = rows.filter((u) => u.role === role)

  const page = Math.max(1, Number(payload.page) || 1)
  const size = Math.max(1, Number(payload.size) || 10)
  return {
    total: rows.length,
    records: rows.slice((page - 1) * size, page * size),
    page,
    size,
  }
}

// ---------- 工具状态日志（内存） ----------
const TOOL_LOGS: Record<number, ToolStatusLog[]> = {
  1: [
    {
      id: 1, toolId: 1, oldStatus: 2, newStatus: 1, operatorId: 2,
      remark: '归还验收：完好', createTime: '2026-09-21 09:10:00',
    },
    {
      id: 2, toolId: 1, oldStatus: 1, newStatus: 2, operatorId: 2,
      remark: '预约单 SQ-ZY-20260920-0002 取件', createTime: '2026-09-20 14:32:00',
    },
  ],
  3: [
    {
      id: 3, toolId: 3, oldStatus: 1, newStatus: 3, operatorId: 2,
      remark: '报修单 SQ-BX-20261012-0002 确认损坏', createTime: '2026-10-13 09:30:00',
    },
  ],
}
let nextToolLogId = 4

// ---------- 租赁（内存） ----------
interface MockRental {
  id: number
  orderNo: string
  toolId: number
  startTime: string
  endTime: string
  pickupName: string
  /** 1待支付 2已预约 3已取件 4已归还 5已取消 */
  status: number
  userId: number
  depositAmount: number
  /** 1未缴 2已缴 3已退还 */
  depositStatus: number
  /** 0未验收 1完好 2轻微损坏 3严重损坏 */
  acceptResult: number
  overdueFee: number
  depositRefundAmount: number
  cancelTime: string
  returnTime: string
  createTime: string
  updateTime: string
}

function seedRental(
  partial: Pick<MockRental, 'id' | 'orderNo' | 'toolId' | 'startTime' | 'endTime' | 'status'> &
    Partial<MockRental>,
): MockRental {
  return {
    pickupName: '张三',
    userId: 3,
    depositAmount: 0,
    depositStatus: 1,
    acceptResult: 0,
    overdueFee: 0,
    depositRefundAmount: 0,
    cancelTime: '',
    returnTime: '',
    createTime: partial.startTime,
    updateTime: partial.startTime,
    ...partial,
  }
}

const rentals: MockRental[] = [
  seedRental({
    id: 101, orderNo: 'SQ-ZY-20261019-0008', toolId: 6,
    startTime: '2026-10-20T09:00:00', endTime: '2026-10-20T12:00:00',
    status: 1, depositAmount: 60, createTime: '2026-10-19T14:30:00',
  }),
  seedRental({
    id: 102, orderNo: 'SQ-ZY-20261016-0001', toolId: 1,
    startTime: '2026-10-16T09:00:00', endTime: '2026-10-19T08:00:00',
    status: 3, depositAmount: 200, depositStatus: 2, createTime: '2026-10-16T08:10:00',
  }),
  seedRental({
    id: 103, orderNo: 'SQ-ZY-20261010-0001', toolId: 7,
    startTime: '2026-10-10T09:00:00', endTime: '2026-10-12T18:00:00',
    status: 4, depositAmount: 100, depositStatus: 3, acceptResult: 1,
    depositRefundAmount: 100, returnTime: '2026-10-12T17:40:00',
    createTime: '2026-10-10T08:20:00',
  }),
  seedRental({
    id: 104, orderNo: 'SQ-ZY-20260928-0002', toolId: 5,
    startTime: '2026-09-29T08:00:00', endTime: '2026-09-30T18:00:00',
    status: 4, depositAmount: 300, depositStatus: 3, acceptResult: 2,
    overdueFee: 20, depositRefundAmount: 280, returnTime: '2026-10-01T09:30:00',
    createTime: '2026-09-28T19:00:00',
  }),
  seedRental({
    id: 105, orderNo: 'SQ-ZY-20261005-0005', toolId: 9,
    startTime: '2026-10-06T09:00:00', endTime: '2026-10-07T18:00:00',
    status: 5, depositAmount: 80, depositStatus: 3,
    depositRefundAmount: 80, cancelTime: '2026-10-05T20:00:00',
    createTime: '2026-10-05T18:00:00',
  }),
  // 距取件不足 24 小时，用于验证取消失败提示
  seedRental({
    id: 106, orderNo: 'SQ-ZY-20261018-0002', toolId: 2,
    startTime: '2026-10-20T08:00:00', endTime: '2026-10-20T18:00:00',
    status: 2, depositAmount: 150, depositStatus: 2, createTime: '2026-10-18T09:00:00',
  }),
  // 距取件超过 24 小时，用于验证取消成功
  seedRental({
    id: 107, orderNo: 'SQ-ZY-20261017-0001', toolId: 8,
    startTime: '2026-10-25T09:00:00', endTime: '2026-10-26T18:00:00',
    status: 2, depositAmount: 120, depositStatus: 2, createTime: '2026-10-17T10:00:00',
  }),
]
let nextRentalId = 200

function sleep(ms = 250) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function todayCompact() {
  const d = new Date()
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
}

function todayCompactWithDash() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 09:00-18:00 按小时切分，剔除已预约/已取件的重叠时段 */
function availableTimes(toolId: number, date: string): TimeSlotVO[] {
  const slots: TimeSlotVO[] = []
  for (let hour = 9; hour < 18; hour++) {
    slots.push({
      startTime: `${date}T${pad(hour)}:00:00`,
      endTime: `${date}T${pad(hour + 1)}:00:00`,
    })
  }
  return slots.filter((slot) =>
    rentals.every(
      (r) =>
        r.toolId !== toolId ||
        (r.status !== 2 && r.status !== 3) ||
        !(r.startTime < slot.endTime && slot.startTime < r.endTime),
    ),
  )
}

function pageTools(payload: Record<string, unknown>): PageResult<Tool> {
  const keyword = String(payload.keyword ?? '').trim().toLowerCase()
  const category = Number(payload.category ?? 0)
  const status = Number(payload.status ?? 0)
  const orderBy = String(payload.orderBy ?? '')

  let rows = TOOLS.filter((tool) => {
    if (category && tool.category !== category) return false
    if (status && tool.status !== status) return false
    if (keyword) {
      const hit = [tool.name, tool.modelSpec, tool.toolCode].some((v) =>
        v.toLowerCase().includes(keyword),
      )
      if (!hit) return false
    }
    return true
  })

  if (orderBy === 'rentalCount') rows = rows.slice().sort((a, b) => b.rentalCount - a.rentalCount)
  if (orderBy === 'lastRepairTime') {
    rows = rows
      .slice()
      .sort((a, b) => (b.lastRepairTime || '').localeCompare(a.lastRepairTime || ''))
  }

  const page = Math.max(1, Number(payload.page) || 1)
  const size = Math.max(1, Number(payload.size) || 10)
  const total = rows.length
  return {
    total,
    records: rows.slice((page - 1) * size, page * size),
    page,
    size,
  }
}

function nowText() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function rentalVO(rental: MockRental): RentalOrderVO {
  const tool = TOOLS.find((t) => t.id === rental.toolId)
  return {
    id: rental.id,
    orderNo: rental.orderNo,
    toolId: rental.toolId,
    toolCode: tool?.toolCode ?? '',
    toolName: tool?.name ?? '',
    userId: rental.userId,
    userName: '张三',
    userPhone: '138****0003',
    pickupName: rental.pickupName,
    startTime: rental.startTime,
    endTime: rental.endTime,
    depositAmount: rental.depositAmount,
    depositStatus: rental.depositStatus,
    status: rental.status,
    cancelTime: rental.cancelTime,
    returnTime: rental.returnTime,
    acceptResult: rental.acceptResult,
    overdueFee: rental.overdueFee,
    depositRefundAmount: rental.depositRefundAmount,
    createTime: rental.createTime,
    updateTime: rental.updateTime,
  }
}

function pageRentals(payload: Record<string, unknown>): PageResult<RentalOrderVO> {
  const userName = String(payload.userName ?? '').trim()
  const toolCode = String(payload.toolCode ?? '').trim().toLowerCase()
  const acceptResult = Number(payload.acceptResult ?? 0)

  let rows = rentals
    .map(rentalVO)
    .sort((a, b) => (b.createTime || '').localeCompare(a.createTime || ''))
  if (userName) rows = rows.filter((r) => r.userName.includes(userName))
  if (toolCode) rows = rows.filter((r) => r.toolCode.toLowerCase().includes(toolCode))
  if (acceptResult) rows = rows.filter((r) => r.acceptResult === acceptResult)
  if (payload.overdue === true) rows = rows.filter((r) => r.overdueFee > 0)
  if (payload.overdue === false) rows = rows.filter((r) => r.overdueFee === 0)

  const page = Math.max(1, Number(payload.page) || 1)
  const size = Math.max(1, Number(payload.size) || 10)
  return {
    total: rows.length,
    records: rows.slice((page - 1) * size, page * size),
    page,
    size,
  }
}

// ---------- 报修（内存） ----------
const REPAIRS: RepairOrderVO[] = [
  {
    id: 201,
    repairNo: 'SQ-BX-20261012-0002',
    rentalOrderId: 102,
    orderNo: 'SQ-ZY-20261016-0001',
    toolId: 1,
    toolCode: 'SQ-G-WX-0001',
    toolName: '手电钻',
    reporterId: 3,
    reporterName: '张三',
    reporterPhone: '138****0003',
    damagePart: '钻头夹头',
    damageDesc: '夹头无法夹紧钻头，钻孔时打滑',
    images: [],
    createTime: '2026-10-12 20:14:00',
    verifyBy: 2,
    verifyByName: '李四',
    verifyTime: '2026-10-13 09:30:00',
    verifyResult: 1,
    repairerName: '李四',
    expectDuration: '3 天',
    repairStartTime: '2026-10-14 15:02:00',
    repairStatus: 2,
    repairFee: 0,
    acceptBy: 0,
    acceptByName: '',
    acceptTime: '',
    acceptResult: 0,
    remark: '',
    progressList: [
      {
        id: 1, repairId: 201, status: 1, description: '现场核验：确认损坏，工具转入维修中',
        operatorId: 2, createTime: '2026-10-13 09:30:00',
      },
      {
        id: 2, repairId: 201, status: 1, description: '待配件：夹头总成缺货，已订购',
        operatorId: 2, createTime: '2026-10-14 15:02:00',
      },
      {
        id: 3, repairId: 201, status: 2, description: '维修中：配件到货，更换夹头总成',
        operatorId: 2, createTime: '2026-10-16 11:20:00',
      },
    ],
  },
  {
    id: 203,
    repairNo: 'SQ-BX-20261018-0004',
    rentalOrderId: 102,
    orderNo: 'SQ-ZY-20261016-0001',
    toolId: 5,
    toolCode: 'SQ-G-YY-0002',
    toolName: '割草机',
    reporterId: 3,
    reporterName: '张三',
    reporterPhone: '138****0003',
    damagePart: '电源线',
    damageDesc: '电源线破损，通电无反应',
    images: [],
    createTime: '2026-10-18 21:05:00',
    verifyBy: 0,
    verifyByName: '',
    verifyTime: '',
    verifyResult: 0,
    repairerName: '',
    expectDuration: '',
    repairStartTime: '',
    repairStatus: 1,
    repairFee: 0,
    acceptBy: 0,
    acceptByName: '',
    acceptTime: '',
    acceptResult: 0,
    remark: '',
    progressList: [],
  },
  {
    id: 202,
    repairNo: 'SQ-BX-20261015-0003',
    rentalOrderId: 103,
    orderNo: 'SQ-ZY-20261010-0001',
    toolId: 7,
    toolCode: 'SQ-G-QJ-0001',
    toolName: '高压洗车水枪',
    reporterId: 3,
    reporterName: '张三',
    reporterPhone: '138****0003',
    damagePart: '进水口滤网',
    damageDesc: '进水口滤网堵塞，水压明显不足',
    images: [],
    createTime: '2026-10-15 10:41:00',
    verifyBy: 2,
    verifyByName: '李四',
    verifyTime: '2026-10-15 14:10:00',
    verifyResult: 1,
    repairerName: '李四',
    expectDuration: '1 天',
    repairStartTime: '2026-10-15 16:30:00',
    repairStatus: 3,
    repairFee: 0,
    acceptBy: 2,
    acceptByName: '李四',
    acceptTime: '2026-10-16 09:15:00',
    acceptResult: 1,
    remark: '验收合格，工具已重新上架',
    progressList: [
      {
        id: 4, repairId: 202, status: 1, description: '现场核验：确认损坏，工具转入维修中',
        operatorId: 2, createTime: '2026-10-15 14:10:00',
      },
      {
        id: 5, repairId: 202, status: 2, description: '维修中：清理并更换进水滤网',
        operatorId: 2, createTime: '2026-10-15 16:30:00',
      },
      {
        id: 6, repairId: 202, status: 3, description: '维修完成，验收合格，工具重新上架',
        operatorId: 2, createTime: '2026-10-16 09:15:00',
      },
    ],
  },
]

// ---------- 消息（内存） ----------
const MESSAGES: Message[] = [
  {
    id: 5, userId: 3, type: 1, title: '取件提醒',
    content: '您预约的「果树修枝剪套装」将于明天 09:00 开始可取件，请携带身份证或预约单号前往社区服务站。',
    channel: 1, sendStatus: 2, sendTime: '2026-10-18 18:00:00',
    bizNo: 'SQ-ZY-20261019-0008', readStatus: 0, createTime: '2026-10-18 18:00:00',
  },
  {
    id: 4, userId: 3, type: 2, title: '到期提醒',
    content: '您租赁的「手电钻」将于今天 18:00 到期，请及时归还，逾期将产生逾期费用。',
    channel: 1, sendStatus: 2, sendTime: '2026-10-17 09:00:00',
    bizNo: 'SQ-ZY-20261016-0001', readStatus: 0, createTime: '2026-10-17 09:00:00',
  },
  {
    id: 3, userId: 3, type: 4, title: '维修完成提醒',
    content: '报修单 SQ-BX-20261015-0003（高压洗车水枪）已维修完成并验收合格，工具重新上架，可继续预约。',
    channel: 1, sendStatus: 2, sendTime: '2026-10-16 09:20:00',
    bizNo: 'SQ-BX-20261015-0003', readStatus: 0, createTime: '2026-10-16 09:20:00',
  },
  {
    id: 2, userId: 3, type: 3, title: '逾期提醒',
    content: '您租赁的「割草机」已逾期，逾期费用按日计收（封顶押金金额），请尽快归还。',
    channel: 2, sendStatus: 2, sendTime: '2026-10-01 08:30:00',
    bizNo: 'SQ-ZY-20260928-0002', readStatus: 1, createTime: '2026-10-01 08:30:00',
  },
  {
    id: 1, userId: 3, type: 1, title: '取件提醒',
    content: '您预约的「高压洗车水枪」明天可取件，请携带身份证或预约单号。',
    channel: 1, sendStatus: 2, sendTime: '2026-10-10 18:00:00',
    bizNo: 'SQ-ZY-20261010-0001', readStatus: 1, createTime: '2026-10-10 18:00:00',
  },
]

function pageMessages(payload: Record<string, unknown>): PageResult<Message> {
  const type = Number(payload.type ?? 0)
  const hasReadStatus =
    payload.readStatus !== undefined && payload.readStatus !== null && payload.readStatus !== ''
  const readStatus = hasReadStatus ? Number(payload.readStatus) : -1

  let rows = MESSAGES.slice().sort((a, b) => (b.createTime || '').localeCompare(a.createTime || ''))
  if (type) rows = rows.filter((m) => m.type === type)
  if (hasReadStatus) rows = rows.filter((m) => m.readStatus === readStatus)

  const page = Math.max(1, Number(payload.page) || 1)
  const size = Math.max(1, Number(payload.size) || 10)
  return {
    total: rows.length,
    records: rows.slice((page - 1) * size, page * size),
    page,
    size,
  }
}

// ---------- AI 问答（关键词命中） ----------
const AI_KB: { pattern: RegExp; answer: string; source: ChatSourceVO }[] = [
  {
    pattern: /押金|钱/,
    answer:
      '押金金额由每件工具预设（如手电钻 ¥200、割草机 ¥300），预约时缴纳；归还验收合格后原路退还。若产生逾期费用或损坏赔偿，将从押金中扣除后退还余款。',
    source: {
      documentName: '社区工具租赁规则.docx',
      content: '押金缴纳：预约生成后需在 30 分钟内完成押金缴纳，逾期预约自动取消；归还验收合格后押金于 1 个工作日内原路退还。',
    },
  },
  {
    pattern: /逾期/,
    answer:
      '逾期费用按日计收，费率为押金的 10%/日，累计不超过押金金额（封顶）。例如押金 ¥200 的工具逾期 3 天，逾期费为 ¥60。',
    source: {
      documentName: '社区工具租赁规则.docx',
      content: '逾期费用：自约定归还时间次日起按日计收，费率为押金的 10%/日，逾期费用总额以押金金额为上限。',
    },
  },
  {
    pattern: /损坏|报修/,
    answer:
      '租赁期间发现损坏，请在「我的租赁」中对应单据发起报修，填写损坏部位、描述并上传材料图片；工作人员会现场核验并安排维修，可在「损坏报修」中跟踪进度。',
    source: {
      documentName: '电钻使用手册.pdf',
      content: '异常处理：使用中出现异响、冒烟、夹头打滑等情况，请立即停机并联系社区服务站，切勿自行拆修。',
    },
  },
  {
    pattern: /取件/,
    answer:
      '取件时间为预约开始时间，请携带身份证或预约单号前往社区服务站；如需他人代取，取件时登记代取人姓名即可。',
    source: {
      documentName: '社区工具租赁规则.docx',
      content: '取件：居民凭身份证或预约单号取件；代取需登记代取人姓名与联系方式。',
    },
  },
  {
    pattern: /钻头|电钻|换/,
    answer:
      '更换钻头步骤：① 确认开关关闭并断开电池；② 用夹头钥匙逆时针旋松夹头；③ 取出旧钻头，装入新钻头并扶正；④ 顺时针旋紧夹头（三点孔位依次紧固）；⑤ 空载低速试转 3 秒确认无摆动。',
    source: {
      documentName: '电钻使用手册.pdf',
      content: '钻头更换：务必在断电状态下操作，夹头需三点依次紧固，安装后应空载低速试运行。',
    },
  },
  {
    pattern: /割草|园艺/,
    answer:
      '割草机使用注意：作业前清理场地石块与杂物；穿戴防滑鞋与护目镜；坡地作业沿横向切割；每作业 2 小时停机冷却；严禁在热机时加注汽油。',
    source: {
      documentName: '园艺工具养护指南.pdf',
      content: '安全操作：割草机作业前须清理场地硬物，15 米范围内不得有无关人员。',
    },
  },
  {
    pattern: /安全|注意/,
    answer:
      '通用安全要求：作业前阅读使用说明并检查设备外观；佩戴护目镜与手套；潮湿环境禁止使用电动工具；儿童与无关人员远离作业区域；异常情况立即停机并联系服务站。',
    source: {
      documentName: '工具安全使用指引.pdf',
      content: '总则：任何工具使用前应确认状态完好，发现裂纹、漏电、异响应立即停止使用。',
    },
  },
]
const AI_FALLBACK: { answer: string; source: ChatSourceVO } = {
  answer:
    '这个问题我还在学习中。您可以试试询问：押金与退还、逾期费用、报修流程、取件方式，或具体工具的使用与安全注意事项。',
  source: {
    documentName: '社区工具租赁规则.docx',
    content: '客服渠道：社区服务站工作时间 9:00-18:00，电话 027-8888-6666。',
  },
}

function chatReply(question: string): ChatVO {
  const hit = AI_KB.find((item) => item.pattern.test(question)) ?? AI_FALLBACK
  return { answer: hit.answer, sources: [hit.source] }
}

let nextRepairId = 300
let nextRepairProgressId = 100

function pageRepairs(payload: Record<string, unknown>): PageResult<RepairOrderVO> {
  const toolCode = String(payload.toolCode ?? '').trim().toLowerCase()
  const repairStatus = Number(payload.repairStatus ?? 0)

  let rows = REPAIRS.slice().sort((a, b) => (b.createTime || '').localeCompare(a.createTime || ''))
  if (toolCode) rows = rows.filter((r) => r.toolCode.toLowerCase().includes(toolCode))
  if (repairStatus) rows = rows.filter((r) => r.repairStatus === repairStatus)

  const page = Math.max(1, Number(payload.page) || 1)
  const size = Math.max(1, Number(payload.size) || 10)
  return {
    total: rows.length,
    records: rows.slice((page - 1) * size, page * size),
    page,
    size,
  }
}

/**
 * 命中 mock 时返回 { hit: true, data }，未命中返回 { hit: false }（继续走真实 axios）。
 * 抛出 Error 表示模拟业务失败。
 */
export async function matchMock(
  method: string,
  url: string,
  payload?: unknown,
): Promise<{ hit: boolean; data?: unknown }> {
  const body = (payload ?? {}) as Record<string, unknown>

  if (method === 'POST' && url === '/user/login') {
    const username = String(body.username ?? '')
    const password = String(body.password ?? '')
    const user = USERS.find((u) => u.username === username && u.password === password)
    await sleep()
    if (!user) throw new Error('用户名或密码错误')
    return { hit: true, data: { ...user.vo } }
  }

  if (method === 'GET' && url === '/tool/page') {
    await sleep()
    return { hit: true, data: pageTools(body) }
  }

  const toolDetail = url.match(/^\/tool\/(\d+)$/)
  if (method === 'GET' && toolDetail) {
    await sleep()
    const tool = TOOLS.find((t) => t.id === Number(toolDetail[1]))
    if (!tool) throw new Error('工具不存在')
    return { hit: true, data: tool }
  }

  if (method === 'POST' && url === '/tool') {
    await sleep()
    const name = String(body.name ?? '').trim()
    const category = Number(body.category)
    const damageLevel = Number(body.damageLevel)
    const deposit = Number(body.deposit)
    const storageLocation = String(body.storageLocation ?? '').trim()
    if (!name) throw new Error('请输入工具名称')
    if (!category) throw new Error('请选择类别')
    if (!damageLevel) throw new Error('请选择损坏程度')
    if (!storageLocation) throw new Error('请输入存放区域')

    const catCode: Record<number, string> = { 1: 'WX', 2: 'YY', 3: 'QJ', 4: 'QT' }
    const prefix = `SQ-G-${catCode[category] ?? 'QT'}-`
    const max = Math.max(
      0,
      ...TOOLS.filter((t) => t.toolCode.startsWith(prefix)).map(
        (t) => Number(t.toolCode.slice(prefix.length)) || 0,
      ),
    )
    const toolCode = `${prefix}${String(max + 1).padStart(4, '0')}`
    const id = Math.max(0, ...TOOLS.map((t) => t.id)) + 1
    const now = nowText()

    TOOLS.unshift({
      id,
      toolCode,
      name,
      modelSpec: String(body.modelSpec ?? '').trim(),
      category,
      damageLevel,
      deposit: Number.isFinite(deposit) ? deposit : 0,
      storageLocation,
      usageInstruction: String(body.usageInstruction ?? '').trim(),
      images: Array.isArray(body.images) ? (body.images as string[]) : [],
      purchaseDate: String(body.purchaseDate ?? ''),
      supplierInfo: String(body.supplierInfo ?? ''),
      status: 1,
      auditStatus: 1,
      auditOpinion: '',
      createBy: 2,
      createTime: now,
      updateTime: now,
      rentalCount: 0,
      lastRepairTime: '',
    })
    return { hit: true, data: { id, toolCode } }
  }

  const toolUpdate = url.match(/^\/tool\/(\d+)$/)
  if (method === 'PUT' && toolUpdate) {
    await sleep()
    const tool = TOOLS.find((t) => t.id === Number(toolUpdate[1]))
    if (!tool) throw new Error('工具不存在')
    tool.name = String(body.name ?? tool.name).trim()
    tool.modelSpec = String(body.modelSpec ?? '').trim()
    const category = Number(body.category)
    if (Number.isFinite(category) && category) tool.category = category
    const damageLevel = Number(body.damageLevel)
    if (Number.isFinite(damageLevel) && damageLevel) tool.damageLevel = damageLevel
    const deposit = Number(body.deposit)
    if (Number.isFinite(deposit)) tool.deposit = deposit
    tool.storageLocation = String(body.storageLocation ?? '').trim()
    tool.usageInstruction = String(body.usageInstruction ?? '').trim()
    if (Array.isArray(body.images)) tool.images = body.images as string[]
    tool.purchaseDate = String(body.purchaseDate ?? '')
    tool.supplierInfo = String(body.supplierInfo ?? '')
    tool.updateTime = nowText()
    return { hit: true, data: null }
  }

  const toolAudit = url.match(/^\/tool\/(\d+)\/audit$/)
  if (method === 'PUT' && toolAudit) {
    await sleep()
    const tool = TOOLS.find((t) => t.id === Number(toolAudit[1]))
    if (!tool) throw new Error('工具不存在')
    const auditStatus = Number(body.auditStatus)
    if (![2, 3].includes(auditStatus)) throw new Error('审核状态不合法')
    tool.auditStatus = auditStatus
    tool.auditOpinion = String(body.auditOpinion ?? '').trim()
    tool.updateTime = nowText()
    return { hit: true, data: null }
  }

  const toolStatus = url.match(/^\/tool\/(\d+)\/status$/)
  if (method === 'PUT' && toolStatus) {
    await sleep()
    const tool = TOOLS.find((t) => t.id === Number(toolStatus[1]))
    if (!tool) throw new Error('工具不存在')
    const newStatus = Number(body.status)
    if (!newStatus) throw new Error('请选择新状态')
    const oldStatus = tool.status
    tool.status = newStatus
    tool.updateTime = nowText()
    if (oldStatus !== newStatus) {
      const logs = (TOOL_LOGS[tool.id] ??= [])
      logs.unshift({
        id: nextToolLogId++,
        toolId: tool.id,
        oldStatus,
        newStatus,
        operatorId: 2,
        remark: String(body.remark ?? ''),
        createTime: nowText(),
      })
    }
    return { hit: true, data: null }
  }

  const toolLogs = url.match(/^\/tool\/(\d+)\/status-logs$/)
  if (method === 'GET' && toolLogs) {
    await sleep()
    const toolId = Number(toolLogs[1])
    if (!TOOLS.some((t) => t.id === toolId)) throw new Error('工具不存在')
    return { hit: true, data: (TOOL_LOGS[toolId] ?? []).slice() }
  }

  if (method === 'GET' && url === '/rental/available-times') {
    await sleep()
    const toolId = Number(body.toolId)
    const date = String(body.date || todayCompactWithDash())
    return { hit: true, data: availableTimes(toolId, date) }
  }

  if (method === 'POST' && url === '/rental') {
    await sleep()
    const toolId = Number(body.toolId)
    const startTime = String(body.startTime ?? '')
    const endTime = String(body.endTime ?? '')
    const pickupName = String(body.pickupName ?? '')

    const tool = TOOLS.find((t) => t.id === toolId)
    if (!tool) throw new Error('工具不存在')
    if (!startTime || !endTime) throw new Error('预约时段不能为空')

    const conflict = rentals.find(
      (r) =>
        r.toolId === toolId &&
        (r.status === 2 || r.status === 3) &&
        r.startTime < endTime &&
        startTime < r.endTime,
    )
    if (conflict) {
      const recommend = availableTimes(toolId, startTime.slice(0, 10))[0]
      if (recommend) {
        const text = (v: string) => v.slice(11, 16)
        throw new Error(
          `该时段已被预约，推荐 ${text(recommend.startTime)}-${text(recommend.endTime)}`,
        )
      }
      throw new Error('该时段已被预约，请选择其他日期')
    }

    const id = nextRentalId++
    const orderNo = `SQ-ZY-${todayCompact()}-${String(id).padStart(4, '0')}`
    const now = nowText()
    rentals.push({
      id,
      orderNo,
      toolId,
      startTime,
      endTime,
      pickupName,
      status: 1,
      userId: 3,
      depositAmount: tool.deposit,
      depositStatus: 1,
      acceptResult: 0,
      overdueFee: 0,
      depositRefundAmount: 0,
      cancelTime: '',
      returnTime: '',
      createTime: now,
      updateTime: now,
    })
    const data: RentalCreateVO = { id, orderNo }
    return { hit: true, data }
  }

  if (method === 'GET' && url === '/rental/page') {
    await sleep()
    return { hit: true, data: pageRentals(body) }
  }

  const rentalDetail = url.match(/^\/rental\/(\d+)$/)
  if (method === 'GET' && rentalDetail) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalDetail[1]))
    if (!rental) throw new Error('预约单不存在')
    return { hit: true, data: rentalVO(rental) }
  }

  const rentalPay = url.match(/^\/rental\/(\d+)\/pay$/)
  if (method === 'POST' && rentalPay) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalPay[1]))
    if (!rental) throw new Error('预约单不存在')
    rental.status = 2
    rental.depositStatus = 2
    rental.updateTime = nowText()
    return { hit: true, data: null }
  }

  if (method === 'GET' && url === '/rental/export') {
    await sleep()
    const header = '预约单号,工具,租赁人,开始时间,结束时间,押金,状态,验收结果,逾期费,实退押金\n'
    const bodyCsv = rentals
      .map(rentalVO)
      .map((r) =>
        [
          r.orderNo, r.toolName, r.userName, r.startTime, r.endTime,
          r.depositAmount, r.status, r.acceptResult, r.overdueFee, r.depositRefundAmount,
        ].join(','),
      )
      .join('\n')
    return {
      hit: true,
      data: new Blob([`﻿${header}${bodyCsv}`], { type: 'application/vnd.ms-excel' }),
    }
  }

  const rentalPickup = url.match(/^\/rental\/(\d+)\/pickup$/)
  if (method === 'POST' && rentalPickup) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalPickup[1]))
    if (!rental) throw new Error('预约单不存在')
    if (rental.status !== 2) throw new Error('当前状态不可取件')
    rental.status = 3
    const name = String(body.pickupName ?? '').trim()
    if (name) rental.pickupName = name
    rental.updateTime = nowText()
    return { hit: true, data: null }
  }

  const rentalReturn = url.match(/^\/rental\/(\d+)\/return$/)
  if (method === 'POST' && rentalReturn) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalReturn[1]))
    if (!rental) throw new Error('预约单不存在')
    if (rental.status !== 3) throw new Error('当前状态不可归还')
    const acceptResult = Number(body.acceptResult)
    const overdueFee = Number(body.overdueFee ?? 0)
    const refund = Number(body.depositRefundAmount ?? 0)
    if (![1, 2, 3].includes(acceptResult)) throw new Error('请选择验收结果')
    if (overdueFee < 0 || refund < 0) throw new Error('金额不能为负数')
    if (overdueFee > rental.depositAmount) throw new Error('逾期费用不能大于押金')
    if (refund + overdueFee > rental.depositAmount) {
      throw new Error('实退押金 + 逾期费不得超过押金金额')
    }
    rental.status = 4
    rental.acceptResult = acceptResult
    rental.overdueFee = overdueFee
    rental.depositRefundAmount = refund
    rental.depositStatus = 3
    rental.returnTime = nowText()
    rental.updateTime = rental.returnTime
    return { hit: true, data: null }
  }

  const rentalCancel = url.match(/^\/rental\/(\d+)\/cancel$/)
  if (method === 'POST' && rentalCancel) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalCancel[1]))
    if (!rental) throw new Error('预约单不存在')
    if (rental.status !== 1 && rental.status !== 2) throw new Error('当前状态不可取消')
    const hours = (new Date(rental.startTime).getTime() - Date.now()) / 3_600_000
    if (hours < 24) throw new Error('取件前 24 小时内不可取消预约')
    rental.status = 5
    rental.depositStatus = 3
    rental.depositRefundAmount = rental.depositAmount
    rental.cancelTime = nowText()
    rental.updateTime = rental.cancelTime
    return { hit: true, data: null }
  }

  if (method === 'GET' && url === '/repair/page') {
    await sleep()
    return { hit: true, data: pageRepairs(body) }
  }

  const repairDetail = url.match(/^\/repair\/(\d+)$/)
  if (method === 'GET' && repairDetail) {
    await sleep()
    const repair = REPAIRS.find((r) => r.id === Number(repairDetail[1]))
    if (!repair) throw new Error('报修单不存在')
    return { hit: true, data: repair }
  }

  const repairVerify = url.match(/^\/repair\/(\d+)\/verify$/)
  if (method === 'POST' && repairVerify) {
    await sleep()
    const repair = REPAIRS.find((r) => r.id === Number(repairVerify[1]))
    if (!repair) throw new Error('报修单不存在')
    const verifyResult = Number(body.verifyResult)
    if (![1, 2, 3].includes(verifyResult)) throw new Error('请选择核验结果')
    const remark = String(body.remark ?? '').trim()
    repair.verifyResult = verifyResult
    repair.verifyBy = 2
    repair.verifyByName = '李四'
    repair.verifyTime = nowText()
    if (verifyResult === 1) {
      repair.repairStatus = 1
      const tool = TOOLS.find((t) => t.id === repair.toolId)
      if (tool) {
        const oldStatus = tool.status
        tool.status = 3
        tool.updateTime = nowText()
        const logs = (TOOL_LOGS[tool.id] ??= [])
        logs.unshift({
          id: nextToolLogId++, toolId: tool.id, oldStatus, newStatus: 3,
          operatorId: 2, remark: `报修单 ${repair.repairNo} 确认损坏`,
          createTime: nowText(),
        })
      }
    }
    const labels: Record<number, string> = { 1: '确认损坏', 2: '疑似损坏', 3: '误报' }
    repair.progressList.push({
      id: nextRepairProgressId++, repairId: repair.id,
      status: repair.repairStatus || 1,
      description: `现场核验：${labels[verifyResult]}${remark ? ` — ${remark}` : ''}`,
      operatorId: 2, createTime: nowText(),
    })
    return { hit: true, data: null }
  }

  const repairProgress = url.match(/^\/repair\/(\d+)\/progress$/)
  if (method === 'POST' && repairProgress) {
    await sleep()
    const repair = REPAIRS.find((r) => r.id === Number(repairProgress[1]))
    if (!repair) throw new Error('报修单不存在')
    const status = Number(body.status)
    if (![1, 2, 3, 4].includes(status)) throw new Error('请选择维修进度')
    const description = String(body.description ?? '').trim()
    repair.repairStatus = status
    const labels: Record<number, string> = {
      1: '待配件', 2: '维修中', 3: '维修完成', 4: '维修失败',
    }
    repair.progressList.push({
      id: nextRepairProgressId++, repairId: repair.id, status,
      description: `进度更新：${labels[status]}${description ? ` — ${description}` : ''}`,
      operatorId: 2, createTime: nowText(),
    })
    return { hit: true, data: null }
  }

  const repairAccept = url.match(/^\/repair\/(\d+)\/accept$/)
  if (method === 'POST' && repairAccept) {
    await sleep()
    const repair = REPAIRS.find((r) => r.id === Number(repairAccept[1]))
    if (!repair) throw new Error('报修单不存在')
    const acceptResult = Number(body.acceptResult)
    const toolStatus = Number(body.toolStatus)
    if (![1, 2, 3].includes(acceptResult)) throw new Error('请选择验收结果')
    if (![1, 3, 4].includes(toolStatus)) throw new Error('请选择工具目标状态')
    const remark = String(body.remark ?? '').trim()
    repair.acceptResult = acceptResult
    repair.acceptBy = 2
    repair.acceptByName = '李四'
    repair.acceptTime = nowText()
    repair.repairStatus = acceptResult === 2 ? 2 : 3
    repair.remark = remark
    const tool = TOOLS.find((t) => t.id === repair.toolId)
    if (tool) {
      const oldStatus = tool.status
      tool.status = toolStatus
      tool.updateTime = nowText()
      const logs = (TOOL_LOGS[tool.id] ??= [])
      logs.unshift({
        id: nextToolLogId++, toolId: tool.id, oldStatus, newStatus: toolStatus,
        operatorId: 2, remark: `报修单 ${repair.repairNo} 维修验收`,
        createTime: nowText(),
      })
    }
    const labels: Record<number, string> = { 1: '合格', 2: '不合格', 3: '部分合格' }
    repair.progressList.push({
      id: nextRepairProgressId++, repairId: repair.id, status: repair.repairStatus,
      description: `维修验收：${labels[acceptResult]}${remark ? ` — ${remark}` : ''}`,
      operatorId: 2, createTime: nowText(),
    })
    return { hit: true, data: null }
  }

  if (method === 'POST' && url === '/repair') {
    await sleep()
    const rentalOrderId = Number(body.rentalOrderId)
    const toolId = Number(body.toolId)
    const damagePart = String(body.damagePart ?? '').trim()
    const damageDesc = String(body.damageDesc ?? '').trim()
    const images = Array.isArray(body.images) ? (body.images as string[]) : []

    const rental = rentals.find((r) => r.id === rentalOrderId)
    if (!rental) throw new Error('关联租赁单不存在')
    const tool = TOOLS.find((t) => t.id === toolId)
    if (!tool) throw new Error('工具不存在')
    if (!damagePart) throw new Error('请填写损坏部位')
    if (!damageDesc) throw new Error('请填写损坏描述')

    const id = nextRepairId++
    const repairNo = `SQ-BX-${todayCompact()}-${String(id - 299).padStart(4, '0')}`
    REPAIRS.unshift({
      id,
      repairNo,
      rentalOrderId,
      orderNo: rental.orderNo,
      toolId,
      toolCode: tool.toolCode,
      toolName: tool.name,
      reporterId: 3,
      reporterName: '张三',
      reporterPhone: '138****0003',
      damagePart,
      damageDesc,
      images,
      createTime: nowText(),
      verifyBy: 0,
      verifyByName: '',
      verifyTime: '',
      verifyResult: 0,
      repairerName: '',
      expectDuration: '',
      repairStartTime: '',
      repairStatus: 1,
      repairFee: 0,
      acceptBy: 0,
      acceptByName: '',
      acceptTime: '',
      acceptResult: 0,
      remark: '',
      progressList: [],
    })
    const data: RepairCreateVO = { id, repairNo }
    return { hit: true, data }
  }

  if (method === 'POST' && url === '/file/upload') {
    await sleep(150)
    const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="160"><rect width="160" height="160" fill="#e5ebe6"/><path d="M50 100l20-24 16 18 12-14 22 26z" fill="#a8bcb5"/><circle cx="60" cy="58" r="10" fill="#a8bcb5"/></svg>'
    return { hit: true, data: { url: `data:image/svg+xml,${encodeURIComponent(svg)}` } }
  }

  if (method === 'GET' && url === '/notify/page') {
    await sleep()
    return { hit: true, data: pageMessages(body) }
  }

  if (method === 'PUT' && url === '/notify/read-all') {
    await sleep()
    MESSAGES.forEach((m) => {
      m.readStatus = 1
    })
    return { hit: true, data: null }
  }

  const readOne = url.match(/^\/notify\/(\d+)\/read$/)
  if (method === 'PUT' && readOne) {
    await sleep()
    const message = MESSAGES.find((m) => m.id === Number(readOne[1]))
    if (!message) throw new Error('消息不存在')
    message.readStatus = 1
    return { hit: true, data: null }
  }

  if (method === 'GET' && url === '/user/page') {
    await sleep()
    return { hit: true, data: pageUsersMock(body) }
  }

  const userRole = url.match(/^\/user\/(\d+)\/role$/)
  if (method === 'PUT' && userRole) {
    await sleep()
    const user = USER_LIST.find((u) => u.id === Number(userRole[1]))
    if (!user) throw new Error('用户不存在')
    const role = Number(body.role)
    if (![0, 1, 2].includes(role)) throw new Error('角色不合法')
    user.role = role
    return { hit: true, data: null }
  }

  const userStatus = url.match(/^\/user\/(\d+)\/status$/)
  if (method === 'PUT' && userStatus) {
    await sleep()
    const user = USER_LIST.find((u) => u.id === Number(userStatus[1]))
    if (!user) throw new Error('用户不存在')
    const status = Number(body.status)
    if (![0, 1].includes(status)) throw new Error('状态不合法')
    user.status = status
    return { hit: true, data: null }
  }

  if (method === 'GET' && url === '/ai/documents') {
    await sleep()
    const page = Math.max(1, Number(body.page) || 1)
    const size = Math.max(1, Number(body.size) || 10)
    const rows = KB_DOCS.slice().sort((a, b) =>
      (b.createTime || '').localeCompare(a.createTime || ''),
    )
    return {
      hit: true,
      data: {
        total: rows.length,
        records: rows.slice((page - 1) * size, page * size),
        page,
        size,
      },
    }
  }

  if (method === 'POST' && url === '/ai/documents') {
    await sleep(400)
    const form = payload instanceof FormData ? payload : null
    const entry = form?.get('file')
    const file = entry instanceof File ? entry : null
    const id = Math.max(0, ...KB_DOCS.map((d) => d.id)) + 1
    const doc: KbDocument = {
      id,
      fileName: file?.name ?? '未命名文档',
      fileUrl: '',
      fileSize: file?.size ?? 0,
      chunkCount: 0,
      status: 1,
      uploadBy: 1,
      createTime: nowText(),
    }
    KB_DOCS.unshift(doc)
    setTimeout(() => {
      doc.status = 2
      doc.chunkCount = Math.max(1, Math.round((file?.size ?? 1024) / 2048))
    }, 2500)
    return { hit: true, data: doc }
  }

  const kbDelete = url.match(/^\/ai\/documents\/(\d+)$/)
  if (method === 'DELETE' && kbDelete) {
    await sleep()
    const index = KB_DOCS.findIndex((d) => d.id === Number(kbDelete[1]))
    if (index < 0) throw new Error('文档不存在')
    KB_DOCS.splice(index, 1)
    return { hit: true, data: null }
  }

  if (method === 'POST' && url === '/ai/chat') {
    await sleep(600)
    const question = String(body.question ?? '').trim()
    if (!question) throw new Error('问题不能为空')
    return { hit: true, data: chatReply(question) }
  }

  return { hit: false }
}
