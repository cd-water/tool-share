import { del, get, post, upload } from './request'
import { useUserStore } from '../stores/user'
import type { ChatDTO, ChatSourceVO, ChatVO, KbDocument, PageQuery, PageResult } from './types'

/** 上传知识库文档（管理员） */
export function uploadKbDocument(form: FormData) {
  return upload<KbDocument>('/ai/documents', form)
}

/** 知识库文档分页 */
export function pageKbDocuments(query: PageQuery) {
  return get<PageResult<KbDocument>>('/ai/documents', query)
}

/** 删除知识库文档（管理员） */
export function deleteKbDocument(id: number) {
  return del<void>(`/ai/documents/${id}`)
}

/** RAG 问答 */
export function chat(data: ChatDTO) {
  return post<ChatVO>('/ai/chat', data)
}

export interface ChatStreamHandlers {
  onSources?: (sources: ChatSourceVO[]) => void
  onDelta?: (text: string) => void
  onDone?: () => void
  onError?: (message: string) => void
}

/** RAG 流式问答：fetch + ReadableStream 手解 SSE（EventSource 不支持 POST） */
export async function chatStream(question: string, handlers: ChatStreamHandlers) {
  const userStore = useUserStore()
  let resp: Response
  try {
    resp = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: userStore.token },
      body: JSON.stringify({ question }),
    })
  } catch {
    handlers.onError?.('网络异常，请稍后重试')
    return
  }
  if (!resp.ok || !resp.body) {
    handlers.onError?.(`请求失败（${resp.status}）`)
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) {
      let event = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trimStart()
      }
      try {
        if (event === 'sources') handlers.onSources?.(JSON.parse(data))
        else if (event === 'delta') handlers.onDelta?.(JSON.parse(data))
        else if (event === 'error') handlers.onError?.(JSON.parse(data))
        else if (event === 'done') handlers.onDone?.()
      } catch {
        // 单帧脏数据只丢帧，不断流
      }
    }
  }
}
