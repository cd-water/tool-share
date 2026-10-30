<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 站内路径白名单校验，防开放重定向 */
function safeRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/menu-1'
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => null)
  if (!valid) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.push(safeRedirect())
  } catch {
    // 错误提示已由响应拦截器弹出，此处无需处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-body">
        <div class="login-title">
          <BrandMark :size="36" />
          <span class="title-text">TicketBox <span class="divider">|</span> 票务盒子管理后台</span>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="账号" :prefix-icon="User" size="large" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="密码"
              :prefix-icon="Lock"
              size="large"
            />
          </el-form-item>
          <el-button
            class="login-btn"
            type="primary"
            size="large"
            :loading="loading"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: #f5f8fd;
}

.login-card {
  display: flex;
  width: 400px;
  background: #fff;
  border: 1px solid #e3ecf8;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(29, 111, 224, 0.06);
}

.login-body {
  flex: 1;
  padding: 40px 36px 32px;
  border-left: 4px solid var(--el-color-primary);
}

.login-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}

.title-text {
  font-size: 17px;
  font-weight: 600;
  color: #1f2d3d;
  white-space: nowrap;
}

.divider {
  color: inherit;
  font-weight: 400;
  margin: 0 4px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}
</style>
