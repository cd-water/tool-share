import http from '@/api/request'
import type { KnowledgeDocVO } from '@/api/types'

export const knowledgeApi = {
  list: () => http.get<KnowledgeDocVO[]>('/admin/knowledge'),
  upload: (file: File, title: string) => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('title', title)
    return http.post<KnowledgeDocVO>('/admin/knowledge', formData)
  },
  remove: (id: number) => http.delete<void>(`/admin/knowledge/${id}`),
}
