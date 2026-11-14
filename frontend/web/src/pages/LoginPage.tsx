/**
 * 文件职责：居民登录页（LoginPage），路由 /login。
 *
 * 该页面是原型 `01-居民登录.html` 的 React 移植：
 * - 静态结构与 SVG 资产以字符串形式内联，由 dangerouslySetInnerHTML 挂载；
 * - 交互逻辑由 initLogin 以原生 DOM 方式驱动（齿圈上紧、点击变形、
 *   密码百叶窗、齿轮虹膜转场等）；
 * - 登录、验证码发送走后端真实接口；
 * - 登录成功后通过 bridge.enter 写入 token、清空查询缓存并跳回来源页。
 *
 * 认证模型：
 * - useAuthStore 保存 token；已登录用户访问本页会直接重定向；
 * - 只有进入转场覆盖层后才写入登录态，避免页面提前卸载导致动画中断。
 *
 * 安全性：
 * - redirect 只接受以单个 / 开头的站内路径，拒绝 // 与外部协议；
 * - 密码只在提交时读取，不在前端持久化；
 * - 验证码为一次性消费，Mock 环境会在响应中返回用于演示。
 *
 * 错误处理：
 * - 登录/发送验证码失败通过 bridge 返回 { ok:false, message }；
 * - initLogin 负责 toast 与机械动画反馈，本组件只负责错误信息传递。
 */
import { useEffect, useRef } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { userApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { initLogin, type LoginBridge } from './login/initLogin'
import { LOGIN_VIEW_HTML } from './login/loginViewHtml'
import './login/login.css'

/**
 * 解析登录成功后的安全跳转路径。
 *
 * @param state react-router 的 location.state。
 * @returns 站内路径；非法或缺失时回退 /tools。
 *
 * 只接受以单个“/”开头且不以“//”开头的字符串，
 * 防止开放重定向到外部站点或协议相对地址。
 * 不校验目标路由是否存在，由路由层处理。
 * 副作用：无。
 */
function safeRedirectPath(state: unknown): string {
  // 从 state 中取 from 字段：
  // 非对象或字段类型不是字符串时视为缺失。
  // location.state 可能来自 RequireAuth 的重定向。
  const from = (state as { from?: unknown } | null)?.from
  // 站内路径校验：
  // - 以 / 开头；
  // - 不以 // 开头（协议相对地址）；
  // 否则回退工具广场。
  // 允许带 query，便于恢复深链接筛选。
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/tools'
}

/**
 * 居民登录 —— 完整复刻原型 `01-居民登录.html`。
 * 静态结构与 SVG 资产原样内联，交互由 initLogin 驱动（charge 单一状态量 / 拖动上紧 /
 * 点击变形 / 密码百叶窗 / 齿轮虹膜转场），登录与验证码走后端真实接口。
 */
// 组件只负责挂载登录视图与桥接 API：
// 动画与表单交互由 initLogin 管理。
// 认证状态由 auth store 与 RequireAuth 共同保证。
// 不在组件内直接操作 DOM 或样式。
// 登录成功后由 enter 完成状态写入与导航。
// 已登录用户会直接重定向，不渲染视图。
// 该组件不持久化密码或验证码。
// 所有错误文案由 bridge 返回。
export default function LoginPage() {
  // DOM 挂载点：
  // initLogin 会在此容器内查询元素并绑定事件。
  // 容器内容由 LOGIN_VIEW_HTML 注入。
  // 引用在组件生命周期内稳定。
  const hostRef = useRef<HTMLDivElement>(null)
  // 路由工具：
  // navigate 用于登录成功后跳转。
  // 使用 replace 避免登录页留在历史记录。
  const navigate = useNavigate()
  // 当前 location：
  // 提供来源路径（state.from）与 query。
  // 只读取，不修改历史。
  const location = useLocation()
  // 查询缓存：
  // 登录成功后清空，避免上一个账号数据残留。
  // 不在此处发起请求。
  const queryClient = useQueryClient()
  // 当前登录态：
  // 非空时直接重定向到目标路径。
  // 订阅 store，登录/退出后自动更新。
  const tokenValue = useAuthStore((s) => s.tokenValue)
  // 目标路径：
  // 由 safeRedirectPath 解析，非法时回退 /tools。
  // 该值同时用于已登录重定向与 enter 跳转。
  const redirect = safeRedirectPath(location.state)

  // 初始化原生交互：
  // 在宿主 DOM 挂载后调用 initLogin。
  // 登录桥接负责真实 API 调用、状态写入与导航。
  // 返回的清理函数会移除事件监听与覆盖层。
  // 依赖 navigate/queryClient/redirect：变化时重新初始化。
  // 宿主节点不存在时直接跳过。
  useEffect(() => {
    // 宿主节点未就绪时直接返回：
    // 通常发生在极端的渲染时序下。
    // 不抛错，避免阻断页面。
    if (!hostRef.current) return
    // LoginBridge 是 React 与原生交互层之间的契约：
    // - login/sendCode 返回可判别结果，不直接抛错；
    // - enter 在转场完成后写入登录态并跳转。
    // 这样 initLogin 不需要了解 API 细节。
    const bridge: LoginBridge = {
      // 管理端入口地址：
      // 开发环境指向本地 5902，生产环境指向 /admin/。
      // 由 initLogin 写入管理端链接。
      // 该地址不参与登录流程。
      adminUrl: import.meta.env.DEV ? 'http://localhost:5902/' : '/admin/',
      /**
       * 执行登录。
       *
       * @param values 登录模式、手机号、验证码/密码。
       * @returns 成功时返回 token；失败时返回可展示的错误信息。
       *
       * 副作用：发起网络请求，但不写 store。
       * 写 store 延迟到 enter，避免转场期间页面卸载。
       */
      async login(values) {
        try {
          // 根据模式选择验证码登录或密码登录。
          // 两个接口返回相同形状：包含 tokenValue。
          // 使用三元表达式保持分支紧凑。
          const data =
            values.mode === 'code'
              ? await userApi.loginByCode(values.phone, values.code)
              : await userApi.loginByPassword(values.phone, values.password)
          // 成功：只返回 token，不写 store。
          // 由 enter 在转场盖满屏幕后写入。
          // 这样动画不会被登录态变化打断。
          return { ok: true as const, token: data.tokenValue }
        } catch (err) {
          // 失败：返回错误信息。
          // Error 实例取 message，其他类型使用默认文案。
          // initLogin 会根据结果播放错误动画并 toast。
          return { ok: false as const, message: err instanceof Error ? err.message : '登录失败，请稍后重试' }
        }
      },
      /**
       * 发送登录验证码。
       *
       * @param phone 11 位手机号。
       * @returns 成功时可返回 Mock 验证码（演示环境）或空。
       *
       * 副作用：发起网络请求。
       * Mock 短信演示环境会直接返回 6 位验证码；
       * 关闭开关时返回 null，需查看后端日志。
       */
      async sendCode(phone) {
        try {
          // Mock 短信演示环境会直接返回 6 位验证码；关闭开关时为 null
          // 调用发送验证码接口：
          // Mock 环境返回验证码字符串，生产环境通常为空。
          // 失败时捕获并返回错误信息。
          // 不在前端生成验证码。
          const code = await userApi.sendCode(phone)
          return { ok: true as const, code: code ?? undefined }
        } catch (err) {
          return { ok: false as const, message: err instanceof Error ? err.message : '发送失败，请稍后重试' }
        }
      },
      /**
       * 转场完成后进入系统。
       *
       * @param token 登录成功得到的 token。
       *
       * 副作用：
       * - 写入 auth store；
       * - 清空全部 React Query 缓存，避免上一个账号的数据泄漏；
       * - 以 replace 跳转到目标路径，避免后退回到登录页。
       */
      enter(token) {
        // 写入登录态：
        // 后续页面守卫与请求拦截器会读取该 token。
        // 写入发生在覆盖层盖满屏幕之后。
        useAuthStore.getState().setAuth(token)
        // 清空查询缓存：
        // 防止上一个账号的租赁/消息等数据残留。
        // removeQueries 不触发网络请求。
        queryClient.removeQueries()
        // 跳转到来源页或工具广场。
        // replace 避免登录页留在历史记录中。
        navigate(redirect, { replace: true })
      },
    }
    // 启动原生交互并返回清理函数。
    // useEffect 卸载时自动执行清理。
    // 桥接对象在本次 effect 内创建，引用稳定。
    return initLogin(hostRef.current, bridge)
  // 依赖变化时重新初始化：
  // 导航函数、缓存实例或目标路径变化都会重建桥接。
  // 正常使用中这些值稳定，effect 只执行一次。
  }, [navigate, queryClient, redirect])

  // 已登录用户：
  // 直接重定向到目标路径，不渲染登录视图。
  // 使用 replace 避免历史记录中出现登录页。
  if (tokenValue) return <Navigate to={redirect} replace />

  // 未登录：
  // 渲染宿主容器并由 initLogin 注入静态结构。
  // dangerouslySetInnerHTML 的内容来自受信任的原型字符串，不包含用户输入。
  // 容器本身不承载 React 子节点。
  return <div ref={hostRef} dangerouslySetInnerHTML={{ __html: LOGIN_VIEW_HTML }} />
// 组件结束。LoginPage 的边界：
// - 只负责挂载登录视图与桥接 API；
// - 动画与表单交互由 initLogin 管理；
// - 认证状态由 auth store 与 RequireAuth 共同保证。
// 若需要新增登录方式，应扩展 LoginBridge 与 initLogin，而不是直接操作 DOM。
}
