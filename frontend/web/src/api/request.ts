import axios, { type AxiosRequestConfig } from 'axios'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

const UNAUTHORIZED_CODE = 'C401'

export interface Result<T> {
  code: string
  message: string
  data: T
}

/** 非 2xx 响应里的 envelope；不是统一响应结构时返回 null */
function parseEnvelope(data: unknown): Result<unknown> | null {
  if (
    data !== null &&
    typeof data === 'object' &&
    typeof (data as Result<unknown>).code === 'string'
  ) {
    return data as Result<unknown>
  }
  return null
}

/** 登录失效统一处理：清空会话并提示。返回是否已按未登录处理。 */
export function handleUnauthorized(code?: string): boolean {
  if (code !== UNAUTHORIZED_CODE) return false
  useAuthStore.getState().clear()
  toast.add({ title: '登录已过期，请重新登录', type: 'error' })
  return true
}

function handleBusinessError(body: Result<unknown>): Error {
  if (!handleUnauthorized(body.code)) {
    toast.add({ title: body.message || '请求失败', type: 'error' })
  }
  return new Error(body.message || '请求失败')
}

const instance = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

instance.interceptors.request.use((config) => {
  const { tokenValue } = useAuthStore.getState()
  if (tokenValue) {
    config.headers.Authorization = `Bearer ${tokenValue}`
  }
  return config
})

instance.interceptors.response.use(
  // 拦截器在此解包统一响应 envelope，返回值无法用 AxiosResponse 表达。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (resp): any => (resp.data as Result<unknown>).data,
  // 语义化状态码：从 error.response.data 解出 envelope，没有 envelope 才是网络异常
  (error) => {
    const body = parseEnvelope(error?.response?.data)
    if (body) {
      return Promise.reject(handleBusinessError(body))
    }
    toast.add({ title: '网络异常，请稍后重试', type: 'error' })
    return Promise.reject(error)
  },
)

const http = {
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T = void>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  put<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },
  delete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  },
}

export default http
