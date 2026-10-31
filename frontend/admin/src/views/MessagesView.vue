<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Refresh } from '@element-plus/icons-vue'
import { messageApi, type MessageVO } from '@/api/messages'
import { formatDateTime } from '@/utils/format'
import {
  MESSAGE_CHANNEL_LABELS,
  MESSAGE_TYPE_LABELS,
  enumLabel,
  isReadStatus,
} from '@/utils/enums'

const loading = ref(false)
const messages = ref<MessageVO[]>([])
const total = ref(0)
const unreadCount = ref(0)
const query = reactive({
  page: 1,
  size: 10,
})

async function loadList() {
  loading.value = true
  try {
    const data = await messageApi.list({ page: query.page, size: query.size })
    messages.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

async function loadUnreadCount() {
  unreadCount.value = await messageApi.unreadCount()
}

async function refresh() {
  await Promise.all([loadList(), loadUnreadCount()])
}

function handleSizeChange() {
  query.page = 1
  loadList()
}

async function handleRead(row: MessageVO) {
  if (isReadStatus(row.readStatus)) return
  await messageApi.read(row.id)
  ElMessage.success('已标记为已读')
  await refresh()
}

const readingAll = ref(false)

async function handleReadAll() {
  if (unreadCount.value === 0) return
  try {
    await ElMessageBox.confirm('确认将全部消息标记为已读？', '全部已读', { type: 'warning' })
  } catch {
    return
  }
  readingAll.value = true
  try {
    await messageApi.readAll()
    ElMessage.success('全部消息已标记为已读')
    await refresh()
  } finally {
    readingAll.value = false
  }
}

function rowClassName({ row }: { row: MessageVO; rowIndex: number }): string {
  return isReadStatus(row.readStatus) ? '' : 'is-unread'
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <span class="toolbar-title">消息通知</span>
          <el-tag v-if="unreadCount > 0" type="danger" effect="plain" round>
            未读 {{ unreadCount }} 条
          </el-tag>
          <el-tag v-else type="info" effect="plain" round>全部已读</el-tag>
        </div>
        <div>
          <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
          <el-button
            type="primary"
            plain
            :icon="Check"
            :disabled="unreadCount === 0"
            :loading="readingAll"
            @click="handleReadAll"
          >
            全部已读
          </el-button>
        </div>
      </div>
      <el-table
        v-loading="loading"
        :data="messages"
        stripe
        empty-text="暂无消息"
        row-key="id"
        :row-class-name="rowClassName"
      >
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="isReadStatus(row.readStatus) ? 'info' : 'danger'" effect="plain">
              {{ isReadStatus(row.readStatus) ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            {{ row.typeName || enumLabel(MESSAGE_TYPE_LABELS, row.type) }}
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'unread-title': !isReadStatus(row.readStatus) }">
              {{ row.title }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column label="渠道" width="100" align="center">
          <template #default="{ row }">
            {{ enumLabel(MESSAGE_CHANNEL_LABELS, row.channel) }}
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="isReadStatus(row.readStatus)"
              @click="handleRead(row)"
            >
              标记已读
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
  </div>
</template>

<style scoped>
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

:deep(.el-table .is-unread) {
  background-color: var(--el-color-primary-light-9);
}

.unread-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
