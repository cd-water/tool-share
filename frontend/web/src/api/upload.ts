/**
 * 文件上传接口。
 *
 * 使用 multipart/form-data 直传后端，由后端写入对象存储并返回可访问的
 * 文件标识/URL。请求层会自动带上 Authorization，因此这里无需手动处理鉴权。
 * 注意：不要手动设置 Content-Type，浏览器需要自行生成 boundary。
 */
import http from '@/api/request'
import type { UploadResult } from '@/api/types'

export const uploadApi = {
  /**
   * 上传单个文件。
   *
   * @param file 浏览器 File 对象（头像、工具图片或损坏凭证）
   * @returns 上传结果，通常包含文件 key 与访问地址
   *
   * 错误：文件过大、类型不允许等由后端返回业务错误，请求层统一 toast；
   * 调用方应自行维护上传进度/loading 状态。
   */
  upload: (file: File) => {
    // 每次上传都新建 FormData，避免复用导致重复 append 同一文件。
    const formData = new FormData()
    formData.append('file', file)
    return http.post<UploadResult>('/user/upload', formData)
  },
}
