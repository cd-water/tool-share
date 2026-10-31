import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  AlertTriangle,
  Camera,
  ChevronRight,
  Info,
  Loader2,
  ShieldAlert,
  Trash2,
  Wrench,
} from 'lucide-react'
import { damageApi } from '@/api/damage'
import { rentalsApi } from '@/api/rentals'
import { uploadApi } from '@/api/upload'
import type { DamageReport } from '@/api/types'
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
import { FieldError, Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/components/ui/toast'
import { formatDateTime, formatMoney } from '@/lib/format'
import { damageStatusTone } from '@/lib/status'

const PAGE_SIZE = 6

const damageSchema = z.object({
  rentalId: z.string().min(1, '请选择租赁记录'),
  damagePart: z
    .string()
    .trim()
    .min(1, '请填写损坏部位')
    .max(50, '损坏部位不超过 50 字'),
  description: z
    .string()
    .trim()
    .min(1, '请描述损坏情况')
    .max(500, '描述不超过 500 字'),
  images: z.array(z.string()).max(3, '最多上传 3 张图片'),
})

type DamageValues = z.infer<typeof damageSchema>

function ReportForm({
  defaultRentalId,
  onCancel,
  onSuccess,
}: {
  defaultRentalId?: number
  onCancel: () => void
  onSuccess: () => void
}) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)

  const rentalsQuery = useQuery({
    queryKey: ['rentals', 'damage-options'],
    queryFn: () => rentalsApi.list({ page: 1, size: 100 }),
  })

  const inProgressRentals = (rentalsQuery.data?.records ?? []).filter(
    (rental) => rental.status === 'PICKED_UP' || rental.status === 'OVERDUE',
  )

  const {
    control,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<DamageValues>({
    resolver: zodResolver(damageSchema),
    defaultValues: {
      rentalId: defaultRentalId ? String(defaultRentalId) : '',
      damagePart: '',
      description: '',
      images: [],
    },
  })

  const images = watch('images')

  const mutation = useMutation({
    mutationFn: (values: DamageValues) =>
      damageApi.create({
        rentalId: Number(values.rentalId),
        damagePart: values.damagePart.trim(),
        description: values.description.trim(),
        images: values.images,
      }),
    onSuccess,
  })

  async function handleFiles(fileList: FileList | null) {
    if (!fileList || fileList.length === 0) return
    const remaining = 3 - images.length
    if (remaining <= 0) {
      toast.add({ title: '最多上传 3 张图片', type: 'warning' })
      return
    }
    const selected = Array.from(fileList).slice(0, remaining)
    if (fileList.length > remaining) {
      toast.add({ title: `最多再上传 ${remaining} 张图片`, type: 'warning' })
    }
    if (selected.some((file) => !file.type.startsWith('image/'))) {
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    setUploading(true)
    try {
      const results = await Promise.all(selected.map((file) => uploadApi.upload(file)))
      setValue('images', [...images, ...results.map((item) => item.url)], {
        shouldValidate: true,
      })
    } catch {
      // 上传失败提示由请求拦截器统一处理
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  function removeImage(url: string) {
    setValue(
      'images',
      images.filter((item) => item !== url),
      { shouldValidate: true },
    )
  }

  return (
    <form
      className="space-y-3"
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
    >
      <div>
        <Label htmlFor="rentalId">关联租赁</Label>
        <Controller
          control={control}
          name="rentalId"
          render={({ field, fieldState }) => (
            <Select
              id="rentalId"
              value={field.value}
              onChange={field.onChange}
              aria-invalid={fieldState.invalid}
              className="h-10 bg-card"
            >
              <option value="">请选择进行中的租赁</option>
              {inProgressRentals.map((rental) => (
                <option key={rental.id} value={rental.id}>
                  {rental.toolName} · {rental.orderNo}
                </option>
              ))}
            </Select>
          )}
        />
        <FieldError>{errors.rentalId?.message}</FieldError>
        {rentalsQuery.isLoading ? (
          <p className="text-xs text-muted-foreground">正在加载租赁记录…</p>
        ) : inProgressRentals.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            当前没有已取件或逾期的租赁，取件后即可发起报修。
          </p>
        ) : null}
      </div>

      <div>
        <Label htmlFor="damagePart">损坏部位</Label>
        <Controller
          control={control}
          name="damagePart"
          render={({ field, fieldState }) => (
            <input
              id="damagePart"
              placeholder="如：钻头夹头、电源线、手柄"
              className="h-10 w-full rounded-lg border border-input bg-card px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        <FieldError>{errors.damagePart?.message}</FieldError>
      </div>

      <div>
        <Label htmlFor="description">损坏描述</Label>
        <Controller
          control={control}
          name="description"
          render={({ field, fieldState }) => (
            <Textarea
              id="description"
              placeholder="请描述损坏发生的场景、现象和影响"
              className="min-h-28 bg-card"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        <FieldError>{errors.description?.message}</FieldError>
      </div>

      <div>
        <Label>证明材料（可选，最多 3 张）</Label>
        <div className="flex flex-wrap gap-2">
          {images.map((url) => (
            <div
              key={url}
              className="group relative size-20 overflow-hidden rounded-lg border border-border"
            >
              <img src={url} alt="证明材料" className="size-full object-cover" />
              <button
                type="button"
                onClick={() => removeImage(url)}
                className="absolute top-1 right-1 rounded-full bg-black/60 p-0.5 text-white opacity-0 transition-opacity group-hover:opacity-100"
                aria-label="移除图片"
              >
                <Trash2 className="size-3.5" />
              </button>
            </div>
          ))}
          {images.length < 3 && (
            <button
              type="button"
              disabled={uploading}
              onClick={() => fileInputRef.current?.click()}
              className="flex size-20 flex-col items-center justify-center gap-1 rounded-lg border border-dashed border-border bg-muted/20 text-xs text-muted-foreground transition-colors hover:bg-muted/40 disabled:opacity-60"
            >
              {uploading ? (
                <Loader2 className="size-5 animate-spin" />
              ) : (
                <Camera className="size-5" />
              )}
              {uploading ? '上传中' : '上传图片'}
            </button>
          )}
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          className="hidden"
          onChange={(event) => handleFiles(event.target.files)}
        />
        <FieldError>{errors.images?.message}</FieldError>
      </div>

      <div className="flex justify-end gap-2 pt-2">
        <Button type="button" variant="outline" onClick={onCancel}>
          取消
        </Button>
        <Button
          type="submit"
          disabled={mutation.isPending || uploading}
        >
          {mutation.isPending && <Loader2 className="size-4 animate-spin" />}
          提交报修
        </Button>
      </div>
    </form>
  )
}

function ReportTimeline({ report }: { report: DamageReport }) {
  const steps: { title: string; time?: string | null; detail?: string | null }[] = [
    {
      title: '提交报修',
      time: report.createTime,
      detail: `${report.reporterName || '报修人'}提交了损坏报修`,
    },
  ]

  if (report.verifyTime || report.verifyResult) {
    steps.push({
      title: '核验处理',
      time: report.verifyTime,
      detail: [
        report.verifier ? `核验人：${report.verifier}` : '',
        report.verifyResult ? `核验结果：${report.verifyResult}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
    })
  }

  if (
    report.repairStartTime ||
    report.progressStatusName ||
    report.progressNote
  ) {
    steps.push({
      title: '维修进度',
      time: report.progressUpdateTime || report.repairStartTime,
      detail: [
        report.progressStatusName ? `状态：${report.progressStatusName}` : '',
        report.repairPerson ? `维修人：${report.repairPerson}` : '',
        report.progressNote || '',
      ]
        .filter(Boolean)
        .join(' · '),
    })
  }

  if (report.acceptTime || report.acceptResultName) {
    steps.push({
      title: '维修验收',
      time: report.acceptTime,
      detail: [
        report.acceptor ? `验收人：${report.acceptor}` : '',
        report.acceptResultName ? `结果：${report.acceptResultName}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
    })
  }

  return (
    <ol className="relative ml-2 border-l border-border pl-5">
      {steps.map((step, index) => (
        <li
          key={`${step.title}-${index}`}
          className={index === steps.length - 1 ? '' : 'pb-4'}
        >
          <span className="absolute -left-[5px] mt-1.5 size-2.5 rounded-full border-2 border-card bg-primary" />
          <p className="text-sm font-medium text-foreground">{step.title}</p>
          <p className="text-xs text-muted-foreground">
            {formatDateTime(step.time)}
          </p>
          {step.detail && (
            <p className="mt-1 text-sm text-foreground/80">{step.detail}</p>
          )}
        </li>
      ))}
    </ol>
  )
}

function ReportDetail({ report }: { report: DamageReport }) {
  const rows: [string, string][] = [
    ['报修单号', report.reportNo],
    ['报修人', report.reporterName || '—'],
    ['联系电话', report.reporterPhone || '—'],
    ['工具名称', report.toolName],
    ['工具编码', report.toolCode],
    ['损坏部位', report.damagePart],
    ['维修费用', report.repairCost === null ? '—' : formatMoney(report.repairCost)],
    ['验收结果', report.acceptResultName || '—'],
    ['备注', report.remark || '—'],
  ]

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={damageStatusTone(report.status)}>{report.statusName}</Badge>
        <span className="font-mono text-xs text-muted-foreground">
          {report.reportNo}
        </span>
      </div>

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">损坏描述</h4>
        <p className="rounded-xl border border-border bg-muted/20 p-3 text-sm leading-relaxed whitespace-pre-wrap text-foreground/90">
          {report.description}
        </p>
      </div>

      {report.images && report.images.length > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-semibold text-foreground">证明材料</h4>
          <div className="flex flex-wrap gap-2">
            {report.images.map((url) => (
              <a
                key={url}
                href={url}
                target="_blank"
                rel="noreferrer"
                className="size-24 overflow-hidden rounded-lg border border-border"
              >
                <img src={url} alt="证明材料" className="size-full object-cover" />
              </a>
            ))}
          </div>
        </div>
      )}

      <div>
        <h4 className="mb-2 text-sm font-semibold text-foreground">处理进度</h4>
        <ReportTimeline report={report} />
      </div>

      <dl className="grid gap-3 rounded-xl border border-border bg-card p-4 text-sm sm:grid-cols-2">
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

export default function DamagePage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const rentalIdParam = Number(searchParams.get('rentalId')) || undefined
  const [page, setPage] = useState(1)
  const [formOpen, setFormOpen] = useState(Boolean(rentalIdParam))
  const [detailId, setDetailId] = useState<number | null>(null)

  useEffect(() => {
    if (rentalIdParam) setFormOpen(true)
  }, [rentalIdParam])

  const reportsQuery = useQuery({
    queryKey: ['damage-reports', page],
    queryFn: () => damageApi.list({ page, size: PAGE_SIZE }),
  })

  const detailQuery = useQuery({
    queryKey: ['damage-report', detailId],
    queryFn: () => damageApi.detail(detailId as number),
    enabled: detailId !== null,
  })

  const detailReport =
    detailQuery.data ??
    reportsQuery.data?.records.find((item) => item.id === detailId)

  function closeForm() {
    setFormOpen(false)
    if (rentalIdParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('rentalId')
      setSearchParams(next, { replace: true })
    }
  }

  return (
    <div>
      <PageHeader
        title="损坏报修"
        description="工具使用过程中发生损坏时，可在此提交报修并跟踪处理进度。"
        action={
          <Button type="button" onClick={() => setFormOpen(true)}>
            <ShieldAlert />
            发起报修
          </Button>
        }
      />

      <div className="mb-5 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
        <Info className="mt-0.5 size-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">报修说明</p>
          <p className="text-amber-700">
            已取件或逾期的租赁可发起报修。请如实填写损坏部位与描述，并上传照片作为证明材料；提交后工作人员会核验并安排维修。
          </p>
        </div>
      </div>

      {reportsQuery.isLoading ? (
        <Loading />
      ) : reportsQuery.data && reportsQuery.data.records.length > 0 ? (
        <>
          <div className="space-y-4">
            {reportsQuery.data.records.map((report) => (
              <button
                key={report.id}
                type="button"
                onClick={() => setDetailId(report.id)}
                className="w-full rounded-2xl border border-border bg-card p-4 text-left transition-colors hover:border-primary/50 hover:bg-card/80 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none sm:p-5"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="font-semibold text-foreground">
                        {report.toolName}
                      </h3>
                      <Badge tone={damageStatusTone(report.status)}>
                        {report.statusName}
                      </Badge>
                    </div>
                    <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                      {report.reportNo} · {report.toolCode}
                    </p>
                  </div>
                  <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                </div>

                <div className="mt-3 grid gap-1 text-sm text-muted-foreground sm:grid-cols-2">
                  <div className="flex items-center gap-1.5">
                    <AlertTriangle className="size-4 shrink-0" />
                    <span>损坏部位：{report.damagePart}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <Wrench className="size-4 shrink-0" />
                    <span>提交时间：{formatDateTime(report.createTime)}</span>
                  </div>
                </div>
                <p className="mt-2 line-clamp-2 text-sm text-foreground/80">
                  {report.description}
                </p>
                {report.images && report.images.length > 0 && (
                  <div className="mt-3 flex gap-2">
                    {report.images.slice(0, 3).map((url) => (
                      <img
                        key={url}
                        src={url}
                        alt="证明材料"
                        className="size-14 rounded-lg border border-border object-cover"
                      />
                    ))}
                  </div>
                )}
              </button>
            ))}
          </div>
          <div className="mt-6">
            <Pagination
              page={reportsQuery.data.page}
              size={reportsQuery.data.size || PAGE_SIZE}
              total={reportsQuery.data.total}
              onChange={setPage}
            />
          </div>
        </>
      ) : (
        <div className="rounded-2xl border border-dashed border-border bg-card/50 p-12">
          <Empty>
            <EmptyHeader>
              <ShieldAlert className="mb-2 size-10 text-muted-foreground" />
              <EmptyTitle>暂无报修记录</EmptyTitle>
              <EmptyDescription>
                工具在租赁期间出现损坏时，可点击上方「发起报修」提交。
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        </div>
      )}

      <Dialog
        open={formOpen}
        onOpenChange={(open) => {
          if (!open) closeForm()
        }}
        title="发起损坏报修"
        description="请选择关联的进行中租赁，并填写损坏情况。"
        size="lg"
      >
        <ReportForm
          defaultRentalId={rentalIdParam}
          onCancel={closeForm}
          onSuccess={() => {
            queryClient.invalidateQueries({ queryKey: ['damage-reports'] })
            toast.add({ title: '报修已提交，请等待工作人员处理', type: 'success' })
            closeForm()
          }}
        />
      </Dialog>

      <Dialog
        open={detailId !== null}
        onOpenChange={(open) => {
          if (!open) setDetailId(null)
        }}
        title="报修详情"
        size="lg"
      >
        {detailQuery.isLoading && !detailReport ? (
          <Loading />
        ) : detailReport ? (
          <ReportDetail report={detailReport} />
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">
            未找到报修信息
          </p>
        )}
      </Dialog>
    </div>
  )
}
