import http from '@/api/request'

export interface KnowledgeDocVO {
  id: number
  title: string
  fileName: string
  contentPreview?: string
  createTime?: string
}

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
