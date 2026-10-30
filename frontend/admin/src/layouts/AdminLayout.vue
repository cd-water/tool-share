<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
// 官方按需样式入口：自动带全 base/overlay/input/button 依赖（EL-OVERLAY 遮罩在此）
import 'element-plus/es/components/message-box/style/css'
import { Document, Setting, SwitchButton, Ticket } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menus = [
  { path: '/menu-1', title: '菜单1', icon: Ticket },
  { path: '/menu-2', title: '菜单2', icon: Document },
  { path: '/menu-3', title: '菜单3', icon: Setting },
]

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await auth.logout()
  } catch {
    // 后端不可达/异常时凭证已在 finally 清空，错误提示由拦截器弹出；仍要回登录页
  }
  await router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <BrandMark :size="34" />
        <div class="brand-text">
          <div class="brand-name">TicketBox</div>
          <div class="brand-sub">票务盒子管理后台</div>
        </div>
      </div>
      <el-menu class="menu" :default-active="route.path" router>
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
      <div class="user">
        <span class="user-name">{{ auth.username || '未登录' }}</span>
        <el-button link type="primary" :icon="SwitchButton" @click="handleLogout">退出登录</el-button>
      </div>
    </el-aside>
    <el-main class="main">
      <router-view />
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
  background: #fff;
  border-right: 1px solid #e3ecf8;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  border-bottom: 1px solid #eef3fb;
}

.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.4;
}

.brand-sub {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.4;
}

.menu {
  flex: 1;
  border-right: none;
}

/* 账号贴左、退出登录贴右 */
.user {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-top: 1px solid #eef3fb;
}

.user-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}

.main {
  background: #f7faff;
}
</style>
