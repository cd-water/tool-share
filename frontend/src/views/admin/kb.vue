<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-kb">
      <div class="page-head">
        <div>
          <h1>知识库管理</h1>
          <p class="sub">
            上传工具手册、安全指引与租赁规则文档，解析后供 AI 问答检索
          </p>
        </div>
      </div>

      <div class="upload-card">
        <div
          class="upload-zone"
          :class="{ dragover: dragOver, busy: uploading }"
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
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <path d="M14 2v6h6M12 18v-6M9 15l3-3 3 3" />
          </svg>
          <div>
            <template v-if="uploading">文档上传中，请稍候…</template>
            <template v-else>将文档拖到此处，或 <b>点击上传</b></template>
          </div>
          <div class="upload-hint">支持 pdf / doc / docx / txt / md，单文件 ≤ 20MB，可多选</div>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept=".pdf,.doc,.docx,.txt,.md"
          multiple
          hidden
          @change="onFileChange"
        />
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !records.length" class="empty-box">
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无知识库文档' }}</p>
          <el-button v-if="failed" @click="load()">重新加载</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <el-table :data="records" row-key="id" class="kb-table">
              <el-table-column label="文档名称" min-width="220">
                <template #default="{ row }">
                  <span class="doc-name" :title="row.fileName">{{ row.fileName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="大小" width="100">
                <template #default="{ row }">
                  <span class="time">{{ fmtSize(row.fileSize) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="切分片段" width="100">
                <template #default="{ row }">
                  <span v-if="row.status === 2">{{ row.chunkCount }} 片</span>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="解析状态" width="120">
                <template #default="{ row }">
                  <span class="tag" :class="statusTag(row.status)[1]">
                    {{ statusTag(row.status)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="上传人" width="90">
                <template #default>管理员</template>
              </el-table-column>
              <el-table-column label="上传时间" width="150">
                <template #default="{ row }">
                  <span class="time">{{ fmtDateTime(row.createTime) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" align="right">
                <template #default="{ row }">
                  <button
                    type="button"
                    class="link-btn danger"
                    :disabled="deletingId === row.id"
                    @click="remove(row)"
                  >
                    {{ deletingId === row.id ? '删除中…' : '删除' }}
                  </button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="m-cards">
            <div v-for="row in records" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title" :title="row.fileName">{{ row.fileName }}</span>
                <span class="tag" :class="statusTag(row.status)[1]">
                  {{ statusTag(row.status)[0] }}
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">大小</span>
                <span class="mc-v">{{ fmtSize(row.fileSize) }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">切分片段</span>
                <span class="mc-v">{{ row.status === 2 ? `${row.chunkCount} 片` : '—' }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">上传人</span>
                <span class="mc-v">管理员</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">上传时间</span>
                <span class="mc-v">{{ fmtDateTime(row.createTime) }}</span>
              </div>
              <div class="mc-actions">
                <button
                  type="button"
                  class="link-btn danger"
                  :disabled="deletingId === row.id"
                  @click="remove(row)"
                >
                  {{ deletingId === row.id ? '删除中…' : '删除' }}
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
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { deleteKbDocument, pageKbDocuments, uploadKbDocument } from '../../api/ai'
import type { KbDocument } from '../../api/types'

const ALLOWED_EXT = ['.pdf', '.doc', '.docx', '.txt', '.md']
const MAX_SIZE = 20 * 1024 * 1024

const KB_STATUS: Record<number, [string, string]> = {
  1: ['解析中', 't-info'],
  2: ['完成', 't-success'],
  3: ['失败', 't-danger'],
}
const statusTag = (v: number) => KB_STATUS[v] ?? ['未知', 't-gray']

const fileInput = ref<HTMLInputElement>()
const records = ref<KbDocument[]>([])
const loading = ref(false)
const failed = ref(false)
const uploading = ref(false)
const dragOver = ref(false)
const deletingId = ref(0)
const page = ref(1)
const size = ref(10)
const total = ref(0)

let pollTimer: ReturnType<typeof setInterval> | undefined

function fmtSize(bytes?: number) {
  const n = Number(bytes || 0)
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${n} B`
}

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

async function load(silent = false) {
  if (!silent) loading.value = true
  failed.value = false
  try {
    const result = await pageKbDocuments({ page: page.value, size: size.value })
    records.value = result.records
    total.value = result.total
    ensurePolling()
  } catch {
    failed.value = true
  } finally {
    if (!silent) loading.value = false
  }
}

function ensurePolling() {
  if (pollTimer) return
  if (!records.value.some((d) => d.status === 1)) return
  pollTimer = setInterval(async () => {
    const result = await pageKbDocuments({ page: page.value, size: size.value }).catch(() => null)
    if (result) {
      records.value = result.records
      total.value = result.total
    }
    if (!records.value.some((d) => d.status === 1)) {
      clearInterval(pollTimer)
      pollTimer = undefined
    }
  }, 3000)
}

function pickFile() {
  if (uploading.value) return
  fileInput.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  await uploadFiles(files)
}

async function onDrop(e: DragEvent) {
  dragOver.value = false
  await uploadFiles(Array.from(e.dataTransfer?.files ?? []))
}

async function uploadFiles(files: File[]) {
  const valid = files.filter((file) => {
    const name = file.name.toLowerCase()
    if (!ALLOWED_EXT.some((ext) => name.endsWith(ext))) {
      ElMessage.warning(`不支持的文档格式：${file.name}`)
      return false
    }
    if (file.size > MAX_SIZE) {
      ElMessage.warning(`文档 ${file.name} 超过 20MB`)
      return false
    }
    return true
  })
  if (!valid.length) return

  uploading.value = true
  try {
    for (const file of valid) {
      const form = new FormData()
      form.append('file', file)
      await uploadKbDocument(form)
      ElMessage.success(`「${file.name}」已上传，正在解析`)
    }
  } catch {
    // 拦截器已统一提示
  } finally {
    uploading.value = false
    await load()
  }
}

async function remove(doc: KbDocument) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${doc.fileName}」吗？删除后其向量将从知识库移除，AI 无法再检索该文档内容。`,
      '删除文档',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  deletingId.value = doc.id
  try {
    await deleteKbDocument(doc.id)
    ElMessage.success('文档已删除')
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    deletingId.value = 0
  }
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

onMounted(() => load())
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.admin-kb {
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

/* ---------- 上传 ---------- */
.upload-card {
  margin-bottom: 14px;
}
.upload-zone {
  padding: 26px;
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
.upload-zone.busy {
  opacity: 0.7;
  cursor: wait;
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
.readonly-tip {
  margin-bottom: 14px;
  padding: 9px 12px;
  font-size: 12.5px;
  color: var(--info);
  background: var(--info-9);
  border: 1px solid #c7d8ef;
  border-radius: var(--r-2);
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
.kb-table {
  width: 100%;
  font-size: 13px;
}
.kb-table :deep(th.el-table__cell) {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--primary-3);
  background: var(--primary-tint);
}
.kb-table :deep(td.el-table__cell) {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}
.kb-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--surface-2);
}
.kb-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.kb-table :deep(.cell) {
  padding: 0;
  overflow: visible;
  text-overflow: clip;
  word-break: normal;
  white-space: normal;
}
.doc-name {
  display: block;
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.time {
  font-size: 12.5px;
  color: var(--text-2);
  white-space: nowrap;
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
.link-btn.danger {
  color: var(--danger);
}
.link-btn.danger:hover {
  color: #a93226;
  background: var(--danger-9);
}
.link-btn:disabled {
  color: var(--text-3);
  cursor: default;
  background: none;
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
.t-gray {
  background: var(--surface-3);
  color: var(--text-2);
  border-color: #e2e7e2;
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
.empty-box :deep(.el-button) {
  height: 32px;
  border-radius: var(--r-2);
  border-color: var(--border-2);
  color: var(--text-2);
  font-size: 13.5px;
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

@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .table-wrap {
    display: none;
  }
  .m-cards {
    display: block;
  }
  .pager :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
