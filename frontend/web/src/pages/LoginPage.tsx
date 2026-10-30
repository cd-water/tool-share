import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Loader2 } from 'lucide-react'
import { z } from 'zod'
import { BrandMark } from '@/components/BrandMark'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { userApi } from '@/api/auth'
import { userInfoOptions } from '@/hooks/useUserInfo'
import { useAuthStore } from '@/stores/auth'

type LoginMode = 'code' | 'pwd'

const loginFormSchema = z
  .object({
    mode: z.enum(['code', 'pwd']),
    phone: z.string().regex(/^1[3-9]\d{9}$/, '请输入正确的手机号'),
    code: z.string(),
    password: z.string(),
  })
  .superRefine((values, ctx) => {
    if (values.mode === 'code' && !/^\d{6}$/.test(values.code)) {
      ctx.addIssue({ code: 'custom', path: ['code'], message: '请输入 6 位验证码' })
    }
    if (values.mode === 'pwd' && values.password.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['password'], message: '请输入密码' })
    }
  })

type LoginFormValues = z.infer<typeof loginFormSchema>

function safeRedirectPath(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')
    ? from
    : '/page-1'
}

function ErrorLine({ msg }: { msg?: string }) {
  return <p className="min-h-4 text-xs leading-4 text-destructive">{msg ?? ''}</p>
}

export default function LoginPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const tokenValue = useAuthStore((s) => s.tokenValue)
  const [countdown, setCountdown] = useState(0)

  const {
    control,
    clearErrors,
    getValues,
    handleSubmit,
    setValue,
    trigger,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginFormSchema),
    defaultValues: { mode: 'code', phone: '', code: '', password: '' },
  })

  const mode = useWatch({ control, name: 'mode' })

  useEffect(() => {
    if (countdown <= 0) return
    const timer = window.setTimeout(() => setCountdown((value) => value - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [countdown])

  const sendCodeMutation = useMutation({
    mutationFn: (phone: string) => userApi.sendCode(phone),
    onSuccess: () => setCountdown(60),
  })

  const loginMutation = useMutation({
    mutationFn: (values: LoginFormValues) =>
      values.mode === 'code'
        ? userApi.loginByCode(values.phone, values.code)
        : userApi.loginByPassword(values.phone, values.password),
    onSuccess: async (data) => {
      useAuthStore.getState().setAuth(data.tokenValue)
      queryClient.removeQueries()
      await queryClient.fetchQuery(userInfoOptions).catch(() => undefined)
      navigate(safeRedirectPath(location.state), { replace: true })
    },
  })

  if (tokenValue) {
    return <Navigate to={safeRedirectPath(location.state)} replace />
  }

  function switchMode(next: LoginMode) {
    setValue('mode', next)
    clearErrors()
  }

  async function handleSendCode() {
    const valid = await trigger('phone')
    if (!valid) return
    sendCodeMutation.mutate(getValues('phone'))
  }

  const inputClassName = 'h-10 bg-card'

  return (
    <div className="flex min-h-svh items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm rounded-lg border border-border bg-card p-8">
        <div className="flex items-center justify-center gap-3">
          <BrandMark size={40} />
          <h1 className="text-xl font-semibold tracking-tight text-foreground">
            TicketBox <span className="font-normal">|</span> 票务盒子
          </h1>
        </div>

        <Tabs
          value={mode}
          onValueChange={(value) => switchMode(value as LoginMode)}
        >
          <TabsList className="mt-7 grid w-full grid-cols-2">
            <TabsTrigger value="code" className="data-active:bg-card data-active:text-foreground">
              验证码登录
            </TabsTrigger>
            <TabsTrigger value="pwd" className="data-active:bg-card data-active:text-foreground">
              密码登录
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <form
          className="mt-4 space-y-2"
          onSubmit={handleSubmit((values) => loginMutation.mutate(values))}
          noValidate
        >
          <div>
            <Controller
              control={control}
              name="phone"
              render={({ field, fieldState }) => (
                <Input
                  aria-label="手机号"
                  type="tel"
                  inputMode="numeric"
                  autoComplete="tel"
                  placeholder="手机号"
                  className={inputClassName}
                  aria-invalid={fieldState.invalid}
                  {...field}
                />
              )}
            />
            <ErrorLine msg={errors.phone?.message} />
          </div>

          {mode === 'code' ? (
            <div className="flex items-start gap-2">
              <div className="flex-1">
                <Controller
                  control={control}
                  name="code"
                  render={({ field, fieldState }) => (
                    <Input
                      aria-label="验证码"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      placeholder="验证码"
                      className={inputClassName}
                      aria-invalid={fieldState.invalid}
                      {...field}
                    />
                  )}
                />
                <ErrorLine msg={errors.code?.message} />
              </div>
              <Button
                type="button"
                variant="outline"
                className="h-10 shrink-0 border-input bg-card text-foreground"
                disabled={countdown > 0 || sendCodeMutation.isPending}
                onClick={handleSendCode}
              >
                {countdown > 0 ? `${countdown}s` : '发送验证码'}
              </Button>
            </div>
          ) : (
            <div>
              <Controller
                control={control}
                name="password"
                render={({ field, fieldState }) => (
                  <Input
                    aria-label="密码"
                    type="password"
                    autoComplete="current-password"
                    placeholder="密码"
                    className={inputClassName}
                    aria-invalid={fieldState.invalid}
                    {...field}
                  />
                )}
              />
              <ErrorLine msg={errors.password?.message} />
            </div>
          )}

          <Button
            type="submit"
            size="lg"
            className="w-full"
            disabled={loginMutation.isPending}
          >
            {loginMutation.isPending ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              '登录'
            )}
          </Button>
        </form>
      </div>
    </div>
  )
}
