import { del, get, post, upload } from './request'
import type { ChatDTO, ChatVO, KbDocument, PageQuery, PageResult } from './types'

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
