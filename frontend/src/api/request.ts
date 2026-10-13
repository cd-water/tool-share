import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截：自动携带 token（sa-token 读 Authorization 头）
request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = userStore.token
  }
  return config
})

// 响应拦截：统一解包 Result，成功直接返回 data，失败弹提示并 reject
request.interceptors.response.use(
  (response) => {
    // 文件流（Excel 导出等）不解包
    if (response.config.responseType === 'blob') {
      return response
    }
    const res = response.data
    if (res.code === 'A200') {
      return res.data
    }
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  },
)

/** GET，query 参数序列化为 URL 参数，返回已解包的 data */
export function get<T>(url: string, params?: object): Promise<T> {
  return request.get(url, { params }) as Promise<T>
}

/** POST，body 为 JSON，返回已解包的 data */
export function post<T>(url: string, data?: object): Promise<T> {
  return request.post(url, data) as Promise<T>
}

/** PUT，body 为 JSON，返回已解包的 data */
export function put<T>(url: string, data?: object): Promise<T> {
  return request.put(url, data) as Promise<T>
}

/** DELETE，返回已解包的 data */
export function del<T>(url: string): Promise<T> {
  return request.delete(url) as Promise<T>
}

/** 文件上传（multipart/form-data） */
export function upload<T>(url: string, form: FormData): Promise<T> {
  return request.post(url, form) as Promise<T>
}

/** 文件下载（Excel 导出等），返回 Blob */
export function download(url: string, params?: object): Promise<Blob> {
  return request.get(url, { params, responseType: 'blob' }).then((r) => r.data)
}
