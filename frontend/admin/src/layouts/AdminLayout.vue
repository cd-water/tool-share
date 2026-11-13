<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell,
  Box,
  Odometer,
  Reading,
  Tickets,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'
import { bindSoundToggle, bindThemeToggle, setCmdItems } from '@/lib/fx'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menuItems = [
  { path: '/dashboard', title: '仪表盘', icon: Odometer },
  { path: '/tools', title: '工具管理', icon: Box },
  { path: '/rentals', title: '租赁管理', icon: Tickets },
  { path: '/damage', title: '报修管理', icon: Warning },
  { path: '/knowledge', title: '知识库', icon: Reading },
  { path: '/messages', title: '消息中心', icon: Bell },
]

onMounted(() => {
  bindSoundToggle(document.getElementById('soundToggle'))
  bindThemeToggle(document.getElementById('themeToggle'))
  setCmdItems([
    ...menuItems.map((item) => ({ label: item.title, fn: () => router.push(item.path) })),
    { label: '切换主题', fn: () => document.getElementById('themeToggle')?.click() },
    { label: '退出登录', fn: handleLogout },
  ])
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await auth.logout()
  } finally {
    await router.push('/login')
  }
}
</script>

<template>
  <div class="ambient"></div>
  <div class="admin-shell">
    <aside class="side">
      <div class="brand">
        <BrandMark :size="34" />
        <div>
          <b>ToolShare</b>
          <span>管理控制台</span>
        </div>
      </div>
      <nav>
        <router-link
          v-for="item in menuItems"
          :key="item.path"
          :to="item.path"
          class="nav-link"
          :class="{ active: route.path.startsWith(item.path) }"
        >
          <component :is="item.icon" class="ic" />
          <span>{{ item.title }}</span>
          <span v-if="item.path === '/messages'" class="badge-dot"></span>
        </router-link>
      </nav>
      <div class="spacer"></div>
      <div class="side-foot">
        <div class="who">
          <span class="ava">{{ (auth.username || 'A').slice(0, 1).toUpperCase() }}</span>
          <div>
            <b>{{ auth.username || '未登录' }}</b>
            <span>社区工作人员</span>
          </div>
        </div>
        <button class="nav-link" style="width: 100%; border: none; background: none; cursor: pointer; margin-top: 6px" @click="handleLogout">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="M16 17l5-5-5-5M21 12H9" /></svg>
          <span>退出登录</span>
        </button>
      </div>
    </aside>
    <div class="admin-main">
      <header class="topbar">
        <div class="crumb">{{ route.meta.title ?? '' }}</div>
        <div class="grow"></div>
        <button class="icon-btn" data-cmdk title="命令面板 (Ctrl+K)" aria-label="命令面板">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3" /></svg>
        </button>
        <button class="icon-btn" id="soundToggle" aria-label="音效开关"></button>
        <button class="icon-btn" id="themeToggle" aria-label="主题切换"></button>
        <div class="user-chip">
          <span class="ava" style="background: linear-gradient(135deg, #d89b43, #c07e28); color: #fff">
            {{ (auth.username || 'A').slice(0, 1).toUpperCase() }}
          </span>
          <b>{{ auth.username || '管理员' }}</b>
        </div>
      </header>
      <div class="admin-content">
        <router-view />
      </div>
    </div>
  </div>
</template>
