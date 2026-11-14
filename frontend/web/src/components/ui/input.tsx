/**
 * 文件职责：表单文本输入框原子组件（Input）。
 *
 * 在 @base-ui/react/input 之上统一了本项目的输入框外观：
 * 高度、圆角、边框、焦点环、禁用态、非法态（aria-invalid）与暗色主题。
 * 该组件不持有受控状态，也不做校验；值、onChange、错误提示均由
 * react-hook-form 或业务页面负责。这样保证它既能用于简单表单，
 * 也能无缝嵌入 Controller 的 render 回调。
 *
 * 可访问性：调用方应传入 id 与对应的 label；校验失败时设置 aria-invalid，
 * 组件的 aria-invalid:* 样式会自动给出错误边框与焦点环。
 */
import * as React from "react"
import { Input as InputPrimitive } from "@base-ui/react/input"
import { cn } from "cn"

/**
 * 通用文本输入框。
 *
 * @param props.className 追加样式类，可覆盖高度/背景等局部外观。
 * @param props.type 原生 input 类型，默认 text；也可传 number、tel、datetime-local 等。
 * @param props 其余原生 input 属性（value、onChange、placeholder、disabled、aria-*）原样透传。
 * @returns 一个带 data-slot="input" 的原生输入元素。
 *
 * 副作用：无；不读写全局状态、不触发网络请求。
 * 注意：基础高度为 h-8；如果页面需要更高输入框，请在调用方用 className 覆盖，
 * 不要直接修改本组件，以免影响所有表单。
 */
function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
  // 只做样式归一化：type 显式透传，避免某些浏览器对缺省 type 的处理差异。
  // 其余属性通过 {...props} 透传，保证受控与非受控用法都可用。
  // file:* 系列类用于支持 input[type=file] 的按钮样式。
    <InputPrimitive
      type={type}
      data-slot="input"
      className={cn(
        "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2.5 py-1 text-base transition-colors outline-none file:inline-flex file:h-6 file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:disabled:bg-input/80 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
}

export { Input }
