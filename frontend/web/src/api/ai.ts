import http from '@/api/request'
import type { AiChatResult } from '@/api/types'

export const aiApi = {
  chat: (question: string) =>
    http.post<AiChatResult>('/user/ai/chat', { question }),
}
