import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  BookOpen,
  CircleAlert,
  Clock,
  MessageSquare,
  Phone,
  RefreshCw,
  Send,
  Sparkles,
  Trash2,
} from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { AiSource } from '@/api/types'
import { ToolRecommendPanel } from '@/components/ToolRecommendPanel'
import { toast } from '@/lib/fx'

type ChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: AiSource[]
  /** 仅助手消息：用于失败后重试 */
  question?: string
  status?: 'streaming' | 'done' | 'error'
  error?: string
}

const QUICK_QUESTIONS = [
  '如何预约租赁工具？',
  '押金什么时候退还？',
  '逾期费用怎么计算？',
  '工具损坏后如何报修？',
]

const KB_ITEMS = ['租赁流程与预约规则', '押金支付与退还标准', '逾期与损坏计费说明', '工具使用与安全须知']

/** 服务端已有 20 秒生成超时；前端在完全无事件时再兜底，避免连接假死。 */
const STREAM_IDLE_TIMEOUT = 40_000

function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export default function AiPage() {
  const navigate = useNavigate()
  const [tab, setTab] = useState<'chat' | 'recommend'>('chat')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, streaming])

  useEffect(() => () => abortRef.current?.abort(), [])

  function updateAssistant(id: string, patch: Partial<ChatMessage>) {
    setMessages((prev) => prev.map((message) => (message.id === id ? { ...message, ...patch } : message)))
  }

  function startStream(question: string, assistantId: string) {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setStreaming(true)
    updateAssistant(assistantId, {
      content: '',
      sources: [],
      status: 'streaming',
      error: undefined,
      question,
    })

    let timer = 0
    const touch = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => {
        updateAssistant(assistantId, { status: 'error', error: '回答超时，未收到完整内容。' })
        controller.abort()
      }, STREAM_IDLE_TIMEOUT)
    }
    touch()

    aiApi
      .chatStream(
        question,
        {
          onSources: (sources) => {
            touch()
            updateAssistant(assistantId, { sources })
          },
          onMessage: (delta) => {
            touch()
            setMessages((prev) =>
              prev.map((message) =>
                message.id === assistantId ? { ...message, content: message.content + delta } : message,
              ),
            )
          },
          onDone: () => {
            window.clearTimeout(timer)
            setMessages((prev) =>
              prev.map((message) => {
                if (message.id !== assistantId || message.status === 'error') return message
                const content = message.content.trim()
                return content
                  ? { ...message, status: 'done' }
                  : { ...message, status: 'error', error: '未收到回答，请重试。' }
              }),
            )
          },
          onError: (message) => {
            window.clearTimeout(timer)
            updateAssistant(assistantId, { status: 'error', error: message })
          },
        },
        controller.signal,
      )
      .finally(() => {
        window.clearTimeout(timer)
        if (abortRef.current === controller) {
          abortRef.current = null
          setStreaming(false)
        }
      })
  }

  function send(questionText?: string) {
    const question = (questionText ?? input).trim()
    if (!question || streaming) return
    const assistantId = createId()
    setMessages((prev) => [
      ...prev,
      { id: createId(), role: 'user', content: question },
      { id: assistantId, role: 'assistant', content: '', sources: [], status: 'streaming' },
    ])
    setInput('')
    startStream(question, assistantId)
  }

  function retry(message: ChatMessage) {
    if (!message.question || streaming) return
    startStream(message.question, message.id)
  }

  function focusSource(messageId: string, order: number) {
    const el = document.getElementById(`source-${messageId}-${order}`)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.remove('flash')
    void el.offsetWidth
    el.classList.add('flash')
    window.setTimeout(() => el.classList.remove('flash'), 1500)
  }

  function renderAnswer(message: ChatMessage) {
    const sourceCount = message.sources?.length ?? 0
    return message.content.split(/(\[\d+\])/g).map((part, index) => {
      const match = /^\[(\d+)\]$/.exec(part)
      const order = match ? Number(match[1]) : 0
      if (order > 0 && order <= sourceCount) {
        return (
          <button
            key={index}
            type="button"
            className="cite"
            title={`查看来源 ${order}`}
            aria-label={`查看来源 ${order}`}
            onClick={() => focusSource(message.id, order)}
          >
            {order}
          </button>
        )
      }
      return <span key={index}>{part}</span>
    })
  }

  function clearChat() {
    abortRef.current?.abort()
    abortRef.current = null
    setMessages([])
    setStreaming(false)
    toast('对话已清空', { type: 'info' })
  }

  return (
    <div>
      <div className="tabs reveal" style={{ marginBottom: 16 }}>
        <button type="button" className={tab === 'chat' ? 'active' : ''} onClick={() => setTab('chat')}>
          智能问答
        </button>
        <button type="button" className={tab === 'recommend' ? 'active' : ''} onClick={() => setTab('recommend')}>
          工具推荐
        </button>
      </div>

      <div className="ai-layout">
        {tab === 'chat' ? (
          <section className="chat card reveal">
        <header className="chat-head">
          <span className="ai-ava">
            <Sparkles className="ic" />
            <i className="ai-live"></i>
          </span>
          <div className="chat-id">
            <b>小享 · 社区工具助手</b>
            <span>
              <i className="online-dot"></i>在线 · 基于社区知识库回答
            </span>
          </div>
          <span className="badge brand" style={{ marginLeft: 'auto' }}>RAG · v2</span>
          <button className="icon-btn" type="button" onClick={clearChat} title="清空对话">
            <Trash2 className="ic sm" />
          </button>
        </header>

        <div className="chat-scroll" ref={scrollRef}>
          <div className="bubble-row ai">
            <span className="b-ava"><Sparkles className="ic sm" /></span>
            <div className="bubble">
              <p>
                您好！我是小享，社区工具房的智能助手。我可以帮您解答<b>借还流程、押金计费、工具使用与故障报修</b>等问题。
              </p>
              <div className="quick-wrap">
                <span className="tiny">试试这样问：</span>
                <div className="quick-grid">
                  {QUICK_QUESTIONS.map((question) => (
                    <button key={question} type="button" onClick={() => send(question)}>
                      <MessageSquare className="ic" />
                      {question}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {messages.map((message) => (
            <div key={message.id} className={'bubble-row ' + (message.role === 'user' ? 'user' : 'ai')}>
              <span className="b-ava">{message.role === 'user' ? '我' : <Sparkles className="ic sm" />}</span>
              <div className="bubble">
                {message.role === 'assistant' && message.status === 'streaming' && !message.content ? (
                  <span className="typing"><i></i><i></i><i></i></span>
                ) : (
                  <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                    {message.role === 'assistant' ? renderAnswer(message) : message.content}
                    {message.role === 'assistant' && message.status === 'streaming' && message.content && (
                      <span className="cursor" />
                    )}
                  </p>
                )}
                {message.sources && message.sources.length > 0 && (
                  <div className="sources">
                    {message.sources.map((source, index) => (
                      <div
                        className="src-item"
                        id={`source-${message.id}-${index + 1}`}
                        key={`${source.title}-${index}`}
                      >
                        <span className="src-no">{index + 1}</span>
                        <div className="src-body">
                          <b>{source.title}</b>
                          {source.snippet && <p>{source.snippet}</p>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {message.role === 'assistant' &&
                  message.status === 'done' &&
                  message.content.trim() &&
                  !message.sources?.length && (
                    <p className="source-note">
                      <CircleAlert className="ic sm" />
                      未检索到知识库来源，本条回答为兜底内容，仅供参考。
                    </p>
                  )}
                {message.status === 'error' && (
                  <div className="chat-error">
                    <CircleAlert className="ic sm" />
                    <span>{message.error || '回答中断，请重试。'}</span>
                    <button
                      className="btn btn-outline btn-sm"
                      type="button"
                      disabled={streaming}
                      onClick={() => retry(message)}
                    >
                      <RefreshCw className="ic sm" />
                      重试
                    </button>
                  </div>
                )}
                {message.role === 'assistant' && message.status === 'done' && message.content && (
                  <div className="followups">
                    {QUICK_QUESTIONS.slice(0, 3).map((question) => (
                      <button key={question} type="button" onClick={() => send(question)}>
                        {question}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

        <div className="chat-input">
          <div className="ci-box">
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault()
                  send()
                }
              }}
              placeholder="问问工具怎么借、怎么用、怎么还…（Enter 发送，Shift+Enter 换行）"
              rows={1}
            />
            <button className="btn btn-primary ci-send" type="button" disabled={streaming || !input.trim()} onClick={() => send()}>
              <Send className="ic sm" />
            </button>
          </div>
          <p className="ci-hint">回答由社区知识库生成，仅供参考；涉及押金与定损以工作人员核验为准。</p>
        </div>
          </section>
        ) : (
          <section className="chat card reveal" style={{ padding: 20, overflow: 'auto' }}>
            <ToolRecommendPanel onSelectTool={(id) => navigate(`/tools?tool=${id}`)} />
          </section>
        )}

      <aside className="ai-rail">
        <div className="panel tight reveal">
          <h3 className="rail-title"><BookOpen className="ic sm" />知识库覆盖</h3>
          <ul className="kb-list">
            {KB_ITEMS.map((item) => (
              <li key={item}><BookOpen className="ic" />{item}</li>
            ))}
          </ul>
        </div>
        <div className="panel tight reveal">
          <h3 className="rail-title"><Sparkles className="ic sm" />热门问题</h3>
          <div className="hot-q">
            {QUICK_QUESTIONS.map((question) => (
              <button key={question} type="button" onClick={() => send(question)}>
                <Sparkles className="ic" />
                {question}
              </button>
            ))}
          </div>
        </div>
        <div className="panel tight reveal rail-note">
          <h3 className="rail-title"><Clock className="ic sm" />人工服务</h3>
          <p>
            工具房每日 08:00–20:00 开放。复杂问题可联系工作人员 <b>0571-8888-6666</b>，或前往工具房现场咨询。
          </p>
          <p className="tiny"><Phone className="ic sm" /> 0571-8888-6666</p>
        </div>
      </aside>
      </div>
    </div>
  )
}
