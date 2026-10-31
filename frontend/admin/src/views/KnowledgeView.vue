<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadInstance } from 'element-plus'
import { Delete, Plus, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { knowledgeApi, type KnowledgeDocVO } from '@/api/knowledge'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const docs = ref<KnowledgeDocVO[]>([])

async function loadList() {
  loading.value = true
  try {
    docs.value = await knowledgeApi.list()
  } finally {
    loading.value = false
  }
}

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadRef = ref<UploadInstance>()
const uploadTitle = ref('')
const selectedFile = ref<File | null>(null)
const selectedFileName = ref('')

function openUpload() {
  uploadVisible.value = true
  uploadTitle.value = ''
  selectedFile.value = null
  selectedFileName.value = ''
  uploadRef.value?.clearFiles()
}

function handleFileChange(file: UploadFile) {
  selectedFile.value = file.raw ?? null
  selectedFileName.value = file.name
  if (!uploadTitle.value.trim()) {
    uploadTitle.value = file.name.replace(/\.[^.]+$/, '')
  }
}

function handleFileRemove() {
  selectedFile.value = null
  selectedFileName.value = ''
}

async function submitUpload() {
  const title = uploadTitle.value.trim()
  if (!title) {
    ElMessage.warning('请输入文档标题')
    return
  }
  if (!selectedFile.value) {
    ElMessage.warning('请选择要上传的文档')
    return
  }
  uploading.value = true
  try {
    await knowledgeApi.upload(selectedFile.value, title)
    ElMessage.success('文档已上传并完成索引')
    uploadVisible.value = false
    await loadList()
  } finally {
    uploading.value = false
  }
}

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
  <div class="page">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      title="上传文档后系统会自动解析并建立向量索引；外部 AI 服务不可用时，仍可通过关键词检索知识库内容。"
    />

    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <span class="toolbar-title">知识库文档</span>
        <div>
          <el-button :icon="Refresh" :loading="loading" @click="loadList">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="openUpload">上传文档</el-button>
        </div>
      </div>
      <el-table
        v-loading="loading"
        :data="docs"
        stripe
        empty-text="暂无知识库文档"
        row-key="id"
      >
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fileName || '—' }}</template>
        </el-table-column>
        <el-table-column
          prop="contentPreview"
          label="内容预览"
          min-width="260"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ row.contentPreview || '—' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :icon="Delete" @click="handleDelete(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="uploadVisible" title="上传知识库文档" width="520px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="文档标题" required>
          <el-input
            v-model="uploadTitle"
            maxlength="80"
            placeholder="请输入文档标题"
          />
        </el-form-item>
        <el-form-item label="文档文件" required>
          <el-upload
            ref="uploadRef"
            drag
            :auto-upload="false"
            :limit="1"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            accept=".txt,.md,.pdf,.doc,.docx"
            style="width: 100%"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">将文件拖到此处，或<em>点击选择</em></div>
            <template #tip>
              <div class="form-tip">
                支持 txt、md、pdf、doc、docx 格式。{{ selectedFileName ? `已选择：${selectedFileName}` : '' }}
              </div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          上传并索引
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
