/**
 * 文件职责：表单标签（Label）与字段错误提示（FieldError）两个轻量组件。
 *
 * 它们只提供排版与颜色，不建立 label 与控件的关联——关联由调用方通过
 * htmlFor + input id 完成。FieldError 预留 min-h-4，使错误出现/消失时
 * 表单布局不会上下跳动。
 *
 * 这两个组件主要服务于 react-hook-form 的 Controller 写法：
 * 页面先渲染 <Label htmlFor="xxx">，再渲染控件，最后渲染 <FieldError>。
 */
import * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * 表单字段标签。
 *
 * @param props.className 追加样式类。
 * @param props 其余原生 label 属性（htmlFor、children）透传。
 * @returns 带 data-slot="label" 的 label 元素。
 *
 * 副作用：无。可访问性依赖调用方正确设置 htmlFor，使其指向对应控件的 id。
 */
function Label({ className, ...props }: React.ComponentProps<'label'>) {
  return (
    <label
      data-slot="label"
      className={cn(
        'mb-1.5 block text-sm font-medium text-foreground',
        className,
      )}
      {...props}
    />
  )
}

/**
 * 字段级错误提示。
 *
 * @param props.children 错误文案；为空时渲染空段落以占位。
 * @returns 固定最小高度的 <p>，避免错误信息出现时挤压下方内容。
 *
 * 副作用：无。建议与 aria-invalid / aria-describedby 配合使用，
 * 让读屏用户也能获知校验失败原因；仅展示文字不构成完整无障碍支持。
 */
function FieldError({ children }: { children?: string }) {
  return (
    <p className="mt-1 min-h-4 text-xs leading-4 text-destructive">
      {children ?? ''}
    </p>
  )
}

// 统一导出两个原子组件；业务侧无需直接引用 @/lib/utils 拼接样式。
// 新增表单辅助组件（如 FieldHint）时，应延续“无状态、纯样式”的边界。
export { Label, FieldError }
