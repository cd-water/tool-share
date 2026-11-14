/**
 * 管理端鉴权接口（登录、注销、当前用户信息）。
 *
 * 登录成功后返回 token，由 Pinia 的 auth store 负责持久化；
 * 请求拦截器会自动把 token 注入 Authorization 头，本层保持无状态。
 */
import http from '@/api/request'
import type { AdminLoginDTO, AdminVO, LoginVO } from '@/api/types'

/**
 * 鉴权 API 集合。
 *
 * 这里刻意不读写 token：状态的保存与清理集中在 auth store，
 * API 层只关心请求与响应，便于复用和测试。
 */
export const authApi = {
  /**
   * 使用用户名和密码登录管理端。
   *
   * @param username 登录名
   * @param password 明文密码（仅通过请求体传输，生产环境应走 HTTPS）
   * @returns LoginVO，主要消费其中的 tokenValue
   * @throws 账号或密码错误、账号被禁用时由响应拦截器提示并 reject
   */
  login: (username: string, password: string) =>
    http.post<LoginVO>('/admin/login', { username, password } satisfies AdminLoginDTO),
  /**
   * 注销当前会话，使服务端 token 失效。
   *
   * 即使请求失败，store 的 logout 也会在 finally 中清空本地状态，
   * 因此这里不需要额外兜底；失败时拒绝原因仍会传给调用方。
   */
  logout: () => http.post<void>('/admin/logout'),
  /**
   * 获取当前登录管理员的资料。
   *
   * @returns 管理员信息（用户名、角色等）
   * 页面刷新后用于恢复会话信息；旧会话只有 token 时也靠它补拉角色。
   * @throws token 失效时拦截器会清理会话并跳转登录页
   */
  info: () => http.get<AdminVO>('/admin/info'),
}
