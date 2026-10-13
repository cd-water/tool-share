import { get, post, put } from './request'
import { useUserStore } from '../stores/user'
import type {
  LoginDTO,
  LoginVO,
  PageResult,
  RegisterDTO,
  UserQueryDTO,
  UserRoleDTO,
  UserStatusDTO,
  UserVO,
} from './types'

/** 登录：成功后登录态写入 store（拦截器自动携带 token） */
export function login(data: LoginDTO) {
  return post<LoginVO>('/user/login', data).then((vo) => {
    Object.assign(useUserStore(), vo)
    return vo
  })
}

/** 居民注册 */
export function register(data: RegisterDTO) {
  return post<void>('/user/register', data)
}

/** 当前登录用户信息 */
export function me() {
  return get<UserVO>('/user/me')
}

/** 用户分页（工作人员） */
export function pageUsers(query: UserQueryDTO) {
  return get<PageResult<UserVO>>('/user/page', query)
}

/** 修改用户角色（管理员） */
export function updateUserRole(id: number, data: UserRoleDTO) {
  return put<void>(`/user/${id}/role`, data)
}

/** 启用/停用用户（工作人员） */
export function updateUserStatus(id: number, data: UserStatusDTO) {
  return put<void>(`/user/${id}/status`, data)
}
