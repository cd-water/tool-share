<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-users">
      <div class="page-head">
        <div>
          <h1>用户管理</h1>
          <p class="sub">居民账号维护 · 角色分配与启用停用（手机号加密存储、脱敏展示）</p>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="realName"
          class="f-search"
          placeholder="按姓名搜索"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-select
          v-model="role"
          class="f-item"
          placeholder="全部角色"
          clearable
          @change="applyFilter"
        >
          <el-option label="居民" :value="0" />
          <el-option label="社区工作人员" :value="1" />
          <el-option label="系统管理员" :value="2" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !records.length" class="empty-box">
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无用户' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <el-table :data="records" row-key="id" class="user-table">
              <el-table-column label="用户名" width="130">
                <template #default="{ row }">
                  <span class="chip-code">{{ row.username }}</span>
                </template>
              </el-table-column>
              <el-table-column label="姓名" min-width="110">
                <template #default="{ row }">
                  <span class="name">{{ row.realName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="手机号" width="150">
                <template #default="{ row }">
                  <span class="phone">{{ row.phone }}</span>
                  <span class="tiny">（已脱敏）</span>
                </template>
              </el-table-column>
              <el-table-column label="角色" width="120">
                <template #default="{ row }">
                  <span class="tag" :class="roleTag(row.role)[1]">{{ roleTag(row.role)[0] }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-switch
                    :model-value="statusValue(row) === 1"
                    :loading="statusLoadingId === row.id"
                    @change="(val: string | number | boolean) => toggleStatus(row, val)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="注册时间" width="150">
                <template #default="{ row }">
                  <span class="time">{{ row.createTime || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="right">
                <template #default="{ row }">
                  <button type="button" class="link-btn" @click="openRole(row)">修改角色</button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="m-cards">
            <div v-for="row in records" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title">{{ row.realName }}</span>
                <span class="tag" :class="roleTag(row.role)[1]">{{ roleTag(row.role)[0] }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">用户名</span>
                <span class="mc-v"><span class="chip-code">{{ row.username }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">手机号</span>
                <span class="mc-v">{{ row.phone }} <span class="tiny">（已脱敏）</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">注册时间</span>
                <span class="mc-v">{{ row.createTime || '—' }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">状态</span>
                <span class="mc-v">
                  <el-switch
                    :model-value="statusValue(row) === 1"
                    :loading="statusLoadingId === row.id"
                    @change="(val: string | number | boolean) => toggleStatus(row, val)"
                  />
                </span>
              </div>
              <div class="mc-actions">
                <button type="button" class="link-btn" @click="openRole(row)">修改角色</button>
              </div>
            </div>
          </div>

          <div class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              :total="total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="onPageChange"
              @size-change="onSizeChange"
            />
          </div>
        </template>
      </div>
    </div>

    <!-- 修改角色 -->
    <el-dialog v-model="roleVisible" title="修改用户角色" width="420px" :close-on-click-modal="false">
      <p v-if="roleTarget" class="dialog-sub">
        用户：<b>{{ roleTarget.realName }}</b>（{{ roleTarget.username }}）· 当前角色：{{
          roleTag(roleTarget.role)[0]
        }}
      </p>
      <el-form label-position="top">
        <el-form-item label="角色" required>
          <el-radio-group v-model="roleForm.role" class="radio-col">
            <el-radio :value="0">居民</el-radio>
            <el-radio :value="1">社区工作人员</el-radio>
            <el-radio :value="2">系统管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { pageUsers, updateUserRole, updateUserStatus } from '../../api/user'
import { useUserStore } from '../../stores/user'
import type { UserQueryDTO, UserVO } from '../../api/types'

/** UserVO 契约未声明 status/createTime，后端 V3 后返回；页面按可选字段兼容 */
interface UserRow extends UserVO {
  status?: number
  createTime?: string
}

const ROLE: Record<number, [string, string]> = {
  0: ['居民', 't-primary'],
  1: ['社区工作人员', 't-info'],
  2: ['系统管理员', 't-accent'],
}
const roleTag = (v: number) => ROLE[v] ?? ['未知', 't-gray']
const statusValue = (row: UserRow) => row.status ?? 1

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const realName = ref('')
const role = ref<number | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<UserRow[]>([])
const loading = ref(false)
const failed = ref(false)
const statusLoadingId = ref(0)

const roleVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref<UserRow | null>(null)
const roleForm = reactive({ role: 0 })

async function load() {
  loading.value = true
  failed.value = false
  const params: UserQueryDTO = { page: page.value, size: size.value }
  if (realName.value.trim()) params.realName = realName.value.trim()
  if (role.value !== '') params.role = role.value

  syncUrl()
  try {
    const result = await pageUsers(params)
    records.value = result.records as UserRow[]
    total.value = result.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  page.value = 1
  load()
}

let keywordTimer: ReturnType<typeof setTimeout> | undefined
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(applyFilter, 300)
}

function reset() {
  realName.value = ''
  role.value = ''
  page.value = 1
  load()
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSizeChange(s: number) {
  size.value = s
  page.value = 1
  load()
}

function syncUrl() {
  router.replace({
    query: {
      ...(realName.value.trim() ? { name: realName.value.trim() } : {}),
      ...(role.value !== '' ? { role: String(role.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function openRole(row: UserRow) {
  roleTarget.value = row
  roleForm.role = row.role
  roleVisible.value = true
}

async function saveRole() {
  if (!roleTarget.value) return
  if (
    roleTarget.value.id === userStore.userId &&
    roleForm.role < roleTarget.value.role
  ) {
    ElMessage.warning('不能将自己降级')
    return
  }
  roleSaving.value = true
  try {
    await updateUserRole(roleTarget.value.id, { role: roleForm.role })
    ElMessage.success(`已将「${roleTarget.value.realName}」角色修改为「${roleTag(roleForm.role)[0]}」`)
    roleVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    roleSaving.value = false
  }
}

async function toggleStatus(row: UserRow, value: string | number | boolean) {
  const next = value ? 1 : 0
  if (statusValue(row) === next) return
  const action = next === 1 ? '启用' : '停用'
  const extra = next === 1 ? '' : '停用后该用户将无法登录系统。'
  try {
    await ElMessageBox.confirm(`确定${action}用户「${row.realName}」吗？${extra}`, `${action}账号`, {
      type: 'warning',
      confirmButtonText: action,
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  statusLoadingId.value = row.id
  try {
    await updateUserStatus(row.id, { status: next })
    ElMessage.success(`账号已${action}`)
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    statusLoadingId.value = 0
  }
}

onMounted(() => {
  const q = route.query
  realName.value = typeof q.name === 'string' ? q.name : ''
  const r = Number(q.role)
  role.value = [0, 1, 2].includes(r) ? r : ''
  const p = Number(q.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.admin-users {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
  --accent-2: #b44309;
  --surface: #fff;
  --surface-2: #f8faf8;
  --surface-3: #f1f4f1;
  --border: #e6eae6;
  --border-2: #d4dbd6;
  --text: #1e2b26;
  --text-2: #5a6a63;
  --text-3: #93a29b;
  --success: #1e7a32;
  --success-9: #e4f2e7;
  --warning: #b45309;
  --warning-9: #faf0dc;
  --danger: #c0392b;
  --danger-9: #fbeae8;
  --info: #2b6cb0;
  --info-9: #e9f1fb;
  --r-1: 4px;
  --r-2: 6px;
  --r-3: 8px;
  --shadow-1: 0 1px 2px rgba(16, 24, 40, 0.05);
  --shadow-2: 0 1px 2px rgba(16, 24, 40, 0.04), 0 4px 12px -4px rgba(16, 24, 40, 0.09);
  --font-mono: Consolas, Monaco, 'Courier New', monospace;

  color: var(--text);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.page-head h1 {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.011em;
  line-height: 1.35;
}
.page-head .sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.45;
}

.filter-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.filter-bar .f-search {
  width: 246px;
}
.filter-bar .f-item {
  width: 166px;
}
.filter-bar .grow {
  flex: 1;
}
.filter-bar :deep(.el-input__wrapper),
.filter-bar :deep(.el-select__wrapper) {
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
}
.filter-bar :deep(.el-input__wrapper:hover),
.filter-bar :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-7) inset;
}
.filter-bar :deep(.el-input__wrapper.is-focus),
.filter-bar :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.filter-bar :deep(.el-input__inner),
.filter-bar :deep(.el-select__placeholder) {
  font-size: 13.5px;
  color: var(--text);
}
.filter-bar :deep(.el-button),
.empty-box :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.filter-bar :deep(.el-button--primary),
.empty-box :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}
.filter-bar :deep(.el-button:not(.el-button--primary)),
.empty-box :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.filter-bar :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
}

/* ---------- 表格 ---------- */
.list-wrap {
  min-height: 180px;
}
.table-wrap {
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.user-table {
  width: 100%;
  font-size: 13px;
}
.user-table :deep(th.el-table__cell) {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--primary-3);
  background: var(--primary-tint);
}
.user-table :deep(td.el-table__cell) {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}
.user-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--surface-2);
}
.user-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.user-table :deep(.cell) {
  padding: 0;
  overflow: visible;
  text-overflow: clip;
  word-break: normal;
  white-space: normal;
}
.name {
  font-weight: 600;
}
.phone,
.time {
  white-space: nowrap;
}
.time {
  font-size: 12.5px;
  color: var(--text-2);
}
.user-table :deep(.el-switch__core) {
  --el-switch-on-color: var(--primary);
}

.m-cards {
  display: none;
}
.m-card {
  margin-bottom: 10px;
  padding: 12px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.mc-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 6px;
}
.mc-title {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.45;
}
.mc-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 12.5px;
  line-height: 1.45;
}
.mc-k {
  flex: none;
  color: var(--text-2);
}
.mc-v {
  color: var(--text);
  text-align: right;
  word-break: break-all;
}
.mc-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

/* ---------- 按钮 / 标签 ---------- */
.link-btn {
  height: 26px;
  padding: 0 5px;
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--primary-3);
  background: none;
  border: none;
  border-radius: var(--r-2);
  cursor: pointer;
  transition: color 120ms, background 120ms;
}
.link-btn:hover {
  color: var(--primary);
  background: var(--primary-tint);
}
.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 7px;
  border: 1px solid transparent;
  border-radius: var(--r-1);
  font-size: 11.5px;
  font-weight: 500;
  line-height: 1;
  letter-spacing: 0.01em;
  white-space: nowrap;
  vertical-align: middle;
}
.t-primary {
  background: var(--primary-tint);
  color: var(--primary-3);
  border-color: #cbd8d1;
}
.t-info {
  background: var(--info-9);
  color: var(--info);
  border-color: #c7d8ef;
}
.t-accent {
  background: var(--accent-9);
  color: var(--accent-2);
  border-color: #f6d2bb;
}
.t-gray {
  background: var(--surface-3);
  color: var(--text-2);
  border-color: #e2e7e2;
}
.chip-code {
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-1);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.tiny {
  font-size: 11.5px;
  color: var(--text-3);
}

/* ---------- 空态 / 分页 ---------- */
.empty-box {
  padding: 40px 20px;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.empty-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-2);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.pager :deep(.el-pagination) {
  --el-color-primary: #1c3a35;
  font-size: 12.5px;
  color: var(--text-2);
}
.pager :deep(.el-pagination.is-background .el-pager li) {
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r-2);
  color: var(--text-2);
}
.pager :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
.pager :deep(.el-pagination.is-background .btn-prev),
.pager :deep(.el-pagination.is-background .btn-next) {
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r-2);
  color: var(--text-2);
}

/* ---------- 对话框 ---------- */
.dialog-sub {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}
.radio-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .filter-bar .f-search,
  .filter-bar .f-item {
    width: 100%;
  }
  .filter-bar .grow {
    display: none;
  }
  .table-wrap {
    display: none;
  }
  .m-cards {
    display: block;
  }
  .pager :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
