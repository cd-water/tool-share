import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Camera,
  CheckCircle2,
  Loader2,
  LogOut,
  Phone,
  Save,
  ShieldAlert,
  Star,
  X,
} from 'lucide-react'
import { creditApi } from '@/api/credit'
import { dashboardApi } from '@/api/dashboard'
import { notificationPrefsApi } from '@/api/messages'
import { profileApi } from '@/api/profile'
import { rentalsApi } from '@/api/rentals'
import { reviewApi } from '@/api/review'
import { uploadApi } from '@/api/upload'
import type {
  NotificationPref,
  ProfileUpdatePayload,
  Rental,
  Review,
  ReviewCreatePayload,
} from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { SoundFX } from '@/lib/fx'
import { formatDateTime, resolveFileUrl } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { toast } from '@/components/ui/toast'
import { useAuthStore } from '@/stores/auth'

const RETURNED_PAGE_SIZE = 5
const CREDIT_PAGE_SIZE = 5

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

function StarRating({ rating }: { rating: number }) {
  return (
    <span className="flex" style={{ gap: 2 }} aria-label={`${rating} 星`}>
      {Array.from({ length: 5 }).map((_, index) => (
        <Star
          key={index}
          className="ic sm"
          style={{
            color: index < rating ? 'var(--brand-2)' : 'var(--line-2)',
            fill: index < rating ? 'var(--brand-2)' : 'none',
          }}
        />
      ))}
    </span>
  )
}

function ReviewSummary({ review }: { review: Review }) {
  return (
    <div style={{ marginTop: 8 }}>
      <div className="flex" style={{ gap: 8, alignItems: 'center', marginBottom: 4 }}>
        <StarRating rating={review.rating} />
        <span className="tiny">{formatDateTime(review.createTime)}</span>
      </div>
      {review.content && (
        <p className="tiny" style={{ margin: 0, color: 'var(--ink-2)', lineHeight: 1.6 }}>
          {review.content}
        </p>
      )}
      {review.images.length > 0 && (
        <div className="dmg-imgs" style={{ marginTop: 8 }}>
          {review.images.map((url) => (
            <a key={url} href={resolveFileUrl(url)} target="_blank" rel="noreferrer">
              <img src={resolveFileUrl(url)} alt="评价图片" style={{ width: 64, height: 64 }} />
            </a>
          ))}
        </div>
      )}
    </div>
  )
}

export default function ProfilePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const reviewFileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [returnedPage, setReturnedPage] = useState(1)
  const [creditPage, setCreditPage] = useState(1)
  const [reviewTarget, setReviewTarget] = useState<Rental | null>(null)
  const [reviewRating, setReviewRating] = useState(5)
  const [reviewContent, setReviewContent] = useState('')
  const [reviewImages, setReviewImages] = useState<string[]>([])
  const [reviewUploading, setReviewUploading] = useState(false)

  const profileQuery = useQuery({
    queryKey: queryKeys.profile,
    queryFn: profileApi.get,
  })
  const recentRentalsQuery = useQuery({
    queryKey: queryKeys.rentals.list({ page: 1, size: 4 }),
    queryFn: () => rentalsApi.list({ page: 1, size: 4 }),
  })
  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboard,
    queryFn: dashboardApi.get,
  })
  const returnedQuery = useQuery({
    queryKey: queryKeys.rentals.list({
      page: returnedPage,
      size: RETURNED_PAGE_SIZE,
      status: 'RETURNED',
    }),
    queryFn: () =>
      rentalsApi.list({ page: returnedPage, size: RETURNED_PAGE_SIZE, status: 'RETURNED' }),
  })
  const creditQuery = useQuery({
    queryKey: queryKeys.credit.overview({ page: creditPage, size: CREDIT_PAGE_SIZE }),
    queryFn: () => creditApi.overview({ page: creditPage, size: CREDIT_PAGE_SIZE }),
  })
  const reviewsQuery = useQuery({
    // ponytail: 一次取 100 条（后端上限）用于标记哪些租赁已评价；评价量超过时改为后端返回 reviewed 标记
    queryKey: queryKeys.reviews.list({ page: 1, size: 100 }),
    queryFn: () => reviewApi.list({ page: 1, size: 100 }),
  })
  const prefsQuery = useQuery({
    queryKey: queryKeys.notificationPrefs,
    queryFn: notificationPrefsApi.list,
  })

  const recentRentals = recentRentalsQuery.data?.records ?? []
  const rentalTotal = recentRentalsQuery.data?.total ?? 0
  const returned = returnedQuery.data?.records ?? []
  const returnedTotal = returnedQuery.data?.total ?? 0
  const returnedPages = Math.max(
    1,
    Math.ceil(returnedTotal / (returnedQuery.data?.size || RETURNED_PAGE_SIZE)),
  )
  const reviewsByRental = new Map(
    (reviewsQuery.data?.records ?? []).map((review) => [review.rentalId, review]),
  )
  const creditEvents = creditQuery.data?.events.records ?? []
  const creditTotal = creditQuery.data?.events.total ?? 0
  const creditPages = Math.max(
    1,
    Math.ceil(creditTotal / (creditQuery.data?.events.size || CREDIT_PAGE_SIZE)),
  )
  const creditScore = creditQuery.data?.score
  const creditLabel =
    creditScore === undefined
      ? ''
      : creditScore >= 90
        ? '信用优秀'
        : creditScore >= 70
          ? '信用良好'
          : creditScore >= 50
            ? '信用一般'
            : '信用较低'

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
      queryClient.setQueryData(queryKeys.profile, updated)
      queryClient.invalidateQueries({ queryKey: queryKeys.profile })
      queryClient.invalidateQueries({ queryKey: queryKeys.user.info })
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

  const prefMutation = useMutation({
    mutationFn: (pref: NotificationPref) =>
      notificationPrefsApi.update(pref.type, {
        enabled: !pref.enabled,
        channel: pref.channel,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.notificationPrefs })
      toast.add({ title: '提醒偏好已保存', type: 'success' })
    },
  })

  const reviewMutation = useMutation({
    mutationFn: (payload: ReviewCreatePayload) => reviewApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.reviews.all })
      queryClient.invalidateQueries({ queryKey: queryKeys.rentals.all })
      queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
      queryClient.invalidateQueries({ queryKey: queryKeys.credit.all })
      closeReview()
      toast.add({ title: '评价已提交，感谢反馈', type: 'success' })
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

  function openReview(rental: Rental) {
    setReviewTarget(rental)
    setReviewRating(5)
    setReviewContent('')
    setReviewImages([])
  }

  function closeReview() {
    setReviewTarget(null)
    setReviewRating(5)
    setReviewContent('')
    setReviewImages([])
  }

  async function handleReviewFiles(fileList: FileList | null) {
    if (!fileList || fileList.length === 0) return
    const remaining = 3 - reviewImages.length
    if (remaining <= 0) {
      toast.add({ title: '最多上传 3 张图片', type: 'warning' })
      return
    }
    const selected = Array.from(fileList).slice(0, remaining)
    if (selected.some((file) => !file.type.startsWith('image/'))) {
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    setReviewUploading(true)
    try {
      const results = await Promise.all(selected.map((file) => uploadApi.upload(file)))
      setReviewImages((prev) => [...prev, ...results.map((item) => item.url)])
    } catch {
      // 上传失败提示由请求拦截器统一处理
    } finally {
      setReviewUploading(false)
      if (reviewFileInputRef.current) reviewFileInputRef.current.value = ''
    }
  }

  function submitReview() {
    if (!reviewTarget) return
    reviewMutation.mutate({
      rentalId: reviewTarget.id,
      rating: reviewRating,
      content: reviewContent.trim() || undefined,
      images: reviewImages,
    })
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

  function toggleSound() {
    const next = SoundFX.toggle()
    setSoundOn(next)
    if (next) SoundFX.pop()
  }

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
            {creditLabel && <span className="badge brand">{creditLabel}</span>}
          </div>
          <p className="ph-phone">
            <Phone className="ic sm" />
            {profile?.phone || '—'}
          </p>
          <div className="ph-complete">
            <div className="phc-bar"><i style={{ width: `${completeness}%` }} /></div>
            <span>资料完整度 {completeness}%</span>
          </div>
        </div>
        <div className="ph-stats">
          <div><b className="num" data-count={rentalTotal}>0</b><span>累计租借</span></div>
          <div><b className="num" data-count={dashboardQuery.data?.rentingCount ?? 0}>0</b><span>租借中</span></div>
          <div><b className="num" data-count={dashboardQuery.data?.pendingPaymentCount ?? 0}>0</b><span>待支付</span></div>
          <div><b className="num" data-count={creditScore ?? 0}>0</b><span>信用分</span></div>
        </div>
      </div>

      <div className="profile-grid">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
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

          <div className="panel reveal">
            <h3 className="panel-title">租赁评价</h3>
            {returnedQuery.isLoading ? (
              <div className="skel" style={{ height: 120 }} />
            ) : returned.length === 0 ? (
              <p className="muted">暂无已归还的租赁，归还后可在此评价工具。</p>
            ) : (
              <>
                {returned.map((rental) => {
                  const review = reviewsByRental.get(rental.id)
                  return (
                    <div className="pref" key={rental.id} style={{ alignItems: 'flex-start' }}>
                      <div style={{ minWidth: 0 }}>
                        <b>{rental.toolName}</b>
                        <span className="tiny">
                          {rental.orderNo} · 归还于 {formatDateTime(rental.actualReturnTime || rental.endTime)}
                        </span>
                        {review ? <ReviewSummary review={review} /> : null}
                      </div>
                      {!review && (
                        <button type="button" className="btn btn-sm btn-outline" onClick={() => openReview(rental)}>
                          <Star className="ic sm" />
                          评价
                        </button>
                      )}
                    </div>
                  )
                })}
                {returnedTotal > RETURNED_PAGE_SIZE && (
                  <div className="between" style={{ marginTop: 12 }}>
                    <span className="tiny">共 {returnedTotal} 条</span>
                    <div className="pager">
                      <button type="button" disabled={returnedPage <= 1} onClick={() => setReturnedPage(returnedPage - 1)}>上一页</button>
                      <button type="button" disabled={returnedPage >= returnedPages} onClick={() => setReturnedPage(returnedPage + 1)}>下一页</button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>

        <div className="profile-side">
          <div className="panel reveal">
            <h3 className="panel-title">偏好设置</h3>
            <div className="pref">
              <div>
                <b>操作音效</b>
                <span>按钮与提示的轻量反馈音</span>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={soundOn}
                aria-label="操作音效"
                className={'switch' + (soundOn ? ' on' : '')}
                onClick={toggleSound}
              >
                <i></i>
              </button>
            </div>
            {prefsQuery.isLoading ? (
              <p className="hint">正在加载提醒偏好…</p>
            ) : prefsQuery.data && prefsQuery.data.length > 0 ? (
              prefsQuery.data.map((pref) => (
                <div className="pref" key={pref.type}>
                  <div>
                    <b>{pref.typeName}</b>
                    <span>
                      {pref.channel === 'SMS' ? 'Mock 短信' : '站内消息'} · 关闭后不再接收该类提醒
                    </span>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={pref.enabled}
                    aria-label={pref.typeName}
                    className={'switch' + (pref.enabled ? ' on' : '')}
                    disabled={prefMutation.isPending}
                    onClick={() => prefMutation.mutate(pref)}
                  >
                    <i></i>
                  </button>
                </div>
              ))
            ) : (
              <p className="hint">暂无可配置的提醒类型。</p>
            )}
          </div>

          <div className="panel reveal">
            <h3 className="panel-title">信用分与流水</h3>
            <div className="flex" style={{ alignItems: 'baseline', gap: 10, marginBottom: 12 }}>
              <b style={{ fontSize: 'var(--t-3xl)', color: 'var(--brand-2)' }}>{creditScore ?? '—'}</b>
              <span className="tiny">初始 100 · 按时归还 +2 · 逾期 −3/次 · 严重损坏 −10</span>
            </div>
            {creditEvents.length > 0 ? (
              <ul className="act-list">
                {creditEvents.map((event) => (
                  <li key={event.id}>
                    <span className="act-dot" style={{ background: event.delta >= 0 ? 'var(--ok)' : 'var(--bad)' }}></span>
                    <div>
                      <b>
                        {event.eventLabel || event.eventType}
                        <span style={{ marginLeft: 6, color: event.delta >= 0 ? 'var(--ok)' : 'var(--bad)' }}>
                          {event.delta > 0 ? '+' : ''}{event.delta}
                        </span>
                      </b>
                      <span className="tiny">
                        {formatDateTime(event.createTime)}
                        {event.reason ? ` · ${event.reason}` : ''}
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">暂无信用分变动记录。</p>
            )}
            {creditTotal > CREDIT_PAGE_SIZE && (
              <div className="between" style={{ marginTop: 12 }}>
                <span className="tiny">共 {creditTotal} 条</span>
                <div className="pager">
                  <button type="button" disabled={creditPage <= 1} onClick={() => setCreditPage(creditPage - 1)}>上一页</button>
                  <button type="button" disabled={creditPage >= creditPages} onClick={() => setCreditPage(creditPage + 1)}>下一页</button>
                </div>
              </div>
            )}
          </div>

          <div className="panel reveal">
            <h3 className="panel-title">最近动态</h3>
            <ul className="act-list">
              {recentRentals.map((rental) => (
                <li key={rental.id}>
                  <span className="act-dot" style={{ background: `var(--${rental.status === 'RETURNED' ? 'ok' : rental.status === 'OVERDUE' ? 'bad' : rental.status === 'PENDING_PAYMENT' ? 'warn' : 'brand'})` }}></span>
                  <div>
                    <b>{rental.toolName}</b>
                    <span className="tiny">{formatDateTime(rental.createTime)} · {rental.statusName}</span>
                  </div>
                </li>
              ))}
              {recentRentals.length === 0 && (
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

      {reviewTarget && (
        <div className="mask show" onClick={closeReview}>
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>评价工具</h3>
                <p className="d-sub">{reviewTarget.toolName} · {reviewTarget.orderNo}</p>
              </div>
              <button className="dlg-x" onClick={closeReview} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            <div className="dlg-body">
              <div className="field">
                <label>工具评分</label>
                <div className="flex" style={{ gap: 6 }}>
                  {[1, 2, 3, 4, 5].map((value) => (
                    <button
                      key={value}
                      type="button"
                      aria-label={`${value} 星`}
                      onClick={() => setReviewRating(value)}
                      style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
                    >
                      <Star
                        className="ic"
                        style={{
                          color: value <= reviewRating ? 'var(--brand-2)' : 'var(--line-2)',
                          fill: value <= reviewRating ? 'var(--brand-2)' : 'none',
                        }}
                      />
                    </button>
                  ))}
                  <span className="tiny">{reviewRating} 星</span>
                </div>
              </div>

              <div className="field">
                <label htmlFor="reviewContent">评价内容（选填）</label>
                <textarea
                  id="reviewContent"
                  className="textarea"
                  maxLength={500}
                  value={reviewContent}
                  onChange={(event) => setReviewContent(event.target.value)}
                  placeholder="分享工具的使用体验、完好程度与服务感受…"
                />
              </div>

              <div className="field">
                <label>图片（选填，最多 3 张）</label>
                <div className="up-grid">
                  {reviewImages.map((url) => (
                    <div className="up-item" key={url}>
                      <img src={resolveFileUrl(url)} alt="评价图片" />
                      <button
                        type="button"
                        onClick={() => setReviewImages((prev) => prev.filter((item) => item !== url))}
                        aria-label="移除图片"
                      >
                        <X className="ic sm" />
                      </button>
                    </div>
                  ))}
                  {reviewImages.length < 3 && (
                    <button
                      type="button"
                      className="up-add"
                      disabled={reviewUploading}
                      onClick={() => reviewFileInputRef.current?.click()}
                    >
                      {reviewUploading ? (
                        <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
                      ) : (
                        <Camera className="ic" />
                      )}
                      {reviewUploading ? '上传中' : '上传图片'}
                    </button>
                  )}
                </div>
                <input
                  ref={reviewFileInputRef}
                  type="file"
                  accept="image/*"
                  multiple
                  hidden
                  onChange={(event) => handleReviewFiles(event.target.files)}
                />
              </div>
            </div>
            <div className="dlg-foot">
              <button type="button" className="btn btn-outline" onClick={closeReview}>取消</button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={reviewMutation.isPending || reviewUploading}
                onClick={submitReview}
              >
                {reviewMutation.isPending && <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />}
                提交评价
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
