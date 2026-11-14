import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Camera, Info, Loader2, ShieldAlert, Wrench, X } from 'lucide-react'
import { damageApi } from '@/api/damage'
import { rentalsApi } from '@/api/rentals'
import { uploadApi } from '@/api/upload'
import type { DamageReport } from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/lib/format'
import { damageStatusTone } from '@/lib/status'
import { toast } from '@/components/ui/toast'

const REPORT_TABS: { value: '' | DamageReport['status']; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'VERIFIED', label: '已核验' },
  { value: 'REPAIRING', label: '维修中' },
  { value: 'COMPLETED', label: '已完成' },
]

function progressBadgeClass(status?: string | null): string {
  if (status === 'COMPLETED') return 'badge ok'
  if (status === 'FAILED') return 'badge bad'
  if (status === 'WAITING_PARTS') return 'badge warn'
  return 'badge info'
}

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
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
    >
      <div className={'field' + (errors.rentalId ? ' is-err' : '')}>
        <label htmlFor="rentalId">
          <span className="req">*</span>关联租赁
        </label>
        <Controller
          control={control}
          name="rentalId"
          render={({ field, fieldState }) => (
            <select
              id="rentalId"
              className="select"
              value={field.value}
              onChange={field.onChange}
              aria-invalid={fieldState.invalid}
            >
              <option value="">请选择进行中的租赁</option>
              {inProgressRentals.map((rental) => (
                <option key={rental.id} value={rental.id}>
                  {rental.orderNo} · {rental.toolName}
                </option>
              ))}
            </select>
          )}
        />
        {errors.rentalId && <p className="err">{errors.rentalId.message}</p>}
        {rentalsQuery.isLoading ? (
          <p className="hint">正在加载租赁记录…</p>
        ) : inProgressRentals.length === 0 ? (
          <p className="hint">当前没有已取件或逾期的租赁，取件后即可发起报修。</p>
        ) : null}
      </div>

      <div className={'field' + (errors.damagePart ? ' is-err' : '')}>
        <label htmlFor="damagePart">
          <span className="req">*</span>损坏部位
        </label>
        <Controller
          control={control}
          name="damagePart"
          render={({ field, fieldState }) => (
            <input
              id="damagePart"
              className="input"
              placeholder="如：钻头夹头、电源线、手柄"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        {errors.damagePart && <p className="err">{errors.damagePart.message}</p>}
      </div>

      <div className={'field' + (errors.description ? ' is-err' : '')}>
        <label htmlFor="description">
          <span className="req">*</span>损坏描述
        </label>
        <Controller
          control={control}
          name="description"
          render={({ field, fieldState }) => (
            <textarea
              id="description"
              className="textarea"
              placeholder="请描述损坏发生的场景、现象和影响"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        {errors.description && <p className="err">{errors.description.message}</p>}
      </div>

      <div className="field">
        <label>证明材料（可选，最多 3 张）</label>
        <div className="up-grid">
          {images.map((url) => (
            <div className="up-item" key={url}>
              <img src={url} alt="证明材料" />
              <button
                type="button"
                onClick={() => removeImage(url)}
                aria-label="移除图片"
              >
                <X className="ic sm" />
              </button>
            </div>
          ))}
          {images.length < 3 && (
            <button
              type="button"
              className="up-add"
              disabled={uploading}
              onClick={() => fileInputRef.current?.click()}
            >
              {uploading ? (
                <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
              ) : (
                <Camera className="ic" />
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
          hidden
          onChange={(event) => handleFiles(event.target.files)}
        />
        <p className="hint">支持 JPG / PNG，上传后仅用于本次报修核验，最多 3 张。</p>
        {errors.images && <p className="err">{errors.images.message}</p>}
      </div>

      <div className="flex" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
        <button type="button" className="btn btn-outline" onClick={onCancel}>
          取消
        </button>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={mutation.isPending || uploading}
        >
          {mutation.isPending && (
            <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
          )}
          提交报修
        </button>
      </div>
    </form>
  )
}

function ReportTimeline({ report }: { report: DamageReport }) {
  const steps: {
    title: string
    time?: string | null
    detail?: string | null
    done?: boolean
  }[] = [
    {
      title: '提交报修',
      time: report.createTime,
      detail: `${report.reporterName || '报修人'}提交了损坏报修`,
      done: true,
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
      done: true,
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
      done: report.progressStatus === 'COMPLETED',
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
      done: true,
    })
  }

  return (
    <ul className="timeline">
      {steps.map((step, index) => (
        <li key={`${step.title}-${index}`} className={step.done ? 'done' : ''}>
          <div className="tl-t">{step.title}</div>
          <div className="tl-time">{formatDateTime(step.time)}</div>
          {step.detail && <div className="tl-d">{step.detail}</div>}
        </li>
      ))}
    </ul>
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
    <div>
      <div className="flex" style={{ marginBottom: 16 }}>
        <span className={badgeClass(damageStatusTone(report.status))}>
          <span className="dot"></span>
          {report.statusName}
        </span>
        {report.progressStatusName && (
          <span className="badge neutral">{report.progressStatusName}</span>
        )}
      </div>

      <div className="sec-label">损坏描述</div>
      <p
        style={{
          fontSize: 'var(--t-sm)',
          lineHeight: 1.7,
          color: 'var(--ink-2)',
          background: 'var(--surface-2)',
          border: '1px solid var(--line)',
          borderRadius: 11,
          padding: '13px 15px',
          margin: 0,
          whiteSpace: 'pre-wrap',
        }}
      >
        {report.description}
      </p>

      {report.images && report.images.length > 0 && (
        <>
          <div className="sec-label">证明材料</div>
          <div className="dmg-imgs">
            {report.images.map((url) => (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                <img src={url} alt="证明材料" />
              </a>
            ))}
          </div>
        </>
      )}

      <div className="sec-label">处理进度</div>
      <ReportTimeline report={report} />

      <div className="sec-label">报修信息</div>
      <dl className="kv">
        {rows.map(([label, value]) => (
          <Fragment key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </Fragment>
        ))}
      </dl>
    </div>
  )
}

export default function DamagePage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const rentalIdParam = Number(searchParams.get('rentalId')) || undefined
  const [status, setStatus] = useState<'' | DamageReport['status']>('')
  const [formOpen, setFormOpen] = useState(Boolean(rentalIdParam))
  const [detailId, setDetailId] = useState<number | null>(null)

  useEffect(() => {
    if (rentalIdParam) setFormOpen(true)
  }, [rentalIdParam])

  const reportsQuery = useQuery({
    queryKey: ['damage-reports', 'all'],
    // 与后端 PageQuery.MAX_SIZE 对齐，避免请求被静默截断
    queryFn: () => damageApi.list({ page: 1, size: 100 }),
  })

  const allReports = reportsQuery.data?.records ?? []
  const counts = useMemo(() => {
    const map: Record<string, number> = {}
    allReports.forEach((item) => (map[item.status] = (map[item.status] ?? 0) + 1))
    return map
  }, [allReports])
  const reports = useMemo(
    () => allReports.filter((item) => !status || item.status === status),
    [allReports, status],
  )

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
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">DAMAGE &amp; REPAIR</p>
          <h1>损坏报修</h1>
          <p className="sub">工具使用中发生损坏时，在此提交报修并跟踪核验、维修与验收进度。</p>
        </div>
        <div className="actions">
          <button type="button" className="btn btn-primary" onClick={() => setFormOpen(true)}>
            <ShieldAlert className="ic" />
            发起报修
          </button>
        </div>
      </div>

      <div className="notice reveal">
        <Info className="ic" />
        <div>
          <b>报修说明</b>
          <p>
            已取件或逾期的租赁可发起报修。请如实填写损坏部位与描述，并上传照片作为证明材料；提交后工作人员会核验并安排维修，维修费用将在验收后据实结算。
          </p>
        </div>
      </div>

      <div className="tabs reveal" style={{ marginBottom: 18 }}>
        {REPORT_TABS.map((tab) => (
          <button
            key={tab.value || 'all'}
            type="button"
            className={status === tab.value ? 'active' : ''}
            onClick={() => setStatus(tab.value)}
          >
            {tab.label}
            <span className="cnt">{tab.value ? counts[tab.value] ?? 0 : allReports.length}</span>
          </button>
        ))}
      </div>

      {reportsQuery.isLoading ? (
        <div className="rep-list">
          {Array.from({ length: 4 }).map((_, i) => (
            <div className="rep-card" key={i}>
              <div className="skel" style={{ height: 18, marginBottom: 12 }}></div>
              <div className="skel" style={{ height: 12, width: '70%', marginBottom: 8 }}></div>
              <div className="skel" style={{ height: 12, width: '90%' }}></div>
            </div>
          ))}
        </div>
      ) : reports.length > 0 ? (
        <div className="rep-list">
          {reports.map((report) => (
            <article
              className="rep-card reveal in"
              key={report.id}
              role="button"
              tabIndex={0}
              onClick={() => setDetailId(report.id)}
            >
              <div className="rc-top">
                {report.toolImageUrl ? (
                  <img src={resolveFileUrl(report.toolImageUrl)} alt={report.toolName} loading="lazy" />
                ) : (
                  <span className="rc-thumb-ph"><Wrench className="ic" /></span>
                )}
                <div>
                  <h3>{report.toolName}</h3>
                  <span className="code">{report.reportNo}</span>
                </div>
                <span
                  className={badgeClass(damageStatusTone(report.status))}
                  style={{ marginLeft: 'auto' }}
                >
                  <span className="dot"></span>
                  {report.statusName}
                </span>
              </div>
              <div className="rc-part">
                <Wrench className="ic" />
                损坏部位：{report.damagePart}
              </div>
              <p className="rc-desc">{report.description}</p>
              <div className="rc-foot">
                <span className="tiny">{formatDateTime(report.createTime)}</span>
                {report.progressStatusName ? (
                  <span className={progressBadgeClass(report.progressStatus)}>{report.progressStatusName}</span>
                ) : (
                  <span className="tiny">待核验</span>
                )}
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="empty">
          <ShieldAlert className="ic e-ic" />
          <h4>暂无报修记录</h4>
          <p>工具完好是最好的消息。如有损坏，点击右上角发起报修。</p>
        </div>
      )}

      {formOpen && (
        <div className="mask show" onClick={closeForm}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>发起报修</h3>
                <p className="d-sub">请选择关联的进行中租赁，并填写损坏情况</p>
              </div>
              <button className="dlg-x" onClick={closeForm} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            <div className="dlg-body">
              <ReportForm
                defaultRentalId={rentalIdParam}
                onCancel={closeForm}
                onSuccess={() => {
                  queryClient.invalidateQueries({ queryKey: ['damage-reports'] })
                  toast.add({ title: '报修已提交，请等待工作人员处理', type: 'success' })
                  closeForm()
                }}
              />
            </div>
          </div>
        </div>
      )}

      {detailId !== null && (
        <div className="mask show" onClick={() => setDetailId(null)}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>报修详情</h3>
                {detailReport?.reportNo ? (
                  <p className="d-sub">{detailReport.reportNo}</p>
                ) : null}
              </div>
              <button className="dlg-x" onClick={() => setDetailId(null)} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            <div className="dlg-body">
              {detailQuery.isLoading && !detailReport ? (
                <div className="skel" style={{ height: 200 }}></div>
              ) : detailReport ? (
                <ReportDetail report={detailReport} />
              ) : (
                <p className="muted">未找到报修信息</p>
              )}
            </div>
            <div className="dlg-foot">
              <button className="btn btn-outline" onClick={() => setDetailId(null)}>
                关闭
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
