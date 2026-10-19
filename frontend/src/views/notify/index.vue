<template>
  <el-config-provider :locale="zhCn">
    <div class="msg-page">
      <div class="page-head">
        <div>
          <h1>消息中心</h1>
          <p class="sub">取件 / 到期 / 逾期 / 维修完成提醒 · 系统内消息与短信记录</p>
        </div>
        <div class="ph-actions">
          <el-button :loading="readAllLoading" @click="readAll">全部标记已读</el-button>
        </div>
      </div>

      <div class="filter-bar">
        <el-select
          v-model="type"
          class="f-item"
          placeholder="全部类型"
          clearable
          @change="applyFilter"
        >
          <el-option label="取件提醒" :value="1" />
          <el-option label="到期提醒" :value="2" />
          <el-option label="逾期提醒" :value="3" />
          <el-option label="维修完成提醒" :value="4" />
        </el-select>
        <el-select
          v-model="readStatus"
          class="f-item"
          placeholder="已读状态"
          clearable
          @change="applyFilter"
        >
          <el-option label="未读" :value="0" />
          <el-option label="已读" :value="1" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="msg-list">
        <div v-if="!loading && !messages.length" class="empty-box">
          <svg
            class="empty-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无符合条件的消息' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
        </div>

        <template v-else>
          <div
            v-for="msg in messages"
            :key="msg.id"
            class="msg-item"
            :class="{ unread: msg.readStatus === 0 }"
          >
            <div class="msg-ic">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path :d="TYPE_ICON[msg.type] || TYPE_ICON[1]" />
              </svg>
            </div>
            <div class="msg-body">
              <div class="msg-title">
                <span>{{ msg.title }}</span>
                <span class="tag" :class="typeTag(msg.type)[1]">{{ typeTag(msg.type)[0] }}</span>
                <span v-if="msg.readStatus === 0" class="tag t-info unread-tag">未读</span>
              </div>
              <div class="msg-content">{{ msg.content }}</div>
              <div class="msg-meta">
                <span>{{ fmtDateTime(msg.createTime) }}</span>
                <span class="channel">
                  渠道：<span class="tag" :class="channelTag(msg.channel)[1]">{{
                    channelTag(msg.channel)[0]
                  }}</span>
                </span>
                <span v-if="msg.bizNo">
                  关联单号：<span class="chip-code">{{ msg.bizNo }}</span>
                </span>
              </div>
            </div>
            <div class="msg-act">
              <span v-if="msg.readStatus === 1" class="tiny">已读</span>
              <button
                v-else
                type="button"
                class="link-btn"
                :disabled="readingId === msg.id"
                @click="markRead(msg)"
              >
                {{ readingId === msg.id ? '处理中…' : '标记已读' }}
              </button>
            </div>
          </div>
        </template>
      </div>

      <div v-if="total > 0" class="pager">
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
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { pageNotify, readAllNotify, readNotify } from '../../api/notify'
import type { Message, NotifyQueryDTO } from '../../api/types'

const MSG_TYPE: Record<number, [string, string]> = {
  1: ['取件提醒', 't-info'],
  2: ['到期提醒', 't-warning'],
  3: ['逾期提醒', 't-danger'],
  4: ['维修完成提醒', 't-success'],
}
const CHANNEL: Record<number, [string, string]> = {
  1: ['系统内', 't-gray'],
  2: ['短信', 't-info'],
}
const TYPE_ICON: Record<number, string> = {
  1: 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z',
  2: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2',
  3: 'M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01',
  4: 'M22 11.1V12a10 10 0 1 1-5.9-9.1M22 4l-10 10-3-3',
}

const typeTag = (v: number) => MSG_TYPE[v] ?? ['消息', 't-gray']
const channelTag = (v: number) => CHANNEL[v] ?? ['未知', 't-gray']

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

const route = useRoute()
const router = useRouter()

const type = ref<number | ''>('')
const readStatus = ref<number | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const messages = ref<Message[]>([])
const loading = ref(false)
const failed = ref(false)
const readingId = ref(0)
const readAllLoading = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  const params: NotifyQueryDTO = { page: page.value, size: size.value }
  if (type.value !== '') params.type = type.value
  if (readStatus.value !== '') params.readStatus = readStatus.value

  syncUrl()
  try {
    const result = await pageNotify(params)
    messages.value = result.records
    total.value = result.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  page.value = 1
  load()
}

function reset() {
  type.value = ''
  readStatus.value = ''
  page.value = 1
  load()
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

function syncUrl() {
  router.replace({
    query: {
      ...(type.value !== '' ? { type: String(type.value) } : {}),
      ...(readStatus.value !== '' ? { readStatus: String(readStatus.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

async function markRead(msg: Message) {
  if (readingId.value) return
  readingId.value = msg.id
  try {
    await readNotify(msg.id)
    ElMessage.success('已标记为已读')
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    readingId.value = 0
  }
}

async function readAll() {
  try {
    await ElMessageBox.confirm('确定将全部消息标记为已读吗？', '全部标记已读', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  readAllLoading.value = true
  try {
    await readAllNotify()
    ElMessage.success('已全部标记为已读')
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    readAllLoading.value = false
  }
}

onMounted(() => {
  const t = Number(route.query.type)
  type.value = [1, 2, 3, 4].includes(t) ? t : ''
  const r = route.query.readStatus
  readStatus.value = r === '0' ? 0 : r === '1' ? 1 : ''
  const p = Number(route.query.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.msg-page {
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
.ph-actions :deep(.el-button),
.filter-bar :deep(.el-button),
.empty-box :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
}
.ph-actions :deep(.el-button:not(.el-button--primary)),
.filter-bar :deep(.el-button:not(.el-button--primary)),
.empty-box :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.ph-actions :deep(.el-button:not(.el-button--primary):hover),
.filter-bar :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
}
.filter-bar :deep(.el-button--primary),
.empty-box :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}

.filter-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
}
.filter-bar .f-item {
  width: 166px;
}
.filter-bar .grow {
  flex: 1;
}
.filter-bar :deep(.el-select__wrapper) {
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
}
.filter-bar :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-7) inset;
}
.filter-bar :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.filter-bar :deep(.el-select__placeholder) {
  font-size: 13.5px;
  color: var(--text);
}

/* ---------- 消息列表 ---------- */
.msg-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}
.msg-item {
  display: flex;
  gap: 13px;
  align-items: flex-start;
  padding: 13px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
  transition: box-shadow 180ms, border-color 180ms;
}
.msg-item:hover {
  border-color: var(--primary-7);
  box-shadow: var(--shadow-2);
}
.msg-item.unread {
  border-left: 2px solid var(--info);
}
.msg-ic {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 36px;
  height: 36px;
  color: var(--primary-5);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 50%;
}
.msg-ic svg {
  width: 17px;
  height: 17px;
}
.msg-body {
  flex: 1;
  min-width: 0;
}
.msg-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 14px;
  line-height: 1.45;
  letter-spacing: -0.005em;
}
.msg-item.unread .msg-title {
  font-weight: 600;
}
.unread-tag {
  height: 18px;
  font-size: 11px;
}
.msg-content {
  display: -webkit-box;
  margin-top: 3px;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.msg-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.msg-act {
  flex: none;
  align-self: center;
}

/* ---------- 标签 / 按钮 ---------- */
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
.t-warning {
  background: var(--warning-9);
  color: var(--warning);
  border-color: #eddcb4;
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
.link-btn {
  height: 26px;
  padding: 0 6px;
  font-family: inherit;
  font-size: 13px;
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
.link-btn:disabled {
  color: var(--text-3);
  cursor: default;
  background: none;
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
.empty-icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 8px;
  color: var(--text-3);
}
.empty-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-2);
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
  .filter-bar .f-item {
    width: 100%;
  }
  .filter-bar .grow {
    display: none;
  }
  .msg-item {
    flex-wrap: wrap;
  }
  .msg-act {
    width: 100%;
    padding-left: 49px;
    text-align: left;
    align-self: flex-start;
  }
  .pager :deep(.el-pagination) {
    justify-content: center;
  }
}
</style>
