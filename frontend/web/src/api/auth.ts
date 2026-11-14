/**
 * 认证相关接口。
 *
 * 这里只声明「后端契约路径 + 入参/出参类型」，不处理任何状态：
 * - 请求统一走 `@/api/request` 的 axios 实例，由其负责注入 Bearer Token、
 *   解包统一响应 envelope，并把业务错误码转换成 rejected Promise；
 * - 登录成功后的 token 持久化由调用方（登录页）写入 `useAuthStore`；
 * - 返回类型均来自 `@/api/types`，最终由 OpenAPI 生成的 `generated.ts` 约束，
 *   避免前后端字段漂移。
 */
import http from '@/api/request'
import type { LoginResult, UserProfile } from '@/api/types'

export const userApi = {
  /**
   * 发送短信验证码。
   *
   * @param phone 手机号，由后端校验格式与发送频率
   * @returns 后端可能在开发环境回传验证码字符串，生产环境通常为 null
   *
   * 错误：手机号非法或发送过于频繁时，统一请求层会 toast 并 reject，
   * 调用方只需处理按钮 loading/倒计时状态。
   */
  sendCode: (phone: string) => http.post<string | null>('/user/send-code', { phone }),

  /**
   * 验证码登录。
   *
   * @param phone 手机号
   * @param code 短信验证码
   * @returns 登录结果（含 token 与基础用户信息），由登录页写入 auth store
   *
   * 错误：验证码错误/过期会 reject；页面据此提示用户重新获取。
   */
  loginByCode: (phone: string, code: string) =>
    http.post<LoginResult>('/user/login/by-code', { phone, code }),

  /**
   * 密码登录。
   *
   * @param phone 手机号
   * @param password 明文密码，仅通过 HTTPS 传输，前端不做持久化
   * @returns 同验证码登录，包含 token 与用户信息
   *
   * 错误：账号不存在或密码错误会 reject，由登录页展示后端 message。
   */
  loginByPassword: (phone: string, password: string) =>
    http.post<LoginResult>('/user/login/by-pwd', { phone, password }),

  /**
   * 注销当前会话。
   *
   * 副作用：后端会失效当前 token；前端在 `useLogout` 的 onSettled 中
   * 清理 auth store 与 React Query 缓存，即使请求失败也会本地登出，
   * 避免用户被卡在无法退出的状态。
   */
  logout: () => http.post<void>('/user/logout'),

  /**
   * 拉取当前登录用户资料。
   *
   * 用于 `useUserInfo` 在应用启动/刷新后恢复昵称与头像；
   * 401 时统一请求层会清空会话并跳回登录流程。
   */
  info: () => http.get<UserProfile>('/user/info'),
}
