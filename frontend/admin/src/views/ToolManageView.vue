<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ArrowUp,
  Box,
  Close,
  Delete,
  Document,
  InfoFilled,
  Plus,
  Refresh,
  Search,
  Upload,
} from '@element-plus/icons-vue'
import {
  toolApi,
  type ToolLogVO,
  type ToolQuery,
  type ToolSaveDTO,
  type ToolStatus,
  type ToolVO,
} from '@/api/tools'
import { uploadApi } from '@/api/upload'
import { toast } from '@/lib/fx'
import { cleanParams } from '@/utils/params'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
import {
  DAMAGE_LEVEL_LABELS,
  TOOL_CATEGORY_LABELS,
  TOOL_LOG_ACTION_LABELS,
  TOOL_STATUS_LABELS,
  enumLabel,
  optionList,
} from '@/utils/enums'

const loading = ref(false)
const list = ref<ToolVO[]>([])
const total = ref(0)
const hiddenIds = ref<number[]>([])

const visibleList = computed(() => list.value.filter((row) => !hiddenIds.value.includes(row.id)))

function rowImage(row: ToolVO): string {
  const raw = row.imageUrl || row.imageUrls?.[0] || ''
  return raw ? resolveFileUrl(raw) : ''
}

function removeRow(row: ToolVO) {
  hiddenIds.value.push(row.id)
  toast('已删除工具（演示）', {
    type: 'info',
    msg: row.code,
    undo: () => {
      hiddenIds.value = hiddenIds.value.filter((id) => id !== row.id)
    },
  })
}
const query = reactive<ToolQuery & { page: number; size: number }>({
  page: 1,
  size: 10,
  keyword: '',
  category: '',
  status: '',
  storageArea: '',
  orderBy: 'default',
  minRentalCount: undefined,
})
/** 维修时间筛选：'' 不限 / '7' 近 7 天 / '30' 近 30 天 / 'never' 从未维修 */
const repairScope = ref<'' | '7' | '30' | 'never'>('')

const categoryOptions = optionList(TOOL_CATEGORY_LABELS)
const damageLevelOptions = optionList(DAMAGE_LEVEL_LABELS)
const statusOptions = optionList(TOOL_STATUS_LABELS)

const orderByOptions = [
  { value: 'default', label: '默认（创建时间）' },
  { value: 'rentalCount', label: '租赁次数最多' },
  { value: 'lastRepairTime', label: '最近维修优先' },
]
const rentalCountOptions = [
  { value: 1, label: '≥ 1 次' },
  { value: 5, label: '≥ 5 次' },
  { value: 10, label: '≥ 10 次' },
]
const repairScopeOptions = [
  { value: '7', label: '近 7 天维修' },
  { value: '30', label: '近 30 天维修' },
  { value: 'never', label: '从未维修' },
]

async function loadList() {
  loading.value = true
  try {
    const params: ToolQuery = { ...query }
    if (repairScope.value === 'never') {
      params.neverRepaired = true
      delete params.repairedWithinDays
    } else if (repairScope.value) {
      params.repairedWithinDays = Number(repairScope.value)
      delete params.neverRepaired
    } else {
      delete params.neverRepaired
      delete params.repairedWithinDays
    }
    const data = await toolApi.list(cleanParams(params))
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
  query.category = ''
  query.status = ''
  query.storageArea = ''
  query.orderBy = 'default'
  query.minRentalCount = undefined
  repairScope.value = ''
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

// —— 状态 / 成色徽章映射 ——
function toolStatusBadge(status?: string): string {
  switch (status) {
    case 'AVAILABLE':
      return 'ok'
    case 'RENTED':
      return 'brand'
    case 'MAINTENANCE':
      return 'warn'
    case 'SCRAPPED':
      return 'bad'
    default:
      return 'neutral'
  }
}

function damageLevelBadge(level?: string): string {
  switch (level) {
    case 'NEW':
      return 'ok'
    case 'LIGHT':
      return 'info'
    case 'MEDIUM':
      return 'warn'
    case 'SEVERE':
      return 'bad'
    default:
      return 'neutral'
  }
}

// —— 新增 / 编辑弹窗 ——
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const editingCode = ref('')
const uploading = ref(false)

type GuideStep = { text: string; imageUrl: string }
const guideSteps = ref<GuideStep[]>([{ text: '', imageUrl: '' }])
const legacyGuide = ref(false)
const stepUploading = ref<number | null>(null)

function emptyStep(): GuideStep {
  return { text: '', imageUrl: '' }
}

/** 旧版富文本说明转纯文本（保留换行） */
function stripHtml(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|li|div|h\d)>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\n{2,}/g, '\n')
    .trim()
}

function parseGuide(raw?: string): { steps: GuideStep[]; legacy: boolean } {
  const value = (raw ?? '').trim()
  if (!value) return { steps: [emptyStep()], legacy: false }
  if (value.startsWith('[')) {
    try {
      const arr = JSON.parse(value)
      if (Array.isArray(arr)) {
        const steps = arr
          .map((item: unknown) => {
            const record = (item ?? {}) as Record<string, unknown>
            return {
              text: typeof record.text === 'string' ? record.text : '',
              imageUrl: typeof record.imageUrl === 'string' ? record.imageUrl : '',
            }
          })
          .filter((step) => step.text || step.imageUrl)
        if (steps.length) return { steps, legacy: false }
      }
    } catch {
      // 不是合法 JSON，按旧版文本处理
    }
  }
  return { steps: [{ text: stripHtml(value), imageUrl: '' }], legacy: true }
}

function serializeGuide(): string {
  const steps = guideSteps.value
    .map((step) => ({ text: step.text.trim(), imageUrl: step.imageUrl }))
    .filter((step) => step.text || step.imageUrl)
  return steps.length ? JSON.stringify(steps) : ''
}

function addStep() {
  guideSteps.value.push(emptyStep())
}

function removeStep(index: number) {
  if (guideSteps.value.length <= 1) return
  guideSteps.value.splice(index, 1)
}

function moveStep(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= guideSteps.value.length) return
  const [item] = guideSteps.value.splice(index, 1)
  guideSteps.value.splice(target, 0, item)
}

const form = reactive<ToolSaveDTO>({
  name: '',
  model: '',
  category: 'WX',
  damageLevel: 'NEW',
  deposit: 0,
  storageArea: '',
  usageGuide: '',
  imageUrls: [],
  purchaseDate: null,
  supplier: '',
  status: 'AVAILABLE',
})

const formErrors = reactive<Record<string, string>>({})

function depositInvalid(): boolean {
  const raw = form.deposit as unknown
  if (raw === '' || raw === null || raw === undefined) return true
  const n = Number(raw)
  return !Number.isFinite(n) || n < 0
}

function validateForm(): boolean {
  const errors: Record<string, string> = {}
  if (!form.name.trim()) errors.name = '请输入工具名称'
  if (!form.category) errors.category = '请选择类别'
  if (!form.damageLevel) errors.damageLevel = '请选择损坏程度'
  if (depositInvalid()) errors.deposit = '请输入有效押金'
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  Object.assign(formErrors, errors)
  return Object.keys(errors).length === 0
}

function resetForm() {
  editingId.value = null
  editingCode.value = ''
  Object.assign(form, {
    name: '',
    model: '',
    category: 'WX',
    damageLevel: 'NEW',
    deposit: 0,
    storageArea: '',
    usageGuide: '',
    imageUrls: [],
    purchaseDate: null,
    supplier: '',
    status: 'AVAILABLE',
  })
  guideSteps.value = [emptyStep()]
  legacyGuide.value = false
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: ToolVO) {
  editingId.value = row.id
  editingCode.value = row.code
  Object.assign(form, {
    name: row.name,
    model: row.model ?? '',
    category: row.category,
    damageLevel: row.damageLevel,
    deposit: row.deposit,
    storageArea: row.storageArea ?? '',
    usageGuide: row.usageGuide ?? '',
    imageUrls: row.imageUrls?.length ? [...row.imageUrls] : row.imageUrl ? [row.imageUrl] : [],
    purchaseDate: row.purchaseDate ?? null,
    supplier: row.supplier ?? '',
    status: row.status,
  })
  const parsed = parseGuide(row.usageGuide)
  guideSteps.value = parsed.steps
  legacyGuide.value = parsed.legacy
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!validateForm()) return
  saving.value = true
  try {
    const payload: ToolSaveDTO = {
      ...form,
      deposit: Number(form.deposit) || 0,
      usageGuide: serializeGuide(),
      imageUrls: [...form.imageUrls],
      purchaseDate: form.purchaseDate || null,
    }
    if (editingId.value) {
      await toolApi.update(editingId.value, payload)
      ElMessage.success('工具已更新')
    } else {
      await toolApi.create(payload)
      ElMessage.success('工具已新增')
    }
    dialogVisible.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

// —— 图片上传（原生 file input） ——
const imageFileRef = ref<HTMLInputElement>()
const stepFileRef = ref<HTMLInputElement>()
const stepUploadIndex = ref(-1)

function pickImage() {
  imageFileRef.value?.click()
}

function onImageChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  handleUpload(file)
}

function pickStepImage(index: number) {
  stepUploadIndex.value = index
  stepFileRef.value?.click()
}

function onStepFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const index = stepUploadIndex.value
  if (index < 0) return
  handleStepUpload(file, index)
}

async function handleUpload(file: File) {
  uploading.value = true
  try {
    const data = await uploadApi.uploadFile(file)
    form.imageUrls.push(data.url)
    ElMessage.success('图片上传成功')
  } finally {
    uploading.value = false
  }
}

function removeImage(index: number) {
  form.imageUrls.splice(index, 1)
}

async function handleStepUpload(file: File, index: number) {
  stepUploading.value = index
  try {
    const data = await uploadApi.uploadFile(file)
    guideSteps.value[index].imageUrl = data.url
    ElMessage.success('步骤图片上传成功')
  } finally {
    stepUploading.value = null
  }
}

// —— 图片预览 ——
const previewUrl = ref('')
function openPreview(url: string) {
  previewUrl.value = url
}

// —— 状态更新 ——
const statusDialogVisible = ref(false)
const statusSaving = ref(false)
const statusTarget = ref<ToolVO | null>(null)
const statusValue = ref<ToolStatus>('AVAILABLE')
const statusReason = ref('')

function openStatusDialog(row: ToolVO) {
  statusTarget.value = row
  statusValue.value = row.status
  statusReason.value = ''
  statusDialogVisible.value = true
}

async function submitStatus() {
  const target = statusTarget.value
  if (!target) return
  if (statusValue.value === target.status) {
    ElMessage.info('工具状态未变化')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将「${target.name}」状态更新为「${enumLabel(TOOL_STATUS_LABELS, statusValue.value)}」？`,
      '状态更新',
      { type: 'warning' },
    )
  } catch {
    return
  }
  statusSaving.value = true
  try {
    await toolApi.updateStatus(target.id, statusValue.value)
    ElMessage.success('状态已更新')
    statusDialogVisible.value = false
    await loadList()
  } finally {
    statusSaving.value = false
  }
}

// —— 操作日志 ——
const logDrawerVisible = ref(false)
const logLoading = ref(false)
const logs = ref<ToolLogVO[]>([])
const logToolName = ref('')

async function openLogs(row: ToolVO) {
  logToolName.value = row.name
  logDrawerVisible.value = true
  logLoading.value = true
  logs.value = []
  try {
    logs.value = await toolApi.logs(row.id)
  } finally {
    logLoading.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">TOOL INVENTORY</p>
        <h1>工具管理</h1>
        <p class="sub">维护工具档案、使用指南与状态，支持按分类、状态、存放区与维修范围筛选。</p>
      </div>
      <div class="actions">
        <button class="btn btn-primary" @click="openCreate">
          <Plus class="ic sm" />新增工具
        </button>
      </div>
    </div>

    <div class="filterbar reveal">
      <div class="f-search">
        <Search class="ic" />
        <input
          class="input"
          v-model="query.keyword"
          placeholder="搜索名称 / 编码 / 型号"
          @keyup.enter="handleSearch"
        />
      </div>
      <select class="select f-item" v-model="query.category">
        <option value="">全部类别</option>
        <option v-for="item in categoryOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <select class="select f-item" v-model="query.status">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <input
        class="input f-item"
        v-model="query.storageArea"
        placeholder="存放区域（如 A区-01）"
        @keyup.enter="handleSearch"
      />
      <select class="select f-item" v-model="query.orderBy">
        <option v-for="item in orderByOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <select class="select f-item" v-model="query.minRentalCount">
        <option value="">租赁次数不限</option>
        <option v-for="item in rentalCountOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
      <select class="select f-item" v-model="repairScope">
        <option value="">维修时间不限</option>
        <option v-for="item in repairScopeOptions" :key="item.value" :value="item.value">
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
      <span class="tiny">共 {{ total }} 条</span>
    </div>

    <div class="table-wrap reveal">
      <div class="table-scroll">
        <table class="tbl">
          <thead>
            <tr>
              <th>工具</th>
              <th>型号</th>
              <th>类别</th>
              <th>损坏程度</th>
              <th>状态</th>
              <th class="r">押金</th>
              <th>存放区域</th>
              <th class="c">租借</th>
              <th>最近维修</th>
              <th>创建时间</th>
              <th style="width: 168px">操作</th>
            </tr>
          </thead>
          <tbody v-if="loading">
            <tr v-for="i in 5" :key="`sk-${i}`">
              <td colspan="11"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
          <tbody v-else-if="!list.length">
            <tr>
              <td colspan="11">
                <div class="empty">
                  <Box class="e-ic" />
                  <h4>暂无工具数据</h4>
                  <p>点击右上角「新增工具」开始录入第一件工具。</p>
                </div>
              </td>
            </tr>
          </tbody>
          <tbody v-else>
            <tr v-for="row in visibleList" :key="row.id">
              <td>
                <div class="cell-tool">
                  <img
                    v-if="rowImage(row)"
                    :src="rowImage(row)"
                    class="thumb"
                    alt=""
                    @click="openPreview(rowImage(row))"
                  />
                  <span v-else class="thumb-ph">—</span>
                  <div>
                    <div class="cell-main">{{ row.name }}</div>
                    <div class="cell-sub code">{{ row.code }}</div>
                  </div>
                </div>
              </td>
              <td>{{ row.model || '—' }}</td>
              <td>{{ row.categoryName || enumLabel(TOOL_CATEGORY_LABELS, row.category) }}</td>
              <td>
                <span class="badge" :class="damageLevelBadge(row.damageLevel)">
                  {{ row.damageLevelName || enumLabel(DAMAGE_LEVEL_LABELS, row.damageLevel) }}
                </span>
              </td>
              <td>
                <span class="badge" :class="toolStatusBadge(row.status)">
                  <span class="dot"></span>{{ row.statusName || enumLabel(TOOL_STATUS_LABELS, row.status) }}
                </span>
              </td>
              <td class="r"><span class="money">{{ formatMoney(row.deposit) }}</span></td>
              <td>{{ row.storageArea || '—' }}</td>
              <td class="c num">{{ row.rentalCount ?? 0 }}</td>
              <td>
                <span v-if="row.lastRepairTime">{{ formatDateTime(row.lastRepairTime) }}</span>
                <span v-else class="muted">未维修</span>
              </td>
              <td>{{ formatDateTime(row.createTime) }}</td>
              <td>
                <div class="row-actions">
                  <button class="link-btn" @click="openEdit(row)">编辑</button>
                  <button class="link-btn" @click="openStatusDialog(row)">状态</button>
                  <button class="link-btn" @click="openLogs(row)">日志</button>
                  <button class="link-btn danger" @click="removeRow(row)">删除</button>
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

    <!-- 新增 / 编辑 -->
    <div class="mask show" v-if="dialogVisible" @click.self="dialogVisible = false">
      <div class="dlg wide">
        <div class="dlg-head">
          <div>
            <h3>{{ editingId ? '编辑工具' : '新增工具' }}</h3>
            <p class="d-sub">工具编码按类别自动生成，编辑时不可修改</p>
          </div>
          <button class="dlg-x" @click="dialogVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <div class="form-grid">
            <div class="field" :class="{ 'is-err': formErrors.name }">
              <label><span class="req">*</span>工具名称</label>
              <input class="input" v-model="form.name" maxlength="50" placeholder="请输入工具名称" />
              <p class="err">{{ formErrors.name }}</p>
            </div>
            <div class="field">
              <label>型号</label>
              <input class="input" v-model="form.model" maxlength="50" placeholder="请输入型号" />
            </div>
            <div class="field">
              <label>工具编码</label>
              <input class="input" :value="editingId ? editingCode : '保存后按类别自动生成'" disabled />
            </div>
            <div class="field" :class="{ 'is-err': formErrors.category }">
              <label><span class="req">*</span>类别</label>
              <select class="select" v-model="form.category">
                <option v-for="item in categoryOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
              <p class="err">{{ formErrors.category }}</p>
            </div>
            <div class="field" :class="{ 'is-err': formErrors.damageLevel }">
              <label><span class="req">*</span>损坏程度</label>
              <select class="select" v-model="form.damageLevel">
                <option v-for="item in damageLevelOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
              <p class="err">{{ formErrors.damageLevel }}</p>
            </div>
            <div class="field" :class="{ 'is-err': formErrors.deposit }">
              <label><span class="req">*</span>押金（元）</label>
              <input
                class="input"
                type="number"
                min="0"
                step="0.01"
                v-model.number="form.deposit"
                placeholder="0.00"
              />
              <p class="err">{{ formErrors.deposit }}</p>
            </div>
            <div class="field">
              <label>存放区域</label>
              <input class="input" v-model="form.storageArea" maxlength="50" placeholder="如 A区-01" />
            </div>
            <div class="field">
              <label>采购日期</label>
              <input class="input" type="date" v-model="form.purchaseDate" />
            </div>
            <div class="field">
              <label>供应商</label>
              <input class="input" v-model="form.supplier" maxlength="50" placeholder="请输入供应商" />
            </div>
            <div class="field">
              <label>状态</label>
              <select class="select" v-model="form.status">
                <option v-for="item in statusOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </div>

            <div class="field full">
              <label>使用指南</label>
              <div class="guide-editor">
                <div v-for="(step, index) in guideSteps" :key="index" class="g-step-block">
                  <div class="g-step-head">
                    <span class="g-num">步骤 {{ index + 1 }}</span>
                    <div class="g-step-actions">
                      <button class="link-btn" :disabled="index === 0" @click="moveStep(index, -1)">
                        <ArrowUp class="ic sm" />上移
                      </button>
                      <button
                        class="link-btn"
                        :disabled="index === guideSteps.length - 1"
                        @click="moveStep(index, 1)"
                      >
                        <ArrowDown class="ic sm" />下移
                      </button>
                      <button
                        class="link-btn danger"
                        :disabled="guideSteps.length === 1"
                        @click="removeStep(index)"
                      >
                        <Delete class="ic sm" />删除
                      </button>
                    </div>
                  </div>
                  <textarea
                    class="textarea"
                    v-model="step.text"
                    rows="2"
                    maxlength="500"
                    placeholder="这一步怎么操作、需要注意什么"
                  ></textarea>
                  <div class="g-step-image">
                    <img
                      v-if="step.imageUrl"
                      :src="resolveFileUrl(step.imageUrl)"
                      class="g-step-preview"
                      alt=""
                      @click="openPreview(resolveFileUrl(step.imageUrl))"
                    />
                    <button
                      class="btn btn-outline btn-sm"
                      :class="{ loading: stepUploading === index }"
                      :disabled="stepUploading !== null"
                      @click="pickStepImage(index)"
                    >
                      <Upload class="ic sm" />{{ step.imageUrl ? '更换图片' : '上传图片' }}
                    </button>
                    <button v-if="step.imageUrl" class="link-btn danger" @click="step.imageUrl = ''">
                      移除图片
                    </button>
                  </div>
                </div>
                <div class="guide-editor-footer">
                  <button class="btn btn-outline btn-sm" @click="addStep">
                    <Plus class="ic sm" />新增步骤
                  </button>
                  <span class="muted">每一步可只填文字、只放图片，或图文都有；用户端会按步骤顺序展示</span>
                </div>
                <div v-if="legacyGuide" class="legacy-note">
                  <InfoFilled class="ic sm" />
                  <span>检测到旧版纯文本说明，保存后将自动转换为分步骤格式</span>
                </div>
              </div>
            </div>

            <div class="field full">
              <label>工具图片</label>
              <div class="image-upload">
                <div v-if="form.imageUrls.length" class="image-list">
                  <div v-for="(url, index) in form.imageUrls" :key="`${url}-${index}`" class="image-item">
                    <img
                      :src="resolveFileUrl(url)"
                      class="image-preview"
                      alt=""
                      @click="openPreview(resolveFileUrl(url))"
                    />
                    <button class="image-remove" title="移除图片" @click="removeImage(index)">
                      <Delete class="ic sm" />
                    </button>
                  </div>
                </div>
                <div>
                  <button
                    class="btn btn-outline btn-sm"
                    :class="{ loading: uploading }"
                    :disabled="uploading"
                    @click="pickImage()"
                  >
                    <Upload class="ic sm" />上传图片
                  </button>
                  <div class="hint">支持 jpg、png 等图片格式，可上传多张。</div>
                </div>
              </div>
            </div>
          </div>

          <input ref="imageFileRef" type="file" accept="image/*" style="display: none" @change="onImageChange" />
          <input ref="stepFileRef" type="file" accept="image/*" style="display: none" @change="onStepFileChange" />
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="dialogVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: saving }" :disabled="saving" @click="handleSubmit">
            保存
          </button>
        </div>
      </div>
    </div>

    <!-- 状态更新 -->
    <div class="mask show" v-if="statusDialogVisible" @click.self="statusDialogVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>更新工具状态</h3>
            <p class="d-sub">
              {{ statusTarget?.name || '—' }} · 当前
              {{ statusTarget ? enumLabel(TOOL_STATUS_LABELS, statusTarget.status) : '—' }}
            </p>
          </div>
          <button class="dlg-x" @click="statusDialogVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <div class="field">
            <label>新状态</label>
            <select class="select" v-model="statusValue">
              <option v-for="item in statusOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div class="field">
            <label>变更原因</label>
            <textarea v-model="statusReason" class="textarea" placeholder="请填写变更原因（选填）" />
          </div>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="statusDialogVisible = false">取消</button>
          <button
            class="btn btn-primary"
            :class="{ loading: statusSaving }"
            :disabled="statusSaving"
            @click="submitStatus"
          >
            确认更新
          </button>
        </div>
      </div>
    </div>

    <!-- 操作日志抽屉 -->
    <div class="drawer-mask show" v-if="logDrawerVisible" @click.self="logDrawerVisible = false">
      <div class="drawer" style="width: 520px">
        <div class="dlg-head">
          <div>
            <h3>操作日志</h3>
            <p class="d-sub">{{ logToolName }}</p>
          </div>
          <button class="dlg-x" @click="logDrawerVisible = false"><Close class="ic sm" /></button>
        </div>
        <div class="dlg-body">
          <template v-if="logLoading">
            <div v-for="i in 4" :key="i" class="skel" style="height: 18px; margin-bottom: 14px"></div>
          </template>
          <ul v-else-if="logs.length" class="timeline">
            <li v-for="(log, index) in logs" :key="log.id" :class="{ done: index < logs.length - 1 }">
              <div class="tl-t">
                {{ enumLabel(TOOL_LOG_ACTION_LABELS, log.action) }}
                <span v-if="log.operatorId" class="badge neutral">操作员 {{ log.operatorId }}</span>
              </div>
              <div class="tl-time">{{ formatDateTime(log.createTime) }}</div>
              <div class="tl-d">{{ log.detail || '—' }}</div>
            </li>
          </ul>
          <div v-else class="empty">
            <Document class="e-ic" />
            <h4>暂无状态日志</h4>
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
.cell-tool {
  display: flex;
  align-items: center;
  gap: 11px;
}
.cell-tool .thumb {
  cursor: zoom-in;
  flex: none;
}
.thumb-ph {
  width: 44px;
  height: 44px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--surface-2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  font-size: var(--t-xs);
}

.guide-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.g-step-block {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--surface-2);
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.g-step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.g-num {
  height: 26px;
  padding: 0 10px;
  border-radius: 8px;
  background: var(--brand-bg);
  color: var(--brand-2);
  font-weight: 700;
  font-size: var(--t-xs);
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
}
.g-step-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
.g-step-actions .link-btn {
  font-size: var(--t-xs);
}
.g-step-actions .link-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.g-step-image {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.g-step-preview {
  width: 96px;
  height: 72px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}
.guide-editor-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: var(--t-xs);
  color: var(--ink-2);
}
.legacy-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-xs);
  color: var(--info);
  background: var(--info-bg);
  border-radius: 8px;
  padding: 9px 12px;
}

.image-upload {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.image-item {
  position: relative;
  width: 88px;
  height: 88px;
}
.image-preview {
  width: 88px;
  height: 88px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}
.image-remove {
  position: absolute;
  top: -7px;
  right: -7px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: none;
  background: var(--bad);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
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
