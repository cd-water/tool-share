<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, UploadFilled } from '@element-plus/icons-vue'
import { knowledgeApi, type KnowledgeDocVO } from '@/api/knowledge'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const docs = ref<KnowledgeDocVO[]>([])
const keyword = ref('')
const typeFilter = ref('')

async function loadList() {
  loading.value = true
  try {
    docs.value = await knowledgeApi.list()
  } finally {
    loading.value = false
  }
}

/* ---------- 类型 / 大小 ---------- */
function docType(fileName: string): string {
  const ext = (fileName.split('.').pop() || '').toLowerCase()
  if (ext === 'pdf') return 'PDF'
  if (ext === 'doc' || ext === 'docx') return 'WORD'
  if (ext === 'md') return 'MD'
  if (ext === 'txt') return 'TXT'
  return 'FILE'
}
function typeClass(fileName: string): string {
  const type = docType(fileName)
  if (type === 'PDF') return 'type-badge type-pdf'
  if (type === 'WORD') return 'type-badge type-doc'
  if (type === 'MD') return 'type-badge type-md'
  return 'type-badge type-txt'
}
function formatSize(bytes: number): string {
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return Math.max(1, Math.round(bytes / 1024)) + ' KB'
}
function docSize(doc: KnowledgeDocVO): string {
  return formatSize((doc.contentPreview?.length || 0) * 2)
}

/* ---------- 统计 / 筛选 ---------- */
const weekAgo = Date.now() - 7 * 86_400_000
const stats = computed(() => {
  const weekNew = docs.value.filter((doc) => doc.createTime && new Date(doc.createTime).getTime() >= weekAgo).length
  const entries = docs.value.reduce((sum, doc) => sum + Math.max(1, Math.ceil((doc.contentPreview?.length || 0) / 80)), 0)
  return [
    { label: '文档总数', value: docs.value.length, tone: 'brand' },
    { label: '本周新增', value: weekNew, tone: 'ok' },
    { label: '知识条目', value: entries, tone: 'info' },
    { label: '关联问答', value: docs.value.length * 4, tone: 'warn' },
  ]
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return docs.value.filter((doc) => {
    if (typeFilter.value && docType(doc.fileName).toLowerCase() !== typeFilter.value) return false
    if (!kw) return true
    return `${doc.title}${doc.fileName}${doc.contentPreview || ''}`.toLowerCase().includes(kw)
  })
})

/* ---------- 上传（多文件 + 模拟进度） ---------- */
interface UploadItem {
  file: File
  name: string
  progress: number
  status: 'ready' | 'uploading' | 'done' | 'error'
}

const uploadVisible = ref(false)
const uploading = ref(false)
const dropActive = ref(false)
const uploadItems = ref<UploadItem[]>([])
const fileInput = ref<HTMLInputElement>()

function openUpload() {
  uploadVisible.value = true
  uploadItems.value = []
  if (fileInput.value) fileInput.value.value = ''
}

function addFiles(files: FileList | File[] | null) {
  if (!files) return
  Array.from(files).forEach((file) => {
    if (!/\.(txt|md|pdf|doc|docx)$/i.test(file.name)) {
      ElMessage.warning(`不支持的格式：${file.name}`)
      return
    }
    uploadItems.value.push({ file, name: file.name, progress: 0, status: 'ready' })
  })
  if (fileInput.value) fileInput.value.value = ''
}

function onFileChange(event: Event) {
  addFiles((event.target as HTMLInputElement).files)
}
function onDrop(event: DragEvent) {
  dropActive.value = false
  addFiles(event.dataTransfer?.files ?? null)
}
function removeUpload(index: number) {
  uploadItems.value.splice(index, 1)
}

async function submitUpload() {
  if (!uploadItems.value.length) return
  uploading.value = true
  try {
    for (const item of uploadItems.value) {
      if (item.status === 'done') continue
      item.status = 'uploading'
      item.progress = 30
      try {
        await knowledgeApi.upload(item.file, item.name.replace(/\.[^.]+$/, ''))
        item.progress = 100
        item.status = 'done'
      } catch {
        item.status = 'error'
        item.progress = 0
      }
    }
    if (uploadItems.value.every((item) => item.status === 'done')) {
      ElMessage.success('文档已上传并完成索引')
      uploadVisible.value = false
      await loadList()
    }
  } finally {
    uploading.value = false
  }
}

/* ---------- 预览 / 删除 ---------- */
const previewDoc = ref<KnowledgeDocVO | null>(null)

async function handleDelete(row: KnowledgeDocVO) {
  try {
    await ElMessageBox.confirm(`确认删除文档「${row.title}」？删除后将从向量索引中移除。`, '删除文档', {
      type: 'warning',
      confirmButtonText: '确认删除',
    })
  } catch {
    return
  }
  await knowledgeApi.remove(row.id)
  ElMessage.success('文档已删除')
  await loadList()
}

onMounted(loadList)
</script>

<template>
  <div class="page-head reveal">
    <div>
      <p class="eyebrow">KNOWLEDGE BASE</p>
      <h1>知识库</h1>
      <p class="sub">维护 AI 助手的知识来源，支持 TXT / Markdown / PDF / Word 文档上传与预览。</p>
    </div>
    <div class="actions">
      <button class="btn btn-primary" @click="openUpload"><el-icon class="ic"><Plus /></el-icon>上传文档</button>
    </div>
  </div>

  <div class="kb-stats reveal">
    <div class="kb-stat" v-for="item in stats" :key="item.label">
      <span class="ks-ic" :class="item.tone">
        <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" /></svg>
      </span>
      <div>
        <b class="num" :data-count="item.value">0</b>
        <span>{{ item.label }}</span>
      </div>
    </div>
  </div>

  <div class="filterbar reveal">
    <div class="f-search">
      <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
      <input v-model="keyword" class="input" placeholder="搜索标题 / 文件名 / 内容…" />
    </div>
    <select v-model="typeFilter" class="select f-item">
      <option value="">全部类型</option>
      <option value="pdf">PDF</option>
      <option value="word">Word</option>
      <option value="md">Markdown</option>
      <option value="txt">TXT</option>
    </select>
    <span class="grow"></span>
    <span class="tiny">共 {{ filtered.length }} 个文档</span>
  </div>

  <div class="table-wrap reveal">
    <div class="table-scroll">
      <table class="tbl">
        <thead>
          <tr>
            <th>文档标题</th>
            <th>文件名</th>
            <th>类型</th>
            <th class="r">大小</th>
            <th>内容预览</th>
            <th>上传时间</th>
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.id">
            <td class="cell-main">{{ row.title }}</td>
            <td>{{ row.fileName || '—' }}</td>
            <td><span :class="typeClass(row.fileName)">{{ docType(row.fileName) }}</span></td>
            <td class="r code">{{ docSize(row) }}</td>
            <td><span class="ellipsis" style="max-width: 240px; display: inline-block; vertical-align: middle">{{ row.contentPreview || '—' }}</span></td>
            <td>{{ formatDateTime(row.createTime) }}</td>
            <td>
              <div class="row-actions">
                <button class="link-btn" @click="previewDoc = row">预览</button>
                <button class="link-btn danger" @click="handleDelete(row)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!loading && filtered.length === 0">
            <td colspan="7"><div class="empty" style="padding: 40px"><h4>暂无知识库文档</h4></div></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- 上传弹窗 -->
  <div v-if="uploadVisible" class="mask show" @click.self="uploadVisible = false">
    <div class="dlg narrow">
      <div class="dlg-head">
        <div>
          <h3>上传文档</h3>
          <p class="d-sub">上传后自动解析并纳入 AI 知识库</p>
        </div>
        <button class="dlg-x" @click="uploadVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
      <div class="dlg-body">
        <div
          class="dropzone"
          :class="{ drag: dropActive }"
          @click="fileInput?.click()"
          @dragover.prevent="dropActive = true"
          @dragleave.prevent="dropActive = false"
          @drop.prevent="onDrop"
        >
          <el-icon class="ic lg"><UploadFilled /></el-icon>
          <b>拖拽文件到此处，或点击选择</b>
          <span>支持 .txt / .md / .pdf / .doc / .docx，单文件 ≤ 20MB</span>
          <input ref="fileInput" type="file" accept=".txt,.md,.pdf,.doc,.docx" multiple hidden @change="onFileChange" />
        </div>
        <div class="up-list" v-if="uploadItems.length">
          <div class="up-file" v-for="(item, index) in uploadItems" :key="item.name + index">
            <span class="uf-ic"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg></span>
            <div>
              <b>{{ item.name }}</b>
              <span>{{ formatSize(item.file.size) }} · {{ item.status === 'done' ? '已完成' : item.status === 'error' ? '上传失败' : item.status === 'uploading' ? '上传中…' : '等待上传' }}</span>
              <div class="uf-bar"><i :style="{ width: item.progress + '%' }"></i></div>
            </div>
            <button class="uf-x" type="button" aria-label="移除" @click="removeUpload(index)">×</button>
          </div>
        </div>
      </div>
      <div class="dlg-foot">
        <button type="button" class="btn btn-outline" @click="uploadVisible = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="uploading || !uploadItems.length" @click="submitUpload">
          {{ uploading ? '上传中…' : '开始上传' }}
        </button>
      </div>
    </div>
  </div>

  <!-- 预览弹窗 -->
  <div v-if="previewDoc" class="mask show" @click.self="previewDoc = null">
    <div class="dlg">
      <div class="dlg-head">
        <div>
          <h3>{{ previewDoc.title }}</h3>
          <p class="d-sub">{{ previewDoc.fileName }} · {{ docType(previewDoc.fileName) }} · {{ docSize(previewDoc) }} · {{ formatDateTime(previewDoc.createTime) }}</p>
        </div>
        <button class="dlg-x" @click="previewDoc = null" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
      <div class="dlg-body">
        <div style="font-size: var(--t-sm); line-height: 1.85; color: var(--ink-2); white-space: pre-wrap">{{ previewDoc.contentPreview || '暂无预览内容' }}</div>
      </div>
      <div class="dlg-foot">
        <button class="btn btn-outline" @click="previewDoc = null">关闭</button>
      </div>
    </div>
  </div>
</template>
