import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'

export const useAuthStore = defineStore(
  'auth',
  () => {
    const tokenValue = ref('')
    const username = ref('')
    const isLoggedIn = computed(() => tokenValue.value !== '')

    async function login(username: string, password: string) {
      const data = await authApi.login(username, password)
      tokenValue.value = data.tokenValue
      await fetchInfo()
    }

    async function fetchInfo() {
      const data = await authApi.info()
      username.value = data.username
    }

    function reset() {
      tokenValue.value = ''
      username.value = ''
    }

    async function logout() {
      try {
        await authApi.logout()
      } finally {
        reset()
      }
    }

    return { tokenValue, username, isLoggedIn, login, fetchInfo, logout, reset }
  },
  { persist: true },
)
