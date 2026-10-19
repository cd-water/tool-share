<template>
  <el-config-provider :locale="zhCn">
  <div class="tool-square">
    <div class="page-head">
      <div>
        <h1>工具广场</h1>
        <p class="sub">社区共享工具一览 · 选择需要的工具在线预约，取件前 24 小时可免费取消</p>
      </div>
    </div>

    <div class="filter-bar">
      <el-input
        v-model="filters.keyword"
        class="f-search"
        placeholder="搜索工具名称 / 型号"
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
        :placeholder="filters.avail ? '可用（仅看可借）' : '全部状态'"
        :disabled="filters.avail"
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
        v-model="filters.orderBy"
        class="f-item"
        placeholder="默认排序"
        clearable
        @change="applyFilter"
      >
        <el-option label="按租赁次数" value="rentalCount" />
        <el-option label="按最近维修时间" value="lastRepairTime" />
      </el-select>
      <el-checkbox v-model="filters.avail" class="avail-check" @change="onAvailChange">
        仅看可借
      </el-checkbox>
      <div class="grow" />
      <el-button class="btn-reset" @click="reset">重 置</el-button>
      <el-button type="primary" @click="applyFilter">查 询</el-button>
    </div>

    <div class="result-line">
      <span class="muted">共 {{ total }} 件工具</span>
    </div>

    <div v-loading="loading" class="tool-grid">
      <div
        v-for="tool in list"
        :key="tool.id"
        class="tool-card"
        :class="{ off: tool.status !== 1 }"
        role="link"
        tabindex="0"
        @click="openTool(tool)"
        @keyup.enter="openTool(tool)"
      >
        <div class="tool-img">
          <svg
            class="tool-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path :d="CATEGORY_ICON[tool.category] || CATEGORY_ICON[4]" />
          </svg>
          <img
            v-if="tool.images?.length"
            class="tool-photo"
            :src="tool.images[0]"
            :alt="tool.name"
            loading="lazy"
            @error="hideImage"
          />
          <span class="tool-status">
            <span class="tag" :class="statusTag(tool.status)[1]">{{ statusTag(tool.status)[0] }}</span>
          </span>
        </div>
        <div class="tool-body">
          <div class="tool-name" :title="tool.name">{{ tool.name }}</div>
          <div class="tool-code-row">
            <span class="chip-code">{{ tool.toolCode }}</span>
            <span class="tool-model" :title="tool.modelSpec">{{ tool.modelSpec }}</span>
          </div>
          <div class="tool-tags">
            <span class="tag" :class="categoryTag(tool.category)[1]">{{ categoryTag(tool.category)[0] }}</span>
            <span class="tag" :class="damageTag(tool.damageLevel)[1]">{{ damageTag(tool.damageLevel)[0] }}</span>
          </div>
          <div class="tool-meta">
            <span class="money">{{ money(tool.deposit) }}</span>
            <span class="tool-loc" :title="tool.storageLocation">{{ tool.storageLocation }}</span>
          </div>
          <div class="tool-foot">
            <span class="detail-link">查看详情 ›</span>
          </div>
        </div>
      </div>

      <div v-if="!loading && !list.length" class="empty-card">
        <div class="empty-ill">{{ failed ? '⚠️' : '🔍' }}</div>
        <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无符合条件的工具' }}</p>
        <el-button v-if="failed" class="btn-reset" @click="load">重新加载</el-button>
        <el-button v-else class="btn-reset" @click="reset">清空筛选条件</el-button>
      </div>
    </div>

    <div v-if="total > 0" class="pager">
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
  </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { pageTools } from '../../api/tool'
import type { Tool, ToolQueryDTO } from '../../api/types'

// ---------- 字典（与设计规范状态色板一致） ----------
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
const CATEGORY_ICON: Record<number, string> = {
  1: 'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z',
  2: 'M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10z',
  3: 'M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z',
  4: 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z',
}

const statusTag = (v: number) => TOOL_STATUS[v] ?? ['未知', 't-gray']
const categoryTag = (v: number) => CATEGORY[v] ?? ['未知', 't-gray']
const damageTag = (v: number) => DAMAGE[v] ?? ['未知', 't-gray']

function money(n: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

// ---------- 查询状态 ----------
interface Filters {
  keyword: string
  category: number | ''
  status: number | ''
  orderBy: '' | 'rentalCount' | 'lastRepairTime'
  avail: boolean
}

const route = useRoute()
const router = useRouter()
const filters = reactive<Filters>({
  keyword: '',
  category: '',
  status: '',
  orderBy: '',
  avail: true,
})
const page = ref(1)
const size = ref(10)
const total = ref(0)
const list = ref<Tool[]>([])
const loading = ref(false)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  const params: ToolQueryDTO = { page: page.value, size: size.value }
  const keyword = filters.keyword.trim()
  if (keyword) params.keyword = keyword
  if (filters.category !== '') params.category = filters.category
  if (filters.avail) params.status = 1
  else if (filters.status !== '') params.status = filters.status
  if (filters.orderBy) params.orderBy = filters.orderBy

  syncUrl()
  try {
    const result = await pageTools(params)
    list.value = result.records
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

function onAvailChange(checked: boolean | string | number) {
  if (checked) filters.status = ''
  applyFilter()
}

let keywordTimer: ReturnType<typeof setTimeout> | undefined
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(applyFilter, 300)
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

function reset() {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  filters.orderBy = ''
  filters.avail = true
  page.value = 1
  load()
}

function openTool(tool: Tool) {
  router.push(`/tools/${tool.id}`)
}

function hideImage(e: Event) {
  ;(e.target as HTMLImageElement).style.display = 'none'
}

// ---------- URL 状态同步 ----------
function syncUrl() {
  const query: Record<string, string> = {}
  const keyword = filters.keyword.trim()
  if (keyword) query.kw = keyword
  if (filters.category !== '') query.cat = String(filters.category)
  if (!filters.avail) query.all = '1'
  if (!filters.avail && filters.status !== '') query.status = String(filters.status)
  if (filters.orderBy) query.sort = filters.orderBy
  if (page.value > 1) query.page = String(page.value)
  if (size.value !== 10) query.size = String(size.value)
  router.replace({ query })
}

function readUrl() {
  const q = route.query
  const str = (v: unknown) => (typeof v === 'string' ? v : '')
  const num = (v: unknown) => {
    const n = Number(str(v))
    return Number.isFinite(n) ? n : 0
  }

  filters.keyword = str(q.kw)
  const cat = num(q.cat)
  filters.category = [1, 2, 3, 4].includes(cat) ? cat : ''
  filters.avail = str(q.all) !== '1'
  const status = num(q.status)
  filters.status = !filters.avail && [1, 2, 3, 4, 5].includes(status) ? status : ''
  const sort = str(q.sort)
  filters.orderBy = sort === 'rentalCount' || sort === 'lastRepairTime' ? sort : ''

  const p = num(q.page)
  page.value = p > 0 ? p : 1
  const s = num(q.size)
  size.value = [10, 20, 50].includes(s) ? s : 10
}

onMounted(() => {
  readUrl()
  load()
})
</script>

<style scoped>
.tool-square {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
  --accent: #e05206;
  --accent-2: #b44309;
  --accent-9: #fdeee3;
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

/* ---------- 页头 ---------- */
.page-head {
  margin-bottom: 16px;
}
.page-head h1 {
  margin: 0 0 3px;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.011em;
  line-height: 1.35;
  color: var(--text);
}
.page-head .sub {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.45;
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
  width: 166px;
}
.filter-bar .grow {
  flex: 1;
}
.avail-check {
  margin-left: 4px;
}

.filter-bar :deep(.el-input__wrapper),
.filter-bar :deep(.el-select__wrapper) {
  min-height: 32px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
  font-size: 13.5px;
  padding: 0 10px;
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
.filter-bar :deep(.el-checkbox__label) {
  font-size: 13.5px;
  color: var(--text-2);
}
.filter-bar :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: var(--primary);
  border-color: var(--primary);
}
.filter-bar :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: var(--text);
}
.filter-bar :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.filter-bar :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}
.filter-bar :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.filter-bar :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
}

.result-line {
  margin-bottom: 12px;
}
.muted {
  font-size: 12.5px;
  color: var(--text-2);
}

/* ---------- 卡片网格 ---------- */
.tool-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  min-height: 180px;
}
.tool-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
  cursor: pointer;
  transition: box-shadow 180ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 180ms cubic-bezier(0.4, 0, 0.2, 1), border-color 180ms cubic-bezier(0.4, 0, 0.2, 1);
}
.tool-card:hover,
.tool-card:focus-visible {
  border-color: var(--primary-7);
  box-shadow: var(--shadow-2);
  transform: translateY(-2px);
  outline: none;
}
.tool-card.off .tool-img {
  filter: grayscale(0.75);
  opacity: 0.7;
}
.tool-img {
  position: relative;
  height: 132px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f1f5f1, #e5ebe6);
}
.tool-icon {
  width: 46px;
  height: 46px;
  color: var(--primary-5);
  transition: transform 180ms cubic-bezier(0.4, 0, 0.2, 1);
}
.tool-card:hover .tool-icon {
  transform: scale(1.06);
}
.tool-photo {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.tool-status {
  position: absolute;
  top: 8px;
  right: 8px;
}
.tool-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
  padding: 12px 14px;
}
.tool-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.005em;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.tool-code-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.chip-code {
  flex: none;
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
.tool-model {
  overflow: hidden;
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.tool-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
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
.tool-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-2);
}
.money {
  font-weight: 600;
  color: var(--accent-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.tool-loc {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.tool-foot {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-top: auto;
  padding-top: 9px;
  border-top: 1px solid var(--border);
}
.detail-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--primary-3);
}

/* ---------- 空态 / 分页 ---------- */
.empty-card {
  grid-column: 1 / -1;
  padding: 32px 0;
  text-align: center;
  color: var(--text-3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.empty-ill {
  margin-bottom: 6px;
  font-size: 44px;
  line-height: 1;
  opacity: 0.85;
}
.empty-desc {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.45;
}
.empty-card :deep(.el-button) {
  height: 32px;
  border-radius: var(--r-2);
  border-color: var(--border-2);
  color: var(--text-2);
  font-size: 13.5px;
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
  border: 1px solid var(--border-2);
  border-radius: var(--r-2);
  background: var(--surface);
  color: var(--text-2);
  font-size: 12.5px;
}
.pager :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
.pager :deep(.el-pagination.is-background .btn-prev),
.pager :deep(.el-pagination.is-background .btn-next) {
  border: 1px solid var(--border-2);
  border-radius: var(--r-2);
  background: var(--surface);
  color: var(--text-2);
}

@media (max-width: 1199px) {
  .tool-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .tool-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
  .filter-bar .f-search,
  .filter-bar .f-item,
  .avail-check {
    width: 100%;
  }
  .filter-bar .grow {
    display: none;
  }
  .filter-bar :deep(.el-button) {
    flex: 1;
  }
}
@media (max-width: 480px) {
  .tool-grid {
    grid-template-columns: 1fr;
  }
}
</style>
