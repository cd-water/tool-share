import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

const SUCCESS_CODE = 'A200'
const UNAUTHORIZED_CODE = 'C401'

interface Result<T> {
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

function handleBusinessError(body: Result<unknown>): Error {
  if (body.code === UNAUTHORIZED_CODE) {
    useAuthStore().reset()
    if (router.currentRoute.value.path !== '/login') {
      router.push({
        path: '/login',
        query: { redirect: router.currentRoute.value.fullPath },
      })
    }
  } else {
    ElMessage.error(body.message || '请求失败')
  }
  return new Error(body.message || '请求失败')
}

const instance = axios.create({
  baseURL: '/api',
  timeout: 30_000,
})

instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.isLoggedIn) {
    config.headers.Authorization = `Bearer ${auth.tokenValue}`
  }
  return config
})

instance.interceptors.response.use(
  (resp): any => {
    if (resp.config.responseType === 'blob') {
      return resp.data
    }
    const body = resp.data as Result<unknown>
    if (body.code === SUCCESS_CODE) {
      return body.data
    }
    // 兼容旧行为：HTTP 200 + 业务错误 code
    return Promise.reject(handleBusinessError(body))
  },
  // 语义化状态码：从 error.response.data 解出 envelope，没有 envelope 才是网络异常
  async (error) => {
    const response = error?.response
    let data: unknown = response?.data
    if (data instanceof Blob) {
      // blob 下载失败时后端返回 JSON envelope，不能当 Excel 保存
      const contentType =
        data.type || response?.headers?.['content-type'] || ''
      if (contentType.includes('json')) {
        try {
          data = JSON.parse(await data.text())
        } catch {
          data = null
        }
      } else {
        data = null
      }
    }
    const body = parseEnvelope(data)
    if (body) {
      return Promise.reject(handleBusinessError(body))
    }
    ElMessage.error('网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

const http = {
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T = void>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  put<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },
  patch<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.patch(url, data) as unknown as Promise<T>
  },
  delete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  },
  download(url: string, params?: object): Promise<Blob> {
    return instance.get(url, {
      params,
      responseType: 'blob',
    }) as unknown as Promise<Blob>
  },
}

export default http
