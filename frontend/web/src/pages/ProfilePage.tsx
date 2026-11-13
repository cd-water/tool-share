import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Camera, CheckCircle2, Loader2, LogOut, Phone, Save, ShieldAlert } from 'lucide-react'
import { profileApi } from '@/api/profile'
import { rentalsApi } from '@/api/rentals'
import { uploadApi } from '@/api/upload'
import type { ProfileUpdatePayload } from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { SoundFX } from '@/lib/fx'
import { formatDateTime } from '@/lib/format'
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

function useTogglePref(key: string, defaultOn: boolean) {
  const [on, setOn] = useState<boolean>(() => {
    const saved = localStorage.getItem(key)
    return saved === null ? defaultOn : saved !== 'off'
  })

  const toggle = () =>
    setOn((prev) => {
      const next = !prev
      localStorage.setItem(key, next ? 'on' : 'off')
      return next
    })

  return { on, toggle }
}

export default function ProfilePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)

  const profileQuery = useQuery({
    queryKey: ['profile'],
    queryFn: profileApi.get,
  })
  const rentalsQuery = useQuery({
    queryKey: ['rentals', 'profile'],
    queryFn: () => rentalsApi.list({ page: 1, size: 200 }),
  })
  const rentals = rentalsQuery.data?.records ?? []
  const rentalTotal = rentalsQuery.data?.total ?? rentals.length
  const returnedCount = rentals.filter((item) => item.status === 'RETURNED').length
  const onTimeRate = rentalTotal >= 3 ? Math.round((returnedCount / rentalTotal) * 100) : 96

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
  const showAvatar = Boolean(avatarValue) && !/github\.com/i.test(avatarValue)

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

  const completeness = (() => {
    const fields = [
      profile?.nickname,
      profile?.avatar,
      profile?.realName,
      profile?.idCard,
    ]
    const filled = fields.filter(Boolean).length
    return Math.round((filled / fields.length) * 100)
  })()

  const [soundOn, setSoundOn] = useState<boolean>(() => SoundFX.isOn())
  const remindPref = useTogglePref('ts_remind', true)
  const watchPref = useTogglePref('ts_watch', true)
  const noticePref = useTogglePref('ts_notice', false)

  function toggleSound() {
    const next = SoundFX.toggle()
    setSoundOn(next)
    if (next) SoundFX.pop()
  }

  const prefs: { label: string; hint: string; on: boolean; toggle: () => void }[] = [
    { label: '操作音效', hint: '按钮与提示的轻量反馈音', on: soundOn, toggle: toggleSound },
    { label: '到期提醒', hint: '租期结束前推送消息', on: remindPref.on, toggle: remindPref.toggle },
    { label: '蹲守通知', hint: '关注的工具可借时通知', on: watchPref.on, toggle: watchPref.toggle },
    { label: '社区公告', hint: '工具房开放与活动公告', on: noticePref.on, toggle: noticePref.toggle },
  ]

  if (profileQuery.isLoading) {
    return (
      <div>
        <div className="page-head">
          <div>
            <h1>个人中心</h1>
            <p className="sub">维护昵称、头像、真实姓名与身份证信息。</p>
          </div>
        </div>
        <div className="card" style={{ padding: 26, marginBottom: 18 }}>
          <div className="skel" style={{ width: 88, height: 88, borderRadius: '50%' }} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 340px', gap: 18 }}>
          <div className="panel">
            <div className="skel" style={{ height: 320 }} />
          </div>
          <div className="panel">
            <div className="skel" style={{ height: 220 }} />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div>
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">MY ACCOUNT</p>
          <h1>个人中心</h1>
          <p className="sub">完善实名信息可提升信用额度，享受免押金等社区权益。</p>
        </div>
      </div>

      <div className="profile-hero card reveal">
        <div className="ph-ava-wrap">
          {showAvatar ? (
            <img className="ph-ava" src={avatarValue} alt="头像" />
          ) : (
            <span className="ph-ava ph-ava-fallback">{(nicknameValue || '用').slice(0, 1)}</span>
          )}
          <button
            type="button"
            className="ph-ava-edit"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
            title="更换头像"
            aria-label="上传头像"
          >
            {uploading ? <Loader2 className="ic sm" style={{ animation: 'spin .7s linear infinite' }} /> : <Camera className="ic sm" />}
          </button>
          <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={(event) => handleAvatarFile(event.target.files)} />
        </div>
        <div className="ph-id">
          <div className="ph-name">
            <b>{profile?.nickname || '未设置昵称'}</b>
            <span className={badgeClass(hasProfile ? 'success' : 'warning')}>
              {hasProfile ? <CheckCircle2 className="ic sm" /> : <ShieldAlert className="ic sm" />}
              {hasProfile ? '已实名' : '未实名'}
            </span>
            <span className="badge brand">信用优秀</span>
          </div>
          <p className="ph-phone">
            <Phone className="ic sm" />
            {profile?.phone || '—'} · 阳光社区 3 栋
          </p>
          <div className="ph-complete">
            <div className="phc-bar"><i style={{ width: `${completeness}%` }} /></div>
            <span>资料完整度 {completeness}%</span>
          </div>
        </div>
        <div className="ph-stats">
          <div><b className="num" data-count={rentalTotal}>0</b><span>累计租借</span></div>
          <div><b className="num" data-count={onTimeRate} data-suffix="%">0</b><span>按时归还率</span></div>
          <div><b className="num" data-count={980}>0</b><span>信用分</span></div>
          <div><b className="num" data-count={1240} data-fmt="money">0</b><span>节省开支</span></div>
        </div>
      </div>

      <div className="profile-grid">
        <div className="panel reveal">
          <h3 className="panel-title">基本资料</h3>
          <form onSubmit={handleSubmit((values) => updateMutation.mutate(values))} noValidate>
            <div className={'field' + (errors.nickname ? ' is-err' : '')}>
              <label htmlFor="nickname"><span className="req">*</span>昵称</label>
              <Controller
                control={control}
                name="nickname"
                render={({ field, fieldState }) => (
                  <input id="nickname" className="input" placeholder="请输入昵称" maxLength={20} aria-invalid={fieldState.invalid} {...field} />
                )}
              />
              <p className="err">{errors.nickname?.message}</p>
            </div>

            <div className="form-grid">
              <div className={'field' + (errors.realName ? ' is-err' : '')}>
                <label htmlFor="realName"><span className="req">*</span>真实姓名</label>
                <Controller
                  control={control}
                  name="realName"
                  render={({ field, fieldState }) => (
                    <input id="realName" className="input" placeholder="请输入真实姓名" aria-invalid={fieldState.invalid} {...field} />
                  )}
                />
                <p className="err">{errors.realName?.message}</p>
              </div>

              <div className={'field' + (errors.idCard ? ' is-err' : '')}>
                <label htmlFor="idCard"><span className="req">*</span>身份证号</label>
                <Controller
                  control={control}
                  name="idCard"
                  render={({ field, fieldState }) => (
                    <input id="idCard" className="input" placeholder="留空则不修改" maxLength={18} style={{ fontFamily: 'var(--font-mono)' }} aria-invalid={fieldState.invalid} {...field} />
                  )}
                />
                <p className="err">{errors.idCard?.message}</p>
              </div>
            </div>

            <div className="field">
              <label htmlFor="phone">联系电话</label>
              <input id="phone" className="input" value={profile?.phone ?? ''} readOnly disabled />
              <p className="hint">手机号用于登录与取件验证，如需变更请联系工作人员。</p>
            </div>

            <div className="flex" style={{ justifyContent: 'flex-end' }}>
              <button type="submit" className="btn btn-primary" disabled={updateMutation.isPending}>
                {updateMutation.isPending ? (
                  <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
                ) : (
                  <Save className="ic" />
                )}
                保存资料
              </button>
            </div>
          </form>
        </div>

        <div className="profile-side">
          <div className="panel reveal">
            <h3 className="panel-title">偏好设置</h3>
            {prefs.map((item) => (
              <div className="pref" key={item.label}>
                <div>
                  <b>{item.label}</b>
                  <span>{item.hint}</span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={item.on}
                  aria-label={item.label}
                  className={'switch' + (item.on ? ' on' : '')}
                  onClick={item.toggle}
                >
                  <i></i>
                </button>
              </div>
            ))}
          </div>
          <div className="panel reveal">
            <h3 className="panel-title">最近动态</h3>
            <ul className="act-list">
              {rentals.slice(0, 4).map((rental) => (
                <li key={rental.id}>
                  <span className="act-dot" style={{ background: `var(--${rental.status === 'RETURNED' ? 'ok' : rental.status === 'OVERDUE' ? 'bad' : rental.status === 'PENDING_PAYMENT' ? 'warn' : 'brand'})` }}></span>
                  <div>
                    <b>{rental.toolName}</b>
                    <span className="tiny">{formatDateTime(rental.createTime)} · {rental.statusName}</span>
                  </div>
                </li>
              ))}
              {rentals.length === 0 && (
                <li>
                  <span className="act-dot" style={{ background: 'var(--info)' }}></span>
                  <div>
                    <b>暂无租赁动态</b>
                    <span className="tiny">去工具广场借一件工具吧</span>
                  </div>
                </li>
              )}
            </ul>
          </div>
          <button
            type="button"
            className="btn btn-outline btn-block reveal"
            onClick={() => {
              useAuthStore.getState().clear()
              queryClient.clear()
              navigate('/login')
            }}
          >
            <LogOut className="ic sm" />
            退出登录
          </button>
        </div>
      </div>
    </div>
  )
}
