<template>
  <div class="layout">
    <aside class="side">
      <div class="brand">社区共享工具租赁系统</div>
      <nav class="nav">
        <RouterLink v-for="item in nav" :key="item.path" :to="item.path" class="nav-item">
          {{ item.label }}
        </RouterLink>
      </nav>
      <div class="spacer"></div>
      <div class="who">
        登录为 <b>{{ user.realName || '未登录' }}</b>
      </div>
      <button class="logout" @click="logout">退出登录</button>
    </aside>
    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const user = useUserStore()
const router = useRouter()

// 角色对应导航：0居民 1工作人员 2管理员（三角色职责分离，互不继承）
const NAVS: Record<number, { label: string; path: string }[]> = {
  0: [
    { label: '工具广场', path: '/tools' },
    { label: '我的租赁', path: '/rentals' },
    { label: '损坏报修', path: '/repairs' },
    { label: '消息中心', path: '/messages' },
    { label: 'AI 问答', path: '/ai' },
  ],
  1: [
    { label: '工具管理', path: '/admin/tools' },
    { label: '租赁管理', path: '/admin/rentals' },
    { label: '报修处理', path: '/admin/repairs' },
  ],
  2: [
    { label: '用户管理', path: '/admin/users' },
    { label: '工具审核', path: '/admin/audit' },
    { label: '知识库管理', path: '/admin/kb' },
  ],
}
const nav = computed(() => NAVS[user.role] ?? NAVS[0]!)

function logout() {
  user.$reset()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
  background: #f2f4f1;
}
.side {
  width: 216px;
  flex: none;
  background: #1c3a35;
  color: #cfe0da;
  display: flex;
  flex-direction: column;
  padding: 22px 0 18px;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}
.brand {
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  padding: 0 22px 20px;
  border-bottom: 1px solid #2a4c46;
  margin-bottom: 14px;
}
.nav {
  padding: 0 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.nav-item {
  padding: 9px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: #cfe0da;
}
.nav-item:hover {
  background: #2a4c46;
}
.nav-item.router-link-active {
  background: #fff;
  color: #1c3a35;
  font-weight: 600;
}
.spacer {
  flex: 1;
}
.who {
  padding: 12px 24px 0;
  font-size: 13px;
  color: #8fa8a0;
}
.who b {
  color: #fff;
}
.logout {
  background: none;
  border: none;
  color: #cfe0da;
  padding: 6px 24px;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.logout:hover {
  color: #fff;
  text-decoration: underline;
}
.main {
  flex: 1;
  min-width: 0;
  padding: 30px 40px 60px;
}
@media (max-width: 760px) {
  .layout {
    flex-direction: column;
  }
  .side {
    width: 100%;
    height: auto;
    position: static;
  }
  .nav {
    flex-direction: row;
    flex-wrap: wrap;
  }
  .main {
    padding: 22px 16px 60px;
  }
}
</style>
