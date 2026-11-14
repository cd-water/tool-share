/**
 * 应用路由表。
 *
 * 结构分三层：
 * 1. `/login` 公开路由，不经过鉴权；
 * 2. `RequireAuth` 作为布局路由守卫，未登录时重定向到登录页，
 *    其子路由再套 `AppLayout`（导航/内容框架）；
 * 3. `*` 通配兜底，未知路径统一回到工具列表。
 *
 * 使用 `createBrowserRouter`（History API），因此生产环境需要服务端
 * 把所有前端路径回退到 index.html。
 *
 * 页面组件采用静态 import；如果后续包体变大，可在这里改为 React.lazy
 * 并配合 Suspense，路由表结构无需变化。
 */
import { createBrowserRouter, Navigate } from 'react-router'
import { RequireAuth } from '@/components/RequireAuth'
import AppLayout from '@/layouts/AppLayout'
import AiPage from '@/pages/AiPage'
import DamagePage from '@/pages/DamagePage'
import LoginPage from '@/pages/LoginPage'
import MessagesPage from '@/pages/MessagesPage'
import ProfilePage from '@/pages/ProfilePage'
import RentalsPage from '@/pages/RentalsPage'
import ToolsPage from '@/pages/ToolsPage'

/**
 * 浏览器路由实例。
 *
 * 鉴权边界由 `RequireAuth` 布局路由统一承担，页面组件本身不重复判断登录态；
 * 布局路由不写 path，因此其子路由的绝对路径就是 `tools`、`rentals` 等。
 *
 * 维护提示：新增受保护页面时，应挂在 `AppLayout` 的 children 下，
 * 而不是与 `/login` 平级，否则会绕过登录检查。
 */
export const router = createBrowserRouter([
  // 公开路由：登录页自身不校验 token，否则未登录用户会被重定向死循环。
  { path: '/login', element: <LoginPage /> },
  {
    // 守卫路由：RequireAuth 内部读取 auth store，未登录时 Navigate 到 /login。
    element: <RequireAuth />,
    children: [
      {
        // 登录后的统一布局：顶栏、导航与内容区。
        element: <AppLayout />,
        children: [
          // 根路径没有独立页面，直接重定向到工具列表；replace 避免污染历史栈。
          { index: true, element: <Navigate to="/tools" replace /> },
          // 工具浏览与详情入口。
          { path: 'tools', element: <ToolsPage /> },
          // 我的租赁订单。
          { path: 'rentals', element: <RentalsPage /> },
          // 损坏报告相关页面。
          { path: 'damage', element: <DamagePage /> },
          // 站内消息与通知偏好。
          { path: 'messages', element: <MessagesPage /> },
          // AI 工具推荐与流式问答。
          { path: 'ai', element: <AiPage /> },
          // 个人资料与信用信息。
          { path: 'profile', element: <ProfilePage /> },
        ],
      },
    ],
  },
  // 通配兜底：未知路径回到首页，避免出现空白页。
  { path: '*', element: <Navigate to="/tools" replace /> },
])
