import http from '@/api/request'
import type { UploadResult } from '@/api/types'

export const uploadApi = {
  upload: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<UploadResult>('/user/upload', formData)
  },
}
