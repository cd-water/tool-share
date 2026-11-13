import { useEffect, useRef } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { userApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { initLogin, type LoginBridge } from './login/initLogin'
import { LOGIN_VIEW_HTML } from './login/loginViewHtml'
import './login/login.css'

function safeRedirectPath(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/tools'
}

/**
 * 居民登录 —— 完整复刻原型 `01-居民登录.html`。
 * 静态结构与 SVG 资产原样内联，交互由 initLogin 驱动（charge 单一状态量 / 拖动上紧 /
 * 点击变形 / 密码百叶窗 / 齿轮虹膜转场），登录与验证码走后端真实接口。
 */
export default function LoginPage() {
  const hostRef = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const tokenValue = useAuthStore((s) => s.tokenValue)
  const redirect = safeRedirectPath(location.state)

  useEffect(() => {
    if (!hostRef.current) return
    const bridge: LoginBridge = {
      adminUrl: import.meta.env.DEV ? 'http://localhost:5902/' : '/admin/',
      async login(values) {
        try {
          const data =
            values.mode === 'code'
              ? await userApi.loginByCode(values.phone, values.code)
              : await userApi.loginByPassword(values.phone, values.password)
          return { ok: true as const, token: data.tokenValue }
        } catch (err) {
          return { ok: false as const, message: err instanceof Error ? err.message : '登录失败，请稍后重试' }
        }
      },
      async sendCode(phone) {
        try {
          // 后端 MOCK 短信会直接把验证码返回，便于演示
          const code = await userApi.sendCode(phone)
          return { ok: true as const, code }
        } catch (err) {
          return { ok: false as const, message: err instanceof Error ? err.message : '发送失败，请稍后重试' }
        }
      },
      enter(token) {
        useAuthStore.getState().setAuth(token)
        queryClient.removeQueries()
        navigate(redirect, { replace: true })
      },
    }
    return initLogin(hostRef.current, bridge)
  }, [navigate, queryClient, redirect])

  if (tokenValue) return <Navigate to={redirect} replace />

  return <div ref={hostRef} dangerouslySetInnerHTML={{ __html: LOGIN_VIEW_HTML }} />
}
