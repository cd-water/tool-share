<script setup lang="ts">
import { onMounted, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell,
  Box,
  Calendar,
  CircleCheck,
  Clock,
  Reading,
  Tickets,
  Tools,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { dashboardApi, type AdminDashboardVO } from '@/api/dashboard'

interface StatItem {
  key: keyof AdminDashboardVO
  label: string
  icon: Component
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info'
}

const router = useRouter()
const loading = ref(false)
const stats = ref<AdminDashboardVO | null>(null)

const statItems: StatItem[] = [
  { key: 'toolTotal', label: '工具总数', icon: Tools, tone: 'primary' },
  { key: 'availableToolCount', label: '可用工具', icon: CircleCheck, tone: 'success' },
  { key: 'rentedToolCount', label: '已租出', icon: Box, tone: 'primary' },
  { key: 'maintenanceToolCount', label: '维修中', icon: Tools, tone: 'warning' },
  { key: 'activeRentalCount', label: '进行中租赁', icon: Tickets, tone: 'primary' },
  { key: 'overdueRentalCount', label: '逾期租赁', icon: Clock, tone: 'danger' },
  { key: 'pendingDamageCount', label: '待处理报修', icon: Warning, tone: 'warning' },
  { key: 'userCount', label: '用户总数', icon: User, tone: 'info' },
  { key: 'todayRentalCount', label: '今日新增预约', icon: Calendar, tone: 'success' },
]

const quickLinks = [
  {
    path: '/tools',
    title: '工具管理',
    desc: '维护工具台账、状态与图片',
    icon: Box,
  },
  {
    path: '/rentals',
    title: '租赁管理',
    desc: '确认取件、归还验收与导出',
    icon: Tickets,
  },
  {
    path: '/damage',
    title: '报修管理',
    desc: '核验报修、跟踪维修进度',
    icon: Warning,
  },
  {
    path: '/knowledge',
    title: '知识库',
    desc: '上传文档，为 AI 问答提供知识',
    icon: Reading,
  },
  {
    path: '/messages',
    title: '消息中心',
    desc: '查看系统通知与业务提醒',
    icon: Bell,
  },
]

function statValue(key: keyof AdminDashboardVO): number {
  return stats.value?.[key] ?? 0
}

async function loadDashboard() {
  loading.value = true
  try {
    stats.value = await dashboardApi.get()
  } finally {
    loading.value = false
  }
}

onMounted(loadDashboard)
</script>

<template>
  <div class="page dashboard" v-loading="loading">
    <el-card shadow="never" class="welcome-card">
      <div class="welcome">
        <div>
          <h2 class="welcome-title">社区共享工具租赁管理后台</h2>
          <p class="welcome-desc">
            在这里维护工具、处理租赁与报修，并管理知识库和消息通知。
          </p>
        </div>
        <el-button type="primary" plain :icon="Tickets" @click="router.push('/rentals')">
          处理租赁
        </el-button>
      </div>
    </el-card>

    <el-row :gutter="16">
      <el-col v-for="item in statItems" :key="item.key" :xs="12" :sm="8" :md="8" :lg="6" :xl="5">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-inner">
            <div class="stat-icon" :class="`tone-${item.tone}`">
              <el-icon :size="22"><component :is="item.icon" /></el-icon>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ statValue(item.key) }}</div>
              <div class="stat-label">{{ item.label }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="quick-card">
      <template #header>
        <div class="toolbar" style="margin-bottom: 0">
          <span class="toolbar-title">快捷入口</span>
          <el-button link :icon="Clock" @click="loadDashboard">刷新统计</el-button>
        </div>
      </template>
      <div class="quick-grid">
        <button
          v-for="link in quickLinks"
          :key="link.path"
          type="button"
          class="quick-item"
          @click="router.push(link.path)"
        >
          <el-icon class="quick-icon" :size="22"><component :is="link.icon" /></el-icon>
          <div class="quick-text">
            <div class="quick-title">{{ link.title }}</div>
            <div class="quick-desc">{{ link.desc }}</div>
          </div>
        </button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.welcome-card :deep(.el-card__body) {
  padding: 20px 24px;
}

.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.welcome-title {
  margin: 0 0 6px;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.welcome-desc {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.stat-card {
  margin-bottom: 16px;
}

.stat-card :deep(.el-card__body) {
  padding: 18px 20px;
}

.stat-inner {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 10px;
  flex-shrink: 0;
}

.tone-primary {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.tone-success {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.tone-warning {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}

.tone-danger {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.tone-info {
  color: var(--el-color-info);
  background: var(--el-color-info-light-9);
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.quick-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  transition:
    border-color 0.2s,
    transform 0.2s;
}

.quick-item:hover {
  border-color: var(--el-color-primary-light-5);
  transform: translateY(-1px);
}

.quick-icon {
  color: var(--el-color-primary);
}

.quick-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.quick-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
