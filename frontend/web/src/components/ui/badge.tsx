/**
 * 文件职责：基于 class-variance-authority（cva）的徽标（Badge）原子组件。
 *
 * 徽标用于在列表、详情与状态栏中展示简短的状态词，例如“可用 / 已租出”
 * “已核验 / 维修中”“已实名 / 未实名”。它只做视觉呈现，不包含业务判断：
 * 具体使用哪种 tone 由调用方根据状态映射函数（如 lib/status 中的 *Tone）决定。
 *
 * 设计约束：
 * - 所有尺寸固定为 text-xs + 紧凑内边距，避免在表格/卡片中挤压布局；
 * - whitespace-nowrap 保证“待支付”等词不被折行；
 * - 默认 tone 为 neutral，调用方不传 tone 时也有稳定外观；
 * - 支持 className 覆盖，但业务侧应优先使用 tone，保持全站状态色一致。
 */
import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/**
 * 徽标样式变体表。
 *
 * tone 的语义约定：
 * - neutral：无状态/中性信息（分类、渠道）；
 * - primary：品牌强调（评分、推荐）；
 * - success：成功、可用、已实名；
 * - warning：待处理、维修中、未实名；
 * - danger：失败、逾期、损坏；
 * - info：进行中、已发送等提示性状态。
 *
 * 通过 cva 统一管理颜色，避免各页面重复拼接 Tailwind 类导致状态色漂移。
 */
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium whitespace-nowrap',
  {
    // 每个 tone 只负责颜色与边框，不改动尺寸和排版，
    // 这样切换状态色时徽标高度保持不变，列表不会产生抖动。
    // 颜色使用低饱和背景 + 高对比文字，兼顾浅色主题下的可读性。
    variants: {
      tone: {
        neutral: 'border-border bg-muted text-muted-foreground',
        primary: 'border-primary/40 bg-primary/15 text-[#7a5300]',
        success: 'border-emerald-200 bg-emerald-50 text-emerald-700',
        warning: 'border-amber-200 bg-amber-50 text-amber-700',
        danger: 'border-red-200 bg-red-50 text-red-600',
        info: 'border-sky-200 bg-sky-50 text-sky-700',
      },
    },
    // 默认 tone 保证组件在缺省调用时依然可读；
    // 如果业务出现新的状态语义，应在此扩展而非在页面里写死颜色。
    defaultVariants: {
      tone: 'neutral',
    },
  },
)

// 从变体定义反推出合法 tone 联合类型，供调用方与状态映射函数复用，
// 避免在业务代码中重复声明字符串字面量。
export type BadgeTone = NonNullable<
  VariantProps<typeof badgeVariants>['tone']
>

/**
 * 徽标组件。
 *
 * @param props.className 追加样式类；会覆盖 cva 生成的同名工具类。
 * @param props.tone 语义色，默认 "neutral"，取值范围由 badgeVariants 定义。
 * @param props 其余 span 属性（children、title、aria-* 等）原样透传。
 * @returns 一个带 data-slot="badge" 的内联块元素。
 *
 * 副作用：无；纯展示组件。调用方应保证 children 为短文本，
 * 长文本请使用普通段落而不是徽标，以免破坏行内对齐。
 */
// 组件只组合样式，不做条件渲染，因此保持为普通函数组件。
// 传入的 aria-label / title 会原样落到 span 上，便于图标型徽标补充语义。
export function Badge({
  className,
  tone,
  ...props
}: React.ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  // 渲染策略：data-slot 供测试与全局样式定位；tone 由 cva 解析为具体类名。
  // 内置的 rounded-full / border / text-xs 是设计系统基线，不建议在调用方覆盖尺寸。
  // 先解析变体再把调用方 className 追加到末尾，
  // 使得业务侧仍可用同名工具类做局部覆盖。
  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ tone }), className)}
      {...props}
    />
  )
}
