/**
 * 文件职责：基于 @base-ui/react/menu 的下拉菜单组件集（DropdownMenu）。
 *
 * 该文件是一层“样式 + 组合”适配：把 base-ui 的 Root / Trigger / Portal /
 * Positioner / Popup / Item 等原语，包装成本项目统一的外观与命名。
 * 它不包含菜单项数据、权限判断或导航逻辑——这些由调用方（如 UserMenu）
 * 通过 children、onClick 与路由完成。
 *
 * 主要能力：
 * - 支持普通菜单项、破坏性菜单项（variant="destructive"）、分组、标签；
 * - 支持子菜单、复选项、单选组；
 * - 支持快捷键提示与分隔线；
 * - 统一了圆角、阴影、焦点态、进入/退出动画与定位偏移。
 *
 * 使用约束：菜单必须由 DropdownMenuTrigger 触发，内容放在 DropdownMenuContent
 * 中；所有组件都支持 className 覆盖，但不要移除 data-slot 等内部契约属性。
 */
"use client"

import * as React from "react"
import { Menu as MenuPrimitive } from "@base-ui/react/menu"
import { cn } from "cn"
import { ChevronRightIcon, CheckIcon } from "lucide-react"

/**
 * 下拉菜单根节点，持有打开/关闭状态。
 *
 * @param props base-ui Menu.Root 的属性（open、onOpenChange、modal 等）。
 * @returns 仅提供上下文，不渲染可见节点。
 *
 * 副作用：管理焦点陷阱与关闭行为；非受控用法下由 base-ui 内部维护状态。
 */
function DropdownMenu({ ...props }: MenuPrimitive.Root.Props) {
  // Root 直接透传，不做额外包装：开合状态、焦点陷阱与关闭原因
  // 全部交给 base-ui，避免重复实现导致行为差异。
  // data-slot 作为测试与外部样式的稳定锚点，不随重构消失。
  // 若业务需要受控模式，只需传 open/onOpenChange，无需改本组件。
  // 非受控模式下；点击外部、Esc 或选择菜单项都会自动关闭。
  // Root 不产生任何可见 DOM，因此不影响触发器的布局。
  return <MenuPrimitive.Root data-slot="dropdown-menu" {...props} />
// Root 组件结束。它没有可见输出，因此不影响触发器与菜单内容之外的布局。
// 状态变化只会通过子组件重渲染体现，调用方可安全地在任意位置使用。
// 如需在关闭时执行清理，使用 onOpenChange 而非监听 DOM 事件。
}

/**
 * 菜单内容传送门。
 *
 * @param props base-ui Menu.Portal 的属性。
 * @returns 把菜单挂载到 body 末尾的传送门。
 *
 * 副作用：菜单脱离父级 DOM 层级，避免被 overflow/transform 裁切；
 * z-index 与定位由 Positioner 负责。
 */
function DropdownMenuPortal({ ...props }: MenuPrimitive.Portal.Props) {
  // Portal 无可见输出，只改变 DOM 挂载位置。
  // 这样做可以避免菜单被表格、卡片等容器的 overflow 裁切。
  // 传送门的目标容器由 base-ui 管理，通常是 document.body。
  // 若页面存在多个独立层叠上下文，仍能通过 Positioner 的 z-50 保持可见。
  // 不需要手动清理节点，关闭时由 base-ui 卸载。
  return <MenuPrimitive.Portal data-slot="dropdown-menu-portal" {...props} />
// Portal 结束：菜单节点在关闭动画结束后被卸载，不会长期占用 DOM。
// 若业务需要测量菜单尺寸，应在打开后的回调中读取，而不是渲染前。
}

/**
 * 菜单触发器。
 *
 * @param props base-ui Menu.Trigger 的属性（children、className、disabled 等）。
 * @returns 可点击/可键盘激活的触发元素。
 *
 * 副作用：点击、Enter、Space 或方向键会打开菜单；具体键位由 base-ui 处理。
 * 调用方需保证触发器具备可访问名称（文字或 aria-label）。
 */
function DropdownMenuTrigger({ ...props }: MenuPrimitive.Trigger.Props) {
  // Trigger 保留原生按钮语义（base-ui 默认渲染 button），
  // 因此天然支持键盘聚焦与屏幕阅读器。
  // 调用方应保证 children 可读，或补充 aria-label。
  // openOnHover 等行为由调用方通过属性开启，本组件不预设。
  // disabled 时 base-ui 会阻止打开，并保留禁用态样式。
  return <MenuPrimitive.Trigger data-slot="dropdown-menu-trigger" {...props} />
// Trigger 组件结束：所有视觉反馈（聚焦环、按压态）来自 base-ui 的数据属性。
// 调用方可通过 className 覆盖外观，但不应改变按钮的语义标签。
// 触发器内容可以是图标 + 文本，注意保持最小点击面积。
}

/**
 * 菜单内容面板。
 *
 * @param props.align 相对触发器的对齐方式，默认 "start"。
 * @param props.alignOffset 对齐方向上的像素偏移，默认 0。
 * @param props.side 弹出方向，默认 "bottom"。
 * @param props.sideOffset 与触发器的间距，默认 4。
 * @param props.className 追加样式类。
 * @param props 其余 Popup 属性透传。
 * @returns 带定位与进出场动画的弹出面板。
 *
 * 副作用：打开时把焦点移入菜单，关闭时恢复焦点到触发器；
 * 面板高度受 --available-height 约束，超出时内部滚动。
 */
function DropdownMenuContent({
  align = "start",
  alignOffset = 0,
  side = "bottom",
  sideOffset = 4,
  className,
  ...props
}: MenuPrimitive.Popup.Props &
  Pick<
    MenuPrimitive.Positioner.Props,
    "align" | "alignOffset" | "side" | "sideOffset"
  >) {
    // 内容面板通过 Portal 渲染到 body，再由 Positioner 计算坐标；
    // 这样菜单不会受触发器的 transform 或 overflow 影响。
    // align/side/offset 的默认值在函数签名中给出，调用方可按需覆盖。
    // 面板宽度默认与触发器一致，适合“用户菜单”这类紧凑场景；
    // 如需更宽的内容，可在 className 中覆盖 w-*。
    // 高度上限由 --available-height 提供，空间不足时自动滚动。
    // 焦点进入后由 base-ui 管理方向键循环，无需额外代码。
  return (
    // Portal + Positioner 的组合负责“挂到哪里”和“相对哪里定位”；
    // align/side/offset 全部透传到 Positioner，保证与触发器对齐。
    // isolate + z-50 建立独立层叠上下文，避免菜单被页面其他浮层覆盖。
    <MenuPrimitive.Portal>
      <MenuPrimitive.Positioner
        className="isolate z-50 outline-none"
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
      >
          {/*
           * 面板样式分四组：
           * 1) 尺寸：w-(--anchor-width) 与触发器同宽，max-h 限制可用高度；
           * 2) 视觉：popover 背景、圆角、阴影与前景色环；
           * 3) 动画：duration-100 + fade/zoom + slide-in-from-*；
           * 4) 关闭：data-closed 时隐藏溢出并播放退出动画。
           * --transform-origin 由 Positioner 根据弹出方向注入，缩放原点因此正确。
           */}
        <MenuPrimitive.Popup
          data-slot="dropdown-menu-content"
          className={cn("z-50 max-h-(--available-height) w-(--anchor-width) min-w-32 origin-(--transform-origin) overflow-x-hidden overflow-y-auto rounded-lg bg-popover p-1 text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 outline-none data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:overflow-hidden data-closed:fade-out-0 data-closed:zoom-out-95", className )}
          {...props}
        />
      </MenuPrimitive.Positioner>
    </MenuPrimitive.Portal>
  // 内容面板渲染结束：定位、尺寸与动画均由 base-ui 注入的变量驱动。
  // 调用方若覆盖 transform，可能破坏 slide-in 动画，应优先覆盖颜色与圆角。
  // 面板内部可放置任意菜单项组合，但需保持 at least 一个可聚焦项。
  // 空菜单没有意义，打开后应立即关闭，业务侧应避免渲染空内容。
  )
}

/**
 * 菜单分组容器。
 *
 * @param props base-ui Menu.Group 的属性。
 * @returns 用于把若干菜单项与标签组合在一起的语义分组。
 *
 * 副作用：无；不改变键盘导航，仅提供结构与样式钩子。
 */
function DropdownMenuGroup({ ...props }: MenuPrimitive.Group.Props) {
  // 分组只提供语义与样式钩子，不改变菜单的键盘导航顺序。
  // 组内可混合普通项、复选项与单选项，但建议同类项放在同组。
  // 分组之间通常配合 Separator 使用，形成清晰的视觉区隔。
  // 分组本身不可聚焦，读屏会通过 GroupLabel 理解分组含义。
  // 不要在分组上绑定 onClick，点击事件应由具体菜单项处理。
  return <MenuPrimitive.Group data-slot="dropdown-menu-group" {...props} />
}

/**
 * 分组标签。
 *
 * @param props.className 追加样式类。
 * @param props.inset 是否为左侧留白布局（与带图标的菜单项对齐），默认 false。
 * @param props 其余 GroupLabel 属性透传。
 * @returns 小号、次要文字色的分组标题。
 *
 * 副作用：无。标签不可聚焦，也不参与菜单项选择。
 */
function DropdownMenuLabel({
  className,
  inset,
  ...props
}: MenuPrimitive.GroupLabel.Props & {
  inset?: boolean
}) {
    // 标签通过 data-inset 控制左缩进，使其与带图标的菜单项文字对齐。
    // 颜色使用 muted-foreground，弱化标题、突出可操作项。
    // 标签不可选中，也不会触发菜单关闭。
    // 标签内容应简短，过长会挤压菜单宽度。
    // 若菜单项都没有图标，可不使用 inset，保持左对齐。
  return (
    <MenuPrimitive.GroupLabel
      data-slot="dropdown-menu-label"
      data-inset={inset}
      className={cn(
        "px-1.5 py-1 text-xs font-medium text-muted-foreground data-inset:pl-7",
        className
      )}
      {...props}
    />
  // 分组项渲染结束：每项都带 data-inset 与 data-variant，
  // 便于外部样式按语义定制，而无需修改组件实现。
  // 项之间的间距由菜单容器的 padding 与项内边距共同决定。
  // 不建议在菜单项内放置复杂表单控件，会影响键盘导航。
  )
}

/**
 * 普通菜单项。
 *
 * @param props.className 追加样式类。
 * @param props.inset 是否为左侧留白布局，默认 false；带图标时通常设为 true。
 * @param props.variant 视觉语义，默认 "default"；"destructive" 用于危险操作。
 * @param props 其余 Item 属性（onClick、disabled、children）透传。
 * @returns 可聚焦、可点击的菜单行。
 *
 * 副作用：点击会触发调用方回调并关闭菜单；disabled 时不可聚焦。
 * 破坏性变体只改变颜色，不会二次确认，业务侧需自行处理确认流程。
 */
function DropdownMenuItem({
  className,
  inset,
  variant = "default",
  ...props
}: MenuPrimitive.Item.Props & {
  inset?: boolean
  variant?: "default" | "destructive"
}) {
    // 菜单项是主要交互单元：hover/focus 使用 accent 背景，
    // destructive 变体使用红色文字与浅红焦点背景。
    // data-inset 为带图标的项提供与文字对齐的左内边距。
    // svg 尺寸统一约束为 size-4，避免不同图标粗细不一致。
    // disabled 时 pointer-events-none + opacity-50，且不可聚焦。
    // 点击后是否关闭菜单由 base-ui 默认行为决定；需要阻止关闭时
    // 可在 onClick 中调用事件对象的 preventDefault。
  return (
    <MenuPrimitive.Item
      data-slot="dropdown-menu-item"
      data-inset={inset}
      data-variant={variant}
      className={cn(
        "group/dropdown-menu-item relative flex cursor-default items-center gap-1.5 rounded-md px-1.5 py-1 text-sm outline-hidden select-none focus:bg-accent focus:text-accent-foreground not-data-[variant=destructive]:focus:**:text-accent-foreground data-inset:pl-7 data-[variant=destructive]:text-destructive data-[variant=destructive]:focus:bg-destructive/10 data-[variant=destructive]:focus:text-destructive dark:data-[variant=destructive]:focus:bg-destructive/20 data-disabled:pointer-events-none data-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4 data-[variant=destructive]:*:[svg]:text-destructive",
        className
      )}
      {...props}
    />
  )
}

/**
 * 子菜单根节点。
 *
 * @param props base-ui Menu.SubmenuRoot 的属性。
 * @returns 子菜单的上下文容器，不渲染可见节点。
 *
 * 副作用：管理子菜单的展开方向与关闭时机。
 */
function DropdownMenuSub({ ...props }: MenuPrimitive.SubmenuRoot.Props) {
  // 子菜单根节点只承载上下文，不渲染可见内容；
  // 展开方向、关闭时机与焦点返回由 base-ui 统一处理。
  // 子菜单通常嵌套在主菜单项内部，避免与主菜单状态冲突。
  // 复杂多级菜单应谨慎使用，移动端体验可能不如抽屉。
  // 子菜单的打开不会关闭主菜单，二者共享同一焦点上下文。
  return <MenuPrimitive.SubmenuRoot data-slot="dropdown-menu-sub" {...props} />
}

/**
 * 子菜单触发器。
 *
 * @param props.className 追加样式类。
 * @param props.inset 是否为左侧留白布局，默认 false。
 * @param props.children 触发器内容，通常是文字。
 * @returns 带右侧箭头的菜单行；悬停或键盘右方向键展开子菜单。
 *
 * 副作用：展开/收起子菜单；箭头方向固定向右，表示层级深入。
 */
function DropdownMenuSubTrigger({
  className,
  inset,
  children,
  ...props
}: MenuPrimitive.SubmenuTrigger.Props & {
  inset?: boolean
}) {
    // 子菜单触发器右侧固定显示 ChevronRight，暗示“还有下一级”。
    // hover 或键盘右方向键展开，左方向键返回上一级。
    // data-popup-open / data-open 时保持高亮，防止用户迷失层级。
    // data-inset 与普通菜单项一致，保证文字列对齐。
    // 触发器本身不可点击执行动作，只负责展开子菜单。
    // 子菜单内容通过 DropdownMenuSubContent 渲染，通常向右弹出。
  return (
    <MenuPrimitive.SubmenuTrigger
      data-slot="dropdown-menu-sub-trigger"
      data-inset={inset}
      className={cn(
        "flex cursor-default items-center gap-1.5 rounded-md px-1.5 py-1 text-sm outline-hidden select-none focus:bg-accent focus:text-accent-foreground not-data-[variant=destructive]:focus:**:text-accent-foreground data-inset:pl-7 data-popup-open:bg-accent data-popup-open:text-accent-foreground data-open:bg-accent data-open:text-accent-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      {children}
      <ChevronRightIcon className="ml-auto" />
    </MenuPrimitive.SubmenuTrigger>
  // 子菜单内容渲染结束：方向翻转由 Positioner 自动处理。
  // 子菜单宽度与父菜单独立，调用方可按内容覆盖 min-w。
  // 关闭子菜单时焦点返回触发项，键盘用户不会丢失位置。
  )
}

/**
 * 子菜单内容面板。
 *
 * @param props.align 对齐方式，默认 "start"。
 * @param props.alignOffset 对齐偏移，默认 -3。
 * @param props.side 弹出方向，默认 "right"。
 * @param props.sideOffset 与触发器的间距，默认 0。
 * @param props.className 追加样式类。
 * @returns 复用 DropdownMenuContent 的子菜单面板，默认向右展开。
 *
 * 副作用：与主菜单共享焦点管理；在小屏上可能被视口边缘裁切，
 * 调用方需评估是否需要改为移动端抽屉。
 */
function DropdownMenuSubContent({
  align = "start",
  alignOffset = -3,
  side = "right",
  sideOffset = 0,
  className,
  ...props
}: React.ComponentProps<typeof DropdownMenuContent>) {
    // 子菜单复用主内容面板的样式与动画，只覆盖默认方向与宽度；
    // 默认 side="right"、alignOffset=-3，让子菜单紧贴父项右侧。
    // 最小宽度 96px，保证短文本也有可点击面积。
    // 进入/退出动画与主菜单一致，减少层级切换的割裂感。
    // 若视口右侧空间不足，base-ui 会自动翻转方向。
  return (
    <DropdownMenuContent
      data-slot="dropdown-menu-sub-content"
      className={cn("w-auto min-w-[96px] rounded-lg bg-popover p-1 text-popover-foreground shadow-lg ring-1 ring-foreground/10 duration-100 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95", className )}
      align={align}
      alignOffset={alignOffset}
      side={side}
      sideOffset={sideOffset}
      {...props}
    />
  )
}

/**
 * 复选菜单项。
 *
 * @param props.className 追加样式类。
 * @param props.children 菜单项文字。
 * @param props.checked 受控选中状态。
 * @param props.inset 是否为左侧留白布局，默认 false。
 * @param props 其余 CheckboxItem 属性透传。
 * @returns 右侧带勾选指示器的菜单行。
 *
 * 副作用：点击切换选中状态并通过 onCheckedChange 通知调用方；
 * 勾选指示器由 base-ui 的 ItemIndicator 管理显示时机。
 */
function DropdownMenuCheckboxItem({
  className,
  children,
  checked,
  inset,
  ...props
}: MenuPrimitive.CheckboxItem.Props & {
  inset?: boolean
}) {
    // 复选项保留菜单项的全部交互与样式，仅增加右侧勾选指示器。
    // checked 为受控属性，业务需在 onCheckedChange 中更新状态。
    // 指示器容器绝对定位，避免影响文字换行与行高。
    // 未选中时指示器不渲染图标，但容器仍占位，保持行宽稳定。
    // 复选项通常不会关闭菜单，便于连续勾选多个配置。
  return (
    <MenuPrimitive.CheckboxItem
      data-slot="dropdown-menu-checkbox-item"
      data-inset={inset}
      className={cn(
        "relative flex cursor-default items-center gap-1.5 rounded-md py-1 pr-8 pl-1.5 text-sm outline-hidden select-none focus:bg-accent focus:text-accent-foreground focus:**:text-accent-foreground data-inset:pl-7 data-disabled:pointer-events-none data-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        className
      )}
      checked={checked}
      {...props}
    >
      {/*
       * 指示器绝对定位在右侧，不参与文字排版；
       * CheckIcon 只在选中时由 base-ui 渲染，未选中时该区域留空。
       * 这样做保证选中/未选中行宽一致，菜单不会因状态变化而抖动。
       */}
      <span
        className="pointer-events-none absolute right-2 flex items-center justify-center"
        data-slot="dropdown-menu-checkbox-item-indicator"
      >
        <MenuPrimitive.CheckboxItemIndicator>
          <CheckIcon
          />
        </MenuPrimitive.CheckboxItemIndicator>
      </span>
      {children}
    </MenuPrimitive.CheckboxItem>
  // 复选项渲染结束：勾选状态完全由 checked 控制，
  // 组件不维护内部状态，避免受控/非受控混用导致不同步。
  // 指示器不参与键盘导航，读屏通过 aria-checked 获知状态。
  // 连续勾选时菜单通常保持打开，符合批量设置预期。
  )
}

/**
 * 单选菜单组。
 *
 * @param props base-ui Menu.RadioGroup 的属性（value、onValueChange）。
 * @returns 管理一组单选菜单项上下文的容器。
 *
 * 副作用：在组内维护唯一选中值；切换时关闭菜单与否由调用方决定。
 */
function DropdownMenuRadioGroup({ ...props }: MenuPrimitive.RadioGroup.Props) {
  // 单选组通过上下文共享当前值，子项只需提供 value；
  // 选中值变化时由 onValueChange 通知业务层。
  // 组本身不渲染 DOM，因此不会增加额外布局层级。
  // 单选组常与 Separator 配合，形成“设置项”区块。
  // 若选项较多，建议改用页面内的表单控件而非菜单。
  return (
    <MenuPrimitive.RadioGroup
      data-slot="dropdown-menu-radio-group"
      {...props}
    />
  )
}

/**
 * 单选菜单项。
 *
 * @param props.className 追加样式类。
 * @param props.children 菜单项文字。
 * @param props.inset 是否为左侧留白布局，默认 false。
 * @param props 其余 RadioItem 属性（value、disabled）透传。
 * @returns 右侧带勾选指示器的单选菜单行。
 *
 * 副作用：点击后向 RadioGroup 提交新值；指示器只在当前值匹配时显示。
 */
function DropdownMenuRadioItem({
  className,
  children,
  inset,
  ...props
}: MenuPrimitive.RadioItem.Props & {
  inset?: boolean
}) {
    // 单选项与复选项共享视觉规格，区别在于状态来源。
    // value 必须是组内唯一值，否则选中态会同时出现多个。
    // 指示器只在当前值匹配时渲染 CheckIcon。
    // 点击后是否关闭菜单由业务决定，默认行为是关闭。
    // disabled 项仍展示，但不可选择，便于说明“暂不可用”。
  return (
    <MenuPrimitive.RadioItem
      data-slot="dropdown-menu-radio-item"
      data-inset={inset}
      className={cn(
        "relative flex cursor-default items-center gap-1.5 rounded-md py-1 pr-8 pl-1.5 text-sm outline-hidden select-none focus:bg-accent focus:text-accent-foreground focus:**:text-accent-foreground data-inset:pl-7 data-disabled:pointer-events-none data-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      {/*
       * 单选指示器与复选指示器共用同一套定位与尺寸；
       * 区别仅在于状态来源：复选使用 checked，单选使用 RadioGroup 的 value。
       * 视觉上不做额外区分，避免菜单出现两种勾选样式。
       */}
      <span
        className="pointer-events-none absolute right-2 flex items-center justify-center"
        data-slot="dropdown-menu-radio-item-indicator"
      >
        <MenuPrimitive.RadioItemIndicator>
          <CheckIcon
          />
        </MenuPrimitive.RadioItemIndicator>
      </span>
      {children}
    </MenuPrimitive.RadioItem>
  // 单选项渲染结束：value 与 RadioGroup 的当前值比较决定勾选显示。
  // 点击后由 RadioGroup 统一更新状态，避免子项各自维护副本。
  // 若需要“未选择任何项”的初始状态，传入 undefined 作为初始值。
  )
}

/**
 * 菜单分隔线。
 *
 * @param props.className 追加样式类。
 * @param props 其余 Separator 属性透传。
 * @returns 一条 1px 高的水平线，左右负边距以贴合菜单内边距。
 *
 * 副作用：无；不参与键盘导航。
 */
function DropdownMenuSeparator({
  className,
  ...props
}: MenuPrimitive.Separator.Props) {
  // 分隔线使用负水平边距抵消菜单内边距，形成贯穿整行的细线。
  // my-1 提供上下呼吸空间，避免分组拥挤。
  // 分隔线不参与焦点顺序，读屏也不会朗读。
  // 仅在语义确实分组时使用，过多分隔线会让菜单显得零碎。
  return (
    <MenuPrimitive.Separator
      data-slot="dropdown-menu-separator"
      className={cn("-mx-1 my-1 h-px bg-border", className)}
      {...props}
    />
  // 分隔线渲染结束：它只占用 1px 高度与少量外边距。
  // 在深色主题下颜色由 border 变量自动适配，无需额外处理。
  // 分隔线不应作为唯一的视觉分组手段，配合 GroupLabel 更清晰。
  )
}

/**
 * 快捷键提示文字。
 *
 * @param props.className 追加样式类。
 * @param props.children 快捷键文本，例如 "⌘K"。
 * @returns 右对齐、小号、宽字距的提示节点。
 *
 * 副作用：无；仅作视觉提示，不绑定真实键盘事件。
 * 真正的快捷键需由业务在全局监听并调用对应操作。
 */
function DropdownMenuShortcut({
  className,
  ...props
}: React.ComponentProps<"span">) {
  // 快捷键提示右对齐，视觉上弱于菜单项文字；
  // tracking-widest 让“⌘K”等符号更易辨认。
  // 该节点不绑定事件，真实快捷键需在应用层注册。
  // 若平台快捷键不同，应由调用方传入对应文本。
  return (
    <span
      data-slot="dropdown-menu-shortcut"
      className={cn(
        "ml-auto text-xs tracking-widest text-muted-foreground group-focus/dropdown-menu-item:text-accent-foreground",
        className
      )}
      {...props}
// 导出清单到此结束。所有菜单组件均为无状态适配层，开合与选中状态由 base-ui 管理。
// 业务侧只需关注内容与回调；样式定制通过 className 或共享变量完成。
// 若发现某些组件长期未被使用，可在后续清理中移除，但需先确认外部引用。
    />
  // 快捷键提示渲染结束：文本由调用方提供，组件不做平台判断。
  // 若快捷键在移动端无意义，可传空字符串隐藏或直接不渲染该节点。
  // 右对齐依赖 ml-auto，父项需为 flex 布局。
  )
}

// 导出全部菜单原子组件，覆盖从简单项到子菜单、复选/单选的常见场景。
// 业务侧应优先使用这些组件而不是直接引用 @base-ui/react/menu，
// 以保证定位、动画与主题样式在全站一致。
// 新增组件时请同步维护 data-slot 命名，测试与样式选择器依赖该契约。
export {
  DropdownMenu,
  DropdownMenuPortal,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuItem,
  DropdownMenuCheckboxItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuSub,
  DropdownMenuSubTrigger,
  DropdownMenuSubContent,
// 导出结束。菜单组件覆盖了常见桌面交互模式：
// 触发器、内容、分组、项、子菜单、复选、单选、分隔线与快捷键。
// 业务侧若只需要一个简单下拉，使用 Root/Trigger/Content/Item 即可。
// 保持组件薄封装，复杂业务逻辑（权限、导航、确认）留在调用方。
// 修改样式时优先调整共享类，避免各组件视觉逐渐分叉。
}
