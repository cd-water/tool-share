/**
 * 居民端应用入口。
 *
 * <p>负责挂载 React 根节点、初始化 TanStack Query、路由与全局 Toast，
 * 并在最后启动界面特效模块。应用级数据请求都通过 QueryClient 管理，
 * 页面组件不自行创建客户端。</p>
 */
// React 严格模式：开发环境帮助发现副作用与过时 API，生产环境不产生额外行为。
import { StrictMode } from 'react'
// React 18+ 的并发根节点入口。
import { createRoot } from 'react-dom/client'
// 服务端状态缓存：页面查询/变更共享同一客户端。
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
// React Router 的 DOM 渲染器，路由表定义在 @/router。
import { RouterProvider } from 'react-router/dom'
// 全局 Toast 容器，业务错误与操作反馈统一从这里展示。
import { Toaster } from '@/components/ui/toast'
// 应用路由表；登录页、工具广场等页面均由它匹配。
import { router } from '@/router'
// 全局动效初始化：音效、光标等非业务交互。
import { initFx } from '@/lib/fx'
// 基础样式与设计系统样式；design.css 负责主题变量。
import './index.css'
import './styles/design.css'

// 标记当前应用为居民端，design.css 依据该属性切换品牌色与布局变量。
// 该属性必须早于组件渲染写入，否则首帧会使用错误主题。
document.body.dataset.app = 'web'

// 全局查询客户端：失败只重试一次，窗口重新聚焦时不自动刷新，避免频繁请求。
// 具体页面的 staleTime、缓存 key 由各查询自行覆盖。
// 该实例在应用生命周期内唯一，退出登录时由页面清空缓存。
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
})

// 根节点挂载：QueryClientProvider 提供缓存，RouterProvider 承载页面路由，
// Toaster 负责全站统一提示；StrictMode 仅在开发环境启用额外检查。
// 根节点必须存在于 index.html；使用非空断言是因为这是启动入口，缺失即无法运行。
// 渲染树顺序固定：StrictMode -> QueryClient -> Router -> Toaster。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* QueryClientProvider 必须包住路由，页面 hooks 才能访问同一缓存。 */}
    <QueryClientProvider client={queryClient}>
      {/* 路由出口；页面切换由 RouterProvider 接管，不刷新整页。 */}
      <RouterProvider router={router} />
      {/* Toast 放在路由外层，页面切换时仍能展示全局提示。 */}
      <Toaster />
    </QueryClientProvider>
  </StrictMode>,
)

// 初始化全局交互特效（光标、音效等），必须在 DOM 挂载后执行。
// 该调用与业务状态无关，失败也不应阻断页面渲染。
// 清理逻辑由特效模块在页面卸载时自行处理。
initFx()
