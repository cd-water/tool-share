import { useMemo, useState } from 'react'
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
import type { Message, MessageType } from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { formatDateTime, isReadStatus } from '@/lib/format'
import { messageTypeTone } from '@/lib/status'
import { toast } from '@/components/ui/toast'

const TYPE_FILTERS: { value: '' | MessageType; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'RESERVATION', label: '预约' },
  { value: 'PICKUP', label: '取件' },
  { value: 'RENT_DUE', label: '租期' },
  { value: 'OVERDUE', label: '逾期' },
  { value: 'REPAIR', label: '维修' },
  { value: 'SYSTEM', label: '系统' },
]

const MESSAGE_META: Record<
  MessageType,
  { icon: ComponentType<{ className?: string }>; fg: string; bg: string }
> = {
  RESERVATION: { icon: CalendarClock, fg: 'var(--info)', bg: 'var(--info-bg)' },
  PICKUP: { icon: Package, fg: 'var(--info)', bg: 'var(--info-bg)' },
  RENT_DUE: { icon: Clock, fg: 'var(--warn)', bg: 'var(--warn-bg)' },
  OVERDUE: { icon: TriangleAlert, fg: 'var(--bad)', bg: 'var(--bad-bg)' },
  REPAIR: { icon: Wrench, fg: 'var(--brand-2)', bg: 'var(--brand-bg)' },
  SYSTEM: { icon: Bell, fg: 'var(--ink-2)', bg: 'var(--surface-2)' },
}

const GO_MAP: Record<string, string> = {
  RESERVATION: '/rentals',
  PICKUP: '/rentals',
  RENT_DUE: '/rentals',
  OVERDUE: '/rentals',
  REPAIR: '/damage',
  SYSTEM: '/tools',
}

export default function MessagesPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [typeFilter, setTypeFilter] = useState<'' | MessageType>('')
  const [active, setActive] = useState<Message | null>(null)

  const messagesQuery = useQuery({
    queryKey: ['messages', 'all'],
    queryFn: () => messagesApi.list({ page: 1, size: 200 }),
  })

  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread-count'],
    queryFn: messagesApi.unreadCount,
  })

  const readMutation = useMutation({
    mutationFn: (id: number) => messagesApi.read(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })

  const readAllMutation = useMutation({
    mutationFn: messagesApi.readAll,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['messages'] })
      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
      toast.add({ title: '全部消息已标记为已读', type: 'success' })
    },
  })

  const all = messagesQuery.data?.records ?? []
  const unreadCount = unreadQuery.data ?? all.filter((m) => !isReadStatus(m.readStatus)).length

  const counts = useMemo(() => {
    const map: Record<string, number> = {}
    all.forEach((message) => (map[message.type] = (map[message.type] ?? 0) + 1))
    return map
  }, [all])

  const list = useMemo(
    () => all.filter((message) => !typeFilter || message.type === typeFilter),
    [all, typeFilter],
  )

  function openMessage(message: Message) {
    setActive(message)
    if (!isReadStatus(message.readStatus)) readMutation.mutate(message.id)
  }

  return (
    <div>
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">NOTIFICATIONS</p>
          <h1>消息中心</h1>
          <p className="sub">预约、取件、租期、维修与系统公告，重要提醒一目了然。当前有 {unreadCount} 条未读。</p>
        </div>
        <div className="actions">
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

      <div className="between reveal" style={{ marginBottom: 18, flexWrap: 'wrap' }}>
        <div className="tabs">
          {TYPE_FILTERS.map((item) => {
            const count = item.value ? counts[item.value] ?? 0 : all.length
            return (
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
        <span className="badge bad" style={{ display: unreadCount ? 'inline-flex' : 'none' }}>
          <span className="dot"></span>
          未读 {unreadCount} 条
        </span>
      </div>

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
      ) : list.length > 0 ? (
        <div className="msg-list card reveal">
          {list.map((message) => {
            const read = isReadStatus(message.readStatus)
            const meta = MESSAGE_META[message.type] ?? MESSAGE_META.SYSTEM
            const Icon = meta.icon
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
                <span className="m-ic" style={{ color: meta.fg, background: meta.bg }}>
                  <Icon className="ic" />
                </span>
                <div className="m-body">
                  <div className="m-top">
                    <h3>{message.title}</h3>
                    <span className={badgeClass(messageTypeTone(message.type))}>{message.typeName}</span>
                  </div>
                  <p className="m-preview">{message.content}</p>
                </div>
                <span className="m-time">{formatDateTime(message.createTime)}</span>
                <span className="m-dot"></span>
              </div>
            )
          })}
        </div>
      ) : (
        <div className="empty reveal">
          <Bell className="ic e-ic" />
          <h4>暂无相关消息</h4>
          <p>当期分类下没有消息，切换到其他分类看看。</p>
        </div>
      )}

      {active && (
        <div className="mask show" onClick={() => setActive(null)}>
          <div className="dlg narrow" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>{active.title}</h3>
                <p className="d-sub">{formatDateTime(active.createTime)}</p>
              </div>
              <button className="dlg-x" onClick={() => setActive(null)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              <div className="flex" style={{ marginBottom: 12 }}>
                <span className={badgeClass(messageTypeTone(active.type))}>
                  <span className="dot"></span>
                  {active.typeName}
                </span>
              </div>
              <p style={{ margin: 0, color: 'var(--ink-2)', lineHeight: 1.8 }}>{active.content}</p>
            </div>
            <div className="dlg-foot">
              <button type="button" className="btn btn-outline" onClick={() => setActive(null)}>关闭</button>
              <button type="button" className="btn btn-primary" onClick={() => navigate(GO_MAP[active.type] || '/tools')}>
                去处理
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
