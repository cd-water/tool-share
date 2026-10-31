import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Camera, CheckCircle2, Loader2, ShieldAlert, UserRound } from 'lucide-react'
import { profileApi } from '@/api/profile'
import { uploadApi } from '@/api/upload'
import type { ProfileUpdatePayload } from '@/api/types'
import { Loading } from '@/components/Loading'
import { PageHeader } from '@/components/PageHeader'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { FieldError, Label } from '@/components/ui/label'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

const profileSchema = z.object({
  nickname: z
    .string()
    .trim()
    .min(1, '请填写昵称')
    .max(20, '昵称不超过 20 个字'),
  avatar: z.string(),
  realName: z.string().trim().max(30, '真实姓名不超过 30 个字'),
  idCard: z
    .string()
    .trim()
    .refine(
      (value) => value === '' || /^\d{17}[\dXx]$/.test(value),
      '请输入正确的 18 位身份证号',
    ),
})

type ProfileValues = z.infer<typeof profileSchema>

export default function ProfilePage() {
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)

  const profileQuery = useQuery({
    queryKey: ['profile'],
    queryFn: profileApi.get,
  })

  const {
    control,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    defaultValues: { nickname: '', avatar: '', realName: '', idCard: '' },
  })

  const profile = profileQuery.data
  const avatarValue = watch('avatar')
  const nicknameValue = watch('nickname')

  useEffect(() => {
    if (!profile) return
    reset({
      nickname: profile.nickname ?? '',
      avatar: profile.avatar ?? '',
      realName: profile.realName ?? '',
      idCard: '',
    })
  }, [profile, reset])

  const updateMutation = useMutation({
    mutationFn: (values: ProfileValues) => {
      const payload: ProfileUpdatePayload = {
        nickname: values.nickname.trim(),
        avatar: values.avatar,
        realName: values.realName.trim(),
      }
      if (values.idCard.trim()) {
        payload.idCard = values.idCard.trim()
      }
      return profileApi.update(payload)
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(['profile'], updated)
      queryClient.invalidateQueries({ queryKey: ['profile'] })
      queryClient.invalidateQueries({ queryKey: ['user', 'info'] })
      useAuthStore.getState().setProfile({
        nickname: updated.nickname,
        avatar: updated.avatar,
      })
      reset({
        nickname: updated.nickname ?? '',
        avatar: updated.avatar ?? '',
        realName: updated.realName ?? '',
        idCard: '',
      })
      toast.add({ title: '个人资料已保存', type: 'success' })
    },
  })

  async function handleAvatarFile(files: FileList | null) {
    const file = files?.[0]
    if (!file) return
    if (!file.type.startsWith('image/')) {
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    setUploading(true)
    try {
      const result = await uploadApi.upload(file)
      setValue('avatar', result.url, { shouldDirty: true })
      toast.add({ title: '头像上传成功，保存后生效', type: 'success' })
    } catch {
      // 上传失败提示由请求拦截器统一处理
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const hasProfile = Boolean(profile?.realName && profile?.idCard)

  if (profileQuery.isLoading) return <Loading />

  return (
    <div>
      <PageHeader
        title="个人中心"
        description="维护昵称、头像、真实姓名与身份证信息。"
      />

      <div className="grid gap-5 lg:grid-cols-[320px_1fr]">
        <div className="space-y-4">
          <div className="rounded-2xl border border-border bg-card p-5">
            <div className="flex items-center gap-4">
              <div className="relative">
                <Avatar className="size-16">
                  <AvatarImage src={avatarValue || undefined} alt="头像" />
                  <AvatarFallback className="text-xl">
                    {(nicknameValue || '用').slice(0, 1)}
                  </AvatarFallback>
                </Avatar>
                <button
                  type="button"
                  disabled={uploading}
                  onClick={() => fileInputRef.current?.click()}
                  className="absolute -right-1 -bottom-1 flex size-7 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-sm disabled:opacity-60"
                  aria-label="上传头像"
                >
                  {uploading ? (
                    <Loader2 className="size-3.5 animate-spin" />
                  ) : (
                    <Camera className="size-3.5" />
                  )}
                </button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/*"
                  className="hidden"
                  onChange={(event) => handleAvatarFile(event.target.files)}
                />
              </div>
              <div className="min-w-0">
                <p className="truncate font-semibold text-foreground">
                  {profile?.nickname || '未设置昵称'}
                </p>
                <p className="text-sm text-muted-foreground">
                  {profile?.phone || '—'}
                </p>
              </div>
            </div>

            <dl className="mt-4 space-y-2 border-t border-border pt-4 text-sm">
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">手机号</dt>
                <dd className="text-foreground">{profile?.phone || '—'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">真实姓名</dt>
                <dd className="text-foreground">
                  {profile?.realName || '未填写'}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted-foreground">身份证号</dt>
                <dd className="font-mono text-foreground">
                  {profile?.idCard || '未填写'}
                </dd>
              </div>
            </dl>
          </div>

          <div
            className={
              hasProfile
                ? 'flex items-start gap-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-700'
                : 'flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800'
            }
          >
            {hasProfile ? (
              <CheckCircle2 className="mt-0.5 size-4 shrink-0" />
            ) : (
              <ShieldAlert className="mt-0.5 size-4 shrink-0" />
            )}
            <p>
              {hasProfile
                ? '资料已完善，可以正常预约租赁。'
                : '尚未完善真实姓名与身份证号。预约租赁前需先填写并保存资料。'}
            </p>
          </div>
        </div>

        <form
          className="space-y-4 rounded-2xl border border-border bg-card p-5"
          onSubmit={handleSubmit((values) => updateMutation.mutate(values))}
          noValidate
        >
          <div className="flex items-center gap-2 border-b border-border pb-3">
            <UserRound className="size-4 text-primary" />
            <h2 className="text-sm font-semibold text-foreground">编辑资料</h2>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <Label htmlFor="nickname">昵称</Label>
              <Controller
                control={control}
                name="nickname"
                render={({ field, fieldState }) => (
                  <Input
                    id="nickname"
                    placeholder="请输入昵称"
                    className="h-10 bg-card"
                    aria-invalid={fieldState.invalid}
                    {...field}
                  />
                )}
              />
              <FieldError>{errors.nickname?.message}</FieldError>
            </div>

            <div>
              <Label htmlFor="phone">手机号</Label>
              <Input
                id="phone"
                value={profile?.phone ?? ''}
                readOnly
                disabled
                className="h-10 bg-muted/40"
              />
              <p className="mt-1 min-h-4 text-xs leading-4 text-muted-foreground">
                手机号已脱敏，仅用于联系与登录
              </p>
            </div>

            <div>
              <Label htmlFor="realName">真实姓名</Label>
              <Controller
                control={control}
                name="realName"
                render={({ field, fieldState }) => (
                  <Input
                    id="realName"
                    placeholder="请输入真实姓名"
                    className="h-10 bg-card"
                    aria-invalid={fieldState.invalid}
                    {...field}
                  />
                )}
              />
              <FieldError>{errors.realName?.message}</FieldError>
            </div>

            <div>
              <Label htmlFor="idCard">身份证号</Label>
              <Controller
                control={control}
                name="idCard"
                render={({ field, fieldState }) => (
                  <Input
                    id="idCard"
                    placeholder="留空则不修改"
                    className="h-10 bg-card font-mono"
                    aria-invalid={fieldState.invalid}
                    {...field}
                  />
                )}
              />
              <FieldError>{errors.idCard?.message}</FieldError>
            </div>

            <div className="sm:col-span-2">
              <Label htmlFor="avatar">头像地址</Label>
              <Controller
                control={control}
                name="avatar"
                render={({ field }) => (
                  <Input
                    id="avatar"
                    placeholder="可点击左侧头像上传，或直接填写图片地址"
                    className="h-10 bg-card"
                    {...field}
                  />
                )}
              />
              <p className="mt-1 min-h-4 text-xs leading-4 text-muted-foreground">
                建议上传清晰的正方形图片
              </p>
            </div>
          </div>

          <div className="flex justify-end border-t border-border pt-4">
            <Button type="submit" disabled={updateMutation.isPending}>
              {updateMutation.isPending && (
                <Loader2 className="size-4 animate-spin" />
              )}
              保存资料
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
