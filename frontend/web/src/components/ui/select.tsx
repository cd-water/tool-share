/**
 * 文件职责：原生 <select> 的样式封装（Select）。
 *
 * 设计目标：
 * - 保留原生 select 的键盘与移动端体验，不引入自定义弹层；
 * - 统一高度、边框、焦点环与禁用/非法态，与 Input / Textarea 保持一致；
 * - 通过右侧 ChevronDown 图标暗示可展开，并隐藏浏览器默认箭头。
 *
 * 该组件不管理选中值，也不负责选项数据；业务页面自行渲染 <option>，
 * 并通过 value/onChange 受控。这样既可用于简单筛选项，也可嵌入表单库。
 */
import * as React from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * 下拉选择框。
 *
 * @param props.className 追加样式类；会追加到内部 select 上。
 * @param props.children 选项内容，通常为若干 <option>。
 * @param props 其余原生 select 属性（value、onChange、disabled、aria-label）透传。
 * @returns 带右侧箭头的相对定位容器，内部为原生 select。
 *
 * 副作用：无；不持有状态。
 * 可访问性：调用方应为 select 提供 aria-label 或关联 label，
 * 仅依赖视觉箭头不足以让读屏用户理解控件用途。
 */
function Select({ className, children, ...props }: React.ComponentProps<'select'>) {
  return (
  // 外层 relative 仅用于定位右侧箭头；实际交互元素仍是原生 select。
  // appearance-none 去掉浏览器默认箭头，避免与自定义箭头重复。
  // pr-8 为右侧图标预留空间，防止选项文字与箭头重叠。
    <div className="relative">
      <select
        data-slot="select"
        className={cn(
          'h-8 w-full min-w-0 appearance-none rounded-lg border border-input bg-transparent py-1 pr-8 pl-2.5 text-sm transition-colors outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30',
          className,
        )}
        {...props}
      >
        {children}
      </select>
      {/*
       * 箭头设置 pointer-events-none，点击会穿透到 select 上，
       * 因此用户点击箭头区域时仍然能正常展开下拉框。
       */}
      <ChevronDown className="pointer-events-none absolute top-1/2 right-2 size-4 -translate-y-1/2 text-muted-foreground" />
    </div>
  )
}

export { Select }
