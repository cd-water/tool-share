/**
 * 文件职责：工具广场页面（ToolsPage），路由 /tools。
 *
 * 页面能力：
 * - 展示社区可借工具卡片（图片、状态、分类、损坏等级、评分、押金）；
 * - 按关键词（名称/型号/编码）、分类、状态筛选，按综合/评分排序；
 * - 分页浏览服务端工具列表；
 * - 打开工具详情：使用说明、历史租赁、评分与押金；
 * - 立即预约：选择时间、取件人、到期提醒，并实时检查时段可用性；
 * - 不可预约的工具支持“蹲守提醒”（恢复可用后通知）；
 * - “AI 帮我选”弹窗复用 ToolRecommendPanel。
 *
 * 数据来源：
 * - toolsApi.list / detail / history / availability / watched / watch / unwatch；
 * - rentalsApi.create 提交预约；
 * - dashboardApi.get 获取顶部统计与角标。
 *
 * 页面联动：
 * - AI 推荐点击工具后通过 ?tool=id 打开详情，消费后从 URL 移除；
 * - 预约成功后失效 rentals/tools/dashboard/messages 查询并跳转 /rentals。
 *
 * 错误/加载/空状态：
 * - 列表加载显示骨架卡片；
 * - 筛选无结果显示引导性空状态；
 * - 时段冲突展示可约建议并禁用提交；
 * - 请求失败由全局拦截器提示。
 *
 * 权限：页面位于 RequireAuth 之下，需要登录；未审核工具不可预约。
 */
import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Bell,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  ClipboardList,
  Loader2,
  MapPin,
  Package,
  RefreshCw,
  Search,
  Sparkles,
  Star,
  Wrench,
} from 'lucide-react'
import { dashboardApi } from '@/api/dashboard'
import { rentalsApi } from '@/api/rentals'
import { toolsApi } from '@/api/tools'
import type { Rental, Tool, ToolCategory, ToolStatus } from '@/api/types'
import { ToolRecommendPanel } from '@/components/ToolRecommendPanel'
import { badgeClass } from '@/lib/badge'
import { formatDateTime, formatMoney } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { sanitizeHtml } from '@/lib/sanitize'
import { damageLevelTone, toolStatusTone } from '@/lib/status'

// 工具分类选项：
// 与后端 ToolCategory 枚举保持一致：WX 维修、YY 园艺、QJ 清洁、QT 其他。
// 用于筛选下拉与表单类别选择。
// 新增分类时需同步 API 类型。
const CATEGORY_OPTIONS: { value: ToolCategory; label: string }[] = [
  { value: 'WX', label: '维修工具' },
  { value: 'YY', label: '园艺工具' },
  { value: 'QJ', label: '清洁工具' },
  { value: 'QT', label: '其他' },
]

// 工具状态选项：
// AVAILABLE 可用、RENTED 已租出、MAINTENANCE 维修中、SCRAPPED 已报废。
// “已租出/维修中”可蹲守，“已报废”不可预约。
// 状态筛选进入服务端查询参数。
const STATUS_OPTIONS: { value: ToolStatus; label: string }[] = [
  { value: 'AVAILABLE', label: '可用' },
  { value: 'RENTED', label: '已租出' },
  { value: 'MAINTENANCE', label: '维修中' },
  { value: 'SCRAPPED', label: '已报废' },
]

// 每页条数：9 与三列网格匹配，避免最后一行残缺。
// 修改该值会影响分页器与骨架屏数量。
const PAGE_SIZE = 9

// 到期提醒选项：
// 0 表示不提醒，其余为提前分钟数。
// 文案按分钟/小时/天展示，提交时传数值。
// 最大 2880 分钟（48 小时），与后端口径一致。
const REMIND_OPTIONS = [
  { value: 0, label: '不提醒' },
  { value: 30, label: '提前 30 分钟' },
  { value: 60, label: '提前 1 小时' },
  { value: 180, label: '提前 3 小时' },
  { value: 360, label: '提前 6 小时' },
  { value: 720, label: '提前 12 小时' },
  { value: 1440, label: '提前 24 小时' },
  { value: 2880, label: '提前 48 小时' },
]

// 预约表单校验规则：
// - startTime/endTime 必填；
// - pickupPerson 1–20 字；
// - remindBeforeMinutes 为 0–10080 的整数；
// - endTime 必须晚于 startTime；
// - startTime 必须晚于当前时间。
// 两个 refine 分别定位到 endTime 与 startTime 字段，
// 便于在对应输入框下展示错误。
// 时间比较使用本地 Date，datetime-local 字符串按本地时区解析。
// 不校验租期上限，业务上由后端库存与规则决定。
const reservationSchema = z
  .object({
    startTime: z.string().min(1, '请选择开始时间'),
    endTime: z.string().min(1, '请选择结束时间'),
    pickupPerson: z.string().trim().min(1, '请填写取件人').max(20, '取件人姓名过长'),
    remindBeforeMinutes: z.number().int().min(0, '提醒时间不能为负数').max(10080, '最多提前 7 天提醒'),
  })
  .refine(
    (values) =>
      !values.startTime ||
      !values.endTime ||
      new Date(values.endTime) > new Date(values.startTime),
    {
      path: ['endTime'],
      message: '结束时间需晚于开始时间',
    },
  )
  .refine(
    (values) => !values.startTime || new Date(values.startTime) > new Date(),
    {
      path: ['startTime'],
      message: '开始时间需晚于当前时间',
    },
  )

// 表单值类型：由 zod schema 推导。
// 保证 resolver、defaultValues 与提交回调的类型一致。
// remindBeforeMinutes 是 number，select 的字符串值需转换。
type ReservationValues = z.infer<typeof reservationSchema>

/**
 * 格式化预约时段文案。
 *
 * @param startTime 开始时间（ISO 字符串）。
 * @param endTime 结束时间（ISO 字符串）。
 * @returns “开始 至 结束”的可读字符串；缺失时由 formatDateTime 处理。
 *
 * 副作用：无。用于可用时段徽标与建议时段按钮。
 * 输出会随 locale 与 formatDateTime 的实现变化。
 * 不参与时间比较，仅用于展示。
 */
  // formatWindow 是纯展示函数：
  // 输入两个 ISO 时间，输出可读时段。
  // 缺失值交给 formatDateTime 处理。
  // 不参与时间比较或可用性判断。
  // 用于空闲时段徽标与建议按钮。
  // 输出长度随 locale 变化。
function formatWindow(startTime?: string, endTime?: string) {
  return `${formatDateTime(startTime)} 至 ${formatDateTime(endTime)}`
}

/**
 * 判断工具为何不可预约。
 *
 * @param tool 工具对象。
 * @returns 不可预约原因文案；可预约时返回 null。
 *
 * 规则：
 * - auditStatus 存在且不为 1：工具未通过审核；
 * - status 不是 AVAILABLE：工具已租出/维修中/报废。
 * 文案会展示在详情提示与按钮 title 中。
 * 审核状态优先于运行状态，避免给出误导性的原因。
 * 副作用：无。
 */
/** 审核未通过或状态不可用时，说明为什么不能预约。 */
  // reservationBlockReason 的判断顺序很重要：
  // 先看审核状态，再看运行状态。
  // 未审核工具即使显示 AVAILABLE 也不能预约，
  // 因此必须优先给出审核原因。
  // auditStatus 为 null 表示历史数据没有该字段，
  // 此时不阻止预约，保持向后兼容。
  // 运行状态使用 statusName 展示后端本地化文案。
  // 返回 null 表示可预约，调用方据此决定按钮。
  // 函数不抛异常，任意字段缺失都有回退。
  // 文案中的“可蹲守提醒”是引导，不是承诺。
function reservationBlockReason(tool: Tool): string | null {
  if (tool.auditStatus != null && tool.auditStatus !== 1) {
    return `工具${tool.auditStatusName || '尚未通过审核'}，暂不可预约。`
  }
  if (tool.status !== 'AVAILABLE') {
    return `工具当前状态为「${tool.statusName}」，暂不可预约；恢复可用后可蹲守提醒。`
  }
  return null
}

/**
 * 工具当前是否可预约。
 *
 * @param tool 工具对象。
 * @returns 可预约返回 true，否则 false。
 *
 * 等价于 reservationBlockReason(tool) === null；
 * 用于按钮样式、提交禁用与详情操作分支。
 * 副作用：无。
 */
  // canReserve 是 reservationBlockReason 的布尔封装：
  // 保持判断逻辑单一来源，避免多处复制条件。
  // 返回 false 时调用方应展示原因。
  // 不缓存结果，工具状态变化后立即反映。
  // 用于按钮样式、提交禁用与详情操作分支。
  // 该函数在渲染期间调用，必须无副作用。
function canReserve(tool: Tool) {
  return reservationBlockReason(tool) === null
}

/**
 * 工具缩略图。
 *
 * @param props.tool 工具对象，读取 imageUrl / imageUrls。
 * @param props.className 图片样式类。
 * @returns 有图时渲染懒加载图片；无图或加载失败时渲染扳手占位。
 *
 * 副作用：内部维护 failed 状态，onError 后切换到占位；
 * 该状态只影响当前缩略图，不会触发父组件刷新。
 * 图片使用 object-cover，保证卡片尺寸稳定。
 * 无图与加载失败共用同一占位，视觉一致。
 */
  // ToolImage 只处理图片来源与失败回退：
  // 优先主图，其次图集第一张；
  // 两者都没有时直接显示占位。
  // 图片加载失败后不再重试，避免破图循环。
  // failed 状态是组件内部的，不影响父组件。
  // 占位与图片共用父容器尺寸，切换不跳动。
  // 图片懒加载，减少首屏流量。
  // alt 使用工具名，保证语义可读。
  // 不处理点击或预览，交互由父级决定。
  // className 只作用于 img，不作用于占位。
function ToolImage({ tool, className }: { tool: Tool; className?: string }) {
  // 失败标记：
  // 初始 false；onError 后置 true。
  // 一旦为 true，即使 src 变化也继续显示占位，
  // 因为同一组件的 src 通常不会变化。
  const [failed, setFailed] = useState(false)
  const src = tool.imageUrl || tool.imageUrls?.[0]

  if (!src || failed) {
  // 占位分支：
  // 使用主题品牌底色与图标色，
  // 大号扳手图标表达“工具”语义。
  // 占位铺满父容器，保持布局稳定。
  // 不渲染 alt 文本，因为这是装饰性图形。
    return (
      <div className="flex size-full items-center justify-center" style={{ background: 'var(--brand-bg)', color: 'var(--brand-2)' }}>
        <Wrench className="ic lg" />
      </div>
    )
  }

  return <img src={src} alt={tool.name} className={className} loading="lazy" onError={() => setFailed(true)} />
}

/**
 * 把 Date 转换为 datetime-local 输入框所需的值。
 *
 * @param date Date 对象。
 * @returns 形如 YYYY-MM-DDTHH:mm 的本地时间字符串。
 *
 * 副作用：无。手动补零而不是使用 toISOString，
 * 因为 toISOString 会转换为 UTC，导致输入框显示偏移。
 * 月份从 0 开始，因此需要 +1。
 */
  // toInputValue 手动拼接本地时间：
  // 不使用 toISOString，因为它会转成 UTC。
  // 月份 +1，其余字段补零到两位。
  // 输出格式与 datetime-local 的 value 兼容。
  // 不包含秒与毫秒，避免输入框显示多余精度。
  // 不做时区转换，用户看到的就是本地时间。
  // 该函数在默认值与建议时段写回时使用。
  // 输入必须是有效 Date，否则输出 NaN 字符串。
function toInputValue(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`
}

// 原型默认值：明日 09:00 → 三天后 18:00。
// 使用本地时间构造，避免时区转换导致日期偏移。
// 该默认值只在表单首次挂载时计算一次。
// 用户可自由修改，不做额外限制。
/** 原型默认值：明日 09:00 → 三天后 18:00 */
  // defaultRange 生成表单默认时段：
  // 开始为明天 09:00，结束为开始后三天 18:00。
  // 使用本地时间，避免时区导致日期偏移。
  // 复制 start 再修改 end，避免共享引用。
  // 默认值只用于首次渲染，用户可自由修改。
  // 该函数无副作用，可在 useMemo 中调用。
  // 返回字符串而不是 Date，直接适配表单。
  // 不校验“开始晚于当前时间”，因为默认值始终在未来。
function defaultRange() {
  const start = new Date()
  start.setDate(start.getDate() + 1)
  start.setHours(9, 0, 0, 0)
  const end = new Date(start)
  end.setDate(end.getDate() + 3)
  end.setHours(18, 0, 0, 0)
  return { startTime: toInputValue(start), endTime: toInputValue(end) }
}

/**
 * 预约表单（弹窗内）。
 *
 * @param props.tool 要预约的工具。
 * @param props.onCancel 取消回调，返回详情视图。
 * @param props.onSuccess 预约成功回调，刷新缓存并跳转租赁页。
 * @returns 工具摘要、时段、取件人、提醒设置与提交按钮。
 *
 * 副作用：
 * - 时段有效时查询可用性（toolsApi.availability）；
 * - 提交时调用 rentalsApi.create。
 *
 * 关键交互：
 * - 时段冲突时禁用提交，并展示可约建议；
 * - 点击建议时段可一键采用；
 * - 提醒时间会实时预览发送时刻。
 *
 * 校验全部由 zod schema 完成，提交前不额外手写判断。
 * 表单不使用浏览器原生校验，避免与自定义错误重复。
 */
  // ReservationForm 负责一次预约的完整交互：
  // 表单状态、可用性查询、提醒预览与提交。
  // 不负责弹窗开关，父组件控制。
  // 不负责列表刷新，成功后通过 onSuccess 通知。
  // 不负责工具详情展示，父组件在进入预约前已展示。
  // 所有网络请求都通过 API 层。
  // 表单校验由 zod schema 统一完成。
  // 时段有效性同时影响查询与提交。
  // 冲突状态下禁止提交。
  // 组件卸载时无需清理，查询由 React Query 管理。
  // 失败提示由全局拦截器负责。
  // 成功提示由父组件负责。
function ReservationForm({
  tool,
  onCancel,
  onSuccess,
}: {
  tool: Tool
  onCancel: () => void
  onSuccess: (rental: Rental) => void
}) {
  // 默认时段只计算一次，避免每次渲染生成新的 Date。
  // useMemo 依赖为空，表单重置时也保持同一默认值。
  // 该值只用于 useForm 的 defaultValues。
  // 默认时段只计算一次：
  // useMemo 依赖为空，组件生命周期内保持不变。
  // 这样用户重置表单时仍得到同一默认值。
  // 不根据当前时间动态变化，避免渲染期间产生不同结果。
  // 默认值仅用于 useForm 初始化。
  // 用户修改后不再回写默认值。
  // defaultRange 的返回值直接作为 useForm 的默认值：
  // - 只包含 startTime/endTime 两个字段；
  // - pickupPerson 与 remindBeforeMinutes 在下方单独给出；
  // - 默认值只在首次挂载时生效，之后由用户输入覆盖；
  // - 不随当前时间变化，避免组件重渲染时默认值漂移；
  // - 使用 useMemo 空依赖，保证引用稳定；
  // - 若未来需要“最近可用时段”作为默认值，应改为异步初始化；
  // - 默认时段本身不校验是否可约，可用性查询会给出结果；
  // - 该值不参与提交，除非用户未修改。
  const defaults = useMemo(defaultRange, [])
  // 表单初始化：
  // resolver 使用预约 schema；
  // 默认值包含时段、空取件人与 60 分钟提醒。
  // 不使用 mode: onChange，避免输入过程中频繁报错。
  // formState 提供错误与提交状态。
  // setValue 用于采用建议时段。
  const { control, handleSubmit, formState, setValue } = useForm<ReservationValues>({
    resolver: zodResolver(reservationSchema),
    defaultValues: { ...defaults, pickupPerson: '', remindBeforeMinutes: 60 },
  })

  // 监听开始时间：
  // 用于租期计算与可用性查询参数。
  // useWatch 只订阅指定字段，减少无关重渲染。
  const startTimeValue = useWatch({ control, name: 'startTime' })
  // 监听结束时间：
  // 与开始时间共同决定租期与可用性。
  // 变更会触发租期和提示文案重算。
  const endTimeValue = useWatch({ control, name: 'endTime' })
  // 监听提醒分钟数：
  // 用于实时预览提醒发送时刻。
  // 值为 number，select 变更时显式转换。
  const remindBefore = useWatch({ control, name: 'remindBeforeMinutes' })
  // 租期天数：
  // 按毫秒差计算并向上取整，至少 1 天。
  // 时间非法或结束不晚于开始时回退 3 天，避免展示异常值。
  // 租期只用于展示，不参与后端计费。
  // 租期计算：
  // 毫秒差除以一天并向上取整，至少 1 天。
  // 时间非法或结束不晚于开始时回退 3 天。
  // 回退值只是为了展示稳定，不代表真实租期。
  // 租期不参与后端计费，仅用于摘要。
  // 依赖开始与结束两个 watch 值。
  // 计算很轻，无需额外防抖。
  const rentDays = useMemo(() => {
    const start = new Date(startTimeValue)
    const end = new Date(endTimeValue)
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end <= start) return 3
    return Math.max(1, Math.ceil((end.getTime() - start.getTime()) / 86_400_000))
  }, [startTimeValue, endTimeValue])

  // 时段是否有效：
  // 两个时间都能解析且结束晚于开始。
  // 只有有效时才发起可用性查询。
  // 无效时不请求，避免无意义的后端调用。
  // 时段有效性：
  // 两个时间都能解析且结束严格晚于开始。
  // 只有有效时才请求可用性接口。
  // 无效时既不请求也不显示可用性提示。
  // 该值同时用于“正在检查”提示的显示条件。
  // 不检查开始时间是否已过，交给 schema 与后端。
  // 依赖两个 watch 值。
  const rangeValid = useMemo(() => {
    const start = new Date(startTimeValue)
    const end = new Date(endTimeValue)
    return (
      !Number.isNaN(start.getTime()) &&
      !Number.isNaN(end.getTime()) &&
      end > start
    )
  }, [startTimeValue, endTimeValue])

  // 可用性查询：
  // enabled 绑定 rangeValid，避免非法时段请求。
  // staleTime 15s，减少频繁调整时间时的重复请求。
  // retry=false，失败时直接展示错误而不是反复重试。
  // queryKey 包含工具 id 与完整时段。
  // 查询失败不会禁用提交，用户仍可尝试提交。
  // 该查询不轮询，时段变化时自动重新请求。
  // 可用性查询：
  // queryKey 包含工具 id 与完整时段，确保不同时段独立缓存。
  // enabled 绑定 rangeValid，避免非法时段请求。
  // retry=false，失败时直接展示错误。
  // staleTime 15s，减少拖动时间时的重复请求。
  // 查询结果包含 available/message/freeWindows/suggestionWindows。
  // 该查询不阻塞表单提交，但冲突时会禁用提交。
  // 网络错误与“不可用”是两种不同状态。
  // 查询失败时仍允许用户尝试提交。
  // 该查询不轮询，时段变化时自动重新请求。
  // 组件卸载后查询由 React Query 回收。
  const availabilityQuery = useQuery({
    queryKey: queryKeys.tools.availability(tool.id, startTimeValue, endTimeValue),
    queryFn: () => toolsApi.availability(tool.id, startTimeValue, endTimeValue),
    enabled: rangeValid,
    retry: false,
    staleTime: 15_000,
  })
  // 可用性结果与冲突标记：
  // available === false 表示时段冲突，提交按钮禁用。
  // 数据未返回时 conflict 为 false，不阻塞表单。
  // 该值只影响提交按钮与提示文案。
  // 可用性结果：
  // 未返回时为 undefined，模板通过可选链判断。
  // 包含可用标记、提示文案与建议时段。
  // 该值只用于展示与提交禁用。
  const availability = availabilityQuery.data
  // 冲突标记：
  // 只有明确返回 available=false 才视为冲突。
  // 网络错误不等同于冲突，避免误禁用。
  // 冲突标记：
  // 只有明确 available=false 才视为冲突。
  // 未返回或网络错误时视为“未知”，不阻止提交。
  // 该值参与提交按钮的 disabled 与文案。
  const conflict = availability?.available === false

  /**
   * 采用建议时段。
   *
   * @param window 后端返回的可用时段（开始/结束）。
   *
   * 副作用：写入 startTime/endTime 并触发校验。
   * 只接受同时包含开始与结束的时段；缺失时忽略。
   * 建议时段来自 availability.suggestionWindows。
   */
  // adoptWindow 把建议时段写回表单：
  // 转换 ISO 字符串为 datetime-local 格式。
  // shouldValidate 立即重新校验开始/结束规则。
  // 缺失开始或结束时忽略，避免写入空值。
  // 不自动提交，用户仍需点击提交按钮。
  // 写回后会触发可用性查询重新请求。
  // 该方法只由建议时段按钮调用。
  // 不修改提醒设置或取件人。
  function adoptWindow(window: { startTime?: string; endTime?: string }) {
    if (!window.startTime || !window.endTime) return
    setValue('startTime', toInputValue(new Date(window.startTime)), {
      shouldValidate: true,
    })
    setValue('endTime', toInputValue(new Date(window.endTime)), {
      shouldValidate: true,
    })
  }

  // 提醒时刻预览：
  // 0 表示不提醒；未选结束时间时提示先选择；
  // 若提醒时刻已过，说明下单后会补发；
  // 否则展示本地化的发送时间。
  // 该文案不发起请求，只根据表单值计算。
  // 提醒时间以结束时间为基准倒推。
  // 提醒预览：
  // 以结束时间为基准倒推提醒时刻。
  // 0 表示不提醒，直接返回固定文案。
  // 结束时间缺失或非法时给出提示。
  // 提醒时刻已过时说明下单后会补发。
  // 否则展示本地化的发送时间。
  // 该文案只用于展示，不参与提交。
  // 提醒由后端定时任务发送。
  // locale 固定 zh-CN，与页面其他时间格式一致。
  // 依赖结束时间与提醒分钟数。
  // 计算很轻，无需额外缓存。
  const remindHint = useMemo(() => {
    if (remindBefore === 0) return '不发送到期提醒，请自行留意归还时间。'
    if (!endTimeValue) return '选择结束时间后可预览提醒时刻。'
    const end = new Date(endTimeValue)
    if (Number.isNaN(end.getTime())) return ''
    const at = new Date(end.getTime() - Number(remindBefore) * 60_000)
    if (at.getTime() <= Date.now()) return '预约即将到期，系统会在下单后约 1 分钟补发提醒。'
    const text = at.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    })
    return `系统将在 ${text} 发送到期提醒。`
  }, [endTimeValue, remindBefore])

  // 预约提交 mutation：
  // 合并工具 id 与表单值，调用 rentalsApi.create。
  // 成功后调用 onSuccess，由父组件刷新缓存并跳转。
  // 失败时保留表单，用户可以调整时段或取件人后重试。
  // 提交期间按钮禁用，防止重复下单。
  // 不在此处做 toast，成功提示由父组件负责。
  // 提交 mutation：
  // 将工具 id 与表单值合并后调用 rentalsApi.create。
  // 表单值已通过 schema 校验。
  // 成功后调用父组件的 onSuccess。
  // 失败时保留表单，用户可调整后重试。
  // 提交期间按钮禁用，避免重复下单。
  // 不在此处上传图片或处理支付。
  // 不在此处做 toast。
  // mutation 状态由 React Query 管理。
  // 返回的 Rental 由父组件决定如何使用。
  // 该 mutation 只创建预约，不负责取件。
  const mutation = useMutation({
    mutationFn: (values: ReservationValues) => rentalsApi.create({ toolId: tool.id, ...values }),
    onSuccess,
  })

  // 表单渲染：
  // 顶部是工具摘要与租期/押金；
  // 中部是时段、取件人、提醒；
  // 可用性检查结果动态展示在时段下方；
  // 底部是取消与提交，冲突时禁用提交。
  // 所有字段使用 Controller 接入表单。
  // 校验错误展示在对应字段下方。
  // 表单渲染：
  // 工具摘要、租期押金、时段、可用性反馈、
  // 取件人、提醒与底部操作。
  // 所有字段通过 Controller 接入表单。
  // 错误展示在对应字段下方。
  // 可用性反馈是条件渲染。
  // 提交按钮同时受请求中与冲突状态影响。
  // 表单不使用原生校验。
  return (
    <form
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
      id="reserveForm"
    >
      {/*
       * 工具摘要：缩略图 + 名称 + 分类/存放区域。
       * 让用户在提交前确认选中的工具，避免预约错对象。
       * 缩略图复用 ToolImage，失败时自动占位。
       */}
      <div className="r-tool">
        <ToolImage tool={tool} />
        <div>
          <b>{tool.name}</b>
          <span className="tiny">
            {tool.categoryName} · {tool.storageArea || '社区服务站'}
          </span>
        </div>
      </div>
      {/*
       * 租期与押金摘要：
       * 租期由所选时段计算，押金来自工具数据。
       * 强调“归还验收后押金原路退回”，减少用户顾虑。
       * 两者都是只读信息，不参与表单校验。
       */}
      <div className="r-summary">
        <span>
          租期约 <b className="num" style={{ fontSize: 'var(--t-base)' }}>{rentDays}</b> 天 · 归还验收后押金原路退回
        </span>
        <span>
          应付押金 <b>{formatMoney(tool.deposit)}</b>
        </span>
      </div>

      {/*
       * 时段输入网格：
       * 开始/结束各一个 datetime-local 输入框。
       * 两个字段都必填，且结束必须晚于开始。
       * 错误通过 formState.errors 展示在字段下方。
       */}
      <div className="form-grid">
        {/*
         * 开始时间字段：
         * label 与 input 通过 id 关联；
         * aria-invalid 在错误时设置，便于读屏与样式。
         */}
        <div className={'field' + (formState.errors.startTime ? ' is-err' : '')}>
          <label htmlFor="startTime"><span className="req">*</span>开始时间</label>
          {/*
           * Controller 把原生 input 接入 react-hook-form：
           * field 展开 value/onChange/onBlur，
           * fieldState 提供 invalid 状态。
           */}
          <Controller
            control={control}
            name="startTime"
            render={({ field, fieldState }) => (
              <input
                id="startTime"
                type="datetime-local"
                className="input"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          {formState.errors.startTime && <p className="err">{formState.errors.startTime.message}</p>}
        </div>
        <div className={'field' + (formState.errors.endTime ? ' is-err' : '')}>
          <label htmlFor="endTime"><span className="req">*</span>结束时间</label>
          <Controller
            control={control}
            name="endTime"
            render={({ field, fieldState }) => (
              <input
                id="endTime"
                type="datetime-local"
                className="input"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          {formState.errors.endTime && <p className="err">{formState.errors.endTime.message}</p>}
        </div>
      </div>

      {/*
       * 可用性反馈区：
       * - 查询中显示提示；
       * - 可用时显示成功提示与空闲时段；
       * - 冲突时显示警告、期望时段内空闲段与建议时段；
       * - 查询错误显示错误文案。
       * 该区域只在时段有效时出现。
       */}
      <div className="field">
        {/*
         * 查询中提示：
         * 仅在 rangeValid 且 isFetching 时显示。
         * 避免在用户还没选完时间时闪烁。
         */}
        {rangeValid && availabilityQuery.isFetching && (
          <p className="hint">正在检查该时段是否可预约…</p>
        )}
        {/*
         * 可用分支：
         * 展示后端 message 与空闲时段徽标。
         * 徽标可帮助用户确认期望时段确实可用。
         * 该分支不阻止提交，提交仍以后端校验为准。
         */}
        {availability?.available && (
          <div className="notice-sm" style={{ background: 'var(--ok-bg)', color: 'var(--ok)' }}>
            <CheckCircle2 className="ic" />
            <div>
              <b>{availability.message}</b>
              {availability.freeWindows.length > 0 && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
                  {availability.freeWindows.map((window) => (
                    <span
                      key={`${window.startTime}-${window.endTime}`}
                      className="badge ok"
                    >
                      <CalendarClock className="ic" style={{ width: 12, height: 12 }} />
                      {formatWindow(window.startTime, window.endTime)}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
        {/*
         * 冲突分支：
         * 展示冲突原因、期望时段内仍空闲的片段，
         * 以及后端建议的最近可约时段。
         * 点击建议时段会写回表单并重新校验。
         * 冲突时提交按钮禁用。
         */}
        {availability && !availability.available && (
          <div
            className="notice-sm"
            style={{ background: 'var(--warn-bg)', color: 'var(--warn)', display: 'block' }}
          >
            <div style={{ display: 'flex', gap: 9 }}>
              <CircleAlert className="ic" />
              <div>
                <b>{availability.message}</b>
                {availability.freeWindows.length > 0 && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
                    <span className="tiny" style={{ color: 'var(--ink-2)' }}>
                      期望时段内仍空闲：
                    </span>
                    {availability.freeWindows.map((window) => (
                      <span
                        key={`${window.startTime}-${window.endTime}`}
                        className="badge ok"
                      >
                        <CalendarClock className="ic" style={{ width: 12, height: 12 }} />
                        {formatWindow(window.startTime, window.endTime)}
                      </span>
                    ))}
                  </div>
                )}
                {/*
                 * 建议时段按钮：
                 * 点击调用 adoptWindow，把开始/结束写回表单。
                 * 使用 outline 小按钮，避免与主提交混淆。
                 * 建议时段来自后端，前端不再二次筛选。
                 */}
                {availability.suggestionWindows.length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <p className="tiny" style={{ marginBottom: 6, color: 'var(--ink-2)' }}>
                      最近可约时段（点击采用）：
                    </p>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {availability.suggestionWindows.map((window) => (
                        <button
                          key={`${window.startTime}-${window.endTime}`}
                          type="button"
                          className="btn btn-outline btn-sm"
                          onClick={() => adoptWindow(window)}
                        >
                          {formatWindow(window.startTime, window.endTime)}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
        {/*
         * 可用性查询错误：
         * 只展示错误文案，不禁用提交。
         * 用户仍可尝试提交，由后端做最终判断。
         */}
        {availabilityQuery.error && (
          <p className="err" style={{ display: 'block' }}>
            {availabilityQuery.error.message}
          </p>
        )}
      </div>

      {/*
       * 取件人字段：
       * 必填，1–20 字；
       * 用于取件核验，不能填写他人账号昵称。
       * 错误信息展示在字段下方。
       */}
      <div className={'field' + (formState.errors.pickupPerson ? ' is-err' : '')}>
        <label htmlFor="pickupPerson"><span className="req">*</span>取件人</label>
        <Controller
          control={control}
          name="pickupPerson"
          render={({ field, fieldState }) => (
            <input
              id="pickupPerson"
              placeholder="请填写实际取件人姓名"
              className="input"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        {formState.errors.pickupPerson && <p className="err">{formState.errors.pickupPerson.message}</p>}
      </div>

      {/*
       * 到期提醒字段：
       * 下拉选择提前量，0 表示不提醒。
       * 下方实时预览提醒发送时刻。
       * 提醒由后端定时任务发送，前端只负责提交参数。
       */}
      <div className={'field' + (formState.errors.remindBeforeMinutes ? ' is-err' : '')}>
        <label htmlFor="remindBeforeMinutes">到期提醒</label>
        <Controller
          control={control}
          name="remindBeforeMinutes"
          render={({ field }) => (
            <select
              id="remindBeforeMinutes"
              className="select"
              value={String(field.value)}
              onChange={(event) => field.onChange(Number(event.target.value))}
            >
              {REMIND_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          )}
        />
        <p className="hint">{remindHint}</p>
        {formState.errors.remindBeforeMinutes && (
          <p className="err" style={{ display: 'block' }}>
            {formState.errors.remindBeforeMinutes.message}
          </p>
        )}
      </div>

      {/*
       * 提交错误：
       * 展示 mutation 失败原因（如库存冲突）。
       * 表单保持可编辑，用户调整后可重试。
       */}
      {mutation.error && (
        <p className="err" style={{ display: 'block' }}>
          {mutation.error.message}
        </p>
      )}

      {/*
       * 底部操作：
       * 取消返回详情视图；
       * 提交在请求中或时段冲突时禁用。
       * 冲突时按钮文案改为“时段冲突”，并给出 title 说明。
       */}
      <div className="dlg-foot">
        <button type="button" className="btn btn-outline" onClick={onCancel}>
          取消
        </button>
        {/*
         * 提交按钮：
         * - disabled 同时考虑 isPending 与 conflict；
         * - 请求中显示旋转图标；
         * - 冲突时提示用户采用最近可约时段。
         */}
        <button
          type="submit"
          className="btn btn-primary"
          disabled={mutation.isPending || conflict}
          title={conflict ? '当前时段已被预约，请采用最近可约时段' : undefined}
        >
          {mutation.isPending && <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />}
          {conflict ? '时段冲突' : '提交预约'}
        </button>
      </div>
    </form>
  )
}

// 使用说明步骤：
// text 为步骤文字，imageUrl 为可选配图。
// 后端可能存 JSON 数组字符串，也可能存富文本。
type GuideStep = { text: string; imageUrl?: string }

/**
 * 解析使用说明。
 *
 * @param raw 后端返回的 usageGuide 原始字符串。
 * @returns 步骤数组；无法解析为步骤数组时返回 null。
 *
 * 支持两种格式：
 * - JSON 数组字符串：元素可含 text/imageUrl；
 * - 其他字符串：返回 null，由调用方按富文本渲染。
 * 解析过程对元素做类型收窄并过滤空步骤。
 * 副作用：无；解析失败不抛异常。
 * 空数组视为无法解析，返回 null。
 */
  // parseGuideSteps 兼容两种后端存储格式：
  // JSON 数组字符串解析为步骤；
  // 其他字符串返回 null，由调用方按富文本处理。
  // 只对以 [ 开头的内容尝试 JSON.parse。
  // 元素字段做类型收窄，避免任意 JSON 污染渲染。
  // 过滤掉 text 与 imageUrl 都为空的步骤。
  // 空数组视为无法解析，返回 null。
  // 解析失败不抛异常，保证页面稳定。
  // 该函数是纯函数，可在渲染期间调用。
  // 不执行 HTML 清洗，富文本分支由调用方负责。
  // 不限制步骤数量，后端内容量可控。
function parseGuideSteps(raw?: string | null): GuideStep[] | null {
  if (!raw) return null
  const value = raw.trim()
  if (!value.startsWith('[')) return null
  try {
    const arr = JSON.parse(value)
    if (!Array.isArray(arr)) return null
    const steps = arr
      .map((item: unknown) => {
        const record = (item ?? {}) as Record<string, unknown>
        return {
          text: typeof record.text === 'string' ? record.text : '',
          imageUrl: typeof record.imageUrl === 'string' ? record.imageUrl : undefined,
        }
      })
      .filter((step) => step.text || step.imageUrl)
    return steps.length ? steps : null
  } catch {
    return null
  }
}

/**
 * 使用说明展示。
 *
 * @param props.guide 原始说明字符串。
 * @returns 可解析时渲染有序步骤列表；否则按富文本渲染或展示空状态。
 *
 * 安全性：富文本分支使用 sanitizeHtml 清洗后再插入，
 * 避免后端内容注入脚本。
 * 步骤图片懒加载，减少首屏流量。
 * 副作用：无。
 */
  // UsageGuide 优先渲染步骤列表：
  // 可解析时使用有序列表展示文字与配图。
  // 不可解析但有原始内容时按富文本渲染。
  // 富文本必须经过 sanitizeHtml 清洗。
  // 完全没有内容时展示“暂无使用说明”。
  // 步骤图片懒加载。
  // 图片 alt 使用步骤序号。
  // 组件不处理点击或展开。
  // 不使用 dangerouslySetInnerHTML 渲染步骤文本。
function UsageGuide({ guide }: { guide?: string | null }) {
  const steps = parseGuideSteps(guide)
  if (!steps) {
    return guide ? (
      <div className="d-desc" dangerouslySetInnerHTML={{ __html: sanitizeHtml(guide) }} />
    ) : (
      <p className="d-desc">暂无使用说明</p>
    )
  }
  // 步骤列表渲染：
  // 每个步骤一个 li，文字加粗，
  // 可选配图在文字下方。
  // 图片最大宽度 100%，避免溢出。
  // key 使用索引，步骤顺序稳定。
  // 列表本身不提供编辑操作。
  return (
    <ol className="guide">
      {steps.map((step, index) => (
        <li key={index}>
          <b>{step.text}</b>
          {step.imageUrl && (
            <img src={step.imageUrl} alt={`步骤 ${index + 1}`} loading="lazy" style={{ maxWidth: '100%', borderRadius: 8, marginTop: 6 }} />
          )}
        </li>
      ))}
    </ol>
  )
}

/**
 * 工具详情（弹窗内）。
 *
 * @param props.tool 工具对象。
 * @returns 主图、状态徽标、关键字段、不可预约提示、使用说明与历史租赁。
 *
 * 副作用：挂载时查询该工具的历史已完成租赁。
 * 历史记录只展示时段，不包含其他用户信息。
 * 组件不发预约请求，预约由父级切换到 ReservationForm。
 */
  // ToolDetail 是弹窗内的只读详情：
  // 主图、状态徽标、关键字段、不可预约提示、
  // 使用说明与历史租赁。
  // 不发起预约请求，预约由父级切换到表单。
  // 不处理点击或导航。
  // 历史租赁只展示时段，不展示他人信息。
  // 所有可选字段都有缺省值。
  // 组件可独立复用于其他工具详情场景。
  // 数据由父级的详情查询提供。
  // 失败提示由父级/全局拦截器负责。
function ToolDetail({ tool }: { tool: Tool }) {
  // 不可预约原因；为空表示可预约。
  // 用于在详情顶部展示提示。
  // 审核状态与运行状态都会影响该值。
  const blockReason = reservationBlockReason(tool)
  // 历史租赁查询：
  // 按工具 id 查询已完成租赁的时段。
  // 失败时回退为空列表/空状态。
  // 该查询不阻塞主信息展示。
  // 只展示最近若干条，避免详情过长。
  // 历史租赁查询：
  // 按工具 id 获取已完成租赁的时段。
  // 失败时 history 为 undefined，走空状态。
  // 该查询不阻塞头部信息展示。
  // 只展示最近若干条，避免详情过长。
  // 不轮询，打开详情时请求一次。
  // 查询结果不包含承租人身份信息。
  // 组件卸载后由 React Query 回收。
  const historyQuery = useQuery({
    queryKey: queryKeys.tools.history(tool.id),
    queryFn: () => toolsApi.history(tool.id),
  })
  // 历史数据：
  // completedCount 表示累计完成次数；
  // windows 是最近租期时段数组。
  // 数据缺失时走空状态分支。
  const history = historyQuery.data
  // 详情渲染：
  // 主图与字段、不可预约提示、使用说明、历史租赁。
  // 历史租赁加载时显示骨架。
  // 所有区块顺序固定。
  // 详情渲染：
  // 头部主图与字段；
  // 条件展示不可预约提示；
  // 使用说明；
  // 历史租赁（加载/有数据/空）。
  // 所有区块顺序固定。
  // 不使用折叠面板。
  // 图片链接在新窗口打开。
  return (
    <div>
      {/*
       * 详情头部：
       * 左侧主图（4:3 比例），右侧状态徽标、名称、型号与说明。
       * 状态徽标包括运行状态、损坏等级与分类。
       * 主图失败时由 ToolImage 自动占位。
       */}
      <div className="d-hero">
        <div style={{ borderRadius: 12, overflow: 'hidden', aspectRatio: '4/3' }}>
          <ToolImage tool={tool} className="size-full object-cover" />
        </div>
        <div>
          <div className="d-badges">
            <span className={badgeClass(toolStatusTone(tool.status))}>{tool.statusName}</span>
            <span className={badgeClass(damageLevelTone(tool.damageLevel))}>{tool.damageLevelName}</span>
            <span className="badge neutral">{tool.categoryName}</span>
          </div>
          <h3 style={{ margin: '0 0 4px', fontSize: 'var(--t-xl)', fontWeight: 700 }}>{tool.name}</h3>
          <p className="muted" style={{ margin: '0 0 8px' }}>{tool.model}</p>
          <p className="d-desc">
            {tool.supplier ? `由 ${tool.supplier} 提供` : '社区工具房在册工具'}
            {tool.purchaseDate ? ` · ${tool.purchaseDate} 购入` : ''}，押金归还验收合格后原路退回。
          </p>
          {/*
           * 关键字段表：
           * 押金、已租赁次数、评分与存放区域。
           * 评分为 0 条评价时显示“暂无评价”。
           * 存放区域缺失时显示“—”。
           */}
          <dl className="kv">
            <dt>押金金额</dt>
            <dd>{formatMoney(tool.deposit)}</dd>
            <dt>已租赁次数</dt>
            <dd>{tool.rentalCount} 次</dd>
            <dt>用户评分</dt>
            <dd>
              {tool.reviewCount > 0
                ? `${(tool.ratingAvg ?? 0).toFixed(1)} 分（${tool.reviewCount} 条评价）`
                : '暂无评价'}
            </dd>
            <dt>存放区域</dt>
            <dd>
              <MapPin className="ic sm" style={{ verticalAlign: -2, marginRight: 4 }} />
              {tool.storageArea || '—'}
            </dd>
          </dl>
        </div>
      </div>

      {/*
       * 不可预约提示：
       * 仅在 reservationBlockReason 非空时显示。
       * 说明具体原因（未审核/已租出/维修中/报废）。
       */}
      {blockReason && (
        <div className="notice-sm" style={{ background: 'var(--warn-bg)', color: 'var(--warn)' }}>
          <CircleAlert className="ic" />
          <span>{blockReason}</span>
        </div>
      )}

      {/*
       * 使用说明区块：
       * 优先解析为步骤列表，否则按富文本渲染。
       * 无说明时显示“暂无使用说明”。
       */}
      <div className="sec-label">使用说明</div>
      <UsageGuide guide={tool.usageGuide} />

      {/*
       * 历史租赁区块：
       * 加载中显示骨架；
       * 有完成记录时展示最近 6 个时段；
       * 没有记录时显示空文案。
       * 不展示其他承租人的身份信息。
       */}
      <div className="sec-label">历史租赁</div>
        {/*
         * 加载态：单行骨架，高度与文案接近。
         * 不阻塞头部字段展示。
         */}
      {historyQuery.isLoading ? (
        <div className="skel" style={{ height: 48 }}></div>
        // 有历史记录分支：
        // 先说明累计完成次数，
        // 再用时间线展示最近 6 个租期。
        // 时间线只读，不提供操作。
      ) : history && history.completedCount > 0 ? (
        <>
          <p className="tiny" style={{ marginBottom: 8 }}>
            已完成 {history.completedCount} 次租赁，以下为最近租期（仅展示时段，不含他人信息）。
          </p>
          {/*
           * 历史时段列表：
           * 每个 li 展示开始至结束时间，
           * done 类表示已完成。
           * key 使用时段组合，避免重复。
           */}
          <ul className="timeline">
            {history.windows.slice(0, 6).map((window) => (
              <li key={`${window.startTime}-${window.endTime}`} className="done">
                <div className="tl-time">
                  {formatDateTime(window.startTime)} 至 {formatDateTime(window.endTime)}
                </div>
              </li>
            ))}
          </ul>
        </>
        // 无历史记录：显示“暂无已完成的租赁单”。
        // 与加载失败区分，失败时 history 可能为 undefined。
      ) : (
        <p className="muted">暂无已完成的租赁单。</p>
      )}
    </div>
  )
}

/**
 * 工具广场页面。
 *
 * @returns 头部、统计条、筛选栏、工具网格/骨架/空状态、分页与两个弹窗。
 *
 * 副作用：
 * - 列表、详情、可用性、蹲守与仪表盘查询；
 * - 预约/蹲守 mutation；
 * - 从 URL 读取 ?tool= 打开详情。
 *
 * 状态说明：
 * - keyword/appliedKeyword 分离以支持边输边搜；
 * - category/status/sort/page 构成列表查询参数；
 * - selectedToolId/mode/recommendOpen 控制弹窗。
 *
 * 预约成功后跳转租赁页，蹲守操作只刷新蹲守状态。
 */
  // ToolsPage 负责工具广场的完整流程：
  // 列表查询、筛选/排序/分页、详情与预约入口、蹲守。
  // 不负责预约表单内部逻辑，交给 ReservationForm。
  // 不负责工具详情内部渲染，交给 ToolDetail。
  // 不负责 AI 推荐内部逻辑，交给 ToolRecommendPanel。
  // 预约成功后统一失效多个查询并跳转租赁页。
  // 蹲守只刷新蹲守状态，不影响列表。
  // 所有筛选条件由服务端参数驱动。
  // 页面不做本地乐观更新。
  // 页面位于 RequireAuth 之下，需要登录。
  // 错误提示由全局拦截器负责。
  // 不持久化筛选条件，刷新后恢复默认。
  // 弹窗状态只存在内存中。
  // 卸载后查询缓存按默认策略回收。
export default function ToolsPage() {
  // 路由与查询工具：
  // navigate 用于跳转租赁页；
  // searchParams 用于读取/清理 ?tool=；
  // queryClient 用于手动刷新与失效缓存。
  // 三者都在组件生命周期内稳定。
  const navigate = useNavigate()
  // 搜索与筛选状态：
  // keyword 是输入框即时值，appliedKeyword 是真正提交给服务端的值。
  // category/status 为空字符串表示不限。
  // sort 控制综合/评分排序。
  // page 从 1 开始。
  const [searchParams, setSearchParams] = useSearchParams()
  // queryClient 用于手动刷新与失效缓存。
  // 预约成功后失效 rentals/tools/dashboard/messages。
  // 蹲守成功后只失效蹲守查询。
  // 该引用在生命周期内稳定。
  const queryClient = useQueryClient()
  // 关键词输入即时值：
  // 每次输入同步 appliedKeyword，实现边输边搜。
  // 与 appliedKeyword 分离便于未来加入防抖。
  // 关键词输入即时值：
  // 与 appliedKeyword 同步实现边输边搜。
  // 不直接进入查询参数。
  // 刷新页面后清空。
  // 与分类/状态筛选可组合。
  // 输入事件中同时重置页码。
  const [keyword, setKeyword] = useState('')
  // 实际提交给服务端的搜索词：
  // 进入 toolParams，变化时页码重置。
  // 空字符串转为 undefined。
  // 实际提交的搜索词：
  // 进入 toolParams。
  // 空字符串转为 undefined。
  // 变化时页码重置。
  // 与 keyword 分离便于未来加防抖。
  // 不持久化到 URL。
  const [appliedKeyword, setAppliedKeyword] = useState('')
  // 分类筛选：
  // 空字符串表示全部分类。
  // 进入服务端查询参数。
  // 分类筛选：
  // 空字符串表示全部分类。
  // 进入服务端查询参数。
  // 切换时重置页码。
  // 与关键词/状态可组合。
  // 选项来自 CATEGORY_OPTIONS。
  // 不做本地二次过滤。
  const [category, setCategory] = useState<ToolCategory | ''>('')
  // 状态筛选：
  // 空字符串表示全部状态。
  // “已租出/维修中”可用于蹲守。
  // 状态筛选：
  // 空字符串表示全部状态。
  // 进入服务端查询参数。
  // 切换时重置页码。
  // 已租出/维修中可用于蹲守。
  // 选项来自 STATUS_OPTIONS。
  // 与分类/关键词可组合。
  const [status, setStatus] = useState<ToolStatus | ''>('')
  // 排序方式：
  // default 为综合排序，rating 为评分最高。
  // 映射为后端的 orderBy=rating。
  // 排序方式：
  // default 综合排序，rating 评分最高。
  // 映射为后端 orderBy=rating。
  // 切换时重置页码。
  // 只影响列表顺序，不影响详情。
  // 不改变筛选条件。
  const [sort, setSort] = useState<'default' | 'rating'>('default')
  // 当前页码：
  // 从 1 开始，由分页器修改。
  // 筛选/排序变化时重置。
  // 当前页码：
  // 从 1 开始，由分页按钮修改。
  // 筛选/排序变化时重置。
  // 进入服务端查询参数。
  // 总页数由 total/size 计算。
  // 页面越界时后端返回空列表。
  const [page, setPage] = useState(1)
  // 工具弹窗目标 id：
  // null 表示关闭；非空时启用详情查询。
  // 与 AI 推荐弹窗互不干扰。
  // 工具弹窗目标 id：
  // null 表示关闭；非空时启用详情查询。
  // 打开时默认进入详情模式。
  // 与推荐弹窗互不干扰。
  // 关闭时不清除详情缓存。
  const [selectedToolId, setSelectedToolId] = useState<number | null>(null)
  // 弹窗视图模式：
  // detail 展示详情；reserve 展示预约表单。
  // 关闭弹窗时重置为 detail。
  // 弹窗视图模式：
  // detail 展示只读详情；
  // reserve 展示预约表单。
  // 关闭弹窗时重置为 detail。
  // 切换模式不重新请求详情。
  const [mode, setMode] = useState<'detail' | 'reserve'>('detail')
  // AI 推荐弹窗开关：
  // 独立于工具详情弹窗。
  // 选择推荐工具后会关闭并打开对应详情。
  // AI 推荐弹窗开关：
  // 独立于工具详情弹窗。
  // 选择推荐工具后关闭并打开对应详情。
  // 关闭不会清空推荐结果（随卸载丢失）。
  const [recommendOpen, setRecommendOpen] = useState(false)

  // 仪表盘查询：
  // 提供可用工具数、待支付数、租借中数与未读数。
  // 与布局层共享缓存，失败时统计回退 0。
  // 该查询只用于顶部统计条。
  // 仪表盘查询：
  // 提供可用工具数、待支付数、租借中数与未读数。
  // 与布局层共享缓存。
  // 失败时统计回退 0。
  // 只用于顶部统计条。
  // 不阻塞工具列表渲染。
  // 该查询不轮询。
  const dashboardQuery = useQuery({ queryKey: queryKeys.dashboard, queryFn: dashboardApi.get })

  // 列表查询参数：
  // 空筛选转换为 undefined，避免后端误判。
  // sort=rating 时传 orderBy=rating，否则使用默认排序。
  // size 固定 PAGE_SIZE，page 由分页器控制。
  // 参数对象每次渲染重建，React Query 会结构化比较。
  // 列表查询参数：
  // 空筛选转 undefined，避免后端误判。
  // rating 映射为 orderBy=rating。
  // size 固定 PAGE_SIZE。
  // 参数对象每次渲染重建，React Query 做结构化比较。
  // 任一字段变化都会命中新缓存。
  // 不包含弹窗等 UI 状态。
  // 断言为 const，避免意外修改。
  // 该对象直接传给 API 层。
  const toolParams = {
    keyword: appliedKeyword || undefined,
    category: category || undefined,
    status: status || undefined,
    orderBy: sort === 'rating' ? 'rating' : undefined,
    page,
    size: PAGE_SIZE,
  } as const

  // 工具列表查询：
  // queryKey 由参数决定，任一筛选/排序/分页变化都会重新请求。
  // 失败时保留旧数据。
  // 加载态驱动骨架网格。
  // 该查询不轮询，用户可手动刷新。
  // 预约成功后会失效 tools.all。
  // 工具列表查询：
  // queryKey 由参数决定。
  // 失败时保留旧数据。
  // 加载态驱动骨架网格。
  // 该查询不轮询。
  // 预约成功后会失效 tools.all。
  // 刷新按钮也失效该查询。
  // 分页/筛选/排序共享同一配置。
  // 不在 queryFn 内做数据转换。
  // 返回 records/total/size。
  // 列表与详情查询独立。
  const toolsQuery = useQuery({
    queryKey: queryKeys.tools.list(toolParams),
    queryFn: () => toolsApi.list(toolParams),
  })

  // 详情查询：
  // 仅在 selectedToolId 非空时启用。
  // 结果优先于列表项，保证详情最新。
  // 关闭弹窗不清缓存，便于再次打开。
  // 查询失败时回退列表项。
  // 详情查询：
  // 仅在 selectedToolId 非空时启用。
  // 结果优先于列表项。
  // 关闭弹窗不清缓存。
  // 失败时回退列表项。
  // 查询不参与列表分页。
  // 加载态控制详情骨架。
  // 成功后直接交给 ToolDetail/ReservationForm。
  // 不做额外转换。
  const detailQuery = useQuery({
    queryKey: queryKeys.tools.detail(selectedToolId),
    queryFn: () => toolsApi.detail(selectedToolId as number),
    enabled: selectedToolId !== null,
  })

  // 列表中的同 id 记录：
  // 用于详情查询未返回时的即时展示。
  // 找不到时 activeTool 可能为 undefined。
  // 列表中的同 id 记录：
  // 详情未返回时用于即时展示。
  // 可能为 undefined。
  // 不触发额外请求。
  const listTool = toolsQuery.data?.records.find((item) => item.id === selectedToolId)
  // 当前展示的工具：
  // 优先详情接口，其次列表项。
  // 弹窗打开瞬间即可展示基础信息。
  // 当前展示的工具：
  // 优先详情接口，其次列表项。
  // 弹窗打开瞬间即可展示基础信息。
  // 两者都缺失时显示“未找到”。
  const activeTool = detailQuery.data ?? listTool

  // 蹲守查询：
  // 仅当工具不可预约时启用；
  // staleTime 5s，保持状态较新。
  // 结果用于切换“蹲守/取消蹲守”按钮。
  // 可预约工具不会请求该接口。
  // 蹲守查询：
  // 仅在工具不可预约时启用。
  // staleTime 5s，保持状态较新。
  // 结果决定按钮文案与样式。
  // 可预约工具不请求该接口。
  // 失败时回退未蹲守。
  // 预约成功后该查询可能失效。
  // 不轮询。
  // 与详情查询独立。
  const watchQuery = useQuery({
    queryKey: queryKeys.tools.watch(activeTool?.id ?? null),
    queryFn: () => toolsApi.watched(activeTool!.id),
    enabled: !!activeTool && activeTool.status !== 'AVAILABLE',
    staleTime: 5_000,
  })
  // 是否已蹲守：
  // 未返回时回退 false。
  // 该值决定按钮文案与样式。
  // 是否已蹲守：
  // 未返回时 false。
  // 决定按钮文案与样式。
  // 不参与预约流程。
  const watched = watchQuery.data?.watched ?? false
  // 蹲守切换 mutation：
  // 已蹲守则取消，否则添加。
  // 成功后只失效蹲守查询，不刷新整个列表。
  // 失败由全局拦截器提示。
  // 请求中按钮禁用。
  // 蹲守切换 mutation：
  // 已蹲守则取消，否则添加。
  // 成功后只失效蹲守查询。
  // 失败由全局拦截器提示。
  // 请求中按钮禁用。
  // 不刷新整个列表。
  // 不修改工具状态。
  // 不影响预约按钮。
  const watchMutation = useMutation({
    mutationFn: () => (watched ? toolsApi.unwatch(activeTool!.id) : toolsApi.watch(activeTool!.id)),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.tools.watch(activeTool?.id ?? null) }),
  })

  /**
   * 打开工具弹窗。
   *
   * @param id 工具 id。
   *
   * 副作用：设置选中工具并切换到详情视图。
   * 不主动刷新详情，详情查询会按 id 自动请求。
   * 复用同一弹窗，避免打开多个详情。
   */
  // openTool 打开工具弹窗：
  // 设置选中 id 并回到详情模式。
  // 详情查询按 id 自动请求。
  // 不重置列表筛选。
  // 不关闭推荐弹窗（调用方负责）。
  // 可重复打开同一工具，命中缓存。
  // 不滚动页面。
  // 不修改 URL（除非由推荐入口处理）。
  // 该函数只设置本地状态。
  // openTool 的 id 来自工具卡片或推荐结果：
  // - 卡片点击传入列表项 id；
  // - 推荐面板传入推荐条目 toolId；
  // - URL ?tool= 解析后也走同一入口；
  // - 设置 id 后详情查询自动启用；
  // - mode 重置为 detail，避免残留预约表单；
  // - 不校验 id 是否存在于当前列表；
  // - 不存在的 id 会由详情查询返回错误/空；
  // - 不修改 URL，URL 同步由调用方处理。
  function openTool(id: number) {
    setSelectedToolId(id)
    setMode('detail')
  }

  // AI 推荐点击后通过 ?tool=id 打开详情
  // AI 推荐点击后通过 ?tool=id 打开详情。
  // 读取并解析 ?tool=id；
  // 合法 id 打开详情并从 URL 移除该参数，避免刷新重复打开。
  // 使用 replace 避免历史记录膨胀。
  // 依赖 searchParams：参数变化时重新检查。
  // 非法 id 直接忽略，不改变当前弹窗。
  // 消费 URL 中的 ?tool=：
  // 解析为数字，合法则打开详情。
  // 打开后从 URL 移除该参数，避免刷新重复打开。
  // 使用 replace 避免历史记录膨胀。
  // 依赖 searchParams，参数变化时重新检查。
  // 非法 id 直接忽略。
  // 不清除其他查询参数。
  // 该副作用只在参数变化时执行。
  // 不发起额外请求。
  // 不修改列表状态。
  useEffect(() => {
    const id = Number(searchParams.get('tool'))
    if (Number.isFinite(id) && id > 0) {
      openTool(id)
      const next = new URLSearchParams(searchParams)
      next.delete('tool')
      setSearchParams(next, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])
  /**
   * 关闭工具弹窗。
   *
   * 副作用：清空选中 id 并回到详情视图。
   * 不清理详情/蹲守查询缓存。
   * 不重置列表筛选与分页。
   */
  // closeDialog 关闭工具弹窗：
  // 清空选中 id 并回到详情模式。
  // 不清理详情/蹲守缓存。
  // 不重置列表筛选与分页。
  // 不关闭推荐弹窗。
  // 不影响 URL。
  // 该函数只设置本地状态。
  // 再次打开同一工具会命中缓存。
  // 可安全重复调用。
  function closeDialog() {
    setSelectedToolId(null)
    setMode('detail')
  }
  /**
   * 重置全部筛选条件。
   *
   * 副作用：清空关键词、分类、状态、排序，并把页码重置为 1。
   * 不关闭弹窗，不影响已打开的工具详情。
   * 不刷新页面，列表查询会因参数变化自动重新请求。
   */
  // resetFilters 重置全部筛选：
  // 清空关键词、分类、状态与排序。
  // 页码回到 1。
  // 不关闭弹窗。
  // 不刷新页面。
  // 列表查询会因参数变化自动重新请求。
  // 与顶部“刷新”按钮职责不同。
  // 不重置 AI 推荐面板状态。
  // 该函数只设置本地状态。
  function resetFilters() {
    setKeyword('')
    setAppliedKeyword('')
    setCategory('')
    setStatus('')
    setSort('default')
    setPage(1)
  }

  // 顶部统计：
  // 数据来自仪表盘查询，缺失时回退 0。
  // 图标与配色按语义区分：可用/待支付/租借中/未读。
  // 统计条只是概览，点击不跳转。
  // 顶部统计：
  // 数据来自仪表盘查询，缺失时回退 0。
  // 图标与配色按语义区分。
  // 统计条只做概览，不参与筛选。
  // 点击统计项不跳转。
  // 数据随仪表盘查询刷新。
  // 与导航角标共享同一查询缓存。
  // 不单独请求统计接口。
  // 渲染时直接 map。
  const stats = [
    { label: '可借工具', value: dashboardQuery.data?.availableToolCount ?? 0, icon: Wrench, color: 'var(--ok)', bg: 'var(--ok-bg)' },
    { label: '待支付', value: dashboardQuery.data?.pendingPaymentCount ?? 0, icon: ClipboardList, color: 'var(--warn)', bg: 'var(--warn-bg)' },
    { label: '租借中', value: dashboardQuery.data?.rentingCount ?? 0, icon: Package, color: 'var(--info)', bg: 'var(--info-bg)' },
    { label: '未读消息', value: dashboardQuery.data?.unreadCount ?? 0, icon: Bell, color: 'var(--bad)', bg: 'var(--bad-bg)' },
  ]

  // 总页数：
  // 至少 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE。
  // 数据未返回时也按 1 页处理。
  // 总页数：
  // 至少 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE。
  // 数据未返回时按 1 页处理。
  // 与 page 一起控制分页按钮禁用。
  // 不缓存，随查询结果变化。
  // 只用于展示与分页交互。
  const pageCount = toolsQuery.data ? Math.max(1, Math.ceil(toolsQuery.data.total / (toolsQuery.data.size || PAGE_SIZE))) : 1

  // 页面渲染：
  // 头部、统计条、筛选栏、工具网格/骨架/空状态、分页与两个弹窗。
  // 网格分支由 isLoading 与 records.length 决定。
  // 工具弹窗支持详情/预约两种模式。
  // AI 推荐弹窗独立于工具弹窗。
  // 所有区块在同一根 div 内纵向排列。
  // 该 return 是唯一输出，没有提前返回。
  // 页面渲染：
  // 头部、统计条、筛选栏、网格/骨架/空状态、
  // 分页与两个弹窗。
  // 网格分支由 isLoading 与 records.length 决定。
  // 工具弹窗支持详情/预约两种模式。
  // AI 推荐弹窗独立于工具弹窗。
  // 所有区块在同一根 div 内纵向排列。
  // 该 return 是唯一输出。
  // 弹窗放在最后，避免遮挡列表布局。
  // 页面不包含全局 Provider。
  return (
    <div>
    {/*
     * 页面头部：
     * 标题、说明与三个操作：AI 帮我选、刷新、我的租赁。
     * 说明文案强调“押金可退”与“点击卡片查看说明”。
     * 头部不参与滚动。
     */}
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">TOOL PLAZA</p>
          <h1>工具广场</h1>
          <p className="sub">社区工具房共享工具，按需预约、押金可退。点击卡片查看使用指南。</p>
        </div>
        <div className="actions">
          {/*
           * AI 帮我选：打开推荐弹窗。
           * 推荐结果选择后会打开对应工具详情。
           * 使用 outline 样式，与主操作“我的租赁”区分。
           */}
          <button type="button" className="btn btn-outline" onClick={() => setRecommendOpen(true)}>
            <Sparkles className="ic sm" />AI 帮我选
          </button>
          {/*
           * 刷新：失效工具与仪表盘查询。
           * 不直接 refetch，避免与正在进行的请求竞争。
           * 刷新后列表与统计条同步更新。
           */}
          <button
            type="button"
            className="btn btn-outline"
            onClick={() => {
              // 刷新按钮的本地处理：
              // 失效工具与仪表盘查询；
              // 不直接 refetch，避免与进行中的请求竞争；
              // 失效后正在使用的查询会自行重新请求；
              // 不重置筛选与分页；
              // 不关闭弹窗；
              // 统计条与列表会同步刷新；
              // 失败由全局拦截器提示。
              queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
              queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
            }}
          >
            <RefreshCw className="ic sm" />刷新
          </button>
          {/*
           * 我的租赁：跳转 /rentals。
           * 使用主按钮样式，强调这是页面唯一主动作。
           * 跳转会离开工具广场。
           */}
          <button type="button" className="btn btn-primary" onClick={() => navigate('/rentals')}>
            <ClipboardList className="ic sm" />我的租赁
          </button>
        </div>
      </div>

      {/*
       * 统计条：
       * 可借工具、待支付、租借中、未读消息。
       * 数据来自仪表盘查询，缺失时显示 0。
       * data-count 供样式层做数字动画（如启用）。
       */}
      <div className="stat-band reveal">
        {stats.map((item) => {
          const Icon = item.icon
          // 遍历统计项：
          // 每个 tile 展示图标、数值与标签；
          // 图标组件从配置中取出；
          // 数值使用 data-count 供样式层做动画；
          // 缺失值已在上方回退为 0。
          return (
            <div className="stat-tile" key={item.label}>
              <span className="st-ic" style={{ color: item.color, background: item.bg }}>
                <Icon className="ic" />
              </span>
              <div>
                <b className="num" data-count={item.value}>0</b>
                <span>{item.label}</span>
              </div>
            </div>
          )
        })}
      </div>

      {/*
       * 筛选栏：
       * 关键词搜索、分类、状态、排序与重置。
       * 输入即时更新 appliedKeyword，实现边输边搜。
       * 筛选变化时页码重置为 1。
       * 右侧展示当前结果总数。
       */}
      <form
        className="filterbar reveal"
        onSubmit={(event) => {
          event.preventDefault()
          // 表单提交：阻止默认行为；
          // 提交 trim 后的关键词；
          // 页码回到第一页；
          // 不额外触发请求，列表查询随参数变化自动执行。
          setAppliedKeyword(keyword.trim())
          setPage(1)
        }}
      >
        {/*
         * 关键词搜索框：
         * 支持名称/型号/编码。
         * 输入事件同步 keyword 与 appliedKeyword。
         * 提交事件也更新一次，兼容回车。
         */}
        <div className="f-search">
          <Search className="ic" />
          <input
            className="input"
            value={keyword}
            onChange={(event) => {
              // 输入事件：同步 keyword 与 appliedKeyword；
              // 页码重置为 1；
              // 实现边输边搜；
              // 不做防抖，当前数据量下可接受。
              setKeyword(event.target.value)
              setAppliedKeyword(event.target.value.trim())
              setPage(1)
            }}
            placeholder="搜索工具名称 / 型号 / 编码…"
          />
        </div>
        {/*
         * 分类筛选：
         * “全部分类”对应空字符串。
         * 切换时更新 category 并重置页码。
         * 选项来自 CATEGORY_OPTIONS。
         */}
        <select className="select f-item" value={category} onChange={(event) => { setCategory(event.target.value as ToolCategory | ''); setPage(1) }} aria-label="工具分类">
          <option value="">全部分类</option>
          {CATEGORY_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        {/*
         * 状态筛选：
         * “全部状态”对应空字符串。
         * 切换时更新 status 并重置页码。
         * 选项来自 STATUS_OPTIONS。
         */}
        <select className="select f-item" value={status} onChange={(event) => { setStatus(event.target.value as ToolStatus | ''); setPage(1) }} aria-label="工具状态">
          <option value="">全部状态</option>
          {STATUS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        {/*
         * 排序筛选：
         * 综合排序 / 评分最高。
         * 切换时更新 sort 并重置页码。
         */}
        <select
          className="select f-item"
          value={sort}
          onChange={(event) => {
            setSort(event.target.value as 'default' | 'rating')
            setPage(1)
          }}
          aria-label="排序"
        >
          <option value="default">综合排序</option>
          <option value="rating">评分最高</option>
        </select>
        {/*
         * 重置按钮：
         * 清空所有筛选与排序并回到第一页。
         * 不刷新页面。
         */}
        <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilters}>重置</button>
        <span className="grow"></span>
        <span className="tiny">{toolsQuery.data ? `共 ${toolsQuery.data.total} 件` : ''}</span>
      </form>

        {/*
         * 加载态：6 张骨架卡片。
         * 骨架结构与真实工具卡一致，减少布局跳动。
         * 仅首次加载显示。
         */}
      {toolsQuery.isLoading ? (
        <div className="tool-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div className="tool-card" key={i}>
              <div className="tc-img skel"></div>
              <div className="tc-body">
                <div className="skel" style={{ height: 18, marginBottom: 8 }}></div>
                <div className="skel" style={{ height: 12, width: '60%' }}></div>
              </div>
            </div>
          ))}
        </div>
        // 列表态：当前页有工具。
        // 使用 Fragment 包裹网格与分页。
        // 分页只在有数据时渲染。
      ) : toolsQuery.data && toolsQuery.data.records.length > 0 ? (
        <>
          {/*
           * 工具网格：三列（样式层控制），每张卡片可点击打开详情。
           * 卡片展示图片、状态、热门标记、名称、分类、损坏等级、
           * 评分、存放区域、租借次数、押金与预约按钮。
           */}
          <div className="tool-grid">
            {/*
             * 遍历工具：
             * 整卡可点击打开详情；
             * 预约按钮会阻止冒泡并直接进入预约模式。
             * key 使用工具 id。
             */}
            {toolsQuery.data.records.map((tool) => (
              // 单张工具卡：
              // role=button + tabIndex 提供键盘可达性；
              // 点击打开详情；
              // 预约按钮单独处理，避免一次点击触发两次。
              <div className="tool-card tilt reveal" key={tool.id} onClick={() => openTool(tool.id)} role="button" tabIndex={0}>
                {/*
                 * 图片区：
                 * 主图或占位；
                 * 左上角状态徽标；
                 * 租借次数达到阈值时展示“热门”。
                 */}
                <div className="tc-img">
                  <ToolImage tool={tool} className="size-full object-cover" />
                  {/*
                   * 状态徽标：
                   * 使用 toolStatusTone 映射颜色；
                   * 圆点 + 状态名，弱化但可读。
                   */}
                  <div className="tc-status">
                    <span className={badgeClass(toolStatusTone(tool.status))}>
                      <span className="dot"></span>
                      {tool.statusName}
                    </span>
                  </div>
                  {/*
                   * 热门标记：
                   * 仅当 rentalCount >= 20 时显示。
                   * 阈值是产品定义，不是后端字段。
                   */}
                  {tool.rentalCount >= 20 && <span className="tc-hot">热门</span>}
                </div>
                {/*
                 * 卡片主体：
                 * 名称、分类/损坏/评分徽标、
                 * 存放区域、租借次数、押金与预约按钮。
                 */}
                <div className="tc-body">
                  <h3 className="tc-name">{tool.name}</h3>
                  {/*
                   * 名称与徽标行：
                   * 名称可截断；
                   * 评分仅在 reviewCount > 0 时显示。
                   */}
                  <div className="tc-meta">
                    <span className="badge neutral">{tool.categoryName}</span>
                    <span className={badgeClass(damageLevelTone(tool.damageLevel))}>{tool.damageLevelName}</span>
                    {tool.reviewCount > 0 && (
                      <span className="badge brand">
                        <Star className="ic" style={{ width: 12, height: 12 }} />
                        {(tool.ratingAvg ?? 0).toFixed(1)}（{tool.reviewCount}）
                      </span>
                    )}
                  </div>
                  {/*
                   * 存放区域：图标 + 文本，缺失时显示“—”。
                   */}
                  <div className="tc-info">
                    <MapPin className="ic" />
                    <span>{tool.storageArea || '—'}</span>
                  </div>
                  {/*
                   * 租借次数：展示工具使用热度。
                   */}
                  <div className="tc-info">
                    <ClipboardList className="ic" />
                    <span>已租借 {tool.rentalCount} 次</span>
                  </div>
                  {/*
                   * 押金：卡片底部，金额加粗。
                   * 押金是用户决策的关键信息。
                   */}
                  <div className="tc-foot">
                    <span className="tc-deposit">
                      押金 <b>{formatMoney(tool.deposit)}</b>
                    </span>
                  </div>
                  {/*
                   * 预约按钮：
                   * - 可预约时使用主按钮，点击进入预约模式；
                   * - 不可预约时使用 outline，点击打开详情并可蹲守；
                   * - 阻止冒泡，避免与整卡点击重复；
                   * - title 展示不可预约原因。
                   */}
                  <button
                    type="button"
                    className={'btn rent-btn ' + (canReserve(tool) ? 'btn-primary' : 'btn-outline')}
                    title={reservationBlockReason(tool) ?? undefined}
                    onClick={(event) => {
                      event.stopPropagation()
                      openTool(tool.id)
                      // 预约按钮点击：
                      // 先阻止冒泡，避免触发整卡点击；
                      // 打开详情；
                      // 仅在可预约时切换到预约模式；
                      // 不可预约时留在详情，用户可蹲守。
                      if (canReserve(tool)) setMode('reserve')
                    }}
                  >
                    {canReserve(tool) ? '预约' : '蹲守提醒'}
                  </button>
                </div>
              </div>
            ))}
          </div>

          {/*
           * 分页器：
           * 上一页/下一页在边界禁用；
           * 页码按钮直接渲染全部页数；
           * 顶部展示总件数。
           */}
          <div className="table-foot" style={{ marginTop: 18 }}>
            <span className="tiny">共 {toolsQuery.data.total} 件工具</span>
            <div className="pager">
              <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
              {Array.from({ length: pageCount }).map((_, i) => (
                <button key={i} type="button" className={page === i + 1 ? 'active' : ''} onClick={() => setPage(i + 1)}>
                  {i + 1}
                </button>
              ))}
              <button type="button" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
            </div>
          </div>
        </>
      ) : (
        // 空状态：
        // 当前筛选无结果时展示。
        // 提供“重置筛选”按钮。
        // 与错误状态区分，错误由全局提示。
        <div className="empty">
          <Package className="ic e-ic" />
          <h4>暂无符合条件的工具</h4>
          <p>换个关键词或分类试试，也可以重置筛选条件。</p>
          <button className="btn btn-outline" onClick={resetFilters}>重置筛选</button>
        </div>
      )}

      {/*
       * 工具弹窗：
       * 仅 selectedToolId 非空时渲染。
       * 支持 detail/reserve 两种模式。
       * 遮罩点击关闭，内容区阻止冒泡。
       */}
      {selectedToolId !== null && (
      // 弹窗遮罩：点击关闭。
      // 关闭时回到详情模式并清空选中 id。
        <div className="mask show" onClick={closeDialog}>
          <div className={'dlg' + (mode === 'detail' ? ' wide' : '')} onClick={(event) => event.stopPropagation()}>
        {/*
         * 弹窗主体：
         * detail 模式使用 wide 宽度；
         * 内部依次是头部、内容与底部操作。
         */}
            <div className="dlg-head">
              <div>
                <h3>{mode === 'reserve' ? '预约工具' : activeTool?.name ?? '工具详情'}</h3>
                {mode === 'detail' && activeTool ? (
                  <p className="d-sub">
                    {activeTool.code}
                    {activeTool.supplier ? ` · ${activeTool.supplier}` : ''}
                  </p>
                ) : null}
              </div>
              <button className="dlg-x" onClick={closeDialog} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            {/*
             * 弹窗内容：
             * 加载中且无回退数据时显示骨架；
             * 有数据时按 mode 渲染预约表单或详情；
             * 都找不到时显示提示。
             */}
            <div className="dlg-body">
              {/*
               * 加载态：详情未返回且没有列表项时显示骨架。
               * 高度与详情大致相当，减少跳动。
               */}
              {detailQuery.isLoading && !activeTool ? (
                <div className="skel" style={{ height: 200 }}></div>
              // 有数据：reserve 模式渲染预约表单，
              // 否则渲染只读详情。
              // 预约成功后的回调由父级处理。
              ) : activeTool ? (
                mode === 'reserve' ? (
                  <ReservationForm
                    tool={activeTool}
                    onCancel={() => setMode('detail')}
                    // 预约成功回调：
                    // 失效租赁、工具、仪表盘与消息查询；
                    // 关闭工具弹窗；
                    // 跳转我的租赁页；
                    // 新订单会出现在列表顶部（按后端排序）。
                    onSuccess={() => {
                      queryClient.invalidateQueries({ queryKey: queryKeys.rentals.all })
                      queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
                      queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
                      queryClient.invalidateQueries({ queryKey: queryKeys.messages.all })
                      closeDialog()
                      navigate('/rentals')
                    }}
                  />
                ) : (
                  // detail 模式：渲染工具详情。
                  // 组件内部按字段条件展示各区块。
                  <ToolDetail tool={activeTool} />
                )
                // 兜底：详情与列表都找不到工具。
                // 保留关闭按钮，用户可退出。
              ) : (
                <p className="muted">未找到工具信息</p>
              )}
            </div>
            {/*
             * 弹窗底部（仅 detail 模式）：
             * 关闭按钮 + 主操作。
             * 可预约时显示“立即预约”；
             * 不可预约时显示蹲守切换按钮。
             */}
            {mode === 'detail' && activeTool && (
              <div className="dlg-foot">
                <button type="button" className="btn btn-outline" onClick={closeDialog}>关闭</button>
                {canReserve(activeTool) ? (
                  <button type="button" className="btn btn-primary" onClick={() => setMode('reserve')}>
                    <CalendarClock className="ic sm" />立即预约
                  </button>
                ) : (
                  // 蹲守按钮：
                  // - 已蹲守时使用 outline，文案提示可取消；
                  // - 未蹲守时使用主按钮；
                  // - 请求中禁用并显示“处理中…”。
                  // 蹲守只影响通知，不改变工具状态。
                  <button
                    type="button"
                    className={'btn ' + (watched ? 'btn-outline' : 'btn-primary')}
                    disabled={watchMutation.isPending}
                    onClick={() => watchMutation.mutate()}
                  >
                    <Bell className="ic sm" />
                    {watchMutation.isPending ? '处理中…' : watched ? '已蹲守（点击取消）' : '蹲守提醒'}
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/*
       * AI 推荐弹窗：
       * 仅 recommendOpen 时渲染。
       * 复用 ToolRecommendPanel，选择工具后打开详情。
       * 与工具弹窗互斥使用，避免层叠。
       */}
      {recommendOpen && (
      // 推荐弹窗遮罩：点击关闭。
      // 关闭不会清空推荐结果（面板内部状态随卸载丢失）。
        <div className="mask show" onClick={() => setRecommendOpen(false)}>
        {/*
         * 推荐弹窗主体：宽版，容纳推荐列表。
         * 头部说明可以描述场景、时间与预算。
         */}
          <div className="dlg wide" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>AI 帮我选工具</h3>
                <p className="d-sub">描述使用场景、时间与预算，AI 结合社区工具推荐合适的工具</p>
              </div>
              <button className="dlg-x" onClick={() => setRecommendOpen(false)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            {/*
             * 推荐面板：
             * onSelectTool 关闭推荐弹窗并打开对应工具详情。
             * 面板内部处理请求、错误、空结果与反馈。
             */}
            <div className="dlg-body">
              {/*
               * 选择推荐工具：
               * 先关闭推荐弹窗，再打开工具详情。
               * 详情查询会按 toolId 自动请求。
               */}
              <ToolRecommendPanel
                onSelectTool={(id) => {
                  setRecommendOpen(false)
                  openTool(id)
                }}
              />
            </div>
            {/*
             * 推荐弹窗底部：仅提供关闭。
             * 选择工具由推荐卡片自身处理。
             */}
            <div className="dlg-foot">
              <button type="button" className="btn btn-outline" onClick={() => setRecommendOpen(false)}>关闭</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
