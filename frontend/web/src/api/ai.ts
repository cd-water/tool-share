import http, { handleUnauthorized, type Result } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import type {
  AiSource,
  ToolRecommendRequest,
  ToolRecommendResult,
} from '@/api/types'

export interface ChatStreamHandlers {
  onSources: (sources: AiSource[]) => void
  onMessage: (delta: string) => void
  onDone?: () => void
  onError?: (message: string) => void
}

export const aiApi = {
  /** AI 工具推荐：描述需求，返回匹配的可用工具 */
  recommend: (payload: ToolRecommendRequest) =>
    http.post<ToolRecommendResult>('/user/ai/tool-recommend', payload, {
      // 向量检索 + 大模型重排可能超过默认的 10 秒
      timeout: 30_000,
    }),

  /** 推荐卡片点击埋点 */
  recommendClick: (recommendationId: number, toolId: number) =>
    http.post<void>(`/user/ai/tool-recommend/${recommendationId}/click`, { toolId }),

  /** 推荐结果反馈：1 有帮助 2 没帮助 */
  recommendFeedback: (recommendationId: number, feedback: 1 | 2) =>
    http.post<void>(`/user/ai/tool-recommend/${recommendationId}/feedback`, {
      feedback,
    }),

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

    // 错误一律非 2xx：与统一请求层共用 envelope 解析和登录失效处理。
    if (!response.ok || !response.body) {
      let message = `请求失败（${response.status}）`
      try {
        const data = (await response.json()) as Partial<Result<unknown>>
        if (handleUnauthorized(data?.code)) return
        if (data?.message) message = data.message
      } catch {
        // 非 JSON 响应（如 404 页面），保留默认文案
      }
      handlers.onError?.(message)
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
