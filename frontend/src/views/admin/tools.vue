<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-tools">
      <div class="page-head">
        <div>
          <h1>工具管理</h1>
          <p class="sub">工具录入、信息维护、状态变更与状态日志（工具编号按 SQ-G-XX-YYYY 自动生成）</p>
        </div>
        <div class="ph-actions">
          <el-button type="primary" @click="router.push('/admin/tools/new')">＋ 新增工具</el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="filters.keyword"
          class="f-search"
          placeholder="搜索工具名称 / 编号 / 型号"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-select
          v-model="filters.category"
          class="f-item"
          placeholder="全部类别"
          clearable
          @change="applyFilter"
        >
          <el-option label="维修" :value="1" />
          <el-option label="园艺" :value="2" />
          <el-option label="清洁" :value="3" />
          <el-option label="其他" :value="4" />
        </el-select>
        <el-select
          v-model="filters.status"
          class="f-item"
          placeholder="全部状态"
          clearable
          @change="applyFilter"
        >
          <el-option label="可用" :value="1" />
          <el-option label="已借出" :value="2" />
          <el-option label="维修中" :value="3" />
          <el-option label="报废" :value="4" />
          <el-option label="下架" :value="5" />
        </el-select>
        <el-select
          v-model="filters.auditStatus"
          class="f-item"
          placeholder="审核状态"
          clearable
          @change="applyFilter"
        >
          <el-option label="待审核" :value="1" />
          <el-option label="已通过" :value="2" />
          <el-option label="需修改" :value="3" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !paged.length" class="empty-box">
          <svg
            class="empty-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
            <path d="M3.3 7 12 12l8.7-5M12 22V12" />
          </svg>
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无工具数据' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
          <el-button v-else type="primary" @click="router.push('/admin/tools/new')">录入新工具</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <el-table :data="paged" row-key="id" class="tool-table">
              <el-table-column label="工具编号" width="136">
                <template #default="{ row }">
                  <span class="chip-code">{{ row.toolCode }}</span>
                </template>
              </el-table-column>
              <el-table-column label="工具" min-width="160">
                <template #default="{ row }">
                  <div class="tool-name" :title="row.name">{{ row.name }}</div>
                  <div class="tiny model-spec" :title="row.modelSpec">{{ row.modelSpec }}</div>
                </template>
              </el-table-column>
              <el-table-column label="类别" width="92">
                <template #default="{ row }">
                  <span class="tag" :class="categoryTag(row.category)[1]">
                    {{ categoryTag(row.category)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="92">
                <template #default="{ row }">
                  <span class="tag" :class="statusTag(row.status)[1]">
                    {{ statusTag(row.status)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="损坏程度" width="112">
                <template #default="{ row }">
                  <span class="tag" :class="damageTag(row.damageLevel)[1]">
                    {{ damageTag(row.damageLevel)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="押金" width="92">
                <template #default="{ row }">
                  <span class="money">{{ money(row.deposit) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="审核" width="96">
                <template #default="{ row }">
                  <span class="tag" :class="auditTag(row.auditStatus)[1]">
                    {{ auditTag(row.auditStatus)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="存放区域" min-width="120">
                <template #default="{ row }">
                  <span class="loc" :title="row.storageLocation">{{ row.storageLocation || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="216" align="right">
                <template #default="{ row }">
                  <button type="button" class="link-btn" @click="editTool(row)">编辑</button>
                  <button
                    v-if="row.status === 1"
                    type="button"
                    class="link-btn danger"
                    @click="offShelf(row)"
                  >
                    下架
                  </button>
                  <button type="button" class="link-btn" @click="openStatus(row)">变更状态</button>
                  <button type="button" class="link-btn" @click="openLogs(row)">日志</button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="m-cards">
            <div v-for="row in paged" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title" :title="row.name">{{ row.name }}</span>
                <span class="tag" :class="statusTag(row.status)[1]">{{ statusTag(row.status)[0] }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">工具编号</span>
                <span class="mc-v"><span class="chip-code">{{ row.toolCode }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">类别 / 损坏程度</span>
                <span class="mc-v">
                  {{ categoryTag(row.category)[0] }} · {{ damageTag(row.damageLevel)[0] }}
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">押金</span>
                <span class="mc-v"><span class="money">{{ money(row.deposit) }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">审核状态</span>
                <span class="mc-v">
                  <span class="tag" :class="auditTag(row.auditStatus)[1]">
                    {{ auditTag(row.auditStatus)[0] }}
                  </span>
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">存放区域</span>
                <span class="mc-v">{{ row.storageLocation || '—' }}</span>
              </div>
              <div class="mc-actions">
                <button type="button" class="link-btn" @click="editTool(row)">编辑</button>
                <button
                  v-if="row.status === 1"
                  type="button"
                  class="link-btn danger"
                  @click="offShelf(row)"
                >
                  下架
                </button>
                <button type="button" class="link-btn" @click="openStatus(row)">变更状态</button>
                <button type="button" class="link-btn" @click="openLogs(row)">日志</button>
              </div>
            </div>
          </div>

          <div class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              :total="filtered.length"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              background
              @current-change="syncUrl"
              @size-change="onSizeChange"
            />
          </div>
        </template>
      </div>
    </div>

    <!-- 状态变更 -->
    <el-dialog v-model="statusVisible" title="变更工具状态" width="440px" :close-on-click-modal="false">
      <p class="dialog-sub">{{ statusTarget?.name }}（{{ statusTarget?.toolCode }}）</p>
      <el-form label-position="top">
        <el-form-item label="新状态" required>
          <el-select v-model="statusForm.status" class="full" placeholder="请选择">
            <el-option label="可用" :value="1" />
            <el-option label="已借出" :value="2" />
            <el-option label="维修中" :value="3" />
            <el-option label="报废" :value="4" />
            <el-option label="下架" :value="5" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="statusForm.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="变更原因 / 关联单号（选填）"
          />
        </el-form-item>
      </el-form>
      <p class="form-tip">状态变更将自动记录到工具状态日志</p>
      <template #footer>
        <el-button @click="statusVisible = false">取消</el-button>
        <el-button type="primary" :loading="statusSaving" @click="saveStatus">确定变更</el-button>
      </template>
    </el-dialog>

    <!-- 状态日志 -->
    <el-drawer v-model="logsVisible" title="状态变更日志" size="460px">
      <div v-loading="logsLoading" class="logs-body">
        <div v-if="logsTarget" class="log-tool">
          <span class="chip-code">{{ logsTarget.toolCode }}</span>
          <span>{{ logsTarget.name }}</span>
        </div>
        <ul v-if="logs.length" class="timeline">
          <li v-for="(log, index) in logs" :key="log.id" :class="{ latest: index === 0 }">
            <span class="node" />
            <div class="c">
              <span class="tag" :class="statusTag(log.oldStatus)[1]">
                {{ statusTag(log.oldStatus)[0] }}
              </span>
              <span class="arrow">→</span>
              <span class="tag" :class="statusTag(log.newStatus)[1]">
                {{ statusTag(log.newStatus)[0] }}
              </span>
              <span v-if="log.remark" class="remark">· {{ log.remark }}</span>
            </div>
            <div class="t">{{ fmtDateTime(log.createTime) }}</div>
          </li>
        </ul>
        <div v-else class="logs-empty">暂无状态日志</div>
      </div>
      <template #footer>
        <el-button @click="logsVisible = false">关 闭</el-button>
      </template>
    </el-drawer>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { getToolStatusLogs, pageTools, updateToolStatus } from '../../api/tool'
import type { Tool, ToolQueryDTO, ToolStatusLog } from '../../api/types'

const TOOL_STATUS: Record<number, [string, string]> = {
  1: ['可用', 't-success'],
  2: ['已借出', 't-info'],
  3: ['维修中', 't-warning'],
  4: ['报废', 't-danger'],
  5: ['下架', 't-gray'],
}
const CATEGORY: Record<number, [string, string]> = {
  1: ['维修', 't-primary'],
  2: ['园艺', 't-success'],
  3: ['清洁', 't-info'],
  4: ['其他', 't-gray'],
}
const DAMAGE: Record<number, [string, string]> = {
  1: ['全新', 't-success'],
  2: ['轻微磨损', 't-info'],
  3: ['中度磨损', 't-warning'],
  4: ['严重损坏', 't-danger'],
}
const AUDIT: Record<number, [string, string]> = {
  1: ['待审核', 't-warning'],
  2: ['已通过', 't-success'],
  3: ['需修改', 't-danger'],
}

const statusTag = (v: number) => TOOL_STATUS[v] ?? ['未知', 't-gray']
const categoryTag = (v: number) => CATEGORY[v] ?? ['未知', 't-gray']
const damageTag = (v: number) => DAMAGE[v] ?? ['未知', 't-gray']
const auditTag = (v: number) => AUDIT[v] ?? ['未知', 't-gray']

function money(n?: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

const route = useRoute()
const router = useRouter()

interface Filters {
  keyword: string
  category: number | ''
  status: number | ''
  auditStatus: number | ''
}
const filters = reactive<Filters>({
  keyword: '',
  category: '',
  status: '',
  auditStatus: '',
})
const all = ref<Tool[]>([])
const loading = ref(false)
const failed = ref(false)
const page = ref(1)
const size = ref(10)

const statusVisible = ref(false)
const statusSaving = ref(false)
const statusTarget = ref<Tool | null>(null)
const statusForm = reactive({ status: '' as number | '', remark: '' })

const logsVisible = ref(false)
const logsLoading = ref(false)
const logsTarget = ref<Tool | null>(null)
const logs = ref<ToolStatusLog[]>([])

const filtered = computed(() =>
  filters.auditStatus === ''
    ? all.value
    : all.value.filter((t) => t.auditStatus === filters.auditStatus),
)
const paged = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
)

/**
 * ponytail: ToolQueryDTO 没有 auditStatus 参数，审核筛选需要全量数据；
 * 单次拉取 200 条本地过滤/分页，工具量超限再让后端补 auditStatus 查询参数。
 */
async function load() {
  loading.value = true
  failed.value = false
  const params: ToolQueryDTO = { page: 1, size: 200 }
  const keyword = filters.keyword.trim()
  if (keyword) params.keyword = keyword
  if (filters.category !== '') params.category = filters.category
  if (filters.status !== '') params.status = filters.status

  try {
    const result = await pageTools(params)
    all.value = result.records
    const maxPage = Math.max(1, Math.ceil(filtered.value.length / size.value))
    if (page.value > maxPage) page.value = maxPage
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  page.value = 1
  load()
  syncUrl()
}

let keywordTimer: ReturnType<typeof setTimeout> | undefined
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(applyFilter, 300)
}

function reset() {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  filters.auditStatus = ''
  page.value = 1
  load()
  syncUrl()
}

function onSizeChange(s: number) {
  size.value = s
  page.value = 1
  syncUrl()
}

function syncUrl() {
  router.replace({
    query: {
      ...(filters.keyword.trim() ? { kw: filters.keyword.trim() } : {}),
      ...(filters.category !== '' ? { cat: String(filters.category) } : {}),
      ...(filters.status !== '' ? { status: String(filters.status) } : {}),
      ...(filters.auditStatus !== '' ? { audit: String(filters.auditStatus) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function editTool(row: Tool) {
  router.push({ path: '/admin/tools/new', query: { id: String(row.id) } })
}

async function offShelf(row: Tool) {
  try {
    await ElMessageBox.confirm(`确定下架工具「${row.name}」吗？下架后居民将无法预约。`, '下架工具', {
      type: 'warning',
      confirmButtonText: '确定下架',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await updateToolStatus(row.id, { status: 5, remark: '下架' })
    ElMessage.success('工具已下架')
    await load()
  } catch {
    // 拦截器已统一提示
  }
}

function openStatus(row: Tool) {
  statusTarget.value = row
  statusForm.status = ''
  statusForm.remark = ''
  statusVisible.value = true
}

async function saveStatus() {
  if (!statusTarget.value || statusForm.status === '') {
    ElMessage.warning('请选择新状态')
    return
  }
  statusSaving.value = true
  try {
    await updateToolStatus(statusTarget.value.id, {
      status: statusForm.status,
      remark: statusForm.remark.trim() || undefined,
    })
    ElMessage.success(`状态已更新为「${statusTag(statusForm.status)[0]}」`)
    statusVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    statusSaving.value = false
  }
}

async function openLogs(row: Tool) {
  logsTarget.value = row
  logs.value = []
  logsVisible.value = true
  logsLoading.value = true
  try {
    const result = await getToolStatusLogs(row.id)
    logs.value = [...result].sort((a, b) =>
      (b.createTime || '').localeCompare(a.createTime || ''),
    )
  } catch {
    // 拦截器已统一提示
  } finally {
    logsLoading.value = false
  }
}

onMounted(() => {
  const q = route.query
  filters.keyword = typeof q.kw === 'string' ? q.kw : ''
  const cat = Number(q.cat)
  filters.category = [1, 2, 3, 4].includes(cat) ? cat : ''
  const status = Number(q.status)
  filters.status = [1, 2, 3, 4, 5].includes(status) ? status : ''
  const audit = Number(q.audit)
  filters.auditStatus = [1, 2, 3].includes(audit) ? audit : ''
  const p = Number(q.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.admin-tools {
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
.ph-actions :deep(.el-button),
.filter-bar :deep(.el-button),
.empty-box :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.ph-actions :deep(.el-button--primary),
.filter-bar :deep(.el-button--primary),
.empty-box :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}

/* ---------- 筛选栏 ---------- */
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
  width: 150px;
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
.filter-bar :deep(.el-button:not(.el-button--primary)) {
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
.tool-table {
  width: 100%;
  font-size: 13px;
}
.tool-table :deep(th.el-table__cell) {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--primary-3);
  background: var(--primary-tint);
}
.tool-table :deep(td.el-table__cell) {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}
.tool-table :deep(.cell) {
  overflow: visible;
  text-overflow: clip;
}
.tool-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--surface-2);
}
.tool-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.tool-name {
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.loc,
.model-spec {
  display: block;
  overflow: hidden;
  color: var(--text-2);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.model-spec {
  font-size: 11.5px;
  color: var(--text-3);
}

/* ---------- 移动卡片 ---------- */
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
  white-space: nowrap;
  text-overflow: ellipsis;
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

/* ---------- 按钮 / 标签 / 文本 ---------- */
.link-btn {
  height: 26px;
  padding: 0 4px;
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
.link-btn.danger {
  color: var(--danger);
}
.link-btn.danger:hover {
  color: #a93226;
  background: var(--danger-9);
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
.t-success {
  background: var(--success-9);
  color: var(--success);
  border-color: #cbe7d1;
}
.t-warning {
  background: var(--warning-9);
  color: var(--warning);
  border-color: #eddcb4;
}
.t-danger {
  background: var(--danger-9);
  color: var(--danger);
  border-color: #efc7c1;
}
.t-info {
  background: var(--info-9);
  color: var(--info);
  border-color: #c7d8ef;
}
.t-primary {
  background: var(--primary-tint);
  color: var(--primary-3);
  border-color: #cbd8d1;
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
.money {
  font-weight: 600;
  color: var(--accent-2);
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
.empty-icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 8px;
  color: var(--text-3);
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

/* ---------- 对话框 / 抽屉 ---------- */
.full {
  width: 100%;
}
.dialog-sub {
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--text-2);
}
.form-tip {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-3);
}
.log-tool {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 13px;
  font-weight: 600;
}
.logs-body {
  min-height: 160px;
}
.logs-empty {
  padding: 24px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}
.timeline li {
  position: relative;
  margin-left: 5px;
  padding: 0 0 16px 22px;
  border-left: 1px solid var(--border);
}
.timeline li:last-child {
  padding-bottom: 0;
  border-left-color: transparent;
}
.timeline .node {
  position: absolute;
  top: 4px;
  left: -5.5px;
  width: 10px;
  height: 10px;
  background: var(--primary-7);
  border: 2px solid #fff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--border);
}
.timeline li.latest .node {
  background: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-tint);
}
.timeline .c {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 13px;
  line-height: 1.6;
}
.timeline .arrow {
  color: var(--text-3);
}
.timeline .remark {
  color: var(--text-2);
}
.timeline .t {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
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
