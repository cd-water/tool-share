import { useState } from 'react'
import { MapPin, ShieldCheck, Wrench } from 'lucide-react'
import type { Tool } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { formatMoney } from '@/lib/format'
import { damageLevelTone, toolStatusTone } from '@/lib/status'

export function ToolImage({ tool, className }: { tool: Tool; className?: string }) {
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

type GuideStep = { text: string; imageUrl?: string }

/** 后台保存的图文步骤是 JSON 数组；旧数据仍是 HTML 富文本 */
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
          imageUrl:
            typeof record.imageUrl === 'string' ? record.imageUrl : undefined,
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
  return (
    <div>
      <h4 className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <ShieldCheck className="size-4 text-primary" />
        使用说明
      </h4>
      {steps ? (
        <ol className="space-y-3">
          {steps.map((step, index) => (
            <li
              key={index}
              className="rounded-xl border border-border bg-muted/20 p-4"
            >
              <div className="flex items-start gap-3">
                <span className="mt-0.5 inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1 space-y-2">
                  {step.text && (
                    <p className="text-sm leading-relaxed whitespace-pre-wrap text-foreground/90">
                      {step.text}
                    </p>
                  )}
                  {step.imageUrl && (
                    <img
                      src={step.imageUrl}
                      alt={`步骤 ${index + 1}`}
                      loading="lazy"
                      className="max-h-72 w-full rounded-lg border border-border object-cover"
                    />
                  )}
                </div>
              </div>
            </li>
          ))}
        </ol>
      ) : guide ? (
        <div
          className="rounded-xl border border-border bg-muted/20 p-4 text-sm leading-relaxed text-foreground/90 [&_a]:text-primary [&_a]:underline [&_img]:my-2 [&_img]:max-w-full [&_img]:rounded-lg [&_li]:ml-5 [&_li]:list-disc [&_ol>li]:list-decimal [&_p]:mb-2 [&_p:last-child]:mb-0"
          dangerouslySetInnerHTML={{ __html: guide }}
        />
      ) : (
        <p className="text-sm text-muted-foreground">暂无使用说明</p>
      )}
    </div>
  )
}

/** 工具详情展示（图片、状态、基本信息、使用说明），不含预约/蹲守等操作 */
export function ToolDetailView({ tool }: { tool: Tool }) {
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

      <UsageGuide guide={tool.usageGuide} />
    </div>
  )
}
