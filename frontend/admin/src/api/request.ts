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
    if (body.code === UNAUTHORIZED_CODE) {
      useAuthStore().reset()
      if (router.currentRoute.value.path !== '/login') {
        router.push({
          path: '/login',
          query: { redirect: router.currentRoute.value.fullPath },
        })
      }
      return Promise.reject(new Error(body.message))
    }
    ElMessage.error(body.message || '请求失败')
    return Promise.reject(new Error(body.message || '请求失败'))
  },
  (error) => {
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
