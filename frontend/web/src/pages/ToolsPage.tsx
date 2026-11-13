import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Bell,
  CalendarClock,
  ClipboardList,
  Loader2,
  MapPin,
  Package,
  RefreshCw,
  Search,
  Sparkles,
  Wrench,
} from 'lucide-react'
import { dashboardApi } from '@/api/dashboard'
import { rentalsApi } from '@/api/rentals'
import { toolsApi } from '@/api/tools'
import type { Rental, Tool } from '@/api/types'
import { ToolRecommendPanel } from '@/components/ToolRecommendPanel'
import { badgeClass } from '@/lib/badge'
import { formatMoney } from '@/lib/format'
import { damageLevelTone, toolStatusTone } from '@/lib/status'

const CATEGORY_OPTIONS = [
  { value: 'WX', label: '维修工具' },
  { value: 'YY', label: '园艺工具' },
  { value: 'QJ', label: '清洁工具' },
  { value: 'QT', label: '其他' },
]

const STATUS_OPTIONS = [
  { value: 'AVAILABLE', label: '可用' },
  { value: 'RENTED', label: '已租出' },
  { value: 'MAINTENANCE', label: '维修中' },
  { value: 'SCRAPPED', label: '已报废' },
]

const PAGE_SIZE = 9

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

const reservationSchema = z
  .object({
    startTime: z.string().min(1, '请选择开始时间'),
    endTime: z.string().min(1, '请选择结束时间'),
    pickupPerson: z.string().trim().min(1, '请填写取件人').max(20, '取件人姓名过长'),
    remindBeforeMinutes: z.number().int().min(0, '提醒时间不能为负数').max(10080, '最多提前 7 天提醒'),
  })
  .refine((values) => new Date(values.endTime) > new Date(values.startTime), {
    path: ['endTime'],
    message: '结束时间需晚于开始时间',
  })

type ReservationValues = z.infer<typeof reservationSchema>

function ToolImage({ tool, className }: { tool: Tool; className?: string }) {
  const [failed, setFailed] = useState(false)
  const src = tool.imageUrl || tool.imageUrls?.[0]

  if (!src || failed) {
    return (
      <div className="flex size-full items-center justify-center" style={{ background: 'var(--brand-bg)', color: 'var(--brand-2)' }}>
        <Wrench className="ic lg" />
      </div>
    )
  }

  return <img src={src} alt={tool.name} className={className} loading="lazy" onError={() => setFailed(true)} />
}

function toInputValue(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`
}

/** 原型默认值：明日 09:00 → 三天后 18:00 */
function defaultRange() {
  const start = new Date()
  start.setDate(start.getDate() + 1)
  start.setHours(9, 0, 0, 0)
  const end = new Date(start)
  end.setDate(end.getDate() + 3)
  end.setHours(18, 0, 0, 0)
  return { startTime: toInputValue(start), endTime: toInputValue(end) }
}

function ReservationForm({
  tool,
  onCancel,
  onSuccess,
}: {
  tool: Tool
  onCancel: () => void
  onSuccess: (rental: Rental) => void
}) {
  const defaults = useMemo(defaultRange, [])
  const { control, handleSubmit, formState } = useForm<ReservationValues>({
    resolver: zodResolver(reservationSchema),
    defaultValues: { ...defaults, pickupPerson: '张三', remindBeforeMinutes: 60 },
  })

  const startTimeValue = useWatch({ control, name: 'startTime' })
  const endTimeValue = useWatch({ control, name: 'endTime' })
  const remindBefore = useWatch({ control, name: 'remindBeforeMinutes' })
  const rentDays = useMemo(() => {
    const start = new Date(startTimeValue)
    const end = new Date(endTimeValue)
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end <= start) return 3
    return Math.max(1, Math.ceil((end.getTime() - start.getTime()) / 86_400_000))
  }, [startTimeValue, endTimeValue])

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

  const mutation = useMutation({
    mutationFn: (values: ReservationValues) => rentalsApi.create({ toolId: tool.id, ...values }),
    onSuccess,
  })

  return (
    <form
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
      id="reserveForm"
    >
      <div className="r-tool">
        <ToolImage tool={tool} />
        <div>
          <b>{tool.name}</b>
          <span className="tiny">
            {tool.categoryName} · {tool.storageArea || '社区服务站'}
          </span>
        </div>
      </div>
      <div className="r-summary">
        <span>
          租期约 <b className="num" style={{ fontSize: 'var(--t-base)' }}>{rentDays}</b> 天 · 归还验收后押金原路退回
        </span>
        <span>
          应付押金 <b>{formatMoney(tool.deposit)}</b>
        </span>
      </div>

      <div className="form-grid">
        <div className="field">
          <label htmlFor="startTime"><span className="req">*</span>开始时间</label>
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
        <div className="field">
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

      <div className="field">
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

      <div className="field">
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
      </div>

      <div className="dlg-foot">
        <button type="button" className="btn btn-outline" onClick={onCancel}>
          取消
        </button>
        <button type="submit" className="btn btn-primary" disabled={mutation.isPending}>
          {mutation.isPending && <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />}
          提交预约
        </button>
      </div>
    </form>
  )
}

type GuideStep = { text: string; imageUrl?: string }

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

function UsageGuide({ guide }: { guide?: string | null }) {
  const steps = parseGuideSteps(guide)
  if (!steps) {
    return guide ? (
      <div className="d-desc" dangerouslySetInnerHTML={{ __html: guide }} />
    ) : (
      <p className="d-desc">暂无使用说明</p>
    )
  }
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

function ToolDetail({ tool }: { tool: Tool }) {
  return (
    <div>
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
          <dl className="kv">
            <dt>押金金额</dt>
            <dd>{formatMoney(tool.deposit)}</dd>
            <dt>已租赁次数</dt>
            <dd>{tool.rentalCount} 次</dd>
            <dt>存放区域</dt>
            <dd>
              <MapPin className="ic sm" style={{ verticalAlign: -2, marginRight: 4 }} />
              {tool.storageArea || '—'}
            </dd>
          </dl>
        </div>
      </div>

      <div className="sec-label">使用说明</div>
      <UsageGuide guide={tool.usageGuide} />
    </div>
  )
}

/** 本周共享趋势迷你折线（原型 pulseSpark 的等价实现） */
function PulseSpark() {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    const w = canvas.clientWidth || 220
    const h = 44
    canvas.width = w * dpr
    canvas.height = h * dpr
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.scale(dpr, dpr)
    const data = [3, 5, 4, 6, 4, 7, 5, 8, 6, 9, 7, 10]
    const max = Math.max(...data)
    const min = Math.min(...data)
    const pts = data.map((v, i) => [4 + ((w - 8) * i) / (data.length - 1), h - 4 - ((h - 8) * (v - min)) / (max - min || 1)])
    const area = ctx.createLinearGradient(0, 0, 0, h)
    area.addColorStop(0, 'rgba(224,137,0,.22)')
    area.addColorStop(1, 'rgba(224,137,0,0)')
    ctx.beginPath()
    ctx.moveTo(pts[0][0], h)
    pts.forEach(([x, y]) => ctx.lineTo(x, y))
    ctx.lineTo(pts[pts.length - 1][0], h)
    ctx.closePath()
    ctx.fillStyle = area
    ctx.fill()
    ctx.beginPath()
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)))
    ctx.strokeStyle = '#E08900'
    ctx.lineWidth = 1.8
    ctx.stroke()
  }, [])
  return <canvas ref={ref} style={{ width: '100%', height: 44, display: 'block' }} />
}

export default function ToolsPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [appliedKeyword, setAppliedKeyword] = useState('')
  const [category, setCategory] = useState('')
  const [status, setStatus] = useState('')
  const [sort, setSort] = useState('default')
  const [page, setPage] = useState(1)
  const [selectedToolId, setSelectedToolId] = useState<number | null>(null)
  const [mode, setMode] = useState<'detail' | 'reserve'>('detail')
  const [recommendOpen, setRecommendOpen] = useState(false)

  const dashboardQuery = useQuery({ queryKey: ['dashboard'], queryFn: dashboardApi.get })

  const toolsQuery = useQuery({
    queryKey: ['tools', { appliedKeyword, category, status, page }],
    queryFn: () =>
      toolsApi.list({
        keyword: appliedKeyword || undefined,
        category: category || undefined,
        status: status || undefined,
        page,
        size: PAGE_SIZE,
      }),
  })

  const detailQuery = useQuery({
    queryKey: ['tool', selectedToolId],
    queryFn: () => toolsApi.detail(selectedToolId as number),
    enabled: selectedToolId !== null,
  })

  const listTool = toolsQuery.data?.records.find((item) => item.id === selectedToolId)
  const activeTool = detailQuery.data ?? listTool

  const watchQuery = useQuery({
    queryKey: ['tools', activeTool?.id, 'watch'],
    queryFn: () => toolsApi.watched(activeTool!.id),
    enabled: !!activeTool && activeTool.status !== 'AVAILABLE',
    staleTime: 5_000,
  })
  const watched = watchQuery.data?.watched ?? false
  const watchMutation = useMutation({
    mutationFn: () => (watched ? toolsApi.unwatch(activeTool!.id) : toolsApi.watch(activeTool!.id)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tools', activeTool?.id, 'watch'] }),
  })

  const records = useMemo(() => {
    const list = [...(toolsQuery.data?.records ?? [])]
    if (sort === 'hot') list.sort((a, b) => b.rentalCount - a.rentalCount)
    if (sort === 'depositAsc') list.sort((a, b) => a.deposit - b.deposit)
    if (sort === 'depositDesc') list.sort((a, b) => b.deposit - a.deposit)
    return list
  }, [toolsQuery.data, sort])

  function openTool(id: number) {
    setSelectedToolId(id)
    setMode('detail')
  }

  // AI 推荐点击后通过 ?tool=id 打开详情
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
  function closeDialog() {
    setSelectedToolId(null)
    setMode('detail')
  }
  function resetFilters() {
    setKeyword('')
    setAppliedKeyword('')
    setCategory('')
    setStatus('')
    setPage(1)
  }

  const stats = [
    { label: '可借工具', value: dashboardQuery.data?.availableToolCount ?? 0, icon: Wrench, color: 'var(--ok)', bg: 'var(--ok-bg)' },
    { label: '待支付', value: dashboardQuery.data?.pendingPaymentCount ?? 0, icon: ClipboardList, color: 'var(--warn)', bg: 'var(--warn-bg)' },
    { label: '租借中', value: dashboardQuery.data?.rentingCount ?? 0, icon: Package, color: 'var(--info)', bg: 'var(--info-bg)' },
    { label: '未读消息', value: dashboardQuery.data?.unreadCount ?? 0, icon: Bell, color: 'var(--bad)', bg: 'var(--bad-bg)' },
  ]

  const pageCount = toolsQuery.data ? Math.max(1, Math.ceil(toolsQuery.data.total / (toolsQuery.data.size || PAGE_SIZE))) : 1

  return (
    <div>
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">TOOL PLAZA</p>
          <h1>工具广场</h1>
          <p className="sub">社区工具房现有 8 类共享工具，按需预约、押金可退。点击卡片查看使用指南。</p>
        </div>
        <div className="actions">
          <button type="button" className="btn btn-outline" onClick={() => setRecommendOpen(true)}>
            <Sparkles className="ic sm" />AI 帮我选
          </button>
          <button
            type="button"
            className="btn btn-outline"
            onClick={() => {
              queryClient.invalidateQueries({ queryKey: ['tools'] })
              queryClient.invalidateQueries({ queryKey: ['dashboard'] })
            }}
          >
            <RefreshCw className="ic sm" />刷新
          </button>
          <button type="button" className="btn btn-primary" onClick={() => navigate('/rentals')}>
            <ClipboardList className="ic sm" />我的租赁
          </button>
        </div>
      </div>

      <div className="stat-band reveal">
        {stats.map((item) => {
          const Icon = item.icon
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

      <div className="pulse reveal">
        <div className="pulse-txt">
          <span className="pulse-ic">
            <ClipboardList className="ic" />
          </span>
          <div>
            <b>本周社区已共享 23 次工具</b>
            <span>邻里互助，减少重复购置约 ¥4,600，让每件工具物尽其用。</span>
          </div>
        </div>
        <div className="pulse-chart">
          <PulseSpark />
        </div>
      </div>

      <form
        className="filterbar reveal"
        onSubmit={(event) => {
          event.preventDefault()
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
              setAppliedKeyword(event.target.value.trim())
              setPage(1)
            }}
            placeholder="搜索工具名称 / 编码 / 品牌…"
          />
        </div>
        <select className="select f-item" value={category} onChange={(event) => { setCategory(event.target.value); setPage(1) }} aria-label="工具分类">
          <option value="">全部分类</option>
          {CATEGORY_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <select className="select f-item" value={status} onChange={(event) => { setStatus(event.target.value); setPage(1) }} aria-label="工具状态">
          <option value="">全部状态</option>
          {STATUS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <select className="select f-item" value={sort} onChange={(event) => setSort(event.target.value)} aria-label="排序">
          <option value="default">综合排序</option>
          <option value="hot">租借最多</option>
          <option value="depositAsc">押金从低到高</option>
          <option value="depositDesc">押金从高到低</option>
        </select>
        <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilters}>重置</button>
        <span className="grow"></span>
        <span className="tiny">{toolsQuery.data ? `共 ${toolsQuery.data.total} 件` : ''}</span>
      </form>

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
      ) : toolsQuery.data && toolsQuery.data.records.length > 0 ? (
        <>
          <div className="tool-grid">
            {records.map((tool) => (
              <div className="tool-card tilt reveal" key={tool.id} onClick={() => openTool(tool.id)} role="button" tabIndex={0}>
                <div className="tc-img">
                  <ToolImage tool={tool} className="size-full object-cover" />
                  <div className="tc-status">
                    <span className={badgeClass(toolStatusTone(tool.status))}>
                      <span className="dot"></span>
                      {tool.statusName}
                    </span>
                  </div>
                  {tool.rentalCount >= 20 && <span className="tc-hot">热门</span>}
                </div>
                <div className="tc-body">
                  <h3 className="tc-name">{tool.name}</h3>
                  <div className="tc-meta">
                    <span className="badge neutral">{tool.categoryName}</span>
                    <span className={badgeClass(damageLevelTone(tool.damageLevel))}>{tool.damageLevelName}</span>
                  </div>
                  <div className="tc-info">
                    <MapPin className="ic" />
                    <span>{tool.storageArea || '—'}</span>
                  </div>
                  <div className="tc-info">
                    <ClipboardList className="ic" />
                    <span>已租借 {tool.rentalCount} 次</span>
                  </div>
                  <div className="tc-foot">
                    <span className="tc-deposit">
                      押金 <b>{formatMoney(tool.deposit)}</b>
                    </span>
                  </div>
                  <button
                    type="button"
                    className={'btn rent-btn ' + (tool.status === 'AVAILABLE' ? 'btn-primary' : 'btn-outline')}
                    onClick={(event) => {
                      event.stopPropagation()
                      openTool(tool.id)
                      if (tool.status === 'AVAILABLE') setMode('reserve')
                    }}
                  >
                    {tool.status === 'AVAILABLE' ? '预约' : '蹲守提醒'}
                  </button>
                </div>
              </div>
            ))}
          </div>

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
        <div className="empty">
          <Package className="ic e-ic" />
          <h4>暂无符合条件的工具</h4>
          <p>换个关键词或分类试试，也可以重置筛选条件。</p>
          <button className="btn btn-outline" onClick={resetFilters}>重置筛选</button>
        </div>
      )}

      {selectedToolId !== null && (
        <div className="mask show" onClick={closeDialog}>
          <div className={'dlg' + (mode === 'detail' ? ' wide' : '')} onClick={(event) => event.stopPropagation()}>
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
            <div className="dlg-body">
              {detailQuery.isLoading && !activeTool ? (
                <div className="skel" style={{ height: 200 }}></div>
              ) : activeTool ? (
                mode === 'reserve' ? (
                  <ReservationForm
                    tool={activeTool}
                    onCancel={() => setMode('detail')}
                    onSuccess={() => {
                      queryClient.invalidateQueries({ queryKey: ['rentals'] })
                      queryClient.invalidateQueries({ queryKey: ['dashboard'] })
                      queryClient.invalidateQueries({ queryKey: ['messages'] })
                      closeDialog()
                      navigate('/rentals')
                    }}
                  />
                ) : (
                  <ToolDetail tool={activeTool} />
                )
              ) : (
                <p className="muted">未找到工具信息</p>
              )}
            </div>
            {mode === 'detail' && activeTool && (
              <div className="dlg-foot">
                <button type="button" className="btn btn-outline" onClick={closeDialog}>关闭</button>
                {activeTool.status === 'AVAILABLE' ? (
                  <button type="button" className="btn btn-primary" onClick={() => setMode('reserve')}>
                    <CalendarClock className="ic sm" />立即预约
                  </button>
                ) : (
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

      {recommendOpen && (
        <div className="mask show" onClick={() => setRecommendOpen(false)}>
          <div className="dlg wide" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>AI 帮我选工具</h3>
                <p className="d-sub">描述使用场景、时间与预算，AI 结合社区库存推荐合适的工具</p>
              </div>
              <button className="dlg-x" onClick={() => setRecommendOpen(false)} aria-label="关闭">
                <svg className="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="dlg-body">
              <ToolRecommendPanel
                onSelectTool={(id) => {
                  setRecommendOpen(false)
                  openTool(id)
                }}
              />
            </div>
            <div className="dlg-foot">
              <button type="button" className="btn btn-outline" onClick={() => setRecommendOpen(false)}>关闭</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
