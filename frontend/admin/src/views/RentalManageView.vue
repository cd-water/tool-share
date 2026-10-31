<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import {
  rentalApi,
  type DamageResult,
  type RentalQuery,
  type RentalReturnDTO,
  type RentalVO,
} from '@/api/rentals'
import { cleanParams } from '@/utils/params'
import { exportTimestamp, formatDateTime, formatMoney } from '@/utils/format'
import {
  DAMAGE_RESULT_LABELS,
  DEPOSIT_STATUS_LABELS,
  RENTAL_STATUS_LABELS,
  depositStatusTag,
  enumLabel,
  optionList,
  rentalStatusTag,
} from '@/utils/enums'

const loading = ref(false)
const list = ref<RentalVO[]>([])
const total = ref(0)
const query = reactive<RentalQuery>({
  page: 1,
  size: 10,
  keyword: '',
  status: '',
  overdue: '',
})

const statusOptions = optionList(RENTAL_STATUS_LABELS)
const damageResultOptions = optionList(DAMAGE_RESULT_LABELS)
const overdueOptions = [
  { value: 'true', label: '仅看逾期' },
  { value: 'false', label: '未逾期' },
]

async function loadList() {
  loading.value = true
  try {
    const data = await rentalApi.list(cleanParams({ ...query }))
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  loadList()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.overdue = ''
  query.page = 1
  loadList()
}

function handleSizeChange() {
  query.page = 1
  loadList()
}

const exporting = ref(false)

async function handleExport() {
  exporting.value = true
  try {
    const blob = await rentalApi.export(cleanParams({ ...query }))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `租赁记录_${exportTimestamp()}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('Excel 导出成功')
  } finally {
    exporting.value = false
  }
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RentalVO | null>(null)

async function fetchDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await rentalApi.detail(id)
  } finally {
    detailLoading.value = false
  }
}

function openDetail(row: RentalVO) {
  detail.value = row
  detailVisible.value = true
  fetchDetail(row.id)
}

async function refreshCurrentDetail(id: number) {
  if (detailVisible.value && detail.value?.id === id) {
    await fetchDetail(id)
  }
}

async function handlePickup(row: RentalVO) {
  try {
    await ElMessageBox.confirm(`确认工具「${row.toolName}」已取件？`, '确认取件', {
      type: 'warning',
    })
  } catch {
    return
  }
  await rentalApi.pickup(row.id)
  ElMessage.success('已确认取件')
  await loadList()
  await refreshCurrentDetail(row.id)
}

const returnDialogVisible = ref(false)
const returnSaving = ref(false)
const returnTarget = ref<RentalVO | null>(null)
const returnForm = reactive<{
  damageResult: DamageResult
  overdueFee: number | null
  damageFee: number | null
  remark: string
}>({
  damageResult: 'INTACT',
  overdueFee: null,
  damageFee: null,
  remark: '',
})

const refundPreview = computed(() => {
  const deposit = returnTarget.value?.depositAmount ?? 0
  return Math.max(
    0,
    deposit - (returnForm.overdueFee ?? 0) - (returnForm.damageFee ?? 0),
  )
})

function openReturn(row: RentalVO) {
  returnTarget.value = row
  returnForm.damageResult = 'INTACT'
  returnForm.overdueFee = null
  returnForm.damageFee = null
  returnForm.remark = ''
  returnDialogVisible.value = true
}

async function submitReturn() {
  const target = returnTarget.value
  if (!target) return
  const payload: RentalReturnDTO = {
    damageResult: returnForm.damageResult,
  }
  if (returnForm.overdueFee !== null) payload.overdueFee = returnForm.overdueFee
  if (returnForm.damageFee !== null) payload.damageFee = returnForm.damageFee
  if (returnForm.remark.trim()) payload.remark = returnForm.remark.trim()
  returnSaving.value = true
  try {
    await rentalApi.return(target.id, payload)
    ElMessage.success('归还验收已完成')
    returnDialogVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    returnSaving.value = false
  }
}

async function handleCancel(row: RentalVO) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入取消原因', '管理员取消租赁', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      type: 'warning',
      inputValidator: (value) => (value && value.trim() ? true : '请输入取消原因'),
    })
    reason = result.value.trim()
  } catch {
    return
  }
  await rentalApi.cancel(row.id, { reason })
  ElMessage.success('租赁已取消')
  await loadList()
  await refreshCurrentDetail(row.id)
}

function canCancel(row: RentalVO): boolean {
  return ['PENDING_PAYMENT', 'RESERVED', 'PICKED_UP', 'OVERDUE'].includes(row.status)
}

function canReturn(row: RentalVO): boolean {
  return row.status === 'PICKED_UP' || row.status === 'OVERDUE'
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="单号 / 工具 / 租用人"
            clearable
            style="width: 210px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="逾期">
          <el-select v-model="query.overdue" placeholder="全部" clearable style="width: 130px">
            <el-option
              v-for="item in overdueOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button :icon="Download" :loading="exporting" @click="handleExport">
            导出 Excel
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="loading"
        :data="list"
        stripe
        empty-text="暂无租赁记录"
        row-key="id"
      >
        <el-table-column prop="orderNo" label="租赁单号" width="180" />
        <el-table-column label="工具" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.toolName }}</div>
            <div class="muted small">{{ row.toolCode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="租用人" width="140">
          <template #default="{ row }">
            <div>{{ row.renterName }}</div>
            <div class="muted small">{{ row.renterPhone }}</div>
          </template>
        </el-table-column>
        <el-table-column label="租期" width="210">
          <template #default="{ row }">
            <div>{{ formatDateTime(row.startTime) }}</div>
            <div class="muted small">至 {{ formatDateTime(row.endTime) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="押金" width="110" align="right">
          <template #default="{ row }">
            <span class="amount">{{ formatMoney(row.depositAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="押金状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="depositStatusTag(row.depositStatus)" effect="plain">
              {{ row.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, row.depositStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="rentalStatusTag(row.status)">
              {{ row.statusName || enumLabel(RENTAL_STATUS_LABELS, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="费用" width="140" align="right">
          <template #default="{ row }">
            <div v-if="row.overdueFee" class="muted small">逾期 {{ formatMoney(row.overdueFee) }}</div>
            <div v-if="row.damageFee" class="muted small">损坏 {{ formatMoney(row.damageFee) }}</div>
            <div v-if="!row.overdueFee && !row.damageFee" class="muted">—</div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'RESERVED'"
              link
              type="success"
              @click="handlePickup(row)"
            >
              取件
            </el-button>
            <el-button v-if="canReturn(row)" link type="warning" @click="openReturn(row)">
              归还验收
            </el-button>
            <el-button v-if="canCancel(row)" link type="danger" @click="handleCancel(row)">
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadList"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="租赁详情" size="520px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="租赁单号" :span="2">
              {{ detail.orderNo }}
            </el-descriptions-item>
            <el-descriptions-item label="工具名称">{{ detail.toolName }}</el-descriptions-item>
            <el-descriptions-item label="工具编码">{{ detail.toolCode }}</el-descriptions-item>
            <el-descriptions-item label="租用人">{{ detail.renterName }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.renterPhone }}</el-descriptions-item>
            <el-descriptions-item label="取件人">{{ detail.pickupPerson || '—' }}</el-descriptions-item>
            <el-descriptions-item label="租赁状态">
              <el-tag :type="rentalStatusTag(detail.status)">
                {{ detail.statusName || enumLabel(RENTAL_STATUS_LABELS, detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="开始时间">
              {{ formatDateTime(detail.startTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="结束时间">
              {{ formatDateTime(detail.endTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="实际归还">
              {{ formatDateTime(detail.actualReturnTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(detail.createTime) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">押金与费用</el-divider>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="押金金额">
              {{ formatMoney(detail.depositAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="押金状态">
              <el-tag :type="depositStatusTag(detail.depositStatus)" effect="plain">
                {{ detail.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, detail.depositStatus) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="逾期费">
              {{ formatMoney(detail.overdueFee) }}
            </el-descriptions-item>
            <el-descriptions-item label="损坏费">
              {{ formatMoney(detail.damageFee) }}
            </el-descriptions-item>
            <el-descriptions-item label="退款金额">
              {{ formatMoney(detail.refundAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="验收结果">
              {{ enumLabel(DAMAGE_RESULT_LABELS, detail.damageResult) }}
            </el-descriptions-item>
          </el-descriptions>

          <template v-if="detail.returnRemark || detail.cancelReason">
            <el-divider content-position="left">备注信息</el-divider>
            <el-descriptions :column="1" border>
              <el-descriptions-item v-if="detail.returnRemark" label="归还备注">
                {{ detail.returnRemark }}
              </el-descriptions-item>
              <el-descriptions-item v-if="detail.cancelReason" label="取消原因">
                {{ detail.cancelReason }}（{{ formatDateTime(detail.cancelTime) }}）
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </template>
        <el-empty v-else description="暂无详情" />
      </div>
    </el-drawer>

    <el-dialog v-model="returnDialogVisible" title="归还验收" width="520px">
      <el-form label-width="96px">
        <el-form-item label="租赁单号">
          <span>{{ returnTarget?.orderNo || '—' }}</span>
        </el-form-item>
        <el-form-item label="押金金额">
          <span class="amount">{{ formatMoney(returnTarget?.depositAmount) }}</span>
        </el-form-item>
        <el-form-item label="验收结果" required>
          <el-select v-model="returnForm.damageResult" style="width: 100%">
            <el-option
              v-for="item in damageResultOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="逾期费">
          <el-input-number
            v-model="returnForm.overdueFee"
            :min="0"
            :precision="2"
            :step="5"
            controls-position="right"
            placeholder="留空由系统计算"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="损坏费">
          <el-input-number
            v-model="returnForm.damageFee"
            :min="0"
            :precision="2"
            :step="10"
            controls-position="right"
            placeholder="可手动指定"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="归还备注">
          <el-input
            v-model="returnForm.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
        <el-alert :closable="false" type="info" show-icon>
          <template #title>
            预计退款：<strong class="amount">{{ formatMoney(refundPreview) }}</strong>
            <span class="muted">（押金 - 逾期费 - 损坏费，最低为 0）</span>
          </template>
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSaving" @click="submitReturn">
          确认归还
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.small {
  font-size: 12px;
  line-height: 1.5;
}
</style>
