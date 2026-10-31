import http from '@/api/request'
import type { ProfileUpdatePayload, UserProfile } from '@/api/types'

export const profileApi = {
  get: () => http.get<UserProfile>('/user/profile'),
  update: (payload: ProfileUpdatePayload) =>
    http.put<UserProfile>('/user/profile', payload),
}
