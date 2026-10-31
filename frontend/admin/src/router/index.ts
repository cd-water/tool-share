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

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    title?: string
  }
}

const DEFAULT_REDIRECT = '/dashboard'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      redirect: DEFAULT_REDIRECT,
      meta: { requiresAuth: true },
      children: [
        { path: 'dashboard', component: DashboardView, meta: { title: '仪表盘' } },
        { path: 'tools', component: ToolManageView, meta: { title: '工具管理' } },
        { path: 'rentals', component: RentalManageView, meta: { title: '租赁管理' } },
        { path: 'damage', component: DamageManageView, meta: { title: '报修管理' } },
        { path: 'knowledge', component: KnowledgeView, meta: { title: '知识库' } },
        { path: 'messages', component: MessagesView, meta: { title: '消息中心' } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.matched.some((record) => record.meta.requiresAuth) && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return DEFAULT_REDIRECT
  }
})

export default router
