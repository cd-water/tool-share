import type { DamageReportQuery } from '@/api/damage'
import type { MessageQuery } from '@/api/messages'
import type { RentalQuery } from '@/api/rentals'
import type { ToolQuery } from '@/api/tools'
import type { PageParams } from '@/api/types'

/**
 * 统一 query key 约定：`[领域, 范围, 参数...]`。
 * 失效时只传领域前缀（如 `queryKeys.tools.all`）即可清除该领域全部缓存。
 */
export const queryKeys = {
  dashboard: ['dashboard'] as const,
  user: {
    info: ['user', 'info'] as const,
  },
  profile: ['profile'] as const,
  tools: {
    all: ['tools'] as const,
    list: (params: ToolQuery) => ['tools', 'list', params] as const,
    detail: (id: number | null) => ['tools', 'detail', id] as const,
    watch: (id: number | null) => ['tools', 'detail', id, 'watch'] as const,
    availability: (id: number | null, startTime: string, endTime: string) =>
      ['tools', 'detail', id, 'availability', startTime, endTime] as const,
  },
  rentals: {
    all: ['rentals'] as const,
    list: (params: RentalQuery) => ['rentals', 'list', params] as const,
    detail: (id: number | null) => ['rentals', 'detail', id] as const,
  },
  damageReports: {
    all: ['damage-reports'] as const,
    list: (params: DamageReportQuery) => ['damage-reports', 'list', params] as const,
    detail: (id: number | null) => ['damage-reports', 'detail', id] as const,
  },
  messages: {
    all: ['messages'] as const,
    list: (params: MessageQuery) => ['messages', 'list', params] as const,
    unreadCount: ['messages', 'unread-count'] as const,
  },
  notificationPrefs: ['notification-prefs'] as const,
  credit: {
    all: ['credit'] as const,
    overview: (params: PageParams) => ['credit', 'overview', params] as const,
  },
  reviews: {
    all: ['reviews'] as const,
    list: (params: PageParams) => ['reviews', 'list', params] as const,
  },
}
