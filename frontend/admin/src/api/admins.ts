/**
 * 管理员账号接口（/admin/admins）。
 *
 * 供「管理员账号」页面使用：查询账号列表、调整账号角色。
 * 本文件只声明请求形状；鉴权头注入、统一响应解包与错误提示
 * 都由 `@/api/request` 的拦截器集中处理。
 */
import http from '@/api/request'
import type { AdminRole, AdminRoleUpdateDTO, AdminVO } from '@/api/types'

/**
 * 管理员账号 API 集合。
 *
 * 所有方法返回 Promise：成功时直接拿到响应 envelope 的 data 字段，
 * 业务失败时由响应拦截器统一提示并 reject，调用方只需处理成功分支。
 */
export const adminApi = {
  /**
   * 获取全部管理员账号。
   *
   * @returns 管理员列表；后端不分页，一次性返回全部账号。
   * @throws 未登录或无权限时由拦截器跳转登录/提示并 reject。
   */
  list: () => http.get<AdminVO[]>('/admin/admins'),
  /**
   * 修改指定管理员的角色。
   *
   * @param id 管理员 ID
   * @param role 目标角色（STAFF 工作人员 / SUPER_ADMIN 系统管理员）
   * @returns 无响应体，Promise resolve 即表示修改成功
   * @throws 角色非法、账号不存在或无权限时由拦截器提示并 reject
   */
  updateRole: (id: number, role: AdminRole) =>
    http.patch<void>(`/admin/admins/${id}/role`, { role } satisfies AdminRoleUpdateDTO),
}
