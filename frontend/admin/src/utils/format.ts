import dayjs from 'dayjs'

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : value
}

export function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD') : value
}

export function formatMoney(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  return `¥${Number(value).toFixed(2)}`
}

export function resolveFileUrl(url?: string | null): string {
  if (!url) return ''
  if (/^(https?:)?\/\//.test(url) || url.startsWith('/')) return url
  return `/api/files/${url}`
}

export function exportTimestamp(): string {
  return dayjs().format('YYYYMMDDHHmmss')
}
