import http from '@/api/request'
import type { UploadVO } from '@/api/types'

export const uploadApi = {
  uploadFile: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<UploadVO>('/admin/upload', formData)
  },
}
