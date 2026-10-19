<template>
  <el-config-provider :locale="zhCn">
    <div class="repair-page">
      <div class="page-head">
        <div>
          <h1>损坏报修</h1>
          <p class="sub">租赁期间发现工具损坏，可在「我的租赁」中发起报修，此处跟踪维修进度</p>
        </div>
        <div class="ph-actions">
          <el-button type="primary" @click="router.push('/repairs/new')">提交报修</el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          class="f-search"
          placeholder="搜索工具编号"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-select
          v-model="repairStatus"
          class="f-item"
          placeholder="全部进度"
          clearable
          @change="applyFilter"
        >
          <el-option label="待配件" :value="1" />
          <el-option label="维修中" :value="2" />
          <el-option label="维修完成" :value="3" />
          <el-option label="维修失败" :value="4" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !records.length" class="empty-box">
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
            <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
          </svg>
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无报修记录' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
          <el-button v-else type="primary" @click="router.push('/repairs/new')">提交报修</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <table class="rp-table">
              <thead>
                <tr>
                  <th>报修单号</th>
                  <th>工具</th>
                  <th>损坏部位</th>
                  <th>核验结果</th>
                  <th>维修进度</th>
                  <th>提交时间</th>
                  <th class="th-actions">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in records" :key="row.id">
                  <td><span class="chip-code">{{ row.repairNo }}</span></td>
                  <td>
                    <div class="tool-name">{{ row.toolName || '—' }}</div>
                    <span class="tiny">{{ row.toolCode }}</span>
                  </td>
                  <td>{{ row.damagePart || '—' }}</td>
                  <td>
                    <span class="tag" :class="verifyTag(row.verifyResult)[1]">
                      {{ verifyTag(row.verifyResult)[0] }}
                    </span>
                  </td>
                  <td>
                    <span class="tag" :class="statusTag(row.repairStatus)[1]">
                      {{ statusTag(row.repairStatus)[0] }}
                    </span>
                  </td>
                  <td class="time-cell">{{ fmtDateTime(row.createTime) }}</td>
                  <td class="td-actions">
                    <button type="button" class="link-btn" @click="openDetail(row)">查看进度</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="m-cards">
            <div v-for="row in records" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title">{{ row.toolName || '—' }}</span>
                <span class="tag" :class="statusTag(row.repairStatus)[1]">
                  {{ statusTag(row.repairStatus)[0] }}
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">报修单号</span>
                <span class="mc-v"><span class="chip-code">{{ row.repairNo }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">损坏部位</span>
                <span class="mc-v">{{ row.damagePart || '—' }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">核验结果</span>
                <span class="mc-v">
                  <span class="tag" :class="verifyTag(row.verifyResult)[1]">
                    {{ verifyTag(row.verifyResult)[0] }}
                  </span>
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">提交时间</span>
                <span class="mc-v">{{ fmtDateTime(row.createTime) }}</span>
              </div>
              <div class="mc-actions">
                <button type="button" class="link-btn" @click="openDetail(row)">查看进度</button>
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

    <el-drawer v-model="detailVisible" title="报修详情" size="560px">
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="kv-list">
            <div class="k">报修单号</div>
            <div class="v"><span class="chip-code">{{ detail.repairNo }}</span></div>
            <div class="k">关联租赁单</div>
            <div class="v"><span class="chip-code">{{ detail.orderNo || '—' }}</span></div>
            <div class="k">工具</div>
            <div class="v">{{ detail.toolName }}（{{ detail.toolCode }}）</div>
            <div class="k">损坏部位</div>
            <div class="v">{{ detail.damagePart || '—' }}</div>
            <div class="k">损坏描述</div>
            <div class="v">{{ detail.damageDesc || '—' }}</div>
            <template v-if="detailImages.length">
              <div class="k">材料图片</div>
              <div class="v">
                <div class="img-list">
                  <a
                    v-for="(img, index) in detailImages"
                    :key="img + index"
                    class="thumb"
                    :href="img"
                    target="_blank"
                    rel="noopener"
                  >
                    <img :src="img" :alt="`报修材料 ${index + 1}`" @error="hideImage" />
                  </a>
                </div>
              </div>
            </template>
            <div class="k">核验结果</div>
            <div class="v">
              <span class="tag" :class="verifyTag(detail.verifyResult)[1]">
                {{ verifyTag(detail.verifyResult)[0] }}
              </span>
              <span v-if="detail.verifyByName" class="tiny"> · {{ detail.verifyByName }}</span>
            </div>
            <div class="k">维修进度</div>
            <div class="v">
              <span class="tag" :class="statusTag(detail.repairStatus)[1]">
                {{ statusTag(detail.repairStatus)[0] }}
              </span>
            </div>
            <div class="k">维修费用</div>
            <div class="v"><span class="money">{{ money(detail.repairFee) }}</span></div>
            <template v-if="detail.acceptResult">
              <div class="k">验收结果</div>
              <div class="v">
                <span class="tag" :class="acceptTag(detail.acceptResult)[1]">
                  {{ acceptTag(detail.acceptResult)[0] }}
                </span>
                <span v-if="detail.acceptByName" class="tiny"> · {{ detail.acceptByName }}</span>
              </div>
            </template>
            <template v-if="detail.remark">
              <div class="k">备注</div>
              <div class="v">{{ detail.remark }}</div>
            </template>
            <div class="k">提交时间</div>
            <div class="v">{{ fmtDateTime(detail.createTime) }}</div>
          </div>

          <h4 class="timeline-title">维修进度</h4>
          <ul v-if="progressList.length" class="timeline">
            <li
              v-for="(item, index) in progressList"
              :key="item.id || index"
              :class="{ latest: index === progressList.length - 1 }"
            >
              <span class="node" />
              <div class="c">
                <span class="tag" :class="statusTag(item.status)[1]">{{ statusTag(item.status)[0] }}</span>
                <span class="progress-desc">{{ item.description }}</span>
              </div>
              <div class="t">{{ fmtDateTime(item.createTime) }}</div>
            </li>
          </ul>
          <div v-else class="timeline-empty">暂无进度记录</div>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关 闭</el-button>
      </template>
    </el-drawer>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { getRepair, pageRepairs } from '../../api/repair'
import type { RepairOrderVO, RepairQueryDTO } from '../../api/types'

const REPAIR_STATUS: Record<number, [string, string]> = {
  1: ['待配件', 't-warning'],
  2: ['维修中', 't-info'],
  3: ['维修完成', 't-success'],
  4: ['维修失败', 't-danger'],
}
const VERIFY_RESULT: Record<number, [string, string]> = {
  1: ['确认损坏', 't-danger'],
  2: ['疑似损坏', 't-warning'],
  3: ['误报', 't-gray'],
}
const ACCEPT_RESULT: Record<number, [string, string]> = {
  1: ['合格', 't-success'],
  2: ['不合格', 't-danger'],
  3: ['部分合格', 't-warning'],
}

const statusTag = (v: number) => REPAIR_STATUS[v] ?? ['未知', 't-gray']
const verifyTag = (v: number) => VERIFY_RESULT[v] ?? ['待核验', 't-gray']
const acceptTag = (v: number) => ACCEPT_RESULT[v] ?? ['未知', 't-gray']

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

const keyword = ref('')
const repairStatus = ref<number | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<RepairOrderVO[]>([])
const loading = ref(false)
const failed = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RepairOrderVO | null>(null)

const detailImages = computed(() => detail.value?.images ?? [])
const progressList = computed(() =>
  [...(detail.value?.progressList ?? [])].sort((a, b) =>
    (a.createTime || '').localeCompare(b.createTime || ''),
  ),
)

async function load() {
  loading.value = true
  failed.value = false
  const params: RepairQueryDTO = { page: page.value, size: size.value }
  if (keyword.value.trim()) params.toolCode = keyword.value.trim()
  if (repairStatus.value !== '') params.repairStatus = repairStatus.value

  syncUrl()
  try {
    const result = await pageRepairs(params)
    records.value = result.records
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
  keyword.value = ''
  repairStatus.value = ''
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
      ...(keyword.value.trim() ? { toolCode: keyword.value.trim() } : {}),
      ...(repairStatus.value !== '' ? { status: String(repairStatus.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

async function openDetail(row: RepairOrderVO) {
  detail.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getRepair(row.id)
  } catch {
    // 保留列表数据作为兜底
  } finally {
    detailLoading.value = false
  }
}

function hideImage(e: Event) {
  ;(e.target as HTMLImageElement).style.display = 'none'
}

onMounted(() => {
  keyword.value = typeof route.query.toolCode === 'string' ? route.query.toolCode : ''
  const status = Number(route.query.status)
  repairStatus.value = [1, 2, 3, 4].includes(status) ? status : ''
  const p = Number(route.query.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.repair-page {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
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
  --r-4: 12px;
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
.ph-actions :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.ph-actions :deep(.el-button--primary),
.empty-box :deep(.el-button--primary),
.filter-bar :deep(.el-button--primary) {
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
.filter-bar :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.filter-bar :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.filter-bar :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
}

/* ---------- 列表 ---------- */
.list-wrap {
  min-height: 180px;
}
.table-wrap {
  overflow-x: auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.rp-table {
  width: 100%;
  min-width: 820px;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.rp-table th {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  text-align: left;
  color: var(--primary-3);
  background: var(--primary-tint);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.rp-table td {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
  line-height: 1.45;
}
.rp-table tbody tr:hover td {
  background: var(--surface-2);
}
.rp-table tbody tr:last-child td {
  border-bottom: none;
}
.th-actions,
.td-actions {
  text-align: right;
  white-space: nowrap;
}
.tool-name {
  font-weight: 600;
}
.time-cell {
  font-size: 12.5px;
  color: var(--text-2);
  white-space: nowrap;
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
  font-variant-numeric: tabular-nums;
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
  padding: 0 6px;
  font-family: inherit;
  font-size: 13px;
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
  letter-spacing: 0.01em;
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
.empty-box :deep(.el-button) {
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

/* ---------- 抽屉 ---------- */
.kv-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  font-size: 13px;
}
.kv-list .k {
  color: var(--text-2);
}
.kv-list .v {
  margin: -6px 0 0;
  color: var(--text);
  word-break: break-all;
}
.img-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.thumb {
  display: inline-flex;
  width: 56px;
  height: 56px;
  overflow: hidden;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-2);
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.timeline-title {
  margin: 18px 0 10px;
  font-size: 15px;
  font-weight: 600;
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
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  line-height: 1.6;
}
.timeline .t {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.progress-desc {
  color: var(--text);
}
.timeline-empty {
  padding: 16px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
.drawer-body {
  min-height: 200px;
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
