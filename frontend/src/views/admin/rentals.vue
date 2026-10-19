<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-rentals">
      <div class="page-head">
        <div>
          <h1>租赁管理</h1>
          <p class="sub">全量租赁记录 · 取件登记 / 归还验收 / 多条件筛选导出</p>
        </div>
        <div class="ph-actions">
          <el-button :loading="exporting" @click="exportExcel">⬇ 导出 Excel</el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="filters.userName"
          class="f-search"
          placeholder="租赁人姓名"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-input
          v-model="filters.toolCode"
          class="f-search"
          placeholder="工具编号，如 SQ-G-WX-0001"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-select
          v-model="filters.acceptResult"
          class="f-item"
          placeholder="验收结果"
          clearable
          @change="applyFilter"
        >
          <el-option label="完好" :value="1" />
          <el-option label="轻微损坏" :value="2" />
          <el-option label="严重损坏" :value="3" />
        </el-select>
        <el-select
          v-model="filters.overdue"
          class="f-item"
          placeholder="逾期状态"
          clearable
          @change="applyFilter"
        >
          <el-option label="已逾期" value="1" />
          <el-option label="未逾期" value="0" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !records.length" class="empty-box">
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无租赁记录' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <el-table :data="records" row-key="id" class="rental-table">
              <el-table-column label="预约单号" width="136">
                <template #default="{ row }">
                  <span class="chip-code">{{ row.orderNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="工具" min-width="150">
                <template #default="{ row }">
                  <div class="tool-name" :title="row.toolName">{{ row.toolName }}</div>
                  <div class="tiny">{{ row.toolCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="租赁人" width="120">
                <template #default="{ row }">
                  <div>{{ row.userName }}</div>
                  <div class="tiny phone">{{ row.userPhone }}</div>
                </template>
              </el-table-column>
              <el-table-column label="租赁时段" width="150">
                <template #default="{ row }">
                  <div class="time-cell">{{ fmtDateTime(row.startTime) }}</div>
                  <div class="time-cell">~ {{ fmtDateTime(row.endTime) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="押金" width="96">
                <template #default="{ row }">
                  <div class="money">{{ money(row.depositAmount) }}</div>
                  <span class="tag" :class="depositTag(row.depositStatus)[1]">
                    {{ depositTag(row.depositStatus)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="订单状态" width="92">
                <template #default="{ row }">
                  <span class="tag" :class="statusTag(row.status)[1]">{{ statusTag(row.status)[0] }}</span>
                </template>
              </el-table-column>
              <el-table-column label="验收" width="128">
                <template #default="{ row }">
                  <template v-if="row.acceptResult">
                    <span class="tag" :class="acceptTag(row.acceptResult)[1]">
                      {{ acceptTag(row.acceptResult)[0] }}
                    </span>
                    <div class="fee-line" :class="{ neg: row.overdueFee > 0 }">
                      {{ row.overdueFee > 0 ? `逾期 ${money(row.overdueFee)}` : '无逾期' }}
                    </div>
                  </template>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="160" align="right">
                <template #default="{ row }">
                  <button type="button" class="link-btn" @click="openDetail(row)">详情</button>
                  <button
                    v-if="row.status === 2"
                    type="button"
                    class="link-btn"
                    @click="openPickup(row)"
                  >
                    取件登记
                  </button>
                  <button
                    v-if="row.status === 3"
                    type="button"
                    class="link-btn"
                    @click="openReturn(row)"
                  >
                    归还验收
                  </button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="m-cards">
            <div v-for="row in records" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title" :title="row.toolName">{{ row.toolName }}</span>
                <span class="tag" :class="statusTag(row.status)[1]">{{ statusTag(row.status)[0] }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">预约单号</span>
                <span class="mc-v"><span class="chip-code">{{ row.orderNo }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">租赁人</span>
                <span class="mc-v">{{ row.userName }} · {{ row.userPhone }}</span>
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
              <div v-if="row.acceptResult" class="mc-row">
                <span class="mc-k">验收</span>
                <span class="mc-v">
                  <span class="tag" :class="acceptTag(row.acceptResult)[1]">
                    {{ acceptTag(row.acceptResult)[0] }}
                  </span>
                  <span class="fee-line" :class="{ neg: row.overdueFee > 0 }">
                    {{ row.overdueFee > 0 ? `逾期 ${money(row.overdueFee)}` : '无逾期' }}
                  </span>
                </span>
              </div>
              <div class="mc-actions">
                <button type="button" class="link-btn" @click="openDetail(row)">详情</button>
                <button
                  v-if="row.status === 2"
                  type="button"
                  class="link-btn"
                  @click="openPickup(row)"
                >
                  取件登记
                </button>
                <button
                  v-if="row.status === 3"
                  type="button"
                  class="link-btn"
                  @click="openReturn(row)"
                >
                  归还验收
                </button>
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

    <!-- 取件登记 -->
    <el-dialog v-model="pickupVisible" title="取件登记" width="440px" :close-on-click-modal="false">
      <p v-if="pickupTarget" class="dialog-sub">
        工具：<b>{{ pickupTarget.toolName }}</b>（{{ pickupTarget.toolCode }}）<br />
        预约单号：<span class="chip-code">{{ pickupTarget.orderNo }}</span>
      </p>
      <el-form label-position="top">
        <el-form-item label="取件人" required>
          <el-input
            v-model="pickupName"
            maxlength="20"
            placeholder="默认为预约人，可修改为代取人"
          />
        </el-form-item>
      </el-form>
      <p class="form-tip">取件后租赁单状态将变为「已取件」，工具状态同步为「已借出」。</p>
      <template #footer>
        <el-button @click="pickupVisible = false">取消</el-button>
        <el-button type="primary" :loading="pickupSaving" @click="confirmPickup">确认取件</el-button>
      </template>
    </el-dialog>

    <!-- 归还验收 -->
    <el-dialog v-model="returnVisible" title="归还验收" width="520px" :close-on-click-modal="false">
      <p v-if="returnTarget" class="dialog-sub">
        工具：<b>{{ returnTarget.toolName }}</b>（{{ returnTarget.toolCode }}）<br />
        预约单号：<span class="chip-code">{{ returnTarget.orderNo }}</span> · 押金
        {{ money(returnTarget.depositAmount) }}
      </p>
      <el-form label-position="top">
        <el-form-item label="验收结果" required>
          <el-radio-group v-model="returnForm.acceptResult">
            <el-radio :value="1">完好</el-radio>
            <el-radio :value="2">轻微损坏（无需赔偿）</el-radio>
            <el-radio :value="3">严重损坏（需赔偿）</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="money-grid">
          <el-form-item label="逾期费用（元）">
            <el-input-number
              v-model="returnForm.overdueFee"
              class="full"
              :min="0"
              :max="returnTarget?.depositAmount ?? 0"
              :precision="2"
              controls-position="right"
            />
          </el-form-item>
          <el-form-item label="实退押金（元）">
            <el-input-number
              v-model="returnForm.depositRefundAmount"
              class="full"
              :min="0"
              :max="returnTarget?.depositAmount ?? 0"
              :precision="2"
              controls-position="right"
            />
          </el-form-item>
        </div>
      </el-form>
      <div class="calc-line">
        押金 {{ money(returnTarget?.depositAmount || 0) }} − 逾期费用
        {{ money(returnForm.overdueFee) }} = 应退上限
        <b>{{ money(Math.max((returnTarget?.depositAmount || 0) - returnForm.overdueFee, 0)) }}</b>
        （逾期费用封顶押金）
      </div>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSaving" @click="confirmReturn">确认验收</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="租赁单详情" size="480px">
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="kv-list">
            <div class="k">预约单号</div>
            <div class="v"><span class="chip-code">{{ detail.orderNo }}</span></div>
            <div class="k">工具</div>
            <div class="v">{{ detail.toolName }}（{{ detail.toolCode }}）</div>
            <div class="k">租赁人</div>
            <div class="v">{{ detail.userName }} · {{ detail.userPhone }}</div>
            <div class="k">取件人</div>
            <div class="v">{{ detail.pickupName || '—' }}</div>
            <div class="k">租赁时段</div>
            <div class="v">{{ fmtDateTime(detail.startTime) }} ~ {{ fmtDateTime(detail.endTime) }}</div>
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
              <div class="k">验收结果</div>
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
              <div class="v"><span class="money pos">{{ money(detail.depositRefundAmount) }}</span></div>
            </template>
            <div class="k">创建时间</div>
            <div class="v">{{ fmtDateTime(detail.createTime) }}</div>
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
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  exportRentals,
  getRental,
  pageRentals,
  pickupRental,
  returnRental,
} from '../../api/rental'
import type { RentalOrderVO, RentalQueryDTO } from '../../api/types'

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

function money(n?: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

function todayCompact() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
}

const route = useRoute()
const router = useRouter()

const filters = reactive({
  userName: '',
  toolCode: '',
  acceptResult: '' as number | '',
  overdue: '' as '1' | '0' | '',
})
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<RentalOrderVO[]>([])
const loading = ref(false)
const failed = ref(false)
const exporting = ref(false)

const pickupVisible = ref(false)
const pickupSaving = ref(false)
const pickupTarget = ref<RentalOrderVO | null>(null)
const pickupName = ref('')

const returnVisible = ref(false)
const returnSaving = ref(false)
const returnTarget = ref<RentalOrderVO | null>(null)
const returnForm = reactive({
  acceptResult: 1,
  overdueFee: 0,
  depositRefundAmount: 0,
})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RentalOrderVO | null>(null)

function buildParams(): RentalQueryDTO {
  const params: RentalQueryDTO = { page: page.value, size: size.value }
  if (filters.userName.trim()) params.userName = filters.userName.trim()
  if (filters.toolCode.trim()) params.toolCode = filters.toolCode.trim()
  if (filters.acceptResult !== '') params.acceptResult = filters.acceptResult
  if (filters.overdue !== '') params.overdue = filters.overdue === '1'
  return params
}

async function load() {
  loading.value = true
  failed.value = false
  syncUrl()
  try {
    const result = await pageRentals(buildParams())
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
  filters.userName = ''
  filters.toolCode = ''
  filters.acceptResult = ''
  filters.overdue = ''
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
      ...(filters.userName.trim() ? { user: filters.userName.trim() } : {}),
      ...(filters.toolCode.trim() ? { code: filters.toolCode.trim() } : {}),
      ...(filters.acceptResult !== '' ? { accept: String(filters.acceptResult) } : {}),
      ...(filters.overdue !== '' ? { overdue: filters.overdue } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function openPickup(row: RentalOrderVO) {
  pickupTarget.value = row
  pickupName.value = row.pickupName || row.userName
  pickupVisible.value = true
}

async function confirmPickup() {
  if (!pickupTarget.value) return
  if (!pickupName.value.trim()) {
    ElMessage.warning('请填写取件人')
    return
  }
  pickupSaving.value = true
  try {
    await pickupRental(pickupTarget.value.id, { pickupName: pickupName.value.trim() })
    ElMessage.success('取件登记完成')
    pickupVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    pickupSaving.value = false
  }
}

function openReturn(row: RentalOrderVO) {
  returnTarget.value = row
  returnForm.acceptResult = 1
  returnForm.overdueFee = 0
  returnForm.depositRefundAmount = row.depositAmount
  returnVisible.value = true
}

async function confirmReturn() {
  if (!returnTarget.value) return
  const deposit = returnTarget.value.depositAmount || 0
  const fee = returnForm.overdueFee || 0
  const refund = returnForm.depositRefundAmount || 0
  if (fee < 0 || refund < 0) {
    ElMessage.warning('金额不能为负数')
    return
  }
  if (fee > deposit) {
    ElMessage.warning('逾期费用不能大于押金')
    return
  }
  if (refund + fee > deposit) {
    ElMessage.warning('实退押金 + 逾期费不得超过押金金额')
    return
  }
  returnSaving.value = true
  try {
    await returnRental(returnTarget.value.id, {
      acceptResult: returnForm.acceptResult,
      overdueFee: fee,
      depositRefundAmount: refund,
    })
    ElMessage.success('归还验收完成，押金已退还')
    returnVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    returnSaving.value = false
  }
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

async function exportExcel() {
  exporting.value = true
  try {
    const blob = await exportRentals(buildParams())
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `rental-${todayCompact()}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch {
    // 拦截器已统一提示
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  const q = route.query
  filters.userName = typeof q.user === 'string' ? q.user : ''
  filters.toolCode = typeof q.code === 'string' ? q.code : ''
  const accept = Number(q.accept)
  filters.acceptResult = [1, 2, 3].includes(accept) ? accept : ''
  filters.overdue = q.overdue === '1' ? '1' : q.overdue === '0' ? '0' : ''
  const p = Number(q.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.admin-rentals {
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
.filter-bar :deep(.el-button:not(.el-button--primary)),
.ph-actions :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.filter-bar :deep(.el-button:not(.el-button--primary):hover),
.ph-actions :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
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
  width: 200px;
}
.filter-bar .f-item {
  width: 140px;
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
.rental-table {
  width: 100%;
  font-size: 13px;
}
.rental-table :deep(th.el-table__cell) {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--primary-3);
  background: var(--primary-tint);
}
.rental-table :deep(td.el-table__cell) {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}
.rental-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--surface-2);
}
.rental-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.rental-table :deep(.cell) {
  padding: 0;
  overflow: visible;
  text-overflow: clip;
  word-break: normal;
  white-space: normal;
}
.tool-name {
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.time-cell {
  font-size: 12.5px;
  color: var(--text-2);
  white-space: nowrap;
}
.phone {
  white-space: nowrap;
}
.fee-line {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-3);
}
.fee-line.neg {
  color: var(--danger);
}
.muted {
  font-size: 12.5px;
  color: var(--text-3);
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

/* ---------- 弹窗 / 抽屉 ---------- */
.dialog-sub {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}
.form-tip {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-3);
}
.full {
  width: 100%;
}
.money-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.calc-line {
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--info);
  background: var(--info-9);
  border-left: 4px solid var(--info);
  border-radius: 0 var(--r-2) var(--r-2) 0;
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
  .money-grid {
    grid-template-columns: 1fr;
  }
  .pager :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
