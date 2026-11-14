/**
 * 全局 HTTP 请求层。
 *
 * 职责：
 * 1. 创建唯一的 axios 实例，统一 baseURL（`/api`，由开发服务器或网关代理）
 *    与超时；
 * 2. 请求拦截器从 zustand 读取 token 并注入 `Authorization: Bearer ...`；
 * 3. 响应拦截器解包后端统一响应 envelope `{ code, message, data }`，
 *    让业务层直接拿到 `data`；
 * 4. 统一错误语义：
 *    - 业务错误（HTTP 非 2xx 且响应体是 envelope）→ toast + reject Error；
 *    - 登录失效（code = C401）→ 清空会话 + toast + reject；
 *    - 没有 envelope（真正的网络异常、超时、HTML 错误页）→ 网络异常 toast；
 * 5. 导出极简 `get/post/put/delete`，隐藏 axios 的泛型差异。
 *
 * 业务 API 模块只需声明 `http.get<T>(url, config)`，不要直接 import axios。
 */
import axios, { type AxiosRequestConfig } from 'axios'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

/**
 * 后端约定的未登录/登录过期业务码。
 *
 * 注意这是响应体里的 code 字段，不是 HTTP 状态码；后端可能用 200/401
 * 包裹同样的 envelope，因此判断以 body.code 为准。
 */
const UNAUTHORIZED_CODE = 'C401'

/**
 * 统一响应 envelope。
 *
 * @typeParam T data 字段的实际类型
 *
 * 仅 2xx 时拦截器会解包 data；非 2xx 时该结构用于错误提示与
 * 登录失效判断。
 */
export interface Result<T> {
  code: string
  message: string
  data: T
}

/** 非 2xx 响应里的 envelope；不是统一响应结构时返回 null */
function parseEnvelope(data: unknown): Result<unknown> | null {
  // 必须同时满足「对象」且 code 为字符串，避免把任意 JSON（如 HTML 错误页、
  // 第三方网关返回）误判为业务 envelope。
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
/**
 * 补充说明：
 * @param code 响应体里的业务码
 * @returns true 表示本次已按未登录处理（调用方不应再弹通用错误）
 *
 * 副作用：
 * - 清空 zustand 持久化 store（token、昵称、头像）；
 * - 弹出「登录已过期」toast；
 * - 不在这里做路由跳转，由 RequireAuth / 页面响应 store 变化后处理，
 *   避免请求层依赖 router 造成循环引用。
 *
 * 该函数同时被 SSE 流式接口复用，保证两条请求链路语义一致。
 */
export function handleUnauthorized(code?: string): boolean {
  if (code !== UNAUTHORIZED_CODE) return false
  useAuthStore.getState().clear()
  toast.add({ title: '登录已过期，请重新登录', type: 'error' })
  return true
}

/**
 * 把业务 envelope 转成 rejected Error。
 *
 * @param body 非 2xx 响应中的统一结构
 * @returns 带后端 message 的 Error，供 React Query 的 error 状态使用
 *
 * 错误提示策略：C401 只提示一次登录过期，其他业务错误 toast 后端文案；
 * 两者都会 reject，保证调用方的 catch/onError 仍能感知失败。
 */
function handleBusinessError(body: Result<unknown>): Error {
  if (!handleUnauthorized(body.code)) {
    toast.add({ title: body.message || '请求失败', type: 'error' })
  }
  return new Error(body.message || '请求失败')
}

/**
 * axios 单例。
 *
 * - baseURL 固定为 `/api`：开发环境由 Vite proxy 转发，生产环境由网关处理；
 * - 默认 10 秒超时，AI 推荐等长耗时接口在各自调用处单独覆盖 timeout。
 */
const instance = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

/**
 * 请求拦截器：注入登录凭证。
 *
 * 从 store 的 getState 同步读取（而非 hook），因此可在任意请求中使用；
 * token 为空时不加 Authorization 头，让后端按匿名请求处理并返回 C401。
 */
instance.interceptors.request.use((config) => {
  const { tokenValue } = useAuthStore.getState()
  if (tokenValue) {
    config.headers.Authorization = `Bearer ${tokenValue}`
  }
  return config
})

/**
 * 响应拦截器。
 *
 * 成功分支：直接返回 envelope.data，业务层拿到的就是业务对象。
 * 失败分支：先尝试解析 envelope；能解析说明是后端业务错误，
 * 交给 handleBusinessError；否则才认为是网络/网关异常。
 */
instance.interceptors.response.use(
  // 拦截器在此解包统一响应 envelope，返回值无法用 AxiosResponse 表达。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (resp): any => (resp.data as Result<unknown>).data,
  // 语义化状态码：从 error.response.data 解出 envelope，没有 envelope 才是网络异常
  (error) => {
    const body = parseEnvelope(error?.response?.data)
    if (body) {
      // 后端明确返回了业务错误：统一 toast 并按需处理登录失效。
      return Promise.reject(handleBusinessError(body))
    }
    // 无 envelope：超时、断网、CORS、网关 HTML 错误页等。
    toast.add({ title: '网络异常，请稍后重试', type: 'error' })
    return Promise.reject(error)
  },
)

/**
 * 对 axios 实例的类型化薄封装。
 *
 * 为什么需要：响应拦截器已经解包 data，但 axios 的类型仍假设返回
 * AxiosResponse，因此这里用 `as unknown as Promise<T>` 把真实运行时类型
 * 告诉调用方。默认 T=void 适合只关心成功/失败、无返回体的接口。
 */
const http = {
  /**
   * GET 请求。
   *
   * @param url 相对 `/api` 的路径
   * @param config 可传 params/headers/signal 等 axios 配置
   * @returns 解包后的 data
   */
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },

  /**
   * POST 请求。
   *
   * @param url 相对路径
   * @param data 请求体；FormData 也可直接传入
   * @param config 额外配置（如 AI 接口覆盖 timeout）
   * @returns 解包后的 data
   */
  post<T = void>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig,
  ): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },

  /**
   * PUT 请求，用于幂等更新（如资料、通知偏好）。
   *
   * @returns 解包后的 data
   */
  put<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },

  /**
   * DELETE 请求。
   *
   * @param config 可传 params（用于按查询参数删除）
   * @returns 解包后的 data
   */
  delete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  },
}

export default http
