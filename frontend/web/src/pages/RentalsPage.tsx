import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CalendarClock,
  ClipboardList,
  CreditCard,
  Loader2,
  MapPin,
  RotateCcw,
  ShieldAlert,
  XCircle,
} from 'lucide-react'
import { rentalsApi } from '@/api/rentals'
import type { Rental, RentalStatus } from '@/api/types'
import { Loading } from '@/components/Loading'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyTitle,
} from '@/components/ui/empty'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/components/ui/toast'
import { formatDateTime, formatMoney } from '@/lib/format'
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

const PAGE_SIZE = 6

function ReturnInfo({ rental }: { rental: Rental }) {
  if (rental.status !== 'RETURNED') return null
  return (
    <div className="mt-3 rounded-xl border border-emerald-100 bg-emerald-50/60 p-3">
      <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-emerald-700">
        <RotateCcw className="size-3.5" />
        归还验收与退款信息
      </p>
      <dl className="grid gap-2 text-xs text-emerald-800 sm:grid-cols-2">
        <div>
          <dt className="text-emerald-600">实际归还时间</dt>
          <dd>{formatDateTime(rental.actualReturnTime)}</dd>
        </div>
        <div>
          <dt className="text-emerald-600">验收结果</dt>
          <dd>{rental.damageResultName || '—'}</dd>
        </div>
        <div>
          <dt className="text-emerald-600">逾期费</dt>
          <dd>{formatMoney(rental.overdueFee)}</dd>
        </div>
        <div>
          <dt className="text-emerald-600">损坏费</dt>
          <dd>{formatMoney(rental.damageFee)}</dd>
        </div>
        <div>
          <dt className="text-emerald-600">退还押金</dt>
          <dd className="font-semibold">{formatMoney(rental.refundAmount)}</dd>
        </div>
        <div>
          <dt className="text-emerald-600">备注</dt>
          <dd>{rental.returnRemark || '—'}</dd>
        </div>
      </dl>
    </div>
  )
}

function RentalDetail({ rental }: { rental: Rental }) {
  const rows: [string, string][] = [
    ['工具名称', rental.toolName],
    ['工具编码', rental.toolCode],
    ['租赁人', rental.renterName || '—'],
    ['联系电话', rental.renterPhone || '—'],
    ['取件人', rental.pickupPerson || '—'],
    ['开始时间', formatDateTime(rental.startTime)],
    ['结束时间', formatDateTime(rental.endTime)],
    ['押金金额', formatMoney(rental.depositAmount)],
    ['押金状态', rental.depositStatusName || '—'],
    ['创建时间', formatDateTime(rental.createTime)],
  ]

  if (rental.cancelReason) {
    rows.push(['取消原因', rental.cancelReason])
    rows.push(['取消时间', formatDateTime(rental.cancelTime)])
  }
  if (rental.actualReturnTime) {
    rows.push(['实际归还时间', formatDateTime(rental.actualReturnTime)])
  }
  if (rental.damageResultName) {
    rows.push(['验收结果', rental.damageResultName])
  }
  if (rental.overdueFee !== null) {
    rows.push(['逾期费', formatMoney(rental.overdueFee)])
  }
  if (rental.damageFee !== null) {
    rows.push(['损坏费', formatMoney(rental.damageFee)])
  }
  if (rental.refundAmount !== null) {
    rows.push(['退还押金', formatMoney(rental.refundAmount)])
  }
  if (rental.returnRemark) {
    rows.push(['归还备注', rental.returnRemark])
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={rentalStatusTone(rental.status)}>{rental.statusName}</Badge>
        <span className="font-mono text-xs text-muted-foreground">
          {rental.orderNo}
        </span>
      </div>
      <dl className="grid gap-3 rounded-xl border border-border bg-muted/20 p-4 text-sm sm:grid-cols-2">
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="mt-0.5 break-all text-foreground">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

export default function RentalsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<'' | RentalStatus>('')
  const [page, setPage] = useState(1)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [cancelTarget, setCancelTarget] = useState<Rental | null>(null)
  const [cancelReason, setCancelReason] = useState('')

  const rentalsQuery = useQuery({
    queryKey: ['rentals', { status, page }],
    queryFn: () =>
      rentalsApi.list({
        status: status || undefined,
        page,
        size: PAGE_SIZE,
      }),
  })

  const detailQuery = useQuery({
    queryKey: ['rental', detailId],
    queryFn: () => rentalsApi.detail(detailId as number),
    enabled: detailId !== null,
  })

  const detailRental = detailQuery.data ?? rentalsQuery.data?.records.find(
    (item) => item.id === detailId,
  )

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['rentals'] })
    queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    queryClient.invalidateQueries({ queryKey: ['tool'] })
    queryClient.invalidateQueries({ queryKey: ['tools'] })
  }

  const payMutation = useMutation({
    mutationFn: (id: number) => rentalsApi.pay(id),
    onSuccess: () => {
      invalidate()
      toast.add({ title: '押金支付成功，预约已生效', type: 'success' })
    },
  })

  const cancelMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      rentalsApi.cancel(id, reason),
    onSuccess: () => {
      invalidate()
      setCancelTarget(null)
      setCancelReason('')
      toast.add({ title: '预约已取消，退款将按规则处理', type: 'success' })
    },
  })

  function changeStatus(next: '' | RentalStatus) {
    setStatus(next)
    setPage(1)
  }

  function handleCancel() {
    if (!cancelTarget) return
    const reason = cancelReason.trim()
    if (!reason) {
      toast.add({ title: '请填写取消原因', type: 'warning' })
      return
    }
    cancelMutation.mutate({ id: cancelTarget.id, reason })
  }

  return (
    <div>
      <PageHeader
        title="我的租赁"
        description="支付押金、取消预约，并查看归还验收与退款结果。"
      />

      <div className="mb-5 flex gap-1 overflow-x-auto rounded-xl border border-border bg-card p-1">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value || 'all'}
            type="button"
            onClick={() => changeStatus(tab.value)}
            className={
              status === tab.value
                ? 'shrink-0 rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground'
                : 'shrink-0 rounded-lg px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground'
            }
          >
            {tab.label}
          </button>
        ))}
      </div>

      {rentalsQuery.isLoading ? (
        <Loading />
      ) : rentalsQuery.data && rentalsQuery.data.records.length > 0 ? (
        <>
          <div className="space-y-4">
            {rentalsQuery.data.records.map((rental) => (
              <div
                key={rental.id}
                className="rounded-2xl border border-border bg-card p-4 sm:p-5"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold text-foreground">
                        {rental.toolName}
                      </h3>
                      <Badge tone={rentalStatusTone(rental.status)}>
                        {rental.statusName}
                      </Badge>
                    </div>
                    <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                      {rental.orderNo} · {rental.toolCode}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="text-xs text-muted-foreground">押金</p>
                    <p className="font-semibold text-foreground">
                      {formatMoney(rental.depositAmount)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {rental.depositStatusName}
                    </p>
                  </div>
                </div>

                <div className="mt-3 grid gap-2 text-sm text-muted-foreground sm:grid-cols-2">
                  <div className="flex items-center gap-1.5">
                    <CalendarClock className="size-4 shrink-0" />
                    <span>
                      {formatDateTime(rental.startTime)} 至{' '}
                      {formatDateTime(rental.endTime)}
                    </span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <MapPin className="size-4 shrink-0" />
                    <span>取件人：{rental.pickupPerson || '—'}</span>
                  </div>
                </div>

                <ReturnInfo rental={rental} />

                <div className="mt-4 flex flex-wrap items-center gap-2">
                  {rental.status === 'PENDING_PAYMENT' && (
                    <Button
                      type="button"
                      disabled={payMutation.isPending}
                      onClick={() => payMutation.mutate(rental.id)}
                    >
                      {payMutation.isPending &&
                      payMutation.variables === rental.id ? (
                        <Loader2 className="size-4 animate-spin" />
                      ) : (
                        <CreditCard />
                      )}
                      支付押金
                    </Button>
                  )}
                  {(rental.status === 'PENDING_PAYMENT' ||
                    rental.status === 'RESERVED') && (
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => {
                        setCancelReason('')
                        setCancelTarget(rental)
                      }}
                    >
                      <XCircle />
                      取消预约
                    </Button>
                  )}
                  {(rental.status === 'PICKED_UP' ||
                    rental.status === 'OVERDUE') && (
                    <Button
                      type="button"
                      variant="secondary"
                      onClick={() =>
                        navigate(`/damage?rentalId=${rental.id}`)
                      }
                    >
                      <ShieldAlert />
                      发起报修
                    </Button>
                  )}
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => setDetailId(rental.id)}
                  >
                    查看详情
                  </Button>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-6">
            <Pagination
              page={rentalsQuery.data.page}
              size={rentalsQuery.data.size || PAGE_SIZE}
              total={rentalsQuery.data.total}
              onChange={setPage}
            />
          </div>
        </>
      ) : (
        <div className="rounded-2xl border border-dashed border-border bg-card/50 p-12">
          <Empty>
            <EmptyHeader>
              <ClipboardList className="mb-2 size-10 text-muted-foreground" />
              <EmptyTitle>暂无租赁记录</EmptyTitle>
              <EmptyDescription>
                去工具广场挑选心仪的工具，预约后即可在这里管理。
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </div>
      )}

      <Dialog
        open={detailId !== null}
        onOpenChange={(open) => {
          if (!open) setDetailId(null)
        }}
        title="租赁详情"
        size="lg"
      >
        {detailQuery.isLoading && !detailRental ? (
          <Loading />
        ) : detailRental ? (
          <RentalDetail rental={detailRental} />
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">
            未找到租赁信息
          </p>
        )}
      </Dialog>

      <Dialog
        open={cancelTarget !== null}
        onOpenChange={(open) => {
          if (!open) setCancelTarget(null)
        }}
        title="取消预约"
        description="取件前 24 小时外可取消并退款，具体以系统校验结果为准。"
        footer={
          <>
            <Button
              type="button"
              variant="outline"
              onClick={() => setCancelTarget(null)}
            >
              再想想
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={cancelMutation.isPending}
              onClick={handleCancel}
            >
              {cancelMutation.isPending && (
                <Loader2 className="size-4 animate-spin" />
              )}
              确认取消
            </Button>
          </>
        }
      >
        {cancelTarget && (
          <div className="space-y-3">
            <div className="rounded-xl bg-muted/40 p-3 text-sm">
              <p className="font-medium text-foreground">
                {cancelTarget.toolName}
              </p>
              <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                {cancelTarget.orderNo}
              </p>
            </div>
            <div>
              <Label htmlFor="cancelReason">取消原因</Label>
              <Textarea
                id="cancelReason"
                value={cancelReason}
                onChange={(event) => setCancelReason(event.target.value)}
                placeholder="请说明取消预约的原因"
                className="bg-card"
              />
            </div>
          </div>
        )}
      </Dialog>
    </div>
  )
}
