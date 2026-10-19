<template>
  <el-config-provider :locale="zhCn">
    <div class="form-page">
      <div class="breadcrumb">
        <RouterLink to="/repairs">损坏报修</RouterLink>
        <span class="sep">/</span>
        <span class="cur">提交报修</span>
      </div>

      <div class="page-head">
        <div>
          <h1>提交报修</h1>
          <p class="sub">请如实描述损坏情况，工作人员将现场核验并安排维修</p>
        </div>
      </div>

      <div class="form-card" v-loading="rentalsLoading">
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent
        >
          <el-form-item label="关联租赁单" prop="rentalOrderId">
            <el-select
              v-model="form.rentalOrderId"
              class="full"
              placeholder="请选择租赁中的工具（已取件）"
              :disabled="!rentals.length"
              no-data-text="暂无可报修的已取件租赁单"
            >
              <el-option
                v-for="rental in rentals"
                :key="rental.id"
                :value="rental.id"
                :label="rentalLabel(rental)"
              />
            </el-select>
          </el-form-item>

          <div v-if="!rentalsLoading && !rentals.length" class="notice">
            暂无可报修的已取件租赁单，请先在「我的租赁」取件后再提交报修。
            <el-button link type="primary" @click="router.push('/rentals')">去我的租赁</el-button>
          </div>

          <el-form-item label="工具信息">
            <div v-if="selectedRental" class="tool-info">
              <svg
                class="tool-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
              </svg>
              <div>
                <div class="tool-name">{{ selectedRental.toolName }}</div>
                <div class="tool-meta">
                  <span class="chip-code">{{ selectedRental.toolCode }}</span>
                  <span class="tiny">租赁 {{ fmtDate(selectedRental.startTime) }} ~ {{ fmtDate(selectedRental.endTime) }}</span>
                </div>
              </div>
            </div>
            <div v-else class="tool-info placeholder">选择关联租赁单后显示工具信息</div>
          </el-form-item>

          <el-form-item label="损坏部位" prop="damagePart">
            <el-input
              v-model="form.damagePart"
              maxlength="50"
              show-word-limit
              placeholder="如：钻头夹头 / 电机 / 外壳（≤50 字）"
            />
          </el-form-item>

          <el-form-item label="损坏描述" prop="damageDesc">
            <el-input
              v-model="form.damageDesc"
              type="textarea"
              :rows="4"
              maxlength="500"
              placeholder="请描述损坏场景与现象，如：夹头无法夹紧钻头，钻孔时打滑（≤500 字）"
            />
            <div class="counter">{{ form.damageDesc.length }}/500</div>
          </el-form-item>

          <el-form-item label="材料图片">
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
                <div class="upload-hint">选填，最多 3 张，单张 ≤ 5MB，支持 jpg / png</div>
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

          <div class="form-actions">
            <el-button @click="router.push('/repairs')">取 消</el-button>
            <el-button
              type="primary"
              class="submit-btn"
              :loading="submitting"
              :disabled="uploading"
              @click="submit"
            >
              提交报修
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
import { ElMessage, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import type { FormInstance, FormRules } from 'element-plus'
import { pageRentals } from '../../api/rental'
import { createRepair } from '../../api/repair'
import { uploadFile } from '../../api/file'
import type { RentalOrderVO } from '../../api/types'

const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const fileInput = ref<HTMLInputElement>()
const form = reactive({
  rentalOrderId: undefined as number | undefined,
  damagePart: '',
  damageDesc: '',
})
const rules: FormRules = {
  rentalOrderId: [{ required: true, message: '请选择关联租赁单', trigger: 'change' }],
  damagePart: [
    { required: true, message: '请填写损坏部位', trigger: 'blur' },
    { max: 50, message: '损坏部位不能超过 50 字', trigger: 'blur' },
  ],
  damageDesc: [
    { required: true, message: '请填写损坏描述', trigger: 'blur' },
    { max: 500, message: '损坏描述不能超过 500 字', trigger: 'blur' },
  ],
}

const rentals = ref<RentalOrderVO[]>([])
const rentalsLoading = ref(false)
const selectedRental = computed(
  () => rentals.value.find((r) => r.id === form.rentalOrderId) ?? null,
)

interface UploadItem {
  url: string
  name: string
}
const images = ref<UploadItem[]>([])
const uploading = ref(false)
const dragOver = ref(false)
const submitting = ref(false)

function fmtDate(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 10)
}

function rentalLabel(rental: RentalOrderVO) {
  return `${rental.orderNo} · ${rental.toolName}（${fmtDate(rental.startTime)} ~ ${fmtDate(rental.endTime)}）`
}

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
    if (images.value.length >= 3) {
      ElMessage.warning('最多上传 3 张图片')
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

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !selectedRental.value) return
  if (uploading.value) {
    ElMessage.warning('图片上传中，请稍候')
    return
  }
  submitting.value = true
  try {
    const result = await createRepair({
      rentalOrderId: selectedRental.value.id,
      toolId: selectedRental.value.toolId,
      damagePart: form.damagePart.trim(),
      damageDesc: form.damageDesc.trim(),
      images: images.value.map((img) => img.url),
    })
    ElMessage.success(`报修已提交，单号 ${result.repairNo}`)
    router.push('/repairs')
  } catch {
    // 拦截器已统一提示，保留表单便于重试
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  rentalsLoading.value = true
  try {
    const result = await pageRentals({ page: 1, size: 200 })
    rentals.value = result.records.filter((r) => r.status === 3)
    const preselect = Number(route.query.rentalOrderId)
    if (preselect && rentals.value.some((r) => r.id === preselect)) {
      form.rentalOrderId = preselect
    }
  } catch {
    // 拦截器已统一提示
  } finally {
    rentalsLoading.value = false
  }
})
</script>

<style scoped>
.form-page {
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
  --danger: #c0392b;
  --info: #2b6cb0;
  --info-9: #e9f1fb;
  --r-1: 4px;
  --r-2: 6px;
  --r-3: 8px;
  --r-4: 12px;
  --shadow-1: 0 1px 2px rgba(16, 24, 40, 0.05);
  --font-mono: Consolas, Monaco, 'Courier New', monospace;

  max-width: 680px;
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

.form-card {
  padding: 22px 26px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  box-shadow: var(--shadow-1);
}

.form-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.form-card :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  line-height: 1.45;
}
.form-card :deep(.el-input__wrapper),
.form-card :deep(.el-select__wrapper) {
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
.full {
  width: 100%;
}
.counter {
  width: 100%;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.notice {
  margin: -6px 0 16px;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--info);
  background: var(--info-9);
  border: 1px solid #c7d8ef;
  border-radius: var(--r-2);
}
.notice :deep(.el-button) {
  vertical-align: baseline;
}

.tool-info {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 14px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-2);
}
.tool-info.placeholder {
  font-size: 12.5px;
  color: var(--text-3);
}
.tool-icon {
  width: 22px;
  height: 22px;
  flex: none;
  color: var(--primary-5);
}
.tool-name {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.45;
}
.tool-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 2px;
}
.chip-code {
  padding: 1px 6px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-1);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.tiny {
  font-size: 11.5px;
  color: var(--text-3);
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

/* ---------- 操作 ---------- */
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
  min-width: 120px;
}

@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .form-card {
    padding: 18px 16px;
  }
  .form-actions :deep(.el-button) {
    flex: 1;
  }
}
</style>
