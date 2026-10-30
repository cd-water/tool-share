import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AdminLayout from '@/layouts/AdminLayout.vue'
import LoginView from '@/views/LoginView.vue'
import PlaceholderView from '@/views/PlaceholderView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    title?: string
  }
}

const DEFAULT_REDIRECT = '/menu-1'

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
        { path: 'menu-1', component: PlaceholderView, meta: { title: '菜单1' } },
        { path: 'menu-2', component: PlaceholderView, meta: { title: '菜单2' } },
        { path: 'menu-3', component: PlaceholderView, meta: { title: '菜单3' } },
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
