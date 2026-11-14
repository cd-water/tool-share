/**
 * 用户资料接口。
 *
 * 与 `/user/info` 的区别：`info` 用于登录态恢复（轻量），
 * 这里的 `get` 返回完整资料页数据，`update` 负责保存用户编辑后的资料。
 */
import http from '@/api/request'
import type { ProfileUpdatePayload, UserProfile } from '@/api/types'

export const profileApi = {
  /**
   * 获取当前用户完整资料。
   *
   * @returns 昵称、头像、联系方式等资料字段
   *
   * 401 由统一请求层处理；资料页通常配合 `queryKeys.profile` 缓存。
   */
  get: () => http.get<UserProfile>('/user/profile'),

  /**
   * 更新当前用户资料。
   *
   * @param payload 仅包含允许用户修改的字段（不包含角色、信用分等）
   * @returns 保存后的完整资料
   *
   * 副作用：成功后应失效 `queryKeys.profile` 与 `queryKeys.user.info`，
   * 让顶栏头像/昵称同步刷新。
   */
  update: (payload: ProfileUpdatePayload) =>
    http.put<UserProfile>('/user/profile', payload),
}
