/**
 * 文件职责：按钮原子组件（Button）及其样式变体表。
 *
 * 基于 @base-ui/react/button 封装，统一了本项目的按钮外观与交互反馈：
 * - variant：default（主操作）、outline（次要操作）、secondary、ghost（弱操作）、
 *   destructive（危险操作，如取消租赁）、link（文字链接式操作）；
 * - size：default、xs、sm、lg 以及 icon 系列（纯图标按钮）。
 *
 * 组件不包含业务逻辑，也不处理 loading 文案；业务页面需自行在 disabled
 * 与图标（如 Loader2）之间切换。统一使用本组件可以保证焦点环、禁用态、
 * 按压位移与暗色主题行为一致。
 */
import { Button as ButtonPrimitive } from "@base-ui/react/button"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
  // 变体矩阵说明：
  // 第一段是所有按钮共享的基线样式——行内弹性布局、居中、圆角、字号、
  // 过渡动画、焦点环、禁用态、aria-invalid 错误态与 svg 尺寸约束。
  // 这段基线的顺序很重要：disabled:* 与 aria-invalid:* 依赖 Tailwind 的
  // 状态选择器优先级，调用方覆盖时请避免直接改动这些状态规则。
  // 共享基线同时保证图标与文字在垂直方向居中对齐。
    // 变体对象按“层级 / 尺寸”两个维度组织：
    // 层级回答“这个动作有多重要”，尺寸回答“它在页面中的密度”。
    // 两者正交组合，避免为每个业务场景新增一次性样式。
    variants: {
    // variant 控制“操作重要性”的视觉层级：
    // - default 使用品牌主色，页面中同一区域通常只出现一个；
    // - outline 用于并列的次要动作；
    // - secondary 用于低对比度的辅助动作；
    // - ghost 用于工具栏或列表内的轻量动作；
    // - destructive 使用低饱和红底，避免误触但保持警示；
    // - link 仅用于文案式跳转，不产生按钮块级占位。
    // 每种变体都定义了 hover 与 aria-expanded（下拉触发时）的反馈。
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/80",
        outline:
          "border-border bg-background hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-[color-mix(in_oklch,var(--secondary),var(--foreground)_5%)] aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/20 focus-visible:border-destructive/40 focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:hover:bg-destructive/30 dark:focus-visible:ring-destructive/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
    // size 控制高度、内边距、圆角与图标尺寸：
    // xs/sm 用于表格行内或紧凑工具栏，lg 用于表单主按钮；
    // icon 系列为纯图标按钮，尺寸与文字按钮的对应档位保持一致；
    // has-data-[icon=...] 允许调用方通过 data-icon 标记图标位置，
    // 从而在图标侧收紧内边距，避免按钮视觉重心偏移。
      // 尺寸档位的优先级高于内边距覆盖：
      // 如果调用方同时传 size 和自定义高度，自定义高度会通过 className 生效，
      // 但内置的 gap、圆角与图标尺寸仍由对应档位决定。
      size: {
        default:
          "h-8 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3",
        sm: "h-7 gap-1 rounded-[min(var(--radius-md),12px)] px-2.5 text-[0.8rem] in-data-[slot=button-group]:rounded-lg has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-9 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        icon: "size-8",
        "icon-xs":
          "size-6 rounded-[min(var(--radius-md),10px)] in-data-[slot=button-group]:rounded-lg [&_svg:not([class*='size-'])]:size-3",
        "icon-sm":
          "size-7 rounded-[min(var(--radius-md),12px)] in-data-[slot=button-group]:rounded-lg",
        "icon-lg": "size-9",
      },
    },
    // 默认值保证 <Button /> 在无参数时即为“主操作、默认尺寸”，
    // 减少业务页面的重复配置；需要其他层级时显式传 variant/size。
    // 默认变体集中在此声明，便于全局调整按钮默认风格；
    // 修改默认值会影响所有未显式传参的调用点，需谨慎。
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

/**
 * 按钮组件。
 *
 * @param props.className 追加样式类；与变体类合并，调用方可做局部覆盖。
 * @param props.variant 视觉层级，默认 "default"。
 * @param props.size 尺寸档位，默认 "default"。
 * @param props 其余属性（type、disabled、onClick、aria-*、children）透传。
 * @returns 带 data-slot="button" 的按钮元素。
 *
 * 副作用：无；不发起请求、不订阅状态。
 * 注意：表单内的按钮请显式传 type="button" 或 "submit"，原生默认是 submit，
 * 可能意外触发表单提交。
 */
function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  // base-ui 的 Button 已处理键盘与按压语义，
  // 这里只负责样式解析与属性透传，避免重复实现原生行为。
  // 传入的 type/disabled/aria-* 会覆盖默认行为，调用方应确保语义正确。
  return (
  // 先解析变体再合并调用方 className：
  // cn 会保留后出现类的优先级，因此业务侧可用同名工具类覆盖内置样式。
  // 不在组件内部处理 loading，避免把业务状态耦合进原子组件。
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

// 同时导出 Button 与 buttonVariants：
// - Button 供常规 JSX 使用；
// - buttonVariants 供需要把按钮样式挂到 <a> 或第三方组件的场景，
//   避免为了外观而强制改变语义标签。
// 新增变体时应同步更新 TSDoc 中的语义说明，保持文档与实现一致。
// 导出顺序保持 Button 在前，便于 IDE 自动导入时优先提示组件而非样式函数。
// buttonVariants 是公共 API 的一部分，供“链接外观按钮”等场景复用。
// 若未来拆分图标按钮，应保留当前 size 契约，避免破坏已有调用点。
export { Button, buttonVariants }
