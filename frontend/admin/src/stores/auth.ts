import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { } from 'pinia-plugin-persistedstate'
import http from '@/utils/request'

interface LoginVO {
  tokenName: string
  tokenValue: string
}

interface AdminVO {
  id: number
  username: string
}

/**
 * 认证状态：persist: true 落 localStorage（key='auth'），刷新不丢登录态。
 */
export const useAuthStore = defineStore(
  'auth',
  () => {
    const tokenValue = ref('')
    const username = ref('')
    const isLoggedIn = computed(() => tokenValue.value !== '')

    async function login(name: string, password: string) {
      const data = await http.post<LoginVO>('/admin/login', {
        username: name,
        password,
      })
      tokenValue.value = data.tokenValue
      await fetchInfo()
    }

    async function fetchInfo() {
      const data = await http.get<AdminVO>('/admin/info')
      username.value = data.username
    }

    /** 无论后端登出是否成功，都清空本地凭证 */
    async function logout() {
      try {
        await http.post<void>('/admin/logout')
      } finally {
        tokenValue.value = ''
        username.value = ''
      }
    }

    return { tokenValue, username, isLoggedIn, login, fetchInfo, logout }
  },
  { persist: true },
)
