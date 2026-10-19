<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-audit">
      <div class="page-head">
        <div>
          <h1>工具审核</h1>
          <p class="sub">工作人员录入工具后，由管理员审核通过方可上架预约</p>
        </div>
      </div>

      <div class="tabs">
        <button
          v-for="tab in TABS"
          :key="tab.value"
          type="button"
          class="tab"
          :class="{ active: activeTab === tab.value }"
          @click="changeTab(tab.value)"
        >
          {{ tab.label }}
          <span class="count">{{ countOf(tab.value) }}</span>
        </button>
      </div>

      <div v-loading="loading" class="audit-grid">
        <div v-for="tool in filtered" :key="tool.id" class="audit-card">
          <div class="ac-head">
            <div class="ac-main">
              <div class="ac-name" :title="tool.name">{{ tool.name }}</div>
              <div class="ac-meta">
                <span class="chip-code">{{ tool.toolCode }}</span>
                <span class="tiny">{{ tool.modelSpec }}</span>
              </div>
            </div>
            <span class="tag" :class="categoryTag(tool.category)[1]">
              {{ categoryTag(tool.category)[0] }}
            </span>
          </div>

          <div class="muted">
            押金 <span class="money">{{ money(tool.deposit) }}</span> ·
            {{ tool.storageLocation || '—' }} · 录入 {{ fmtDateTime(tool.createTime) }}
          </div>

          <div v-if="tool.auditStatus === 3 && tool.auditOpinion" class="opinion">
            审核意见：{{ tool.auditOpinion }}
          </div>

          <div v-if="activeTab === 1" class="ac-ops">
            <el-button
              type="primary"
              size="small"
              :loading="actingId === tool.id"
              @click="pass(tool)"
            >
              审 核
            </el-button>
            <el-button
              type="danger"
              plain
              size="small"
              :disabled="actingId === tool.id"
              @click="reject(tool)"
            >
              需修改
            </el-button>
          </div>
        </div>

        <div v-if="!loading && !filtered.length" class="empty-card">
          <p>{{ failed ? '加载失败，请稍后重试' : '该分类下暂无工具' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
        </div>
      </div>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { auditTool, pageTools } from '../../api/tool'
import type { Tool } from '../../api/types'

const TABS = [
  { value: 1, label: '待审核' },
  { value: 2, label: '已通过' },
  { value: 3, label: '需修改' },
]
const CATEGORY: Record<number, [string, string]> = {
  1: ['维修', 't-primary'],
  2: ['园艺', 't-success'],
  3: ['清洁', 't-info'],
  4: ['其他', 't-gray'],
}
const categoryTag = (v: number) => CATEGORY[v] ?? ['未知', 't-gray']

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

const all = ref<Tool[]>([])
const loading = ref(false)
const failed = ref(false)
const activeTab = ref(1)
const actingId = ref(0)

const filtered = computed(() => all.value.filter((t) => t.auditStatus === activeTab.value))

function countOf(status: number) {
  return all.value.filter((t) => t.auditStatus === status).length
}

/**
 * ponytail: ToolQueryDTO 没有 auditStatus 参数，审核列表需全量数据后本地分组；
 * 单次拉取 200 条，工具量超限再让后端补 auditStatus 查询参数。
 */
async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await pageTools({ page: 1, size: 200 })
    all.value = result.records
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function changeTab(tab: number) {
  activeTab.value = tab
  router.replace({ query: { tab: String(tab) } })
}

async function pass(tool: Tool) {
  try {
    await ElMessageBox.confirm(
      `确认通过「${tool.name}」的录入审核？通过后工具将上架开放预约。`,
      '审核通过',
      { type: 'warning', confirmButtonText: '通过', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actingId.value = tool.id
  try {
    await auditTool(tool.id, { auditStatus: 2 })
    ElMessage.success('已通过审核')
    await load()
    activeTab.value = 2
    changeTab(2)
  } catch {
    // 拦截器已统一提示
  } finally {
    actingId.value = 0
  }
}

async function reject(tool: Tool) {
  let opinion = ''
  try {
    const result = await ElMessageBox.prompt('请说明需要修改的内容，工作人员将收到反馈并重新提交。', '审核意见 · 需修改', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '如：押金金额与社区定价不符，请调整后重新提交（≤200 字）',
      inputValidator: (value: string) =>
        value && value.trim() ? true : '请填写审核修改意见',
      inputPattern: /^[\s\S]{1,200}$/,
      inputErrorMessage: '审核意见需为 1-200 字',
      type: 'warning',
    })
    opinion = result.value.trim()
  } catch {
    return
  }
  actingId.value = tool.id
  try {
    await auditTool(tool.id, { auditStatus: 3, auditOpinion: opinion })
    ElMessage.success('已退回修改')
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    actingId.value = 0
  }
}

onMounted(() => {
  const tab = Number(route.query.tab)
  activeTab.value = [1, 2, 3].includes(tab) ? tab : 1
  load()
})
</script>

<style scoped>
.admin-audit {
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

/* ---------- Tabs ---------- */
.tabs {
  display: flex;
  gap: 20px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  scrollbar-width: none;
}
.tabs::-webkit-scrollbar {
  display: none;
}
.tab {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 2px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--text-2);
  white-space: nowrap;
  background: none;
  border: none;
  cursor: pointer;
}
.tab::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--primary);
  border-radius: 2px 2px 0 0;
  transform: scaleX(0);
  transition: transform 180ms cubic-bezier(0.4, 0, 0.2, 1);
}
.tab:hover {
  color: var(--primary);
}
.tab.active {
  color: var(--primary);
  font-weight: 600;
}
.tab.active::after {
  transform: scaleX(1);
}
.tab .count {
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 500;
  line-height: 15px;
  color: var(--text-2);
  background: var(--surface-3);
  border-radius: 99px;
  font-variant-numeric: tabular-nums;
}
.tab.active .count {
  color: #fff;
  background: var(--primary);
}

/* ---------- 审核卡片 ---------- */
.audit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  min-height: 140px;
}
.audit-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
  transition: box-shadow 180ms, border-color 180ms;
}
.audit-card:hover {
  border-color: var(--primary-7);
  box-shadow: var(--shadow-2);
}
.ac-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}
.ac-main {
  min-width: 0;
}
.ac-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.005em;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ac-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 4px;
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
.muted {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
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
.opinion {
  padding: 8px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--danger);
  background: var(--danger-9);
  border: 1px solid #efc7c1;
  border-radius: var(--r-2);
}
.ac-ops {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.ac-ops :deep(.el-button) {
  height: 28px;
  padding: 0 10px;
  border-radius: var(--r-2);
  font-size: 12.5px;
  font-weight: 500;
}
.ac-ops :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}
.ac-ops :deep(.el-button--danger.is-plain) {
  --el-button-text-color: var(--danger);
  --el-button-border-color: #ebc3be;
  --el-button-hover-bg-color: var(--danger-9);
  --el-button-hover-text-color: #a93226;
  --el-button-hover-border-color: #e3b4ae;
}
.empty-card {
  grid-column: 1 / -1;
  padding: 40px 20px;
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.empty-card p {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-2);
}
.empty-card :deep(.el-button) {
  height: 32px;
  border-radius: var(--r-2);
  border-color: var(--border-2);
  color: var(--text-2);
  font-size: 13.5px;
}

/* ---------- 标签 ---------- */
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

@media (max-width: 1199px) {
  .audit-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
}
</style>
