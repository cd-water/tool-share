import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface Profile {
  nickname: string
  avatar: string
}

interface AuthState {
  tokenValue: string
  nickname: string
  avatar: string
  setAuth: (tokenValue: string) => void
  setProfile: (profile: Profile) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      tokenValue: '',
      nickname: '',
      avatar: '',
      setAuth: (tokenValue) => set({ tokenValue }),
      setProfile: ({ nickname, avatar }) => set({ nickname, avatar }),
      clear: () => set({ tokenValue: '', nickname: '', avatar: '' }),
    }),
    {
      name: 'web-auth',
      partialize: (state) => ({
        tokenValue: state.tokenValue,
        nickname: state.nickname,
        avatar: state.avatar,
      }),
    },
  ),
)
