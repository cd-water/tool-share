/**
 * 知识库文档接口（/admin/knowledge）。
 *
 * 仅系统管理员可维护：上传文档、查看列表、删除文档。
 * 上传使用 multipart/form-data，直接传递浏览器构造的 FormData。
 */
import http from '@/api/request'
import type { KnowledgeDocVO } from '@/api/types'

/**
 * 知识库 API 集合。
 */
export const knowledgeApi = {
  /**
   * 获取知识库文档列表。
   *
   * @returns 全部文档，后端不分页
   * @throws 请求失败时由拦截器提示并 reject
   */
  list: () => http.get<KnowledgeDocVO[]>('/admin/knowledge'),
  /**
   * 上传一个知识库文档。
   *
   * @param file 浏览器 File 对象（原始文件内容）
   * @param title 文档标题，用于列表展示与检索
   * @returns 新建的文档信息
   * 注意：使用 FormData 时不要手动设置 Content-Type，
   * 浏览器会自动补上带 boundary 的 multipart 头。
   * @throws 文件类型/大小不符合要求时由拦截器提示并 reject
   */
  upload: (file: File, title: string) => {
    // multipart 表单：文件与标题一起提交，由浏览器生成 boundary
    const formData = new FormData()
    formData.append('file', file)
    formData.append('title', title)
    return http.post<KnowledgeDocVO>('/admin/knowledge', formData)
  },
  /**
   * 删除指定知识库文档。
   *
   * @param id 文档 ID
   * @returns 无响应体，resolve 表示删除成功
   * @throws 文档不存在或无权限时由拦截器提示并 reject
   */
  remove: (id: number) => http.delete<void>(`/admin/knowledge/${id}`),
}
