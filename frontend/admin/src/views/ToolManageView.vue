<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules, UploadRequestOptions } from 'element-plus'
import { Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  toolApi,
  type ToolLogVO,
  type ToolQuery,
  type ToolSaveDTO,
  type ToolStatus,
  type ToolVO,
} from '@/api/tools'
import { uploadApi } from '@/api/upload'
import { cleanParams } from '@/utils/params'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
import {
  DAMAGE_LEVEL_LABELS,
  TOOL_CATEGORY_LABELS,
  TOOL_LOG_ACTION_LABELS,
  TOOL_STATUS_LABELS,
  damageLevelTag,
  enumLabel,
  optionList,
  toolStatusTag,
} from '@/utils/enums'

const loading = ref(false)
const list = ref<ToolVO[]>([])
const total = ref(0)
const query = reactive<ToolQuery>({
  page: 1,
  size: 10,
  keyword: '',
  category: '',
  status: '',
  storageArea: '',
})

const categoryOptions = optionList(TOOL_CATEGORY_LABELS)
const damageLevelOptions = optionList(DAMAGE_LEVEL_LABELS)
const statusOptions = optionList(TOOL_STATUS_LABELS)

async function loadList() {
  loading.value = true
  try {
    const data = await toolApi.list(cleanParams({ ...query }))
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
  query.page = 1
  loadList()
}

function handleSizeChange() {
  query.page = 1
  loadList()
}

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const editingCode = ref('')
const formRef = ref<FormInstance>()
const uploading = ref(false)

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

const rules: FormRules = {
  name: [{ required: true, message: '请输入工具名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  damageLevel: [{ required: true, message: '请选择损坏程度', trigger: 'change' }],
  deposit: [{ required: true, message: '请输入押金', trigger: 'blur' }],
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
  formRef.value?.clearValidate()
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
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload: ToolSaveDTO = {
      ...form,
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

async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const data = await uploadApi.uploadFile(options.file)
    form.imageUrls.push(data.url)
    ElMessage.success('图片上传成功')
  } finally {
    uploading.value = false
  }
}

function removeImage(index: number) {
  form.imageUrls.splice(index, 1)
}

const statusDialogVisible = ref(false)
const statusSaving = ref(false)
const statusTarget = ref<ToolVO | null>(null)
const statusValue = ref<ToolStatus>('AVAILABLE')

function openStatusDialog(row: ToolVO) {
  statusTarget.value = row
  statusValue.value = row.status
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
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="编码 / 名称 / 型号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="query.category" placeholder="全部类别" clearable style="width: 140px">
            <el-option
              v-for="item in categoryOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
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
        <el-form-item label="存放区域">
          <el-input
            v-model="query.storageArea"
            placeholder="如 A区-01"
            clearable
            style="width: 150px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button type="primary" plain :icon="Plus" @click="openCreate">新增工具</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="loading"
        :data="list"
        stripe
        empty-text="暂无工具数据"
        row-key="id"
      >
        <el-table-column label="图片" width="76" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.imageUrl"
              :src="resolveFileUrl(row.imageUrl)"
              :preview-src-list="[resolveFileUrl(row.imageUrl)]"
              fit="cover"
              preview-teleported
              class="thumb"
            />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="工具编码" width="150" />
        <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column prop="model" label="型号" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '—' }}</template>
        </el-table-column>
        <el-table-column label="类别" width="90" align="center">
          <template #default="{ row }">
            {{ row.categoryName || enumLabel(TOOL_CATEGORY_LABELS, row.category) }}
          </template>
        </el-table-column>
        <el-table-column label="损坏程度" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="damageLevelTag(row.damageLevel)" effect="plain">
              {{ row.damageLevelName || enumLabel(DAMAGE_LEVEL_LABELS, row.damageLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="toolStatusTag(row.status)">
              {{ row.statusName || enumLabel(TOOL_STATUS_LABELS, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="押金" width="110" align="right">
          <template #default="{ row }">
            <span class="amount">{{ formatMoney(row.deposit) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="storageArea" label="存放区域" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.storageArea || '—' }}</template>
        </el-table-column>
        <el-table-column prop="rentalCount" label="租借次数" width="90" align="center">
          <template #default="{ row }">{{ row.rentalCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openStatusDialog(row)">状态</el-button>
            <el-button link type="primary" @click="openLogs(row)">日志</el-button>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑工具' : '新增工具'"
      width="720px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="工具名称" prop="name">
              <el-input v-model="form.name" maxlength="50" placeholder="请输入工具名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="型号">
              <el-input v-model="form.model" maxlength="50" placeholder="请输入型号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工具编码">
              <el-input
                :model-value="editingId ? editingCode : '保存后按类别自动生成'"
                disabled
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="类别" prop="category">
              <el-select v-model="form.category" style="width: 100%">
                <el-option
                  v-for="item in categoryOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="损坏程度" prop="damageLevel">
              <el-select v-model="form.damageLevel" style="width: 100%">
                <el-option
                  v-for="item in damageLevelOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="押金" prop="deposit">
              <el-input-number
                v-model="form.deposit"
                :min="0"
                :max="99999"
                :precision="2"
                :step="10"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="存放区域">
              <el-input v-model="form.storageArea" maxlength="50" placeholder="如 A区-01" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="采购日期">
              <el-date-picker
                v-model="form.purchaseDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择采购日期"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="供应商">
              <el-input v-model="form.supplier" maxlength="50" placeholder="请输入供应商" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option
                  v-for="item in statusOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="使用说明">
          <el-input
            v-model="form.usageGuide"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请填写工具使用说明或注意事项"
          />
        </el-form-item>
        <el-form-item label="工具图片">
          <div class="image-upload">
            <div v-if="form.imageUrls.length" class="image-list">
              <div v-for="(url, index) in form.imageUrls" :key="`${url}-${index}`" class="image-item">
                <el-image
                  :src="resolveFileUrl(url)"
                  :preview-src-list="form.imageUrls.map(resolveFileUrl)"
                  :initial-index="index"
                  fit="cover"
                  preview-teleported
                  class="image-preview"
                />
                <el-button
                  class="image-remove"
                  type="danger"
                  size="small"
                  circle
                  :icon="Delete"
                  @click="removeImage(index)"
                />
              </div>
            </div>
            <el-upload
              :show-file-list="false"
              :http-request="handleUpload"
              accept="image/*"
              :disabled="uploading"
            >
              <el-button :loading="uploading">上传图片</el-button>
            </el-upload>
            <div class="form-tip">支持 jpg、png 等图片格式，可上传多张。</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="statusDialogVisible" title="更新工具状态" width="420px">
      <el-form label-width="80px">
        <el-form-item label="工具">
          <span>{{ statusTarget?.name || '—' }}</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="statusValue" style="width: 100%">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="statusSaving" @click="submitStatus">确认更新</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="logDrawerVisible" :title="`状态日志 · ${logToolName}`" size="440px">
      <div v-loading="logLoading" class="log-body">
        <el-timeline v-if="logs.length">
          <el-timeline-item
            v-for="log in logs"
            :key="log.id"
            :timestamp="formatDateTime(log.createTime)"
            placement="top"
          >
            <div class="log-action">{{ enumLabel(TOOL_LOG_ACTION_LABELS, log.action) }}</div>
            <div class="log-detail">{{ log.detail || '—' }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无状态日志" :image-size="100" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.thumb {
  width: 40px;
  height: 40px;
  border-radius: 4px;
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
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
}

.image-remove {
  position: absolute;
  top: -6px;
  right: -6px;
}

.log-body {
  min-height: 120px;
}

.log-action {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.log-detail {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
