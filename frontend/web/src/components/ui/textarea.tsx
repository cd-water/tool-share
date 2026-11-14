/**
 * 文件职责：多行文本输入框原子组件（Textarea）。
 *
 * 与 Input 共享同一套边框、圆角、焦点环、禁用态与 aria-invalid 错误态，
 * 用于报修描述、评价内容、取消原因等需要多行输入的场景。
 * 组件本身不限制字数、不做校验；maxLength、字数统计与错误提示
 * 都由业务页面或 react-hook-form 负责。
 *
 * 默认最小高度 min-h-20，调用方可用 rows 或 className 调整。
 */
import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * 多行文本输入框。
 *
 * @param props.className 追加样式类，可覆盖最小高度或背景。
 * @param props 其余原生 textarea 属性（value、onChange、rows、maxLength、placeholder 等）透传。
 * @returns 带 data-slot="textarea" 的原生 textarea 元素。
 *
 * 副作用：无；纯受控/非受控输入控件。
 * 注意：如需自动增高，请在调用方通过 ref + scrollHeight 实现，
 * 本组件不引入额外布局副作用，保持可预测。
 */
function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return (
  // 透传所有原生属性，保证 Controller 的 field 展开、rows、maxLength 等可正常工作。
  // 样式顺序为内置 → 调用方，业务侧可安全覆盖 min-h。
    <textarea
      data-slot="textarea"
      className={cn(
        'min-h-20 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-2 text-sm transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30',
        className,
      )}
      {...props}
    />
  )
}

export { Textarea }
