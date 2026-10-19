<template>
  <el-config-provider :locale="zhCn">
    <div class="rental-page">
      <div class="page-head">
        <div>
          <h1>我的租赁</h1>
          <p class="sub">本人全部租赁预约与记录 · 取件前 24 小时可取消预约</p>
        </div>
        <div class="ph-actions">
          <el-button class="btn-default" @click="router.push('/tools')">去找工具</el-button>
        </div>
      </div>

      <div class="summary">
        <div v-for="card in summary" :key="card.label" class="sum-card">
          <div class="sv">{{ card.value }}</div>
          <div class="sk">{{ card.label }}</div>
        </div>
      </div>

      <div class="tabs">
        <button
          v-for="tab in TABS"
          :key="tab.value"
          type="button"
          class="tab"
          :class="{ active: activeStatus === tab.value }"
          @click="changeTab(tab.value)"
        >
          {{ tab.label }}
          <span class="count">{{ countOf(tab.value) }}</span>
        </button>
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
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '还没有租赁记录，去工具广场看看' }}</p>
          <el-button v-if="failed" @click="loadAll">重新加载</el-button>
          <el-button v-else type="primary" @click="router.push('/tools')">去工具广场</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <table class="rt-table">
              <thead>
                <tr>
                  <th>预约单号</th>
                  <th>工具</th>
                  <th>租赁时段</th>
                  <th>押金</th>
                  <th>订单状态</th>
                  <th class="th-actions">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in paged" :key="row.id">
                  <td><span class="chip-code">{{ row.orderNo }}</span></td>
                  <td>
                    <div class="tool-name">{{ row.toolName || '—' }}</div>
                    <span class="tiny">{{ row.toolCode }}</span>
                  </td>
                  <td class="time-cell">
                    {{ fmtDateTime(row.startTime) }}<br />~ {{ fmtDateTime(row.endTime) }}
                  </td>
                  <td>
                    <span class="money">{{ money(row.depositAmount) }}</span><br />
                    <span class="tag" :class="depositTag(row.depositStatus)[1]">
                      {{ depositTag(row.depositStatus)[0] }}
                    </span>
                  </td>
                  <td>
                    <span class="tag" :class="statusTag(row.status)[1]">
                      {{ statusTag(row.status)[0] }}
                    </span>
                  </td>
                  <td class="td-actions">
                    <button v-if="row.status === 1" type="button" class="link-btn" @click="openPay(row)">
                      支付押金
                    </button>
                    <button v-if="row.status === 1" type="button" class="link-btn danger" @click="cancelOrder(row)">
                      取消
                    </button>
                    <button v-if="row.status === 2" type="button" class="link-btn danger" @click="cancelOrder(row)">
                      取消预约
                    </button>
                    <button v-if="row.status === 2" type="button" class="link-btn" @click="openPickup(row)">
                      取件信息
                    </button>
                    <button v-if="row.status === 3" type="button" class="link-btn" @click="goRepair(row)">
                      去报修
                    </button>
                    <button v-if="row.status === 3" type="button" class="link-btn" @click="openDetail(row)">
                      查看
                    </button>
                    <button v-if="row.status === 4" type="button" class="link-btn" @click="openDetail(row)">
                      查看验收结果
                    </button>
                    <button v-if="row.status === 5" type="button" class="link-btn" @click="openDetail(row)">
                      查看
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="m-cards">
            <div v-for="row in paged" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title">{{ row.toolName || '—' }}</span>
                <span class="tag" :class="statusTag(row.status)[1]">{{ statusTag(row.status)[0] }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">预约单号</span>
                <span class="mc-v"><span class="chip-code">{{ row.orderNo }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">租赁时段</span>
                <span class="mc-v">
                  {{ fmtDateTime(row.startTime) }} ~ {{ fmtDateTime(row.endTime) }}
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">押金</span>
                <span class="mc-v">
                  <span class="money">{{ money(row.depositAmount) }}</span>
                  <span class="tag" :class="depositTag(row.depositStatus)[1]">
                    {{ depositTag(row.depositStatus)[0] }}
                  </span>
                </span>
              </div>
              <div class="mc-actions">
                <button v-if="row.status === 1" type="button" class="link-btn" @click="openPay(row)">支付押金</button>
                <button v-if="row.status === 1" type="button" class="link-btn danger" @click="cancelOrder(row)">
                  取消
                </button>
                <button v-if="row.status === 2" type="button" class="link-btn danger" @click="cancelOrder(row)">
                  取消预约
                </button>
                <button v-if="row.status === 2" type="button" class="link-btn" @click="openPickup(row)">
                  取件信息
                </button>
                <button v-if="row.status === 3" type="button" class="link-btn" @click="goRepair(row)">去报修</button>
                <button v-if="row.status === 3" type="button" class="link-btn" @click="openDetail(row)">查看</button>
                <button v-if="row.status === 4" type="button" class="link-btn" @click="openDetail(row)">
                  查看验收结果
                </button>
                <button v-if="row.status === 5" type="button" class="link-btn" @click="openDetail(row)">查看</button>
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

    <!-- 支付沙箱 -->
    <el-dialog
      v-model="payVisible"
      title="押金缴纳 · 支付沙箱"
      width="420px"
      :close-on-click-modal="false"
    >
      <div class="sandbox-tip">支付沙箱：本环境为演示模式，不会产生真实扣款</div>
      <p class="pay-meta">{{ payTarget?.toolName }} · {{ fmtDate(payTarget?.startTime) }} 取件</p>
      <div class="pay-amount">{{ money(payTarget?.depositAmount || 0) }}</div>
      <p class="pay-meta">
        预约单号：<span class="chip-code">{{ payTarget?.orderNo || '—' }}</span>
      </p>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="paying" @click="confirmPay">确认支付</el-button>
      </template>
    </el-dialog>

    <!-- 取件信息 -->
    <el-dialog v-model="pickupVisible" title="取件信息" width="440px">
      <div class="kv-list">
        <div class="k">取件人</div>
        <div class="v">{{ pickTarget?.pickupName || '—' }}</div>
        <div class="k">取件时间</div>
        <div class="v">{{ fmtDateTime(pickTarget?.startTime) }}</div>
        <div class="k">取件地点</div>
        <div class="v">社区服务站</div>
        <div class="k">所需凭证</div>
        <div class="v">身份证 / 预约单号</div>
      </div>
      <div class="callout">提示：可由他人代取，取件时填写代取人姓名即可。</div>
      <template #footer>
        <el-button type="primary" @click="pickupVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="租赁单详情" size="560px">
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="kv-list">
            <div class="k">预约单号</div>
            <div class="v"><span class="chip-code">{{ detail.orderNo }}</span></div>
            <div class="k">工具</div>
            <div class="v">{{ detail.toolName }}（{{ detail.toolCode }}）</div>
            <div class="k">租赁时段</div>
            <div class="v">{{ fmtDateTime(detail.startTime) }} ~ {{ fmtDateTime(detail.endTime) }}</div>
            <div class="k">取件人</div>
            <div class="v">{{ detail.pickupName || '—' }}</div>
            <div class="k">押金</div>
            <div class="v">
              <span class="money">{{ money(detail.depositAmount) }}</span>
              <span class="tag" :class="depositTag(detail.depositStatus)[1]">
                {{ depositTag(detail.depositStatus)[0] }}
              </span>
            </div>
            <div class="k">订单状态</div>
            <div class="v">
              <span class="tag" :class="statusTag(detail.status)[1]">{{ statusTag(detail.status)[0] }}</span>
            </div>
            <template v-if="detail.acceptResult">
              <div class="k">归还验收</div>
              <div class="v">
                <span class="tag" :class="acceptTag(detail.acceptResult)[1]">
                  {{ acceptTag(detail.acceptResult)[0] }}
                </span>
              </div>
            </template>
            <template v-if="detail.overdueFee != null">
              <div class="k">逾期费用</div>
              <div class="v">
                <span class="money" :class="{ neg: detail.overdueFee > 0 }">
                  {{ money(detail.overdueFee) }}
                </span>
              </div>
            </template>
            <template v-if="detail.depositRefundAmount != null">
              <div class="k">实退押金</div>
              <div class="v">
                <span class="money pos">{{ money(detail.depositRefundAmount) }}</span>
              </div>
            </template>
            <div v-if="detail.createTime" class="k">创建时间</div>
            <div v-if="detail.createTime" class="v">{{ fmtDateTime(detail.createTime) }}</div>
            <div v-if="detail.returnTime" class="k">归还时间</div>
            <div v-if="detail.returnTime" class="v">{{ fmtDateTime(detail.returnTime) }}</div>
            <div v-if="detail.cancelTime" class="k">取消时间</div>
            <div v-if="detail.cancelTime" class="v">{{ fmtDateTime(detail.cancelTime) }}</div>
          </div>
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
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { cancelRental, getRental, pageRentals, payRental } from '../../api/rental'
import type { RentalOrderVO } from '../../api/types'

const RENTAL_STATUS: Record<number, [string, string]> = {
  1: ['待支付', 't-accent'],
  2: ['已预约', 't-info'],
  3: ['已取件', 't-primary'],
  4: ['已归还', 't-success'],
  5: ['已取消', 't-gray'],
}
const DEPOSIT_STATUS: Record<number, [string, string]> = {
  1: ['未缴', 't-warning'],
  2: ['已缴', 't-success'],
  3: ['已退还', 't-gray'],
}
const ACCEPT_RESULT: Record<number, [string, string]> = {
  1: ['完好', 't-success'],
  2: ['轻微损坏', 't-warning'],
  3: ['严重损坏', 't-danger'],
}

const statusTag = (v: number) => RENTAL_STATUS[v] ?? ['未知', 't-gray']
const depositTag = (v: number) => DEPOSIT_STATUS[v] ?? ['未知', 't-gray']
const acceptTag = (v: number) => ACCEPT_RESULT[v] ?? ['未知', 't-gray']

const TABS = [
  { label: '全部', value: 0 },
  { label: '待支付', value: 1 },
  { label: '已预约', value: 2 },
  { label: '已取件', value: 3 },
  { label: '已归还', value: 4 },
  { label: '已取消', value: 5 },
]

function money(n?: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

function fmtDate(value?: string) {
  return fmtDateTime(value).slice(0, 10)
}

const route = useRoute()
const router = useRouter()

const all = ref<RentalOrderVO[]>([])
const loading = ref(false)
const failed = ref(false)
const activeStatus = ref(0)
const page = ref(1)
const size = ref(10)

const payVisible = ref(false)
const paying = ref(false)
const payTarget = ref<RentalOrderVO | null>(null)
const pickupVisible = ref(false)
const pickTarget = ref<RentalOrderVO | null>(null)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RentalOrderVO | null>(null)

const filtered = computed(() =>
  activeStatus.value ? all.value.filter((r) => r.status === activeStatus.value) : all.value,
)
const paged = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))
const summary = computed(() => [
  { label: '待支付', value: countOf(1) },
  { label: '已预约', value: countOf(2) },
  { label: '已取件', value: countOf(3) },
  { label: '已完成', value: countOf(4) },
])

function countOf(status: number) {
  return status ? all.value.filter((r) => r.status === status).length : all.value.length
}

/**
 * ponytail: 租赁分页接口没有 status 参数，状态 Tab/统计需要全量数据；
 * 先一次性拉取 200 条本地分页，居民租赁量超限再改为接口加 status 筛选。
 */
async function loadAll() {
  loading.value = true
  failed.value = false
  try {
    const result = await pageRentals({ page: 1, size: 200 })
    all.value = [...result.records].sort((a, b) =>
      (b.createTime || '').localeCompare(a.createTime || ''),
    )
    const maxPage = Math.max(1, Math.ceil(filtered.value.length / size.value))
    if (page.value > maxPage) page.value = maxPage
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function syncUrl() {
  router.replace({
    query: {
      ...(activeStatus.value ? { status: String(activeStatus.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function changeTab(status: number) {
  activeStatus.value = status
  page.value = 1
  syncUrl()
}

function onSizeChange() {
  page.value = 1
  syncUrl()
}

function openPay(row: RentalOrderVO) {
  payTarget.value = row
  payVisible.value = true
}

async function confirmPay() {
  if (!payTarget.value) return
  paying.value = true
  try {
    await payRental(payTarget.value.id)
    ElMessage.success('押金缴纳成功，预约已确认')
    payVisible.value = false
    await loadAll()
  } finally {
    paying.value = false
  }
}

async function cancelOrder(row: RentalOrderVO) {
  try {
    await ElMessageBox.confirm(
      `确定取消预约单 ${row.orderNo} 吗？取消后押金将原路退还。`,
      '取消预约',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  try {
    await cancelRental(row.id)
    ElMessage.success('预约已取消，押金退还中')
    await loadAll()
  } catch {
    // 拦截器已统一提示（如距取件不足 24 小时）
  }
}

function openPickup(row: RentalOrderVO) {
  pickTarget.value = row
  pickupVisible.value = true
}

function goRepair(row: RentalOrderVO) {
  router.push({
    path: '/repairs/new',
    query: { rentalOrderId: String(row.id), toolId: String(row.toolId) },
  })
}

async function openDetail(row: RentalOrderVO) {
  detail.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getRental(row.id)
  } catch {
    // 保留列表数据作为兜底
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  const status = Number(route.query.status)
  activeStatus.value = [1, 2, 3, 4, 5].includes(status) ? status : 0
  const p = Number(route.query.page)
  page.value = p > 0 ? p : 1
  loadAll()
})
</script>

<style scoped>
.rental-page {
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

/* ---------- 页头 ---------- */
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
.empty-box :deep(.el-button) {
  height: 32px;
  border-radius: var(--r-2);
  border-color: var(--border-2);
  color: var(--text-2);
  font-size: 13.5px;
}
.ph-actions :deep(.el-button--primary),
.empty-box :deep(.el-button--primary),
.pay-meta + .el-dialog :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
}

/* ---------- 汇总卡 ---------- */
.summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.sum-card {
  padding: 13px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
  transition: box-shadow 180ms, border-color 180ms;
}
.sum-card:hover {
  border-color: var(--primary-7);
  box-shadow: var(--shadow-2);
}
.sum-card .sv {
  font-size: 20px;
  font-weight: 600;
  color: var(--primary);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.011em;
  line-height: 1.3;
}
.sum-card .sk {
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
.rt-table {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.rt-table th {
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
.rt-table td {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
  line-height: 1.45;
}
.rt-table tbody tr:hover td {
  background: var(--surface-2);
}
.rt-table tbody tr:last-child td {
  border-bottom: none;
}
.th-actions {
  text-align: right;
}
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

/* ---------- 按钮 / 标签 / 文本 ---------- */
.link-btn {
  padding: 0 6px;
  height: 26px;
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
.money {
  font-weight: 600;
  color: var(--accent-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.money.pos {
  color: var(--success);
}
.money.neg {
  color: var(--danger);
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

/* ---------- 弹窗 / 抽屉 ---------- */
.sandbox-tip {
  margin-bottom: 14px;
  padding: 8px 12px;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--info);
  background: var(--info-9);
  border: 1px solid #c7d8ef;
  border-radius: var(--r-2);
}
.pay-meta {
  margin: 0 0 6px;
  font-size: 12.5px;
  color: var(--text-2);
  text-align: center;
}
.pay-amount {
  margin: 10px 0 6px;
  font-size: 28px;
  font-weight: 700;
  color: var(--accent-2);
  text-align: center;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}
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
.callout {
  margin-top: 16px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--info);
  background: var(--info-9);
  border-left: 4px solid var(--info);
  border-radius: 0 var(--r-2) var(--r-2) 0;
}
.drawer-body {
  min-height: 200px;
}

@media (max-width: 768px) {
  .summary {
    grid-template-columns: repeat(2, 1fr);
  }
  .table-wrap {
    display: none;
  }
  .m-cards {
    display: block;
  }
  .page-head h1 {
    font-size: 18px;
  }
  .pager :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
