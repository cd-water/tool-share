import type { BadgeTone } from '@/components/ui/badge'

// 把旧的 shadcn BadgeTone 映射到设计系统的 .badge 修饰类
const TONE_CLASS: Record<BadgeTone, string> = {
  success: 'ok',
  warning: 'warn',
  info: 'info',
  danger: 'bad',
  primary: 'brand',
  neutral: 'neutral',
}

export function badgeClass(tone: BadgeTone): string {
  return 'badge ' + (TONE_CLASS[tone] ?? 'neutral')
}

export function badgeDotClass(tone: BadgeTone): string {
  return 'badge ' + (TONE_CLASS[tone] ?? 'neutral')
}
