/**
 * 管理端路由表与全局路由守卫。
 *
 * 结构：
 * - /login 是独立页面，不套后台布局；
 * - / 使用 AdminLayout，作为所有业务页面的父路由，统一要求登录；
 * - 子路由通过 meta.title 提供页面标题，通过 meta.roles 声明角色限制。
 *
 * 守卫职责：未登录跳登录页、已登录访问登录页时回首页、
 * 对声明了 roles 的路由校验当前管理员角色。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AdminLayout from '@/layouts/AdminLayout.vue'
import LoginView from '@/views/LoginView.vue'
import DashboardView from '@/views/DashboardView.vue'
import ToolManageView from '@/views/ToolManageView.vue'
import RentalManageView from '@/views/RentalManageView.vue'
import DamageManageView from '@/views/DamageManageView.vue'
import KnowledgeView from '@/views/KnowledgeView.vue'
import MessagesView from '@/views/MessagesView.vue'
import AccountsView from '@/views/AccountsView.vue'

/**
 * 扩展 vue-router 的路由元信息类型。
 *
 * requiresAuth：该路由（含父路由）是否需要登录；
 * title：页面标题，可用于面包屑或文档标题；
 * roles：允许访问的角色列表，空表示所有已登录管理员。
 */
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    title?: string
    roles?: string[]
  }
}

// 登录成功、访问根路径或角色不匹配时的默认落地页。
const DEFAULT_REDIRECT = '/dashboard'

/**
 * 路由实例。
 *
 * 使用 HTML5 History 模式（createWebHistory），路径中不含 #；
 * 生产部署需要服务端把所有前端路径回退到 index.html。
 */
const router = createRouter({
  // History 模式：URL 更干净，生产环境需服务端 fallback 到 index.html。
  history: createWebHistory(),
  // 顶层只有登录页与后台布局；未匹配的路径由部署时的 fallback 处理。
  routes: [
    // 登录页：不套后台布局，也不需要登录态。
    { path: '/login', component: LoginView },
    {
      // 后台主布局：所有业务页面都是它的子路由。
      path: '/',
      // 布局负责侧边栏、顶栏以及 <router-view> 出口。
      component: AdminLayout,
      // 访问根路径时重定向到仪表盘。
      redirect: DEFAULT_REDIRECT,
      // 父路由声明需登录；子路由通过 matched 继承该限制。
      meta: { requiresAuth: true },
      // 子路由按业务模块划分，声明顺序即侧边栏展示顺序。
      children: [
        // 仪表盘：所有已登录管理员可见。
        { path: 'dashboard', component: DashboardView, meta: { title: '仪表盘' } },
        // 工具管理：仅系统管理员，涉及工具审核与维护。
        { path: 'tools', component: ToolManageView, meta: { title: '工具管理', roles: ['SUPER_ADMIN'] } },
        // 租赁管理：所有管理员均可处理取件/归还。
        { path: 'rentals', component: RentalManageView, meta: { title: '租赁管理' } },
        // 报修管理：所有管理员均可核验与跟进维修。
        { path: 'damage', component: DamageManageView, meta: { title: '报修管理' } },
        // 知识库：仅系统管理员可上传与删除文档。
        { path: 'knowledge', component: KnowledgeView, meta: { title: '知识库', roles: ['SUPER_ADMIN'] } },
        // 管理员账号：仅系统管理员可查看与调整角色。
        { path: 'accounts', component: AccountsView, meta: { title: '管理员账号', roles: ['SUPER_ADMIN'] } },
        // 消息中心：所有已登录管理员可见自己的消息。
        { path: 'messages', component: MessagesView, meta: { title: '消息中心' } },
      ],
    },
  ],
})

/**
 * 全局前置守卫。
 *
 * 执行顺序：
 * 1. 访问受保护路由但未登录 → 跳登录页，并记录 redirect；
 * 2. 已登录却访问 /login → 直接回默认首页；
 * 3. 路由声明了 roles → 若本地角色为空先拉取用户信息，再校验角色；
 *    不匹配时回默认首页，避免展示无权限页面。
 *
 * 返回值约定：返回路由地址表示重定向，返回 undefined 表示放行。
 * 注意：fetchInfo 失败（如 token 过期）会跳回登录页。
 */
router.beforeEach(async (to) => {
  // store 已持久化，刷新页面后仍能直接判断登录态。
  const auth = useAuthStore()
  // matched 会包含父路由，因此子路由自动继承 requiresAuth。
  if (to.matched.some((record) => record.meta.requiresAuth) && !auth.isLoggedIn) {
    // 记录原始地址，登录成功后可回跳。
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录用户无需再次看到登录页。
  // 已登录用户无需再次看到登录页，直接回默认首页。
  if (to.path === '/login' && auth.isLoggedIn) {
    return DEFAULT_REDIRECT
  }
  // 合并父子路由声明的角色；子路由可以追加更严格的限制。
  const roles = to.matched.flatMap((record) => record.meta.roles ?? [])
  if (roles.length) {
    // 旧会话只持久化了 token，先补拉角色再判断
    // 旧会话或刷新后可能只有 token，先补齐角色再做权限判断。
    if (!auth.role) {
      // info 失败通常意味着 token 已失效。
      try {
        await auth.fetchInfo()
        // 拉取失败直接回登录页，不继续渲染受保护页面。
      } catch {
        return { path: '/login' }
      }
    }
    // 角色不匹配：回首页而不是抛 403，避免白屏。
    if (!roles.includes(auth.role)) {
      return DEFAULT_REDIRECT
    }
  }
})

// 供 main.ts 挂载，也是 request 拦截器跳转登录页时使用的同一个实例。
export default router
