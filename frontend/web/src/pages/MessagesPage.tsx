/**
 * 文件职责：消息中心页面（MessagesPage），路由 /messages。
 *
 * 展示当前用户的站内消息与 Mock 短信记录，支持：
 * - 按类型筛选（预约、取件、租期、逾期、维修、公告、系统）；
 * - 分页浏览服务端消息；
 * - 点击消息自动标记已读，并打开详情弹窗；
 * - 一键全部已读、手动刷新；
 * - 从详情跳转到对应处理页面（租赁、报修、工具广场）。
 *
 * 数据来源：
 * - messagesApi.list 获取分页消息；
 * - messagesApi.unreadCount 获取未读数（同时被布局层轮询）；
 * - messagesApi.read / readAll 标记已读。
 *
 * 重要限制：后端列表接口暂不支持类型筛选，因此类型标签只过滤当前页；
 * 分页与未读数始终是服务端数据。这一点在 UI 中有文案提示，避免误解。
 *
 * 错误/加载/空状态：
 * - 加载时展示骨架屏；
 * - 列表为空（或当前页筛选后为空）展示引导性空状态；
 * - 请求失败由全局拦截器提示，页面保留上一次成功数据。
 */
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bell,
  CalendarClock,
  CheckCheck,
  Clock,
  Loader2,
  Package,
  RefreshCw,
  TriangleAlert,
  Wrench,
} from 'lucide-react'
import type { ComponentType } from 'react'
import { messagesApi } from '@/api/messages'
import type {
  DeliveryStatus,
  Message,
  MessageChannel,
  MessageType,
} from '@/api/types'
import { badgeClass } from '@/lib/badge'
import type { BadgeTone } from '@/components/ui/badge'
import { formatDateTime, isReadStatus } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { messageTypeTone } from '@/lib/status'
import { toast } from '@/components/ui/toast'

// 每页条数：与后端默认分页配合。
// 消息量大时不建议调大，避免首屏过慢；
// 未读数是独立接口，不受此分页影响。
const PAGE_SIZE = 10

// 渠道展示文案：
// SYSTEM 为站内消息，SMS 为演示环境使用的 Mock 短信。
// 使用 Record 约束覆盖所有渠道枚举，新增渠道时类型检查会报错。
const CHANNEL_LABEL: Record<MessageChannel, string> = {
  SYSTEM: '站内消息',
  SMS: 'Mock 短信',
}

// 投递状态展示文案：
// PENDING 待发送、SENT 已发送、DELIVERED 已送达、FAILED 失败。
// 仅用于展示，真实状态由后端维护。
const DELIVERY_LABEL: Record<DeliveryStatus, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  DELIVERED: '已送达',
  FAILED: '发送失败',
}

/**
 * 把投递状态映射为徽标色调。
 *
 * @param status 消息投递状态。
 * @returns 成功/危险/信息/中性四种 BadgeTone。
 *
 * 规则：DELIVERED 成功、FAILED 危险、SENT 信息、其余中性。
 * 副作用：无。未知状态回退为 neutral，避免出现无样式徽标。
 */
function deliveryTone(status: DeliveryStatus): BadgeTone {
  if (status === 'DELIVERED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'SENT') return 'info'
  return 'neutral'
}

// 类型筛选标签：
// 空字符串表示“全部”，其余对应 MessageType 枚举。
// 顺序按业务优先级排列：预约、取件、租期、逾期、维修、公告、系统。
// 标签上的数量来自当前页消息的本地统计，不是全量计数。
const TYPE_FILTERS: { value: '' | MessageType; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'RESERVATION', label: '预约' },
  { value: 'PICKUP', label: '取件' },
  { value: 'RENT_DUE', label: '租期' },
  { value: 'OVERDUE', label: '逾期' },
  { value: 'REPAIR', label: '维修' },
  { value: 'ANNOUNCEMENT', label: '公告' },
  { value: 'SYSTEM', label: '系统' },
]

// 每种消息类型的视觉元数据：
// icon 用于列表左侧图标，fg/bg 控制图标颜色与底色。
// 使用 Record 覆盖全部枚举，新增类型时类型检查会提示补齐。
// TOOL_AVAILABLE 表示蹲守工具恢复可用，归入工具广场相关。
const MESSAGE_META: Record<
  MessageType,
  { icon: ComponentType<{ className?: string }>; fg: string; bg: string }
> = {
  RESERVATION: { icon: CalendarClock, fg: 'var(--info)', bg: 'var(--info-bg)' },
  PICKUP: { icon: Package, fg: 'var(--info)', bg: 'var(--info-bg)' },
  RENT_DUE: { icon: Clock, fg: 'var(--warn)', bg: 'var(--warn-bg)' },
  OVERDUE: { icon: TriangleAlert, fg: 'var(--bad)', bg: 'var(--bad-bg)' },
  REPAIR: { icon: Wrench, fg: 'var(--brand-2)', bg: 'var(--brand-bg)' },
  TOOL_AVAILABLE: { icon: Package, fg: 'var(--ok)', bg: 'var(--ok-bg)' },
  ANNOUNCEMENT: { icon: Bell, fg: 'var(--brand-2)', bg: 'var(--brand-bg)' },
  SYSTEM: { icon: Bell, fg: 'var(--ink-2)', bg: 'var(--surface-2)' },
}

// 消息类型到处理页面的映射：
// 租赁相关去 /rentals，报修去 /damage，其余去 /tools。
// 未知类型回退到 /tools，保证“去处理”按钮始终可用。
// 映射只决定导航目标，不校验权限。
const GO_MAP: Record<string, string> = {
  RESERVATION: '/rentals',
  PICKUP: '/rentals',
  RENT_DUE: '/rentals',
  OVERDUE: '/rentals',
  REPAIR: '/damage',
  TOOL_AVAILABLE: '/tools',
  ANNOUNCEMENT: '/tools',
  SYSTEM: '/tools',
}

/**
 * 消息中心页面。
 *
 * @returns 头部统计与操作、类型筛选、消息列表/骨架/空状态、分页与详情弹窗。
 *
 * 副作用：
 * - 挂载时读取当前页消息与未读数；
 * - 点击未读消息触发 read mutation；
 * - “全部已读”触发 readAll mutation；
 * - 两个 mutation 成功后使消息与仪表盘查询失效，驱动角标更新。
 *
 * 状态说明：
 * - typeFilter 只影响当前页的本地过滤；
 * - page 是服务端分页参数；
 * - active 保存当前打开详情的消息。
 */
export default function MessagesPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // 类型筛选：空字符串表示全部。
  // 切换筛选时重置到第一页，避免停留在越界页。
  // 该状态不写入 URL，刷新后恢复“全部”。
  // 类型筛选状态：
  // 空字符串代表“全部”，与 TYPE_FILTERS 的 value 对齐。
  // 切换时只更新本地状态并重置页码，
  // 不重新请求服务端（后端尚不支持类型参数）。
  // 筛选只影响当前页，UI 中有明确提示。
  // 刷新页面后恢复“全部”。
  const [typeFilter, setTypeFilter] = useState<'' | MessageType>('')
  // 服务端分页页码，从 1 开始。
  // 筛选切换与刷新不会重置页码，除非用户主动切换类型；
  // 页面越界时由后端返回空列表，UI 仍可翻回。
  // 当前页码：从 1 开始，作为 list 查询参数。
  // 翻页时保留类型筛选，但筛选本身不改变页码，
  // 只有切换类型时才重置到第一页。
  const [page, setPage] = useState(1)
  // 当前打开详情的消息；null 表示弹窗关闭。
  // 打开时若消息未读，会同时触发已读 mutation。
  // 当前打开详情的消息：
  // 非空时渲染弹窗；关闭时置空。
  // 打开未读消息会触发已读 mutation，但不等待其完成。
  const [active, setActive] = useState<Message | null>(null)

  // 当前页消息查询：
  // queryKey 包含页码与每页条数，翻页会命中不同缓存。
  // 失败时保留旧数据（React Query 默认行为），页面不会白屏。
  // 加载态由 isFetching/isLoading 驱动骨架屏。
  // 消息列表查询：
  // - queryKey 由页码与每页条数组成，翻页命中不同缓存；
  // - queryFn 只传分页参数，类型筛选在本地完成；
  // - 查询失败由 React Query 保留旧数据，页面不白屏。
  // 加载态由 isLoading 驱动骨架屏；后台刷新不显示骨架。
  // 该查询不轮询，用户可通过刷新按钮手动更新。
  const messagesQuery = useQuery({
    queryKey: queryKeys.messages.list({ page, size: PAGE_SIZE }),
    // 请求当前页消息：
    // page 与 PAGE_SIZE 是唯一服务端参数；
    // 返回 records/total/size，用于列表与分页。
    // 不在这里做类型过滤，避免每次筛选都请求后端。
    queryFn: () => messagesApi.list({ page, size: PAGE_SIZE }),
  })

  // 未读数查询：
  // 使用独立 queryKey，与布局层的角标共享缓存。
  // 成功后驱动头部“未读 N 条”与“全部已读”按钮的禁用状态。
  // 该接口失败时 data 为 undefined，展示为 0。
  // 未读数查询：
  // 与布局层共享 queryKey，因此导航角标与本页数字同步。
  // 该查询失败时回退为 0，不阻塞列表渲染。
  // 未读数变化后通过 invalidate 触发刷新。
  const unreadQuery = useQuery({
    queryKey: queryKeys.messages.unreadCount,
    // 直接使用 API 函数作为 queryFn，
    // 因为它无参数且返回形状稳定。
    // React Query 会负责缓存、重试与错误状态。
    queryFn: messagesApi.unreadCount,
  })

  // 单条已读 mutation：
  // 点击未读消息时调用，成功后使消息列表与仪表盘失效，
  // 从而刷新列表中的已读样式与导航角标。
  // 不在本地手动改状态，保持与服务端一致。
  const readMutation = useMutation({
  // 单条已读 mutation：
  // mutationFn 接收消息 id；
  // 成功后失效消息与仪表盘查询，
  // 让列表中的已读样式和导航角标同步更新。
  // 不做乐观更新，避免请求失败时状态回滚复杂。
    mutationFn: (id: number) => messagesApi.read(id),
    // onSuccess 中同时失效两个查询：
    // messages.all 覆盖列表与未读数；
    // dashboard 覆盖导航栏的待支付/未读角标。
    // 失效会触发正在使用的查询重新请求。
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.messages.all })
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
    },
  })

  // 全部已读 mutation：
  // 成功后同样失效消息与仪表盘查询，并 toast 提示。
  // 按钮在未读数为 0 或请求进行中时禁用。
  // 失败由全局拦截器提示，本地状态不变。
  const readAllMutation = useMutation({
  // 全部已读 mutation：
  // 无参数，调用 messagesApi.readAll；
  // 成功后同样失效消息与仪表盘查询，
  // 并 toast 告知用户操作完成。
  // 按钮在未读数为 0 时禁用，避免无效请求。
    mutationFn: messagesApi.readAll,
    // onSuccess 的清理逻辑与单条已读一致，
    // 额外增加成功提示，因为这是一次批量操作。
    // 失败提示由全局 http 拦截器负责。
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.messages.all })
      queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
      toast.add({ title: '全部消息已标记为已读', type: 'success' })
    },
  })

  // 当前页消息记录；接口未返回时为空数组，
  // 这样后续统计与过滤无需重复判空。
  // 空数组与“加载中”通过 isLoading 区分。
  // 派生数据统一在此计算，避免在 JSX 中重复判空：
  // - all：当前页全部消息；
  // - unreadCount：服务端未读数；
  // - total/pageCount：分页信息；
  // - counts/list：本地筛选与统计。
  // 这些派生值不触发额外请求，只随 messagesQuery 数据变化。
  // 所有回退值（?? 0 / ?? []）保证渲染阶段不出现 undefined。
  const all = messagesQuery.data?.records ?? []
  // 后端消息接口暂不支持类型筛选，类型标签只过滤当前页；分页与未读数均为服务端数据。
  // 服务端未读数；未加载完成时回退 0。
  // 注意它不是当前页未读数，而是全量未读数。
  const unreadCount = unreadQuery.data ?? 0
  // 服务端返回的总条数，用于分页器与“共 N 条”。
  // 本地类型筛选不会改变该总数。
  const total = messagesQuery.data?.total ?? 0
  // 总页数：至少 1 页，避免空列表时出现 0 页。
  // size 缺失时回退 PAGE_SIZE，兼容旧接口。
  const pageCount = Math.max(1, Math.ceil(total / (messagesQuery.data?.size || PAGE_SIZE)))

  // 本地统计：按类型计数，用于标签上的数字。
  // 只统计当前页，因此与“全部”标签的总数一致。
  // 使用普通对象而非 Map，便于模板中直接索引。
  // 本地类型计数：
  // 只统计当前页消息，用于标签上的数字。
  // 使用普通对象，key 为 MessageType；
  // 未出现的类型不会出现在对象中，读取时用 ?? 0 兜底。
  // 该统计不包含全量数据，UI 中已注明“仅当前页”。
  const counts: Record<string, number> = {}
  all.forEach((message) => (counts[message.type] = (counts[message.type] ?? 0) + 1))
  // 类型筛选后的列表：
  // 未选择类型时返回当前页全部；
  // 选择类型时只过滤当前页，不重新请求服务端。
  // 这是后端能力限制下的折中，UI 中有明确提示。
  // 类型过滤后的列表：
  // - 未选择类型时返回全部当前页消息；
  // - 选择类型时按 message.type 过滤；
  // 该操作不改变服务端分页，也不影响未读数。
  // 过滤结果为空时进入空状态分支。
  const list = all.filter((message) => !typeFilter || message.type === typeFilter)

  /**
   * 打开消息详情。
   *
   * @param message 被点击的消息。
   *
   * 副作用：设置 active 打开弹窗；若消息未读，触发已读 mutation。
   * 已读判断通过 isReadStatus 统一处理，兼容后端多种已读表示。
   * 已读请求失败不影响弹窗打开，用户仍可阅读内容。
   */
  // openMessage 是列表行点击/键盘激活的统一入口：
  // 先打开详情弹窗，再判断是否需要标记已读。
  // 已读判断使用 isReadStatus，兼容不同后端表示。
  // 已读请求失败不影响阅读，弹窗保持打开。
  function openMessage(message: Message) {
    // 设置 active 触发弹窗渲染；
    // 弹窗内容直接使用列表中的消息对象，
    // 不额外请求详情接口。
    setActive(message)
    if (!isReadStatus(message.readStatus)) readMutation.mutate(message.id)
  }

  // 页面骨架：
  // 头部（标题、未读数、刷新/全部已读）→ 筛选/统计 → 列表 → 分页 → 详情弹窗。
  // 列表区域根据 isLoading、list.length 在骨架/列表/空状态之间切换。
  // 详情弹窗独立于列表，关闭后列表状态保持不变。
  // 页面 return 前的状态说明：
  // - 列表数据来自 messagesQuery；
  // - 未读数来自 unreadQuery；
  // - 弹窗内容来自 active；
  // 三者互不阻塞，任一失败都不影响其余区域。
  // 头部、筛选、列表与分页在同一个根 div 内纵向排列。
  // 详情弹窗放在最后，避免遮挡列表的布局计算。
  // 所有交互都通过本地状态与 mutation 完成。
  return (
    <div>
    {/*
     * 页面头部：标题、说明与未读数量。
     * 说明文案会随未读数变化，提供即时反馈。
     * 头部不参与滚动，位于内容区顶部。
     */}
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">NOTIFICATIONS</p>
          <h1>消息中心</h1>
          <p className="sub">租赁、取件、租期、维修与系统公告，重要提醒一目了然。当前有 {unreadCount} 条未读。</p>
        </div>
      {/*
       * 操作区：刷新与全部已读。
       * 刷新同时重新请求列表与未读数；
       * 全部已读只在有未读时可用。
       */}
        <div className="actions">
        {/*
         * 刷新按钮：手动触发两个查询的 refetch。
         * 不使用 invalidateQueries，避免不必要的缓存清空；
         * refetch 会保留旧数据直到新数据返回。
         * 请求进行中按钮不禁用，React Query 会合并重复请求。
         */}
          <button
            type="button"
            className="btn btn-outline"
            onClick={() => {
              messagesQuery.refetch()
              unreadQuery.refetch()
            }}
          >
            <RefreshCw className="ic sm" />
            刷新
          </button>
        {/*
         * 全部已读按钮：
         * - 未读数为 0 或 mutation 进行中时禁用；
         * - 进行中显示旋转图标；
         * - 成功后列表与角标自动刷新。
         */}
          <button
            type="button"
            className="btn btn-primary"
            disabled={unreadCount === 0 || readAllMutation.isPending}
            onClick={() => readAllMutation.mutate()}
          >
            {readAllMutation.isPending ? (
              <Loader2 className="ic sm" style={{ animation: 'spin .7s linear infinite' }} />
            ) : (
              <CheckCheck className="ic sm" />
            )}
            全部已读
          </button>
        </div>
      </div>

    {/*
     * 筛选与统计栏：
     * 左侧是类型标签，右侧是“仅当前页”提示与未读徽标。
     * 窄屏下自动换行，避免标签被压缩。
     */}
      <div className="between reveal" style={{ marginBottom: 18, flexWrap: 'wrap' }}>
      {/*
       * 类型标签栏：每个标签展示类型名与当前页数量。
       * 点击切换 typeFilter，不触发服务端请求。
       * 选中态由 active 类控制。
       */}
        <div className="tabs">
        {/*
         * 遍历类型标签：
         * “全部”使用 all.length，其余使用 counts[type]。
         * 数量只代表当前页，不能理解为全量未读。
         */}
          {TYPE_FILTERS.map((item) => {
            const count = item.value ? counts[item.value] ?? 0 : all.length
            // 单条标签渲染：
            // count 由本地统计得到；
            // 点击只更新 typeFilter，不触发请求。
            // 返回按钮节点，由 map 收集为数组。
            return (
          // 单个标签按钮：
          // key 使用 type 或 'all'，避免空字符串冲突；
          // 点击后仅更新本地筛选状态。
          // 不使用 NavLink，因为这是页内过滤而非路由。
              <button
                key={item.value || 'all'}
                type="button"
                className={typeFilter === item.value ? 'active' : ''}
                onClick={() => setTypeFilter(item.value)}
              >
                {item.label}
                <span className="cnt">{count}</span>
              </button>
            )
          })}
        </div>
        {/*
         * 右侧提示：
         * 明确告知“类型筛选仅作用于当前页”，
         * 避免用户误以为数量是全局统计。
         * 未读徽标在未读数为 0 时隐藏。
         */}
        <div className="flex" style={{ alignItems: 'center', gap: 10 }}>
          <span className="tiny">类型筛选仅作用于当前页</span>
          <span className="badge bad" style={{ display: unreadCount ? 'inline-flex' : 'none' }}>
            <span className="dot"></span>
            未读 {unreadCount} 条
          </span>
        </div>
      </div>

    {/*
     * 加载态：展示 5 条骨架消息。
     * 骨架结构与真实消息行一致，减少加载完成时的布局跳动。
     * 仅首次加载显示；翻页时 React Query 保留旧数据。
     */}
      {messagesQuery.isLoading ? (
        <div className="card">
          {Array.from({ length: 5 }).map((_, i) => (
            <div className="msg" key={i}>
              <div className="skel" style={{ width: 38, height: 38, borderRadius: 11 }}></div>
              <div style={{ flex: 1 }}>
                <div className="skel" style={{ height: 16, marginBottom: 8 }}></div>
                <div className="skel" style={{ height: 12, width: '60%' }}></div>
              </div>
            </div>
          ))}
        </div>
    // 列表态：当前页有消息（可能经过本地筛选）。
    // 每条消息可点击、可键盘激活。
    // 已读消息使用较弱的视觉样式（read 类）。
      ) : list.length > 0 ? (
      // 消息列表容器：卡片式布局，整行可点击。
      // 列表本身不处理分页，分页在下方独立渲染。
      // reveal 类提供进入动画。
        <div className="msg-list card reveal">
        {/*
         * 遍历消息：
         * read 决定已读样式；
         * meta 提供图标与配色；
         * 未知类型回退 SYSTEM，保证不会因新枚举崩溃。
         */}
          {list.map((message) => {
            const read = isReadStatus(message.readStatus)
            const meta = MESSAGE_META[message.type] ?? MESSAGE_META.SYSTEM
            const Icon = meta.icon
          // 单条消息行：
          // - role=button + tabIndex 提供键盘可达性；
          // - Enter 键触发打开详情；
          // - 点击整行打开详情并标记已读。
          // 行内不放置按钮，避免嵌套交互元素。
            // 单条消息渲染：
            // read 控制已读样式，meta 控制图标；
            // Icon 是组件引用，必须大写开头才能作为 JSX 使用。
            // 返回整行节点，点击/键盘事件在行上。
            return (
              <div
                key={message.id}
                className={'msg' + (read ? ' read' : '')}
                role="button"
                tabIndex={0}
                onClick={() => openMessage(message)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') openMessage(message)
                }}
              >
            {/*
             * 左侧类型图标：颜色与底色来自 MESSAGE_META。
             * 图标不参与交互，仅辅助识别消息类型。
             */}
                <span className="m-ic" style={{ color: meta.fg, background: meta.bg }}>
                  <Icon className="ic" />
                </span>
            {/*
             * 消息主体：标题、类型/渠道/投递状态徽标、内容预览。
             * 预览文本由样式层截断，避免长内容撑高行。
             */}
                <div className="m-body">
              {/*
               * 顶部信息行：
               * 标题在左，三枚徽标在右。
               * 徽标顺序：类型、渠道、投递状态。
               */}
                  <div className="m-top">
                    <h3>{message.title}</h3>
                {/*
                 * 类型徽标使用统一的状态色映射；
                 * 渠道与投递状态使用中性/语义色区分。
                 * 徽标不换行，窄屏下由容器换行。
                 */}
                    <span className={badgeClass(messageTypeTone(message.type))}>{message.typeName}</span>
                    <span className="badge neutral">{CHANNEL_LABEL[message.channel]}</span>
                    <span className={badgeClass(deliveryTone(message.deliveryStatus))}>
                      {DELIVERY_LABEL[message.deliveryStatus]}
                    </span>
                  </div>
              {/*
               * 内容预览：单行截断，完整内容在详情弹窗中查看。
               * 不使用富文本渲染，避免消息内容注入样式。
               */}
                  <p className="m-preview">{message.content}</p>
                </div>
            {/*
             * 时间列：服务端创建时间，格式化为本地时间。
             * 时间不参与交互，仅作信息展示。
             */}
                <span className="m-time">{formatDateTime(message.createTime)}</span>
            {/*
             * 未读圆点：已读时由 read 类隐藏（样式层控制）。
             * 圆点只是视觉提示，读屏信息由标题与徽标提供。
             */}
                <span className="m-dot"></span>
              </div>
            )
          })}
        </div>
    // 空状态：当前页没有消息，或类型筛选后为空。
    // 文案引导用户切换分类或等待新消息。
    // 与“加载失败”区分：失败由全局提示，不进入空状态。
      ) : (
        <div className="empty reveal">
          <Bell className="ic e-ic" />
          <h4>暂无相关消息</h4>
          <p>当前分类下没有消息，切换到其他分类看看。</p>
        </div>
      )}

    {/*
     * 分页器：仅在总数大于 0 时显示。
     * 上一页/下一页在边界禁用；
     * 页码按钮最多显示全部页数（消息量通常有限）。
     * 翻页只改变 page，触发服务端查询。
     */}
      {total > 0 && (
        <div className="between" style={{ marginTop: 18 }}>
          <span className="tiny">共 {total} 条</span>
          <div className="pager">
          {/*
           * 上一页按钮：第一页时禁用。
           * 点击直接递减 page，触发列表查询。
           * 不校验下界，禁用属性已阻止越界。
           */}
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            {Array.from({ length: pageCount }).map((_, index) => (
              <button
                key={index}
                type="button"
                className={page === index + 1 ? 'active' : ''}
          // 页码按钮：当前页高亮。
          // 点击跳转到指定页，页码从 1 开始。
          // 页数通常不大，直接全部渲染。
                onClick={() => setPage(index + 1)}
              >
                {index + 1}
              </button>
            ))}
          {/*
           * 下一页按钮：最后一页时禁用。
           * 点击递增 page，触发列表查询。
           * 与服务端 total/size 计算出的 pageCount 对齐。
           */}
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
          </div>
        </div>
      )}

    {/*
     * 详情弹窗：仅 active 非空时渲染。
     * 遮罩点击关闭，内容区阻止冒泡。
     * 弹窗内展示完整内容与跳转按钮。
     */}
      {active && (
      // 遮罩层：点击关闭弹窗。
      // 使用 mask show 类由样式层控制淡入。
      // 关闭时不重置筛选与分页。
      // 弹窗遮罩：点击关闭详情。
      // 仅在 active 非空时渲染，因此无需额外的 open 状态。
      // 关闭后列表滚动位置保持不变。
      // 遮罩层不阻止键盘 Esc（由浏览器/基础组件处理）。
        <div className="mask show" onClick={() => setActive(null)}>
        {/*
         * 弹窗主体：窄版对话框。
         * 阻止点击冒泡，避免点击内容区也关闭。
         */}
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
          {/*
           * 弹窗头部：消息标题与创建时间。
           * 标题可能较长，由样式层换行。
           * 关闭按钮在右上角，带 aria-label。
           */}
            <div className="dlg-head">
              <div>
                <h3>{active.title}</h3>
                <p className="d-sub">{formatDateTime(active.createTime)}</p>
              </div>
              {/*
               * 关闭按钮：置空 active。
               * 使用 X 图标 + aria-label，保证读屏可理解。
               * 点击不会触发遮罩的关闭逻辑（事件冒泡到遮罩同样置空）。
               */}
              <button className="dlg-x" onClick={() => setActive(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
          {/*
           * 徽标行：类型、渠道、投递状态。
           * 与列表行保持一致，便于用户确认消息来源。
           */}
              <div className="flex" style={{ marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
                <span className={badgeClass(messageTypeTone(active.type))}>
                  <span className="dot"></span>
                  {active.typeName}
                </span>
                <span className="badge neutral">{CHANNEL_LABEL[active.channel]}</span>
                <span className={badgeClass(deliveryTone(active.deliveryStatus))}>
                  {DELIVERY_LABEL[active.deliveryStatus]}
                </span>
              </div>
          {/*
           * 消息正文：
           * - 保留换行（由样式层控制）；
           * - 使用次要文字色与宽松行高；
           * - 不渲染 HTML，避免注入风险。
           */}
              <p style={{ margin: 0, color: 'var(--ink-2)', lineHeight: 1.8 }}>{active.content}</p>
            </div>
            <div className="dlg-foot">
              {/*
               * 底部“关闭”按钮与右上角关闭行为一致。
               * 使用 outline 样式，视觉权重低于“去处理”。
               * 关闭后不重置筛选与页码，便于继续浏览。
               */}
              <button type="button" className="btn btn-outline" onClick={() => setActive(null)}>关闭</button>
          {/*
           * 底部操作：
           * “关闭”只关闭弹窗；
           * “去处理”根据 GO_MAP 跳转到对应页面。
           * 未知类型回退到工具广场。
           */}
              <button type="button" className="btn btn-primary" onClick={() => navigate(GO_MAP[active.type] || '/tools')}>
                去处理
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
  // 页面渲染结束：筛选、列表、分页与弹窗互不依赖，
  // 关闭弹窗不会重置筛选或页码。
  // 所有请求状态都由 React Query 管理，组件本身不缓存消息。
// 组件结束。MessagesPage 的职责边界：
// - 负责消息展示、筛选、已读操作与导航；
// - 不负责生成消息（由后端定时任务/业务事件产生）；
// - 不维护全量未读缓存，未读数以服务端为准。
}
