/**
 * 文件职责：空状态（Empty）组合组件集。
 *
 * 用于列表无数据、筛选无结果、AI 推荐无匹配等场景，统一“图标 + 标题 + 说明
 * + 可选操作”的排版。组件本身不判断何时为空——业务页面根据查询结果决定
 * 是否渲染 <Empty>，例如 ToolsPage 在 records 为空时展示“暂无符合条件的工具”。
 *
 * 这不是弹窗，也不具备遮罩：它只是占据内容区的一块普通布局，因此可以安全地
 * 放在卡片、网格或任意容器中。各子组件都支持 className 覆盖。
 */
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

/**
 * 空状态根容器。
 *
 * @param props.className 追加样式类。
 * @param props 其余 div 属性透传。
 * @returns 纵向居中、水平居中的弹性容器，默认带虚线边框语义（由调用方决定是否显示）。
 *
 * 副作用：无。建议内部依次放置 EmptyHeader（媒体、标题、描述）与 EmptyContent
 * （操作按钮），以保持全站空状态的一致节奏。
 */
function Empty({ className, ...props }: React.ComponentProps<"div">) {
  // 根容器使用 flex-1 撑满可用宽度，但最小宽度为 0，
  // 因此在窄屏或栅格中不会溢出；text-balance 让标题换行更自然。
  // 虚线边框只是可选视觉提示，是否出现取决于调用方是否传入 border 类。
  // data-slot 供测试选择器使用，业务样式请优先通过 className。
  return (
    <div
      data-slot="empty"
      className={cn(
        "flex w-full min-w-0 flex-1 flex-col items-center justify-center gap-4 rounded-xl border-dashed p-6 text-center text-balance",
        className
      )}
      {...props}
    />
  // 到这里根容器的属性组合完成：布局类、调用方类与其余透传属性均已就位。
  // 组件不包裹额外 Provider，因此不会影响外部上下文。
  // 结束前不做任何状态写入，保持渲染纯函数特性。
  )
}

/**
 * 空状态头部区域，用于承载图标、标题和描述。
 *
 * @param props.className 追加样式类。
 * @returns 最大宽度受控的纵向容器，避免长文案在大屏上拉成一行。
 *
 * 副作用：无。最大宽度 max-w-sm 是设计约束，不是功能限制。
 */
function EmptyHeader({ className, ...props }: React.ComponentProps<"div">) {
  // 头部限制最大宽度，避免在宽屏卡片中正文被拉得过长；
  // gap-2 控制图标、标题、描述之间的垂直节奏。
  // 该容器不处理横向排列，图标与文字始终居中堆叠。
  return (
    <div
      data-slot="empty-header"
      className={cn("flex max-w-sm flex-col items-center gap-2", className)}
      {...props}
    />
  // 头部区域没有子节点数量限制，调用方可按需插入徽标或补充说明。
  // 该容器始终纵向排列，不能通过 className 之外的方式改成横向。
  // 若需横向空状态，请组合 EmptyContent 并自定义布局。
  )
}

/**
 * 空状态媒体（图标/插画）的样式变体。
 *
 * variant="icon" 提供带底色的方形图标容器，适合小型线性图标；
 * variant="default" 则完全透明，适合业务自定义插画。
 * 统一在此管理尺寸与 svg 约束，避免各页面重复写 size-* 类。
 */
const emptyMediaVariants = cva(
  "mb-2 flex shrink-0 items-center justify-center [&_svg]:pointer-events-none [&_svg]:shrink-0",
  {
      // 媒体变体只影响图标区域：
      // default 不提供任何装饰，适合业务自带插画；
      // icon 提供圆角底色与内边距，让线性图标更像一个“状态徽章”。
    variants: {
      variant: {
        default: "bg-transparent",
        icon: "flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground [&_svg:not([class*='size-'])]:size-4",
      },
    },
    // 默认使用透明变体，避免在未指定时出现意外底色；
    // 需要图标容器时显式传 variant="icon"。
    defaultVariants: {
      variant: "default",
    },
  }
)

/**
 * 空状态媒体容器。
 *
 * @param props.className 追加样式类。
 * @param props.variant 媒体样式，默认 "default"；图标场景建议传 "icon"。
 * @returns 居中排列媒体内容的 div，带 data-slot="empty-icon"。
 *
 * 副作用：无。传入 SVG 时会受 [&_svg] 规则约束尺寸，防止图标过大。
 */
function EmptyMedia({
  className,
  variant = "default",
  ...props
}: React.ComponentProps<"div"> & VariantProps<typeof emptyMediaVariants>) {
  // 合并变体类与调用方类，后者可覆盖尺寸和背景；
  // 同时输出 data-variant，方便调试与外部样式按变体定位。
  // 内部 svg 的尺寸约束由 cva 中的选择器统一处理。
  return (
    <div
      data-slot="empty-icon"
      data-variant={variant}
      className={cn(emptyMediaVariants({ variant, className }))}
      {...props}
    />
  // 媒体容器渲染完成；内部图标尺寸由父级选择器统一约束。
  // data-variant 同时作为样式钩子与调试标记存在。
  // 调用方若传自定义插画，可忽略 variant，仅使用 className 控制尺寸。
  )
}

/**
 * 空状态标题。
 *
 * @param props.className 追加样式类。
 * @returns 使用 heading 字体、较小字号的标题节点。
 *
 * 副作用：无。标题应简短（一般不超过一行），详细解释放到 EmptyDescription。
 */
function EmptyTitle({ className, ...props }: React.ComponentProps<"div">) {
  // 标题只负责排版，不处理点击或跳转；
  // 字号刻意小于普通标题，避免空状态在页面中喧宾夺主。
  // 若需要强调，可在调用方传入 text-base 等类覆盖。
  return (
    <div
      data-slot="empty-title"
      className={cn(
        "font-heading text-sm font-medium tracking-tight",
        className
      )}
      {...props}
    />
  // 标题节点支持任意内联元素；纯文本场景无需额外包装。
  // 字体粗细与颜色继承自设计系统，保证与页面其他标题一致。
  // 空标题虽合法但不推荐，容易造成布局空洞。
  )
}

/**
 * 空状态描述文字。
 *
 * @param props.className 追加样式类。
 * @returns 次要文字色的说明段落；内部链接会自动附加下划线与主题色。
 *
 * 副作用：无。适合放“如何解决当前空状态”的操作指引，例如“换个关键词试试”。
 */
function EmptyDescription({ className, ...props }: React.ComponentProps<"p">) {
  // 描述使用 text-sm/relaxed 提升长句可读性；
  // 内部 a 标签自动继承主题色与下划线，无需业务侧重复处理。
  // 该节点可以是 <div>，实际语义由 children 决定。
  return (
    <div
      data-slot="empty-description"
      className={cn(
        "text-sm/relaxed text-muted-foreground [&>a]:underline [&>a]:underline-offset-4 [&>a:hover]:text-primary",
        className
      )}
      {...props}
    />
  // 描述支持富文本与链接，适用于给出下一步操作指引。
  // 颜色为次要文字色，与标题形成层级差。
  // 若文案极长，建议拆分为多段或改用普通内容区。
  )
}

/**
 * 空状态操作区。
 *
 * @param props.className 追加样式类。
 * @returns 居中的纵向容器，用于放置“重置筛选”“去工具广场”等按钮。
 *
 * 副作用：无。操作按钮应由调用方传入，本组件不内置导航行为。
 */
function EmptyContent({ className, ...props }: React.ComponentProps<"div">) {
  // 操作区宽度受 max-w-sm 限制并与头部对齐；
  // 纵向排列适合一到两个按钮，避免横向拥挤。
  // 按钮的点击行为完全由调用方定义。
  return (
    <div
      data-slot="empty-content"
      className={cn(
        "flex w-full max-w-sm min-w-0 flex-col items-center gap-2.5 text-sm text-balance",
        className
      )}
      {...props}
    />
  )
}

// 导出结束：以上七个组件构成空状态的完整积木。
// 页面通常只需 Empty + EmptyHeader（含媒体/标题/描述），操作区按需添加。
// 若后续需要统一空状态插画，可在此文件内新增默认媒体常量，而不是改各页面。
// 按“根容器 → 头部 → 媒体 → 标题 → 描述 → 操作”的层级导出，
// 让业务侧可以自由组合；不需要的层级直接省略即可，不会产生额外 DOM。
// 新增子组件时请同步维护 data-slot，测试与样式定位依赖该契约。
// 空状态不是错误提示：真正的接口错误应使用 toast 或行内错误条，而不是 Empty。
// 保持导出集合稳定：新增组件属于公共 API 变更，需要同步更新文档。
// 组合式设计让页面可以只使用 Empty + EmptyHeader 的最小形态，
// 也可以补齐描述与操作，形成完整的空状态引导。
// 不要在此文件内引入业务 API 或路由，保持原子组件的纯净性。
export {
  Empty,
  EmptyHeader,
  EmptyTitle,
  EmptyDescription,
  EmptyContent,
  EmptyMedia,
}
