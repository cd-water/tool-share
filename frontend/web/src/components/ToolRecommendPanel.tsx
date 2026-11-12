import { useState } from 'react'
import {
  ArrowRight,
  Loader2,
  MapPin,
  Sparkles,
  ThumbsDown,
  ThumbsUp,
  Wrench,
} from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { ToolRecommendItem, ToolRecommendResult } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/components/ui/toast'
import { formatMoney } from '@/lib/format'

const CATEGORY_OPTIONS = [
  { value: 'WX', label: '维修工具' },
  { value: 'YY', label: '园艺工具' },
  { value: 'QJ', label: '清洁工具' },
  { value: 'QT', label: '其他' },
]

const EXAMPLES = ['墙上打孔挂窗帘', '院子里的树枝需要修剪', '想清洗阳台和地面']

function ToolThumb({ item }: { item: ToolRecommendItem }) {
  const [failed, setFailed] = useState(false)
  if (!item.imageUrl || failed) {
    return (
      <div className="flex size-full items-center justify-center bg-gradient-to-br from-secondary to-muted text-primary">
        <Wrench className="size-8" />
      </div>
    )
  }
  return (
    <img
      src={item.imageUrl}
      alt={item.name}
      loading="lazy"
      className="size-full object-cover"
      onError={() => setFailed(true)}
    />
  )
}

export function ToolRecommendPanel({
  onSelectTool,
}: {
  onSelectTool?: (toolId: number) => void
}) {
  const [question, setQuestion] = useState('')
  const [startTime, setStartTime] = useState('')
  const [endTime, setEndTime] = useState('')
  const [category, setCategory] = useState('')
  const [maxDeposit, setMaxDeposit] = useState('')
  const [pending, setPending] = useState(false)
  const [result, setResult] = useState<ToolRecommendResult | null>(null)
  const [feedback, setFeedback] = useState<1 | 2 | null>(null)

  async function submit() {
    const text = question.trim()
    if (!text || pending) {
      if (!text) toast.add({ title: '请先描述你想做的事', type: 'error' })
      return
    }
    if ((startTime && !endTime) || (!startTime && endTime)) {
      toast.add({ title: '请同时选择开始时间和结束时间', type: 'error' })
      return
    }
    if (startTime && endTime && new Date(endTime) <= new Date(startTime)) {
      toast.add({ title: '结束时间需晚于开始时间', type: 'error' })
      return
    }
    const deposit = maxDeposit.trim() === '' ? undefined : Number(maxDeposit)
    if (deposit !== undefined && (!Number.isFinite(deposit) || deposit < 0)) {
      toast.add({ title: '押金上限需为不小于 0 的数字', type: 'error' })
      return
    }

    setPending(true)
    try {
      const data = await aiApi.recommend({
        question: text,
        startTime: startTime || undefined,
        endTime: endTime || undefined,
        category: category ? (category as 'WX' | 'YY' | 'QJ' | 'QT') : undefined,
        maxDeposit: deposit,
        limit: 5,
      })
      setResult(data)
      setFeedback(null)
    } catch {
      // http 拦截器已提示错误
    } finally {
      setPending(false)
    }
  }

  function handleSelect(item: ToolRecommendItem) {
    if (result?.recommendationId) {
      void aiApi
        .recommendClick(result.recommendationId, item.toolId)
        .catch(() => undefined)
    }
    onSelectTool?.(item.toolId)
  }

  async function sendFeedback(value: 1 | 2) {
    if (!result?.recommendationId || feedback) return
    try {
      await aiApi.recommendFeedback(result.recommendationId, value)
      setFeedback(value)
      toast.add({
        title: value === 1 ? '感谢反馈' : '已记录，我们会继续优化推荐',
        type: 'success',
      })
    } catch {
      // http 拦截器已提示错误
    }
  }

  return (
    <div className="space-y-5">
      <div className="rounded-2xl border border-border bg-card p-4 sm:p-5">
        <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-foreground">
          <span className="flex size-7 items-center justify-center rounded-lg bg-primary/15 text-primary">
            <Sparkles className="size-4" />
          </span>
          描述你想做的事，AI 帮你选工具
        </div>
        <Textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={500}
          rows={3}
          placeholder="例如：家里墙面想打几个孔挂窗帘，希望工具轻便、押金不超过 300 元"
          className="bg-card"
        />
        <div className="mt-1 text-right text-xs text-muted-foreground">
          {question.length}/500
        </div>

        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <Label htmlFor="recommend-start">开始时间（可选）</Label>
            <Input
              id="recommend-start"
              type="datetime-local"
              value={startTime}
              onChange={(event) => setStartTime(event.target.value)}
              className="mt-1 h-10 bg-card"
            />
          </div>
          <div>
            <Label htmlFor="recommend-end">结束时间（可选）</Label>
            <Input
              id="recommend-end"
              type="datetime-local"
              value={endTime}
              onChange={(event) => setEndTime(event.target.value)}
              className="mt-1 h-10 bg-card"
            />
          </div>
          <div>
            <Label htmlFor="recommend-category">类别（可选）</Label>
            <Select
              id="recommend-category"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              className="mt-1 h-10 bg-card"
            >
              <option value="">不限类别</option>
              {CATEGORY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </div>
          <div>
            <Label htmlFor="recommend-deposit">押金上限（可选）</Label>
            <Input
              id="recommend-deposit"
              type="number"
              min={0}
              step={10}
              value={maxDeposit}
              onChange={(event) => setMaxDeposit(event.target.value)}
              placeholder="不限"
              className="mt-1 h-10 bg-card"
            />
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">试试：</span>
            {EXAMPLES.map((example) => (
              <button
                key={example}
                type="button"
                onClick={() => setQuestion(example)}
                className="rounded-full border border-border bg-muted/20 px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                {example}
              </button>
            ))}
          </div>
          <Button type="button" disabled={pending || !question.trim()} onClick={submit}>
            {pending ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <Sparkles className="size-4" />
            )}
            {pending ? '正在挑选…' : '帮我推荐'}
          </Button>
        </div>
      </div>

      {result && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-muted/20 px-4 py-3">
            <p className="flex items-start gap-2 text-sm text-foreground">
              <Sparkles className="mt-0.5 size-4 shrink-0 text-primary" />
              {result.summary}
            </p>
            {result.degraded && (
              <Badge tone="warning">部分智能服务不可用，已按相关度推荐</Badge>
            )}
          </div>

          {result.items.length === 0 ? (
            <div className="rounded-2xl border border-dashed border-border bg-card/50 p-8">
              <Empty>
                <EmptyHeader>
                  <Wrench className="mb-2 size-9 text-muted-foreground" />
                  <EmptyTitle>暂未找到合适的可用工具</EmptyTitle>
                  <EmptyDescription>
                    可以换一种描述，或放宽类别、押金、时间条件后再试。
                  </EmptyDescription>
                </EmptyHeader>
              </Empty>
            </div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              {result.items.map((item) => (
                <div
                  key={item.toolId}
                  className="overflow-hidden rounded-xl border border-border bg-card"
                >
                  <div className="flex gap-3 p-4">
                    <div className="size-20 shrink-0 overflow-hidden rounded-lg bg-muted/40">
                      <ToolThumb item={item} />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <h3 className="truncate font-semibold text-foreground">
                            {item.name}
                          </h3>
                          <p className="truncate text-xs text-muted-foreground">
                            {item.model}
                          </p>
                        </div>
                        <span className="shrink-0 text-sm font-semibold text-foreground">
                          {formatMoney(item.deposit)}
                        </span>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center gap-1.5">
                        <Badge tone="neutral">{item.categoryName}</Badge>
                        <Badge tone="success">{item.statusName}</Badge>
                      </div>
                      <div className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
                        <MapPin className="size-3.5 shrink-0" />
                        <span className="truncate">{item.storageArea || '—'}</span>
                      </div>
                    </div>
                  </div>
                  <div className="border-t border-border/70 px-4 py-3">
                    <p className="text-xs leading-relaxed text-muted-foreground">
                      <span className="font-medium text-foreground">推荐理由：</span>
                      {item.reason}
                    </p>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="mt-2 w-full"
                      onClick={() => handleSelect(item)}
                    >
                      查看详情
                      <ArrowRight className="size-3.5" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {result.items.length > 0 && (
            <div className="flex flex-wrap items-center justify-center gap-2 pt-1 text-xs text-muted-foreground">
              <span>这些推荐有帮助吗？</span>
              <Button
                type="button"
                variant={feedback === 1 ? 'secondary' : 'ghost'}
                size="sm"
                disabled={feedback !== null}
                onClick={() => void sendFeedback(1)}
              >
                <ThumbsUp className="size-3.5" />
                有帮助
              </Button>
              <Button
                type="button"
                variant={feedback === 2 ? 'secondary' : 'ghost'}
                size="sm"
                disabled={feedback !== null}
                onClick={() => void sendFeedback(2)}
              >
                <ThumbsDown className="size-3.5" />
                没帮助
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
