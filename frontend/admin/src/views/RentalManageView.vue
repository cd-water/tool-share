<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import {
  rentalApi,
  type DamageResult,
  type RentalReturnDTO,
  type RentalVO,
} from '@/api/rentals'
import { cleanParams } from '@/utils/params'
import { exportTimestamp, formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
import { badgeClass } from '@/utils/badge'
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
const query = reactive({ page: 1, size: 10, keyword: '', status: '', overdue: '' })

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
  if (detailVisible.value && detail.value?.id === id) await fetchDetail(id)
}

const pickupVisible = ref(false)
const pickupSaving = ref(false)
const pickupTarget = ref<RentalVO | null>(null)
const pickupForm = reactive({ person: '', remark: '' })

function openPickup(row: RentalVO) {
  pickupTarget.value = row
  pickupForm.person = row.pickupPerson || ''
  pickupForm.remark = ''
  pickupVisible.value = true
}

async function submitPickup() {
  const target = pickupTarget.value
  if (!target) return
  pickupSaving.value = true
  try {
    await rentalApi.pickup(target.id)
    ElMessage.success('已确认取件')
    pickupVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    pickupSaving.value = false
  }
}

const detailTimeline = computed(() => {
  const row = detail.value
  if (!row) return []
  const list: Array<{ title: string; time: string; desc: string }> = [
    { title: '提交预约', time: formatDateTime(row.createTime), desc: `取件人 ${row.pickupPerson || '—'}` },
  ]
  if (row.depositStatus !== 'UNPAID') {
    list.push({ title: '支付押金', time: formatDateTime(row.createTime), desc: `押金 ${formatMoney(row.depositAmount)} 已支付` })
  }
  if (['PICKED_UP', 'RETURNED', 'OVERDUE'].includes(row.status)) {
    list.push({ title: '取件', time: formatDateTime(row.startTime), desc: `取件人 ${row.pickupPerson || '—'} 已取件` })
  }
  if (row.actualReturnTime) {
    list.push({
      title: '归还',
      time: formatDateTime(row.actualReturnTime),
      desc: `验收结果：${enumLabel(DAMAGE_RESULT_LABELS, row.damageResult)}`,
    })
  }
  if (row.status === 'CANCELLED') {
    list.push({ title: '已取消', time: formatDateTime(row.cancelTime), desc: row.cancelReason || '管理员取消' })
  }
  return list
})

const returnDialogVisible = ref(false)
const returnSaving = ref(false)
const returnTarget = ref<RentalVO | null>(null)
const returnForm = reactive<{
  damageResult: DamageResult
  returnTime: string
  waiveOverdue: boolean
  waiveDamage: boolean
  waiverReason: string
  remark: string
}>({
  damageResult: 'INTACT',
  returnTime: '',
  waiveOverdue: false,
  waiveDamage: false,
  waiverReason: '',
  remark: '',
})

function nowInput(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

function openReturn(row: RentalVO) {
  returnTarget.value = row
  returnForm.damageResult = 'INTACT'
  returnForm.returnTime = nowInput()
  returnForm.waiveOverdue = false
  returnForm.waiveDamage = false
  returnForm.waiverReason = ''
  returnForm.remark = ''
  returnDialogVisible.value = true
}

async function submitReturn() {
  const target = returnTarget.value
  if (!target) return
  const waiving = returnForm.waiveOverdue || returnForm.waiveDamage
  if (waiving && !returnForm.waiverReason.trim()) {
    ElMessage.warning('减免必须填写原因')
    return
  }
  const payload: RentalReturnDTO = {
    actualReturnTime: returnForm.returnTime,
    damageResult: returnForm.damageResult,
  }
  if (returnForm.waiveOverdue) payload.waiveOverdue = true
  if (returnForm.waiveDamage) payload.waiveDamage = true
  if (waiving) payload.waiverReason = returnForm.waiverReason.trim()
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

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / query.size)))

onMounted(loadList)
</script>

<template>
  <div class="page-head reveal">
    <div>
      <p class="eyebrow">RENTAL OPERATIONS</p>
      <h1>租赁管理</h1>
      <p class="sub">处理取件、归还验收、退款试算与租赁取消，所有流转留痕可追溯。</p>
    </div>
    <div class="actions">
      <button type="button" class="btn btn-outline" :disabled="exporting" @click="handleExport">
        <el-icon class="ic"><Download /></el-icon>导出 Excel
      </button>
    </div>
  </div>

  <form class="filterbar reveal" @submit.prevent="handleSearch">
    <div class="f-search">
      <el-icon class="ic"><Search /></el-icon>
      <input v-model="query.keyword" class="input" placeholder="单号 / 工具 / 租用人" @keyup.enter="handleSearch" />
    </div>
    <select v-model="query.status" class="select f-item">
      <option value="">全部状态</option>
      <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <select v-model="query.overdue" class="select f-item">
      <option value="">全部</option>
      <option v-for="item in overdueOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <button type="submit" class="btn btn-primary"><el-icon class="ic"><Search /></el-icon>查询</button>
    <button type="button" class="btn btn-outline" @click="handleReset"><el-icon class="ic"><Refresh /></el-icon>重置</button>
  </form>

  <div class="table-wrap reveal">
    <div class="table-scroll">
      <table class="tbl">
        <thead>
          <tr>
            <th>租赁单号</th>
            <th>工具</th>
            <th>租用人</th>
            <th>租期</th>
            <th class="r">押金</th>
            <th class="c">押金状态</th>
            <th class="c">状态</th>
            <th class="r">费用</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in list" :key="row.id">
            <td class="code">{{ row.orderNo }}</td>
            <td>
              <div class="cell-tool">
                <img v-if="row.toolImageUrl" :src="resolveFileUrl(row.toolImageUrl)" class="thumb" alt="" />
                <span v-else class="thumb-ph">—</span>
                <div>
                  <div class="cell-main">{{ row.toolName }}</div>
                  <div class="cell-sub">{{ row.toolCode }}</div>
                </div>
              </div>
            </td>
            <td>
              <div>{{ row.renterName }}</div>
              <div class="cell-sub">{{ row.renterPhone }}</div>
            </td>
            <td>
              <div>{{ formatDateTime(row.startTime) }}</div>
              <div class="cell-sub">至 {{ formatDateTime(row.endTime) }}</div>
            </td>
            <td class="r money">{{ formatMoney(row.depositAmount) }}</td>
            <td class="c">
              <span :class="badgeClass(depositStatusTag(row.depositStatus))">
                {{ row.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, row.depositStatus) }}
              </span>
            </td>
            <td class="c">
              <span :class="badgeClass(rentalStatusTag(row.status))">
                {{ row.statusName || enumLabel(RENTAL_STATUS_LABELS, row.status) }}
              </span>
            </td>
            <td class="r">
              <div v-if="row.overdueFee" class="tiny">逾期 {{ formatMoney(row.overdueFee) }}</div>
              <div v-if="row.damageFee" class="tiny">损坏 {{ formatMoney(row.damageFee) }}</div>
              <span v-if="!row.overdueFee && !row.damageFee" class="muted">—</span>
            </td>
            <td>{{ formatDateTime(row.createTime) }}</td>
            <td>
              <div class="row-actions">
                <button class="link-btn" @click="openDetail(row)">详情</button>
                <button v-if="row.status === 'RESERVED'" class="link-btn ok" @click="openPickup(row)">取件</button>
                <button v-if="canReturn(row)" class="link-btn" @click="openReturn(row)">归还验收</button>
                <button v-if="canCancel(row)" class="link-btn danger" @click="handleCancel(row)">取消</button>
              </div>
            </td>
          </tr>
          <tr v-if="!loading && list.length === 0">
            <td colspan="10"><div class="empty" style="padding: 40px"><h4>暂无租赁记录</h4></div></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="table-foot">
      <span class="tiny">共 {{ total }} 条</span>
      <div class="pager">
        <button type="button" :disabled="query.page <= 1" @click="query.page--; loadList()">上一页</button>
        <button
          v-for="p in pageCount"
          :key="p"
          type="button"
          :class="{ active: query.page === p }"
          @click="query.page = p; loadList()"
        >{{ p }}</button>
        <button type="button" :disabled="query.page >= pageCount" @click="query.page++; loadList()">下一页</button>
      </div>
    </div>
  </div>

  <!-- 详情抽屉 -->
  <div v-if="detailVisible" class="drawer-mask show" @click.self="detailVisible = false">
    <div class="drawer" style="width: 560px">
      <div class="dlg-head">
        <h3>租赁详情</h3>
        <button class="dlg-x" @click="detailVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
      <div class="dlg-body">
        <div v-if="detailLoading" class="skel" style="height: 300px"></div>
        <div v-else-if="detail">
          <div class="flex" style="margin-bottom: 16px">
            <span :class="badgeClass(rentalStatusTag(detail.status))">
              <span class="dot"></span>{{ detail.statusName || enumLabel(RENTAL_STATUS_LABELS, detail.status) }}
            </span>
            <span :class="badgeClass(depositStatusTag(detail.depositStatus))">
              押金{{ detail.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, detail.depositStatus) }}
            </span>
          </div>
          <dl class="kv">
            <dt>租赁单号</dt>
            <dd class="code">{{ detail.orderNo }}</dd>
            <dt>工具名称</dt>
            <dd>{{ detail.toolName }}</dd>
            <dt>工具编码</dt>
            <dd>{{ detail.toolCode }}</dd>
            <dt>租用人</dt>
            <dd>{{ detail.renterName }}</dd>
            <dt>联系电话</dt>
            <dd>{{ detail.renterPhone }}</dd>
            <dt>取件人</dt>
            <dd>{{ detail.pickupPerson || '—' }}</dd>
            <dt>租赁状态</dt>
            <dd><span :class="badgeClass(rentalStatusTag(detail.status))">{{ detail.statusName || enumLabel(RENTAL_STATUS_LABELS, detail.status) }}</span></dd>
            <dt>开始时间</dt>
            <dd>{{ formatDateTime(detail.startTime) }}</dd>
            <dt>结束时间</dt>
            <dd>{{ formatDateTime(detail.endTime) }}</dd>
            <dt>实际归还</dt>
            <dd>{{ formatDateTime(detail.actualReturnTime) }}</dd>
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(detail.createTime) }}</dd>
          </dl>
          <template v-if="detail.status === 'RETURNED' || detail.refundAmount !== null">
            <div class="sec-label">结算明细</div>
            <div class="return-box">
              <div class="rb-row"><span>押金</span><span class="num">{{ formatMoney(detail.depositAmount) }}</span></div>
              <div class="rb-row"><span>逾期费用</span><span class="num">− {{ formatMoney(detail.overdueFee || 0) }}</span></div>
              <div class="rb-row"><span>损坏赔偿</span><span class="num">− {{ formatMoney(detail.damageFee || 0) }}</span></div>
              <div class="rb-row"><span>实际退款</span><span class="num">{{ formatMoney(detail.refundAmount || 0) }}</span></div>
            </div>
          </template>
          <template v-if="detail.returnRemark || detail.cancelReason">
            <div class="sec-label">备注信息</div>
            <dl class="kv">
              <dt v-if="detail.returnRemark">归还备注</dt>
              <dd v-if="detail.returnRemark">{{ detail.returnRemark }}</dd>
              <dt v-if="detail.cancelReason">取消原因</dt>
              <dd v-if="detail.cancelReason">{{ detail.cancelReason }}（{{ formatDateTime(detail.cancelTime) }}）</dd>
            </dl>
          </template>
          <div class="sec-label">流转记录</div>
          <ul class="timeline">
            <li v-for="(item, index) in detailTimeline" :key="index" :class="{ done: index < detailTimeline.length - 1 }">
              <div class="tl-t">{{ item.title }}</div>
              <div class="tl-time">{{ item.time }}</div>
              <div class="tl-d">{{ item.desc }}</div>
            </li>
          </ul>
        </div>
        <p v-else class="muted">暂无详情</p>
      </div>
    </div>
  </div>

  <!-- 取件确认 -->
  <div v-if="pickupVisible" class="mask show" @click.self="pickupVisible = false">
    <div class="dlg narrow">
      <div class="dlg-head">
        <div>
          <h3>确认取件</h3>
          <p class="d-sub">{{ pickupTarget?.orderNo }} · {{ pickupTarget?.toolName }}</p>
        </div>
        <button class="dlg-x" @click="pickupVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
      <div class="dlg-body">
        <div class="notice-sm">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" /></svg>
          <span>请核对取件人身份与押金支付状态后再确认取件。</span>
        </div>
        <div class="field">
          <label>取件人</label>
          <input v-model="pickupForm.person" class="input" placeholder="取件人姓名" />
        </div>
        <div class="field">
          <label>取件备注</label>
          <textarea v-model="pickupForm.remark" class="textarea" rows="2" placeholder="选填" />
        </div>
      </div>
      <div class="dlg-foot">
        <button type="button" class="btn btn-outline" @click="pickupVisible = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="pickupSaving" @click="submitPickup">
          {{ pickupSaving ? '处理中…' : '确认取件' }}
        </button>
      </div>
    </div>
  </div>

  <!-- 归还验收弹窗 -->
  <div v-if="returnDialogVisible" class="mask show" @click.self="returnDialogVisible = false">
    <div class="dlg">
      <div class="dlg-head">
        <h3>归还验收</h3>
        <button class="dlg-x" @click="returnDialogVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
      <div class="dlg-body">
        <div class="field">
          <label>租赁单号</label>
          <span class="code">{{ returnTarget?.orderNo || '—' }}</span>
        </div>
        <div class="field">
          <label>押金金额</label>
          <span class="money">{{ formatMoney(returnTarget?.depositAmount) }}</span>
        </div>
        <div class="form-grid">
          <div class="field">
            <label>实际归还时间</label>
            <input v-model="returnForm.returnTime" type="datetime-local" class="input" />
          </div>
          <div class="field">
            <label>验收结果</label>
            <select v-model="returnForm.damageResult" class="select">
              <option v-for="item in damageResultOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </div>
        </div>
        <div class="form-grid">
          <div class="field">
            <label>减免</label>
            <div class="check-row">
              <label class="ck"><input v-model="returnForm.waiveOverdue" type="checkbox" /><span>减免逾期费</span></label>
              <label class="ck"><input v-model="returnForm.waiveDamage" type="checkbox" /><span>减免损坏赔偿</span></label>
            </div>
          </div>
          <div class="field">
            <label>减免原因</label>
            <input
              v-model="returnForm.waiverReason"
              class="input"
              maxlength="500"
              :disabled="!returnForm.waiveOverdue && !returnForm.waiveDamage"
              placeholder="减免时必填"
            />
          </div>
        </div>
        <div class="field">
          <label>归还备注</label>
          <textarea v-model="returnForm.remark" class="textarea" rows="2" maxlength="200" placeholder="选填"></textarea>
        </div>
        <div class="refund-preview">
          <div>
            <span class="rp-label">退款金额</span>
            <span class="rp-calc">
              由系统按实际归还时间、验收结果与减免计算，押金 {{ formatMoney(returnTarget?.depositAmount || 0) }}
            </span>
          </div>
        </div>
      </div>
      <div class="dlg-foot">
        <button type="button" class="btn btn-outline" @click="returnDialogVisible = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="returnSaving" @click="submitReturn">确认归还</button>
      </div>
    </div>
  </div>
</template>
