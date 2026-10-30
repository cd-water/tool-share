import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff, Loader2 } from 'lucide-react'
import { BrandMark } from '@/components/BrandMark'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Field, FieldError, FieldLabel } from '@/components/ui/field'
import { Input } from '@/components/ui/input'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { userApi } from '@/lib/api'
import { userInfoQuery } from '@/lib/queries'
import {
  loginSchema,
  type LoginFormValues,
  type LoginMode,
} from '@/lib/schemas'
import { useAuthStore } from '@/stores/auth'

function safeFrom(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from
  return typeof from === 'string' &&
    from.startsWith('/') &&
    !from.startsWith('//')
    ? from
    : '/page-1'
}

export default function LoginView() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const tokenValue = useAuthStore((s) => s.tokenValue)
  const [countdown, setCountdown] = useState(0)
  const [showPassword, setShowPassword] = useState(false)

  const {
    control,
    clearErrors,
    getValues,
    handleSubmit,
    setValue,
    trigger,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
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
        : userApi.loginByPwd(values.phone, values.password),
    onSuccess: async (data) => {
      useAuthStore.getState().setAuth(data.tokenValue)
      queryClient.removeQueries()
      await queryClient.fetchQuery(userInfoQuery).catch(() => undefined)
      navigate(safeFrom(location.state), { replace: true })
    },
  })

  if (tokenValue) {
    return <Navigate to={safeFrom(location.state)} replace />
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

  return (
    <div className="flex min-h-full items-center justify-center bg-[#FFFBEB] px-4 py-10">
      <Card className="w-full max-w-[400px] gap-0 overflow-hidden border-[#F0E4C0] py-0 shadow-[0_4px_16px_rgba(245,179,1,0.08)]">
        <div className="h-1 bg-primary" />
        <CardHeader className="px-9 pt-8 pb-5">
          <div className="flex items-center gap-2.5">
            <BrandMark size={36} />
            <span className="text-[17px] font-semibold whitespace-nowrap text-foreground">
              TicketBox <span className="mx-1 font-normal text-foreground">|</span> 票务盒子
            </span>
          </div>
        </CardHeader>
        <CardContent className="px-9 pb-8">
          <Tabs value={mode} onValueChange={(value) => switchMode(value as LoginMode)}>
            <TabsList variant="line" className="mb-6 w-full">
              <TabsTrigger value="code" className="flex-1">
                验证码登录
              </TabsTrigger>
              <TabsTrigger value="pwd" className="flex-1">
                密码登录
              </TabsTrigger>
            </TabsList>
          </Tabs>

          <form onSubmit={handleSubmit((values) => loginMutation.mutate(values))} noValidate>
            <Field className="mb-4" data-invalid={Boolean(errors.phone)}>
              <FieldLabel htmlFor="phone">手机号</FieldLabel>
              <Controller
                control={control}
                name="phone"
                render={({ field, fieldState }) => (
                  <>
                    <Input
                      id="phone"
                      type="tel"
                      inputMode="numeric"
                      autoComplete="tel"
                      placeholder="请输入手机号"
                      aria-invalid={fieldState.invalid}
                      {...field}
                    />
                    {fieldState.error ? (
                      <FieldError errors={[fieldState.error]} />
                    ) : null}
                  </>
                )}
              />
            </Field>

            {mode === 'code' ? (
              <Field className="mb-4" data-invalid={Boolean(errors.code)}>
                <FieldLabel htmlFor="code">验证码</FieldLabel>
                <div className="flex gap-2">
                  <Controller
                    control={control}
                    name="code"
                    render={({ field, fieldState }) => (
                      <Input
                        id="code"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        placeholder="6 位验证码"
                        className="flex-1"
                        aria-invalid={fieldState.invalid}
                        {...field}
                      />
                    )}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    className="shrink-0"
                    disabled={countdown > 0 || sendCodeMutation.isPending}
                    onClick={handleSendCode}
                  >
                    {countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}
                  </Button>
                </div>
                <FieldError errors={[errors.code]} />
              </Field>
            ) : (
              <Field className="mb-4" data-invalid={Boolean(errors.password)}>
                <FieldLabel htmlFor="password">密码</FieldLabel>
                <Controller
                  control={control}
                  name="password"
                  render={({ field, fieldState }) => (
                    <>
                      <div className="relative">
                        <Input
                          id="password"
                          type={showPassword ? 'text' : 'password'}
                          autoComplete="current-password"
                          placeholder="请输入密码"
                          className="pr-9"
                          aria-invalid={fieldState.invalid}
                          {...field}
                        />
                        <button
                          type="button"
                          aria-label={showPassword ? '隐藏密码' : '显示密码'}
                          className="absolute inset-y-0 right-0 flex w-9 items-center justify-center text-muted-foreground hover:text-foreground"
                          onClick={() => setShowPassword((value) => !value)}
                        >
                          {showPassword ? (
                            <EyeOff className="size-4" />
                          ) : (
                            <Eye className="size-4" />
                          )}
                        </button>
                      </div>
                      {fieldState.error ? (
                        <FieldError errors={[fieldState.error]} />
                      ) : null}
                    </>
                  )}
                />
              </Field>
            )}

            <Button
              type="submit"
              className="mt-2 h-10 w-full"
              disabled={loginMutation.isPending}
            >
              {loginMutation.isPending ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                '登 录'
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
