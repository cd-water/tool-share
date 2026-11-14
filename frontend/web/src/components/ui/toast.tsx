/**
 * 文件职责：基于 @base-ui/react/toast 的全局提示（Toast）组件集与单例管理器。
 *
 * 使用方式：
 * - 应用根节点渲染一次 <Toaster>，它负责 Provider、Portal 与视口；
 * - 业务代码直接调用导出的 `toast.add({ title, type, ... })` 弹出提示，
 *   或使用 `toast.add` 的等价封装（如 lib/fx 中的 toast）；
 * - 提示会自动堆叠、支持滑动关闭、展开查看，并在限定数量后丢弃旧提示。
 *
 * 该文件不包含业务文案与图标选择逻辑之外的行为：何时提示、提示什么，
 * 均由页面在请求成功/失败或表单校验失败时决定。所有样式以 data-slot
 * 与 data-* 状态属性为契约，方便测试与全局样式定位。
 */
import * as React from "react"
import { Toast as ToastPrimitive } from "@base-ui/react/toast"
import { cn } from "cn"

import { Button } from "@/components/ui/button"
import { XIcon, CircleCheckIcon, InfoIcon, TriangleAlertIcon, OctagonXIcon, Loader2Icon } from "lucide-react"

// 全局唯一的 toast 管理器实例。
// 不放进 React Context，是因为业务工具函数（非组件）也需要能直接调用；
// 通过 Toaster 注入同一个 manager，保证命令式调用与视口渲染共享队列。
const toast = ToastPrimitive.createToastManager()

/**
 * Toast Provider 包装。
 *
 * @param props base-ui Toast.Provider 的属性，通常包含 toastManager、timeout 等全局配置。
 * @returns 仅提供上下文的 Provider，不渲染任何可见 DOM。
 *
 * 副作用：建立 toast 队列与生命周期管理；必须包裹在所有 toast 调用方之上。
 * 一般不需要直接使用，优先渲染 Toaster。
 */
// Provider 只做上下文注入，因此保持一行实现；
// 不要在这里添加业务配置，全局超时等参数应通过 props 传入。
// 该组件无可见输出，测试时可通过上下文是否存在来判断。
function ToastProvider({ ...props }: ToastPrimitive.Provider.Props) {
  return <ToastPrimitive.Provider {...props} />
// Provider 结束。它不产生 DOM，因此不会影响布局或样式。
// 上下文包含 toastManager，子组件可通过 useToastManager 读取队列。
}

/**
 * Toast Portal。
 *
 * @param props base-ui Toast.Portal 的属性。
 * @returns 把提示挂载到 body 末尾的传送门，避免被父级 overflow/transform 裁切。
 *
 * 副作用：改变 DOM 挂载位置；因此提示不受页面容器层级与 z-index 影响。
 */
// Portal 把提示移出应用 DOM 层级，
// 这样即使页面容器有 overflow:hidden 或 transform，提示仍能完整显示。
function ToastPortal({ ...props }: ToastPrimitive.Portal.Props) {
  return <ToastPrimitive.Portal data-slot="toast-portal" {...props} />
// Portal 结束：提示的实际挂载点由 base-ui 决定，通常为 body 末尾。
// 这保证了提示的层级不受页面局部 stacking context 影响。
}

/**
 * Toast 视口（堆叠容器）。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Viewport 属性透传。
 * @returns 固定在页面顶部居中的容器，宽度自适应且限制最大宽度。
 *
 * 副作用：pointer-events-none 让容器本身不拦截点击，
 * 具体提示通过 pointer-events-auto 恢复交互，避免遮挡页面操作。
 */
// 视口是提示的定位基准：顶部居中、宽度自适应。
// 它在 DOM 中始终存在，但队列为空时不渲染任何提示内容。
function ToastViewport({ className, ...props }: ToastPrimitive.Viewport.Props) {
  return (
    <ToastPrimitive.Viewport
      data-slot="toast-viewport"
      // 视口固定在顶部居中：移动端几乎占满宽度，桌面端限制 max-w-sm。
      // z-50 保证提示浮于弹窗与遮罩之上；outline-none 避免容器获取焦点时出现框。
        // 固定定位 + 位移：left-1/2 与 -translate-x-1/2 实现水平居中。
        // 宽度计算扣除两侧留白，移动端不会贴边；max-w-sm 限制桌面端宽度。
        // z-50 高于常规弹窗，确保提示不被遮挡。
        // pointer-events-none 只作用于容器，子项通过 pointer-events-auto 恢复交互。
      className={cn(
        "pointer-events-none fixed top-4 left-1/2 z-50 w-[calc(100%-2rem)] max-w-sm -translate-x-1/2 outline-none",
        className
      )}
      {...props}
    />
  // 视口渲染结束。队列为空时该容器无可见内容，但仍需存在于 DOM 中。
  // 不要在视口上设置背景或边框，否则空队列时会露出多余装饰。
  )
}

/**
 * 单条 Toast 根节点，负责位置、堆叠、进出场动画与滑动交互。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Root 属性（toast 实例、swipeDirection 等）透传。
 * @returns 一条可交互的提示卡片。
 *
 * 副作用：内部通过 CSS 变量（--toast-index、--toast-swipe-movement-* 等）
 * 计算堆叠偏移与缩放，由 base-ui 在渲染时注入，调用方不要手动覆盖这些变量。
 */
// Toast 根节点消费 base-ui 注入的索引入场：
// 越新的提示 index 越大，位置越靠下且缩放越接近 1。
function Toast({ className, ...props }: ToastPrimitive.Root.Props) {
  return (
    <ToastPrimitive.Root
      data-slot="toast"
        // 定位层级：z-[calc(1000-var(--toast-index))] 让旧提示在下、新提示在上。
        // 形状与色彩：rounded-2xl + border + popover 背景 + shadow-lg。
        // 交互：will-change-transform 提升动画性能，select-none 防止误选文字。
        // 尺寸变量：--height 取 frontmost 高度，折叠时按 peek 露出顶部。
        // 偏移计算：index * gap 形成堆叠间距，swipe 位移叠加在 transform 上。
        // 状态：data-expanded 展开为完整列表，data-limited 超限提示透明隐藏。
        // 退场：data-ending-style 根据 swipe-direction 选择上/下/左/右退出方向。
      className={cn(
        // 以下类名分层说明：
        // 1) 定位与层级：absolute + z-[calc(...)] 让新提示压在旧提示之上；
        // 2) 视觉：圆角、边框、popover 背景与阴影；
        // 3) 动画：transform/opacity/height 过渡，500ms 缓动；
        // 4) 状态：data-expanded 展开、data-limited 超限隐藏、data-ending-style 退场；
        // 5) 滑动：根据 swipe-direction 决定退出方向。
        // 变量 --toast-index / --toast-offset-y / --toast-height 由 base-ui 维护，
        // 这里只消费它们来计算 translateY、scale 与高度。
        "group/toast pointer-events-auto absolute left-0 top-0 z-[calc(1000-var(--toast-index))] w-full origin-top rounded-2xl border bg-popover text-popover-foreground shadow-lg will-change-transform outline-none select-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50",
        // 自定义 CSS 变量区块：
        // --gap 控制提示间距，--peek 控制折叠时露出的高度，
        // --scale 随 index 递减，形成“后层更小”的堆叠透视。
        "[--gap:0.75rem] [--height:var(--toast-frontmost-height,var(--toast-height))] [--offset-y:calc(var(--toast-offset-y)*-1+calc(var(--toast-index)*var(--gap)*-1)+var(--toast-swipe-movement-y))] [--peek:0.75rem] [--scale:calc(max(0,1-(var(--toast-index)*0.1)))] [--shrink:calc(1-var(--scale))]",
        "h-(--height) [transform:translateX(var(--toast-swipe-movement-x))_translateY(calc(var(--toast-swipe-movement-y)-(var(--toast-index)*var(--peek))-(var(--shrink)*var(--height))))_scale(var(--scale))] [transition:transform_500ms_cubic-bezier(0.22,1,0.36,1),opacity_500ms,height_150ms]",
        "after:absolute after:top-full after:left-0 after:h-[calc(var(--gap)+1px)] after:w-full after:content-['']",
        "data-expanded:h-(--toast-height) data-expanded:[transform:translateX(var(--toast-swipe-movement-x))_translateY(var(--offset-y))]",
        "data-limited:opacity-0 data-starting-style:[transform:translateY(-150%)]",
        "[&[data-ending-style]:not([data-limited]):not([data-swipe-direction])]:[transform:translateY(-150%)]",
        "data-ending-style:data-[swipe-direction=down]:[transform:translateY(calc(var(--toast-swipe-movement-y)+150%))]",
        "data-ending-style:data-[swipe-direction=left]:[transform:translateX(calc(var(--toast-swipe-movement-x)-150%))_translateY(var(--offset-y))]",
        "data-ending-style:data-[swipe-direction=right]:[transform:translateX(calc(var(--toast-swipe-movement-x)+150%))_translateY(var(--offset-y))]",
        "data-ending-style:data-[swipe-direction=up]:[transform:translateY(calc(var(--toast-swipe-movement-y)-150%))]",
        "data-expanded:data-ending-style:data-[swipe-direction=down]:[transform:translateY(calc(var(--toast-swipe-movement-y)+150%))]",
        "data-expanded:data-ending-style:data-[swipe-direction=left]:[transform:translateX(calc(var(--toast-swipe-movement-x)-150%))_translateY(var(--offset-y))]",
        "data-expanded:data-ending-style:data-[swipe-direction=right]:[transform:translateX(calc(var(--toast-swipe-movement-x)+150%))_translateY(var(--offset-y))]",
        "data-expanded:data-ending-style:data-[swipe-direction=up]:[transform:translateY(calc(var(--toast-swipe-movement-y)-150%))]",
        className
      )}
      {...props}
    />
  // Root 的 CSS 变量与 data 属性共同决定动画帧，
  // 因此不要在调用方通过 style 覆盖 transform，以免打断堆叠计算。
  // 若需定制颜色，优先覆盖背景与边框类，而不是位置类。
  )
// 组件结束：所有视觉状态都由 CSS 变量与 data-* 属性驱动，
// 不在 React 层维护额外的动画状态，避免与 base-ui 生命周期冲突。
}

/**
 * Toast 内容容器，负责内部横向布局。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Content 属性透传。
 * @returns 图标、标题/描述、操作与关闭按钮的弹性布局容器。
 *
 * 副作用：data-behind 时透明度降低，提示被遮挡时自动弱化；
 * data-expanded 恢复不透明，展开态文字保持可读。
 */
// 内容层只负责排版，不决定提示是否显示；
// 图标是否存在取决于 type，标题/描述由 toast.add 的字段决定。
function ToastContent({ className, ...props }: ToastPrimitive.Content.Props) {
  return (
    <ToastPrimitive.Content
      data-slot="toast-content"
      // h-full 让内容填满 Root 计算出的高度；
      // gap-3 统一图标与文字、操作按钮之间的间距；
      // overflow-hidden 防止长文案在退场动画中溢出圆角。
        // h-full 跟随 Root 高度，保证折叠时内容被裁切而非溢出。
        // data-behind 时降低透明度，表示该提示被更上层遮挡。
        // 展开态恢复不透明，确保用户能读清被展开的提示。
      className={cn(
        "flex h-full items-center gap-3 overflow-hidden p-4 transition-opacity duration-250 ease-[cubic-bezier(0.22,1,0.36,1)] data-behind:opacity-0 data-expanded:opacity-100",
        className
      )}
      {...props}
    />
  // 内容层结束：内部元素顺序固定为图标、文字、操作、关闭，
  // 调整顺序会改变视觉重心与键盘焦点顺序。
  )
}

/**
 * Toast 标题。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Title 属性透传。
 * @returns 单行加粗的标题文本。
 *
 * 副作用：无。标题由 toast.add({ title }) 提供，建议控制在十余字以内。
 */
// 标题是提示的主要信息，建议由业务传入简短动宾短语；
// 组件不截断文本，过长标题会换行并撑高提示。
function ToastTitle({ className, ...props }: ToastPrimitive.Title.Props) {
  return (
    <ToastPrimitive.Title
      data-slot="toast-title"
      // 标题使用 text-sm + font-medium，与描述形成层级但不抢视觉焦点。
      // 颜色继承 popover-foreground，自动适配深浅主题。
      className={cn("text-sm font-medium", className)}
      {...props}
    />
  // 标题渲染结束。空标题仍会占据一行高度，建议始终传 title。
  // 标题文本由调用方负责本地化与文案风格。
  )
}

/**
 * Toast 描述。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Description 属性透传。
 * @returns 次要文字色的补充说明。
 *
 * 副作用：无。用于解释结果或下一步操作，例如“取件码已生成”。
 */
// 描述用于补充结果与下一步建议，通常是可选字段；
// 未传 description 时该节点为空，布局仍保持稳定。
function ToastDescription({
  className,
  ...props
}: ToastPrimitive.Description.Props) {
  return (
    <ToastPrimitive.Description
      data-slot="toast-description"
      // 描述使用次要文字色，长文案允许换行；外层容器已限制最大宽度。
      className={cn("text-sm text-muted-foreground", className)}
      {...props}
    />
  // 描述渲染结束。若描述为空，标题仍保持垂直居中。
  // 多行描述会撑高提示，视口不限制单条高度，但会限制总量。
  )
}

/**
 * Toast 操作按钮（可选），例如“去查看”“重试”。
 *
 * @param props.className 追加样式类。
 * @param props.render 自定义渲染元素，默认是 outline 小号按钮。
 * @param props 其余 Action 属性（children、onClick）透传。
 * @returns 建议由 toast.add({ actionProps: ... }) 配置的操作节点。
 *
 * 副作用：点击会触发调用方传入的回调；本组件不自动关闭提示，
 * 需要时由调用方在回调中显式关闭。
 */
// 操作区只在需要引导用户继续操作时出现；
// 默认渲染为 outline 小按钮，与其他次要操作保持一致。
function ToastAction({
  className,
  render = <Button variant="outline" size="sm" />,
  ...props
}: ToastPrimitive.Action.Props) {
  return (
      // render 默认注入 Button，保证操作按钮与全站按钮风格一致；
      // 调用方可传 render 覆盖为链接等语义元素。
    <ToastPrimitive.Action
      data-slot="toast-action"
      render={render}
      className={cn("shrink-0", className)}
      {...props}
    />
  // 操作渲染结束。按钮始终位于关闭按钮左侧，
  // 这样危险/关闭类操作保持在最右侧，符合常见交互习惯。
  )
}

/**
 * Toast 关闭按钮。
 *
 * @param props.className 追加样式类。
 * @param props.children 自定义图标；默认使用 XIcon。
 * @param props.render 自定义渲染元素，默认是 ghost 图标按钮。
 * @returns 右上角（由内容布局决定）的关闭控件。
 *
 * 副作用：点击后由 base-ui 关闭当前提示；aria-label 提供无障碍名称。
 */
// 关闭控件提供显式退出路径，移动端滑动关闭并非唯一方式；
// 图标按钮保持紧凑，避免抢占标题空间。
function ToastClose({
  className,
  children,
  render = <Button variant="ghost" size="icon-sm" />,
  ...props
}: ToastPrimitive.Close.Props) {
// 关闭按钮的可点击区域比视觉图标大，符合触控最小目标建议；
// 默认图标对读屏隐藏，语义由 aria-label 提供。
  return (
      // 关闭按钮扩大可点击区域（after:-inset-2），但视觉尺寸保持紧凑；
      // hover 时文字色加深，提示可交互；默认图标仅作装饰，对读屏隐藏。
    <ToastPrimitive.Close
      data-slot="toast-close"
      aria-label="Close toast"
      render={render}
        // 相对定位用于扩展点击热区（after:-inset-2）。
        // hover 加深文字色，给出可点击反馈；shrink-0 防止被长标题挤压。
      className={cn(
        "relative shrink-0 text-muted-foreground after:absolute after:-inset-2 after:content-[''] hover:text-foreground",
        className
      )}
      {...props}
    >
      {children ?? (
        <XIcon aria-hidden="true" />
      )}
    </ToastPrimitive.Close>
  // 关闭控件渲染结束。滑动关闭与按钮关闭共享同一套退场动画。
  // 若禁用关闭按钮，应同时提供其他退出方式，避免提示无法消除。
  )
}

/**
 * 根据 toast 类型渲染对应图标。
 *
 * @param props.type toast.add 传入的 type：success / info / warning / error / loading。
 * @returns 对应图标节点；未知类型返回 null，不渲染空占位。
 *
 * 副作用：无。图标仅用于辅助识别，真正的信息由标题与描述承载；
 * error 类型使用 destructive 颜色，loading 类型使用旋转动画。
 */
// 图标选择函数保持纯函数：输入 type，输出图标或 null。
// 新增类型时只需在此添加分支，不影响其他组件。
function ToastIcon({ type }: { type: string | undefined }) {
  let icon: React.ReactNode = null

  // 图标按类型分支渲染，保持与全局语义色一致：
  // success 勾选、info 信息、warning 三角、error 八边形、loading 旋转。
  // 不使用 switch，是为了让新增类型时缺失分支自然落到 null，便于渐进扩展。
  if (type === "success") {
    icon = (
      <CircleCheckIcon aria-hidden="true" />
    )
  }

  if (type === "info") {
    icon = (
      <InfoIcon aria-hidden="true" />
    )
  }

  if (type === "warning") {
    icon = (
      <TriangleAlertIcon aria-hidden="true" />
    )
  }

  if (type === "error") {
    icon = (
      <OctagonXIcon className="text-destructive" aria-hidden="true" />
    )
  }

  if (type === "loading") {
    icon = (
      <Loader2Icon className="animate-spin" aria-hidden="true" />
    )
  }

  if (!icon) {
    return null
  }

  // 图标容器统一约束 svg 尺寸，避免不同图标视觉重量差异过大。
  // 图标是装饰性的，真正的语义由标题/描述承担，因此对读屏隐藏。
  return (
    <span
      data-slot="toast-icon"
      className="shrink-0 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4"
    >
      {icon}
    </span>
  // 图标容器结束。loading 图标继承 animate-spin，
  // 其他图标为静态，避免多条提示同时闪烁。
  )
}

/**
 * Toast 列表：订阅管理器中的队列并渲染每一条提示。
 *
 * @returns 由多条 <Toast> 组成的数组；队列为空时不产生任何 DOM。
 *
 * 副作用：通过 useToastManager 订阅队列变化，队列更新时触发重渲染。
 * 每条提示都复用同一套结构：图标 + 标题/描述 + 操作 + 关闭。
 */
// 列表层只负责遍历与结构复用，不处理单条提示的显示逻辑；
// 队列顺序由管理器维护，列表按原顺序渲染即可。
function ToastList() {
  const { toasts } = ToastPrimitive.useToastManager()

  // toasts 由 base-ui 按栈顺序提供，最新提示通常位于数组末尾；
  // 这里直接 map 渲染，位置与层级由 Toast 根节点根据 index 计算。
  // 不在列表层做过滤或排序，避免与 base-ui 的动画状态不一致。
  return toasts.map((toastItem) => (
    <Toast key={toastItem.id} toast={toastItem}>
      <ToastContent>
        <ToastIcon type={toastItem.type} />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <ToastTitle />
          <ToastDescription />
        </div>
        <ToastAction />
        <ToastClose />
      </ToastContent>
    </Toast>
  // 列表渲染结束：toasts 为空时返回空数组，不产生占位节点。
  // 列表本身不包额外容器，每条 Toast 自行负责定位。
  ))
}

/**
 * Toaster：应用级提示挂载点。
 *
 * @param props.children 应用内容；提示视口会渲染在其后。
 * @param props.toastManager 自定义管理器，默认使用本文件导出的全局实例。
 * @param props 其余 Provider 属性透传。
 * @returns Provider + 子内容 + Portal/Viewport/ToastList 的完整结构。
 *
 * 副作用：建立全局提示上下文。整个应用只需渲染一次；重复渲染会产生
 * 多套视口，导致同一提示出现多次。通常在 App 根组件中调用。
 */
// Toaster 是唯一的应用级入口，应该只渲染一次；
// 嵌套渲染会产生多个视口，同一提示可能重复出现。
function Toaster({
  children,
  toastManager = toast,
  ...props
}: ToastPrimitive.Provider.Props) {
  // Provider 必须在 Portal/Viewport 之外，保证队列上下文可用；
  // children 放在提示结构之前，避免 Portal 影响应用布局。
  // 不使用 Fragment 包裹额外节点，减少不必要的 DOM 层级。
  return (
  // Provider 包裹 children，确保任意层级的组件都能调用 useToastManager；
  // Portal 放在 children 之后，保证提示在 DOM 顺序上位于应用内容之后。
  // Viewport 与 ToastList 分离，便于后续替换列表渲染策略而不影响定位。
    <ToastProvider toastManager={toastManager} {...props}>
      {children}
      <ToastPortal>
        <ToastViewport>
          <ToastList />
        </ToastViewport>
      </ToastPortal>
    </ToastProvider>
  // Toaster 结构结束：Provider → children → Portal → Viewport → List。
  // 该顺序不可随意调整，Portal 必须在 Provider 内部才能拿到队列。
  // 应用卸载时队列随 Provider 一起释放，无需手动清理。
  )
}

const createToastManager = ToastPrimitive.createToastManager
const useToastManager = ToastPrimitive.useToastManager

// 提示队列是内存态：刷新页面后不保留历史提示，这是有意为之。
// 需要持久化的事件应写入消息中心，而不是依赖 toast。
// 导出结束。以上组件构成提示系统的完整公共 API。
// 维护约定：新增导出需同步更新本文件头部说明与使用示例。
// 业务侧不要直接修改 toast 的 DOM，所有定制应通过 props 或管理器完成。
// 若需在非 React 环境使用，可复用 createToastManager 而不引入组件树。
// 对外导出：
// - Toaster 是应用入口组件；
// - toast 是命令式单例，供非组件代码调用；
// - 其余原子组件供需要自定义提示结构的高级场景使用；
// - createToastManager / useToastManager 透传底层能力，便于测试或多实例隔离。
// 业务代码优先使用 toast.add，不要直接操作 DOM 或复制样式。
// 导出契约：
// - 应用根组件只依赖 Toaster；
// - 业务代码只依赖 toast；
// - 原子组件与 hooks 供高级定制与测试使用。
// 保持导出稳定，避免业务侧直接引用 @base-ui/react/toast。
// 若需多套独立提示队列，使用 createToastManager 创建实例并传给 Toaster。
export {
  Toaster,
  Toast,
  ToastAction,
  ToastClose,
  ToastContent,
  ToastDescription,
  ToastPortal,
  ToastProvider,
  ToastTitle,
  ToastViewport,
  createToastManager,
  toast,
  useToastManager,
}
