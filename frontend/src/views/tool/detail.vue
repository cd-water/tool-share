<template>
  <el-config-provider :locale="zhCn">
    <div class="tool-detail">
      <div class="breadcrumb">
        <RouterLink to="/tools">工具广场</RouterLink>
        <span class="sep">/</span>
        <span class="cur">{{ tool?.name || '工具详情' }}</span>
      </div>

      <div v-if="loading" v-loading="loading" class="loading-box" />

      <div v-else-if="error" class="card empty-box">
        <svg
          class="empty-ill"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          <path d="M12 9v4M12 17h.01" />
        </svg>
        <p class="empty-desc">{{ error }}</p>
        <el-button @click="router.push('/tools')">返回工具广场</el-button>
      </div>

      <template v-else-if="tool">
        <div class="detail-grid">
          <div class="gallery">
            <div class="g-main">
              <img
                v-if="mainImage"
                class="g-photo"
                :src="mainImage"
                :alt="tool.name"
                @error="hideImage"
              />
              <svg
                v-else
                class="g-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path :d="CATEGORY_ICON[tool.category] || CATEGORY_ICON[4]" />
              </svg>
              <span class="g-status">
                <span class="tag" :class="statusTag(tool.status)[1]">
                  {{ statusTag(tool.status)[0] }}
                </span>
              </span>
            </div>
            <div v-if="images.length > 1" class="g-thumbs">
              <button
                v-for="(img, index) in images"
                :key="img + index"
                type="button"
                class="g-thumb"
                :class="{ active: index === activeImage }"
                @click="activeImage = index"
              >
                <img :src="img" :alt="`${tool.name} 图片 ${index + 1}`" @error="hideImage" />
              </button>
            </div>
          </div>

          <div>
            <div class="card">
              <h1 class="info-name">{{ tool.name }}</h1>
              <div class="info-tags">
                <span class="tag" :class="categoryTag(tool.category)[1]">
                  {{ categoryTag(tool.category)[0] }}
                </span>
                <span class="tag" :class="statusTag(tool.status)[1]">
                  {{ statusTag(tool.status)[0] }}
                </span>
                <span class="tag" :class="damageTag(tool.damageLevel)[1]">
                  {{ damageTag(tool.damageLevel)[0] }}
                </span>
                <span class="chip-code">{{ tool.toolCode }}</span>
              </div>
              <div class="kv-grid">
                <div class="k">型号规格</div>
                <div class="v">{{ tool.modelSpec || '—' }}</div>
                <div class="k">押金金额</div>
                <div class="v"><span class="money">{{ money(tool.deposit) }}</span></div>
                <div class="k">存放区域</div>
                <div class="v">{{ tool.storageLocation || '—' }}</div>
                <div class="k">采购日期</div>
                <div class="v">{{ tool.purchaseDate || '—' }}</div>
                <div class="k">供应商</div>
                <div class="v">{{ tool.supplierInfo || '—' }}</div>
                <div class="k">审核状态</div>
                <div class="v">
                  <span class="tag" :class="auditTag(tool.auditStatus)[1]">
                    {{ auditTag(tool.auditStatus)[0] }}
                  </span>
                </div>
              </div>
            </div>

            <div class="card">
              <div class="tabs">
                <button
                  type="button"
                  class="tab"
                  :class="{ active: tab === 'usage' }"
                  @click="tab = 'usage'"
                >
                  使用说明
                </button>
                <button
                  type="button"
                  class="tab"
                  :class="{ active: tab === 'logs' }"
                  @click="tab = 'logs'"
                >
                  状态日志 <span class="tiny">（工作人员可见）</span>
                </button>
              </div>
              <div v-show="tab === 'usage'" class="usage">
                <p class="usage-text">{{ tool.usageInstruction || '暂无使用说明' }}</p>
              </div>
              <div v-show="tab === 'logs'" class="lock-box">
                <svg
                  class="lock-icon"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <rect x="4" y="11" width="16" height="10" rx="2" />
                  <path d="M8 11V7a4 4 0 0 1 8 0v4" />
                </svg>
                状态变更日志仅社区工作人员可见<br />
                居民可在「工具详情」查看当前状态，如需历史记录请联系服务站
              </div>
            </div>
          </div>
        </div>

        <div class="card book-panel">
          <template v-if="paidOrderNo">
            <div class="ok-box">
              <svg
                class="ok-ic"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="9" />
                <path d="M8 12l2.5 2.5L16 9" />
              </svg>
              <div class="ok-title">预约成功，押金已缴纳</div>
              <p class="ok-sub">
                预约单号 <span class="chip-code">{{ paidOrderNo }}</span> · 请按取件时间前往
                {{ tool.storageLocation }}
              </p>
              <p class="ok-sub">取件时请携带身份证或预约单号；取件前 24 小时可取消，押金原路退还</p>
              <div class="ok-actions">
                <el-button type="primary" @click="router.push('/rentals')">查看我的租赁</el-button>
                <el-button @click="router.push('/tools')">返回工具广场</el-button>
              </div>
            </div>
          </template>

          <template v-else>
            <div class="card-title">
              预约此工具
              <span class="tiny">{{ tool.toolCode }}</span>
            </div>

            <div class="book-row">
              <div class="field">
                <label>取件日期</label>
                <el-date-picker
                  v-model="date"
                  class="date-picker"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择日期"
                  :disabled-date="disabledDate"
                  :disabled="!canBook"
                  :clearable="false"
                  @change="onDateChange"
                />
              </div>
              <div class="field">
                <label>取件人</label>
                <el-input
                  v-model="pickupName"
                  maxlength="20"
                  :disabled="!canBook"
                  placeholder="取件人姓名（可代取）"
                />
              </div>
            </div>

            <div class="slots-head">
              <span>可预约时段</span>
              <span class="tiny">按小时划分，选择后实时校验</span>
            </div>
            <div v-if="!canBook" class="slots-tip">{{ unbookableReason }}</div>
            <div v-else-if="slotsLoading" class="slots-tip">时段加载中…</div>
            <div v-else-if="slots.length" class="slot-grid">
              <button
                v-for="(slot, index) in slots"
                :key="slot.startTime + slot.endTime"
                type="button"
                class="slot"
                :class="{ active: index === selectedIndex }"
                @click="selectedIndex = index"
              >
                {{ timeText(slot.startTime) }}-{{ timeText(slot.endTime) }}
              </button>
            </div>
            <div v-else class="slots-tip">当日暂无可约时段，请选择其他日期</div>

            <div v-if="conflictMessage" class="conflict">
              <span class="conflict-icon">!</span>
              <span>{{ conflictMessage }}</span>
            </div>

            <div class="deposit-line">
              <span class="deposit-label">押金合计（归还验收后原路退还）</span>
              <span class="money deposit-value">{{ money(tool.deposit) }}</span>
            </div>

            <el-button
              class="book-btn"
              type="primary"
              :loading="booking"
              :disabled="!canBook"
              @click="book"
            >
              {{ bookButtonText }}
            </el-button>
            <p class="form-tip">取件时间前 24 小时可取消预约，押金自动退还</p>
          </template>
        </div>
      </template>

      <el-dialog
        v-model="payVisible"
        title="押金缴纳 · 支付沙箱"
        width="440px"
        :close-on-click-modal="false"
      >
        <div class="sandbox-tip">支付沙箱：本环境为演示模式，不会产生真实扣款</div>
        <p class="pay-meta">{{ tool?.name }} · {{ selectedSlotText }}</p>
        <div class="pay-amount">{{ money(tool?.deposit || 0) }}</div>
        <p class="pay-meta">
          预约单号：<span class="chip-code">{{ pendingOrder?.orderNo || '—' }}</span>
        </p>
        <div class="field">
          <label>支付方式</label>
          <div class="pay-methods">
            <span class="tag t-success">微信支付</span>
            <span class="tag t-primary">支付宝</span>
            <span class="tag t-info">银行卡</span>
          </div>
        </div>
        <template #footer>
          <el-button @click="payVisible = false">取消</el-button>
          <el-button type="primary" :loading="paying" @click="confirmPay">确认支付</el-button>
        </template>
      </el-dialog>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { getTool } from '../../api/tool'
import { createRental, getAvailableTimes, payRental } from '../../api/rental'
import { useUserStore } from '../../stores/user'
import type { RentalCreateVO, TimeSlotVO, Tool } from '../../api/types'

// ---------- 字典（与设计规范状态色板一致） ----------
const TOOL_STATUS: Record<number, [string, string]> = {
  1: ['可用', 't-success'],
  2: ['已借出', 't-info'],
  3: ['维修中', 't-warning'],
  4: ['报废', 't-danger'],
  5: ['下架', 't-gray'],
}
const CATEGORY: Record<number, [string, string]> = {
  1: ['维修', 't-primary'],
  2: ['园艺', 't-success'],
  3: ['清洁', 't-info'],
  4: ['其他', 't-gray'],
}
const DAMAGE: Record<number, [string, string]> = {
  1: ['全新', 't-success'],
  2: ['轻微磨损', 't-info'],
  3: ['中度磨损', 't-warning'],
  4: ['严重损坏', 't-danger'],
}
const AUDIT: Record<number, [string, string]> = {
  1: ['待审核', 't-warning'],
  2: ['已通过', 't-success'],
  3: ['需修改', 't-danger'],
}
const CATEGORY_ICON: Record<number, string> = {
  1: 'M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z',
  2: 'M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10z',
  3: 'M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z',
  4: 'M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z',
}

const statusTag = (v: number) => TOOL_STATUS[v] ?? ['未知', 't-gray']
const categoryTag = (v: number) => CATEGORY[v] ?? ['未知', 't-gray']
const damageTag = (v: number) => DAMAGE[v] ?? ['未知', 't-gray']
const auditTag = (v: number) => AUDIT[v] ?? ['未知', 't-gray']

function money(n: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

function timeText(value: string) {
  const match = value?.match(/(\d{2}:\d{2})/)
  return match ? match[1] : value
}

function todayStr() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

// ---------- 状态 ----------
const route = useRoute()
const router = useRouter()
const user = useUserStore()

const tool = ref<Tool | null>(null)
const loading = ref(true)
const error = ref('')
const tab = ref<'usage' | 'logs'>('usage')
const activeImage = ref(0)

const date = ref(todayStr())
const pickupName = ref(user.realName || '')
const slots = ref<TimeSlotVO[]>([])
const slotsLoading = ref(false)
const selectedIndex = ref(-1)
const conflictMessage = ref('')

const booking = ref(false)
const pendingOrder = ref<RentalCreateVO | null>(null)
const payVisible = ref(false)
const paying = ref(false)
const paidOrderNo = ref('')

const images = computed(() => tool.value?.images ?? [])
const mainImage = computed(() => images.value[activeImage.value] || '')
const selectedSlot = computed(() => slots.value[selectedIndex.value] ?? null)
const selectedSlotText = computed(() =>
  selectedSlot.value
    ? `${timeText(selectedSlot.value.startTime)}-${timeText(selectedSlot.value.endTime)}`
    : '',
)
const canBook = computed(
  () => !!tool.value && tool.value.status === 1 && tool.value.auditStatus === 2,
)
const unbookableReason = computed(() => {
  if (!tool.value) return ''
  if (tool.value.auditStatus === 1) return '工具待审核，审核通过后开放预约'
  if (tool.value.auditStatus === 3) return '工具需修改，修改通过后开放预约'
  const map: Record<number, string> = {
    2: '工具已借出，归还后可预约',
    3: '工具维修中，维修完成并重新上架后可预约',
    4: '工具已报废，不可预约',
    5: '工具已下架，不可预约',
  }
  return map[tool.value.status] || '当前工具暂不可预约'
})
const bookButtonText = computed(() => {
  if (!tool.value) return '立即预约'
  if (tool.value.auditStatus === 1) return '待审核 · 暂不可预约'
  if (tool.value.auditStatus === 3) return '需修改 · 暂不可预约'
  const map: Record<number, string> = {
    2: '已借出 · 暂不可预约',
    3: '维修中 · 暂不可预约',
    4: '已报废 · 暂不可预约',
    5: '已下架 · 暂不可预约',
  }
  return map[tool.value.status] || '立即预约'
})

const startOfToday = (() => {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  return d.getTime()
})()
const disabledDate = (d: Date) => d.getTime() < startOfToday

async function loadTool() {
  const id = Number(route.params.id)
  if (!Number.isFinite(id) || id <= 0) {
    error.value = '工具不存在'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    tool.value = await getTool(id)
    if (!tool.value || !tool.value.id) {
      error.value = '工具不存在或已被删除'
      return
    }
    if (canBook.value) await loadSlots()
  } catch {
    error.value = '工具加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function onDateChange() {
  conflictMessage.value = ''
  loadSlots()
}

async function loadSlots() {
  if (!tool.value) return
  selectedIndex.value = -1
  slotsLoading.value = true
  try {
    slots.value = (await getAvailableTimes(tool.value.id, date.value)) ?? []
  } catch {
    slots.value = []
  } finally {
    slotsLoading.value = false
  }
}

async function book() {
  if (!tool.value || !canBook.value) return
  if (!selectedSlot.value) {
    ElMessage.warning('请先选择预约时段')
    return
  }
  if (!pickupName.value.trim()) {
    ElMessage.warning('请填写取件人姓名')
    return
  }

  booking.value = true
  conflictMessage.value = ''
  try {
    const order = await createRental({
      toolId: tool.value.id,
      startTime: selectedSlot.value.startTime,
      endTime: selectedSlot.value.endTime,
      pickupName: pickupName.value.trim(),
    })
    pendingOrder.value = order
    payVisible.value = true
    // ponytail: 后端暂未返回推荐时段；先原样展示 C409 message，接口补充后可直接解析并高亮
  } catch (e) {
    conflictMessage.value = (e as Error).message || '预约失败，请稍后重试'
    await loadSlots()
  } finally {
    booking.value = false
  }
}

async function confirmPay() {
  if (!pendingOrder.value) return
  paying.value = true
  try {
    await payRental(pendingOrder.value.id)
    paidOrderNo.value = pendingOrder.value.orderNo
    payVisible.value = false
    ElMessage.success('押金缴纳成功，预约已确认')
  } finally {
    paying.value = false
  }
}

function hideImage(e: Event) {
  ;(e.target as HTMLImageElement).style.display = 'none'
}

onMounted(loadTool)
</script>

<style scoped>
.tool-detail {
  --primary: #1c3a35;
  --primary-2: #24443e;
  --primary-3: #2e5149;
  --primary-5: #6e8a82;
  --primary-7: #a8bcb5;
  --primary-tint: #eef3f0;
  --accent-2: #b44309;
  --accent-9: #fdeee3;
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
  --r-4: 12px;
  --shadow-1: 0 1px 2px rgba(16, 24, 40, 0.05);
  --shadow-2: 0 1px 2px rgba(16, 24, 40, 0.04), 0 4px 12px -4px rgba(16, 24, 40, 0.09);
  --font-mono: Consolas, Monaco, 'Courier New', monospace;

  color: var(--text);
}

.card {
  position: relative;
  margin-bottom: 14px;
  padding: 16px 18px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
  box-shadow: var(--shadow-1);
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

.loading-box {
  min-height: 320px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-3);
}

.empty-box {
  padding: 40px 20px;
  text-align: center;
}
.empty-ill {
  width: 40px;
  height: 40px;
  margin: 0 auto 8px;
  color: var(--warning);
}
.empty-desc {
  margin: 0 0 14px;
  font-size: 13.5px;
  color: var(--text-2);
}

/* ---------- 主信息区 ---------- */
.detail-grid {
  display: grid;
  grid-template-columns: 5fr 7fr;
  gap: 14px;
  align-items: start;
}

.gallery {
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  box-shadow: var(--shadow-1);
}
.g-main {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 280px;
  background: linear-gradient(135deg, #f1f5f1, #e5ebe6);
}
.g-icon {
  width: 88px;
  height: 88px;
  color: var(--primary-5);
}
.g-photo {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.g-status {
  position: absolute;
  top: 12px;
  right: 12px;
}
.g-thumbs {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid var(--border);
}
.g-thumb {
  width: 62px;
  height: 50px;
  overflow: hidden;
  padding: 0;
  background: var(--surface-2);
  border: 1.5px solid var(--border);
  border-radius: var(--r-2);
  cursor: pointer;
  transition: all 120ms cubic-bezier(0.4, 0, 0.2, 1);
}
.g-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.g-thumb:hover {
  border-color: var(--primary-7);
}
.g-thumb.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-tint);
}

.info-name {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.011em;
  text-wrap: balance;
}
.info-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 14px;
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
.kv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px 16px;
  font-size: 13px;
}
.kv-grid .k {
  color: var(--text-2);
}
.kv-grid .v {
  text-align: right;
  color: var(--text);
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}
.money {
  font-weight: 600;
  color: var(--accent-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ---------- Tabs ---------- */
.tabs {
  display: flex;
  gap: 20px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  scrollbar-width: none;
}
.tabs::-webkit-scrollbar {
  display: none;
}
.tab {
  position: relative;
  padding: 8px 2px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--text-2);
  background: none;
  border: none;
  cursor: pointer;
  white-space: nowrap;
}
.tab::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--primary);
  border-radius: 2px 2px 0 0;
  transform: scaleX(0);
  transition: transform 180ms cubic-bezier(0.4, 0, 0.2, 1);
}
.tab:hover {
  color: var(--primary);
}
.tab.active {
  color: var(--primary);
  font-weight: 600;
}
.tab.active::after {
  transform: scaleX(1);
}
.tiny {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-3);
}
.usage-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text);
  white-space: pre-line;
  word-break: break-word;
}
.lock-box {
  padding: 26px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-3);
  text-align: center;
  background: var(--surface-2);
  border: 1px dashed var(--border-2);
  border-radius: var(--r-3);
}
.lock-icon {
  width: 28px;
  height: 28px;
  margin: 0 auto 6px;
  color: var(--text-3);
}

/* ---------- 预约面板 ---------- */
.book-panel {
  padding: 16px 18px;
}
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  letter-spacing: -0.005em;
}
.book-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  align-items: flex-end;
}
.field {
  min-width: 180px;
  margin-bottom: 0;
}
.field label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  letter-spacing: 0.01em;
}
.date-picker {
  width: 100%;
}
.book-panel :deep(.el-input__wrapper),
.book-panel :deep(.el-date-editor .el-input__wrapper) {
  min-height: 32px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
}
.book-panel :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-7) inset;
}
.book-panel :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.book-panel :deep(.el-input__inner) {
  font-size: 13.5px;
  color: var(--text);
}

.slots-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 14px 0 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
}
.slots-tip {
  padding: 18px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
.slot-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.slot-grid.locked {
  opacity: 0.5;
}
.slot {
  padding: 8px 4px;
  font-family: inherit;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  color: var(--text);
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--r-2);
  cursor: pointer;
  transition: all 120ms cubic-bezier(0.4, 0, 0.2, 1);
}
.slot:hover {
  border-color: var(--primary-5);
  box-shadow: var(--shadow-1);
}
.slot.active {
  font-weight: 600;
  color: var(--primary);
  background: var(--primary-tint);
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(28, 58, 53, 0.07);
}
.slot.disabled {
  color: var(--text-3);
  background: var(--surface-3);
  border-color: var(--border);
  cursor: not-allowed;
}

.conflict {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 12px;
  padding: 9px 12px;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--danger);
  background: var(--danger-9);
  border: 1px solid #efc7c1;
  border-radius: var(--r-2);
}
.conflict-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  background: var(--danger);
  border-radius: 50%;
}

.deposit-line {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 12px 0;
  padding: 10px 14px;
  background: var(--accent-9);
  border: 1px solid #f6d2bb;
  border-radius: var(--r-3);
}
.deposit-label {
  font-size: 12.5px;
  color: var(--text-2);
}
.deposit-value {
  font-size: 17px;
}
.book-btn {
  width: 100%;
  height: 38px;
  font-size: 13.5px;
  font-weight: 500;
  border-radius: var(--r-2);
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
  --el-button-active-bg-color: var(--primary-3);
  --el-button-active-border-color: var(--primary-3);
}
.form-tip {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--text-3);
  text-align: center;
}

/* ---------- 成功态 ---------- */
.ok-box {
  padding: 26px 10px;
  text-align: center;
}
.ok-ic {
  width: 44px;
  height: 44px;
  margin: 0 auto 8px;
  color: var(--success);
}
.ok-title {
  margin-bottom: 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--success);
  letter-spacing: -0.005em;
}
.ok-sub {
  margin: 4px 0;
  font-size: 12.5px;
  color: var(--text-2);
}
.ok-actions {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.ok-actions :deep(.el-button) {
  height: 32px;
  border-radius: var(--r-2);
  font-size: 13.5px;
}
.ok-actions :deep(.el-button--primary) {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-2);
  --el-button-hover-border-color: var(--primary-2);
}

/* ---------- 支付沙箱 ---------- */
.sandbox-tip {
  margin-bottom: 14px;
  padding: 8px 12px;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--info);
  background: var(--info-9);
  border: 1px solid #c7d8ef;
  border-radius: var(--r-2);
}
.pay-meta {
  margin: 0 0 6px;
  font-size: 12.5px;
  color: var(--text-2);
  text-align: center;
}
.pay-amount {
  margin: 10px 0 4px;
  font-size: 28px;
  font-weight: 700;
  color: var(--accent-2);
  text-align: center;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}
.pay-methods {
  display: flex;
  gap: 8px;
}

/* ---------- 标签 ---------- */
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
.t-primary {
  background: var(--primary-tint);
  color: var(--primary-3);
  border-color: #cbd8d1;
}
.t-gray {
  background: var(--surface-3);
  color: var(--text-2);
  border-color: #e2e7e2;
}

@media (max-width: 1199px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 768px) {
  .g-main {
    height: 210px;
  }
  .g-icon {
    width: 64px;
    height: 64px;
  }
  .kv-grid {
    grid-template-columns: 1fr;
  }
  .slot-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .book-row .field {
    width: 100%;
  }
}
</style>
