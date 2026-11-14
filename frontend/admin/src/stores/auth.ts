import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'

export const useAuthStore = defineStore(
  'auth',
  () => {
    const tokenValue = ref('')
    const username = ref('')
    const role = ref('')
    const isLoggedIn = computed(() => tokenValue.value !== '')
    const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')

    async function login(username: string, password: string) {
      const data = await authApi.login(username, password)
      tokenValue.value = data.tokenValue
      await fetchInfo()
    }

    async function fetchInfo() {
      const data = await authApi.info()
      username.value = data.username
      role.value = data.role
    }

    function reset() {
      tokenValue.value = ''
      username.value = ''
      role.value = ''
    }

    async function logout() {
      try {
        await authApi.logout()
      } finally {
        reset()
      }
    }

    return { tokenValue, username, role, isLoggedIn, isSuperAdmin, login, fetchInfo, logout, reset }
  },
  { persist: true },
)
