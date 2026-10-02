import { defineStore } from 'pinia'
import type { UserInfo } from '../api/types'

/** 登录态（persistedstate 持久化到 localStorage） */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: '',
    user: null as UserInfo | null,
  }),
  getters: {
    role: (state) => state.user?.role ?? '',
    isLoggedIn: (state) => state.token !== '',
  },
  actions: {
    setLogin(token: string, user: UserInfo) {
      this.token = token
      this.user = user
    },
    clear() {
      this.token = ''
      this.user = null
    },
  },
  persist: true,
})
