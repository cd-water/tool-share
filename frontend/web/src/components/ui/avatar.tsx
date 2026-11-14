/**
 * 文件职责：基于 @base-ui/react/avatar 封装的头像基础组件集。
 *
 * 该文件只负责表现层组合，不发起任何网络请求、不读写全局状态：
 * - Avatar 提供圆形容器与尺寸档位（default / sm / lg），尺寸通过 data-size 暴露给子组件；
 * - AvatarImage 渲染真实图片，加载失败时由 base-ui 自动切换到 Fallback；
 * - AvatarFallback 在无图片或图片失败时展示文字占位；
 * - AvatarBadge 用于右上/右下角的状态点；
 * - AvatarGroup / AvatarGroupCount 用于堆叠展示多个头像及“+N”数量。
 *
 * 使用方：UserMenu（导航栏用户入口）等。所有组件都支持 className 透传，
 * 便于业务侧用 Tailwind 覆盖圆角、尺寸与配色，而不必改动本文件。
 */
import * as React from "react"
import { Avatar as AvatarPrimitive } from "@base-ui/react/avatar"
import { cn } from "cn"

/**
 * 头像根容器。
 *
 * @param props.className 追加的样式类，最终与内置类合并（后者优先级由调用方决定）。
 * @param props.size 尺寸档位，默认 "default"；sm 对应 24px、default 对应 32px、lg 对应 40px。
 * @param props 其余属性原样透传给 base-ui 的 Avatar.Root。
 * @returns 一个带 data-slot="avatar" 与 data-size 标记的圆形容器。
 *
 * 副作用：无；不订阅状态、不触发布局测量。data-slot / data-size 供子组件
 * 使用 group-data-[size=...] 选择器自适应，因此调用方不要随意移除这两个属性。
 */
function Avatar({
  className,
  size = "default",
  ...props
}: AvatarPrimitive.Root.Props & {
  size?: "default" | "sm" | "lg"
}) {
  // 渲染策略：不区分尺寸分支，统一交给 data-size + Tailwind 数据属性选择器，
  // 这样新增档位只需改样式层，无需改动组件逻辑。
  // 根节点保持 position: relative，供 AvatarBadge 绝对定位。
  // after: 伪元素绘制内描边，并通过 mix-blend 适配深浅主题。
  return (
    // 根节点只承载布局与尺寸语义，图片和占位内容都由子组件决定；
    // data-slot/data-size 是子组件 Tailwind 选择器的钩子，必须保留。
    <AvatarPrimitive.Root
      data-slot="avatar"
      data-size={size}
      className={cn(
        "group/avatar relative flex size-8 shrink-0 rounded-full select-none after:absolute after:inset-0 after:rounded-full after:border after:border-border after:mix-blend-darken data-[size=lg]:size-10 data-[size=sm]:size-6 dark:after:mix-blend-lighten",
        className
      )}
      {...props}
    />
  )
}

/**
 * 头像图片。
 *
 * @param props.className 追加样式类；内置类保证图片等比裁切为圆形。
 * @param props 其余 img 属性透传给 base-ui Avatar.Image。
 * @returns 撑满父容器的圆形图片节点。
 *
 * 副作用：由 base-ui 在图片加载失败时触发 onError 并通知 Fallback 显示，
 * 业务侧无需自行处理 404 或空 src；空 src 时父级应直接渲染 Fallback。
 */
function AvatarImage({ className, ...props }: AvatarPrimitive.Image.Props) {
  // 图片节点始终撑满根容器：size-full + aspect-square + object-cover，
  // 保证任意比例的源图都被裁切成圆形且不变形。
  // 加载失败不在此处兜底，交给 base-ui 的 Fallback 机制。
  // 根节点不渲染额外 DOM，仅把属性透传给 base-ui，保持组件薄且可控。
  // className 合并顺序为内置 → 调用方，调用方可以用同名 Tailwind 类覆盖。
  return (
    // 图片本身不处理错误回调，失败状态由 base-ui 的 Avatar 上下文统一管理；
    // 因此这里只需保证尺寸与裁切样式正确。
    <AvatarPrimitive.Image
      data-slot="avatar-image"
      className={cn(
        "aspect-square size-full rounded-full object-cover",
        className
      )}
      {...props}
    />
  )
}

/**
 * 头像占位内容（图片缺失或加载失败时显示）。
 *
 * @param props.className 追加样式类，通常用于调整字号或背景色。
 * @param props 其余属性透传给 base-ui Avatar.Fallback。
 * @returns 居中显示的占位节点，默认使用 muted 背景与次要文字色。
 *
 * 使用约定：占位文字应只取昵称首字，避免长文本撑破圆形布局。
 * 副作用：无；仅做展示。
 */
function AvatarFallback({
  className,
  ...props
}: AvatarPrimitive.Fallback.Props) {
  // 占位节点同样撑满父容器；文字由调用方通过 children 传入。
  // sm 档位使用更小字号，避免单字在 24px 圆内显得拥挤。
  // 背景使用 muted，确保与页面主背景有足够对比度。
  // Fallback 与 Image 共用圆角与尺寸，切换时不会出现布局跳动。
  // children 为空时也能正常渲染空占位，不会抛错。
  return (
    <AvatarPrimitive.Fallback
      data-slot="avatar-fallback"
      className={cn(
        "flex size-full items-center justify-center rounded-full bg-muted text-sm text-muted-foreground group-data-[size=sm]/avatar:text-xs",
        className
      )}
      {...props}
    />
  )
}

/**
 * 头像角标，用于展示在线状态、未读提示等小圆点。
 *
 * @param props.className 追加样式类，可覆盖角标颜色（如成功绿 / 危险红）。
 * @param props 其余 span 属性透传，支持 aria-label 等无障碍信息。
 * @returns 绝对定位在右下角的角标节点。
 *
 * 角标尺寸随父级 Avatar 的 data-size 自动缩放：sm 为 8px、default 为 10px、
 * lg 为 12px；内部 svg 在 sm 档位会被隐藏以保持视觉整洁。
 * 副作用：无。
 */
function AvatarBadge({ className, ...props }: React.ComponentProps<"span">) {
  // 角标使用 absolute 定位在右下角，z-10 保证覆盖在头像图片之上。
  // ring-2 + ring-background 让角标与头像之间出现一圈“背景色间隙”，
  // 在图片颜色复杂时仍能辨认边界。
  // 尺寸随父级 data-size 变化：sm 8px / default 10px / lg 12px。
  // 角标是纯装饰元素，业务侧应同时提供 aria-label 说明其含义。
  // data-slot 便于测试与样式定位，不应被调用方移除。
  return (
    <span
      data-slot="avatar-badge"
      className={cn(
        "absolute right-0 bottom-0 z-10 inline-flex items-center justify-center rounded-full bg-primary text-primary-foreground bg-blend-color ring-2 ring-background select-none",
        "group-data-[size=sm]/avatar:size-2 group-data-[size=sm]/avatar:[&>svg]:hidden",
        "group-data-[size=default]/avatar:size-2.5 group-data-[size=default]/avatar:[&>svg]:size-2",
        "group-data-[size=lg]/avatar:size-3 group-data-[size=lg]/avatar:[&>svg]:size-2",
        className
      )}
      {...props}
    />
  )
}

/**
 * 头像组容器，将多个 Avatar 负间距堆叠展示。
 *
 * @param props.className 追加样式类。
 * @param props 其余 div 属性透传。
 * @returns 使用 -space-x-2 排列、并给每个头像加背景描边的容器。
 *
 * 使用约定：子元素应为 Avatar，且可通过 data-slot="avatar" 被组选择器命中；
 * 描边颜色取 ring-background，从而在重叠处形成清晰分隔。
 * 副作用：无。
 */
function AvatarGroup({ className, ...props }: React.ComponentProps<"div">) {
  // 使用负间距把相邻头像叠在一起；每个子头像再套一圈背景色 ring，
  // 从视觉上切出层次，避免重叠处糊成一团。
  // 该容器依赖子元素的 data-slot="avatar" 选择器，因此子元素应使用 Avatar。
  // 组容器本身不限制子元素数量，超出宽度时由父级决定换行或裁切。
  // 负间距仅影响水平方向，垂直方向保持自然高度。
  return (
    <div
      data-slot="avatar-group"
      className={cn(
        "group/avatar-group flex -space-x-2 *:data-[slot=avatar]:ring-2 *:data-[slot=avatar]:ring-background",
        className
      )}
      {...props}
    />
  )
}

/**
 * 头像组末尾的“+N”计数节点。
 *
 * @param props.className 追加样式类。
 * @param props 其余 div 属性透传，通常在其中放入剩余数量文字。
 * @returns 与头像同尺寸的圆形计数节点。
 *
 * 尺寸会跟随头像组的档位（sm/default/lg）自动变化，并同步调整内部 svg 尺寸，
 * 以便与相邻 Avatar 对齐。副作用：无。
 */
function AvatarGroupCount({
  className,
  ...props
}: React.ComponentProps<"div">) {
  // 计数节点与头像保持同尺寸，并用 ring-2 与相邻头像形成一致的分隔线。
  // group-has-data-[size=...] 让计数节点跟随组内头像的实际档位，
  // 即使调用方混用尺寸也能保持大致对齐。
  // 内部 svg 尺寸同样分档调整，避免图标在 24px 圆内溢出。
  // 计数节点通常放在组内最后一个位置；children 一般是“+N”文本。
  // 与头像一样使用 group 数据属性联动，避免调用方手动传尺寸。
  return (
    <div
      data-slot="avatar-group-count"
      className={cn(
        "relative flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-sm text-muted-foreground ring-2 ring-background group-has-data-[size=lg]/avatar-group:size-10 group-has-data-[size=sm]/avatar-group:size-6 [&>svg]:size-4 group-has-data-[size=lg]/avatar-group:[&>svg]:size-5 group-has-data-[size=sm]/avatar-group:[&>svg]:size-3",
        className
      )}
      {...props}
    />
  )
}

/*
 * 对外导出全部头像原子组件。
 * 业务侧按需组合，例如 UserMenu 只使用 Avatar + AvatarImage + AvatarFallback；
 * 不要在业务组件中直接引用 @base-ui/react/avatar，统一经由本文件以获得一致的样式契约。
 */
// 导出清单保持与 base-ui Avatar 的原子能力一一对应：
// 业务组件只依赖本文件，后续如需替换底层库，仅需改动此处的适配层。
// 默认不导出样式变体，避免调用方绕过设计系统直接拼 className。
// 如需新增“带在线状态的头像”等业务组合，应在 components 层封装，而不是修改本文件。
// 注意：所有组件的 Props 都直接复用 base-ui 或 React 原生类型，升级时能获得类型提示。
export {
  Avatar,
  AvatarImage,
  AvatarFallback,
  AvatarGroup,
  AvatarGroupCount,
  AvatarBadge,
}
