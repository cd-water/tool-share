/**
 * 徽标样式工具。
 *
 * 把 Element Plus 的语义 tag 类型映射为设计系统的 .badge 修饰类，
 * 让「状态 → 颜色」只在一处定义，表格、详情和筛选项共用。
 */
import type { TagType } from '@/utils/enums'

// TagType 是业务语义，badge 修饰类才是实际样式：
// primary→brand（品牌蓝）、success→ok（绿）、warning→warn（橙）、
// info→info（灰）、danger→bad（红）。新增语义时在这里补齐映射。
// 把 Element Plus 的 tag 类型映射到设计系统的 .badge 修饰类
const TONE: Record<TagType, string> = {
  primary: 'brand',
  success: 'ok',
  warning: 'warn',
  info: 'info',
  danger: 'bad',
}

/**
 * 生成徽标的 CSS 类名。
 *
 * @param tag 业务语义 tag 类型
 * @returns 形如 `badge ok` 的类名，供模板直接绑定 class
 * 该函数无副作用；传入类型受 TagType 约束，不会出现未知样式。
 */
export function badgeClass(tag: TagType): string {
  return 'badge ' + TONE[tag]
}
