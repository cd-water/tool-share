<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, CircleCheck, Close, Document, Refresh, Search, Tools, Warning } from '@element-plus/icons-vue'
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
  enumLabel,
  optionList,
} from '@/utils/enums'

const loading = ref(false)
const list = ref<DamageReportVO[]>([])
const total = ref(0)
const query = reactive<DamageQuery & { page: number; size: number }>({
  page: 1,
  size: 10,
  keyword: '',
  status: '',
})

const progressFilter = ref('')
const visibleList = computed(() =>
  progressFilter.value ? list.value.filter((row) => row.progressStatus === progressFilter.value) : list.value,
)

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

// —— 分页 ——
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))
const pageNumbers = computed<(number | '…')[]>(() => {
  const pages: (number | '…')[] = []
  const all = totalPages.value
  const cur = query.page
  const span = 2
  for (let i = 1; i <= all; i++) {
    if (i === 1 || i === all || (i >= cur - span && i <= cur + span)) {
      pages.push(i)
    } else if (pages[pages.length - 1] !== '…') {
      pages.push('…')
    }
  }
  return pages
})

function gotoPage(p: number) {
  if (p < 1 || p > totalPages.value || p === query.page) return
  query.page = p
  loadList()
}

// —— 状态徽章映射（设计系统 .badge 色调） ——
function reportBadge(status?: string): string {
  switch (status) {
    case 'SUBMITTED':
      return 'warn'
    case 'VERIFIED':
      return 'info'
    case 'REPAIRING':
      return 'brand'
    case 'COMPLETED':
      return 'ok'
    case 'REJECTED':
    case 'SCRAPPED':
      return 'bad'
    default:
      return 'neutral'
  }
}

function progressBadge(status?: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'ok'
    case 'FAILED':
      return 'bad'
    case 'WAITING_PARTS':
      return 'warn'
    default:
      return 'info'
  }
}

function acceptBadge(result?: string): string {
  switch (result) {
    case 'QUALIFIED':
      return 'ok'
    case 'UNQUALIFIED':
      return 'bad'
    case 'PARTIAL':
      return 'warn'
    default:
      return 'neutral'
  }
}

// —— 详情抽屉 ——
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

// 三段状态机时间线：核验 → 维修 → 验收
interface TimelineStage {
  title: string
  done: boolean
  active: boolean
  time?: string
  desc: string
}

const timelineStages = computed<TimelineStage[]>(() => {
  const d = detail.value
  if (!d) return []
  const verifyDone = d.status !== 'SUBMITTED'
  const repairDone =
    d.status === 'COMPLETED' ||
    d.status === 'SCRAPPED' ||
    d.progressStatus === 'COMPLETED' ||
    d.progressStatus === 'FAILED'
  const acceptDone = d.status === 'COMPLETED' || d.status === 'SCRAPPED' || !!d.acceptTime

  const stages: TimelineStage[] = [
    {
      title: '核验',
      done: verifyDone,
      active: false,
      time: verifyDone ? d.verifyTime : undefined,
      desc: verifyDone
        ? `${d.verifier ? d.verifier + ' · ' : ''}${enumLabel(VERIFY_RESULT_LABELS, d.verifyResult)}`
        : '等待管理员核验',
    },
    {
      title: '维修',
      done: repairDone,
      active: false,
      time: repairDone ? d.progressUpdateTime || d.repairStartTime : undefined,
      desc: repairDone
        ? `${d.repairPerson ? d.repairPerson + ' · ' : ''}${
            d.progressStatus ? enumLabel(PROGRESS_STATUS_LABELS, d.progressStatus) : '维修中'
          }`
        : d.status === 'REJECTED'
          ? '已驳回，未进入维修'
          : '等待安排维修',
    },
    {
      title: '验收',
      done: acceptDone,
      active: false,
      time: acceptDone ? d.acceptTime : undefined,
      desc: acceptDone
        ? `${d.acceptor ? d.acceptor + ' · ' : ''}${
            d.acceptResult ? enumLabel(ACCEPT_RESULT_LABELS, d.acceptResult) : ''
          }`
        : '维修完成后进行验收',
    },
  ]

  const firstPending = stages.findIndex((s) => !s.done)
  stages.forEach((s, i) => {
    s.active = firstPending === i
  })
  return stages
})

// 图片预览
const previewUrl = ref('')
function openPreview(url: string) {
  previewUrl.value = url
}

// —— 核验 ——
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

// —— 维修进度 ——
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

// —— 验收 ——
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

// 原生 number 输入：空串回退为 null，保持与 el-input-number 清空语义一致
function readCost(e: Event): number | null {
  const raw = (e.target as HTMLInputElement).value
  return raw === '' ? null : Number(raw)
}

function canVerify(row: DamageReportVO): boolean {
  return row.status === 'SUBMITTED'
}

function canProgress(row: DamageReportVO): boolean {
  return row.status === 'VERIFIED' || row.status === 'REPAIRING'
}

function canAccept(row: DamageReportVO): boolean {
  // 原型：仅维修中且维修进度已完成时开放验收
  return row.status === 'REPAIRING' && row.progressStatus === 'COMPLETED'
}

// 部分合格 / 不合格不能直接置为可用，后端会拒绝；这里同步收口选项
watch(
  () => acceptForm.acceptResult,
  (result) => {
    if (result !== 'QUALIFIED' && acceptForm.toolStatusAfter === 'AVAILABLE') {
      acceptForm.toolStatusAfter = 'MAINTENANCE'
    }
  },
)

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">REPAIR WORKFLOW</p>
        <h1>报修管理</h1>
        <p class="sub">按「核验 → 维修 → 验收」流程处理居民报修，全程留痕、据实结算。</p>
      </div>
    </div>

    <div class="filterbar reveal">
      <div class="f-search">
        <Search class="ic" />
        <input
          class="input"
          v-model="query.keyword"
          placeholder="报修单号 / 工具 / 报修人"
          @keyup.enter="handleSearch"
        />
      </div>
      <select class="select f-item" v-model="query.status">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <select class="select f-item" v-model="progressFilter">
        <option value="">全部维修进度</option>
        <option v-for="item in progressStatusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <button class="btn btn-primary btn-sm" @click="handleSearch">
        <Search class="ic" />查询
      </button>
      <button class="btn btn-ghost btn-sm" @click="handleReset">
        <Refresh class="ic" />重置
      </button>
      <span class="grow"></span>
      <span class="tiny">共 {{ total }} 单</span>
    </div>

    <div class="table-wrap reveal">
      <div class="table-scroll">
        <table class="tbl">
          <thead>
            <tr>
              <th>报修单号</th>
              <th>工具</th>
              <th>报修人</th>
              <th>损坏部位</th>
              <th>状态</th>
              <th>维修进度</th>
              <th>负责人</th>
              <th>创建时间</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody v-if="loading">
            <tr v-for="i in 5" :key="`sk-${i}`">
              <td colspan="9"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
          <tbody v-else-if="!list.length">
            <tr>
              <td colspan="9">
                <div class="empty">
                  <Warning class="e-ic" />
                  <h4>暂无报修记录</h4>
                  <p>居民提交的报修单会显示在这里。</p>
                </div>
              </td>
            </tr>
          </tbody>
          <tbody v-else>
            <tr v-for="row in visibleList" :key="row.id">
              <td>
                <span class="code">{{ row.reportNo }}</span>
                <div class="cell-sub">{{ formatDateTime(row.createTime) }}</div>
              </td>
              <td>
                <div class="cell-tool">
                  <img v-if="row.toolImageUrl" :src="resolveFileUrl(row.toolImageUrl)" class="thumb" alt="" />
                  <span v-else class="thumb-ph">—</span>
                  <div>
                    <div class="cell-main">{{ row.toolName || '—' }}</div>
                    <div class="cell-sub">{{ row.toolCode || '' }}</div>
                  </div>
                </div>
              </td>
              <td>
                <div class="cell-main">{{ row.reporterName || '—' }}</div>
                <div class="cell-sub">{{ row.reporterPhone || '' }}</div>
              </td>
              <td class="muted">{{ row.damagePart || '—' }}</td>
              <td>
                <span class="badge" :class="reportBadge(row.status)">
                  <span class="dot"></span>{{ row.statusName || enumLabel(REPORT_STATUS_LABELS, row.status) }}
                </span>
              </td>
              <td>
                <span v-if="row.progressStatus" class="badge" :class="progressBadge(row.progressStatus)">
                  {{ row.progressStatusName || enumLabel(PROGRESS_STATUS_LABELS, row.progressStatus) }}
                </span>
                <span v-else class="muted">—</span>
              </td>
              <td class="muted">{{ row.repairPerson || '—' }}</td>
              <td>{{ formatDateTime(row.createTime) }}</td>
              <td>
                <div class="row-actions">
                  <button class="link-btn" @click="openDetail(row)">详情</button>
                  <button v-if="canVerify(row)" class="link-btn" @click="openVerify(row)">核验</button>
                  <button v-if="canProgress(row)" class="link-btn" @click="openProgress(row)">进度</button>
                  <button v-if="canAccept(row)" class="link-btn ok" @click="openAccept(row)">验收</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="table-foot">
        <span class="tiny">共 {{ total }} 条 · 第 {{ query.page }}/{{ totalPages }} 页</span>
        <div class="flex">
          <select class="select" style="width: 116px; height: 32px" v-model="query.size" @change="handleSizeChange">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
          <div class="pager">
            <button :disabled="query.page <= 1" @click="gotoPage(query.page - 1)">‹</button>
            <template v-for="p in pageNumbers" :key="p">
              <button v-if="p === '…'" disabled>…</button>
              <button v-else :class="{ active: p === query.page }" @click="gotoPage(p)">{{ p }}</button>
            </template>
            <button :disabled="query.page >= totalPages" @click="gotoPage(query.page + 1)">›</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 核验 -->
    <div class="mask show" v-if="verifyVisible" @click.self="verifyVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>报修核验</h3>
            <p class="d-sub">{{ verifyTarget ? `${verifyTarget.reportNo} · ${verifyTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="verifyVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <div class="field">
            <label><span class="req">*</span>核验结果</label>
            <select class="select" v-model="verifyForm.verifyResult">
              <option v-for="item in verifyResultOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label>维修负责人</label>
            <input class="input" v-model="verifyForm.repairPerson" placeholder="选填，如 王师傅" />
          </div>
          <div class="field">
            <label>预计时长</label>
            <input class="input" v-model="verifyForm.repairEstimate" placeholder="如 3 天" />
          </div>
          <div class="field">
            <label>备注</label>
            <textarea class="textarea" v-model="verifyForm.remark" rows="2" placeholder="选填"></textarea>
          </div>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="verifyVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: verifySaving }" :disabled="verifySaving" @click="submitVerify">
            确认核验
          </button>
        </div>
      </div>
    </div>

    <!-- 进度 -->
    <div class="mask show" v-if="progressVisible" @click.self="progressVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>更新维修进度</h3>
            <p class="d-sub">{{ progressTarget ? `${progressTarget.reportNo} · ${progressTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="progressVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <div class="field">
            <label><span class="req">*</span>进度状态</label>
            <select class="select" v-model="progressForm.progressStatus">
              <option v-for="item in progressStatusOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label><span class="req">*</span>进度说明</label>
            <textarea
              class="textarea"
              v-model="progressForm.progressNote"
              rows="3"
              maxlength="200"
              placeholder="请填写本次维修进度说明"
            ></textarea>
          </div>
          <div class="form-grid">
            <div class="field">
              <label>维修负责人</label>
              <input class="input" v-model="progressForm.repairPerson" placeholder="选填" />
            </div>
            <div class="field">
              <label>预计时长</label>
              <input class="input" v-model="progressForm.repairEstimate" placeholder="选填" />
            </div>
          </div>
          <div class="field">
            <label>维修费用（元）</label>
            <input
              class="input"
              type="number"
              min="0"
              step="0.01"
              :value="progressForm.repairCost ?? ''"
              placeholder="选填"
              @input="progressForm.repairCost = readCost($event)"
            />
          </div>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="progressVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: progressSaving }" :disabled="progressSaving" @click="submitProgress">
            保存进度
          </button>
        </div>
      </div>
    </div>

    <!-- 验收 -->
    <div class="mask show" v-if="acceptVisible" @click.self="acceptVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>维修验收</h3>
            <p class="d-sub">{{ acceptTarget ? `${acceptTarget.reportNo} · ${acceptTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="acceptVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <div class="field">
            <label><span class="req">*</span>验收结果</label>
            <select class="select" v-model="acceptForm.acceptResult">
              <option v-for="item in acceptResultOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label><span class="req">*</span>验收后工具状态</label>
            <select class="select" v-model="acceptForm.toolStatusAfter">
              <option v-for="item in toolStatusAfterOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label>维修费用（元）</label>
            <input
              class="input"
              type="number"
              min="0"
              step="0.01"
              :value="acceptForm.repairCost ?? ''"
              placeholder="选填"
              @input="acceptForm.repairCost = readCost($event)"
            />
          </div>
          <div class="field">
            <label>备注</label>
            <textarea class="textarea" v-model="acceptForm.remark" rows="2" placeholder="选填"></textarea>
          </div>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="acceptVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: acceptSaving }" :disabled="acceptSaving" @click="submitAccept">
            确认验收
          </button>
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
    <div class="drawer-mask show" v-if="detailVisible" @click.self="detailVisible = false">
      <div class="drawer" style="width: 600px">
        <div class="dlg-head">
          <div>
            <h3>报修详情</h3>
            <p class="d-sub">{{ detail?.reportNo }}</p>
          </div>
          <button class="dlg-x" @click="detailVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <template v-if="detailLoading">
            <div v-for="i in 6" :key="i" class="skel" style="height: 18px; margin-bottom: 14px"></div>
          </template>
          <template v-else-if="detail">
            <div class="badge-row flex">
              <span class="badge" :class="reportBadge(detail.status)">
                <span class="dot"></span>{{ detail.statusName || enumLabel(REPORT_STATUS_LABELS, detail.status) }}
              </span>
              <span v-if="detail.progressStatus" class="badge" :class="progressBadge(detail.progressStatus)">
                {{ detail.progressStatusName || enumLabel(PROGRESS_STATUS_LABELS, detail.progressStatus) }}
              </span>
            </div>

            <ul class="timeline">
              <li v-for="s in timelineStages" :key="s.title" :class="{ done: s.done, active: s.active }">
                <div class="tl-t">{{ s.title }}</div>
                <div v-if="s.time" class="tl-time">{{ formatDateTime(s.time) }}</div>
                <div class="tl-d">{{ s.desc }}</div>
              </li>
            </ul>

            <div class="sec-label">损坏描述</div>
            <p class="dmg-desc">{{ detail.description || '—' }}</p>
            <p v-if="detail.remark" class="dmg-note">备注：{{ detail.remark }}</p>

            <dl class="kv">
              <dt>报修单号</dt>
              <dd><span class="code">{{ detail.reportNo }}</span></dd>
              <dt>工具</dt>
              <dd>{{ detail.toolName || '—' }}</dd>
              <dt>工具编码</dt>
              <dd>{{ detail.toolCode || '—' }}</dd>
              <dt>损坏部位</dt>
              <dd>{{ detail.damagePart || '—' }}</dd>
              <dt>报修人</dt>
              <dd>{{ detail.reporterName || '—' }}<template v-if="detail.reporterPhone"> · {{ detail.reporterPhone }}</template></dd>
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(detail.createTime) }}</dd>
              <dt>关联租赁</dt>
              <dd>{{ detail.rentalId ?? '—' }}</dd>
            </dl>

            <template v-if="detail.images?.length">
              <div class="sec-label">证明材料</div>
              <div class="dmg-imgs">
                <img
                  v-for="(url, index) in detail.images"
                  :key="`${url}-${index}`"
                  :src="resolveFileUrl(url)"
                  alt=""
                  @click="openPreview(resolveFileUrl(url))"
                />
              </div>
            </template>

            <div class="rep-section">
              <h4><Check class="ic" />核验信息</h4>
              <dl class="kv">
                <dt>核验人</dt>
                <dd>{{ detail.verifier || '—' }}</dd>
                <dt>核验时间</dt>
                <dd>{{ formatDateTime(detail.verifyTime) }}</dd>
                <dt>核验结果</dt>
                <dd>{{ enumLabel(VERIFY_RESULT_LABELS, detail.verifyResult) }}</dd>
                <dt>预计时长</dt>
                <dd>{{ detail.repairEstimate || '—' }}</dd>
              </dl>
            </div>

            <div class="rep-section">
              <h4><Tools class="ic" />维修信息</h4>
              <dl class="kv">
                <dt>维修负责人</dt>
                <dd>{{ detail.repairPerson || '—' }}</dd>
                <dt>开始维修</dt>
                <dd>{{ formatDateTime(detail.repairStartTime) }}</dd>
                <dt>维修进度</dt>
                <dd>{{ enumLabel(PROGRESS_STATUS_LABELS, detail.progressStatus) }}</dd>
                <dt>更新进度</dt>
                <dd>{{ formatDateTime(detail.progressUpdateTime) }}</dd>
                <dt>维修费用</dt>
                <dd>{{ formatMoney(detail.repairCost) }}</dd>
                <dt>进度说明</dt>
                <dd>{{ detail.progressNote || '—' }}</dd>
              </dl>
            </div>

            <div class="rep-section">
              <h4><CircleCheck class="ic" />验收信息</h4>
              <dl class="kv">
                <dt>验收人</dt>
                <dd>{{ detail.acceptor || '—' }}</dd>
                <dt>验收时间</dt>
                <dd>{{ formatDateTime(detail.acceptTime) }}</dd>
                <dt>验收结果</dt>
                <dd>
                  <span v-if="detail.acceptResult" class="badge" :class="acceptBadge(detail.acceptResult)">
                    {{ detail.acceptResultName || enumLabel(ACCEPT_RESULT_LABELS, detail.acceptResult) }}
                  </span>
                  <span v-else class="muted">—</span>
                </dd>
                <dt>工具状态</dt>
                <dd>{{ enumLabel(TOOL_STATUS_LABELS, detail.toolStatusAfter) }}</dd>
              </dl>
            </div>
          </template>
          <div v-else class="empty">
            <Document class="e-ic" />
            <h4>暂无详情</h4>
          </div>
        </div>
      </div>
    </div>

    <!-- 图片预览 -->
    <div class="mask show preview-mask" v-if="previewUrl" @click.self="previewUrl = ''">
      <img :src="previewUrl" class="preview-img" alt="" @click="previewUrl = ''" />
    </div>
  </div>
</template>

<style scoped>
.badge-row {
  margin-bottom: 16px;
}

.timeline li.active::before {
  box-shadow: 0 0 0 4px var(--brand-ring);
}

.dmg-desc {
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 13px 15px;
  margin: 0 0 6px;
  word-break: break-word;
}

.dmg-note {
  font-size: var(--t-xs);
  color: var(--ink-3);
  margin: 6px 0 0;
}

.dmg-imgs {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.dmg-imgs img {
  width: 90px;
  height: 90px;
  border-radius: 10px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}

.rep-section {
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 14px 16px;
  margin-bottom: 12px;
}

.rep-section h4 {
  font-size: var(--t-sm);
  font-weight: 700;
  margin: 0 0 11px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.rep-section h4 .ic {
  width: 15px;
  height: 15px;
  color: var(--brand-2);
}

.preview-mask {
  align-items: center;
}

.preview-img {
  max-width: 86vw;
  max-height: 84vh;
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  cursor: zoom-out;
}
</style>
