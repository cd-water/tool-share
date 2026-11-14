function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** `<input type="datetime-local">` 使用的本地时间格式（分钟精度）。 */
export function toInputValue(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/** 后端 LocalDateTime 使用的本地时间格式（秒精度）。 */
export function toApiValue(date: Date): string {
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/**
 * 预约取件后要按「实际归还时间不得早于开始时间」结算，因此开始时间取下一分钟，
 * 给支付与取件留出操作时间，最多等待 60 秒即可归还。
 */
export function nextMinuteStart(): Date {
  const date = new Date(Date.now() + 65_000)
  date.setSeconds(0, 0)
  return date
}
