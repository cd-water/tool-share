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

const STATUS_TABS: { value: '' | RentalStatus; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待支付' },
  { value: 'RESERVED', label: '已预约' },
  { value: 'PICKED_UP', label: '使用中' },
  { value: 'OVERDUE', label: '已逾期' },
  { value: 'RETURNED', label: '已归还' },
  { value: 'CANCELLED', label: '已取消' },
]

const STATUS_COLOR: Record<string, string> = {
  PENDING_PAYMENT: '#C77700',
  RESERVED: '#2F6D80',
  PICKED_UP: '#F7A600',
  OVERDUE: '#C93A2E',
  RETURNED: '#2E7D46',
  CANCELLED: '#9C917E',
}

const PAY_METHODS = [
  { key: 'wechat', label: '微信支付', color: '#2BA245' },
  { key: 'alipay', label: '支付宝', color: '#1677FF' },
  { key: 'balance', label: '社区余额', color: 'var(--brand-2)' },
]

function describeRemind(rental: Rental): string {
  const minutes = rental.remindBeforeMinutes
  if (minutes === null || minutes === undefined) return '未设置'
  if (minutes === 0) return '不提醒'
  const label =
    minutes % 1440 === 0
      ? `${minutes / 1440} 天`
      : minutes % 60 === 0
        ? `${minutes / 60} 小时`
        : `${minutes} 分钟`
  return rental.remindAt ? `提前 ${label}（${formatDateTime(rental.remindAt)}）` : `提前 ${label}`
}

function RentalDetail({ rental }: { rental: Rental }) {
  const timeline: { title: string; time: string; desc: string }[] = [
    { title: '提交预约', time: rental.createTime, desc: '已提交租赁预约，等待支付押金' },
  ]
  if (rental.depositStatus !== 'UNPAID') {
    timeline.push({ title: '支付押金', time: rental.createTime, desc: `押金 ${formatMoney(rental.depositAmount)} 已支付` })
  }
  if (rental.status === 'PICKED_UP' || rental.status === 'OVERDUE' || rental.actualReturnTime) {
    timeline.push({ title: '取件', time: rental.startTime, desc: `取件人 ${rental.pickupPerson || '—'} 已取件` })
  }
  if (rental.actualReturnTime) {
    timeline.push({
      title: '归还',
      time: rental.actualReturnTime,
      desc: `验收结果：${rental.damageResultName || '—'}`,
    })
  }
  if (rental.status === 'CANCELLED') {
    timeline.push({
      title: '已取消',
      time: rental.cancelTime || rental.createTime,
      desc: rental.cancelReason || '用户主动取消',
    })
  }

  return (
    <div>
      <div className="flex" style={{ marginBottom: 16 }}>
        <span className={badgeClass(rentalStatusTone(rental.status))}>
          <span className="dot"></span>
          {rental.statusName}
        </span>
        <span className={badgeClass(rental.depositStatus === 'PAID' ? 'success' : rental.depositStatus === 'REFUNDED' ? 'info' : 'warning')}>
          押金{rental.depositStatusName}
        </span>
      </div>
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
        {rental.status === 'RETURNED' && (
          <>
            <dt>验收结果</dt>
            <dd>{rental.damageResultName || '—'}</dd>
          </>
        )}
      </dl>

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

export default function RentalsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<'' | RentalStatus>('')
  const [page, setPage] = useState(1)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [cancelTarget, setCancelTarget] = useState<Rental | null>(null)
  const [cancelReason, setCancelReason] = useState('')
  const [payTarget, setPayTarget] = useState<Rental | null>(null)
  const [payMethod, setPayMethod] = useState('wechat')

  const rentalParams = { page, size: PAGE_SIZE, status: status || undefined }
  const rentalsQuery = useQuery({
    queryKey: queryKeys.rentals.list(rentalParams),
    queryFn: () => rentalsApi.list(rentalParams),
  })
  const list = rentalsQuery.data?.records ?? []

  const detailQuery = useQuery({
    queryKey: queryKeys.rentals.detail(detailId),
    queryFn: () => rentalsApi.detail(detailId as number),
    enabled: detailId !== null,
  })
  const detailRental = detailQuery.data ?? list.find((item) => item.id === detailId)

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: queryKeys.rentals.all })
    queryClient.invalidateQueries({ queryKey: queryKeys.dashboard })
    queryClient.invalidateQueries({ queryKey: queryKeys.tools.all })
    queryClient.invalidateQueries({ queryKey: queryKeys.messages.all })
  }

  const payMutation = useMutation({
    mutationFn: (id: number) => rentalsApi.pay(id),
    onSuccess: () => {
      invalidate()
      setPayTarget(null)
      toast('押金支付成功', { type: 'ok', msg: '取件码已生成，请按时取件' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => rentalsApi.cancel(id, reason),
    onSuccess: () => {
      invalidate()
      setCancelTarget(null)
      setCancelReason('')
      toast('租赁已取消', { type: 'info', msg: '押金将在 1–3 个工作日内原路退回' })
    },
  })

  const total = rentalsQuery.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / (rentalsQuery.data?.size || PAGE_SIZE)))

  function selectStatus(next: '' | RentalStatus) {
    setStatus(next)
    setPage(1)
  }

  function actions(rental: Rental) {
    if (rental.status === 'PENDING_PAYMENT') {
      return (
        <>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setPayTarget(rental)}>去支付</button>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => { setCancelReason(''); setCancelTarget(rental) }}>取消</button>
        </>
      )
    }
    if (rental.status === 'RESERVED') {
      return (
        <>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => setDetailId(rental.id)}>取件信息</button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => { setCancelReason(''); setCancelTarget(rental) }}>取消</button>
        </>
      )
    }
    if (rental.status === 'PICKED_UP' || rental.status === 'OVERDUE') {
      return (
        <>
          <button type="button" className="btn btn-sm btn-outline" onClick={() => navigate(`/damage?rentalId=${rental.id}`)}>报修</button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => setDetailId(rental.id)}>详情</button>
        </>
      )
    }
    if (rental.status === 'RETURNED') {
      return <button type="button" className="btn btn-sm btn-outline" onClick={() => setDetailId(rental.id)}>归还信息</button>
    }
    return null
  }

  return (
    <div>
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">MY RENTALS</p>
          <h1>我的租赁</h1>
          <p className="sub">管理您的预约、支付押金、跟踪租期与归还退款。逾期按 ¥5/小时 计费，封顶押金。</p>
        </div>
        <div className="actions">
          <button type="button" className="btn btn-primary" onClick={() => navigate('/tools')}>
            <Plus className="ic sm" />去借工具
          </button>
        </div>
      </div>

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

      {rentalsQuery.isLoading ? (
        <div className="rental-list">
          {Array.from({ length: 3 }).map((_, i) => (
            <div className="panel" key={i}>
              <div className="skel" style={{ height: 18, width: '40%', marginBottom: 12 }}></div>
              <div className="skel" style={{ height: 12, width: '70%' }}></div>
            </div>
          ))}
        </div>
      ) : list.length > 0 ? (
        <div className="rental-list">
          {list.map((rental) => (
            <article className="rental reveal in" key={rental.id} style={{ '--rc': STATUS_COLOR[rental.status] } as React.CSSProperties}>
              {rental.toolImageUrl ? (
                <img src={resolveFileUrl(rental.toolImageUrl)} alt={rental.toolName} loading="lazy" />
              ) : (
                <span className="thumb-ph"><Wrench className="ic" /></span>
              )}
              <div className="r-main">
                <div className="r-top">
                  <h3>{rental.toolName}</h3>
                  <span className={badgeClass(rentalStatusTone(rental.status))}>
                    <span className="dot"></span>
                    {rental.statusName}
                  </span>
                  <span className="code">{rental.orderNo}</span>
                </div>
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
      ) : (
        <div className="empty">
          <ClipboardList className="ic e-ic" />
          <h4>暂无相关租赁</h4>
          <p>当前筛选下没有记录，去工具广场借一件吧。</p>
          <button className="btn btn-primary" onClick={() => navigate('/tools')}>前往工具广场</button>
        </div>
      )}

      {total > 0 && (
        <div className="between" style={{ marginTop: 18 }}>
          <span className="tiny">共 {total} 条</span>
          <div className="pager">
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            {Array.from({ length: pageCount }).map((_, index) => (
              <button
                key={index}
                type="button"
                className={page === index + 1 ? 'active' : ''}
                onClick={() => setPage(index + 1)}
              >
                {index + 1}
              </button>
            ))}
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
          </div>
        </div>
      )}

      {/* 支付押金 */}
      {payTarget && (
        <div className="mask show" onClick={() => setPayTarget(null)}>
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>支付押金</h3>
                <p className="d-sub">{payTarget.orderNo} · {payTarget.toolName}</p>
              </div>
              <button className="dlg-x" onClick={() => setPayTarget(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              <div className="pay-amount">
                <span>应付押金</span>
                <b>{formatMoney(payTarget.depositAmount)}</b>
                <small>归还验收合格后原路退回</small>
              </div>
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
            <div className="dlg-foot">
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
      {detailId !== null && (
        <div className="mask show" onClick={() => setDetailId(null)}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>租赁详情</h3>
                <p className="d-sub">{detailRental?.orderNo ?? ''}</p>
              </div>
              <button className="dlg-x" onClick={() => setDetailId(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              {detailQuery.isLoading && !detailRental ? (
                <div className="skel" style={{ height: 200 }}></div>
              ) : detailRental ? (
                <RentalDetail rental={detailRental} />
              ) : (
                <p className="muted">未找到租赁信息</p>
              )}
            </div>
            <div className="dlg-foot">
              <button type="button" className="btn btn-outline" onClick={() => setDetailId(null)}>关闭</button>
            </div>
          </div>
        </div>
      )}

      {/* 取消租赁 */}
      {cancelTarget && (
        <div className="mask show" onClick={() => setCancelTarget(null)}>
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>取消租赁</h3>
                <p className="d-sub">{cancelTarget.orderNo} · {cancelTarget.toolName}</p>
              </div>
              <button className="dlg-x" onClick={() => setCancelTarget(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              <div className="notice-sm">
                <RotateCcw className="ic" />
                <span>取件前 24 小时外可取消；已支付押金将原路退回，具体以系统校验结果为准。</span>
              </div>
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
            <div className="dlg-foot">
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
    </div>
  )
}
