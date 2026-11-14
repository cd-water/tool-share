/**
 * 通用文件上传接口（/admin/upload）。
 *
 * 目前用于工具图片上传：先上传拿到文件标识/直链，再把 URL 写入工具保存体。
 * 使用 multipart/form-data，由浏览器自动设置带 boundary 的 Content-Type。
 */
import http from '@/api/request'
import type { UploadVO } from '@/api/types'

/**
 * 上传 API 集合。
 */
export const uploadApi = {
  /**
   * 上传单个文件。
   *
   * @param file 浏览器 File 对象
   * @returns UploadVO，含可访问的文件地址或标识
   * 注意：不要给请求手动加 Content-Type，否则会丢失 multipart boundary。
   * @throws 文件类型/大小不合法时由拦截器提示并 reject
   */
  uploadFile: (file: File) => {
    // 构造 multipart 表单；字段名 file 与后端约定一致
    const formData = new FormData()
    formData.append('file', file)
    return http.post<UploadVO>('/admin/upload', formData)
  },
}
