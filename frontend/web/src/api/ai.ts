import { toast } from '@/components/ui/toast'
import http from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import type { AiChatResult, AiSource } from '@/api/types'

interface ResultError {
  code?: string
  message?: string
}

export interface ChatStreamHandlers {
  onSources: (sources: AiSource[]) => void
  onMessage: (delta: string) => void
  onDone?: () => void
  onError?: (message: string) => void
}

export const aiApi = {
  chat: (question: string) => http.post<AiChatResult>('/user/ai/chat', { question }),

  /** SSE 流式问答：fetch + ReadableStream 手工解析 event/data 帧。 */
  chatStream: async (
    question: string,
    handlers: ChatStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> => {
    const { tokenValue } = useAuthStore.getState()
    let response: Response
    try {
      response = await fetch('/api/user/ai/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(tokenValue ? { Authorization: `Bearer ${tokenValue}` } : {}),
        },
        body: JSON.stringify({ question }),
        signal,
      })
    } catch (error) {
      if ((error as Error).name === 'AbortError') return
      handlers.onError?.('网络异常，请稍后再试。')
      return
    }

    if (!response.ok || !response.body) {
      let message = `请求失败（${response.status}）`
      try {
        const data = (await response.json()) as { message?: string }
        if (data?.message) message = data.message
      } catch {
        // 非 JSON 响应（如 404 页面），保留默认文案
      }
      handlers.onError?.(message)
      return
    }

    // 业务错误与正常接口一致：HTTP 200 + Result JSON（非 event-stream）
    const contentType = response.headers.get('content-type') ?? ''
    if (!contentType.includes('text/event-stream')) {
      let data: ResultError | null = null
      try {
        data = (await response.json()) as ResultError
      } catch {
        // 无法解析时按通用错误处理
      }
      if (data?.code === 'C401') {
        useAuthStore.getState().clear()
        toast.add({ title: '登录已过期，请重新登录', type: 'error' })
        return
      }
      handlers.onError?.(data?.message || '请求失败，请稍后再试。')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = ''
    let dataLines: string[] = []

    const dispatch = () => {
      if (!eventName && dataLines.length === 0) return
      const data = dataLines.join('\n')
      if (eventName === 'sources') {
        try {
          const parsed = JSON.parse(data) as { sources?: AiSource[] }
          handlers.onSources(parsed.sources ?? [])
        } catch {
          // 忽略无法解析的来源事件
        }
      } else if (eventName === 'message') {
        handlers.onMessage(data)
      }
      eventName = ''
      dataLines = []
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '')
          buffer = buffer.slice(idx + 1)
          if (line === '') {
            dispatch()
          } else if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
      }
    } catch (error) {
      // 组件卸载主动中止：静默结束；流中途断开：提示并结束
      if ((error as Error).name !== 'AbortError') {
        handlers.onError?.('连接中断，请稍后再试。')
      }
      return
    }
    dispatch()
    handlers.onDone?.()
  },
}
