import { useEffect, useRef, useState } from 'react'
import { BookOpen, Clock, MessageSquare, Phone, Send, Sparkles, Trash2 } from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { AiSource } from '@/api/types'
import { toast } from '@/lib/fx'

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

const KB_ITEMS = ['租赁流程与预约规则', '押金支付与退还标准', '逾期与损坏计费说明', '工具使用与安全须知']

function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

export default function AiPage() {
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
                message.id === assistantId ? { ...message, content: message.content + delta } : message,
              ),
            ),
          onError: (message) => {
            toast(message, { type: 'bad' })
            updateAssistant(assistantId, { content: '抱歉，AI 助手暂时无法回答，请稍后再试。' })
          },
        },
        controller.signal,
      )
      .finally(() => {
        if (abortRef.current === controller) abortRef.current = null
        setStreaming(false)
      })
  }

  function clearChat() {
    abortRef.current?.abort()
    setMessages([])
    setStreaming(false)
    toast('对话已清空', { type: 'info' })
  }

  return (
    <div className="ai-layout">
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
                {message.role === 'assistant' && message.content === '' && streaming ? (
                  <span className="typing"><i></i><i></i><i></i></span>
                ) : (
                  <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{message.content}</p>
                )}
                {message.sources && message.sources.length > 0 && (
                  <div className="sources">
                    {message.sources.map((source, index) => (
                      <span className="src" key={`${source.title}-${index}`} title={source.snippet}>
                        <BookOpen className="ic" />
                        {source.title}
                      </span>
                    ))}
                  </div>
                )}
                {message.role === 'assistant' && message.content && !streaming && (
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
  )
}
