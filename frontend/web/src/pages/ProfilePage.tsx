/**
 * 文件职责：个人中心页面（ProfilePage），路由 /profile。
 *
 * 页面能力：
 * - 展示头像、昵称、真实姓名/实名状态、手机号与资料完整度；
 * - 维护头像、昵称、真实姓名与身份证号；
 * - 查看累计租借、租借中、待支付与信用分统计；
 * - 查看最近动态与已归还租赁，并对未评价的租赁提交评分/文字/图片；
 * - 配置通知提醒偏好（站内消息 / Mock 短信）；
 * - 切换操作音效、查看信用分流水、退出登录。
 *
 * 数据来源：
 * - profileApi.get / update：个人资料；
 * - rentalsApi.list：最近租赁与已归还分页；
 * - creditApi.overview：信用分与流水；
 * - reviewApi.list / create：评价列表与提交；
 * - notificationPrefsApi.list / update：提醒偏好；
 * - dashboardApi.get：统计；
 * - uploadApi.upload：头像与评价图片。
 *
 * 权限：页面位于 RequireAuth 之下，需要登录。
 * 身份证号只用于实名与取件核验，提交后不在前端回显。
 *
 * 错误/加载/空状态：
 * - 资料首次加载显示整页骨架；
 * - 各面板独立处理加载/空状态；
 * - 请求失败由全局拦截器提示。
 *
 * 交互约定：
 * - 头像上传只更新表单，点击“保存资料”后才写入后端；
 * - 身份证输入框留空表示不修改；
 * - 评价成功后关闭弹窗并刷新评价/租赁/工具/信用查询；
 * - 退出登录会清空登录态与全部 React Query 缓存。
 */
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

// 已归还租赁每页条数：
// 用于“租赁评价”面板的分页。
// 与信用流水分页独立。
// 修改该值会影响分页器与请求参数。
const RETURNED_PAGE_SIZE = 5
// 信用分流水每页条数：
// 用于“信用分与流水”面板的分页。
// 与已归还租赁分页独立。
// 修改该值会影响分页器与请求参数。
const CREDIT_PAGE_SIZE = 5

// 个人资料校验规则：
// - nickname 1–20 字，trim 后不能为空；
// - avatar 为上传后的 URL，可为空；
// - realName 最多 30 字，可为空；
// - idCard 允许为空（表示不修改），非空时必须为 18 位身份证格式。
// 身份证只在填写时提交，避免每次保存都覆盖。
// 使用 refine 而不是正则 min，以允许空字符串。
// 校验错误会展示在对应字段下方。
// 提交前由 zodResolver 统一执行，不额外手写判断。
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

// 资料表单值类型：由 schema 推导。
// avatar 是 URL 字符串，不是文件对象。
// idCard 在提交时若为空则不放入 payload。
// 类型与 useForm/resolver 保持一致。
// 该类型只在本文件内使用。
type ProfileValues = z.infer<typeof profileSchema>

/**
 * 只读星级展示。
 *
 * @param props.rating 评分（0–5）。
 * @returns 五颗星，已评分部分填充品牌色，其余为线条色。
 *
 * 副作用：无。用于评价摘要。
 * aria-label 提供整体评分语义，单颗星对读屏隐藏。
 */
function StarRating({ rating }: { rating: number }) {
  return (
  // 固定渲染 5 颗星：
  // index < rating 的星填充品牌色，其余仅描边。
  // 使用索引作为 key，星星数量固定。
  // 不处理点击，只读展示。
  // 评分不取整，支持小数时按阈值填充。
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

/**
 * 评价摘要（已归还租赁下方）。
 *
 * @param props.review 评价对象。
 * @returns 星级、时间、文字内容与图片缩略图。
 *
 * 副作用：无。图片可点击查看原图。
 * 文字与图片都是可选的，缺失时不渲染对应区块。
 */
function ReviewSummary({ review }: { review: Review }) {
  return (
  // 顶部：星级 + 评价时间。
  // 时间使用 formatDateTime 统一格式。
  // 星级只读，不提供修改。
  // 内容与图片按存在性条件渲染。
  // 图片使用固定缩略尺寸，点击查看原图。
  // 组件不处理评价的编辑/删除。
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

/**
 * 个人中心页面。
 *
 * @returns 资料 Hero、基本资料表单、租赁评价、偏好设置、
 *   信用分流水、最近动态与评价弹窗。
 *
 * 副作用：
 * - 多个 React Query 并行加载资料/租赁/信用/评价/偏好；
 * - 资料、偏好、评价三类 mutation；
 * - 上传头像与评价图片；
 * - 退出登录时清空登录态与查询缓存。
 *
 * 状态说明：
 * - returnedPage/creditPage 控制两个分页面板；
 * - reviewTarget/rating/content/images 控制评价弹窗；
 * - uploading/reviewUploading 控制两处上传按钮。
 *
 * 页面不对资料做本地草稿持久化，刷新后以服务端为准。
 * 所有面板独立处理加载与空状态。
 * 请求失败由全局拦截器提示，不阻塞其他面板。
 */
export default function ProfilePage() {
  // 路由与查询工具：
  // navigate 用于退出登录后跳转；
  // queryClient 用于保存资料后更新缓存。
  // 两者在生命周期内稳定。
  // 不参与数据查询。
  // 退出登录时 queryClient 会被清空。
  const navigate = useNavigate()
  // queryClient 用于：
  // - 资料保存后 setQueryData；
  // - 失效资料/用户信息查询；
  // - 退出登录时清空全部缓存。
  // 不直接 refetch 其他面板。
  // 该引用在组件生命周期内稳定。
  const queryClient = useQueryClient()
  // 头像文件输入引用：
  // 由头像上的相机按钮触发，隐藏原生 input。
  // 上传完成后清空 value，允许重复选择同一文件。
  // 与评价图片输入完全独立。
  // 输入元素本身不展示。
  const fileInputRef = useRef<HTMLInputElement>(null)
  // 评价图片文件输入引用：
  // 由评价弹窗中的“上传图片”按钮触发。
  // 与头像上传完全独立。
  // 上传完成后清空 value。
  // 弹窗关闭后引用仍存在，但不会自动触发。
  const reviewFileInputRef = useRef<HTMLInputElement>(null)
  // 头像上传态：
  // 为 true 时相机按钮显示加载并禁用。
  // 上传成功后只更新表单 avatar，需保存资料才生效。
  // 上传失败时由 finally 复位。
  // 不控制评价图片上传。
  const [uploading, setUploading] = useState(false)
  // 已归还租赁分页页码：
  // 从 1 开始，用于“租赁评价”面板。
  // 与信用流水分页独立。
  // 翻页会触发 returnedQuery 重新请求。
  // 评价成功后该查询可能失效。
  const [returnedPage, setReturnedPage] = useState(1)
  // 信用流水分页页码：
  // 从 1 开始，用于“信用分与流水”面板。
  // 与已归还租赁分页独立。
  // 翻页会触发 creditQuery 重新请求。
  // 评价成功后该查询可能失效。
  const [creditPage, setCreditPage] = useState(1)
  // 评价弹窗目标租赁：
  // 非空时打开评价弹窗。
  // 保存整个租赁对象以展示工具名/单号。
  // 关闭弹窗时置空。
  // 与分页状态无关。
  const [reviewTarget, setReviewTarget] = useState<Rental | null>(null)
  // 评价星级：
  // 1–5，默认 5。点击星星修改。
  // 提交时作为 rating 传给后端。
  // 不持久化，关闭弹窗后重置。
  // 不校验，UI 只提供 1–5。
  const [reviewRating, setReviewRating] = useState(5)
  // 评价文字：
  // 选填，最多 500 字。
  // 提交时 trim，空值传 undefined。
  // 关闭弹窗后清空。
  // 不自动保存草稿。
  const [reviewContent, setReviewContent] = useState('')
  // 评价图片 URL 数组：
  // 最多 3 张，由 uploadApi 上传后写入。
  // 提交时随评价一起传给后端。
  // 关闭弹窗后清空。
  // 移除只影响本地数组，不删除对象存储文件。
  const [reviewImages, setReviewImages] = useState<string[]>([])
  // 评价图片上传态：
  // 为 true 时上传按钮显示加载并禁用。
  // 与头像上传态独立。
  // 提交按钮在该状态为 true 时禁用。
  // 上传失败时由 finally 复位。
  const [reviewUploading, setReviewUploading] = useState(false)

  // 个人资料查询：
  // 页面主数据源，包含昵称/头像/实名/手机号。
  // 首次加载时整页显示骨架。
  // 保存资料后会更新缓存并失效该查询。
  // 失败时页面仍可渲染骨架/空状态。
  // 该查询不轮询。
  // 与用户信息查询共享同一份后端数据。
  const profileQuery = useQuery({
    queryKey: queryKeys.profile,
    queryFn: profileApi.get,
  })
  // 最近租赁查询：
  // 取 4 条用于“最近动态”面板。
  // 与已归还分页查询独立。
  // 失败时面板显示空状态。
  // 不轮询，进入页面时请求一次。
  // total 用于“累计租借”统计。
  // 不参与评价面板分页。
  const recentRentalsQuery = useQuery({
    queryKey: queryKeys.rentals.list({ page: 1, size: 4 }),
    queryFn: () => rentalsApi.list({ page: 1, size: 4 }),
  })
  // 仪表盘查询：
  // 提供租借中/待支付数量。
  // 与布局层共享缓存。
  // 失败时统计回退 0。
  // 不阻塞资料表单。
  // 该查询不轮询。
  // 与租赁查询独立。
  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboard,
    queryFn: dashboardApi.get,
  })
  // 已归还租赁查询：
  // 按 RETURNED 状态分页，用于评价面板。
  // 页码变化会重新请求。
  // 失败时 panel 显示空/错误提示。
  // 评价成功后会失效该查询。
  // size 固定 RETURNED_PAGE_SIZE。
  // total 用于分页器。
  // 只返回当前用户的租赁。
  const returnedQuery = useQuery({
    queryKey: queryKeys.rentals.list({
      page: returnedPage,
      size: RETURNED_PAGE_SIZE,
      status: 'RETURNED',
    }),
    queryFn: () =>
      rentalsApi.list({ page: returnedPage, size: RETURNED_PAGE_SIZE, status: 'RETURNED' }),
  })
  // 信用分概览查询：
  // 返回当前分数与流水分页。
  // 页码变化会重新请求。
  // 失败时分数显示“—”、流水为空。
  // 评价成功后会失效该查询（评价可能影响信用）。
  // size 固定 CREDIT_PAGE_SIZE。
  // 与已归还分页独立。
  // 只返回当前用户的记录。
  const creditQuery = useQuery({
    queryKey: queryKeys.credit.overview({ page: creditPage, size: CREDIT_PAGE_SIZE }),
    queryFn: () => creditApi.overview({ page: creditPage, size: CREDIT_PAGE_SIZE }),
  })
  // 评价列表查询：
  // 一次取 100 条（后端上限），用于标记哪些租赁已评价。
  // 本地建立 rentalId → review 映射。
  // 评价量超过 100 时，部分已评价租赁可能被误判为未评价。
  // 这是当前后端接口限制下的折中。
  // 评价成功后会失效该查询。
  // 该查询不参与分页展示。
  // 只用于“评价/已评价”按钮切换。
  // 失败时视为全部未评价，仍可提交评价。
  // 不轮询。
  // 映射在每次渲染时重建。
  const reviewsQuery = useQuery({
    // ponytail: 一次取 100 条（后端上限）用于标记哪些租赁已评价；评价量超过时改为后端返回 reviewed 标记
    queryKey: queryKeys.reviews.list({ page: 1, size: 100 }),
    queryFn: () => reviewApi.list({ page: 1, size: 100 }),
  })
  // 通知偏好查询：
  // 返回可配置的提醒类型与开关状态。
  // 切换开关会调用 update mutation。
  // 失败时面板显示空/提示。
  // 保存成功后失效该查询。
  // 与资料查询独立。
  // 不轮询。
  const prefsQuery = useQuery({
    queryKey: queryKeys.notificationPrefs,
    queryFn: notificationPrefsApi.list,
  })

  // 最近租赁记录：
  // 未返回时为空数组。
  // rentalTotal 用于“累计租借”统计。
  // 该数组只用于最近动态面板。
  // 最近租赁安全视图：未返回时为空数组。
  // 用于“最近动态”与累计租借统计。
  // 只包含当前用户的数据。
  const recentRentals = recentRentalsQuery.data?.records ?? []
  // 累计租借总数：
  // 来自最近租赁查询的 total。
  // 不是已归还数量，而是全部租赁单。
  // 用于 Hero 统计。
  const rentalTotal = recentRentalsQuery.data?.total ?? 0
  // 已归还租赁当前页记录：
  // 用于评价面板。
  // 未返回时为空数组。
  // 每条记录可能已有评价，通过映射判断。
  // 已归还当前页：用于评价面板。
  // 未返回时为空数组。
  // 每条可能已有评价。
  const returned = returnedQuery.data?.records ?? []
  // 已归还总数：
  // 用于分页器与“共 N 条”。
  // 未返回时回退 0。
  const returnedTotal = returnedQuery.data?.total ?? 0
  // 已归还总页数：
  // 至少 1。
  // size 缺失时回退 RETURNED_PAGE_SIZE。
  // 与 returnedPage 一起控制分页按钮。
  const returnedPages = Math.max(
    1,
    Math.ceil(returnedTotal / (returnedQuery.data?.size || RETURNED_PAGE_SIZE)),
  )
  // 评价映射：
  // rentalId → review，用于判断某租赁是否已评价。
  // 只包含最近 100 条评价。
  // 未命中的租赁视为未评价。
  // 每次渲染重建，成本很低。
  // 不缓存，避免评价成功后映射过期。
  // 评价映射：
  // 只包含最近 100 条评价。
  // 未命中的租赁视为未评价。
  // 评价成功后查询失效，映射会更新。
  const reviewsByRental = new Map(
    (reviewsQuery.data?.records ?? []).map((review) => [review.rentalId, review]),
  )
  // 信用流水当前页：
  // 来自 creditQuery.data.events。
  // 未返回时为空数组。
  // 每条包含事件类型、增减值与时间。
  // 信用流水当前页：
  // 每条包含事件、增减值、时间与原因。
  // 未返回时为空数组。
  const creditEvents = creditQuery.data?.events.records ?? []
  // 信用流水总数：
  // 用于分页器与“共 N 条”。
  // 未返回时回退 0。
  const creditTotal = creditQuery.data?.events.total ?? 0
  // 信用流水总页数：
  // 至少 1。
  // size 缺失时回退 CREDIT_PAGE_SIZE。
  // 与 creditPage 一起控制分页按钮。
  const creditPages = Math.max(
    1,
    Math.ceil(creditTotal / (creditQuery.data?.events.size || CREDIT_PAGE_SIZE)),
  )
  // 当前信用分：
  // 未加载时为 undefined，展示“—”。
  // 由信用概览接口返回。
  // 参与 Hero 统计与等级计算。
  const creditScore = creditQuery.data?.score
  // 信用等级文案：
  // >=90 优秀、>=70 良好、>=50 一般、其余较低。
  // 分数未加载时为空字符串，不展示徽标。
  // 阈值是产品定义，不是后端计算。
  // 文案只用于展示。
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

  // 资料表单初始化：
  // resolver 使用 profileSchema；
  // 所有字段初始为空，等 profile 查询返回后 reset。
  // idCard 始终从空开始，避免回显敏感信息。
  // 不使用 mode: onChange。
  // formState.errors 用于字段级提示。
  // reset 在 profile 变化时调用。
  // 表单提交由 updateMutation 处理。
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

  // 当前资料：
  // 未加载时为 undefined。
  // 用于 Hero、手机号与完整性计算。
  // 保存成功后会被更新。
  // 不与表单值直接共享，避免未保存修改污染展示。
  const profile = profileQuery.data
  // 表单中的头像值：
  // 上传后立即更新，保存后才写入后端。
  // 用于 Hero 预览。
  // 为空时 Hero 显示昵称首字。
  const avatarValue = watch('avatar')
  // 表单中的昵称值：
  // 用于 Hero 预览与资料完整度。
  // 与 profile.nickname 可能暂时不同。
  // 输入时实时更新。
  const nicknameValue = watch('nickname')
  // 是否展示头像图片：
  // 有值且不是 GitHub 默认头像时展示。
  // GitHub 头像通常不适合作为社区身份展示，因此回退首字。
  // 该判断只影响 Hero 展示。
  const showAvatar = Boolean(avatarValue) && !/github\.com/i.test(avatarValue)

  // profile 加载/更新后同步表单：
  // idCard 始终置空，避免回显。
  // 其他字段使用后端值。
  // 依赖 profile 与 reset；reset 引用稳定。
  // 该副作用只在 profile 变化时执行。
  // 用户正在编辑时若 profile 刷新，会覆盖未保存内容。
  // 当前页面不会主动刷新 profile，因此影响较小。
  // 不修改查询缓存。
  // 不触发额外请求。
  useEffect(() => {
    if (!profile) return
    // reset 会用最新资料覆盖表单：
    // - 昵称/头像/真实姓名取后端值；
    // - idCard 始终为空，避免回显敏感信息；
    // - 该操作会丢弃用户未保存的修改；
    // - 只在 profile 变化时触发；
    // - reset 是 react-hook-form 的稳定引用。
    reset({
      nickname: profile.nickname ?? '',
      avatar: profile.avatar ?? '',
      realName: profile.realName ?? '',
      idCard: '',
    })
  }, [profile, reset])

  // 资料保存 mutation：
  // 组装 payload 时 trim 文本字段；
  // idCard 仅在非空时提交，避免覆盖已有实名信息。
  // 成功后更新 profile 缓存、失效相关查询、同步 auth store 与表单。
  // 失败时保留表单，用户可修改后重试。
  // 提交期间保存按钮禁用。
  // 成功 toast 由本 mutation 负责。
  // 不在此处跳转页面。
  // 返回的 updated 用于同步缓存与 auth store。
  // 资料保存 mutation：
  // 负责把表单值转换为后端 payload。
  // 成功后同步缓存、auth store 与表单。
  // 失败时保留用户输入。
  // 提交期间保存按钮禁用。
  // 不刷新整个页面。
  // 不重新请求 profile（直接用返回值更新缓存）。
  // 仍会失效 user.info，保证导航头像/昵称同步。
  // 成功 toast 只提示一次。
  // 不在此处跳转或关闭页面。
  // updateMutation 是资料保存的唯一入口：
  // 表单值在 mutationFn 中转换为 payload。
  // 成功后同步缓存与 auth store。
  // 失败时表单保持可编辑。
  const updateMutation = useMutation({
    // mutationFn 组装 payload：
    // 文本字段 trim；
    // avatar 直接使用上传后的 URL；
    // idCard 仅在非空时放入 payload。
    // 不发送未修改的身份证空字符串。
    // 返回 Profile 对象。
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
    // onSuccess 收尾：
    // 1) 用返回值直接更新 profile 缓存；
    // 2) 失效 profile 与用户信息查询；
    // 3) 同步 auth store 的昵称/头像；
    // 4) 重置表单为最新值；
    // 5) toast 提示保存成功。
    // 顺序保证 UI 立即反映新资料。
    // 不等待失效查询重新请求。
    onSuccess: (updated) => {
      // 直接用后端返回值写入缓存，
      // 避免保存后短暂显示旧资料。
      queryClient.setQueryData(queryKeys.profile, updated)
      // 失效 profile 查询，
      // 让其他组件/页面获取最新资料。
      queryClient.invalidateQueries({ queryKey: queryKeys.profile })
      // 失效用户信息查询，
      // 同步导航栏/用户菜单的昵称头像。
      queryClient.invalidateQueries({ queryKey: queryKeys.user.info })
      // 同步 auth store：
      // 只更新昵称/头像，不覆盖 token。
      // 用于导航栏等非查询组件。
      useAuthStore.getState().setProfile({
        nickname: updated.nickname,
        avatar: updated.avatar,
      })
      // 重置表单：
      // 使用后端返回值，idCard 置空。
      // 与更新缓存保持一致。
      reset({
        nickname: updated.nickname ?? '',
        avatar: updated.avatar ?? '',
        realName: updated.realName ?? '',
        idCard: '',
      })
      toast.add({ title: '个人资料已保存', type: 'success' })
    },
  })

  // 提醒偏好切换 mutation：
  // 入参为当前偏好对象，提交时反转 enabled。
  // 成功后失效偏好查询并 toast。
  // 失败由全局拦截器提示。
  // 请求期间开关禁用。
  // 不切换本地状态，等待查询刷新。
  // 与资料保存独立。
  // 渠道（站内/短信）随当前偏好一起提交。
  // 提醒偏好切换 mutation：
  // 入参是当前偏好对象。
  // 提交时反转 enabled，渠道保持不变。
  // 成功后失效偏好查询。
  // 失败时开关保持原状态。
  // 请求期间所有开关禁用。
  // 不本地乐观更新。
  // 成功 toast 提示。
  // prefMutation 负责提醒开关：
  // 每次点击提交一次 update。
  // 成功后失效偏好查询。
  // 失败时开关状态以服务端为准。
  const prefMutation = useMutation({
    // mutationFn 调用 update：
    // type 决定更新哪一类提醒；
    // enabled 取反；
    // channel 原样传递。
    mutationFn: (pref: NotificationPref) =>
      notificationPrefsApi.update(pref.type, {
        enabled: !pref.enabled,
        channel: pref.channel,
      }),
    // onSuccess：
    // 失效偏好查询，触发重新请求；
    // toast 提示保存成功。
    // 失败不进入此回调。
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.notificationPrefs })
      toast.add({ title: '提醒偏好已保存', type: 'success' })
    },
  })

  // 评价提交 mutation：
  // 入参为 ReviewCreatePayload。
  // 成功后失效评价/租赁/工具/信用查询，关闭弹窗并 toast。
  // 失败时保留弹窗与输入，用户可重试。
  // 提交期间按钮禁用。
  // 不在此处重新计算评分。
  // 评价会影响工具评分与信用分。
  // 评价内容与图片都是可选的。
  // 评价提交 mutation：
  // 入参是 ReviewCreatePayload。
  // 成功后失效评价/租赁/工具/信用查询。
  // 然后关闭弹窗并 toast。
  // 失败时保留弹窗与输入。
  // 提交期间按钮禁用。
  // 不重新计算工具评分。
  // 信用分可能因评价变化。
  // 评价成功后该租赁不再显示“评价”按钮。
  // reviewMutation 负责评价提交：
  // 成功后刷新四类相关查询。
  // 失败时保留弹窗与输入。
  // 评价内容与图片均可选。
  const reviewMutation = useMutation({
    // mutationFn 直接调用 reviewApi.create。
    // payload 已在 submitReview 中整理。
    // 返回创建的评价对象。
    // 不在此处校验内容长度。
    mutationFn: (payload: ReviewCreatePayload) => reviewApi.create(payload),
    // onSuccess 收尾：
    // 失效四类查询后关闭弹窗并提示。
    // 顺序无所谓，但关闭弹窗放在后面，
    // 让用户先看到提交成功的反馈。
    // 不清空 reviewTarget 之外的本地状态。
    onSuccess: () => {
      // 失效评价列表，更新“已评价”状态。
      queryClient.invalidateQueries({ queryKey: queryKeys.reviews.all })
      // 失效租赁列表，租赁可能带有评价标记。
      queryClient.invalidateQueries({ queryKey: queryKeys.rentals.all })
      // 失效工具查询，工具评分可能变化。
      queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
      // 失效信用查询，评价可能影响信用分。
      queryClient.invalidateQueries({ queryKey: queryKeys.credit.all })
      closeReview()
      toast.add({ title: '评价已提交，感谢反馈', type: 'success' })
    },
  })

  /**
   * 处理头像文件选择与上传。
   *
   * @param files 原生 file input 的文件列表。
   *
   * 流程：取第一个文件 → 校验图片类型 → 上传 → 写入表单 avatar。
   * 上传成功只更新表单，需点击“保存资料”才生效。
   * 失败不在此处 toast，交给全局拦截器。
   * 无论成功失败都复位上传态与 input value。
   */
  // handleAvatarFile 处理头像上传：
  // 只接受第一个文件。
  // 校验图片类型后上传。
  // 成功后写入表单，不自动保存。
  // 失败由全局拦截器提示。
  // 无论成功失败都复位上传态与 input。
  // 该函数是 async，但调用处不 await。
  // 不压缩图片。
  // 不上传多文件。
  // 不在此处校验文件大小。
  // 与评价图片上传逻辑独立。
  // handleAvatarFile 只处理单文件：
  // 先校验类型，再上传，最后写入表单。
  // 不自动保存资料。
  // 失败由全局拦截器提示。
  async function handleAvatarFile(files: FileList | null) {
    // 取第一个文件：
    // 用户一次只选一张头像。
    // 未选择时为 undefined。
    const file = files?.[0]
    // 空选择直接返回，不提示。
    // 输入被清空时不会触发上传。
    if (!file) return
    // 类型校验：必须是图片。
    // accept 属性只是浏览器提示。
    // 非图片时 toast 并返回。
    if (!file.type.startsWith('image/')) {
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    // 进入上传态：
    // 相机按钮显示加载并禁用。
    // finally 中复位。
    setUploading(true)
    try {
    // 上传文件：
    // uploadApi 返回对象存储 URL。
    // 失败进入 catch。
      const result = await uploadApi.upload(file)
    // 写入表单 avatar：
    // shouldDirty 标记表单已修改。
    // 此时还未保存到后端。
    // 用户需点击“保存资料”。
      setValue('avatar', result.url, { shouldDirty: true })
      toast.add({ title: '头像上传成功，保存后生效', type: 'success' })
    // 上传失败：
    // 不写入表单，保持原头像。
    // 全局拦截器已提示错误。
    } catch {
      // 上传失败提示由请求拦截器统一处理
    // 无论成功失败都复位：
    // 关闭上传态；
    // 清空 input value，允许重选同一文件。
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  /**
   * 打开评价弹窗。
   *
   * @param rental 要评价的已归还租赁。
   *
   * 副作用：设置目标租赁，并把星级/文字/图片重置为默认值。
   * 默认 5 星，内容和图片为空。
   * 不校验是否已评价，调用方只在未评价时展示按钮。
   * 不发起请求。
   */
  // openReview 打开评价弹窗：
  // 设置目标租赁；
  // 重置星级为 5；
  // 清空文字与图片；
  // 不校验是否已评价（调用方保证）。
  // 不发起请求。
  // 不滚动页面。
  // 弹窗状态只存在内存。
  // 关闭后这些输入会被清空。
  // openReview 重置评价输入：
  // 默认 5 星、空文字、空图片。
  // 不请求后端。
  // 不校验是否已评价。
  function openReview(rental: Rental) {
    // 保存目标租赁，
    // 用于弹窗头部展示工具名/单号。
    setReviewTarget(rental)
    // 默认 5 星。
    // 用户可以下调。
    setReviewRating(5)
    // 清空上次评价文字。
    // 避免复用旧内容。
    setReviewContent('')
    // 清空上次评价图片。
    // 这些 URL 不会被删除。
    setReviewImages([])
  }

  /**
   * 关闭评价弹窗并重置输入。
   *
   * 副作用：清空目标租赁、星级、文字与图片。
   * 上传中的请求不会被取消，但结果不会再写入（组件仍挂载）。
   * 不清空评价列表缓存。
   * 不刷新页面数据。
   */
  // closeReview 关闭评价弹窗：
  // 清空目标租赁；
  // 重置星级/文字/图片；
  // 不取消进行中的上传（结果不会写入，因为弹窗已关）。
  // 不刷新评价列表。
  // 不重置分页。
  // 可安全重复调用。
  // 只修改本地状态。
  // 不发起请求。
  // closeReview 清空评价输入：
  // 关闭弹窗并重置星级/文字/图片。
  // 不取消上传请求。
  // 不影响评价列表缓存。
  function closeReview() {
    // 关闭弹窗。
    setReviewTarget(null)
    // 重置星级，避免下次打开沿用旧值。
    setReviewRating(5)
    // 清空文字。
    setReviewContent('')
    // 清空图片数组。
    setReviewImages([])
  }

  /**
   * 处理评价图片选择与上传。
   *
   * @param fileList 原生 file input 的文件列表。
   *
   * 规则与报修图片一致：最多 3 张、必须为图片。
   * 并发上传，成功后追加到 reviewImages。
   * 失败不写入任何 URL，保持全有或全无。
   * 无论成功失败都复位上传态与 input value。
   */
  // handleReviewFiles 处理评价图片上传：
  // 最多 3 张、必须为图片。
  // 并发上传，成功后追加到 reviewImages。
  // 失败不写入任何 URL。
  // 无论成功失败都复位上传态与 input。
  // 与头像上传独立。
  // 不压缩图片。
  // 不做拖拽上传。
  // 超出的文件直接丢弃并提示。
  // 上传期间提交按钮禁用。
  // 该函数是 async，但调用处不 await。
  // handleReviewFiles 处理评价图片：
  // 最多 3 张、必须为图片。
  // 并发上传后追加到 reviewImages。
  // 失败不写入任何 URL。
  async function handleReviewFiles(fileList: FileList | null) {
    // 空选择直接返回。
    // 用户取消对话框或未选文件。
    if (!fileList || fileList.length === 0) return
    // 计算剩余名额：
    // 3 - 已上传数量。
    // 与 UI 上限一致。
    const remaining = 3 - reviewImages.length
    // 名额用尽时提示并返回。
    // 不允许继续上传。
    if (remaining <= 0) {
      toast.add({ title: '最多上传 3 张图片', type: 'warning' })
      return
    }
    // 截断选择列表：
    // 只取剩余名额内的文件。
    // 避免一次上传过多。
    const selected = Array.from(fileList).slice(0, remaining)
    // 类型校验：
    // 只要有一个非图片就整体拒绝。
    // 保持全有或全无。
    if (selected.some((file) => !file.type.startsWith('image/'))) {
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    // 进入上传态：
    // 上传按钮显示加载并禁用。
    // 提交按钮同样禁用。
    setReviewUploading(true)
    // 并发上传所有文件：
    // 保持选择顺序。
    // 任一失败进入 catch。
    try {
      const results = await Promise.all(selected.map((file) => uploadApi.upload(file)))
    // 追加到已有图片数组：
    // 使用函数式更新避免闭包过期。
    // 上传结果只包含 URL。
      setReviewImages((prev) => [...prev, ...results.map((item) => item.url)])
    // 上传失败：
    // 不写入任何 URL。
    // 全局拦截器已提示。
    } catch {
      // 上传失败提示由请求拦截器统一处理
    // 无论成功失败都复位：
    // 关闭上传态；
    // 清空 input value。
    } finally {
      setReviewUploading(false)
      if (reviewFileInputRef.current) reviewFileInputRef.current.value = ''
    }
  }

  /**
   * 提交当前评价。
   *
   * 前置条件：reviewTarget 非空。
   * 副作用：调用 reviewMutation，提交 rating/content/images。
   * 空文字转为 undefined，避免提交空字符串。
   * 不校验图片数量，UI 已限制。
   * 成功后由 mutation 关闭弹窗。
  // 该比例只用于展示，
  // 不作为实名或权益判断依据。
  // 完整度为 100% 表示四项都已填写，
  // 但不代表实名审核已通过；
  // 实名审核状态仍以 profile 字段与后端为准。
   * 不等待上传完成，按钮已在上传中被禁用。
   */
  // submitReview 提交当前评价：
  // 前置条件 reviewTarget 非空。
  // 整理 payload：rating、content、images、rentalId。
  // 空文字转 undefined。
  // 调用 reviewMutation。
  // 不等待上传完成（按钮已在上传中禁用）。
  // 不校验图片数量（UI 已限制）。
  // 成功/失败由 mutation 处理。
  // 不在此处关闭弹窗（onSuccess 负责）。
  // submitReview 整理 payload 并提交：
  // 空文字转 undefined。
  // 图片直接使用当前数组。
  // 成功/失败由 mutation 处理。
  function submitReview() {
    // 没有目标租赁时直接返回，
    // 防止提交到错误的租赁。
    if (!reviewTarget) return
    // 调用 mutation：
    // rentalId 来自目标租赁；
    // rating 来自星级状态；
    // content trim 后为空则传 undefined；
    // images 直接传当前数组。
    reviewMutation.mutate({
      rentalId: reviewTarget.id,
      rating: reviewRating,
      content: reviewContent.trim() || undefined,
      images: reviewImages,
    })
  }

  // 是否已实名：
  // realName 与 idCard 都存在时视为已实名。
  // 只用于展示徽标，不参与权限判断。
  // 后端仍会校验实际实名信息。
  // 实名后可享受免押金等权益（由后端规则决定）。
  // 是否已实名：
  // realName 与 idCard 都存在时为 true。
  // 仅用于展示徽标。
  const hasProfile = Boolean(profile?.realName && profile?.idCard)

  // 资料完整度：
  // 统计昵称/头像/真实姓名/身份证四项中已填写的比例。
  // 身份证来自 profile（不是表单），避免用户刚输入未保存就被计入。
  // 结果四舍五入为整数百分比。
  // 仅用于展示进度条。
  // 不参与后端校验。
  // 资料完整度：
  // 统计四项资料中已填写的比例。
  // 身份证来自 profile，不包含未保存的表单值。
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

  // 操作音效开关：
  // 初始值读取 SoundFX.isOn()，保持与全局状态一致。
  // 切换后更新本地状态并播放反馈音。
  // 该状态不持久化到后端。
  // 与提醒偏好无关。
  // 操作音效状态：
  // 初始值来自 SoundFX.isOn()。
  // 与全局音效开关保持一致。
  // 切换后更新本地状态。
  // 不持久化到后端。
  // 音效开关状态：
  // 初始值来自全局 SoundFX。
  // 切换后更新本地状态。
  // 不持久化到后端。
  const [soundOn, setSoundOn] = useState<boolean>(() => SoundFX.isOn())

  // 切换音效：
  // SoundFX.toggle 返回新状态；
  // 开启时播放 pop 音作为即时反馈。
  // 关闭时不播放声音。
  // 只影响本地音效，不影响后端。
  // 切换音效：
  // SoundFX.toggle 返回新状态。
  // 更新本地状态以刷新开关样式。
  // 开启时播放 pop 音作为反馈。
  // 关闭时不播放。
  // 不影响通知偏好。
  // 不发起请求。
  // 切换音效：
  // 更新全局状态与本地状态。
  // 开启时播放反馈音。
  // 关闭时不播放。
  function toggleSound() {
    // 切换全局音效并获取新状态。
    const next = SoundFX.toggle()
    // 同步本地状态，驱动开关 UI。
    setSoundOn(next)
    if (next) SoundFX.pop()
    // 仅在开启时播放反馈音，
    // 让用户确认音效已生效。
  }

  // 资料首次加载：
  // 整页展示骨架（Hero + 两个面板）。
  // 不使用局部骨架，避免布局跳动。
  // 加载失败时 profile 为 undefined，会进入正常渲染但字段为空。
  // 该分支只在 isLoading 时出现。
  // 骨架不阻塞其他查询（它们并行加载）。
  if (profileQuery.isLoading) {
    return (
      <div>
        {/*
         * 加载态页面头部：
         * 只展示标题与说明，不展示操作按钮。
         * 说明文案与正常态略有不同，避免暗示资料已加载。
         * 头部结构简单，不参与骨架。
         * 该区块仅在 profileQuery.isLoading 时出现。
         * 不包含用户数据。
         * 不包含导航或权限操作。
         * 与正常态共用 page-head 样式。
         */}
        <div className="page-head">
          <div>
            <h1>个人中心</h1>
            <p className="sub">维护昵称、头像、真实姓名与身份证信息。</p>
          </div>
        </div>
        <div className="card profile-loading">
          <div className="skel" style={{ width: 88, height: 88, borderRadius: '50%' }} />
        </div>
        {/*
         * 加载态骨架：
         * 一个圆形头像骨架 + 两个面板骨架。
         * 结构与正常态 Hero/左右列大致对应。
         * 不渲染任何真实字段，避免误以为资料已加载。
         * 骨架高度固定，减少加载完成时的布局跳动。
         * 不使用动画以外的交互。
         * 该区块随加载完成整体替换。
         * 不阻塞其他查询。
         */}
        <div className="profile-grid">
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

  // 页面主体：
  // Hero（头像/身份/统计）→ 左右两列：
  // 左列：基本资料、租赁评价；
  // 右列：偏好设置、信用分流水、最近动态、退出登录。
  // 评价弹窗放在最后。
  // 所有面板独立处理加载/空状态。
  // 页面不包含全局 Provider。
  return (
    <div>
      {/*
       * 页面头部：
       * 标题“个人中心”与说明。
       * 说明强调完善实名可提升信用额度。
       * 头部没有操作按钮，所有操作在下方面板。
       * 头部不参与滚动（由样式层控制）。
       * 该区块在所有加载状态下结构一致。
       * 不包含用户数据。
       * 不包含导航。
       */}
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">MY ACCOUNT</p>
          <h1>个人中心</h1>
          <p className="sub">完善实名信息可提升信用额度，享受免押金等社区权益。</p>
        </div>
      </div>

      {/*
       * 资料 Hero：
       * 左侧头像与上传入口；
       * 中间身份/实名/信用/手机号/完整度；
       * 右侧四项统计。
       * 上传头像只更新表单，保存后才生效。
       * Hero 数据混合了 profile、dashboard 与 credit。
       * 任一查询缺失时对应字段回退占位。
       * 不在此处发起请求。
       */}
      <div className="profile-hero card reveal">
        {/*
         * 头像区域：
         * 有可用头像时显示图片，否则显示昵称首字。
         * 相机按钮触发隐藏的 file input。
         * 上传中显示加载图标并禁用按钮。
         * 首字取昵称第一个字符，昵称空时用“用”。
         * 图片与占位共用 ph-ava 尺寸。
         * 头像是可选的，不强制上传。
         * 上传成功后仍需保存资料。
         */}
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
        {/*
         * 身份信息区：
         * 昵称、实名徽标、信用等级、手机号与完整度。
         * 实名徽标区分“已实名/未实名”。
         * 信用等级由分数阈值计算，分数未加载时不展示。
         * 手机号只读，用于登录与取件。
         * 完整度基于四项资料计算。
         * 该区块不使用表单值之外的本地状态（昵称/头像预览除外）。
         */}
        <div className="ph-id">
          {/*
           * 昵称行：
           * 昵称为空时显示“未设置昵称”；
           * 实名徽标使用 success/warning 色调；
           * 信用等级徽标可选。
           * 三个元素在同一行，窄屏由样式层换行。
           */}
          <div className="ph-name">
            <b>{profile?.nickname || '未设置昵称'}</b>
            <span className={badgeClass(hasProfile ? 'success' : 'warning')}>
              {hasProfile ? <CheckCircle2 className="ic sm" /> : <ShieldAlert className="ic sm" />}
              {hasProfile ? '已实名' : '未实名'}
            </span>
            {creditLabel && <span className="badge brand">{creditLabel}</span>}
          </div>
          {/*
           * 手机号：
           * 只读展示，缺失时显示“—”。
           * 手机号用于登录与取件验证。
           * 变更需联系工作人员。
           * 不在本页提供编辑入口。
           */}
          <p className="ph-phone">
            <Phone className="ic sm" />
            {profile?.phone || '—'}
          </p>
          {/*
           * 资料完整度：
           * 进度条宽度绑定 completeness；
           * 文案展示百分比；
           * 仅作引导，不影响功能可用性；
           * 身份证未填写时不会计入（来自 profile）。
           * 完整度在资料保存后重新计算。
           */}
          <div className="ph-complete">
            <div className="phc-bar"><i style={{ width: `${completeness}%` }} /></div>
            <span>资料完整度 {completeness}%</span>
          </div>
        </div>
        {/*
         * 统计区：
         * 累计租借、租借中、待支付、信用分。
         * 前三项来自租赁/仪表盘查询，信用分来自信用概览。
         * 数据缺失时显示 0 或“—”。
         * data-count 供样式层做数字动画。
         * 统计只读，不提供跳转。
         */}
        <div className="ph-stats">
          <div><b className="num" data-count={rentalTotal}>0</b><span>累计租借</span></div>
          <div><b className="num" data-count={dashboardQuery.data?.rentingCount ?? 0}>0</b><span>租借中</span></div>
          <div><b className="num" data-count={dashboardQuery.data?.pendingPaymentCount ?? 0}>0</b><span>待支付</span></div>
          <div><b className="num" data-count={creditScore ?? 0}>0</b><span>信用分</span></div>
        </div>
      </div>

      {/*
       * 左右两列布局：
       * 左列偏账户与评价，右列偏设置与流水。
       * 窄屏下由样式层改为单列。
       * 两个 profile-side 各自包含多个 panel。
       * 面板之间独立处理加载/空状态。
       */}
      <div className="profile-grid">
        <div className="profile-side">
          {/*
           * 基本资料面板：
           * 昵称、真实姓名、身份证号、手机号与保存按钮。
           * 手机号只读；身份证留空表示不修改。
           * 表单校验由 profileSchema 统一完成。
           * 保存成功后表单会被 reset 为最新资料。
           * 保存失败时保留用户输入。
           */}
          <div className="panel reveal">
            <h3 className="panel-title">基本资料</h3>
            {/*
             * 资料表单：
             * 提交时调用 updateMutation。
             * 校验失败不请求。
             * noValidate 关闭浏览器原生提示。
             * 所有字段通过 Controller 接入。
             * 保存按钮在请求中禁用。
             */}
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

              {/*
               * 真实姓名与身份证并排：
               * 真实姓名用于实名，身份证用于取件核验。
               * 身份证输入框使用等宽字体，便于核对。
               * 两个字段都允许为空，但实名徽标需要两者都有。
               * 错误信息展示在各自字段下方。
               */}
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

              {/*
               * 手机号字段：
               * 只读禁用，变更需联系工作人员。
               * 不参与表单校验。
               * 手机号来自 profile 查询。
               * 不使用 Controller，因为不可编辑。
               */}
              <div className="field">
                <label htmlFor="phone">联系电话</label>
                <input id="phone" className="input" value={profile?.phone ?? ''} readOnly disabled />
                <p className="hint">手机号用于登录与取件验证，如需变更请联系工作人员。</p>
              </div>

              {/*
               * 保存按钮：
               * 请求中显示加载图标并禁用。
               * 成功后 toast 并更新缓存。
               * 失败时按钮恢复可用。
               * 使用主按钮样式，强调保存动作。
               */}
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

          {/*
           * 租赁评价面板：
           * 加载中显示骨架；
           * 无已归还租赁时显示空文案；
           * 有数据时逐条展示工具、归还时间与已评价摘要/评价按钮。
           * 分页只在总数超过一页时显示。
           * 评价成功后该面板会失效并重新请求。
           * 评价按钮只在未评价时展示。
           */}
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
                    // 单条已归还租赁：
                    // 左侧工具名/单号/归还时间/评价摘要；
                    // 右侧未评价时显示“评价”按钮；
                    // 已评价时不提供修改/删除；
                    // 评价摘要包含星级/文字/图片。
                    // key 使用租赁 id。
                    // 数据来自 returnedQuery。
                    // 点击评价按钮打开弹窗并重置输入。
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
          {/*
           * 偏好设置面板：
           * 操作音效开关 + 通知提醒开关列表。
           * 音效是本地状态；提醒偏好由后端维护。
           * 提醒开关在请求中禁用。
           * 偏好加载失败时显示空/提示。
           * 渠道文案区分站内消息与 Mock 短信。
           */}
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
                // 单条提醒偏好：
                // 展示类型名、渠道与说明；
                // 开关状态来自 pref.enabled；
                // 点击调用 prefMutation；
                // 请求中所有开关禁用；
                // 成功后查询失效并刷新。
                // key 使用偏好类型。
                // 文案提示关闭后不再接收该类提醒。
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

          {/*
           * 信用分与流水面板：
           * 顶部当前分数与规则说明；
           * 下方流水列表（增减与原因）；
           * 超过一页时显示分页。
           * 分数未加载时显示“—”。
           * 正数使用成功色，负数使用危险色。
           * 流水只读。
           */}
          <div className="panel reveal">
            <h3 className="panel-title">信用分与流水</h3>
            <div className="flex" style={{ alignItems: 'baseline', gap: 10, marginBottom: 12 }}>
              <b style={{ fontSize: 'var(--t-3xl)', color: 'var(--brand-2)' }}>{creditScore ?? '—'}</b>
              <span className="tiny">初始 100 · 按时归还 +2 · 逾期 −3/次 · 严重损坏 −10</span>
            </div>
            {creditEvents.length > 0 ? (
              // 信用流水列表：
              // 每项展示事件标签、增减值、时间与原因。
              // 增减值带正负号。
              // 圆点颜色区分正负。
              // 事件标签优先使用 eventLabel，缺失时用 eventType。
              // 列表本身不提供操作。
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

          {/*
           * 最近动态面板：
           * 展示最近 4 条租赁（工具名、时间、状态）。
           * 无记录时展示引导文案。
           * 仅用于概览，完整列表在“我的租赁”。
           * 状态色由租赁状态映射。
           * 不提供跳转按钮。
           */}
          <div className="panel reveal">
            <h3 className="panel-title">最近动态</h3>
            {/*
             * 动态列表：
             * 每条显示状态色圆点、工具名、创建时间与状态名。
             * 状态色：已归还绿、逾期红、待支付黄、其余品牌色。
             * 空列表时渲染一条引导项。
             * 列表只读，不可点击。
             */}
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
      // 评价弹窗：
      // 仅 reviewTarget 非空时渲染。
      // 包含星级、文字、图片上传与提交。
      // 遮罩点击关闭并重置输入。
      // 弹窗与页面其他状态独立。
      // 提交成功后由 mutation 关闭。
      // 上传中提交按钮禁用。
      // 弹窗不锁定页面滚动（由样式层决定）。
        <div className="mask show" onClick={closeReview}>
        {/*
         * 评价弹窗主体：窄版，阻止点击冒泡。
         * 头部展示工具名与单号。
         * 内容区包含星级、文字与图片。
         * 底部是取消与提交。
         * 关闭时重置所有输入。
         * 与其他弹窗不共享状态。
         */}
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
          {/*
           * 弹窗头部：
           * 标题“评价工具”、工具名与单号、关闭按钮。
           * 单号便于用户核对评价对象。
           * 关闭按钮与遮罩行为一致。
           * 头部不参与滚动。
           */}
            <div className="dlg-head">
              <div>
                <h3>评价工具</h3>
                <p className="d-sub">{reviewTarget.toolName} · {reviewTarget.orderNo}</p>
              </div>
              <button className="dlg-x" onClick={closeReview} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            {/*
             * 弹窗内容：
             * 星级选择、文字评价、图片上传。
             * 星级默认 5，文字与图片可选。
             * 图片最多 3 张。
             * 所有输入只存在本地状态。
             * 提交时一次性发送。
             * 不自动保存草稿。
             */}
            <div className="dlg-body">
              {/*
               * 星级选择：
               * 五个星星按钮，点击设置 reviewRating。
               * 当前值以文字形式展示。
               * 星星填充随选择变化。
               * 不限制只能选整数以外的值。
               * 不发起请求。
               */}
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

              {/*
               * 文字评价：
               * 选填，最多 500 字。
               * 提交时 trim，空值转 undefined。
               * 不校验最少字数。
               * 内容会展示在评价摘要中。
               * 不渲染 HTML。
               */}
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

              {/*
               * 图片上传：
               * 已上传图片以缩略图展示，可逐张移除；
               * 未满 3 张时显示上传按钮；
               * 隐藏的 file input 由按钮触发；
               * 上传中按钮显示加载并禁用；
               * 移除只影响本地数组，不删除对象存储文件。
               */}
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
            {/*
             * 弹窗底部：
             * 取消关闭并重置输入；
             * 提交在请求中或上传中禁用；
             * 提交按钮显示加载图标；
             * 成功后由 mutation 关闭弹窗。
             */}
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
