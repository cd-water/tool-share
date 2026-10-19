/**
 * 前端 Mock 数据（内存存储，不发真实请求）
 *
 * 切换方式：把下方的 USE_MOCK 改为 false 即恢复真实后端。
 * 数据结构与 api/types.ts 保持一致，由 api/request.ts 在请求层拦截。
 */
import type {
  LoginVO,
  PageResult,
  RentalCreateVO,
  TimeSlotVO,
  Tool,
} from '../api/types'

export const USE_MOCK = true

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
}

const rentals: MockRental[] = []
let nextRentalId = 1

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

    if (!TOOLS.some((t) => t.id === toolId)) throw new Error('工具不存在')
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
    rentals.push({ id, orderNo, toolId, startTime, endTime, pickupName, status: 1 })
    const data: RentalCreateVO = { id, orderNo }
    return { hit: true, data }
  }

  const rentalPay = url.match(/^\/rental\/(\d+)\/pay$/)
  if (method === 'POST' && rentalPay) {
    await sleep()
    const rental = rentals.find((r) => r.id === Number(rentalPay[1]))
    if (!rental) throw new Error('预约单不存在')
    rental.status = 2
    return { hit: true, data: null }
  }

  return { hit: false }
}
