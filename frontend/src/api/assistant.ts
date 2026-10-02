import type { ChatResult, KbDocCreated, KbDocItem, PageResult } from './types'
import { httpGet, httpPost } from '../utils/request'

export function uploadDocument(file: File, docType: string, toolId?: number) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('docType', docType)
  if (toolId != null) formData.append('toolId', String(toolId))
  return httpPost<KbDocCreated>('/assistant/documents', formData)
}

export function listDocuments(params: { page?: number; size?: number }) {
  return httpGet<PageResult<KbDocItem>>('/assistant/documents', params)
}

export function chat(question: string) {
  return httpPost<ChatResult>('/assistant/chat', { question })
}
