<script setup lang="ts">
import { useDark, useToggle } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Document, Grid, Moon, Setting, Sunny, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menuItems = [
  { path: '/menu-1', title: '菜单1', icon: Grid },
  { path: '/menu-2', title: '菜单2', icon: Document },
  { path: '/menu-3', title: '菜单3', icon: Setting },
]

const isDark = useDark()
const toggleDark = useToggle(isDark)

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
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <BrandMark :size="34" />
        <div class="brand-text">
          <div class="brand-name">ToolShare</div>
          <div class="brand-sub">管理后台</div>
        </div>
      </div>
      <el-menu class="menu admin-menu" :default-active="route.path" router>
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
      <div class="user">
        <span class="user-name">{{ auth.username || '未登录' }}</span>
        <span class="user-actions">
          <el-tooltip :content="isDark ? '切换为亮色模式' : '切换为暗色模式'" placement="top">
            <el-button link :icon="isDark ? Sunny : Moon" @click="toggleDark()" />
          </el-tooltip>
          <el-button link :icon="SwitchButton" @click="handleLogout">退出登录</el-button>
        </span>
      </div>
    </el-aside>
    <el-main class="main">
      <div class="page-header">
        <h1 class="page-title">{{ route.meta.title ?? '' }}</h1>
      </div>
      <div class="page-body">
        <router-view />
      </div>
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-light);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.brand-name,
.brand-sub {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.4;
}

.menu {
  flex: 1;
  border-right: none;
}

.user {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.main {
  display: flex;
  flex-direction: column;
  padding: 0;
  background: var(--el-bg-color-page);
}

.page-header {
  display: flex;
  align-items: center;
  height: 56px;
  flex-shrink: 0;
  padding: 0 24px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.page-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 24px;
}
</style>
