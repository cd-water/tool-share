<template>
  <el-config-provider :locale="zhCn">
    <div class="tool-form-page">
      <div class="breadcrumb">
        <RouterLink to="/admin/tools">工具管理</RouterLink>
        <span class="sep">/</span>
        <span class="cur">{{ isEdit ? '编辑工具' : '新增工具' }}</span>
      </div>

      <div class="page-head">
        <div>
          <h1>{{ isEdit ? '编辑工具' : '新增工具' }}</h1>
          <p class="sub">
            {{ isEdit ? '编号不可修改；修改后需管理员重新审核' : '工具编号按 SQ-G-XX-YYYY 自动生成，提交后待管理员审核' }}
          </p>
        </div>
        <div v-if="toolCode" class="code-line">
          <span class="tiny">工具编号</span>
          <span class="chip-code">{{ toolCode }}</span>
        </div>
      </div>

      <div v-loading="loading" class="form-card">
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent
        >
          <div class="form-grid">
            <el-form-item class="span2" label="工具名称" prop="name">
              <el-input v-model="form.name" maxlength="50" placeholder="如：博世充电电钻 GSB 120-LI" />
            </el-form-item>

            <el-form-item label="型号规格" prop="modelSpec">
              <el-input v-model="form.modelSpec" maxlength="100" placeholder="如：12V 双电池版" />
            </el-form-item>

            <el-form-item label="采购日期" prop="purchaseDate">
              <el-date-picker
                v-model="form.purchaseDate"
                class="full"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择采购日期"
              />
            </el-form-item>

            <el-form-item label="类别" prop="category">
              <el-select v-model="form.category" class="full" placeholder="请选择类别">
                <el-option label="维修工具" :value="1" />
                <el-option label="园艺工具" :value="2" />
                <el-option label="清洁工具" :value="3" />
                <el-option label="其他工具" :value="4" />
              </el-select>
            </el-form-item>

            <el-form-item label="损坏程度" prop="damageLevel">
              <el-select v-model="form.damageLevel" class="full" placeholder="请选择损坏程度">
                <el-option label="全新" :value="1" />
                <el-option label="轻微磨损" :value="2" />
                <el-option label="中度磨损" :value="3" />
                <el-option label="严重损坏" :value="4" />
              </el-select>
            </el-form-item>

            <el-form-item label="押金金额（元）" prop="deposit">
              <el-input-number
                v-model="form.deposit"
                class="full"
                :min="0"
                :max="999999"
                :precision="2"
                controls-position="right"
              />
            </el-form-item>

            <el-form-item label="存放区域" prop="storageLocation">
              <el-input v-model="form.storageLocation" maxlength="100" placeholder="如：社区服务站 2 号工具柜" />
            </el-form-item>

            <el-form-item label="供应商信息" prop="supplierInfo">
              <el-input v-model="form.supplierInfo" maxlength="100" placeholder="选填" />
            </el-form-item>

            <el-form-item class="span2" label="使用说明" prop="usageInstruction">
              <el-input
                v-model="form.usageInstruction"
                type="textarea"
                :rows="4"
                maxlength="1000"
                placeholder="适用场景、操作步骤、安全提示等"
              />
            </el-form-item>

            <el-form-item class="span2" label="工具图片">
              <div class="upload-block">
                <div
                  class="upload-zone"
                  :class="{ dragover: dragOver }"
                  role="button"
                  tabindex="0"
                  @click="pickFile"
                  @keyup.enter="pickFile"
                  @dragover.prevent="dragOver = true"
                  @dragenter.prevent="dragOver = true"
                  @dragleave.prevent="dragOver = false"
                  @drop.prevent="onDrop"
                >
                  <svg
                    class="upload-icon"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    aria-hidden="true"
                  >
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                    <path d="M17 8l-5-5-5 5M12 3v12" />
                  </svg>
                  <div>将图片拖到此处，或 <b>点击上传</b></div>
                  <div class="upload-hint">支持 jpg / png，单张 ≤ 5MB，建议 1:1 主图，最多 6 张</div>
                </div>
                <input
                  ref="fileInput"
                  type="file"
                  accept="image/*"
                  multiple
                  hidden
                  @change="onFileChange"
                />

                <div v-if="images.length || uploading" class="img-list">
                  <div v-for="(img, index) in images" :key="img.url + index" class="img-thumb">
                    <img :src="img.url" :alt="img.name" />
                    <button type="button" class="del" aria-label="删除图片" @click="removeImage(index)">
                      ×
                    </button>
                  </div>
                  <div v-if="uploading" class="img-thumb uploading">
                    <span class="spinner" />
                    <span class="tiny">上传中</span>
                  </div>
                </div>
              </div>
            </el-form-item>
          </div>

          <div class="form-actions">
            <el-button @click="router.push('/admin/tools')">取 消</el-button>
            <el-button
              type="primary"
              class="submit-btn"
              :loading="submitting"
              :disabled="uploading"
              @click="submit"
            >
              {{ isEdit ? '保存修改' : '保存并提交审核' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import type { FormInstance, FormRules } from 'element-plus'
import { createTool, getTool, updateTool } from '../../api/tool'
import { uploadFile } from '../../api/file'
import type { ToolDTO } from '../../api/types'

const MAX_IMAGES = 6

const route = useRoute()
const router = useRouter()

const editId = computed(() => Number(route.query.id) || 0)
const isEdit = computed(() => editId.value > 0)

const formRef = ref<FormInstance>()
const fileInput = ref<HTMLInputElement>()
const loading = ref(false)
const submitting = ref(false)
const toolCode = ref('')

const form = reactive({
  name: '',
  modelSpec: '',
  category: '' as number | '',
  damageLevel: '' as number | '',
  deposit: 0,
  storageLocation: '',
  usageInstruction: '',
  purchaseDate: '',
  supplierInfo: '',
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入工具名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  damageLevel: [{ required: true, message: '请选择损坏程度', trigger: 'change' }],
  deposit: [{ required: true, message: '请输入押金金额', trigger: 'blur' }],
  storageLocation: [{ required: true, message: '请输入存放区域', trigger: 'blur' }],
}

interface UploadItem {
  url: string
  name: string
}
const images = ref<UploadItem[]>([])
const uploading = ref(false)
const dragOver = ref(false)

function pickFile() {
  fileInput.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  await addFiles(files)
}

async function onDrop(e: DragEvent) {
  dragOver.value = false
  await addFiles(Array.from(e.dataTransfer?.files ?? []))
}

async function addFiles(files: File[]) {
  for (const file of files) {
    if (images.value.length >= MAX_IMAGES) {
      ElMessage.warning(`最多上传 ${MAX_IMAGES} 张图片`)
      break
    }
    if (!file.type.startsWith('image/')) {
      ElMessage.warning(`仅支持图片文件：${file.name}`)
      continue
    }
    if (file.size > 5 * 1024 * 1024) {
      ElMessage.warning(`图片 ${file.name} 超过 5MB`)
      continue
    }
    uploading.value = true
    try {
      const data = new FormData()
      data.append('file', file)
      const res = await uploadFile(data)
      images.value.push({ url: res.url, name: file.name })
    } catch {
      // 拦截器已统一提示
    } finally {
      uploading.value = false
    }
  }
}

function removeImage(index: number) {
  images.value.splice(index, 1)
}

function buildDTO(): ToolDTO {
  return {
    name: form.name.trim(),
    modelSpec: form.modelSpec.trim(),
    category: form.category as number,
    damageLevel: form.damageLevel as number,
    deposit: form.deposit,
    storageLocation: form.storageLocation.trim(),
    usageInstruction: form.usageInstruction.trim(),
    images: images.value.map((img) => img.url),
    purchaseDate: form.purchaseDate || undefined,
    supplierInfo: form.supplierInfo.trim() || undefined,
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (uploading.value) {
    ElMessage.warning('图片上传中，请稍候')
    return
  }

  submitting.value = true
  try {
    if (isEdit.value) {
      await updateTool(editId.value, buildDTO())
      ElMessage.success('工具信息已更新')
      router.push('/admin/tools')
      return
    }

    const result = await createTool(buildDTO())
    await ElMessageBox.alert(
      `工具录入成功，自动编号：${result.toolCode}`,
      '录入成功',
      { confirmButtonText: '返回工具管理', type: 'success' },
    ).catch(() => {})
    router.push('/admin/tools')
  } catch {
    // 拦截器已统一提示，保留表单便于重试
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!isEdit.value) return
  loading.value = true
  try {
    const tool = await getTool(editId.value)
    form.name = tool.name || ''
    form.modelSpec = tool.modelSpec || ''
    form.category = tool.category ?? ''
    form.damageLevel = tool.damageLevel ?? ''
    form.deposit = tool.deposit ?? 0
    form.storageLocation = tool.storageLocation || ''
    form.usageInstruction = tool.usageInstruction || ''
    form.purchaseDate = tool.purchaseDate || ''
    form.supplierInfo = tool.supplierInfo || ''
    images.value = (tool.images ?? []).map((url, index) => ({
      url,
      name: `图片 ${index + 1}`,
    }))
    toolCode.value = tool.toolCode
  } catch {
    // 拦截器已统一提示
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.tool-form-page {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
  --surface: #fff;
  --surface-2: #f8faf8;
  --border: #e6eae6;
  --border-2: #d4dbd6;
  --text: #1e2b26;
  --text-2: #5a6a63;
  --text-3: #93a29b;
  --danger: #c0392b;
  --r-1: 4px;
  --r-2: 6px;
  --r-3: 8px;
  --r-4: 12px;
  --shadow-1: 0 1px 2px rgba(16, 24, 40, 0.05);
  --font-mono: Consolas, Monaco, 'Courier New', monospace;

  max-width: 820px;
  margin: 0 auto;
  color: var(--text);
}

.breadcrumb {
  margin-bottom: 14px;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.45;
}
.breadcrumb a {
  color: var(--primary-3);
  text-decoration: none;
}
.breadcrumb a:hover {
  color: var(--primary);
  text-decoration: underline;
}
.breadcrumb .sep {
  margin: 0 7px;
  color: var(--text-3);
}
.breadcrumb .cur {
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
.code-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.form-card {
  padding: 22px 26px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  box-shadow: var(--shadow-1);
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.form-grid .span2 {
  grid-column: 1 / -1;
}

.form-card :deep(.el-form-item) {
  margin-bottom: 16px;
}
.form-card :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  line-height: 1.45;
}
.form-card :deep(.el-input__wrapper),
.form-card :deep(.el-select__wrapper),
.form-card :deep(.el-date-editor .el-input__wrapper) {
  min-height: 32px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
}
.form-card :deep(.el-input__wrapper:hover),
.form-card :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-7) inset;
}
.form-card :deep(.el-input__wrapper.is-focus),
.form-card :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.form-card :deep(.el-input__inner),
.form-card :deep(.el-textarea__inner) {
  font-size: 13.5px;
  color: var(--text);
}
.form-card :deep(.el-textarea__inner) {
  padding: 7px 10px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
  line-height: 1.7;
}
.form-card :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.form-card :deep(.el-input-number) {
  width: 100%;
}
.full {
  width: 100%;
}

/* ---------- 上传 ---------- */
.upload-block {
  width: 100%;
}
.upload-zone {
  padding: 18px;
  font-size: 13.5px;
  line-height: 1.45;
  color: var(--text-2);
  text-align: center;
  background: var(--surface-2);
  border: 1px dashed var(--border-2);
  border-radius: var(--r-3);
  cursor: pointer;
  transition: border-color 120ms, background 120ms, color 120ms;
}
.upload-zone:hover,
.upload-zone.dragover {
  color: var(--primary);
  background: var(--primary-tint);
  border-color: var(--primary-5);
}
.upload-icon {
  width: 22px;
  height: 22px;
  margin: 0 auto 5px;
}
.upload-hint {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--text-3);
}
.img-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.img-thumb {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 84px;
  height: 84px;
  overflow: hidden;
  background: linear-gradient(135deg, #f0f4f1, #e4eae5);
  border: 1px solid var(--border);
  border-radius: var(--r-2);
}
.img-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.img-thumb .del {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 17px;
  height: 17px;
  padding: 0;
  font-size: 11px;
  line-height: 17px;
  color: #fff;
  background: rgba(23, 32, 29, 0.55);
  border: none;
  border-radius: 50%;
  cursor: pointer;
}
.img-thumb .del:hover {
  background: var(--danger);
}
.img-thumb.uploading {
  flex-direction: column;
  gap: 4px;
}
.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--primary-7);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 操作 / 文本 ---------- */
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}
.form-actions :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.form-actions :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.form-actions :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}
.submit-btn {
  min-width: 140px;
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
.tiny {
  font-size: 11.5px;
  color: var(--text-3);
}

@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .form-card {
    padding: 18px 16px;
  }
  .form-actions :deep(.el-button) {
    flex: 1;
  }
}
</style>
