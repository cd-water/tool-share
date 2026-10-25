import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import BasicLayout from '../layouts/BasicLayout.vue'
import { useUserStore } from '../stores/user'

/** 各角色登录后的首页：居民 → 工具广场，工作人员 → 工具管理，管理员 → 用户管理 */
const HOMES: Record<number, string> = { 0: '/tools', 1: '/admin/tools', 2: '/admin/users' }

export function homeOf(role: number): string {
  return HOMES[role] ?? '/tools'
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('../views/login/index.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: BasicLayout,
    redirect: () => (useUserStore().token ? homeOf(useUserStore().role) : '/login'),
    children: [
      // 居民端（工作人员/管理员禁止访问，守卫弹回各自首页）
      { path: 'tools', component: () => import('../views/tool/index.vue'), meta: { title: '工具广场', roles: [0] } },
      { path: 'tools/:id', component: () => import('../views/tool/detail.vue'), meta: { title: '工具详情', roles: [0] } },
      { path: 'rentals', component: () => import('../views/rental/index.vue'), meta: { title: '我的租赁', roles: [0] } },
      { path: 'repairs', component: () => import('../views/repair/index.vue'), meta: { title: '损坏报修', roles: [0] } },
      { path: 'repairs/new', component: () => import('../views/repair/new.vue'), meta: { title: '提交报修', roles: [0] } },
      { path: 'messages', component: () => import('../views/notify/index.vue'), meta: { title: '消息中心', roles: [0] } },
      { path: 'ai', component: () => import('../views/ai/index.vue'), meta: { title: 'AI 问答', roles: [0] } },
      // 工作人员端
      { path: 'admin/tools', component: () => import('../views/admin/tools.vue'), meta: { title: '工具管理', roles: [1] } },
      { path: 'admin/tools/new', component: () => import('../views/admin/tool-form.vue'), meta: { title: '工具录入', roles: [1] } },
      { path: 'admin/rentals', component: () => import('../views/admin/rentals.vue'), meta: { title: '租赁管理', roles: [1] } },
      { path: 'admin/repairs', component: () => import('../views/admin/repairs.vue'), meta: { title: '报修处理', roles: [1] } },
      // 管理员端
      { path: 'admin/users', component: () => import('../views/admin/users.vue'), meta: { title: '用户管理', roles: [2] } },
      { path: 'admin/audit', component: () => import('../views/admin/audit.vue'), meta: { title: '工具审核', roles: [2] } },
      { path: 'admin/kb', component: () => import('../views/admin/kb.vue'), meta: { title: '知识库管理', roles: [2] } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  // 未登录一律去登录页，记住原目标
  if (!to.meta.public && !userStore.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录访问登录页 → 回角色首页
  if (to.path === '/login' && userStore.token) {
    return { path: homeOf(userStore.role) }
  }
  // 角色不符 → 回角色首页
  const roles = to.meta.roles as number[] | undefined
  if (roles && !roles.includes(userStore.role)) {
    return { path: homeOf(userStore.role) }
  }
})

export default router
