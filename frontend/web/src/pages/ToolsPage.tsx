import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  CalendarClock,
  ImageIcon,
  Loader2,
  MapPin,
  Package,
  Search,
  ShieldCheck,
  Wrench,
} from 'lucide-react'
import { dashboardApi } from '@/api/dashboard'
import { rentalsApi } from '@/api/rentals'
import { toolsApi } from '@/api/tools'
import type { Rental, Tool } from '@/api/types'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Loading } from '@/components/Loading'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Input } from '@/components/ui/input'
import { FieldError, Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { toast } from '@/components/ui/toast'
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

const reservationSchema = z
  .object({
    startTime: z.string().min(1, '请选择开始时间'),
    endTime: z.string().min(1, '请选择结束时间'),
    pickupPerson: z
      .string()
      .trim()
      .min(1, '请填写取件人')
      .max(20, '取件人姓名过长'),
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
      <div className="flex size-full items-center justify-center bg-gradient-to-br from-secondary to-muted text-primary">
        <Wrench className="size-10" />
      </div>
    )
  }

  return (
    <img
      src={src}
      alt={tool.name}
      className={className}
      onError={() => setFailed(true)}
    />
  )
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
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<ReservationValues>({
    resolver: zodResolver(reservationSchema),
    defaultValues: { startTime: '', endTime: '', pickupPerson: '' },
  })

  const mutation = useMutation({
    mutationFn: (values: ReservationValues) =>
      rentalsApi.create({ toolId: tool.id, ...values }),
    onSuccess,
  })

  return (
    <form
      className="space-y-3"
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
    >
      <div className="rounded-xl border border-border bg-muted/30 p-3 text-sm">
        <div className="flex items-center justify-between gap-3">
          <span className="text-muted-foreground">工具押金</span>
          <span className="font-semibold text-foreground">
            {formatMoney(tool.deposit)}
          </span>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">
          提交预约后请在「我的租赁」中完成押金支付，取件前 24 小时外可取消并退款。
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label htmlFor="startTime">开始时间</Label>
          <Controller
            control={control}
            name="startTime"
            render={({ field, fieldState }) => (
              <Input
                id="startTime"
                type="datetime-local"
                className="h-10 bg-card"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          <FieldError>{errors.startTime?.message}</FieldError>
        </div>
        <div>
          <Label htmlFor="endTime">结束时间</Label>
          <Controller
            control={control}
            name="endTime"
            render={({ field, fieldState }) => (
              <Input
                id="endTime"
                type="datetime-local"
                className="h-10 bg-card"
                aria-invalid={fieldState.invalid}
                {...field}
              />
            )}
          />
          <FieldError>{errors.endTime?.message}</FieldError>
        </div>
      </div>

      <div>
        <Label htmlFor="pickupPerson">取件人</Label>
        <Controller
          control={control}
          name="pickupPerson"
          render={({ field, fieldState }) => (
            <Input
              id="pickupPerson"
              placeholder="请填写实际取件人姓名"
              className="h-10 bg-card"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        <FieldError>{errors.pickupPerson?.message}</FieldError>
      </div>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          返回
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
          提交预约
        </Button>
      </div>
    </form>
  )
}

function ToolDetail({
  tool,
  onReserve,
}: {
  tool: Tool
  onReserve: () => void
}) {
  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-xl border border-border bg-muted/20">
        <div className="aspect-[16/9] w-full">
          <ToolImage tool={tool} className="size-full object-cover" />
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={toolStatusTone(tool.status)}>{tool.statusName}</Badge>
        <Badge tone={damageLevelTone(tool.damageLevel)}>
          {tool.damageLevelName}
        </Badge>
        <Badge tone="neutral">{tool.categoryName}</Badge>
        <span className="text-xs text-muted-foreground">{tool.code}</span>
      </div>

      <div>
        <h3 className="text-lg font-semibold text-foreground">{tool.name}</h3>
        <p className="text-sm text-muted-foreground">{tool.model}</p>
      </div>

      <dl className="grid gap-3 rounded-xl border border-border bg-card p-4 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-xs text-muted-foreground">押金金额</dt>
          <dd className="mt-0.5 font-semibold text-foreground">
            {formatMoney(tool.deposit)}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">已租赁次数</dt>
          <dd className="mt-0.5 text-foreground">{tool.rentalCount} 次</dd>
        </div>
        <div className="sm:col-span-2">
          <dt className="text-xs text-muted-foreground">存放区域</dt>
          <dd className="mt-0.5 flex items-start gap-1.5 text-foreground">
            <MapPin className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            {tool.storageArea || '—'}
          </dd>
        </div>
      </dl>

      <div>
        <h4 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
          <ShieldCheck className="size-4 text-primary" />
          使用说明
        </h4>
        {tool.usageGuide ? (
          <div
            className="rounded-xl border border-border bg-muted/20 p-4 text-sm leading-relaxed text-foreground/90 [&_a]:text-primary [&_a]:underline [&_img]:my-2 [&_img]:max-w-full [&_img]:rounded-lg [&_li]:ml-5 [&_li]:list-disc [&_ol>li]:list-decimal [&_p]:mb-2 [&_p:last-child]:mb-0"
            dangerouslySetInnerHTML={{ __html: tool.usageGuide }}
          />
        ) : (
          <p className="text-sm text-muted-foreground">暂无使用说明</p>
        )}
      </div>

      {tool.status === 'AVAILABLE' ? (
        <Button type="button" size="lg" className="w-full" onClick={onReserve}>
          <CalendarClock />
          预约租赁
        </Button>
      ) : (
        <p className="rounded-xl border border-dashed border-border p-3 text-center text-sm text-muted-foreground">
          该工具当前{tool.statusName}，暂不可预约
        </p>
      )}
    </div>
  )
}

export default function ToolsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [appliedKeyword, setAppliedKeyword] = useState('')
  const [category, setCategory] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(1)
  const [selectedToolId, setSelectedToolId] = useState<number | null>(null)
  const [mode, setMode] = useState<'detail' | 'reserve'>('detail')

  const dashboardQuery = useQuery({
    queryKey: ['dashboard'],
    queryFn: dashboardApi.get,
  })

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

  const listTool = toolsQuery.data?.records.find(
    (item) => item.id === selectedToolId,
  )
  const activeTool = detailQuery.data ?? listTool

  function openTool(id: number) {
    setSelectedToolId(id)
    setMode('detail')
  }

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

  const stats = dashboardQuery.data

  return (
    <div>
      <PageHeader
        title="工具广场"
        description="浏览社区共享工具，预约后到服务站取用。"
      />

      <div className="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4">
        {[
          { label: '可租工具', value: stats?.availableToolCount ?? 0 },
          { label: '待支付预约', value: stats?.pendingPaymentCount ?? 0 },
          { label: '使用中', value: stats?.rentingCount ?? 0 },
          { label: '未读消息', value: stats?.unreadCount ?? 0 },
        ].map((item) => (
          <div
            key={item.label}
            className="rounded-xl border border-border bg-card px-4 py-3"
          >
            <p className="text-xs text-muted-foreground">{item.label}</p>
            <p className="mt-1 text-2xl font-semibold text-foreground">
              {item.value}
            </p>
          </div>
        ))}
      </div>

      <div className="mb-5 rounded-xl border border-border bg-card p-3">
        <form
          className="flex flex-col gap-2 md:flex-row md:items-center"
          onSubmit={(event) => {
            event.preventDefault()
            setAppliedKeyword(keyword.trim())
            setPage(1)
          }}
        >
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder="搜索工具名称、型号或编码"
              className="h-10 bg-card pl-8"
            />
          </div>
          <Select
            value={category}
            onChange={(event) => {
              setCategory(event.target.value)
              setPage(1)
            }}
            className="h-10 bg-card md:w-36"
            aria-label="工具分类"
          >
            <option value="">全部分类</option>
            {CATEGORY_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
          <Select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value)
              setPage(1)
            }}
            className="h-10 bg-card md:w-36"
            aria-label="工具状态"
          >
            <option value="">全部状态</option>
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
          <div className="flex gap-2">
            <Button type="submit" className="h-10">
              <Search />
              搜索
            </Button>
            <Button
              type="button"
              variant="outline"
              className="h-10"
              onClick={resetFilters}
            >
              重置
            </Button>
          </div>
        </form>
      </div>

      {toolsQuery.isLoading ? (
        <Loading />
      ) : toolsQuery.data && toolsQuery.data.records.length > 0 ? (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {toolsQuery.data.records.map((tool) => (
              <button
                key={tool.id}
                type="button"
                onClick={() => openTool(tool.id)}
                className="group overflow-hidden rounded-2xl border border-border bg-card text-left transition-all hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
              >
                <div className="relative aspect-[4/3] w-full overflow-hidden bg-muted/30">
                  <ToolImage
                    tool={tool}
                    className="size-full object-cover transition-transform duration-300 group-hover:scale-105"
                  />
                  <div className="absolute top-2 left-2">
                    <Badge tone={toolStatusTone(tool.status)}>
                      {tool.statusName}
                    </Badge>
                  </div>
                </div>
                <div className="space-y-2 p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <h3 className="truncate font-semibold text-foreground">
                        {tool.name}
                      </h3>
                      <p className="truncate text-xs text-muted-foreground">
                        {tool.model}
                      </p>
                    </div>
                    <span className="shrink-0 text-sm font-semibold text-foreground">
                      {formatMoney(tool.deposit)}
                    </span>
                  </div>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge tone="neutral">{tool.categoryName}</Badge>
                    <Badge tone={damageLevelTone(tool.damageLevel)}>
                      {tool.damageLevelName}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-1 text-xs text-muted-foreground">
                    <MapPin className="size-3.5 shrink-0" />
                    <span className="truncate">{tool.storageArea}</span>
                  </div>
                </div>
              </button>
            ))}
          </div>
          <div className="mt-6">
            <Pagination
              page={toolsQuery.data.page}
              size={toolsQuery.data.size || PAGE_SIZE}
              total={toolsQuery.data.total}
              onChange={setPage}
            />
          </div>
        </>
      ) : (
        <div className="rounded-2xl border border-dashed border-border bg-card/50 p-12">
          <Empty>
            <EmptyHeader>
              <Package className="mb-2 size-10 text-muted-foreground" />
              <EmptyTitle>暂无符合条件的工具</EmptyTitle>
              <EmptyDescription>
                换个关键词或分类试试，也可以重置筛选条件。
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </div>
      )}

      <Dialog
        open={selectedToolId !== null}
        onOpenChange={(open) => {
          if (!open) closeDialog()
        }}
        title={mode === 'reserve' ? '预约租赁' : activeTool?.name ?? '工具详情'}
        description={
          mode === 'reserve'
            ? '选择租赁时段并填写取件人，提交后请及时支付押金。'
            : activeTool?.code
        }
        size="lg"
      >
        {detailQuery.isLoading && !activeTool ? (
          <Loading />
        ) : activeTool ? (
          mode === 'reserve' ? (
            <ReservationForm
              tool={activeTool}
              onCancel={() => setMode('detail')}
              onSuccess={() => {
                queryClient.invalidateQueries({ queryKey: ['rentals'] })
                queryClient.invalidateQueries({ queryKey: ['dashboard'] })
                toast.add({
                  title: '预约成功，请前往我的租赁支付押金',
                  type: 'success',
                })
                closeDialog()
                navigate('/rentals')
              }}
            />
          ) : (
            <ToolDetail tool={activeTool} onReserve={() => setMode('reserve')} />
          )
        ) : (
          <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
            <ImageIcon className="size-4" />
            未找到工具信息
          </div>
        )}
      </Dialog>
    </div>
  )
}
