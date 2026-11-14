<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import { adminApi } from '@/api/admins'
import type { AdminRole, AdminVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { ADMIN_ROLE_LABELS, enumLabel, optionList } from '@/utils/enums'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const admins = ref<AdminVO[]>([])
const keyword = ref('')

const roleOptions = optionList(ADMIN_ROLE_LABELS)

async function loadList() {
  loading.value = true
  try {
    admins.value = await adminApi.list()
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return admins.value
  return admins.value.filter((row) =>
    `${row.username}${enumLabel(ADMIN_ROLE_LABELS, row.role)}`.toLowerCase().includes(kw),
  )
})

const stats = computed(() => [
  { label: '管理员总数', value: admins.value.length, tone: 'brand' },
  {
    label: '系统管理员',
    value: admins.value.filter((row) => row.role === 'SUPER_ADMIN').length,
    tone: 'ok',
  },
  {
    label: '工作人员',
    value: admins.value.filter((row) => row.role === 'STAFF').length,
    tone: 'info',
  },
])

function isSelf(row: AdminVO): boolean {
  return row.username === auth.username
}

/* ---------- 修改角色 ---------- */
const roleVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref<AdminVO | null>(null)
const roleValue = ref<AdminRole>('STAFF')

function openRole(row: AdminVO) {
  roleTarget.value = row
  roleValue.value = row.role
  roleVisible.value = true
}

async function submitRole() {
  const target = roleTarget.value
  if (!target || target.role === roleValue.value) {
    roleVisible.value = false
    return
  }
  roleSaving.value = true
  try {
    await adminApi.updateRole(target.id, roleValue.value)
    ElMessage.success(`已将 ${target.username} 的角色改为${ADMIN_ROLE_LABELS[roleValue.value]}`)
    roleVisible.value = false
    // 角色变更即时生效；改到自己时同步会话并离开仅系统管理员可访问的页面
    if (isSelf(target)) {
      await auth.fetchInfo()
      if (!auth.isSuperAdmin) {
        await router.push('/dashboard')
        return
      }
    }
    await loadList()
  } finally {
    roleSaving.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">ACCESS CONTROL</p>
        <h1>管理员账号</h1>
        <p class="sub">管理工作人员与系统管理员角色。角色变更即时生效，权限范围与后端角色模型一致。</p>
      </div>
    </div>

    <div class="kb-stats reveal" style="grid-template-columns: repeat(auto-fit, minmax(180px, 1fr))">
      <div class="kb-stat" v-for="item in stats" :key="item.label">
        <span class="ks-ic" :class="item.tone">
          <User class="ic" />
        </span>
        <div>
          <b class="num">{{ item.value }}</b>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </div>

    <div class="filterbar reveal">
      <div class="f-search">
        <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
        <input v-model="keyword" class="input" placeholder="搜索账号 / 角色…" />
      </div>
      <span class="grow"></span>
      <span class="tiny">共 {{ filtered.length }} 个账号</span>
    </div>

    <div class="table-wrap reveal">
      <div class="table-scroll">
        <table class="tbl">
          <thead>
            <tr>
              <th>账号</th>
              <th>角色</th>
              <th>状态</th>
              <th style="width: 130px">操作</th>
            </tr>
          </thead>
          <tbody v-if="loading">
            <tr v-for="i in 3" :key="`sk-${i}`">
              <td colspan="4"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
          <tbody v-else-if="!filtered.length">
            <tr>
              <td colspan="4">
                <div class="empty">
                  <User class="e-ic" />
                  <h4>暂无管理员账号</h4>
                  <p>当前筛选下没有匹配的账号。</p>
                </div>
              </td>
            </tr>
          </tbody>
          <tbody v-else>
            <tr v-for="row in filtered" :key="row.id">
              <td>
                <div class="cell-main">{{ row.username }}</div>
                <div v-if="isSelf(row)" class="cell-sub">当前登录账号</div>
              </td>
              <td>
                <span class="badge" :class="row.role === 'SUPER_ADMIN' ? 'brand' : 'neutral'">
                  {{ enumLabel(ADMIN_ROLE_LABELS, row.role) }}
                </span>
              </td>
              <td>
                <!-- ponytail: 后端 AdminVO 未暴露 status 且无禁用接口，列表中的账号均可登录；接口补充 status 后改为读取真实状态 -->
                <span class="badge ok"><span class="dot"></span>启用</span>
              </td>
              <td>
                <div class="row-actions">
                  <button class="link-btn" @click="openRole(row)">修改角色</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="roleVisible" class="mask show" @click.self="roleVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>修改角色</h3>
            <p class="d-sub">{{ roleTarget?.username }} · 当前 {{ roleTarget ? enumLabel(ADMIN_ROLE_LABELS, roleTarget.role) : '—' }}</p>
          </div>
          <button class="dlg-x" @click="roleVisible = false" aria-label="关闭">
            <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
          </button>
        </div>
        <div class="dlg-body">
          <label class="tiny" style="display: block; margin-bottom: 8px">角色</label>
          <select v-model="roleValue" class="select" style="width: 100%; height: 40px">
            <option v-for="item in roleOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <p class="tiny" style="margin: 12px 0 0; line-height: 1.7">
            系统管理员可管理工具、知识库与管理员账号；工作人员仅处理租赁、报修与消息。变更后立即生效。
          </p>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="roleVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="roleSaving" @click="submitRole">
            {{ roleSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ks-ic.ok {
  background: var(--ok-bg);
  color: var(--ok);
}
.ks-ic.info {
  background: var(--info-bg);
  color: var(--info);
}
</style>
