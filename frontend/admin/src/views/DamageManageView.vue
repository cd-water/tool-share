<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  damageApi,
  type AcceptResult,
  type DamageAcceptDTO,
  type DamageProgressDTO,
  type DamageQuery,
  type DamageReportVO,
  type DamageVerifyDTO,
  type ProgressStatus,
  type ToolStatusAfter,
  type VerifyResult,
} from '@/api/damage'
import { cleanParams } from '@/utils/params'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
import {
  ACCEPT_RESULT_LABELS,
  PROGRESS_STATUS_LABELS,
  REPORT_STATUS_LABELS,
  TOOL_STATUS_LABELS,
  VERIFY_RESULT_LABELS,
  acceptResultTag,
  enumLabel,
  optionList,
  progressStatusTag,
  reportStatusTag,
} from '@/utils/enums'

const loading = ref(false)
const list = ref<DamageReportVO[]>([])
const total = ref(0)
const query = reactive<DamageQuery>({
  page: 1,
  size: 10,
  keyword: '',
  status: '',
})

const statusOptions = optionList(REPORT_STATUS_LABELS)
const verifyResultOptions = optionList(VERIFY_RESULT_LABELS)
const progressStatusOptions = optionList(PROGRESS_STATUS_LABELS)
const acceptResultOptions = optionList(ACCEPT_RESULT_LABELS)
const toolStatusAfterOptions = optionList(TOOL_STATUS_LABELS).filter(
  (item) => item.value !== 'RENTED',
)

async function loadList() {
  loading.value = true
  try {
    const data = await damageApi.list(cleanParams({ ...query }))
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
  query.page = 1
  loadList()
}

function handleSizeChange() {
  query.page = 1
  loadList()
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<DamageReportVO | null>(null)

async function fetchDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await damageApi.detail(id)
  } finally {
    detailLoading.value = false
  }
}

function openDetail(row: DamageReportVO) {
  detail.value = row
  detailVisible.value = true
  fetchDetail(row.id)
}

async function refreshCurrentDetail(id: number) {
  if (detailVisible.value && detail.value?.id === id) {
    await fetchDetail(id)
  }
}

const verifyVisible = ref(false)
const verifySaving = ref(false)
const verifyTarget = ref<DamageReportVO | null>(null)
const verifyForm = reactive<{
  verifyResult: VerifyResult
  repairPerson: string
  repairEstimate: string
  remark: string
}>({
  verifyResult: 'CONFIRMED',
  repairPerson: '',
  repairEstimate: '',
  remark: '',
})

function openVerify(row: DamageReportVO) {
  verifyTarget.value = row
  verifyForm.verifyResult = 'CONFIRMED'
  verifyForm.repairPerson = ''
  verifyForm.repairEstimate = ''
  verifyForm.remark = ''
  verifyVisible.value = true
}

async function submitVerify() {
  const target = verifyTarget.value
  if (!target) return
  const payload: DamageVerifyDTO = { verifyResult: verifyForm.verifyResult }
  if (verifyForm.repairPerson.trim()) payload.repairPerson = verifyForm.repairPerson.trim()
  if (verifyForm.repairEstimate.trim()) payload.repairEstimate = verifyForm.repairEstimate.trim()
  if (verifyForm.remark.trim()) payload.remark = verifyForm.remark.trim()
  verifySaving.value = true
  try {
    await damageApi.verify(target.id, payload)
    ElMessage.success('报修核验已完成')
    verifyVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    verifySaving.value = false
  }
}

const progressVisible = ref(false)
const progressSaving = ref(false)
const progressTarget = ref<DamageReportVO | null>(null)
const progressForm = reactive<{
  progressStatus: ProgressStatus
  progressNote: string
  repairCost: number | null
  repairEstimate: string
  repairPerson: string
}>({
  progressStatus: 'REPAIRING',
  progressNote: '',
  repairCost: null,
  repairEstimate: '',
  repairPerson: '',
})

function openProgress(row: DamageReportVO) {
  progressTarget.value = row
  progressForm.progressStatus = row.progressStatus ?? 'REPAIRING'
  progressForm.progressNote = ''
  progressForm.repairCost = null
  progressForm.repairEstimate = row.repairEstimate ?? ''
  progressForm.repairPerson = row.repairPerson ?? ''
  progressVisible.value = true
}

async function submitProgress() {
  const target = progressTarget.value
  if (!target) return
  if (!progressForm.progressNote.trim()) {
    ElMessage.warning('请填写进度说明')
    return
  }
  const payload: DamageProgressDTO = {
    progressStatus: progressForm.progressStatus,
    progressNote: progressForm.progressNote.trim(),
  }
  if (progressForm.repairCost !== null) payload.repairCost = progressForm.repairCost
  if (progressForm.repairEstimate.trim()) payload.repairEstimate = progressForm.repairEstimate.trim()
  if (progressForm.repairPerson.trim()) payload.repairPerson = progressForm.repairPerson.trim()
  progressSaving.value = true
  try {
    await damageApi.progress(target.id, payload)
    ElMessage.success('维修进度已更新')
    progressVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    progressSaving.value = false
  }
}

const acceptVisible = ref(false)
const acceptSaving = ref(false)
const acceptTarget = ref<DamageReportVO | null>(null)
const acceptForm = reactive<{
  acceptResult: AcceptResult
  toolStatusAfter: ToolStatusAfter
  repairCost: number | null
  remark: string
}>({
  acceptResult: 'QUALIFIED',
  toolStatusAfter: 'AVAILABLE',
  repairCost: null,
  remark: '',
})

function openAccept(row: DamageReportVO) {
  acceptTarget.value = row
  acceptForm.acceptResult = 'QUALIFIED'
  acceptForm.toolStatusAfter = 'AVAILABLE'
  acceptForm.repairCost = row.repairCost ?? null
  acceptForm.remark = ''
  acceptVisible.value = true
}

async function submitAccept() {
  const target = acceptTarget.value
  if (!target) return
  const payload: DamageAcceptDTO = {
    acceptResult: acceptForm.acceptResult,
    toolStatusAfter: acceptForm.toolStatusAfter,
  }
  if (acceptForm.repairCost !== null) payload.repairCost = acceptForm.repairCost
  if (acceptForm.remark.trim()) payload.remark = acceptForm.remark.trim()
  acceptSaving.value = true
  try {
    await damageApi.accept(target.id, payload)
    ElMessage.success('维修验收已完成')
    acceptVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    acceptSaving.value = false
  }
}

function canVerify(row: DamageReportVO): boolean {
  return row.status === 'SUBMITTED'
}

function canProgress(row: DamageReportVO): boolean {
  return row.status === 'VERIFIED' || row.status === 'REPAIRING'
}

function canAccept(row: DamageReportVO): boolean {
  return (
    row.status === 'COMPLETED' ||
    row.status === 'REPAIRING' ||
    row.progressStatus === 'COMPLETED' ||
    row.progressStatus === 'FAILED'
  )
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
            placeholder="报修单号 / 工具 / 报修人"
            clearable
            style="width: 220px"
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
        <el-form-item class="filter-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="loading"
        :data="list"
        stripe
        empty-text="暂无报修记录"
        row-key="id"
      >
        <el-table-column prop="reportNo" label="报修单号" width="180" />
        <el-table-column label="工具" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.toolName || '—' }}</div>
            <div class="muted small">{{ row.toolCode || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="报修人" width="140">
          <template #default="{ row }">
            <div>{{ row.reporterName || '—' }}</div>
            <div class="muted small">{{ row.reporterPhone || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="damagePart" label="损坏部位" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.damagePart || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="reportStatusTag(row.status)">
              {{ row.statusName || enumLabel(REPORT_STATUS_LABELS, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="维修进度" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.progressStatus" :type="progressStatusTag(row.progressStatus)" effect="plain">
              {{ row.progressStatusName || enumLabel(PROGRESS_STATUS_LABELS, row.progressStatus) }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="repairPerson" label="维修负责人" width="120">
          <template #default="{ row }">{{ row.repairPerson || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canVerify(row)" link type="warning" @click="openVerify(row)">
              核验
            </el-button>
            <el-button v-if="canProgress(row)" link type="primary" @click="openProgress(row)">
              进度
            </el-button>
            <el-button v-if="canAccept(row)" link type="success" @click="openAccept(row)">
              验收
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

    <el-drawer v-model="detailVisible" title="报修详情" size="540px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="报修单号" :span="2">
              {{ detail.reportNo }}
            </el-descriptions-item>
            <el-descriptions-item label="工具名称">{{ detail.toolName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="工具编码">{{ detail.toolCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="报修人">{{ detail.reporterName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="联系电话">{{ detail.reporterPhone || '—' }}</el-descriptions-item>
            <el-descriptions-item label="损坏部位">{{ detail.damagePart || '—' }}</el-descriptions-item>
            <el-descriptions-item label="报修状态">
              <el-tag :type="reportStatusTag(detail.status)">
                {{ detail.statusName || enumLabel(REPORT_STATUS_LABELS, detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(detail.createTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="关联租赁">
              {{ detail.rentalId ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="问题描述" :span="2">
              {{ detail.description || '—' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.remark" label="备注" :span="2">
              {{ detail.remark }}
            </el-descriptions-item>
          </el-descriptions>

          <template v-if="detail.images?.length">
            <el-divider content-position="left">损坏图片</el-divider>
            <div class="image-list">
              <el-image
                v-for="(url, index) in detail.images"
                :key="`${url}-${index}`"
                :src="resolveFileUrl(url)"
                :preview-src-list="detail.images.map(resolveFileUrl)"
                :initial-index="index"
                fit="cover"
                preview-teleported
                class="damage-image"
              />
            </div>
          </template>

          <el-divider content-position="left">核验信息</el-divider>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="核验人">{{ detail.verifier || '—' }}</el-descriptions-item>
            <el-descriptions-item label="核验时间">
              {{ formatDateTime(detail.verifyTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="核验结果">
              {{ enumLabel(VERIFY_RESULT_LABELS, detail.verifyResult) }}
            </el-descriptions-item>
            <el-descriptions-item label="预计时长">
              {{ detail.repairEstimate || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">维修信息</el-divider>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="维修负责人">
              {{ detail.repairPerson || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="开始维修">
              {{ formatDateTime(detail.repairStartTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="维修进度">
              {{ enumLabel(PROGRESS_STATUS_LABELS, detail.progressStatus) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新进度">
              {{ formatDateTime(detail.progressUpdateTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="维修费用">
              {{ formatMoney(detail.repairCost) }}
            </el-descriptions-item>
            <el-descriptions-item label="进度说明">
              {{ detail.progressNote || '—' }}
            </el-descriptions-item>
          </el-descriptions>

          <el-divider content-position="left">验收信息</el-divider>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="验收人">{{ detail.acceptor || '—' }}</el-descriptions-item>
            <el-descriptions-item label="验收时间">
              {{ formatDateTime(detail.acceptTime) }}
            </el-descriptions-item>
            <el-descriptions-item label="验收结果">
              <el-tag
                v-if="detail.acceptResult"
                :type="acceptResultTag(detail.acceptResult)"
                effect="plain"
              >
                {{ detail.acceptResultName || enumLabel(ACCEPT_RESULT_LABELS, detail.acceptResult) }}
              </el-tag>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="工具状态">
              {{ enumLabel(TOOL_STATUS_LABELS, detail.toolStatusAfter) }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty v-else description="暂无详情" />
      </div>
    </el-drawer>

    <el-dialog v-model="verifyVisible" title="报修核验" width="500px">
      <el-form label-width="96px">
        <el-form-item label="报修单号">
          <span>{{ verifyTarget?.reportNo || '—' }}</span>
        </el-form-item>
        <el-form-item label="核验结果" required>
          <el-select v-model="verifyForm.verifyResult" style="width: 100%">
            <el-option
              v-for="item in verifyResultOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="维修负责人">
          <el-input v-model="verifyForm.repairPerson" placeholder="选填" />
        </el-form-item>
        <el-form-item label="预计时长">
          <el-input v-model="verifyForm.repairEstimate" placeholder="如 3 天" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="verifyForm.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="verifySaving" @click="submitVerify">
          确认核验
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="progressVisible" title="更新维修进度" width="500px">
      <el-form label-width="96px">
        <el-form-item label="报修单号">
          <span>{{ progressTarget?.reportNo || '—' }}</span>
        </el-form-item>
        <el-form-item label="进度状态" required>
          <el-select v-model="progressForm.progressStatus" style="width: 100%">
            <el-option
              v-for="item in progressStatusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="进度说明" required>
          <el-input
            v-model="progressForm.progressNote"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="请填写本次维修进度说明"
          />
        </el-form-item>
        <el-form-item label="维修负责人">
          <el-input v-model="progressForm.repairPerson" placeholder="选填" />
        </el-form-item>
        <el-form-item label="预计时长">
          <el-input v-model="progressForm.repairEstimate" placeholder="选填" />
        </el-form-item>
        <el-form-item label="维修费用">
          <el-input-number
            v-model="progressForm.repairCost"
            :min="0"
            :precision="2"
            :step="10"
            controls-position="right"
            placeholder="选填"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="progressVisible = false">取消</el-button>
        <el-button type="primary" :loading="progressSaving" @click="submitProgress">
          保存进度
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="acceptVisible" title="维修验收" width="500px">
      <el-form label-width="96px">
        <el-form-item label="报修单号">
          <span>{{ acceptTarget?.reportNo || '—' }}</span>
        </el-form-item>
        <el-form-item label="验收结果" required>
          <el-select v-model="acceptForm.acceptResult" style="width: 100%">
            <el-option
              v-for="item in acceptResultOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="工具状态" required>
          <el-select v-model="acceptForm.toolStatusAfter" style="width: 100%">
            <el-option
              v-for="item in toolStatusAfterOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="维修费用">
          <el-input-number
            v-model="acceptForm.repairCost"
            :min="0"
            :precision="2"
            :step="10"
            controls-position="right"
            placeholder="选填"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="acceptForm.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" :loading="acceptSaving" @click="submitAccept">
          确认验收
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

.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.damage-image {
  width: 100px;
  height: 100px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
}
</style>
