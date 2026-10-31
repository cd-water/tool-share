import dayjs from 'dayjs'

export function formatDateTime(value?: string | null) {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : value
}

export function formatDate(value?: string | null) {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD') : value
}

export function formatMoney(value?: number | string | null) {
  const amount = Number(value ?? 0)
  return `¥${Number.isFinite(amount) ? amount.toFixed(2) : '0.00'}`
}

export function isReadStatus(value: boolean | number | string | undefined) {
  return value === true || value === 1 || value === '1'
}
