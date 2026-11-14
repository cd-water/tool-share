/**
 * AI 能力接口。
 *
 * 包含两类通信模式：
 * 1. 普通 JSON 请求（推荐、埋点、反馈）复用统一 http 实例，享受鉴权注入、
 *    envelope 解包与统一错误提示；
 * 2. SSE 流式问答无法用 axios 的响应拦截器处理（需要逐块读取流），
 *    因此直接用 fetch + ReadableStream 手工解析 `event:`/`data:` 帧，
 *    但错误处理仍与统一请求层保持同一语义（envelope、C401、本地 toast 由
 *    调用方决定，接口层只回调 onError）。
 *
 * 流式接口不经过 React Query：回答是增量文本，不适合放进查询缓存。
 */
import http, { handleUnauthorized, type Result } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import type {
  AiSource,
  ToolRecommendRequest,
  ToolRecommendResult,
} from '@/api/types'

/**
 * SSE 流式问答的事件回调。
 *
 * 所有回调都在网络流读取过程中触发，调用方应保证它们足够轻量，
 * 并通过函数式 setState 合并增量文本，避免闭包拿到旧值。
 */
export interface ChatStreamHandlers {
  /**
   * 收到检索来源列表时调用。
   *
   * @param sources 本次回答引用的工具/知识片段，可能为空数组
   */
  onSources: (sources: AiSource[]) => void

  /**
   * 收到一段回答增量文本时调用。
   *
   * @param delta 增量字符串（不是完整回答），调用方需自行拼接
   */
  onMessage: (delta: string) => void

  /**
   * 流正常结束时调用（可选）。
   *
   * 主动 abort 不会触发 onDone，也不会触发 onError。
   */
  onDone?: () => void

  /**
   * 发生可展示错误时调用（可选）。
   *
   * @param message 已整理为中文的用户可读文案
   */
  onError?: (message: string) => void
}

export const aiApi = {
  /** AI 工具推荐：描述需求，返回匹配的可用工具 */
  /**
   * AI 工具推荐。
   *
   * @param payload 自然语言需求、筛选条件等
   * @returns 推荐 ID 与工具条目列表
   *
   * 超时：向量检索 + 大模型重排耗时明显高于普通接口，单独放宽到 30 秒；
   * 错误：额度不足、模型超时等由后端返回业务错误，请求层统一 toast。
   */
  recommend: (payload: ToolRecommendRequest) =>
    http.post<ToolRecommendResult>('/user/ai/tool-recommend', payload, {
      // 向量检索 + 大模型重排可能超过默认的 10 秒
      timeout: 30_000,
    }),

  /** 推荐卡片点击埋点 */
  /**
   * 推荐卡片点击埋点。
   *
   * @param recommendationId 本次推荐会话 ID，用于把点击归因到推荐结果
   * @param toolId 被点击的工具 ID
   *
   * 副作用：仅记录埋点，不改变推荐结果；失败不影响用户跳转，
   * 调用方可忽略该 Promise 的 rejection（请求层已 toast）。
   */
  recommendClick: (recommendationId: number, toolId: number) =>
    http.post<void>(`/user/ai/tool-recommend/${recommendationId}/click`, { toolId }),

  /** 推荐结果反馈：1 有帮助 2 没帮助 */
  /**
   * 提交推荐结果反馈。
   *
   * @param recommendationId 推荐会话 ID
   * @param feedback 1=有帮助，2=没帮助
   *
   * 用于后续模型评估与排序调优；接口幂等与否由后端决定，
   * 前端应在提交后禁用按钮避免重复点击。
   */
  recommendFeedback: (recommendationId: number, feedback: 1 | 2) =>
    http.post<void>(`/user/ai/tool-recommend/${recommendationId}/feedback`, {
      feedback,
    }),

  /** SSE 流式问答：fetch + ReadableStream 手工解析 event/data 帧。 */
  /**
   * AI 流式问答。
   *
   * @param question 用户问题
   * @param handlers 增量事件回调（来源、正文、结束、错误）
   * @param signal 外部 AbortSignal；组件卸载时中止请求
   * @returns 流结束或出错后 resolve；不向调用方抛出异常
   *
   * 错误处理约定：
   * - 网络异常、非 2xx、流中断 → onError；
   * - 登录失效（C401）→ 复用 handleUnauthorized 清理会话，不再 onError；
   * - 主动 abort → 静默结束，不回调 onDone/onError；
   * - 正常结束 → onDone。
   *
   * 为什么不用 EventSource：EventSource 只支持 GET，无法携带 JSON 请求体，
   * 也无法自定义 Authorization 头；因此用 fetch 流式读取并手工解析 SSE 协议。
   */
  chatStream: async (
    question: string,
    handlers: ChatStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> => {
    // 与 axios 拦截器一致：直接从 store 读取 token，不依赖 React 上下文。
    const { tokenValue } = useAuthStore.getState()
    let response: Response
    try {
      response = await fetch('/api/user/ai/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          // 未登录时不发 Authorization，交给后端返回 C401。
          ...(tokenValue ? { Authorization: `Bearer ${tokenValue}` } : {}),
        },
        body: JSON.stringify({ question }),
        signal,
      })
    } catch (error) {
      // 组件卸载导致的主动中止：无需提示用户。
      if ((error as Error).name === 'AbortError') return
      handlers.onError?.('网络异常，请稍后再试。')
      return
    }

    // 错误一律非 2xx：与统一请求层共用 envelope 解析和登录失效处理。
    if (!response.ok || !response.body) {
      let message = `请求失败（${response.status}）`
      try {
        const data = (await response.json()) as Partial<Result<unknown>>
        // C401 只清理会话，不再弹错误气泡，避免与全局 toast 重复。
        if (handleUnauthorized(data?.code)) return
        if (data?.message) message = data.message
      } catch {
        // 非 JSON 响应（如 404 页面），保留默认文案
      }
      handlers.onError?.(message)
      return
    }

    // 逐块读取字节流；TextDecoder 使用 stream 模式处理跨块的多字节字符。
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    // SSE 帧状态：当前事件名 + 多行 data（按协议用换行拼接）。
    let eventName = ''
    let dataLines: string[] = []

    /**
     * 派发一个完整 SSE 事件（遇到空行表示帧结束）。
     *
     * 支持的事件：
     * - `sources`：JSON 数组，交给 onSources；
     * - `message`：增量文本，交给 onMessage；
     * - 其他事件忽略，便于后端未来扩展而不破坏旧前端。
     */
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
      // 重置帧状态，准备接收下一个事件。
      eventName = ''
      dataLines = []
    }

    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        // stream: true 允许一个 UTF-8 字符被拆在两个 chunk 中。
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        // 按行切分；保留最后一段不完整的行在 buffer 中等待下一块。
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '')
          buffer = buffer.slice(idx + 1)
          if (line === '') {
            // 空行 = 事件边界。
            dispatch()
          } else if (line.startsWith('event:')) {
            // event: 后可能有空格，trim 掉。
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            // 规范允许 `data: value`，去掉冒号后的一个可选空格。
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
    // 流结束时可能还有未以空行收尾的最后一帧，补一次派发。
    dispatch()
    handlers.onDone?.()
  },
}
