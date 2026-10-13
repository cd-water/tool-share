import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import BasicLayout from '../layouts/BasicLayout.vue'
import { useUserStore } from '../stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('../views/login/index.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: BasicLayout,
    redirect: '/tools',
    children: [
      // 居民端
      { path: 'tools', component: () => import('../views/tool/index.vue'), meta: { title: '工具广场' } },
      { path: 'tools/:id', component: () => import('../views/tool/detail.vue'), meta: { title: '工具详情' } },
      { path: 'rentals', component: () => import('../views/rental/index.vue'), meta: { title: '我的租赁' } },
      { path: 'repairs', component: () => import('../views/repair/index.vue'), meta: { title: '损坏报修' } },
      { path: 'repairs/new', component: () => import('../views/repair/new.vue'), meta: { title: '提交报修' } },
      { path: 'messages', component: () => import('../views/notify/index.vue'), meta: { title: '消息中心' } },
      { path: 'ai', component: () => import('../views/ai/index.vue'), meta: { title: 'AI 问答' } },
      // 工作人员/管理员端
      { path: 'admin/tools', component: () => import('../views/admin/tools.vue'), meta: { title: '工具管理', roles: [1, 2] } },
      { path: 'admin/rentals', component: () => import('../views/admin/rentals.vue'), meta: { title: '租赁管理', roles: [1, 2] } },
      { path: 'admin/repairs', component: () => import('../views/admin/repairs.vue'), meta: { title: '报修处理', roles: [1, 2] } },
      { path: 'admin/users', component: () => import('../views/admin/users.vue'), meta: { title: '用户管理', roles: [2] } },
      { path: 'admin/audit', component: () => import('../views/admin/audit.vue'), meta: { title: '工具审核', roles: [2] } },
      { path: 'admin/kb', component: () => import('../views/admin/kb.vue'), meta: { title: '知识库管理', roles: [1, 2] } },
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
  // 已登录访问登录页 → 回首页
  if (to.path === '/login' && userStore.token) {
    return { path: '/tools' }
  }
  // 角色不符 → 回工具广场
  const roles = to.meta.roles as number[] | undefined
  if (roles && !roles.includes(userStore.role)) {
    return { path: '/tools' }
  }
})

export default router
