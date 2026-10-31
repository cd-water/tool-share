import axios, { type AxiosRequestConfig } from 'axios'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

const SUCCESS_CODE = 'A200'
const UNAUTHORIZED_CODE = 'C401'

interface Result<T> {
  code: string
  message: string
  data: T
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
  (resp): any => {
    const body = resp.data as Result<unknown>
    if (body.code === SUCCESS_CODE) {
      return body.data
    }
    if (body.code === UNAUTHORIZED_CODE) {
      useAuthStore.getState().clear()
      toast.add({ title: '登录已过期，请重新登录', type: 'error' })
    } else {
      toast.add({ title: body.message || '请求失败', type: 'error' })
    }
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
    toast.add({ title: '网络异常，请稍后重试', type: 'error' })
    return Promise.reject(error)
  },
)

const http = {
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.post(url, data) as unknown as Promise<T>
  },
  put<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },
}

export default http
