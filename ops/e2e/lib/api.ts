import { ADMIN_USERNAME, API_URL, PASSWORD, USER_PHONE } from './env'
import { toApiValue } from './time'

interface Result<T> {
  code: string
  message: string
  data: T
}

interface Page<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface LoginResult {
  tokenName: string
  tokenValue: string
}

export interface Tool {
  id: number
  name: string
  code: string
  status: string
  deposit: number
}

export interface Rental {
  id: number
  orderNo: string
  toolId: number
  toolName: string
  pickupPerson: string
  status: string
  depositStatus: string
  depositAmount: number
  damageResult?: string | null
  overdueFee?: number | null
  damageFee?: number | null
  refundAmount?: number | null
}

export interface DamageReport {
  id: number
  reportNo: string
  rentalId: number
  status: string
  progressStatus?: string | null
  verifyResult?: string | null
  verifyTime?: string | null
  acceptResult?: string | null
  acceptTime?: string | null
  toolStatusAfter?: string | null
}

async function request<T>(
  path: string,
  options: { method?: string; token?: string; body?: unknown } = {},
): Promise<T> {
  const { method = 'GET', token, body } = options
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(`${API_URL}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload: Result<T>
  try {
    payload = JSON.parse(text) as Result<T>
  } catch {
    throw new Error(`${method} ${path} 返回非 JSON（HTTP ${response.status}）：${text.slice(0, 200)}`)
  }
  if (!response.ok || payload.code !== 'A200') {
    throw new Error(
      `${method} ${path} 失败（HTTP ${response.status} / ${payload.code}）：${payload.message}`,
    )
  }
  return payload.data
}

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const api = {
  loginUser: () =>
    request<LoginResult>('/user/login/by-pwd', {
      method: 'POST',
      body: { phone: USER_PHONE, password: PASSWORD },
    }),

  loginAdmin: () =>
    request<LoginResult>('/admin/login', {
      method: 'POST',
      body: { username: ADMIN_USERNAME, password: PASSWORD },
    }),

  listTools: (token: string, params: { status?: string; page?: number; size?: number } = {}) =>
    request<Page<Tool>>(`/user/tools${query({ page: 1, size: 100, ...params })}`, { token }),

  toolDetail: (token: string, id: number) => request<Tool & Record<string, unknown>>(`/user/tools/${id}`, { token }),

  availability: (token: string, toolId: number, start: Date, end: Date) =>
    request<{ available: boolean; message: string }>(
      `/user/tools/${toolId}/availability${query({
        startTime: toApiValue(start),
        endTime: toApiValue(end),
      })}`,
      { token },
    ),

  listUserRentals: (token: string, params: { page?: number; size?: number } = {}) =>
    request<Page<Rental>>(`/user/rentals${query({ page: 1, size: 100, ...params })}`, { token }),

  userRentalDetail: (token: string, id: number) => request<Rental>(`/user/rentals/${id}`, { token }),

  createRental: (
    token: string,
    payload: { toolId: number; startTime: Date; endTime: Date; pickupPerson: string; remindBeforeMinutes?: number },
  ) =>
    request<Rental>('/user/rentals', {
      method: 'POST',
      token,
      body: {
        toolId: payload.toolId,
        startTime: toApiValue(payload.startTime),
        endTime: toApiValue(payload.endTime),
        pickupPerson: payload.pickupPerson,
        remindBeforeMinutes: payload.remindBeforeMinutes ?? 0,
      },
    }),

  payRental: (token: string, id: number) =>
    request<void>(`/user/rentals/${id}/pay`, { method: 'POST', token }),

  adminRentalDetail: (token: string, id: number) => request<Rental>(`/admin/rentals/${id}`, { token }),

  adminPickup: (token: string, id: number) =>
    request<void>(`/admin/rentals/${id}/pickup`, { method: 'POST', token }),

  adminReturn: (
    token: string,
    id: number,
    payload: { actualReturnTime: Date; damageResult: string; remark?: string },
  ) =>
    request<void>(`/admin/rentals/${id}/return`, {
      method: 'POST',
      token,
      body: {
        actualReturnTime: toApiValue(payload.actualReturnTime),
        damageResult: payload.damageResult,
        remark: payload.remark,
      },
    }),

  listUserDamage: (token: string, params: { page?: number; size?: number; keyword?: string } = {}) =>
    request<Page<DamageReport>>(
      `/user/damage-reports${query({ page: 1, size: 100, ...params })}`,
      { token },
    ),

  adminDamageDetail: (token: string, id: number) =>
    request<DamageReport & Record<string, unknown>>(`/admin/damage-reports/${id}`, { token }),
}

/** 轮询直到拿到满足条件的数据，失败时带上步骤名与最后一次结果。 */
export async function poll<T>(
  label: string,
  fn: () => Promise<T | undefined>,
  timeoutMs = 20_000,
  intervalMs = 500,
): Promise<T> {
  const deadline = Date.now() + timeoutMs
  let last: T | undefined
  let lastError: unknown
  while (Date.now() < deadline) {
    try {
      last = await fn()
      if (last !== undefined) return last
    } catch (error) {
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs))
  }
  const detail = lastError instanceof Error ? `；最后错误：${lastError.message}` : ''
  throw new Error(`等待「${label}」超时（${timeoutMs}ms）${detail}`)
}

/** 挑选在目标时段真正可预约的工具，避免重复运行时撞上历史租期。 */
export async function findAvailableTool(
  token: string,
  start: Date,
  end: Date,
): Promise<Tool> {
  const page = await api.listTools(token, { status: 'AVAILABLE' })
  for (const tool of page.records) {
    const result = await api.availability(token, tool.id, start, end)
    if (result.available) return tool
  }
  throw new Error('工具广场没有在目标时段可预约的工具')
}
