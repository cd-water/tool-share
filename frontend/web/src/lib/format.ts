/**
 * 展示层格式化工具。
 *
 * 这些函数只负责把后端返回值转成可读文本/可访问 URL，不做业务判断：
 * - 空值统一用占位符，避免页面出现 `null`/`undefined`；
 * - 金额始终按两位小数展示；
 * - 文件 key 与完整 URL 都能被 `resolveFileUrl` 正确处理。
 */
import dayjs from 'dayjs'

/**
 * 格式化日期时间。
 *
 * @param value ISO 时间字符串；null/undefined 表示后端未提供
 * @returns `YYYY-MM-DD HH:mm`；空值返回 `—`
 *
 * 容错：若后端返回无法解析的字符串，则原样返回，方便定位脏数据，
 * 而不是展示 Invalid Date。
 */
export function formatDateTime(value?: string | null) {
  if (!value) return '—'
  const date = dayjs(value)
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm') : value
}

/**
 * 格式化金额。
 *
 * @param value 数字或数字字符串；空值按 0 处理
 * @returns 形如 `¥12.00` 的字符串
 *
 * 容错：非有限数（NaN/Infinity）显示为 0.00，避免页面出现 `¥NaN`。
 */
export function formatMoney(value?: number | string | null) {
  const amount = Number(value ?? 0)
  return `¥${Number.isFinite(amount) ? amount.toFixed(2) : '0.00'}`
}

/**
 * 判断消息是否已读。
 *
 * 后端在不同版本中可能返回 boolean、数字或字符串形式，这里统一收口，
 * 避免每个页面各写一套判断。
 *
 * @returns 只要命中 true / 1 / '1' 即为已读
 */
export function isReadStatus(value: boolean | number | string | undefined) {
  return value === true || value === 1 || value === '1'
}

/**
 * 把后端返回的文件标识解析为可直接用于 `<img src>` 的地址。
 *
 * @param url 完整 http(s) URL、站内绝对路径，或对象存储 key
 * @returns 可访问 URL；空值返回空字符串
 *
 * 规则：
 * - `http://`、`https://`、协议相对 `//` 和以 `/` 开头的地址原样使用；
 * - 其余视为后端文件 key，拼接 `/api/files/` 代理路径读取，
 *   这样不需要把对象存储域名暴露给前端，也便于统一鉴权。
 */
export function resolveFileUrl(url?: string | null) {
  if (!url) return ''
  if (/^(https?:)?\/\//.test(url) || url.startsWith('/')) return url
  return `/api/files/${url}`
}
