import axios, { type AxiosRequestConfig } from 'axios'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

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
  // 拦截器解包后返回 data，axios 类型无法表达，显式 any
  (resp): any => {
    const body = resp.data as Result<unknown>
    if (body.code === 'A200') {
      return body.data
    }
    if (body.code === 'C401') {
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
}

export interface LoginVO {
  tokenName: string
  tokenValue: string
}

export interface UserVO {
  id: number
  phone: string
  nickname: string
  avatar: string
}

export const userApi = {
  sendCode: (phone: string) => http.post<void>('/user/send-code', { phone }),
  loginByCode: (phone: string, code: string) =>
    http.post<LoginVO>('/user/login/by-code', { phone, code }),
  loginByPwd: (phone: string, password: string) =>
    http.post<LoginVO>('/user/login/by-pwd', { phone, password }),
  logout: () => http.post<void>('/user/logout'),
  info: () => http.get<UserVO>('/user/info'),
}

export default http
