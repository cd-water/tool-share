/**
 * Badge 视觉映射工具。
 *
 * 组件层仍沿用 shadcn 风格的语义 tone（success/warning/...），
 * 而设计系统实际输出的是 `.badge--ok` 等修饰类。这里作为唯一映射点，
 * 避免在页面里散落硬编码 class，也便于将来整体换肤时只改一处。
 */
import type { BadgeTone } from '@/components/ui/badge'

/**
 * 语义 tone 到设计系统修饰类的映射表。
 *
 * 使用 `Record<BadgeTone, string>` 保证新增 tone 时编译器强制补全，
 * 不会出现运行时静默落到 neutral 的情况。
 */
// 把旧的 shadcn BadgeTone 映射到设计系统的 .badge 修饰类
const TONE_CLASS: Record<BadgeTone, string> = {
  success: 'ok',
  warning: 'warn',
  info: 'info',
  danger: 'bad',
  primary: 'brand',
  neutral: 'neutral',
}

/**
 * 把 BadgeTone 转成完整 class 字符串。
 *
 * @param tone 语义 tone
 * @returns 形如 `badge ok` 的 class 串
 *
 * 兜底：即使运行时出现未知 tone（例如后端新增枚举但前端未更新），
 * 也返回 neutral，保证 badge 仍有基础样式而不是裸文本。
 */
export function badgeClass(tone: BadgeTone): string {
  return 'badge ' + (TONE_CLASS[tone] ?? 'neutral')
}
