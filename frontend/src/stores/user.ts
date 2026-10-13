import { defineStore } from 'pinia'
import type { LoginVO } from '../api/types'

// 登录态：整体持久化到 localStorage，请求拦截器自动携带 token
export const useUserStore = defineStore('user', {
  state: (): LoginVO => ({
    token: '',
    userId: 0,
    realName: '',
    role: 0,
  }),
  persist: true,
})
