import type { TagType } from '@/utils/enums'

// 把 Element Plus 的 tag 类型映射到设计系统的 .badge 修饰类
const TONE: Record<TagType, string> = {
  primary: 'brand',
  success: 'ok',
  warning: 'warn',
  info: 'info',
  danger: 'bad',
}

export function badgeClass(tag: TagType): string {
  return 'badge ' + TONE[tag]
}
