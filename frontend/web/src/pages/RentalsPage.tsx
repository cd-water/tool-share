/**
 * 文件职责：我的租赁页面（RentalsPage），路由 /rentals。
 *
 * 页面能力：
 * - 按状态标签（全部/待支付/已预约/使用中/已逾期/已归还/已取消）筛选；
 * - 按单号、工具名、编码搜索；
 * - 分页浏览服务端租赁单；
 * - 待支付订单跳转支付弹窗（Mock 支付方式）；
 * - 取件前取消租赁（可选原因）；
 * - 查看租赁详情：租期、押金、取件人、提醒、费用、支付流水与流转时间线；
 * - 使用中/逾期订单可跳转报修页。
 *
 * 数据来源：
 * - rentalsApi.list 分页查询；
 * - rentalsApi.detail 查询详情（列表已有数据时优先复用）；
 * - rentalsApi.pay / rentalsApi.cancel 执行操作。
 *
 * 操作成功后统一失效 rentals/dashboard/tools/messages 查询，
 * 保证列表、导航角标与工具可用状态同步。
 *
 * 错误/加载/空状态：
 * - 首次加载展示骨架屏；
 * - 列表为空展示引导性空状态；
 * - 请求失败由全局拦截器提示，页面保留旧数据。
 */
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Bell,
  CalendarClock,
  ClipboardList,
  CreditCard,
  Loader2,
  Plus,
  RotateCcw,
  Search,
  UserRound,
  Wrench,
  XCircle,
} from 'lucide-react'
import { rentalsApi } from '@/api/rentals'
import type { Rental, RentalStatus } from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { toast } from '@/lib/fx'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { rentalStatusTone } from '@/lib/status'

// 状态标签：
// 第一个为空字符串，表示“全部”；其余与 RentalStatus 枚举一一对应。
// 标签顺序按租赁生命周期排列，便于用户理解流程。
// 标签只改变查询参数，不改变页面路由。
const STATUS_TABS: { value: '' | RentalStatus; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待支付' },
  { value: 'RESERVED', label: '已预约' },
  { value: 'PICKED_UP', label: '使用中' },
  { value: 'OVERDUE', label: '已逾期' },
  { value: 'RETURNED', label: '已归还' },
  { value: 'CANCELLED', label: '已取消' },
]

// 状态色映射：
// 用于卡片顶部/左侧的强调色（由样式层通过 --rc 变量消费）。
// 待支付偏橙、已预约偏蓝、使用中偏黄、逾期偏红、已归还偏绿、已取消偏灰。
// 这些颜色只做辅助识别，状态语义仍以文字为准。
const STATUS_COLOR: Record<string, string> = {
  PENDING_PAYMENT: '#C77700',
  RESERVED: '#2F6D80',
  PICKED_UP: '#F7A600',
  OVERDUE: '#C93A2E',
  RETURNED: '#2E7D46',
  CANCELLED: '#9C917E',
}

// Mock 支付方式：
// 微信、支付宝、社区余额三种，仅用于演示支付弹窗。
// color 控制图标颜色；key 与后端支付记录无强绑定。
// 当前后端 pay 接口不区分渠道，选择只影响 UI。
const PAY_METHODS = [
  { key: 'wechat', label: '微信支付', color: '#2BA245' },
  { key: 'alipay', label: '支付宝', color: '#1677FF' },
  { key: 'balance', label: '社区余额', color: 'var(--brand-2)' },
]

/**
 * 生成“到期提醒”的展示文案。
 *
 * @param rental 租赁单，读取 remindBeforeMinutes 与 remindAt。
 * @returns 未设置 / 不提醒 / 提前 N 分钟（时间）/ 提前 N 分钟。
 *
 * 规则：
 * - null/undefined 视为未设置；
 * - 0 表示不提醒；
 * - 分钟数能整除 1440 显示“天”，整除 60 显示“小时”，否则显示“分钟”；
 * - 若后端返回 remindAt，则附带具体提醒时刻。
 *
 * 副作用：无。仅做格式化，不修改租赁单。
 */
  // describeRemind 是纯格式化函数，不依赖状态或请求。
  // 输入只读取 remindBeforeMinutes 与 remindAt 两个字段。
  // 输出用于详情字段表与列表卡片。
  // 三种单位（天/小时/分钟）按整除关系选择，避免出现“90 分钟”这类不直观文案。
  // 未设置与不提醒是两种不同状态，需要分别表达。
  // 若有具体提醒时刻，则附带格式化后的时间。
  // 函数不抛异常，任意缺失字段都有回退。
  // 不做本地化以外的时区转换，交给 formatDateTime。
  // 不修改传入对象，保证调用方可安全复用。
  // 该函数没有副作用，可在渲染期间直接调用。
function describeRemind(rental: Rental): string {
  // 取提醒分钟数：
  // null/undefined 表示用户从未设置过提醒；
  // 0 表示显式关闭提醒；
  // 正数表示提前 N 分钟。
  // 三种情况在文案上必须区分。
  const minutes = rental.remindBeforeMinutes
  // 未设置分支：返回固定文案“未设置”。
  // 不显示“0 分钟”，避免与“不提醒”混淆。
  // 该分支在详情与列表中都会出现。
  if (minutes === null || minutes === undefined) return '未设置'
  // 显式关闭分支：返回“不提醒”。
  // 与未设置的区别是用户做过选择。
  // 该分支不展示具体时间。
  if (minutes === 0) return '不提醒'
  const label =
    minutes % 1440 === 0
      ? `${minutes / 1440} 天`
      : minutes % 60 === 0
        ? `${minutes / 60} 小时`
        : `${minutes} 分钟`
  return rental.remindAt ? `提前 ${label}（${formatDateTime(rental.remindAt)}）` : `提前 ${label}`
}

/**
 * 租赁详情内容（弹窗内展示）。
 *
 * @param rental 租赁单详情或列表项。
 * @returns 状态徽标、关键字段表、归还结算、费用/支付流水与流转时间线。
 *
 * 说明：
 * - 时间线根据字段动态拼接，只展示已经发生的节点；
 * - 归还结算仅在 status=RETURNED 时展示；
 * - 费用与支付流水来自可选的 fees/payments 数组；
 * - 组件不发请求，数据由父级 detailQuery 提供。
 *
 * 副作用：无；纯展示。
 */
  // RentalDetail 是弹窗内的只读视图：
  // 不发起请求，不持有状态，只根据传入的 rental 渲染。
  // 数据可能来自详情接口，也可能回退到列表项，
  // 因此所有可选字段都必须做缺省处理。
  // 时间线、结算、费用与流水都是条件渲染。
  // 字段顺序按用户核对信息时的关注度排列。
  // 组件不处理支付/取消等操作，操作入口在列表页。
  // 不直接访问路由，避免详情组件与页面耦合。
  // 所有金额使用 formatMoney，所有时间使用 formatDateTime。
  // 该组件可以独立复用于其他需要展示租赁详情的场景。
function RentalDetail({ rental }: { rental: Rental }) {
  // 时间线按生命周期动态拼接：
  // 1) 提交预约为必然节点；
  // 2) 押金已支付；
  // 3) 已取件（使用中/逾期/已归还）；
  // 4) 已归还；
  // 5) 已取消。
  // 每个节点只包含标题、时间与描述，渲染时统一样式。
  // timeline 的类型显式声明为对象数组，
  // 保证 push 的节点结构一致，避免字段拼写错误。
  // 初始只含一个节点，后续按条件追加。
  const timeline: { title: string; time: string; desc: string }[] = [
    { title: '提交预约', time: rental.createTime, desc: '已提交租赁预约，等待支付押金' },
  ]
  // 押金状态不是 UNPAID 时，说明已支付或已退款，
  // 追加“支付押金”节点，时间复用创建时间。
  if (rental.depositStatus !== 'UNPAID') {
    timeline.push({ title: '支付押金', time: rental.createTime, desc: `押金 ${formatMoney(rental.depositAmount)} 已支付` })
  }
  // 使用中、逾期或已有实际归还时间，说明已取件。
  // 取件时间使用计划开始时间 startTime。
  if (rental.status === 'PICKED_UP' || rental.status === 'OVERDUE' || rental.actualReturnTime) {
    timeline.push({ title: '取件', time: rental.startTime, desc: `取件人 ${rental.pickupPerson || '—'} 已取件` })
  }
  // 实际归还时间存在时，追加归还节点，
  // 并展示验收结果名称（完好/损坏等）。
  if (rental.actualReturnTime) {
    timeline.push({
      title: '归还',
      time: rental.actualReturnTime,
      desc: `验收结果：${rental.damageResultName || '—'}`,
    })
  }
  // 已取消订单追加取消节点，
  // 时间优先取 cancelTime，缺失时回退创建时间；
  // 原因缺失时显示“用户主动取消”。
  if (rental.status === 'CANCELLED') {
    timeline.push({
      title: '已取消',
      time: rental.cancelTime || rental.createTime,
      desc: rental.cancelReason || '用户主动取消',
    })
  }

  // 详情布局：
  // 顶部状态徽标 + 押金状态；
  // 中部关键字段表；
  // 下部依次是归还结算（条件）、费用明细（条件）、支付流水（条件）与时间线。
  // 所有条件区块在数据缺失时完全省略，不留空白。
  // 字段表使用 dl/dt/dd 保证语义。
  // 详情渲染：
  // 顶部是状态与押金徽标；
  // 中间是关键字段表；
  // 后面是条件区块与时间线。
  // 所有区块共享同一根 div，便于样式层统一间距。
  // 不使用卡片嵌套卡片，避免视觉层级过深。
  return (
    <div>
        {/*
         * 状态徽标：租赁状态与押金状态并排。
         * 押金色调根据 PAID/REFUNDED/其他 三态映射。
         * 徽标不参与交互。
         */}
      <div className="flex" style={{ marginBottom: 16 }}>
        <span className={badgeClass(rentalStatusTone(rental.status))}>
          <span className="dot"></span>
          {rental.statusName}
        </span>
          {/*
           * 押金状态：已支付 success、已退款 info、其余 warning。
           * 文字使用后端返回的 depositStatusName。
           */}
        <span className={badgeClass(rental.depositStatus === 'PAID' ? 'success' : rental.depositStatus === 'REFUNDED' ? 'info' : 'warning')}>
          押金{rental.depositStatusName}
        </span>
      </div>
      {/*
       * 关键字段表：
       * 工具名、单号、租期、取件人、身份证（脱敏）、提醒、押金。
       * 已归还时额外展示验收结果。
       * 缺省值统一显示“—”。
       */}
      <dl className="kv">
        <dt>工具名称</dt>
        <dd>{rental.toolName}</dd>
        <dt>租赁单号</dt>
        <dd><span className="code">{rental.orderNo}</span></dd>
        <dt>租期</dt>
        <dd>{formatDateTime(rental.startTime)} → {formatDateTime(rental.endTime)}</dd>
        <dt>取件人</dt>
        <dd>{rental.pickupPerson || '—'}</dd>
        <dt>身份证</dt>
        <dd>{rental.renterIdCardMasked || '—'}</dd>
        <dt>到期提醒</dt>
        <dd>{describeRemind(rental)}</dd>
        <dt>押金</dt>
        <dd className="money accent">{formatMoney(rental.depositAmount)}</dd>
        {/*
         * 已归还时追加验收结果行：
         * 使用 Fragment 包裹 dt/dd，避免破坏 dl 结构。
         * 结果名称由后端提供，缺失时显示“—”。
         */}
        {rental.status === 'RETURNED' && (
          <>
            <dt>验收结果</dt>
            <dd>{rental.damageResultName || '—'}</dd>
          </>
        )}
      </dl>

        {/*
         * 归还结算区块：
         * 仅在已归还时展示押金、逾期费、损坏赔偿与实际退款。
         * 金额使用 formatMoney 统一格式。
         * 退款 = 押金 - 逾期费 - 损坏赔偿（后端计算，前端只展示）。
         */}
      {rental.status === 'RETURNED' && (
        <>
          <div className="sec-label">归还结算</div>
          <div className="return-box">
            <div className="rb-row"><span>押金</span><span className="num">{formatMoney(rental.depositAmount)}</span></div>
            <div className="rb-row"><span>逾期费用</span><span className="num">− {formatMoney(rental.overdueFee || 0)}</span></div>
            <div className="rb-row"><span>损坏赔偿</span><span className="num">− {formatMoney(rental.damageFee || 0)}</span></div>
            <div className="rb-row"><span>实际退款</span><span className="num">{formatMoney(rental.refundAmount || 0)}</span></div>
          </div>
        </>
      )}

        {/*
         * 费用明细区块：
         * 仅当 fees 非空时渲染；
         * 每项展示费用类型、金额、时间与原因。
         * 费用可能包含逾期费、损坏赔偿等。
         */}
      {(rental.fees ?? []).length > 0 && (
        <>
          <div className="sec-label">费用明细</div>
          <ul className="act-list">
            {(rental.fees ?? []).map((fee) => (
              <li key={fee.id}>
                <span className="act-dot" style={{ background: 'var(--warn)' }}></span>
                <div>
                  <b>
                    {fee.feeTypeName || fee.feeType}
                    <span style={{ marginLeft: 6 }}>{formatMoney(fee.amount)}</span>
                  </b>
                  <span className="tiny">
                    {formatDateTime(fee.createTime)}
                    {fee.reason ? ` · ${fee.reason}` : ''}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}

        {/*
         * 支付与退款流水区块：
         * 仅当 payments 非空时渲染；
         * 每项展示记录类型、金额、时间与备注。
         * 与费用明细分开，便于对账。
         */}
      {(rental.payments ?? []).length > 0 && (
        <>
          <div className="sec-label">支付与退款流水</div>
          <ul className="act-list">
            {(rental.payments ?? []).map((payment) => (
              <li key={payment.id}>
                <span className="act-dot" style={{ background: 'var(--info)' }}></span>
                <div>
                  <b>
                    {payment.recordTypeName || payment.recordType}
                    <span style={{ marginLeft: 6 }}>{formatMoney(payment.amount)}</span>
                  </b>
                  <span className="tiny">
                    {formatDateTime(payment.createTime)}
                    {payment.remark ? ` · ${payment.remark}` : ''}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}

      {/*
       * 流转时间线：
       * 使用 timeline 数组渲染，最后一个节点不显示连接线（样式层控制）。
       * 每个节点包含标题、时间与描述。
       * 时间线只读，不可点击。
       */}
      <div className="sec-label">流转记录</div>
      <ul className="timeline">
        {timeline.map((item, index) => (
          <li key={index} className={index < timeline.length - 1 ? 'done' : ''}>
            <div className="tl-t">{item.title}</div>
            <div className="tl-time">{formatDateTime(item.time)}</div>
            <div className="tl-d">{item.desc}</div>
          </li>
        ))}
      </ul>
    </div>
  )
}

const PAGE_SIZE = 10

/**
 * 我的租赁页面。
 *
 * @returns 头部、搜索/筛选、租赁列表/骨架/空状态、分页与三个弹窗。
 *
 * 副作用：
 * - 列表与详情查询；
 * - 支付/取消 mutation，成功后失效多个查询；
 * - 支付/取消弹窗的本地状态。
 *
 * 状态说明：
 * - status/keyword/page 共同构成列表查询参数；
 * - detailId/payTarget/cancelTarget 分别控制三个弹窗；
 * - payMethod/cancelReason 是弹窗内的受控输入。
 */
  // RentalsPage 是路由页面组件：
  // 负责查询参数、弹窗状态与操作 mutation。
  // 不直接渲染租赁详情，详情交给 RentalDetail。
  // 不负责报修提交，只提供跳转入口。
  // 所有操作成功后统一调用 invalidate()。
  // 页面数据以服务端为准，不做本地乐观更新。
  // 筛选/搜索/分页共同决定列表查询。
  // 三个弹窗互斥打开，避免层叠。
  // 组件卸载后由 React Query 回收缓存。
  // 页面不持久化筛选条件，刷新后回到默认状态。
  // 该页面位于 RequireAuth 之下，需要登录。
  // 错误提示统一由 http 拦截器负责。
export default function RentalsPage() {
  const navigate = useNavigate()
  // queryClient 用于在支付/取消成功后失效缓存。
  // 不直接调用 refetch，避免在弹窗关闭前触发闪烁。
  // 失效后由正在使用的查询自行重新请求。
  // 该引用在组件生命周期内稳定。
  const queryClient = useQueryClient()
  // 状态筛选：空字符串代表全部。
  // 切换时重置页码到第一页。
  // 该状态直接进入服务端查询参数，不是本地过滤。
  // 状态筛选：
  // 空字符串表示“全部”；
  // 其余值直接作为服务端查询参数。
  // 切换时重置页码，避免越界。
  // 标签选中态由该值比较决定。
  // 初始为“全部”，展示所有租赁。
  // 该状态不写入 URL，刷新后恢复全部。
  // status 的类型限定为 '' | RentalStatus，
  // 空串专门表示“全部”，避免使用 null 造成模板判断复杂。
  // 该值同时用于标签选中态与服务端查询参数。
  const [status, setStatus] = useState<'' | RentalStatus>('')
  // 搜索输入框的即时值（未提交）。
  // 输入时同步更新 appliedKeyword，实现“边输边搜”。
  // 搜索输入框即时值：
  // 每次输入都会同步 appliedKeyword，实现边输边搜。
  // 与 appliedKeyword 分离便于未来加入防抖。
  // 不做本地缓存，刷新后清空。
  // keyword 只反映输入框当前文本，
  // 不直接参与查询，避免每次按键都请求服务端。
  // 与 appliedKeyword 的同步发生在输入事件中。
  const [keyword, setKeyword] = useState('')
  // 实际提交给服务端的搜索词。
  // 与 keyword 分离，便于后续加入防抖或“回车才搜索”。
  // 实际提交的搜索词：
  // 由输入事件或表单 submit 更新。
  // 进入服务端查询参数，变化时页码重置为 1。
  // 空字符串转换为 undefined，避免后端误判。
  // appliedKeyword 是真正进入查询的值，
  // 由输入事件或表单提交更新。
  // 清空输入时同步清空该值。
  const [appliedKeyword, setAppliedKeyword] = useState('')
  // 当前页码，从 1 开始。
  // 筛选或搜索变化时重置为 1。
  // 当前页码：
  // 从 1 开始，作为列表查询参数。
  // 切换状态/搜索时重置为 1。
  // 分页按钮直接修改该值。
  // page 是服务端分页页码，
  // 与本地数组索引无关，从 1 开始。
  // 筛选、搜索、状态切换都会重置为 1。
  const [page, setPage] = useState(1)
  // 当前打开详情的租赁 id；null 表示关闭。
  // 详情查询按需启用，避免列表页预取全部详情。
  // 详情弹窗目标 id：
  // null 表示关闭；非空时启用 detailQuery。
  // 关闭只置空 id，不清除查询缓存。
  // 打开时优先展示列表项，避免空白。
  // detailId 只保存 id 而非整个对象，
  // 这样详情查询可以按需拉取最新数据。
  // 关闭弹窗仅置空 id，不取消缓存。
  const [detailId, setDetailId] = useState<number | null>(null)
  // 待取消的租赁单；非空时打开取消弹窗。
  // 保存整个对象以便展示单号与工具名。
  // 取消弹窗目标租赁单：
  // 保存整个对象以展示单号与工具名。
  // 非空时渲染取消弹窗。
  // 打开前会清空 cancelReason。
  // cancelTarget 保存待取消订单的完整对象，
  // 因为弹窗需要展示单号与工具名。
  // 取消成功后置空。
  const [cancelTarget, setCancelTarget] = useState<Rental | null>(null)
  // 取消原因输入，默认空字符串。
  // 提交时 trim，空值回退为“用户主动取消”。
  // 取消原因输入：
  // 选填，提交时 trim。
  // 空值回退为“用户主动取消”。
  // 弹窗关闭后不保留，下次打开重新输入。
  // cancelReason 是受控输入，
  // 提交时 trim；空值由后端或默认文案处理。
  // 打开取消弹窗前会显式清空。
  const [cancelReason, setCancelReason] = useState('')
  // 待支付的租赁单；非空时打开支付弹窗。
  // 保存整个对象以便展示应付押金。
  // 支付弹窗目标租赁单：
  // 保存整个对象以展示应付押金。
  // 非空时渲染支付弹窗。
  // 支付成功后置空并关闭。
  // payTarget 保存待支付订单的完整对象，
  // 用于展示应付押金与单号。
  // 支付成功后置空并关闭弹窗。
  const [payTarget, setPayTarget] = useState<Rental | null>(null)
  // 选中的支付方式 key，默认微信。
  // 仅影响 UI 高亮，后端 pay 接口不区分渠道。
  // 支付方式 key：
  // 仅影响 UI 选中态，不进入后端请求。
  // 默认微信支付。
  // 切换不会触发请求。
  // payMethod 只是 UI 选中项，
  // 不进入 pay 请求体，也不影响后端结算。
  // 默认微信支付，切换不触发网络请求。
  const [payMethod, setPayMethod] = useState('wechat')

  // 列表查询参数：
  // 页码、每页条数、可选搜索词、可选状态。
  // 使用 undefined 而不是空字符串，避免后端把空串当筛选条件。
  // 列表查询参数：
  // page/size 控制分页；
  // keyword/status 仅在非空时传入，避免后端把空串当筛选。
  // 参数对象每次渲染重建，但 React Query 会做结构化比较。
  // 该对象不包含支付方式等纯 UI 状态。
  // 修改任一参数都会命中新的 queryKey。
  // 参数顺序不影响缓存键。
  // rentalParams 是列表查询的唯一参数来源：
  // 参数变化会改变 queryKey 并触发新请求。
  // 使用 undefined 过滤空筛选，避免空串被当作条件。
  // 该对象不包含弹窗/支付方式等纯 UI 状态。
  const rentalParams = {
    page,
    size: PAGE_SIZE,
    keyword: appliedKeyword || undefined,
    status: status || undefined,
  }
  // 租赁列表查询：
  // queryKey 包含全部参数，任一变化都会命中新缓存。
  // 失败时 React Query 保留旧数据，页面不白屏。
  // 加载态驱动骨架屏；后台刷新不显示骨架。
  // 列表查询：
  // queryKey 由参数对象决定，参数变化即重新请求。
  // queryFn 直接调用 rentalsApi.list。
  // 失败时保留旧数据，页面不白屏。
  // 加载态驱动骨架屏；后台刷新不显示骨架。
  // 该查询不轮询，用户可通过操作或重新进入页面刷新。
  // 支付/取消成功后会 invalidate 该查询。
  // 分页与筛选共享同一份查询配置。
  // 不在 queryFn 内做数据转换，保持服务端形状。
  const rentalsQuery = useQuery({
    queryKey: queryKeys.rentals.list(rentalParams),
    queryFn: () => rentalsApi.list(rentalParams),
  })
  // 当前页记录；未返回时为空数组。
  // 列表与分页均基于该数组与 total。
  // 当前页记录：
  // 未返回时为空数组，便于后续 map 与统计。
  // total 另行读取，用于分页。
  // 列表为空与加载中通过 isLoading 区分。
  // list 是当前页记录的安全视图，
  // 未返回时为空数组，调用方无需判空。
  // total 单独读取，用于分页。
  const list = rentalsQuery.data?.records ?? []

  // 详情查询：
  // 仅在 detailId 非空时启用；
  // 查询结果优先于列表项，保证展示最新状态。
  // 关闭弹窗后不会立即清缓存，便于再次打开时快速展示。
  // 详情查询：
  // 仅在 detailId 非空时启用，避免无谓请求。
  // queryKey 包含 id，不同租赁互不污染。
  // 详情结果优先于列表项，保证状态最新。
  // 关闭弹窗后缓存保留，便于再次打开快速展示。
  // 失败时回退到列表项，仍可展示基本信息。
  // 该查询不参与列表分页。
  // 请求进行中由 isLoading 控制骨架。
  // 成功后不做额外转换，直接交给 RentalDetail。
  const detailQuery = useQuery({
    queryKey: queryKeys.rentals.detail(detailId),
    queryFn: () => rentalsApi.detail(detailId as number),
    enabled: detailId !== null,
  })
  // 详情数据来源：
  // 优先使用 detailQuery 的最新结果；
  // 若详情尚未返回，则回退到列表中的同 id 记录，
  // 这样弹窗打开瞬间就能展示基本信息，避免空白。
  // detailRental 优先使用详情查询结果；
  // 详情未返回时回退到列表同 id 项。
  // 这样弹窗打开瞬间即可展示基础信息。
  const detailRental = detailQuery.data ?? list.find((item) => item.id === detailId)

  /**
   * 失效租赁相关的全部查询缓存。
   *
   * 副作用：使 rentals、dashboard、tools、messages 查询失效。
   * 用途：支付/取消成功后，列表、导航角标、工具可用状态与消息提醒
   * 都可能变化，因此统一刷新，避免局部状态不一致。
   * 该方法不直接发起请求，由 React Query 在需要时重新拉取。
   */
  // invalidate 统一刷新与租赁相关的四类查询：
  // rentals：列表与详情；
  // dashboard：导航角标与统计；
  // tools：工具可用状态（支付/取消会改变占用）；
  // messages：系统可能产生新消息。
  // 支付与取消成功后都调用该方法。
  // 失效是惰性的，只有正在使用的查询会立即重新请求。
  // 不在此处 toast，提示由具体 mutation 负责。
  // 该方法不接收参数，保持调用简单。
  // 不等待刷新完成，避免阻塞弹窗关闭。
  // 若后续新增相关查询，应在此补充失效。
  function invalidate() {
    // 失效租赁列表与详情缓存。
    // 支付/取消会改变订单状态，必须刷新。
    queryClient.invalidateQueries({ queryKey: queryKeys.rentals.all })
    // 失效仪表盘缓存，更新导航角标与统计。
    // 与租赁相关的待支付/租借中数量会变化。
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
    // 失效工具缓存，因为支付/取消会影响工具占用状态。
    // 工具广场与推荐面板会重新拉取。
    queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
    // 失效消息缓存，系统可能生成支付/取消相关消息。
    // 导航未读角标随之更新。
    queryClient.invalidateQueries({ queryKey: queryKeys.messages.all })
  }

  // 支付押金 mutation：
  // mutationFn 只传租赁 id；
  // 成功后失效相关查询、关闭支付弹窗，并 toast 提示取件码已生成。
  // 失败由全局拦截器提示，弹窗保持打开以便重试。
  // 支付方式目前不影响请求体，仅作 UI 选择。
  const payMutation = useMutation({
  // 支付 mutationFn：
  // 只传租赁 id，渠道由后端决定（当前为 Mock）。
  // 返回更新后的租赁单或空，前端不依赖返回值。
  // 请求失败抛出，由全局拦截器提示。
  // 不在请求前做本地状态变更。
  // 支付方式选择仅用于 UI。
  // 重复点击由 isPending 禁用。
    // 支付请求只依赖订单 id，
    // 不传支付方式，后续接入真实渠道时再扩展。
    // 返回结果不参与本地渲染。
    mutationFn: (id: number) => rentalsApi.pay(id),
  // 支付成功回调：
  // 先失效相关查询，再关闭弹窗；
  // 最后 toast 告知取件码已生成。
  // 关闭弹窗放在失效之后，避免列表闪烁时弹窗仍在。
  // 不清空 payMethod，下次支付保留上次选择。
  // 失败时不会进入此回调。
  // 用户可在列表刷新后看到最新状态。
    // 支付成功后的统一收尾：
    // 刷新缓存、关闭弹窗、提示用户。
    // 不在此处跳转，用户可继续查看列表。
    onSuccess: () => {
      invalidate()
      setPayTarget(null)
      toast('押金支付成功', { type: 'ok', msg: '取件码已生成，请按时取件' })
    },
  })

  // 取消租赁 mutation：
  // 入参为 id 与原因；
  // 成功后失效查询、关闭弹窗、清空原因，并提示退款时效。
  // 失败时弹窗保持打开，用户可修改原因后重试。
  // 后端会校验是否在可取消时间窗内，前端不做硬拦截。
  const cancelMutation = useMutation({
  // 取消 mutationFn：
  // 入参为租赁 id 与原因。
  // 后端会校验取消时间窗与订单状态。
  // 原因可以为空字符串，后端按默认原因处理。
  // 前端不做额外校验，避免与后端规则不一致。
  // 请求失败抛出，弹窗保持打开。
  // 该操作不可撤销，但可重新下单。
    // 取消请求包含原因，便于后端记录与展示。
    // 原因长度未在前端限制，由后端校验。
    // 请求失败时弹窗保持打开。
    mutationFn: ({ id, reason }: { id: number; reason: string }) => rentalsApi.cancel(id, reason),
  // 取消成功回调：
  // 失效相关查询、关闭弹窗、清空原因；
  // toast 说明押金退款时效。
  // 清空原因避免下次打开残留旧文案。
  // 列表刷新后该订单显示为已取消。
  // 失败时不会进入此回调，原因保留供修改。
  // 不在此处导航，用户可继续浏览列表。
    // 取消成功后的统一收尾：
    // 刷新缓存、关闭弹窗、清空原因并提示退款时效。
    // 清空原因避免下次打开残留。
    onSuccess: () => {
      invalidate()
      setCancelTarget(null)
      setCancelReason('')
      toast('租赁已取消', { type: 'info', msg: '押金将在 1–3 个工作日内原路退回' })
    },
  })

  // 分页派生值：
  // total 来自服务端；pageCount 至少为 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE，兼容旧接口。
  // 总条数：来自服务端，用于分页与“共 N 条”。
  // 未返回时回退 0，分页器因此隐藏。
  // 筛选/搜索会改变该值。
  // total 来自服务端分页结果，
  // 为 0 时分页器完全不渲染。
  // 本地筛选不会改变该值。
  // total 与 pageCount 是分页的唯一派生数据，
  // 二者都来自服务端响应，不在本地累加或推测。
  // total 为 0 时分页器整体隐藏；
  // pageCount 至少为 1，避免出现“第 0 页”的非法状态。
  const total = rentalsQuery.data?.total ?? 0
  // 总页数：
  // 至少为 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE，兼容旧接口。
  // 与 page 一起控制上一页/下一页禁用。
  // pageCount 由 total/size 计算，
  // 最小为 1，保证空列表也有合法页码。
  // size 缺失时回退 PAGE_SIZE。
  const pageCount = Math.max(1, Math.ceil(total / (rentalsQuery.data?.size || PAGE_SIZE)))

  /**
   * 切换状态筛选。
   *
   * @param next 新的状态值。
   *
   * 副作用：更新 status 并把页码重置到 1。
   * 重置页码是必要的：否则可能停留在旧筛选的越界页。
   */
  // 切换状态筛选：
  // 更新 status 后立即把页码重置为 1。
  // 重置是必要的，否则可能停留在旧筛选的越界页。
  // 不触发额外请求，列表查询会因参数变化自动重新请求。
  // 不清空搜索词，允许“状态 + 关键词”组合筛选。
  // 不关闭已打开的弹窗（实际上切换时通常没有弹窗）。
  // 该函数只由标签按钮调用。
  // 不写 URL，刷新后回到全部。
  // 不做滚动复位，保持用户当前位置。
  // 不校验 next 的合法性，类型已约束。
  // 副作用仅为两次 setState。
  function selectStatus(next: '' | RentalStatus) {
    // 更新筛选值。
    // 类型由调用方保证合法。
    setStatus(next)
    // 重置页码，避免停留在旧筛选的越界页。
    // 列表查询会因参数变化自动重新请求。
    setPage(1)
  }

  /**
   * 根据租赁状态渲染行内操作按钮。
   *
   * @param rental 当前租赁单。
   * @returns 操作按钮组合；未知状态返回 null。
   *
   * 规则：
   * - 待支付：去支付 + 取消；
   * - 已预约：取件信息 + 取消；
   * - 使用中/逾期：报修（跳转 /damage?rentalId=） + 详情；
   * - 已归还：归还信息；
   * - 已取消：无操作。
   *
   * 副作用：按钮点击会打开对应弹窗或导航。
   * 取消前会清空上一次的取消原因，避免复用旧文案。
   */
  // actions 根据租赁状态返回行内操作按钮：
  // 待支付：去支付/取消；
  // 已预约：取件信息/取消；
  // 使用中或逾期：报修/详情；
  // 已归还：归还信息；
  // 已取消：无操作。
  // 所有按钮都是 type=button，避免触发表单提交。
  // 取消按钮会先清空原因再打开弹窗。
  // 报修按钮通过 query 参数携带 rentalId。
  // 详情按钮只设置 detailId。
  // 不在此处做权限判断，登录守卫已保证用户身份。
  // 不处理支付/取消的异步结果，交给 mutation。
  // 返回 Fragment 以便并列多个按钮。
  // 未知状态返回 null，不渲染空按钮。
  // 按钮尺寸统一 btn-sm，适应列表行高。
  // 图标由文字承担语义，未额外添加图标。
  // 该函数是纯渲染辅助，不持有状态。
  function actions(rental: Rental) {
    // 待支付分支：
    // 主要操作是支付押金；次要操作是取消订单。
    // 取消前清空原因，保证每次都是干净输入。
    if (rental.status === 'PENDING_PAYMENT') {
      // 待支付：主操作为支付，次操作为取消。
      // 取消前清空原因，保证输入干净。
      // 两个按钮并列，样式区分主次。
      return (
        <>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setPayTarget(rental)}>去支付</button>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => { setCancelReason(''); setCancelTarget(rental) }}>取消</button>
        </>
      )
    }
    // 已预约分支：
    // 主要操作是查看取件信息；仍可取消。
    // 取消是否成功由后端时间窗决定。
    if (rental.status === 'RESERVED') {
      // 已预约：主操作为查看取件信息，仍可取消。
      // 取消是否成功由后端时间窗校验。
      // 取件信息复用详情弹窗。
      return (
        <>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => setDetailId(rental.id)}>取件信息</button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => { setCancelReason(''); setCancelTarget(rental) }}>取消</button>
        </>
      )
    }
    // 使用中/逾期分支：
    // 提供“报修”入口并携带 rentalId，
    // 报修页会据此预选关联租赁。
    // 详情按钮打开只读弹窗。
    if (rental.status === 'PICKED_UP' || rental.status === 'OVERDUE') {
      // 使用中/逾期：报修入口携带 rentalId，
      // 报修页会预选该租赁；详情为只读。
      // 报修跳转会离开当前页面。
      return (
        <>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => navigate(`/damage?rentalId=${rental.id}`)}>报修</button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => setDetailId(rental.id)}>详情</button>
        </>
      )
    }
    // 已归还分支：
    // 只提供归还信息查看，不再有操作类按钮。
    if (rental.status === 'RETURNED') {
      // 已归还：只展示归还信息，不再提供操作。
      // 归还信息复用详情弹窗。
      // 该分支返回单个按钮而非 Fragment。
      return <button type="button" className="btn btn-sm btn-outline" onClick={() => setDetailId(rental.id)}>归还信息</button>
    }
    return null
  }

  // 页面骨架：
  // 头部 → 搜索条 → 状态标签 → 列表/骨架/空状态 → 分页 → 三个弹窗。
  // 弹窗放在最后，避免影响列表布局。
  // 所有区块共享同一个根 div 的纵向间距。
  // 列表与分页在 total>0 时同时出现。
  // 三个弹窗互斥由各自的目标状态控制。
  // 页面渲染：
  // 顶部头部、搜索条、状态标签、列表/骨架/空状态、分页与三个弹窗。
  // 列表区域由 isLoading 与 list.length 决定分支。
  // 弹窗放在最后，避免遮挡列表布局。
  // 所有区块在同一根 div 内纵向排列。
  // 该 return 是组件唯一输出，没有提前返回。
  // 三个弹窗各自条件渲染，互不干扰。
  // 页面不包含全局 Provider 或 Portal。
  return (
    <div>
    {/*
     * 页面头部：
     * 标题、说明与“去借工具”主操作。
     * 说明中写明逾期计费规则，减少用户误解。
     */}
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">MY RENTALS</p>
          <h1>我的租赁</h1>
          <p className="sub">管理您的预约、支付押金、跟踪租期与归还退款。逾期按 ¥5/小时 计费，封顶押金。</p>
        </div>
        <div className="actions">
        {/*
         * “去借工具”主操作：
         * 跳转到工具广场，不携带筛选参数。
         * 使用主按钮样式，突出页面唯一主行动。
         */}
          <button type="button" className="btn btn-primary" onClick={() => navigate('/tools')}>
            <Plus className="ic sm" />去借工具
          </button>
        </div>
      </div>

    {/*
     * 搜索条：
     * 输入即时更新 appliedKeyword，实现边输边搜；
     * 提交事件也更新一次，兼容回车操作。
     * 搜索变化时页码重置为 1。
     */}
      <form
        className="filterbar reveal"
        onSubmit={(event) => {
          event.preventDefault()
          // 表单提交：阻止默认刷新，提交 trim 后的关键词。
          // 提交后页码回到第一页。
          // 与输入事件共用 appliedKeyword。
          setAppliedKeyword(keyword.trim())
          setPage(1)
        }}
      >
        <div className="f-search">
          <Search className="ic" />
          <input
            className="input"
            value={keyword}
            onChange={(event) => {
              setKeyword(event.target.value)
              // 输入事件：即时更新 keyword 与 appliedKeyword，
              // 实现边输边搜；同时重置页码。
              // 不使用防抖，当前数据量下可接受。
              setAppliedKeyword(event.target.value.trim())
              setPage(1)
            }}
            placeholder="搜索租赁单号 / 工具名称 / 编码…"
          />
        </div>
        <span className="grow"></span>
        <span className="tiny">{total} 条租赁单</span>
      </form>

    {/*
     * 状态标签栏：
     * 每个标签对应一个 RentalStatus（或全部）。
     * 选中态由 status 比较决定。
     * 切换会触发服务端查询并重置页码。
     */}
      <div className="tabs reveal" style={{ marginBottom: 18 }}>
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value || 'all'}
            type="button"
            className={status === tab.value ? 'active' : ''}
            onClick={() => selectStatus(tab.value)}
          >
            {tab.label}
          </button>
        ))}
      </div>

    {/*
     * 加载态：3 条骨架卡片。
     * 骨架结构与真实租赁卡一致，减少布局跳动。
     * 仅首次加载显示。
     */}
      {rentalsQuery.isLoading ? (
        <div className="rental-list">
          {Array.from({ length: 3 }).map((_, i) => (
            <div className="panel" key={i}>
              <div className="skel" style={{ height: 18, width: '40%', marginBottom: 12 }}></div>
              <div className="skel" style={{ height: 12, width: '70%' }}></div>
            </div>
          ))}
        </div>
    // 列表态：当前页有记录。
    // 每条记录包含缩略图、主信息、状态/费用标签、押金与操作按钮。
    // 卡片左侧用状态色强调（--rc 变量）。
      ) : list.length > 0 ? (
        <div className="rental-list">
      {/*
       * 遍历租赁单：
       * 整卡不整体可点击，操作集中在右侧按钮区，
       * 避免误触进入详情。
       * key 使用租赁 id。
       */}
          {list.map((rental) => (
            <article className="rental reveal in" key={rental.id} style={{ '--rc': STATUS_COLOR[rental.status] } as React.CSSProperties}>
        {/*
         * 缩略图：有图时懒加载，失败/无图时显示工具占位图标。
         * 图片由 resolveFileUrl 处理相对地址。
         */}
              {rental.toolImageUrl ? (
                <img src={resolveFileUrl(rental.toolImageUrl)} alt={rental.toolName} loading="lazy" />
              ) : (
                <span className="thumb-ph"><Wrench className="ic" /></span>
              )}
        {/*
         * 主信息区：
         * 顶部是工具名、状态徽标与单号；
         * 中部是租期、取件人与提醒；
         * 底部是押金状态、逾期/归还标签。
         */}
              <div className="r-main">
          {/*
           * 顶部行：工具名（可截断）、状态徽标、单号。
           * 单号使用 code 样式，便于复制/核对。
           */}
                <div className="r-top">
                  <h3>{rental.toolName}</h3>
                  <span className={badgeClass(rentalStatusTone(rental.status))}>
                    <span className="dot"></span>
                    {rental.statusName}
                  </span>
                  <span className="code">{rental.orderNo}</span>
                </div>
          {/*
           * 时间与取件人信息：
           * 租期、取件人、到期提醒三项并排。
           * 图标仅作辅助，文本提供完整语义。
           */}
                <div className="r-times">
                  <span>
                    <CalendarClock className="ic" />
                    {formatDateTime(rental.startTime)} → {formatDateTime(rental.endTime)}
                  </span>
                  <span>
                    <UserRound className="ic" />
                    {rental.pickupPerson || '—'}
                  </span>
                  <span>
                    <Bell className="ic" />
                    {describeRemind(rental)}
                  </span>
                </div>
          {/*
           * 标签行：
           * 押金状态徽标；
           * 逾期时展示费用；
           * 已归还时展示验收结果与退款金额。
           */}
                <div className="r-tags">
                  <span className={badgeClass(rental.depositStatus === 'PAID' ? 'success' : rental.depositStatus === 'REFUNDED' ? 'info' : 'warning')}>
                    押金{rental.depositStatusName}
                  </span>
                  {rental.status === 'OVERDUE' && (
                    <span className="overdue-note">
                      <XCircle className="ic sm" />已逾期 · 费用 {formatMoney(rental.overdueFee || 0)}
                    </span>
                  )}
                  {rental.status === 'RETURNED' && (
                    <span className={badgeClass(rental.damageResult === 'INTACT' ? 'success' : 'warning')}>
                      {rental.damageResult === 'INTACT' ? '完好归还' : rental.damageResultName || '已归还'} · 退款 {formatMoney(rental.refundAmount || 0)}
                    </span>
                  )}
                </div>
              </div>
        {/*
         * 右侧区：
         * 押金金额（醒目）与操作按钮。
         * 操作按钮由 actions(rental) 按状态生成。
         */}
              <div className="r-side">
                <div className="r-deposit">
                  <b className="money">{formatMoney(rental.depositAmount)}</b>
                  <span>押金</span>
                </div>
                <div className="r-acts">{actions(rental)}</div>
              </div>
            </article>
          ))}
        </div>
    // 空状态：
    // 当前筛选/搜索下没有记录。
    // 提供“前往工具广场”的引导按钮。
    // 与错误状态区分，错误由全局提示。
      ) : (
        <div className="empty">
          <ClipboardList className="ic e-ic" />
          <h4>暂无相关租赁</h4>
          <p>当前筛选下没有记录，去工具广场借一件吧。</p>
          {/*
           * 空状态引导按钮：前往工具广场。
           * 与页面头部的主操作一致，方便用户直接行动。
           */}
          <button className="btn btn-primary" onClick={() => navigate('/tools')}>前往工具广场</button>
        </div>
      )}

    {/*
     * 分页器：total>0 时显示。
     * 上一页/下一页在边界禁用；
     * 页码按钮直接渲染全部页数（租赁量通常有限）。
     */}
      {total > 0 && (
        <div className="between" style={{ marginTop: 18 }}>
          <span className="tiny">共 {total} 条</span>
          <div className="pager">
            {/*
             * 上一页：第一页时禁用。
             * 点击递减 page，触发列表查询。
             */}
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            {Array.from({ length: pageCount }).map((_, index) => (
              <button
                key={index}
                type="button"
                className={page === index + 1 ? 'active' : ''}
                // 页码按钮：当前页高亮。
                // 点击直接跳转到对应页。
                onClick={() => setPage(index + 1)}
              >
                {index + 1}
              </button>
            ))}
            {/*
             * 下一页：最后一页时禁用。
             * 点击递增 page，触发列表查询。
             */}
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
          </div>
        </div>
      )}

      {/* 支付押金 */}
    {/*
     * 支付弹窗：
     * 展示应付押金、支付方式选择与确认按钮。
     * 支付方式仅影响 UI，不改变后端请求。
     * 请求进行中禁用确认按钮，防止重复支付。
     */}
      {payTarget && (
      // 遮罩：点击关闭支付弹窗。
      // 关闭不会取消订单，用户可稍后再次支付。
      // 支付遮罩：点击关闭弹窗，不取消订单。
      // 关闭后用户可稍后再次支付。
      // 弹窗状态由 payTarget 控制。
      // 遮罩层不阻止键盘操作（由基础组件处理）。
        <div className="mask show" onClick={() => setPayTarget(null)}>
        {/*
         * 弹窗主体：窄版，阻止点击冒泡。
         * 头部展示单号与工具名，避免用户选错订单。
         */}
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>支付押金</h3>
                <p className="d-sub">{payTarget.orderNo} · {payTarget.toolName}</p>
              </div>
              {/*
               * 关闭按钮：置空 payTarget。
               * 使用 X 图标 + aria-label，保证可访问。
               */}
              <button className="dlg-x" onClick={() => setPayTarget(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
          {/*
           * 应付金额区：
           * 大号金额 + 退款说明。
           * 退款说明强调“验收合格后原路退回”。
           */}
              <div className="pay-amount">
                <span>应付押金</span>
                <b>{formatMoney(payTarget.depositAmount)}</b>
                <small>归还验收合格后原路退回</small>
              </div>
              {/*
               * 支付方式列表：
               * 每个选项是一个 label，点击切换 payMethod。
               * 选中态由 active 类控制。
               * 图标颜色来自 PAY_METHODS 配置。
               */}
              <div className="sec-label">支付方式</div>
              <div className="pay-methods">
                {PAY_METHODS.map((method) => (
                  <label
                    key={method.key}
                    className={'pm' + (payMethod === method.key ? ' active' : '')}
                    onClick={() => setPayMethod(method.key)}
                  >
                    <span className="pm-ic" style={{ color: method.color }}><CreditCard className="ic" /></span>
                    <span>{method.label}</span>
                    <i className="pm-radio"></i>
                  </label>
                ))}
              </div>
            </div>
        {/*
         * 弹窗底部：
         * 取消关闭弹窗；
         * 确认支付调用 payMutation，进行中显示加载图标。
         */}
            <div className="dlg-foot">
              {/*
               * 取消按钮：关闭支付弹窗，不发起支付。
               * outline 样式，视觉权重低于确认支付。
               */}
              <button type="button" className="btn btn-outline" onClick={() => setPayTarget(null)}>取消</button>
              <button type="button" className="btn btn-primary" disabled={payMutation.isPending} onClick={() => payMutation.mutate(payTarget.id)}>
                {payMutation.isPending && <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />}
                确认支付
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 租赁详情 */}
    {/*
     * 详情弹窗：
     * 数据优先取 detailQuery，回退到列表项。
     * 加载中显示骨架；未找到显示提示。
     * 关闭后不清空 detailId 之外的本地状态。
     */}
      {detailId !== null && (
      // 遮罩：点击关闭详情。
      // 详情只读，不提供编辑操作。
      // 详情遮罩：点击关闭详情弹窗。
      // 详情只读，关闭不影响任何数据。
      // 再次打开同一租赁会复用缓存。
      // 遮罩与内容区通过冒泡控制关闭。
        <div className="mask show" onClick={() => setDetailId(null)}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>租赁详情</h3>
                <p className="d-sub">{detailRental?.orderNo ?? ''}</p>
              </div>
              {/*
               * 详情关闭按钮：置空 detailId。
               * 与底部“关闭”按钮行为一致。
               */}
              <button className="dlg-x" onClick={() => setDetailId(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              {/*
               * 详情加载中且无回退数据时显示骨架；
               * 一旦有数据（详情或列表项）立即渲染内容。
               */}
              {detailQuery.isLoading && !detailRental ? (
                <div className="skel" style={{ height: 200 }}></div>
              // 有数据时渲染 RentalDetail；
              // 组件内部按状态展示不同区块。
              ) : detailRental ? (
                <RentalDetail rental={detailRental} />
              // 兜底：详情与列表都找不到对应记录。
              // 通常发生在数据被删除或 id 非法时。
              ) : (
                <p className="muted">未找到租赁信息</p>
              )}
            </div>
            <div className="dlg-foot">
              {/*
               * 底部关闭按钮：与右上角关闭行为一致。
               * 使用 outline 样式，避免与操作按钮混淆。
               */}
              <button type="button" className="btn btn-outline" onClick={() => setDetailId(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}

      {/* 取消租赁 */}
    {/*
     * 取消弹窗：
     * 展示取消规则提示、原因输入与确认按钮。
     * 是否可取消最终由后端校验。
     * 请求进行中禁用确认按钮。
     * 弹窗主体：窄版，阻止冒泡。
     * 头部展示单号与工具名。
     */}
      {cancelTarget && (
      // 遮罩：点击关闭取消弹窗，不执行取消。
      // 关闭后保留已输入的原因？这里不保留，下次打开会清空。
      // 取消遮罩：点击关闭取消弹窗，不执行取消。
      // 已输入的原因不会保留，下次打开重新输入。
      // 关闭不改变租赁状态。
      // 遮罩点击与“再想想”按钮行为一致。
        <div className="mask show" onClick={() => setCancelTarget(null)}>
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>取消租赁</h3>
                <p className="d-sub">{cancelTarget.orderNo} · {cancelTarget.toolName}</p>
              </div>
              {/*
               * 取消弹窗关闭按钮：置空 cancelTarget。
               * 不触发 cancelMutation。
               */}
              <button className="dlg-x" onClick={() => setCancelTarget(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
          {/*
           * 规则提示：
           * 取件前 24 小时外可取消；
           * 已支付押金原路退回；
           * 具体以系统校验结果为准。
           */}
            <div className="dlg-body">
              <div className="notice-sm">
                <RotateCcw className="ic" />
                <span>取件前 24 小时外可取消；已支付押金将原路退回，具体以系统校验结果为准。</span>
              </div>
          {/*
           * 取消原因输入：
           * 选填，提交时 trim；
           * 空值会被替换为“用户主动取消”。
           */}
              <div className="field">
                <label htmlFor="cancelReason">取消原因（选填）</label>
                <textarea
                  id="cancelReason"
                  className="textarea"
                  value={cancelReason}
                  onChange={(event) => setCancelReason(event.target.value)}
                  placeholder="请说明取消租赁的原因"
                />
              </div>
            </div>
        {/*
         * 弹窗底部：
         * “再想想”关闭弹窗；
         * “确认取消”调用 cancelMutation，进行中显示加载图标。
         */}
            <div className="dlg-foot">
              {/*
               * “再想想”按钮：关闭取消弹窗。
               * 语义上比“取消”更友好，降低误操作焦虑。
               */}
              <button type="button" className="btn btn-outline" onClick={() => setCancelTarget(null)}>再想想</button>
              <button
                type="button"
                className="btn btn-danger"
                disabled={cancelMutation.isPending}
                onClick={() => cancelMutation.mutate({ id: cancelTarget.id, reason: cancelReason.trim() || '用户主动取消' })}
              >
                {cancelMutation.isPending && <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />}
                确认取消
              </button>
            </div>
          </div>
        </div>
      )}
  {/*
   * 页面渲染结束：三个弹窗各自独立，
   * 关闭任一弹窗不会影响列表数据与筛选条件。
   * 所有查询与 mutation 的状态都由 React Query 管理。
   * 支付/取消成功后通过 invalidate 驱动全局刷新。
   * 列表滚动位置在弹窗开关期间保持不变。
   * 无全局副作用，卸载后查询缓存按默认策略回收。
   * 组件结束。RentalsPage 的职责边界：
   * - 负责租赁单的展示、筛选、支付/取消入口与详情；
   * - 不负责支付渠道的真实扣款（当前为 Mock）；
   * - 不负责逾期费/损坏赔偿的计算（由后端结算）。
   * 若后续接入真实支付，应在 payMutation 前增加渠道下单流程。
   */}
    </div>
  )
}
