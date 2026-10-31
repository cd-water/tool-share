import { useEffect, useRef, useState } from 'react'
import { BookOpen, Loader2, Send, Sparkles } from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { AiSource } from '@/api/types'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/components/ui/toast'
import { cn } from '@/lib/utils'

type ChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: AiSource[]
}

const QUICK_QUESTIONS = [
  '如何预约租赁工具？',
  '押金什么时候退还？',
  '逾期费用怎么计算？',
  '工具损坏后如何报修？',
]

function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export default function AiPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const bottomRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, streaming])

  useEffect(() => () => abortRef.current?.abort(), [])

  function updateAssistant(id: string, patch: Partial<ChatMessage>) {
    setMessages((prev) =>
      prev.map((message) =>
        message.id === id ? { ...message, ...patch } : message,
      ),
    )
  }

  function send(questionText?: string) {
    const question = (questionText ?? input).trim()
    if (!question || streaming) return
    const assistantId = createId()
    setMessages((prev) => [
      ...prev,
      { id: createId(), role: 'user', content: question },
      { id: assistantId, role: 'assistant', content: '', sources: [] },
    ])
    setInput('')
    setStreaming(true)
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    aiApi
      .chatStream(
        question,
        {
          onSources: (sources) => updateAssistant(assistantId, { sources }),
          onMessage: (delta) =>
            setMessages((prev) =>
              prev.map((message) =>
                message.id === assistantId
                  ? { ...message, content: message.content + delta }
                  : message,
              ),
            ),
          onError: (message) => {
            toast.add({ title: message, type: 'error' })
            updateAssistant(assistantId, {
              content: '抱歉，AI 助手暂时无法回答，请稍后再试。',
            })
          },
        },
        controller.signal,
      )
      .finally(() => {
        if (abortRef.current === controller) abortRef.current = null
        setStreaming(false)
      })
  }

  return (
    <div>
      <PageHeader
        title="AI 助手"
        description="关于租赁规则、押金、报修等问题，可以直接向 AI 助手提问。"
      />

      <div className="flex min-h-[540px] flex-col overflow-hidden rounded-2xl border border-border bg-card">
        <div className="flex-1 space-y-5 overflow-y-auto p-4 sm:p-6">
          {messages.length === 0 && (
            <div className="mx-auto max-w-xl py-6 text-center">
              <div className="mx-auto mb-3 flex size-12 items-center justify-center rounded-2xl bg-primary/15 text-primary">
                <Sparkles className="size-6" />
              </div>
              <h2 className="text-lg font-semibold text-foreground">
                你好，我是社区工具租赁助手
              </h2>
              <p className="mt-1 text-sm text-muted-foreground">
                我会结合社区知识库回答租赁、支付、归还和报修相关问题。
              </p>
              <div className="mt-5 grid gap-2 sm:grid-cols-2">
                {QUICK_QUESTIONS.map((question) => (
                  <button
                    key={question}
                    type="button"
                    onClick={() => send(question)}
                    className="rounded-xl border border-border bg-muted/20 px-3 py-2.5 text-left text-sm text-foreground transition-colors hover:border-primary/40 hover:bg-primary/5"
                  >
                    {question}
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((message) => (
            <div
              key={message.id}
              className={cn(
                'flex gap-2.5',
                message.role === 'user' ? 'justify-end' : 'justify-start',
              )}
            >
              {message.role === 'assistant' && (
                <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/15 text-primary">
                  <Sparkles className="size-4" />
                </div>
              )}
              <div
                className={cn(
                  'max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed sm:max-w-[75%]',
                  message.role === 'user'
                    ? 'rounded-br-md bg-primary text-primary-foreground'
                    : 'rounded-bl-md border border-border bg-background text-foreground',
                )}
              >
                {message.role === 'assistant' &&
                message.content === '' &&
                streaming ? (
                  <p className="flex items-center gap-2 text-muted-foreground">
                    <Loader2 className="size-4 animate-spin" />
                    正在思考…
                  </p>
                ) : (
                  <p className="whitespace-pre-wrap">{message.content}</p>
                )}
                {message.sources && message.sources.length > 0 && (
                  <div className="mt-3 space-y-2 border-t border-border/70 pt-3">
                    <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                      <BookOpen className="size-3.5" />
                      参考来源
                    </p>
                    {message.sources.map((source, index) => (
                      <div
                        key={`${source.title}-${index}`}
                        className="rounded-lg bg-muted/40 p-2.5"
                      >
                        <p className="text-xs font-medium text-foreground">
                          {source.title}
                        </p>
                        <p className="mt-1 line-clamp-3 text-xs text-muted-foreground">
                          {source.snippet}
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>

        <div className="border-t border-border bg-muted/20 p-3 sm:p-4">
          <div className="flex items-end gap-2">
            <Textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (
                  event.key === 'Enter' &&
                  !event.shiftKey &&
                  !event.nativeEvent.isComposing
                ) {
                  event.preventDefault()
                  send()
                }
              }}
              placeholder="输入你的问题，按 Enter 发送，Shift + Enter 换行"
              className="max-h-32 min-h-11 resize-none bg-card"
              rows={1}
            />
            <Button
              type="button"
              size="icon-lg"
              disabled={streaming || !input.trim()}
              onClick={() => send()}
              aria-label="发送"
            >
              <Send />
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
