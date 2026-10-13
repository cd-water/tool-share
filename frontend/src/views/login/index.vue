<template>
  <div class="login-wrap">
    <el-form
      ref="formRef"
      class="login-box"
      :model="form"
      :rules="rules"
      label-position="top"
      @keyup.enter="handleLogin"
    >
      <h1>社区共享工具租赁系统</h1>
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          autocomplete="current-password"
          show-password
        />
      </el-form-item>
      <el-button type="primary" class="submit" :loading="loading" @click="handleLogin">
        登 录
      </el-button>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { login } from '../../api/user'

const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
})
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const vo = await login(form)
    ElMessage.success(`欢迎，${vo.realName}`)
    router.push((route.query.redirect as string) || '/tools')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f2f4f1;
}
.login-box {
  width: min(400px, calc(100vw - 40px));
  background: #fff;
  border: 1px solid #d8ded9;
  border-top: 4px solid #e05206;
  border-radius: 8px;
  padding: 36px 36px 30px;
}
.login-box h1 {
  font-size: 22px;
  color: #1c3a35;
  text-align: center;
  letter-spacing: 2px;
  margin: 0 0 28px;
}
.submit {
  width: 100%;
  margin-top: 4px;
  background: #1c3a35;
  border-color: #1c3a35;
}
.submit:hover {
  background: #2a4c46;
  border-color: #2a4c46;
}
</style>
