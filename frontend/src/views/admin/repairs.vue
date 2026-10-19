<template>
  <el-config-provider :locale="zhCn">
    <div class="admin-repairs">
      <div class="page-head">
        <div>
          <h1>报修处理</h1>
          <p class="sub">现场核验 · 维修进度跟踪 · 维修验收与工具重新上架</p>
        </div>
      </div>

      <div class="filter-bar">
        <el-input
          v-model="keyword"
          class="f-search"
          placeholder="搜索工具编号"
          clearable
          @input="onKeywordInput"
          @keyup.enter="applyFilter"
          @clear="applyFilter"
        />
        <el-select
          v-model="repairStatus"
          class="f-item"
          placeholder="维修进度"
          clearable
          @change="applyFilter"
        >
          <el-option label="待配件" :value="1" />
          <el-option label="维修中" :value="2" />
          <el-option label="维修完成" :value="3" />
          <el-option label="维修失败" :value="4" />
        </el-select>
        <div class="grow" />
        <el-button class="btn-reset" @click="reset">重 置</el-button>
        <el-button type="primary" @click="applyFilter">查 询</el-button>
      </div>

      <div v-loading="loading" class="list-wrap">
        <div v-if="!loading && !records.length" class="empty-box">
          <p class="empty-desc">{{ failed ? '加载失败，请稍后重试' : '暂无报修单' }}</p>
          <el-button v-if="failed" @click="load">重新加载</el-button>
        </div>

        <template v-else>
          <div class="table-wrap">
            <el-table :data="records" row-key="id" class="repair-table">
              <el-table-column label="报修单号" width="140">
                <template #default="{ row }">
                  <span class="chip-code">{{ row.repairNo }}</span>
                </template>
              </el-table-column>
              <el-table-column label="工具" min-width="160">
                <template #default="{ row }">
                  <div class="tool-name" :title="row.toolName">{{ row.toolName }}</div>
                  <div class="tiny">{{ row.toolCode }}</div>
                </template>
              </el-table-column>
              <el-table-column label="报修人" width="100">
                <template #default="{ row }">
                  <div>{{ row.reporterName }}</div>
                  <div class="tiny phone">{{ row.reporterPhone }}</div>
                </template>
              </el-table-column>
              <el-table-column label="损坏部位" min-width="120">
                <template #default="{ row }">
                  <span class="part" :title="row.damagePart">{{ row.damagePart || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="核验结果" width="100">
                <template #default="{ row }">
                  <span class="tag" :class="verifyTag(row.verifyResult)[1]">
                    {{ verifyTag(row.verifyResult)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="维修进度" width="100">
                <template #default="{ row }">
                  <span class="tag" :class="statusTag(row.repairStatus)[1]">
                    {{ statusTag(row.repairStatus)[0] }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="维修费用" width="100">
                <template #default="{ row }">
                  <span class="money">{{ money(row.repairFee) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="220" align="right">
                <template #default="{ row }">
                  <button type="button" class="link-btn" @click="openDetail(row)">详情</button>
                  <button
                    v-if="!row.verifyResult"
                    type="button"
                    class="link-btn"
                    @click="openVerify(row)"
                  >
                    现场核验
                  </button>
                  <button
                    v-if="row.verifyResult === 1 && row.repairStatus && row.repairStatus < 3"
                    type="button"
                    class="link-btn"
                    @click="openProgress(row)"
                  >
                    更新进度
                  </button>
                  <button
                    v-if="row.verifyResult === 1 && row.repairStatus === 3 && !row.acceptResult"
                    type="button"
                    class="link-btn"
                    @click="openAccept(row)"
                  >
                    维修验收
                  </button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="m-cards">
            <div v-for="row in records" :key="row.id" class="m-card">
              <div class="mc-head">
                <span class="mc-title" :title="row.toolName">{{ row.toolName }}</span>
                <span class="tag" :class="statusTag(row.repairStatus)[1]">
                  {{ statusTag(row.repairStatus)[0] }}
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">报修单号</span>
                <span class="mc-v"><span class="chip-code">{{ row.repairNo }}</span></span>
              </div>
              <div class="mc-row">
                <span class="mc-k">报修人</span>
                <span class="mc-v">{{ row.reporterName }} · {{ row.reporterPhone }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">损坏部位</span>
                <span class="mc-v">{{ row.damagePart || '—' }}</span>
              </div>
              <div class="mc-row">
                <span class="mc-k">核验结果</span>
                <span class="mc-v">
                  <span class="tag" :class="verifyTag(row.verifyResult)[1]">
                    {{ verifyTag(row.verifyResult)[0] }}
                  </span>
                </span>
              </div>
              <div class="mc-row">
                <span class="mc-k">维修费用</span>
                <span class="mc-v"><span class="money">{{ money(row.repairFee) }}</span></span>
              </div>
              <div class="mc-actions">
                <button type="button" class="link-btn" @click="openDetail(row)">详情</button>
                <button
                  v-if="!row.verifyResult"
                  type="button"
                  class="link-btn"
                  @click="openVerify(row)"
                >
                  现场核验
                </button>
                <button
                  v-if="row.verifyResult === 1 && row.repairStatus && row.repairStatus < 3"
                  type="button"
                  class="link-btn"
                  @click="openProgress(row)"
                >
                  更新进度
                </button>
                <button
                  v-if="row.verifyResult === 1 && row.repairStatus === 3 && !row.acceptResult"
                  type="button"
                  class="link-btn"
                  @click="openAccept(row)"
                >
                  维修验收
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

    <!-- 现场核验 -->
    <el-dialog v-model="verifyVisible" title="现场核验" width="460px" :close-on-click-modal="false">
      <p v-if="verifyTarget" class="dialog-sub">
        报修单：<span class="chip-code">{{ verifyTarget.repairNo }}</span><br />
        工具：<b>{{ verifyTarget.toolName }}</b>（{{ verifyTarget.toolCode }}）· 损坏部位：{{
          verifyTarget.damagePart
        }}<br />
        报修人：{{ verifyTarget.reporterName }} · 描述：{{ verifyTarget.damageDesc }}
      </p>
      <el-form label-position="top">
        <el-form-item label="核验结果" required>
          <el-radio-group v-model="verifyForm.verifyResult" class="radio-col">
            <el-radio :value="1">确认损坏（需维修）· 工具转入「维修中」</el-radio>
            <el-radio :value="2">疑似损坏（需检测）</el-radio>
            <el-radio :value="3">误报（关闭单据）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="核验备注">
          <el-input
            v-model="verifyForm.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="现场情况说明（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="verifySaving" @click="confirmVerify">提交核验</el-button>
      </template>
    </el-dialog>

    <!-- 更新进度 -->
    <el-dialog v-model="progressVisible" title="更新维修进度" width="460px" :close-on-click-modal="false">
      <p v-if="progressTarget" class="dialog-sub">
        报修单：<span class="chip-code">{{ progressTarget.repairNo }}</span> ·
        {{ progressTarget.toolName }}
      </p>
      <el-form label-position="top">
        <el-form-item label="维修进度" required>
          <el-select v-model="progressForm.status" class="full" placeholder="请选择">
            <el-option label="待配件" :value="1" />
            <el-option label="维修中" :value="2" />
            <el-option label="维修完成" :value="3" />
            <el-option label="维修失败（报废）" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="进度说明">
          <el-input
            v-model="progressForm.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="如：配件到货，更换夹头总成"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="progressVisible = false">取消</el-button>
        <el-button type="primary" :loading="progressSaving" @click="confirmProgress">更新进度</el-button>
      </template>
    </el-dialog>

    <!-- 维修验收 -->
    <el-dialog v-model="acceptVisible" title="维修验收" width="480px" :close-on-click-modal="false">
      <p v-if="acceptTarget" class="dialog-sub">
        报修单：<span class="chip-code">{{ acceptTarget.repairNo }}</span> ·
        {{ acceptTarget.toolName }}（{{ acceptTarget.toolCode }}）
      </p>
      <el-form label-position="top">
        <el-form-item label="验收结果" required>
          <el-radio-group v-model="acceptForm.acceptResult" class="radio-col">
            <el-radio :value="1">合格（恢复可用，重新上架）</el-radio>
            <el-radio :value="3">部分合格（限用）</el-radio>
            <el-radio :value="2">不合格（二次维修）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="工具目标状态">
          <el-select v-model="acceptForm.toolStatus" class="full">
            <el-option label="可用" :value="1" />
            <el-option label="维修中" :value="3" />
            <el-option label="报废" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="acceptForm.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="费用分摊、赔偿说明等（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" :loading="acceptSaving" @click="confirmAccept">确认验收</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="报修详情" size="560px">
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="kv-list">
            <div class="k">报修单号</div>
            <div class="v"><span class="chip-code">{{ detail.repairNo }}</span></div>
            <div class="k">关联租赁单</div>
            <div class="v"><span class="chip-code">{{ detail.orderNo || '—' }}</span></div>
            <div class="k">工具</div>
            <div class="v">{{ detail.toolName }}（{{ detail.toolCode }}）</div>
            <div class="k">报修人</div>
            <div class="v">{{ detail.reporterName }} · {{ detail.reporterPhone }}</div>
            <div class="k">损坏部位 / 描述</div>
            <div class="v">
              {{ detail.damagePart }}
              <div class="muted">{{ detail.damageDesc }}</div>
            </div>
            <template v-if="detailImages.length">
              <div class="k">材料图片</div>
              <div class="v">
                <div class="img-list">
                  <a
                    v-for="(img, index) in detailImages"
                    :key="img + index"
                    class="thumb"
                    :href="img"
                    target="_blank"
                    rel="noopener"
                  >
                    <img :src="img" :alt="`材料图片 ${index + 1}`" @error="hideImage" />
                  </a>
                </div>
              </div>
            </template>
            <div class="k">核验结果</div>
            <div class="v">
              <span class="tag" :class="verifyTag(detail.verifyResult)[1]">
                {{ verifyTag(detail.verifyResult)[0] }}
              </span>
              <span v-if="detail.verifyByName" class="tiny"> · {{ detail.verifyByName }}</span>
            </div>
            <div class="k">维修进度</div>
            <div class="v">
              <span class="tag" :class="statusTag(detail.repairStatus)[1]">
                {{ statusTag(detail.repairStatus)[0] }}
              </span>
            </div>
            <div class="k">维修费用</div>
            <div class="v"><span class="money">{{ money(detail.repairFee) }}</span></div>
            <template v-if="detail.acceptResult">
              <div class="k">验收结果</div>
              <div class="v">
                <span class="tag" :class="acceptTag(detail.acceptResult)[1]">
                  {{ acceptTag(detail.acceptResult)[0] }}
                </span>
                <span v-if="detail.acceptByName" class="tiny"> · {{ detail.acceptByName }}</span>
              </div>
            </template>
            <template v-if="detail.remark">
              <div class="k">备注</div>
              <div class="v">{{ detail.remark }}</div>
            </template>
            <div class="k">提交时间</div>
            <div class="v">{{ fmtDateTime(detail.createTime) }}</div>
          </div>

          <h4 class="timeline-title">维修进度</h4>
          <ul v-if="progressList.length" class="timeline">
            <li
              v-for="(item, index) in progressList"
              :key="item.id || index"
              :class="{ latest: index === progressList.length - 1 }"
            >
              <span class="node" />
              <div class="c">
                <span class="tag" :class="statusTag(item.status)[1]">{{ statusTag(item.status)[0] }}</span>
                <span class="progress-desc">{{ item.description }}</span>
              </div>
              <div class="t">{{ fmtDateTime(item.createTime) }}</div>
            </li>
          </ul>
          <div v-else class="timeline-empty">暂无进度记录</div>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关 闭</el-button>
      </template>
    </el-drawer>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, vLoading } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  acceptRepair,
  getRepair,
  pageRepairs,
  updateRepairProgress,
  verifyRepair,
} from '../../api/repair'
import type { RepairOrderVO, RepairQueryDTO } from '../../api/types'

const REPAIR_STATUS: Record<number, [string, string]> = {
  1: ['待配件', 't-warning'],
  2: ['维修中', 't-info'],
  3: ['维修完成', 't-success'],
  4: ['维修失败', 't-danger'],
}
const VERIFY_RESULT: Record<number, [string, string]> = {
  1: ['确认损坏', 't-danger'],
  2: ['疑似损坏', 't-warning'],
  3: ['误报', 't-gray'],
}
const ACCEPT_RESULT: Record<number, [string, string]> = {
  1: ['合格', 't-success'],
  2: ['不合格', 't-danger'],
  3: ['部分合格', 't-warning'],
}

const statusTag = (v: number) => REPAIR_STATUS[v] ?? ['未知', 't-gray']
const verifyTag = (v: number) => VERIFY_RESULT[v] ?? ['待核验', 't-warning']
const acceptTag = (v: number) => ACCEPT_RESULT[v] ?? ['未知', 't-gray']

function money(n?: number) {
  const [int, dec] = Number(n || 0).toFixed(2).split('.')
  return `¥${(int || '0').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${dec}`
}

function fmtDateTime(value?: string) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const repairStatus = ref<number | ''>('')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<RepairOrderVO[]>([])
const loading = ref(false)
const failed = ref(false)

const verifyVisible = ref(false)
const verifySaving = ref(false)
const verifyTarget = ref<RepairOrderVO | null>(null)
const verifyForm = reactive({ verifyResult: 1, remark: '' })

const progressVisible = ref(false)
const progressSaving = ref(false)
const progressTarget = ref<RepairOrderVO | null>(null)
const progressForm = reactive({ status: '' as number | '', description: '' })

const acceptVisible = ref(false)
const acceptSaving = ref(false)
const acceptTarget = ref<RepairOrderVO | null>(null)
const acceptForm = reactive({ acceptResult: 1, toolStatus: 1, remark: '' })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RepairOrderVO | null>(null)

const detailImages = computed(() => detail.value?.images ?? [])
const progressList = computed(() =>
  [...(detail.value?.progressList ?? [])].sort((a, b) =>
    (a.createTime || '').localeCompare(b.createTime || ''),
  ),
)

async function load() {
  loading.value = true
  failed.value = false
  const params: RepairQueryDTO = { page: page.value, size: size.value }
  if (keyword.value.trim()) params.toolCode = keyword.value.trim()
  if (repairStatus.value !== '') params.repairStatus = repairStatus.value

  syncUrl()
  try {
    const result = await pageRepairs(params)
    records.value = result.records
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

let keywordTimer: ReturnType<typeof setTimeout> | undefined
function onKeywordInput() {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(applyFilter, 300)
}

function reset() {
  keyword.value = ''
  repairStatus.value = ''
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
      ...(keyword.value.trim() ? { code: keyword.value.trim() } : {}),
      ...(repairStatus.value !== '' ? { status: String(repairStatus.value) } : {}),
      ...(page.value > 1 ? { page: String(page.value) } : {}),
    },
  })
}

function openVerify(row: RepairOrderVO) {
  verifyTarget.value = row
  verifyForm.verifyResult = 1
  verifyForm.remark = ''
  verifyVisible.value = true
}

async function confirmVerify() {
  if (!verifyTarget.value) return
  verifySaving.value = true
  try {
    await verifyRepair(verifyTarget.value.id, {
      verifyResult: verifyForm.verifyResult,
      remark: verifyForm.remark.trim() || undefined,
    })
    const messages: Record<number, string> = {
      1: '核验确认，工具已转「维修中」',
      2: '核验结果：疑似损坏',
      3: '核验结果：误报',
    }
    ElMessage.success(messages[verifyForm.verifyResult] || '核验结果已提交')
    verifyVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    verifySaving.value = false
  }
}

function openProgress(row: RepairOrderVO) {
  progressTarget.value = row
  progressForm.status = ''
  progressForm.description = ''
  progressVisible.value = true
}

async function confirmProgress() {
  if (!progressTarget.value) return
  if (progressForm.status === '') {
    ElMessage.warning('请选择维修进度')
    return
  }
  progressSaving.value = true
  try {
    await updateRepairProgress(progressTarget.value.id, {
      status: progressForm.status,
      description: progressForm.description.trim() || undefined,
    })
    ElMessage.success('维修进度已更新')
    progressVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    progressSaving.value = false
  }
}

function openAccept(row: RepairOrderVO) {
  acceptTarget.value = row
  acceptForm.acceptResult = 1
  acceptForm.toolStatus = 1
  acceptForm.remark = ''
  acceptVisible.value = true
}

async function confirmAccept() {
  if (!acceptTarget.value) return
  acceptSaving.value = true
  try {
    await acceptRepair(acceptTarget.value.id, {
      acceptResult: acceptForm.acceptResult,
      toolStatus: acceptForm.toolStatus,
      remark: acceptForm.remark.trim() || undefined,
    })
    const messages: Record<number, string> = {
      1: '验收合格，工具重新上架',
      2: '验收不合格，进入二次维修',
      3: '验收：部分合格（限用）',
    }
    const message = messages[acceptForm.acceptResult] || '验收完成'
    if (acceptForm.acceptResult === 1) ElMessage.success(message)
    else ElMessage.warning(message)
    acceptVisible.value = false
    await load()
  } catch {
    // 拦截器已统一提示
  } finally {
    acceptSaving.value = false
  }
}

async function openDetail(row: RepairOrderVO) {
  detail.value = row
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await getRepair(row.id)
  } catch {
    // 保留列表数据作为兜底
  } finally {
    detailLoading.value = false
  }
}

function hideImage(e: Event) {
  ;(e.target as HTMLImageElement).style.display = 'none'
}

onMounted(() => {
  const q = route.query
  keyword.value = typeof q.code === 'string' ? q.code : ''
  const status = Number(q.status)
  repairStatus.value = [1, 2, 3, 4].includes(status) ? status : ''
  const p = Number(q.page)
  page.value = p > 0 ? p : 1
  load()
})
</script>

<style scoped>
.admin-repairs {
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
.filter-bar .f-search {
  width: 246px;
}
.filter-bar .f-item {
  width: 166px;
}
.filter-bar .grow {
  flex: 1;
}
.filter-bar :deep(.el-input__wrapper),
.filter-bar :deep(.el-select__wrapper) {
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--r-2);
  box-shadow: 0 0 0 1px var(--border-2) inset;
}
.filter-bar :deep(.el-input__wrapper:hover),
.filter-bar :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary-7) inset;
}
.filter-bar :deep(.el-input__wrapper.is-focus),
.filter-bar :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary-5) inset, 0 0 0 3px rgba(28, 58, 53, 0.09);
}
.filter-bar :deep(.el-input__inner),
.filter-bar :deep(.el-select__placeholder) {
  font-size: 13.5px;
  color: var(--text);
}
.filter-bar :deep(.el-button),
.empty-box :deep(.el-button) {
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-2);
  font-size: 13.5px;
  font-weight: 500;
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
.filter-bar :deep(.el-button:not(.el-button--primary)),
.empty-box :deep(.el-button:not(.el-button--primary)) {
  border-color: var(--border-2);
  color: var(--text-2);
}
.filter-bar :deep(.el-button:not(.el-button--primary):hover) {
  color: var(--primary);
  border-color: var(--primary-7);
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
.repair-table {
  width: 100%;
  font-size: 13px;
}
.repair-table :deep(th.el-table__cell) {
  padding: 9px 12px;
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--primary-3);
  background: var(--primary-tint);
}
.repair-table :deep(td.el-table__cell) {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border);
}
.repair-table :deep(.el-table__row:hover > td.el-table__cell) {
  background: var(--surface-2);
}
.repair-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.repair-table :deep(.cell) {
  padding: 0;
  overflow: visible;
  text-overflow: clip;
  word-break: normal;
  white-space: normal;
}
.tool-name {
  overflow: hidden;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.phone {
  white-space: nowrap;
}
.part {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
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
.money {
  font-weight: 600;
  color: var(--accent-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.tiny {
  font-size: 11.5px;
  color: var(--text-3);
}
.muted {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
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

/* ---------- 弹窗 / 抽屉 ---------- */
.dialog-sub {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-2);
}
.full {
  width: 100%;
}
.radio-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.kv-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  font-size: 13px;
}
.kv-list .k {
  color: var(--text-2);
}
.kv-list .v {
  margin: -6px 0 0;
  color: var(--text);
  word-break: break-all;
}
.img-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.thumb {
  display: inline-flex;
  width: 56px;
  height: 56px;
  overflow: hidden;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--r-2);
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.timeline-title {
  margin: 18px 0 10px;
  font-size: 15px;
  font-weight: 600;
}
.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}
.timeline li {
  position: relative;
  margin-left: 5px;
  padding: 0 0 16px 22px;
  border-left: 1px solid var(--border);
}
.timeline li:last-child {
  padding-bottom: 0;
  border-left-color: transparent;
}
.timeline .node {
  position: absolute;
  top: 4px;
  left: -5.5px;
  width: 10px;
  height: 10px;
  background: var(--primary-7);
  border: 2px solid #fff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--border);
}
.timeline li.latest .node {
  background: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-tint);
}
.timeline .c {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  line-height: 1.6;
}
.timeline .t {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.progress-desc {
  color: var(--text);
}
.timeline-empty {
  padding: 16px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}
.drawer-body {
  min-height: 200px;
}

@media (max-width: 768px) {
  .page-head h1 {
    font-size: 18px;
  }
  .filter-bar .f-search,
  .filter-bar .f-item {
    width: 100%;
  }
  .filter-bar .grow {
    display: none;
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
