/**
 * 展示层格式化工具。
 *
 * 统一空值占位符「—」、日期格式和金额格式，避免各页面各写一套。
 * 这些函数都是纯函数，不修改入参，也不依赖全局状态。
 */
import dayjs from 'dayjs'

/**
 * 格式化日期时间。
 *
 * @param value ISO 字符串或后端日期字符串，允许 null/空串
 * @returns `YYYY-MM-DD HH:mm`；空值返回「—」，
 *          无法解析时原样返回输入，避免吞掉异常数据
 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  // dayjs 能解析常见 ISO 与后端日期字符串。
  const date = dayjs(value)
  // 非法日期原样返回，便于暴露后端数据问题。
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : value
}

/**
 * 格式化日期（不含时间）。
 *
 * @param value 日期字符串，允许 null/空串
 * @returns `YYYY-MM-DD`；空值返回「—」，非法日期原样返回
 */
export function formatDate(value?: string | null): string {
  if (!value) return '—'
  // 日期格式同样先解析再校验，避免把非法字符串展示成 1970。
  const date = dayjs(value)
  // 与 formatDateTime 保持一致的容错策略。
  return date.isValid() ? date.format('YYYY-MM-DD') : value
}

/**
 * 格式化金额。
 *
 * @param value 金额数值，允许 null/undefined
 * @returns 带 ¥ 前缀、保留两位小数的字符串；空值返回「—」
 * 不添加千分位，避免与后端导出的数字格式不一致。
 */
export function formatMoney(value?: number | null): string {
  if (value === null || value === undefined) return '—'
  // 统一两位小数；负数也按同一格式展示。
  return `¥${Number(value).toFixed(2)}`
}

/**
 * 把后端返回的文件标识解析为可直接访问的 URL。
 *
 * @param url 完整 URL、协议相对 URL、以 / 开头的站内路径，
 *            或对象存储返回的文件 key
 * @returns 可直接用于 img/src 的地址；空值返回空串
 * 规则：绝对地址和站内绝对路径原样返回，
 *       其余视为文件 key，拼接 /api/files/ 前缀走统一文件代理。
 */
export function resolveFileUrl(url?: string | null): string {
  // 空值返回空串，调用方可以安全地放进 src。
  if (!url) return ''
  // 完整 URL、协议相对 URL 与站内绝对路径都不需要改写。
  if (/^(https?:)?\/\//.test(url) || url.startsWith('/')) return url
  // 其余视为对象存储 key，走统一文件代理。
  return `/api/files/${url}`
}

/**
 * 生成导出文件名用的时间戳。
 *
 * @returns `YYYYMMDDHHmmss`，例如 20260101123045
 * 无参数、无副作用；每次调用取当前本地时间。
 */
export function exportTimestamp(): string {
  // 精确到秒，足够区分同一次操作生成的文件。
  return dayjs().format('YYYYMMDDHHmmss')
}
