import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
// 官方按需样式入口（含 base 等依赖），替代单文件 theme-chalk 引用
import 'element-plus/es/components/message/style/css'
import router from '@/router'
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
  const auth = useAuthStore()
  if (auth.isLoggedIn) {
    config.headers.Authorization = `Bearer ${auth.tokenValue}`
  }
  return config
})

instance.interceptors.response.use(
  // 运行时返回解包后的 data（非 AxiosResponse），axios 类型无法表达，显式 any
  (resp): any => {
    const body = resp.data as Result<unknown>
    if (body.code === 'A200') {
      return body.data
    }
    if (body.code === 'C401') {
      // 凭证失效：清本地并回登录页（已在登录页则不再跳，避免循环）
      const auth = useAuthStore()
      auth.tokenValue = ''
      auth.username = ''
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

/** 注意：拦截器返回解包后的 data 而非 AxiosResponse，此处类型为运行时行为的显式声明 */
const http = {
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.post(url, data) as unknown as Promise<T>
  },
}

export default http
