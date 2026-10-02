import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

/** 后端统一信封（docs/API.md §0） */
interface ApiEnvelope<T = unknown> {
  code: string
  message: string
  data: T
}

const instance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

instance.interceptors.request.use((config) => {
  const { token } = useUserStore()
  if (token) config.headers.Authorization = token
  return config
})

instance.interceptors.response.use(
  // 供 http() 泛型断言：正常响应已解包为 data，blob 为原始数据
  (response): never => {
    // blob（Excel 导出）不走信封
    if (response.config.responseType === 'blob') {
      return response.data as never
    }
    const envelope = response.data as ApiEnvelope
    if (envelope.code === 'A200') {
      return envelope.data as never
    }
    if (envelope.code === 'C401') {
      useUserStore().clear()
      ElMessage.error('登录已过期，请重新登录')
      window.location.href = '/login'
      return Promise.reject(new Error(envelope.message)) as never
    }
    ElMessage.error(envelope.message)
    return Promise.reject(new Error(envelope.message)) as never
  },
  (error) => {
    ElMessage.error(error?.message ?? '网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

export function http<T>(config: AxiosRequestConfig): Promise<T> {
  // 拦截器已解包信封：正常响应即 data，故断言为 T
  return instance.request(config) as Promise<T>
}

export function httpGet<T>(url: string, params?: object): Promise<T> {
  return http<T>({ method: 'get', url, params })
}

export function httpPost<T>(url: string, data?: unknown): Promise<T> {
  return http<T>({ method: 'post', url, data })
}

export function httpPut<T>(url: string, data?: unknown): Promise<T> {
  return http<T>({ method: 'put', url, data })
}

export function httpDelete<T>(url: string): Promise<T> {
  return http<T>({ method: 'delete', url })
}
