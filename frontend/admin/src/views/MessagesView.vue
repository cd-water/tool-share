<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Bell,
  Box,
  Calendar,
  Check,
  Clock,
  Close,
  Refresh,
  Tools,
  Warning,
} from '@element-plus/icons-vue'
import { messageApi, type MessageVO } from '@/api/messages'
import { formatDateTime } from '@/utils/format'
import {
  MESSAGE_CHANNEL_LABELS,
  MESSAGE_TYPE_LABELS,
  enumLabel,
  isReadStatus,
} from '@/utils/enums'

const router = useRouter()

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

/* —— 类型分栏（客户端过滤当前页数据） —— */
const activeType = ref('')
const typeOptions: Array<[string, string]> = [
  ['REPAIR', '报修'],
  ['OVERDUE', '逾期'],
  ['RESERVATION', '预约'],
  ['SYSTEM', '系统'],
]

const typeCounts = computed(() => {
  const map: Record<string, number> = {}
  for (const m of messages.value) {
    map[m.type] = (map[m.type] || 0) + 1
  }
  return map
})

const filteredMessages = computed(() =>
  activeType.value ? messages.value.filter((m) => m.type === activeType.value) : messages.value,
)

/* —— 消息类型：徽章色调 + 图标 —— */
interface TypeMeta {
  badge: string
  fg: string
  bg: string
  icon: Component
}

const TYPE_META: Record<string, TypeMeta> = {
  RESERVATION: { badge: 'brand', fg: 'var(--brand-2)', bg: 'var(--brand-bg)', icon: Calendar },
  PICKUP: { badge: 'info', fg: 'var(--info)', bg: 'var(--info-bg)', icon: Box },
  RENT_DUE: { badge: 'warn', fg: 'var(--warn)', bg: 'var(--warn-bg)', icon: Clock },
  OVERDUE: { badge: 'bad', fg: 'var(--bad)', bg: 'var(--bad-bg)', icon: Warning },
  REPAIR: { badge: 'info', fg: 'var(--info)', bg: 'var(--info-bg)', icon: Tools },
  SYSTEM: { badge: 'neutral', fg: 'var(--ink-2)', bg: 'var(--surface-2)', icon: Bell },
}

function metaOf(type?: string): TypeMeta {
  return TYPE_META[type || ''] || TYPE_META.SYSTEM
}

/* —— 详情弹窗 + 跳转处理 —— */
const dialogVisible = ref(false)
const detail = ref<MessageVO | null>(null)

function openDetail(row: MessageVO) {
  detail.value = row
  dialogVisible.value = true
  if (!isReadStatus(row.readStatus)) handleRead(row)
}

function handleGo() {
  if (!detail.value) return
  const path =
    {
      REPAIR: '/damage',
      OVERDUE: '/rentals',
      RESERVATION: '/rentals',
      PICKUP: '/rentals',
      RENT_DUE: '/rentals',
      SYSTEM: '/tools',
    }[detail.value.type] || '/dashboard'
  dialogVisible.value = false
  router.push(path)
}

/* —— 分页 —— */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))

const pageList = computed(() => {
  const tp = totalPages.value
  const cur = query.page
  const start = Math.max(1, Math.min(cur - 2, tp - 4))
  const end = Math.min(tp, start + 4)
  const pages: number[] = []
  for (let i = start; i <= end; i++) pages.push(i)
  return pages
})

function goPage(p: number) {
  if (p < 1 || p > totalPages.value || p === query.page) return
  query.page = p
  loadList()
}

onMounted(refresh)
</script>

<template>
  <div class="page">
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">OPERATIONS INBOX</p>
        <h1>消息中心</h1>
        <p class="sub">报修待办、逾期预警、库存与系统通知，运营事项集中处理。</p>
      </div>
      <div class="actions">
        <button class="btn btn-outline" :class="{ loading }" @click="refresh">
          <Refresh class="ic sm" />
          刷新
        </button>
        <button
          class="btn btn-primary"
          :class="{ loading: readingAll }"
          :disabled="unreadCount === 0"
          @click="handleReadAll"
        >
          <Check class="ic sm" />
          全部已读
        </button>
      </div>
    </div>

    <div class="between reveal" style="margin-bottom: 18px; flex-wrap: wrap">
      <div class="tabs">
        <button :class="{ active: activeType === '' }" @click="activeType = ''">
          全部<span class="cnt">{{ messages.length }}</span>
        </button>
        <button
          v-for="[key, label] in typeOptions"
          :key="key"
          :class="{ active: activeType === key }"
          @click="activeType = key"
        >
          {{ label }}<span class="cnt">{{ typeCounts[key] || 0 }}</span>
        </button>
      </div>
      <span v-if="unreadCount > 0" class="badge bad">
        <span class="dot"></span>未读 {{ unreadCount }} 条
      </span>
    </div>

    <div class="msg-list card reveal">
      <template v-if="loading && messages.length === 0">
        <div v-for="i in 4" :key="'sk' + i" class="msg">
          <span class="m-ic skel"></span>
          <div class="m-body">
            <div class="skel" style="height: 14px; width: 36%; margin-bottom: 9px"></div>
            <div class="skel" style="height: 12px; width: 82%"></div>
          </div>
        </div>
      </template>

      <template v-else-if="filteredMessages.length">
        <div
          v-for="m in filteredMessages"
          :key="m.id"
          class="msg"
          :class="{ read: isReadStatus(m.readStatus) }"
          @click="openDetail(m)"
        >
          <span class="m-ic" :style="{ color: metaOf(m.type).fg, background: metaOf(m.type).bg }">
            <component :is="metaOf(m.type).icon" class="ic" />
          </span>
          <div class="m-body">
            <div class="m-top">
              <h3>{{ m.title }}</h3>
              <span class="badge" :class="metaOf(m.type).badge">
                {{ m.typeName || enumLabel(MESSAGE_TYPE_LABELS, m.type) }}
              </span>
            </div>
            <p class="m-preview">{{ m.content }}</p>
          </div>
          <span class="m-time">{{ formatDateTime(m.createTime) }}</span>
          <span class="m-dot"></span>
          <button
            v-if="!isReadStatus(m.readStatus)"
            class="link-btn m-mark"
            @click.stop="handleRead(m)"
          >
            标记已读
          </button>
        </div>
      </template>

      <div v-else class="empty">
        <div class="e-ic"><Bell class="ic" /></div>
        <h4>没有相关消息</h4>
        <p>当前分类下暂无通知。</p>
      </div>
    </div>

    <div class="list-foot reveal">
      <div class="flex" style="gap: 12px">
        <span class="tiny">共 {{ total }} 条</span>
        <select
          v-model="query.size"
          class="select"
          style="width: 96px; height: 32px"
          @change="handleSizeChange"
        >
          <option :value="10">10 条/页</option>
          <option :value="20">20 条/页</option>
          <option :value="50">50 条/页</option>
        </select>
      </div>
      <div class="pager">
        <button :disabled="query.page <= 1" @click="goPage(query.page - 1)">‹</button>
        <button
          v-for="p in pageList"
          :key="p"
          :class="{ active: p === query.page }"
          @click="goPage(p)"
        >
          {{ p }}
        </button>
        <button :disabled="query.page >= totalPages" @click="goPage(query.page + 1)">›</button>
      </div>
    </div>

    <div class="mask" :class="{ show: dialogVisible }" @click.self="dialogVisible = false">
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>{{ detail?.title }}</h3>
            <p class="d-sub">
              {{ formatDateTime(detail?.createTime) }}
              <template v-if="detail"> · {{ enumLabel(MESSAGE_CHANNEL_LABELS, detail.channel) }}</template>
            </p>
          </div>
          <button class="dlg-x" aria-label="关闭" @click="dialogVisible = false">
            <Close class="ic sm" />
          </button>
        </div>
        <div class="dlg-body">
          <span
            v-if="detail"
            class="badge"
            :class="metaOf(detail.type).badge"
            style="margin-bottom: 14px"
          >
            <span class="dot"></span>{{ detail.typeName || enumLabel(MESSAGE_TYPE_LABELS, detail.type) }}
          </span>
          <p style="font-size: var(--t-base); line-height: 1.85; color: var(--ink-2); margin: 0">
            {{ detail?.content }}
          </p>
        </div>
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="dialogVisible = false">关闭</button>
          <button class="btn btn-primary" @click="handleGo">去处理</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.msg-list {
  overflow: hidden;
  padding: 6px;
}

.msg {
  display: flex;
  gap: 14px;
  padding: 15px 16px;
  border-radius: 11px;
  cursor: pointer;
  transition: background var(--fast);
  position: relative;
  align-items: flex-start;
}

.msg:hover {
  background: var(--surface-2);
}

.msg + .msg {
  border-top: 1px solid var(--line);
}

.m-ic {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}

.m-body {
  flex: 1;
  min-width: 0;
}

.m-top {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 3px;
}

.m-top h3 {
  font-size: var(--t-base);
  font-weight: 700;
  margin: 0;
}

.m-preview {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.m-time {
  font-size: var(--t-2xs);
  color: var(--ink-3);
  flex: none;
  margin-top: 3px;
}

.m-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--bad);
  flex: none;
  margin-top: 7px;
  box-shadow: 0 0 0 3px var(--bad-bg);
}

.msg.read .m-top h3 {
  font-weight: 600;
  color: var(--ink-2);
}

.msg.read .m-dot {
  visibility: hidden;
}

.m-mark {
  flex: none;
  align-self: center;
  margin-left: 2px;
}

.list-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
</style>
