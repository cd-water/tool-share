/**
 * 统一 HTTP 请求封装。
 *
 * 职责：
 * - 创建 axios 实例，统一 baseURL（/api）与超时时间；
 * - 请求阶段自动注入 Bearer token；
 * - 响应阶段解包后端统一 envelope（{ code, message, data }），
 *   让业务层直接拿到 data；
 * - 业务错误按语义统一提示：C401 清会话并跳登录，其余弹出 message；
 * - blob 下载失败时只解析 JSON 错误体，避免把错误内容当文件保存。
 *
 * 约定：这是 admin 端唯一的网络出口，页面与 store 不直接使用 axios。
 */
import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

// 后端统一约定的登录态失效业务码。
// 注意：它可能出现在 HTTP 200 的 envelope 中，不能只看 HTTP 状态码。
const UNAUTHORIZED_CODE = 'C401'

/**
 * 后端统一响应 envelope。
 *
 * @template T data 字段的实际类型
 * code 是业务状态码；message 用于错误提示；失败时 data 可能为空。
 * 页面层不会直接接触该结构，拦截器会提前解包。
 */
interface Result<T> {
  code: string
  message: string
  data: T
}

/** 响应体里的 envelope；不是统一响应结构时返回 null */
function parseEnvelope(data: unknown): Result<unknown> | null {
  // 只有对象且 code 为字符串时才视为统一 envelope；
  // 文件流、空响应等非 envelope 数据返回 null，交由调用方区分。
  if (
    data !== null &&
    typeof data === 'object' &&
    typeof (data as Result<unknown>).code === 'string'
  ) {
    return data as Result<unknown>
  }
  // 不是统一结构：可能是原始 JSON 或其他内容。
  return null
}

/**
 * 清理登录态并跳转登录页。
 *
 * 副作用：
 * - 调用 auth store 的 reset 清空 token、用户名和角色；
 * - 使用 router 跳转，保持 SPA 体验，不整页刷新；
 * - 把当前完整路径写入 redirect，登录后可回到原页面。
 * 已经处于登录页时不再重复跳转，避免冗余导航。
 */
function redirectToLogin() {
  useAuthStore().reset()
  if (router.currentRoute.value.path !== '/login') {
    router.push({
      path: '/login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
}

/**
 * 处理带 envelope 的业务错误。
 *
 * @param body 已解析的后端响应体
 * @returns 构造出的 Error，供调用链继续 reject
 * 副作用：C401 触发跳登录，其他业务码通过 ElMessage 弹出错误文案。
 * 这里返回 Error 而不是直接抛出，方便调用方统一走 reject 分支。
 */
function handleBusinessError(body: Result<unknown>): Error {
  // 登录态失效：清会话并跳登录，不再重复弹业务错误。
  if (body.code === UNAUTHORIZED_CODE) {
    redirectToLogin()
  // 其他业务错误：直接把后端文案展示给用户。
  } else {
    ElMessage.error(body.message || '请求失败')
  }
  return new Error(body.message || '请求失败')
}

/**
 * 解析错误体。blob 下载失败时后端返回 JSON envelope，
 * 只有 content-type 是 JSON 才解析，避免把真正的错误二进制当 Excel 保存。
 */
async function parseErrorBody(data: unknown, headerType = ''): Promise<Result<unknown> | null> {
  if (data instanceof Blob) {
    // 优先使用 Blob 自身类型；某些环境下需要回退到响应头。
    const contentType = data.type || headerType
    // 非 JSON 的二进制通常是真正的文件内容，不应当作错误解析。
    if (!contentType.includes('json')) return null
    // Blob 内容可能损坏或不是合法 JSON，解析失败按「非 envelope」处理。
    try {
      return parseEnvelope(JSON.parse(await data.text()))
    } catch {
      return null
    }
  }
  // 普通响应：直接尝试按 envelope 解析。
  return parseEnvelope(data)
}

/**
 * axios 实例。
 *
 * baseURL 固定为 /api，开发时由 Vite 代理转发到后端；
 * 30 秒超时兼顾普通查询与较大的导出请求，失败时不会无限挂起。
 */
const instance = axios.create({
  // 所有业务路径都以 /api 为前缀，便于反向代理与多环境部署。
  baseURL: '/api',
  // 单位毫秒；导出等重请求也在该上限内。
  timeout: 30_000,
})

/**
 * 请求拦截器：自动附带登录凭证。
 *
 * 从 auth store 读取当前 token（store 已持久化，刷新页面后仍可用），
 * 仅在已登录时注入 Authorization: Bearer <token>；
 * 未登录请求保持原样，登录等公共接口不受影响。
 */
instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  // 避免在未登录时发送空的 Authorization 头。
  if (auth.isLoggedIn) {
    config.headers.Authorization = `Bearer ${auth.tokenValue}`
  }
  return config
})

/**
 * 响应拦截器。
 *
 * 成功分支：blob 请求返回原始二进制，其余请求解包 envelope 的 data；
 * 失败分支：先尝试解析后端错误 envelope，再按 C401/网络异常分类提示。
 * 这样业务层拿到的就是纯 data，错误提示也保持一致。
 */
instance.interceptors.response.use(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (resp): any => {
    // 文件下载需要完整 Blob，不能按 envelope 解包。
    if (resp.config.responseType === 'blob') {
      return resp.data
    }
    // 统一响应结构：把 { code, message, data } 中的 data 交给业务层。
    return (resp.data as Result<unknown>).data
  },
  // 语义化状态码：从 error.response.data 解出 envelope，没有 envelope 才是网络异常
  async (error) => {
    const response = error?.response
    const body = await parseErrorBody(
      response?.data,
      String(response?.headers?.['content-type'] ?? ''),
    )
    // 能解析出 envelope 说明是业务错误，交给统一处理函数。
    if (body) {
      return Promise.reject(handleBusinessError(body))
    }
    // 没有 envelope 但状态码是 401：兜底处理旧接口或网关返回。
    if (response?.status === 401) {
      redirectToLogin()
      ElMessage.warning('登录已过期，请重新登录')
      return Promise.reject(error)
    }
    // 其余情况视为网络异常（断网、超时、5xx 无响应体等）。
    ElMessage.error('网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

/**
 * 对 axios 实例的轻量封装。
 *
 * 泛型 T 表示解包后的 data 类型，默认 void 适配无响应体的写操作；
 * 方法内部用 unknown 双重断言绕过 axios 的类型推导，
 * 类型安全由各 API 模块调用时传入的泛型参数保证。
 */
const http = {
  /**
   * GET 请求。
   *
   * @param url 相对 /api 的路径
   * @param config axios 配置，常用于 { params } 传查询参数
   * @returns 解包后的 data
   */
  get<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  /**
   * POST 请求（创建、状态流转等）。
   *
   * @param url 相对 /api 的路径
   * @param data 请求体；FormData 会由浏览器自动编码
   * @param config 额外 axios 配置
   * @returns 解包后的 data
   */
  post<T = void>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  /**
   * PUT 请求（整体更新资源）。
   *
   * @param url 相对 /api 的路径
   * @param data 完整请求体
   * @returns 解包后的 data
   */
  put<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },
  /**
   * PATCH 请求（局部更新，如审核、状态调整）。
   *
   * @param url 相对 /api 的路径
   * @param data 局部字段请求体
   * @returns 解包后的 data
   */
  patch<T = void>(url: string, data?: unknown): Promise<T> {
    return instance.patch(url, data) as unknown as Promise<T>
  },
  /**
   * DELETE 请求。
   *
   * @param url 相对 /api 的路径
   * @param config 额外 axios 配置
   * @returns 解包后的 data
   */
  delete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  },
  /**
   * 下载二进制文件。
   *
   * @param url 相对 /api 的路径
   * @param params 查询参数（通常复用列表筛选条件）
   * @returns Blob，由响应拦截器直接透传
   * 副作用：不自动触发浏览器下载，调用方需创建临时 URL 并指定文件名。
   * 错误处理：若后端返回 JSON 错误，拦截器会解析 envelope 并提示。
   */
  download(url: string, params?: object): Promise<Blob> {
    return instance.get(url, {
      params,
      responseType: 'blob',
    }) as unknown as Promise<Blob>
  },
}

/**
 * 默认导出单例：所有 API 模块共享同一 axios 实例与拦截器配置。
 */
export default http
