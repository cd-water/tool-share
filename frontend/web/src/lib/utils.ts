/**
 * 通用工具入口。
 *
 * 目前仅重新导出 `cn`（className 合并函数），让业务代码统一从
 * `@/lib/utils` 引入，避免直接依赖具体实现包。
 */
export { cn } from "cn"
