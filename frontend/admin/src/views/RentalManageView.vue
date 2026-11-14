<script setup lang="ts">
/**
 * ============================================================================
 * 文件：frontend/admin/src/views/RentalManageView.vue
 * 角色：管理端租赁单运营工作台（查询 + 状态流转 + 结算展示 + 导出）
 * ============================================================================
 *
 * 页面职责
 * --------
 * 1. 分页展示租赁单，支持关键词、租赁状态、逾期状态、验收结果四类筛选。
 * 2. 对单条租赁单执行三种运营动作：确认取件、归还验收、取消租赁。
 * 3. 通过详情抽屉展示租赁单全量字段、结算口径、费用明细、支付流水与流转时间线。
 * 4. 按当前筛选条件导出 Excel，导出集合与列表筛选口径保持一致。
 *
 * 数据来源
 * --------
 * 所有读写都通过 @/api/rentals 暴露的 rentalApi 完成，对应后端 /api/admin/rentals：
 * - list   -> GET  /admin/rentals
 * - detail -> GET  /admin/rentals/{id}
 * - pickup -> POST /admin/rentals/{id}/pickup
 * - return -> POST /admin/rentals/{id}/return
 * - cancel -> POST /admin/rentals/{id}/cancel
 * - export -> GET  /admin/rentals/export（下载二进制 xlsx）
 * 页面不拼 URL、不处理鉴权头；请求拦截器统一附加管理员令牌并解包 Result。
 *
 * 租赁状态机（与后端 RentalStateMachine 严格一致）
 * ------------------------------------------------
 * PENDING_PAYMENT（待支付） -> RESERVED（已预约）/ CANCELLED（已取消）
 * RESERVED（已预约）        -> PICKED_UP（使用中）/ CANCELLED（已取消）
 * PICKED_UP（使用中）       -> OVERDUE（已逾期）/ RETURNED（已归还）
 * OVERDUE（已逾期）         -> RETURNED（已归还）
 * RETURNED（已归还）与 CANCELLED（已取消）均为终态，不再允许任何流转。
 * 页面按钮只是把状态机可视化：canCancel / canReturn 决定按钮是否出现，
 * 真正的合法性仍由后端断言，非法跃迁返回 409。
 *
 * 结算口径（与后端 SettlementCalculator 一致）
 * -------------------------------------------
 * - 逾期费：实际归还时间晚于结束时间时，超出部分按小时向上取整，每小时 5 元，封顶为押金。
 * - 损坏赔偿：仅当验收结果为 SEVERE（严重损坏）时产生，金额由后端规则决定。
 * - 减免：waiveOverdue / waiveDamage 为全额减免对应费用，必须填写原因，否则后端拒绝。
 * - 退款：押金 - 逾期费 - 损坏赔偿 + 减免，最低为 0；费用超过押金时差额成为应收。
 * - 金额一律由服务端计算，本页只收集业务事实（时间、验收结论、减免标志），
 *   绝不提交或覆盖金额字段。
 *
 * 交互约定
 * --------
 * - 任一状态流转成功后，先刷新列表，再按 id 刷新当前详情抽屉，避免详情显示旧状态。
 * - 详情抽屉打开时先用列表行占位，再异步拉取完整详情（含 fees / payments 数组）。
 * - 所有改变状态的操作都要求弹窗确认或填写，降低误触风险。
 * - 列表空态、加载态、导出中状态分别由 list.length、loading、exporting 控制。
 *
 * 组合式 API 依赖
 * ---------------
 * - computed：派生详情时间线与总页数，避免在模板中重复计算。
 * - onMounted：首屏进入页面即加载第一页数据。
 * - reactive：筛选条件与归还表单这类多字段对象，使用 reactive 便于 v-model 直接绑定。
 * - ref：加载态、结果集合、弹窗目标等单值状态。
 *
 * Element Plus 反馈
 * -----------------
 * - ElMessage：成功/警告等轻量提示，用于操作结果的即时反馈。
 * - ElMessageBox.prompt：取消租赁时弹出原因输入框，内置非空校验与取消语义。
 *
 * 图标
 * ----
 * Download / Refresh / Search 三个图标分别服务导出、重置、查询按钮。
 *
 * 接口封装
 * --------
 * rentalApi 是本页唯一的租赁接口入口，集中封装列表、详情、取件、归还、取消与导出；
 * 页面不直接依赖 http 实例，便于接口路径或拦截逻辑集中调整。
 *
 * 类型导入
 * --------
 * - DamageResult：归还验收结论（完好 / 轻微损坏 / 严重损坏）。
 * - RentalReturnDTO：归还请求体形状，含实际归还时间、验收结果、减免与备注。
 * - RentalVO：租赁单视图对象，详情额外带有 fees、payments 等数组。
 *
 * 参数清洗
 * --------
 * cleanParams 在发送前剔除空串/null/undefined。
 * 本页筛选以空串表示「全部」，若不清洗，后端会把空串当作有效精确匹配条件。
 *
 * 格式化工具
 * ----------
 * - exportTimestamp：生成带时间戳的导出文件名，避免同名文件被浏览器覆盖。
 * - formatDateTime：统一时间展示，空值返回占位符而不是 Invalid Date。
 * - formatMoney：金额保留两位小数并加货币符号，保证列表与详情口径一致。
 * - resolveFileUrl：把对象存储相对路径解析为可直接用于 img.src 的绝对地址。
 *
 * 展示辅助
 * --------
 * badgeClass：根据后端标签（如 success / warning / danger）返回徽章样式类，
 * 让状态文案与颜色映射集中在一处，避免页面散落颜色判断。
 *
 * 枚举工具
 * --------
 * - DAMAGE_RESULT_LABELS / DEPOSIT_STATUS_LABELS / RENTAL_STATUS_LABELS：
 *   枚举值与中文标签映射，由契约生成，新增枚举值无需改本页。
 * - depositStatusTag / rentalStatusTag：枚举值到徽章语义色的映射。
 * - enumLabel：在 VO 未返回 *Name 时提供兜底中文文案。
 * - optionList：把标签映射转换为下拉选项数组，供筛选项渲染。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import { rentalApi } from '@/api/rentals'
import type { DamageResult, RentalReturnDTO, RentalVO } from '@/api/types'
import { cleanParams } from '@/utils/params'
import { exportTimestamp, formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
import { badgeClass } from '@/utils/badge'
import {
  DAMAGE_RESULT_LABELS,
  DEPOSIT_STATUS_LABELS,
  RENTAL_STATUS_LABELS,
  depositStatusTag,
  enumLabel,
  optionList,
  rentalStatusTag,
} from '@/utils/enums'

/**
 * 列表加载态。
 * loadList 开始时置 true，finally 中复位，保证请求异常时也能解除 loading；
 * 模板可据此展示骨架或禁用交互（本页主要用于空态判断）。
 */
/**
 * 页面状态分组总览（便于按生命周期理解本文件）：
 *
 * 一、列表状态
 * - loading：列表请求进行中
 * - list / total：当前页数据与筛选结果总数
 * - query：筛选与分页条件
 * - exporting：Excel 导出进行中
 *
 * 二、详情状态
 * - detailVisible / detailLoading / detail：抽屉开关、加载态与完整详情
 *
 * 三、取件状态
 * - pickupVisible / pickupSaving / pickupTarget：取件确认弹窗
 *
 * 四、归还状态
 * - returnDialogVisible / returnSaving / returnTarget：归还验收弹窗
 * - returnForm：归还验收表单
 *
 * 每组状态彼此独立：弹窗关闭不会清空目标对象，但每次打开都会重置表单或覆盖目标，
 * 从而避免上一单残留数据影响下一单操作。
 */
const loading = ref(false)
/**
 * 当前页租赁单数据。
 * 直接使用后端 PageResult.records，不在前端二次排序；
 * 列表顺序由后端按创建时间倒序保证。
 */
const list = ref<RentalVO[]>([])
/**
 * 符合条件的租赁单总数（不是当前页条数）。
 * 用于底部「共 N 条」以及 pageCount 计算。
 */
const total = ref(0)
/**
 * 列表筛选与分页条件，直接与表单控件双向绑定。
 * - page / size：页码从 1 开始，默认每页 10 条。
 * - keyword：单号、工具名称、租用人模糊搜索。
 * - status：租赁状态枚举，空串表示全部。
 * - overdue：'all' | 'true' | 'false'，控制是否只看逾期。
 * - damageResult：验收结果枚举，空串表示全部。
 * 发送请求前必须经 cleanParams 清洗，空串不会传给后端。
 */
const query = reactive({ page: 1, size: 10, keyword: '', status: '', overdue: 'all', damageResult: '' })

/**
 * 租赁状态下拉选项。
 * 由 RENTAL_STATUS_LABELS 生成，后端新增枚举值后重新生成标签映射即可同步，
 * 本页无需维护第二份状态常量。
 */
const statusOptions = optionList(RENTAL_STATUS_LABELS)
/**
 * 验收结果下拉选项。
 * 既用于列表筛选，也复用为归还弹窗中的验收结果选择。
 */
const damageResultOptions = optionList(DAMAGE_RESULT_LABELS)
/**
 * 逾期筛选选项。
 * 使用字符串值与后端 query 参数协议保持一致：
 * all 不筛选，true 仅逾期，false 仅未逾期。
 */
const overdueOptions = [
  { value: 'all', label: '全部逾期状态' },
  { value: 'true', label: '仅看逾期' },
  { value: 'false', label: '未逾期' },
]

/**
 * 加载租赁单分页列表。
 *
 * 行为：
 * 1. 以当前 query 为参数，经 cleanParams 去除空筛选条件后调用 rentalApi.list。
 * 2. 用返回的 records 覆盖当前页数据，用 total 更新总数。
 * 3. finally 中统一复位 loading；请求失败的错误由请求拦截器提示，
 *    这里只保证本页加载态不会卡死。
 *
 * 副作用：覆盖 list 与 total；不修改 query.page，翻页由调用方先行修改。
 * 调用时机：首次挂载、查询、重置、翻页，以及取件/归还/取消成功后。
 */
/**
 * 补充说明：分页与筛选的请求路径
 * - query 对象整体展开后交给 cleanParams，避免手写参数拼装遗漏字段。
 * - size 固定由 query.size 控制，后端默认 10，本页未提供每页条数切换。
 * - 后端在分页前会先刷新逾期状态（把到期的使用中订单标记为 OVERDUE），
 *   因此列表中的逾期筛选结果与当前时间相关，长时间停留后可重新查询。
 * - records 与 total 在一次响应中同时返回，不会出现总数与当前页不一致。
 */
/**
 * 返回值约定：rentalApi.list 解包后直接是 PageResult，包含 records 与 total；
 * 请求失败会 reject 并跳过赋值，因此列表保留上一次成功结果。
 * 若后端把 overdue 标记刷新到新状态，本次返回的 status 已是最新值。
 */
async function loadList() {
  loading.value = true
  try {
    const data = await rentalApi.list(cleanParams({ ...query }))
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/**
 * 提交查询。
 * 每次新查询都把页码重置为 1，避免停留在旧结果末页导致空列表；
 * 随后按新条件重新加载。
 */
/**
 * 查询只重置页码，不清空筛选条件。
 * 关键词回车与点击查询走同一函数，行为完全一致；
 * 不清空输入框是为了让用户可以在原条件下微调后再次查询。
 */
/**
 * 页码重置必须在请求前完成，否则 loadList 会带着旧页码查询，
 * 出现「新条件 + 旧页码」导致的空页。
 */
function handleSearch() {
  query.page = 1
  loadList()
}
/**
 * 重置筛选条件。
 * 清空关键词、状态、逾期、验收结果四项，并回到第一页，
 * 然后立即重新加载全部数据；分页大小保持不变。
 */
/**
 * 重置不会调用后端专用接口，只是把 query 恢复为初始值后复用 loadList。
 * 逾期筛选恢复为 all；验收结果与状态恢复为空串（全部）。
 */
/**
 * 重置后立即查询，不等待用户再点一次「查询」；
 * 这是管理端列表的常见交互约定，减少一步操作。
 */
function handleReset() {
  query.keyword = ''
  query.status = ''
  query.overdue = 'all'
  query.damageResult = ''
  query.page = 1
  loadList()
}

/**
 * 导出进行中状态。
 * 导出是文件下载请求，耗时可能明显长于普通列表请求；
 * 用它禁用按钮并防止重复点击产生多份下载。
 */
const exporting = ref(false)
/**
 * 导出当前筛选结果为 Excel。
 *
 * 行为：
 * 1. 复用列表筛选条件（同样经过 cleanParams），保证导出集合与列表口径一致。
 * 2. rentalApi.export 返回 Blob，后端以二进制流输出 xlsx。
 * 3. 通过临时 a 标签与 URL.createObjectURL 触发浏览器下载，
 *    文件名带 exportTimestamp 时间戳。
 * 4. 下载触发后移除节点并 revokeObjectURL，避免内存泄漏。
 *
 * 注意：导出不受分页限制，后端按筛选条件导出全量结果；
 * 若后端以 JSON 错误 envelope 返回，请求拦截器会解析并提示，不会下载损坏文件。
 */
/**
 * 导出异常处理说明：
 * - 导出接口与列表接口共用筛选参数，但响应类型是 blob。
 * - 请求拦截器在 responseType=blob 时直接返回二进制；若后端返回 JSON 错误，
 *   拦截器会解析错误提示，Promise reject。
 * - 本函数没有 catch，错误提示由拦截器统一完成；finally 只负责复位导出状态。
 * - 文件名使用中文前缀与时间戳，避免多次导出互相覆盖。
 */
/**
 * 浏览器下载流程说明：
 * - URL.createObjectURL 生成的地址只在当前文档生命周期内有效。
 * - a 标签必须挂到 document 上再 click，部分浏览器否则不会触发下载。
 * - revokeObjectURL 在 click 后同步调用即可，浏览器已完成下载任务接管。
 * - 若文件名包含中文，浏览器会按自身规则编码，后端响应头中的文件名作为兜底。
 */
async function handleExport() {
  exporting.value = true
  try {
    const blob = await rentalApi.export(cleanParams({ ...query }))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `租赁单_${exportTimestamp()}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('Excel 导出成功')
  } finally {
    exporting.value = false
  }
}

/**
 * 详情抽屉是否可见。
 * 关闭时仅隐藏抽屉，不清空 detail，便于再次打开时快速展示；
 * 每次打开仍会重新拉取详情覆盖缓存。
 */
const detailVisible = ref(false)
/**
 * 详情加载态。
 * 抽屉内展示骨架屏，避免在旧数据与新数据之间闪烁。
 */
const detailLoading = ref(false)
/**
 * 当前详情对象。
 * 打开抽屉时先用列表行做即时占位，随后 fetchDetail 用完整详情替换，
 * 因此可能短暂包含不含 fees / payments 的列表数据。
 * 使用前必须判空，模板以 v-if="detail" 保护。
 */
const detail = ref<RentalVO | null>(null)

/**
 * 拉取单条租赁单完整详情。
 *
 * @param id 租赁单 ID
 * 成功后覆盖 detail，包含费用明细 fees、支付流水 payments、
 * 实际归还时间与减免后的退款金额等列表接口不返回的字段。
 * 请求失败时保留旧 detail，错误由拦截器提示。
 */
/**
 * 详情请求与列表请求的差异：
 * - 列表行只包含摘要字段，详情额外返回 fees、payments、returnRemark、
 *   cancelReason、refundAmount 等结算与审计字段。
 * - 详情可能在打开抽屉的瞬间被列表行占位，因此 detailLoading 用于模板展示骨架屏。
 * - detail 为空时（例如记录被删除）抽屉显示「暂无详情」，不抛异常。
 */
/**
 * 详情接口是幂等只读请求，可安全重复调用；
 * 刷新详情不会改变后端任何状态，也不会影响列表分页。
 */
async function fetchDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await rentalApi.detail(id)
  } finally {
    detailLoading.value = false
  }
}
/**
 * 打开详情抽屉。
 *
 * @param row 列表中的租赁单行
 * 先用行数据占位以获得即时渲染，再异步拉取完整详情；
 * 不等待详情返回就打开抽屉，避免点击后长时间无反馈。
 */
/**
 * 打开详情不校验 row.status，任何状态的租赁单都允许查看详情；
 * 详情是只读的，不会触发任何状态变更。
 * 占位数据让抽屉立即有标题与基础信息，避免空白等待。
 */
function openDetail(row: RentalVO) {
  detail.value = row
  detailVisible.value = true
  fetchDetail(row.id)
}
/**
 * 若详情抽屉正展示该租赁单，则刷新详情。
 *
 * @param id 刚刚发生状态变化的租赁单 ID
 * 仅当抽屉可见且 detail.id 匹配时才请求，避免刷新已关闭或已切换的详情；
 * 这样取件、归还、取消成功后，抽屉里的状态与结算信息能立即更新。
 */
/**
 * 防止详情请求竞态：
 * - 只有当前抽屉仍打开且仍指向同一租赁单时才刷新，避免用户已切换到别的详情后，
 *   旧请求的结果覆盖新详情。
 * - 这里按 id 比对而不是按对象引用比对，因为刷新后 detail 会被新对象替换。
 */
async function refreshCurrentDetail(id: number) {
  if (detailVisible.value && detail.value?.id === id) await fetchDetail(id)
}

/**
 * 取件确认弹窗是否可见。
 */
const pickupVisible = ref(false)
/**
 * 取件提交进行中状态。
 * 用于禁用确认按钮并显示「处理中…」，防止重复提交。
 */
const pickupSaving = ref(false)
/**
 * 取件弹窗的目标租赁单。
 * 弹窗只读展示其单号、工具、取件人与租期，不提供可编辑字段。
 */
const pickupTarget = ref<RentalVO | null>(null)

/**
 * 打开取件确认弹窗。
 *
 * @param row 状态为 RESERVED 的租赁单行
 * 仅记录目标并显示弹窗，真正的取件请求在用户点击确认后发出。
 */
/**
 * 取件弹窗不在这里做状态校验，按钮的 v-if 已保证只有 RESERVED 可见；
 * 即使通过其他方式触发，后端状态机也会拒绝非法取件。
 */
/**
 * 弹窗标题展示单号与工具名称，让工作人员在确认前再次核对目标；
 * 目标为 null 时模板使用可选链兜底，不会报错。
 */
function openPickup(row: RentalVO) {
  pickupTarget.value = row
  pickupVisible.value = true
}

/**
 * 提交取件确认。
 *
 * 前置约束（与后端一致）：
 * - 租赁单必须处于 RESERVED 状态，按钮本身已按状态显示。
 * - 押金必须已支付，否则后端返回 409，弹窗内提示先核对支付状态。
 * - 工具必须处于可借出状态，否则后端拒绝。
 *
 * 副作用：后端把租赁单推进为 PICKED_UP、工具变为已租出、租借次数加一，
 * 并发送站内消息。成功后本页刷新列表与当前详情；
 * 失败时保持弹窗打开，loading 在 finally 中复位。
 */
/**
 * 取件成功后必须同时刷新列表与详情：
 * - 列表刷新让该行状态从「已预约」变为「使用中」，并更新操作按钮。
 * - 详情刷新让抽屉中的状态徽章与流转记录同步更新。
 * - 先 await loadList 再刷新详情，保证两个请求都完成后弹窗才视为结束。
 * - pickupTarget 不从后端重新赋值，弹窗关闭后其值仅供下次打开覆盖。
 */
/**
 * 提交期间不关闭弹窗，只有请求成功才关闭；
 * 若后端返回押金未支付或状态冲突，用户仍能看到弹窗并联系相关人员处理。
 */
async function submitPickup() {
  const target = pickupTarget.value
  if (!target) return
  pickupSaving.value = true
  try {
    await rentalApi.pickup(target.id)
    ElMessage.success('已确认取件')
    pickupVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    pickupSaving.value = false
  }
}

/**
 * 详情抽屉中的「流转记录」时间线。
 *
 * 数据来源：完全由当前 detail 推导，不额外请求接口。
 * 展示顺序按业务发生顺序排列：提交预约、支付押金、取件、归还、取消。
 *
 * 口径说明：
 * - 后端未单独保存支付时刻与取件时刻，因此这两项 time 显示为「—」，
 *   只确认事件确实发生（押金非 UNPAID、状态已越过取件）。
 * - 提交预约时间使用 createTime，取件人取自 pickupPerson。
 * - 归还项使用 actualReturnTime，并展示验收结果的中文标签。
 * - 取消项使用 cancelTime 与 cancelReason，缺省原因回退为「工作人员取消」。
 *
 * 返回空数组表示尚无详情可展示，模板会渲染空时间线。
 */
/**
 * 时间线各节点的判定条件补充：
 * - 「提交预约」始终存在，因为任何租赁单都必然有创建时刻。
 * - 「支付押金」仅在 depositStatus 不为 UNPAID 时出现，已退款也视为曾支付。
 * - 「取件」在状态为 PICKED_UP / RETURNED / OVERDUE 时出现，
 *   说明订单已经越过取件节点，即使当前已是终态也能回溯。
 * - 「归还」仅在 actualReturnTime 有值时出现，时长与验收结果一并展示。
 * - 「取消」仅在状态为 CANCELLED 时出现，原因缺失时使用默认文案。
 * 时间线不展示未发生的未来节点，避免用户误以为流程已推进。
 */
/**
 * 时间线的类型约定：
 * - title：节点名称；
 * - time：可展示的时间字符串，缺失时为「—」；
 * - desc：补充说明，例如取件人、验收结果或取消原因。
 * 模板按 index 判断是否为最后一项，用于区分「当前节点」与「已完成节点」。
 */
const detailTimeline = computed(() => {
  const row = detail.value
  if (!row) return []
  // 后端未单独保存支付/取件时刻，这里只展示确切的提交、归还与取消时间
  const list: Array<{ title: string; time: string; desc: string }> = [
    { title: '提交预约', time: formatDateTime(row.createTime), desc: `取件人 ${row.pickupPerson || '—'}` },
  ]
  if (row.depositStatus !== 'UNPAID') {
    list.push({ title: '支付押金', time: '—', desc: `押金 ${formatMoney(row.depositAmount)} 已支付` })
  }
  if (['PICKED_UP', 'RETURNED', 'OVERDUE'].includes(row.status)) {
    list.push({ title: '取件', time: '—', desc: `取件人 ${row.pickupPerson || '—'} 已取件` })
  }
  if (row.actualReturnTime) {
    list.push({
      title: '归还',
      time: formatDateTime(row.actualReturnTime),
      desc: `验收结果：${enumLabel(DAMAGE_RESULT_LABELS, row.damageResult)}`,
    })
  }
  if (row.status === 'CANCELLED') {
    list.push({ title: '已取消', time: formatDateTime(row.cancelTime), desc: row.cancelReason || '工作人员取消' })
  }
  return list
})

/**
 * 归还验收弹窗是否可见。
 */
const returnDialogVisible = ref(false)
/**
 * 归还提交进行中状态。
 * 禁用「确认归还」按钮，防止重复提交导致重复结算。
 */
const returnSaving = ref(false)
/**
 * 归还弹窗的目标租赁单。
 * 弹窗只读展示其单号与押金金额，作为填写验收信息的上下文。
 */
const returnTarget = ref<RentalVO | null>(null)
/**
 * 归还验收表单。
 * 只收集业务事实与减免意图，金额全部由后端计算：
 * - damageResult：验收结论，默认 INTACT（完好）。
 * - returnTime：实际归还时间，datetime-local 字符串，打开弹窗时预填当前时间。
 * - waiveOverdue / waiveDamage：是否全额减免逾期费 / 损坏赔偿。
 * - waiverReason：任一减免开启时必填，便于审计与向用户说明。
 * - returnWithDamage：存在未终结报修时显式确认带伤归还，工具保持维修中。
 * - remark：归还备注，带伤归还时作为保持维修中的原因写入状态日志。
 */
const returnForm = reactive<{
  damageResult: DamageResult
  returnTime: string
  waiveOverdue: boolean
  waiveDamage: boolean
  waiverReason: string
  returnWithDamage: boolean
  remark: string
}>({
  damageResult: 'INTACT',
  returnTime: '',
  waiveOverdue: false,
  waiveDamage: false,
  waiverReason: '',
  returnWithDamage: false,
  remark: '',
})

/**
 * 生成 datetime-local 输入框所需的本地时间字符串（YYYY-MM-DDTHH:mm）。
 *
 * 使用本地时区而非 UTC，避免表单默认值与用户本地时间相差数小时；
 * 秒级信息被省略，分钟精度已满足归还验收场景。
 */
/**
 * 使用补零保证月、日、时、分都是两位，符合 datetime-local 的格式要求；
 * 不引入日期库，几行原生 Date 运算即可满足需求。
 */
/**
 * 该函数不依赖组件状态，是纯函数；
 * 每次调用都读取系统当前时间，因此弹窗打开时的默认值总是最新的。
 */
function nowInput(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 打开归还验收弹窗。
 *
 * @param row 状态为 PICKED_UP 或 OVERDUE 的租赁单行
 * 每次打开都把表单重置为安全默认值：
 * - 验收结果回到完好；
 * - 实际归还时间预填当前本地时间；
 * - 所有减免与带伤归还标志清零，避免沿用上一条租赁单的选择；
 * - 减免原因与备注清空。
 */
/**
 * 重置表单而不是复用上次输入，是为了避免把上一条订单的减免原因
 * 误带到当前订单，造成审计记录与实际不符。
 */
/**
 * 打开前不校验订单是否已逾期：逾期订单同样允许归还，
 * 并且逾期费会由后端按实际归还时间计算，前端不预判金额。
 */
function openReturn(row: RentalVO) {
  returnTarget.value = row
  returnForm.damageResult = 'INTACT'
  returnForm.returnTime = nowInput()
  returnForm.waiveOverdue = false
  returnForm.waiveDamage = false
  returnForm.waiverReason = ''
  returnForm.returnWithDamage = false
  returnForm.remark = ''
  returnDialogVisible.value = true
}

/**
 * 提交归还验收。
 *
 * 前端校验（后端会再次校验）：
 * 1. 实际归还时间必填，日期时间控件可能被用户清空。
 * 2. 只要勾选了任一减免，减免原因必填，防止无理由减免。
 *
 * 请求体只包含业务事实：
 * - actualReturnTime、damageResult 必传。
 * - waiveOverdue / waiveDamage 仅在为 true 时显式传 true，保持请求精简。
 * - 有减免时附带去除首尾空格的 waiverReason。
 * - returnWithDamage 仅在勾选时传 true。
 * - remark 仅在非空时传，长度上限由控件与后端共同约束。
 *
 * 后端结算规则：
 * - 按实际归还时间计算逾期费（每小时 5 元，封顶押金）。
 * - 验收结果为 SEVERE 时产生损坏赔偿。
 * - 减免只影响本次退款，不覆盖费用明细上的原始金额。
 * - 退款 = 押金 - 净费用，最低为 0；超出押金的部分记为应收。
 *
 * 副作用：租赁单推进为 RETURNED，写入费用、支付/退款流水与信用分记录，
 * 并按验收结果决定工具恢复到可用、转维修中或带伤保持维修中。
 * 成功后刷新列表与当前详情；失败时弹窗保持打开以便修正。
 */
/**
 * 注意：本页不计算也不传递任何金额字段。
 * 后端会根据 actualReturnTime 与 damageResult 重新计算费用，
 * 即使前端被篡改也无法影响结算结果。
 * 减免标志为 true 时后端会校验 waiverReason，前端提前校验只是为了减少无效请求。
 */
/**
 * 提交顺序上的注意点：
 * - 前端先做必填校验，再组装 payload，最后才置 saving，避免校验失败时按钮短暂禁用。
 * - 成功后先关闭弹窗再刷新数据，用户能立即看到操作已完成。
 * - 刷新列表会更新该行状态与费用列，刷新详情会更新结算明细与流转记录。
 * - finally 复位 saving，即使用户连续打开多个订单也不会互相影响。
 */
async function submitReturn() {
  const target = returnTarget.value
  if (!target) return
  if (!returnForm.returnTime) {
    ElMessage.warning('请填写实际归还时间')
    return
  }
  const waiving = returnForm.waiveOverdue || returnForm.waiveDamage
  if (waiving && !returnForm.waiverReason.trim()) {
    ElMessage.warning('减免必须填写原因')
    return
  }
  const payload: RentalReturnDTO = {
    actualReturnTime: returnForm.returnTime,
    damageResult: returnForm.damageResult,
  }
  if (returnForm.waiveOverdue) payload.waiveOverdue = true
  if (returnForm.waiveDamage) payload.waiveDamage = true
  if (waiving) payload.waiverReason = returnForm.waiverReason.trim()
  if (returnForm.returnWithDamage) payload.returnWithDamage = true
  if (returnForm.remark.trim()) payload.remark = returnForm.remark.trim()
  returnSaving.value = true
  try {
    await rentalApi.return(target.id, payload)
    ElMessage.success('归还验收已完成')
    returnDialogVisible.value = false
    await loadList()
    await refreshCurrentDetail(target.id)
  } finally {
    returnSaving.value = false
  }
}

/**
 * 工作人员取消租赁单。
 *
 * 流程：
 * 1. 使用 ElMessageBox.prompt 弹出原因输入框，原因不能为空或纯空白。
 * 2. 用户点击取消或关闭弹窗时 Promise 抛错，直接返回，不发起请求。
 * 3. 调用 rentalApi.cancel 提交原因；成功后提示并刷新列表与详情。
 *
 * 与用户端取消的差异：工作人员取消不受「取件前 24 小时」限制，
 * 但仍受状态机约束（已逾期只能归还，不能取消）。
 * 若押金已支付，后端会在取消时补记退款流水并通知用户。
 */
/**
 * 取消原因同时用于：
 * - 详情抽屉「备注信息」中的取消原因展示；
 * - 用户端消息通知的文案；
 * - 审计与后续争议处理。
 * 因此原因必填且会去除首尾空格后提交。
 */
/**
 * prompt 的 inputValidator 返回 true 表示通过，返回字符串表示错误提示；
 * 取消按钮与右上角关闭都会走 catch 分支，因此不会误发取消请求。
 */
async function handleCancel(row: RentalVO) {
  let reason = ''
  try {
    const result = await ElMessageBox.prompt('请输入取消原因', '工作人员取消租赁', {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      type: 'warning',
      inputValidator: (value) => (value && value.trim() ? true : '请输入取消原因'),
    })
    reason = result.value.trim()
  } catch {
    return
  }
  await rentalApi.cancel(row.id, { reason })
  ElMessage.success('租赁已取消')
  await loadList()
  await refreshCurrentDetail(row.id)
}

/**
 * 判断租赁单是否显示「取消」按钮。
 *
 * 与后端状态机保持一致：PENDING_PAYMENT、RESERVED、PICKED_UP 可取消；
 * OVERDUE（已逾期）只能归还，RETURNED / CANCELLED 是终态。
 * 这里只做展示层裁剪，真正的合法性由后端断言，非法请求返回 409。
 */
/**
 * 注意按钮可见不等于操作一定成功：
 * - 用户可能停留在旧页面，订单已被其他管理员推进到终态；
 * - 后端仍会做状态机断言与并发原子更新，返回 409 时由拦截器提示。
 */
/**
 * 状态集合使用数组 includes 判断，顺序不影响结果；
 * 若后端状态机调整，需要同步修改此函数与 canReturn，保持展示与后端一致。
 */
function canCancel(row: RentalVO): boolean {
  // 与后端状态机一致：已逾期只能归还，不能取消
  return ['PENDING_PAYMENT', 'RESERVED', 'PICKED_UP'].includes(row.status)
}
/**
 * 判断租赁单是否显示「归还验收」按钮。
 *
 * 仅 PICKED_UP（使用中）与 OVERDUE（已逾期）可归还；
 * 已逾期订单必须先完成归还，不能通过取消绕过费用结算。
 */
/**
 * 归还按钮覆盖使用中与已逾期两种状态，这与后端允许的迁移集合完全一致。
 */
function canReturn(row: RentalVO): boolean {
  return row.status === 'PICKED_UP' || row.status === 'OVERDUE'
}

/**
 * 总页数，至少为 1。
 * 空列表时仍显示第 1 页，避免分页器出现 0 页的异常状态；
 * 用 ceil(total / size) 与后端分页口径一致。
 */
/**
 * pageCount 是纯派生值，不做请求。
 * 当筛选后总数变化时自动重算；页码超出范围时后端返回空页，
 * 本页在查询与重置时会主动回到第一页来规避。
 */
/**
 * 当 total 为 0 时 pageCount 为 1，模板仍渲染一个页码按钮，
 * 与「共 0 条」的文案配合，不会出现空白分页区。
 */
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / query.size)))

/**
 * 首屏挂载后加载第一页列表。
 * 只注册一次；后续刷新由各操作显式调用 loadList。
 */
/**
 * 首屏只加载列表，不预取详情；
 * 详情按需加载，减少不必要的请求与首屏等待。
 */
onMounted(loadList)
</script>

<!-- ========================================================================== -->
<!-- 模板结构总览： -->
<!-- 1. page-head：标题与导出入口。 -->
<!-- 2. filterbar：关键词、状态、逾期、验收结果筛选与查询/重置。 -->
<!-- 3. table-wrap：租赁单表格与分页。 -->
<!-- 4. detail drawer：只读详情、结算、费用、流水与流转记录。 -->
<!-- 5. pickup dialog：取件确认。 -->
<!-- 6. return dialog：归还验收表单。 -->
<!-- 所有弹窗均以 v-if 控制挂载，关闭时销毁内部状态展示。 -->
<!-- ========================================================================== -->
<template>
<!-- ==================== 页头区域 ==================== -->
<!-- eyebrow 为英文栏目名，h1 为中文标题，sub 概述本页可执行的运营动作。 -->
<!-- 右侧操作区当前只有「导出 Excel」；导出中禁用按钮，避免重复下载。 -->
<!-- 页头不包含状态统计卡片，统计信息由仪表盘页面负责，避免本页职责膨胀。 -->
<!-- reveal 类用于进入页面时的轻量入场动画，不影响数据加载。 -->
  <div class="page-head reveal">
    <div>
      <p class="eyebrow">RENTAL OPERATIONS</p>
      <h1>租赁管理</h1>
      <p class="sub">处理取件、归还验收、退款试算与租赁取消，所有流转留痕可追溯。</p>
    </div>
    <div class="actions">
<!-- 导出按钮不区分是否有数据：空结果也会导出一份只有表头的文件，便于确认筛选条件。 -->
      <button type="button" class="btn btn-outline" :disabled="exporting" @click="handleExport">
        <el-icon class="ic"><Download /></el-icon>导出 Excel
      </button>
    </div>
  </div>

<!-- ==================== 筛选栏 ==================== -->
<!-- 使用原生 form 提交，回车与点击「查询」都走 handleSearch，行为一致。 -->
<!-- 关键词：支持单号、工具名称、租用人模糊匹配，回车即触发查询。 -->
<!-- 状态筛选：选项来自 RENTAL_STATUS_LABELS，空值表示全部。 -->
<!-- 逾期筛选：all / true / false 三态，直接映射后端 overdue 参数。 -->
<!-- 验收结果筛选：与归还弹窗共用 DAMAGE_RESULT_LABELS，空值表示全部。 -->
<!-- 查询会重置到第一页；重置会清空全部条件并重新加载。 -->
<!-- 筛选栏五个控件共用一个 query 对象：任一字段变化后点击查询即可生效。 -->
<!-- 下拉框使用原生 select，选项来自契约枚举，避免引入额外组件依赖。 -->
<!-- 关键词输入框同时支持回车与按钮提交，移动端键盘回车同样有效。 -->
<!-- 重置按钮不会弹确认框，因为筛选条件本身不涉及数据变更。 -->
  <form class="filterbar reveal" @submit.prevent="handleSearch">
    <div class="f-search">
      <el-icon class="ic"><Search /></el-icon>
<!-- 关键词输入框的占位文案明确支持的搜索维度，减少用户试错。 -->
      <input v-model="query.keyword" class="input" placeholder="单号 / 工具 / 租用人" @keyup.enter="handleSearch" />
    </div>
<!-- 状态筛选的选项值直接是后端枚举 code，提交时无需再做转换。 -->
    <select v-model="query.status" class="select f-item">
      <option value="">全部状态</option>
      <option v-for="item in statusOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
<!-- 逾期筛选的三态选项由 overdueOptions 提供，默认 all 表示不附加逾期条件。 -->
    <select v-model="query.overdue" class="select f-item">
      <option v-for="item in overdueOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
<!-- 验收结果筛选用于快速定位有损坏赔偿的订单，选项复用归还弹窗的枚举。 -->
    <select v-model="query.damageResult" class="select f-item">
      <option value="">全部验收结果</option>
      <option v-for="item in damageResultOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <button type="submit" class="btn btn-primary"><el-icon class="ic"><Search /></el-icon>查询</button>
    <button type="button" class="btn btn-outline" @click="handleReset"><el-icon class="ic"><Refresh /></el-icon>重置</button>
  </form>

<!-- ==================== 列表表格 ==================== -->
<!-- 表格外层负责滚动与边框，窄屏时横向滚动而不是强行压缩列宽。 -->
<!-- 空态只在「非加载中且结果为空」时出现，避免请求过程中闪出空提示。 -->
<!-- 表格使用原生 table 而非组件库表格，便于精确控制列宽与移动端横向滚动。 -->
<!-- 表格区与筛选区分离，筛选条件变化不会自动触发表格重建，只有查询才重新请求。 -->
<!-- 表格数据为空但仍在 loading 时不显示空态，避免请求过程中的视觉闪烁。 -->
  <div class="table-wrap reveal">
<!-- 表格滚动容器保证窄屏下只横向滚动，不会把页面整体撑破。 -->
    <div class="table-scroll">
      <table class="tbl">
<!-- 表头与下方列一一对应：右对齐用于金额，居中用于状态徽章。 -->
<!-- thead 固定列顺序；新增列时需同步调整空态行的 colspan。 -->
        <thead>
          <tr>
            <th>租赁单号</th>
            <th>工具</th>
            <th>租用人</th>
            <th>租期</th>
            <th class="r">押金</th>
            <th class="c">押金状态</th>
            <th class="c">状态</th>
            <th class="r">费用</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
<!-- 每行数据来自当前页 list，状态变化后通过整页刷新更新，不做局部行更新。 -->
        <tbody>
<!-- 按后端返回顺序逐行渲染；key 使用租赁单主键 id，保证行复用正确。 -->
<!-- 行级数据是后端快照，页面不在本地修改；状态更新一律以后端返回为准。 -->
          <tr v-for="row in list" :key="row.id">
<!-- 租赁单号：业务唯一编号，等宽字体便于人工核对。 -->
            <td class="code">{{ row.orderNo }}</td>
<!-- 工具列：缩略图、工具名称与工具编码；无图时展示占位符。 -->
            <td>
              <div class="cell-tool">
<!-- resolveFileUrl 把对象存储相对路径解析为可直接访问的绝对地址。 -->
<!-- 缩略图使用 lazy 的默认行为即可，列表数据量受分页限制，不会一次加载过多图片。 -->
                <img v-if="row.toolImageUrl" :src="resolveFileUrl(row.toolImageUrl)" class="thumb" alt="" />
                <span v-else class="thumb-ph">—</span>
                <div>
                  <div class="cell-main">{{ row.toolName }}</div>
                  <div class="cell-sub">{{ row.toolCode }}</div>
                </div>
              </div>
            </td>
<!-- 租用人列：姓名与联系电话，便于工作人员联络。 -->
            <td>
              <div>{{ row.renterName }}</div>
              <div class="cell-sub">{{ row.renterPhone }}</div>
            </td>
<!-- 租期列：开始时间与结束时间，结束时间决定逾期费的起算点。 -->
            <td>
              <div>{{ formatDateTime(row.startTime) }}</div>
              <div class="cell-sub">至 {{ formatDateTime(row.endTime) }}</div>
            </td>
<!-- 押金列：该租赁单的押金金额，是退款与赔偿的封顶基数。 -->
            <td class="r money">{{ formatMoney(row.depositAmount) }}</td>
<!-- 押金状态列：未支付 / 已支付 / 已退款，使用独立徽章颜色区分。 -->
<!-- 优先展示后端下发的 depositStatusName，缺失时用枚举标签兜底。 -->
            <td class="c">
              <span :class="badgeClass(depositStatusTag(row.depositStatus))">
                {{ row.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, row.depositStatus) }}
              </span>
            </td>
<!-- 租赁状态列：待支付、已预约、使用中、已归还、已取消、已逾期。 -->
<!-- 同样优先使用 statusName，枚举标签兜底，避免后端新增文案时页面空白。 -->
            <td class="c">
              <span :class="badgeClass(rentalStatusTag(row.status))">
                {{ row.statusName || enumLabel(RENTAL_STATUS_LABELS, row.status) }}
              </span>
            </td>
<!-- 费用列：只展示已产生的逾期费与损坏赔偿，均为 0 时显示占位符。 -->
<!-- 该列反映结算结果而非预估；未归还前通常为空。 -->
            <td class="r">
<!-- 费用为 0 或 null 的项不渲染，避免列表中出现「逾期 ¥0.00」这类噪声。 -->
              <div v-if="row.overdueFee" class="tiny">逾期 {{ formatMoney(row.overdueFee) }}</div>
              <div v-if="row.damageFee" class="tiny">损坏 {{ formatMoney(row.damageFee) }}</div>
              <span v-if="!row.overdueFee && !row.damageFee" class="muted">—</span>
            </td>
<!-- 创建时间：租赁单生成时刻，列表默认按它倒序排列。 -->
            <td>{{ formatDateTime(row.createTime) }}</td>
<!-- 操作列按状态裁剪按钮：详情始终可用；取件仅 RESERVED； -->
<!-- 归还仅 PICKED_UP / OVERDUE；取消仅状态机允许的三种前置状态。 -->
            <td>
<!-- 操作按钮使用链接样式，危险操作（取消）使用 danger 样式以示区分。 -->
<!-- 详情按钮永远可点；其余按钮是否渲染完全由状态机函数决定。 -->
<!-- 同一行可能同时出现取件与取消（RESERVED），也可能只有归还（OVERDUE）。 -->
              <div class="row-actions">
<!-- 详情不依赖状态，用于查看历史与审计信息。 -->
<!-- 取件按钮只对已预约订单出现；归还按钮对使用中与已逾期出现；取消按钮受状态机约束。 -->
                <button class="link-btn" @click="openDetail(row)">详情</button>
                <button v-if="row.status === 'RESERVED'" class="link-btn ok" @click="openPickup(row)">取件</button>
                <button v-if="canReturn(row)" class="link-btn" @click="openReturn(row)">归还验收</button>
                <button v-if="canCancel(row)" class="link-btn danger" @click="handleCancel(row)">取消</button>
              </div>
            </td>
          </tr>
<!-- 空态行跨全部 10 列，colspan 必须与表头列数保持一致。 -->
<!-- 空态不提供「新建租赁」入口，因为租赁单由用户端下单产生，管理员只负责运营。 -->
<!-- colspan=10 与表头列数严格对应，新增或删除列时必须同步修改。 -->
          <tr v-if="!loading && list.length === 0">
            <td colspan="10"><div class="empty" style="padding: 40px"><h4>暂无租赁单</h4></div></td>
          </tr>
        </tbody>
      </table>
    </div>
<!-- ==================== 分页区 ==================== -->
<!-- 左侧展示筛选结果总数，右侧为页码与上下页按钮。 -->
<!-- 页码使用 1..pageCount 全量渲染；数据量很大时可改为省略号分页。 -->
<!-- 翻页先修改 query.page 再调用 loadList；首尾页按钮在边界处禁用。 -->
<!-- 分页区只在表格下方出现一次，不随空态隐藏，便于用户知道总数与页码。 -->
<!-- 底部固定展示总数与页码，翻页不会重置筛选条件。 -->
<!-- 页码按钮数量等于总页数，数据量大时可能较长，可按需替换为省略号分页。 -->
    <div class="table-foot">
      <span class="tiny">共 {{ total }} 条</span>
      <div class="pager">
<!-- 上一页在首页禁用，下一页在末页禁用，页码按钮当前页高亮。 -->
        <button type="button" :disabled="query.page <= 1" @click="query.page--; loadList()">上一页</button>
        <button
          v-for="p in pageCount"
          :key="p"
          type="button"
          :class="{ active: query.page === p }"
          @click="query.page = p; loadList()"
        >{{ p }}</button>
        <button type="button" :disabled="query.page >= pageCount" @click="query.page++; loadList()">下一页</button>
      </div>
    </div>
  </div>

<!-- ==================== 详情抽屉 ==================== -->
<!-- 遮罩点击自身时关闭抽屉；点击抽屉内部不会冒泡关闭。 -->
<!-- 抽屉宽度 560px，内容较长时在 dlg-body 内滚动。 -->
  <!-- 详情抽屉 -->
<!-- 详情抽屉为只读视图，不提供编辑、删除或再次状态流转的入口。 -->
<!-- 需要操作时关闭抽屉回到列表，通过行内按钮完成，保持操作入口单一。 -->
<!-- 遮罩层点击自身关闭，抽屉内部点击不会关闭，符合常见抽屉交互习惯。 -->
  <div v-if="detailVisible" class="drawer-mask show" @click.self="detailVisible = false">
    <div class="drawer" style="width: 560px">
<!-- 抽屉头部固定，关闭按钮使用 aria-label 提升无障碍可读性。 -->
      <div class="dlg-head">
        <h3>租赁详情</h3>
        <button class="dlg-x" @click="detailVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
<!-- 加载中显示骨架屏；加载完成但无数据时显示「暂无详情」。 -->
<!-- 抽屉主体承载所有详情内容，加载态与数据态互斥展示。 -->
      <div class="dlg-body">
        <div v-if="detailLoading" class="skel" style="height: 300px"></div>
        <div v-else-if="detail">
<!-- 状态徽章放在最上方，让工作人员一眼确认当前处于状态机的哪个节点。 -->
<!-- 押金状态单独展示，因为取件前必须已支付，取消/归还后可能变为已退款。 -->
          <div class="flex" style="margin-bottom: 16px">
            <span :class="badgeClass(rentalStatusTag(detail.status))">
              <span class="dot"></span>{{ detail.statusName || enumLabel(RENTAL_STATUS_LABELS, detail.status) }}
            </span>
            <span :class="badgeClass(depositStatusTag(detail.depositStatus))">
              押金{{ detail.depositStatusName || enumLabel(DEPOSIT_STATUS_LABELS, detail.depositStatus) }}
            </span>
          </div>
<!-- 基础信息键值区：覆盖单号、工具、租用人、经办人、状态与关键时间。 -->
<!-- 身份证与经办人等可空字段统一用「—」兜底，避免出现 undefined。 -->
<!-- 实际归还时间与验收结果只在归还后才有值，未归还时为空占位。 -->
          <dl class="kv">
<!-- 租赁单号是跨系统对账与客服沟通的关键字段，使用等宽字体。 -->
            <dt>租赁单号</dt>
            <dd class="code">{{ detail.orderNo }}</dd>
<!-- 工具名称与编码同时展示，名称便于阅读，编码便于精确识别同型号工具。 -->
            <dt>工具名称</dt>
            <dd>{{ detail.toolName }}</dd>
<!-- 工具编码通常与资产标签一致，线下盘点时可直接核对。 -->
            <dt>工具编码</dt>
            <dd>{{ detail.toolCode }}</dd>
<!-- 租用人是下单用户，可能与实际取件人不同。 -->
            <dt>租用人</dt>
            <dd>{{ detail.renterName }}</dd>
<!-- 联系电话用于工作人员联系租用人，属于必要的运营信息。 -->
            <dt>联系电话</dt>
            <dd>{{ detail.renterPhone }}</dd>
<!-- 身份证为脱敏值，后端已隐藏中间位，避免明文展示敏感信息。 -->
<!-- 身份证仅展示脱敏值，页面不提供查看明文的入口。 -->
            <dt>身份证（脱敏）</dt>
            <dd>{{ detail.renterIdCardMasked || '—' }}</dd>
<!-- 取件人是线下实际领取工具的人，可能与租用人不同。 -->
<!-- 取件人由线下确认，未填写时显示占位符。 -->
            <dt>取件人</dt>
            <dd>{{ detail.pickupPerson || '—' }}</dd>
<!-- 录入人与更新人用于追责与审计，展示管理员用户名。 -->
<!-- 录入人通常是创建该租赁单的工作人员或用户。 -->
            <dt>录入人</dt>
            <dd>{{ detail.createdByName || '—' }}</dd>
<!-- 更新人记录最近一次状态流转的经办人，便于追责。 -->
            <dt>更新人</dt>
            <dd>{{ detail.updatedByName || '—' }}</dd>
<!-- 租赁状态以徽章展示，颜色语义与列表页保持一致。 -->
            <dt>租赁状态</dt>
            <dd><span :class="badgeClass(rentalStatusTag(detail.status))">{{ detail.statusName || enumLabel(RENTAL_STATUS_LABELS, detail.status) }}</span></dd>
<!-- 开始时间是租赁计划起点，实际归还时间不能早于它（后端校验）。 -->
            <dt>开始时间</dt>
            <dd>{{ formatDateTime(detail.startTime) }}</dd>
<!-- 结束时间是计划归还时间，也是逾期费的起算点。 -->
            <dt>结束时间</dt>
            <dd>{{ formatDateTime(detail.endTime) }}</dd>
<!-- 实际归还时间由归还验收时填写，是逾期费计算的唯一依据。 -->
<!-- 实际归还未发生时显示占位符，发生后才参与结算展示。 -->
            <dt>实际归还</dt>
            <dd>{{ formatDateTime(detail.actualReturnTime) }}</dd>
<!-- 验收结果决定是否产生损坏赔偿，以及工具归还后的状态。 -->
            <dt>验收结果</dt>
            <dd>{{ detail.damageResultName || enumLabel(DAMAGE_RESULT_LABELS, detail.damageResult) }}</dd>
<!-- 创建时间用于确认订单产生时刻，与列表列一致。 -->
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(detail.createTime) }}</dd>
          </dl>
<!-- 结算明细：仅在已归还或存在退款金额时展示。 -->
<!-- 口径与后端 SettlementCalculator 一致： -->
<!-- 押金为基数，逾期费与损坏赔偿为扣减项，实际退款为最终退还金额。 -->
<!-- 减免不单独在此扣除，因为它已体现在较低的扣减项或退款结果中。 -->
          <template v-if="detail.status === 'RETURNED' || detail.refundAmount !== null">
<!-- 结算明细是只读结果展示，不提供任何修改入口；如需调整只能通过重新验收流程。 -->
<!-- 退款金额为 0 时也展示，表示押金已被费用完全抵扣。 -->
            <div class="sec-label">结算明细</div>
            <div class="return-box">
<!-- 押金是结算基数，展示原始金额而不是剩余金额。 -->
<!-- 逾期费与损坏赔偿前的负号表示从押金中扣减，实际退款为扣减后的结果。 -->
              <div class="rb-row"><span>押金</span><span class="num">{{ formatMoney(detail.depositAmount) }}</span></div>
              <div class="rb-row"><span>逾期费用</span><span class="num">− {{ formatMoney(detail.overdueFee || 0) }}</span></div>
              <div class="rb-row"><span>损坏赔偿</span><span class="num">− {{ formatMoney(detail.damageFee || 0) }}</span></div>
              <div class="rb-row"><span>实际退款</span><span class="num">{{ formatMoney(detail.refundAmount || 0) }}</span></div>
            </div>
          </template>
<!-- 费用明细：后端写入的每一笔费用流水（逾期、赔偿、减免等）。 -->
<!-- 每项展示费用类型、金额、产生时间与原因，用于审计与向用户解释。 -->
          <template v-if="detail.fees.length > 0">
<!-- 费用明细按后端写入顺序展示，可能同时包含逾期费、损坏赔偿与减免记录。 -->
<!-- 每笔费用都带原因，减免记录的原因中包含工作人员填写的减免说明。 -->
            <div class="sec-label">费用明细</div>
            <ul class="timeline">
              <li v-for="fee in detail.fees" :key="fee.id" class="done">
<!-- 费用类型与金额放在同一行，时间与原因分行展示，便于快速浏览。 -->
                <div class="tl-t">{{ fee.feeTypeName || fee.feeType }} · {{ formatMoney(fee.amount) }}</div>
                <div class="tl-time">{{ formatDateTime(fee.createTime) }}</div>
                <div class="tl-d">{{ fee.reason || '—' }}</div>
              </li>
            </ul>
          </template>
<!-- 支付与退款流水：押金支付、押金退款、超额应收等记录。 -->
<!-- 与费用明细分开，便于区分「应收/扣减」与「实际资金往来」。 -->
          <template v-if="detail.payments.length > 0">
<!-- 支付流水与费用流水分开，避免把「应扣费用」与「实际收退款」混为一谈。 -->
<!-- 若赔偿超过押金，这里会出现应收记录，提示还需向用户追缴。 -->
            <div class="sec-label">支付与退款流水</div>
            <ul class="timeline">
              <li v-for="payment in detail.payments" :key="payment.id" class="done">
<!-- 支付流水同样展示类型、金额、时间与备注，备注由后端在记账时写入。 -->
                <div class="tl-t">{{ payment.recordTypeName || payment.recordType }} · {{ formatMoney(payment.amount) }}</div>
                <div class="tl-time">{{ formatDateTime(payment.createTime) }}</div>
                <div class="tl-d">{{ payment.remark || '—' }}</div>
              </li>
            </ul>
          </template>
<!-- 备注信息：归还备注与取消原因按需展示，二者可能同时存在（历史数据）。 -->
<!-- 取消原因附带取消时间，便于回溯工作人员操作。 -->
          <template v-if="detail.returnRemark || detail.cancelReason">
<!-- 备注区仅在存在归还备注或取消原因时整块展示，避免空白区块占用空间。 -->
<!-- 取消原因后的时间来自 cancelTime，便于确认取消发生的具体时刻。 -->
            <div class="sec-label">备注信息</div>
            <dl class="kv">
              <dt v-if="detail.returnRemark">归还备注</dt>
              <dd v-if="detail.returnRemark">{{ detail.returnRemark }}</dd>
              <dt v-if="detail.cancelReason">取消原因</dt>
              <dd v-if="detail.cancelReason">{{ detail.cancelReason }}（{{ formatDateTime(detail.cancelTime) }}）</dd>
            </dl>
          </template>
<!-- 流转记录：由 detailTimeline 计算的时间线。 -->
<!-- 最后一项不加 done 样式，表示当前节点；其余节点标记为已完成。 -->
<!-- 流转记录是推导数据，不依赖后端单独的日志接口； -->
<!-- 因此节点时间在部分环节显示为「—」，仅表示该事件已发生。 -->
          <div class="sec-label">流转记录</div>
          <ul class="timeline">
            <li v-for="(item, index) in detailTimeline" :key="index" :class="{ done: index < detailTimeline.length - 1 }">
<!-- 时间线节点标题、时间、说明三段式展示，最后一项表示当前状态。 -->
              <div class="tl-t">{{ item.title }}</div>
              <div class="tl-time">{{ item.time }}</div>
              <div class="tl-d">{{ item.desc }}</div>
            </li>
          </ul>
        </div>
        <p v-else class="muted">暂无详情</p>
      </div>
    </div>
  </div>

<!-- ==================== 取件确认弹窗 ==================== -->
<!-- 仅用于 RESERVED 状态；确认后租赁单进入 PICKED_UP，工具变为已租出。 -->
<!-- 弹窗只读展示关键信息，避免在确认取件时误改数据。 -->
  <!-- 取件确认 -->
<!-- 取件弹窗只承载「确认」语义，不提供修改取件人的能力，避免绕过身份核验。 -->
  <div v-if="pickupVisible" class="mask show" @click.self="pickupVisible = false">
    <div class="dlg narrow">
<!-- 取件弹窗宽度较窄，只承载确认信息，不提供表单编辑。 -->
      <div class="dlg-head">
        <div>
          <h3>确认取件</h3>
          <p class="d-sub">{{ pickupTarget?.orderNo }} · {{ pickupTarget?.toolName }}</p>
        </div>
        <button class="dlg-x" @click="pickupVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
<!-- 提示：取件前需核对取件人身份与押金支付状态，后端会强制校验押金已支付。 -->
<!-- 提示文案强调两个后端硬约束：身份核对与押金已支付。 -->
<!-- 取件人、租期均为只读快照，数据来自打开弹窗时的列表行。 -->
      <div class="dlg-body">
        <div class="notice-sm">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" /></svg>
          <span>请核对取件人身份与押金支付状态后再确认取件。</span>
        </div>
<!-- 只读展示取件人与租期，供工作人员当面核对。 -->
        <div class="field">
          <label>取件人</label>
          <span>{{ pickupTarget?.pickupPerson || '—' }}</span>
        </div>
        <div class="field">
          <label>租期</label>
          <span>{{ formatDateTime(pickupTarget?.startTime) }} 至 {{ formatDateTime(pickupTarget?.endTime) }}</span>
        </div>
      </div>
<!-- 取消直接关闭弹窗；确认按钮在提交中禁用并显示处理中状态。 -->
      <div class="dlg-foot">
        <button type="button" class="btn btn-outline" @click="pickupVisible = false">取消</button>
<!-- 取件确认按钮在提交中显示「处理中…」并禁用，成功后才关闭弹窗。 -->
        <button type="button" class="btn btn-primary" :disabled="pickupSaving" @click="submitPickup">
          {{ pickupSaving ? '处理中…' : '确认取件' }}
        </button>
      </div>
    </div>
  </div>

<!-- ==================== 归还验收弹窗 ==================== -->
<!-- 支持 PICKED_UP 与 OVERDUE 两种状态；提交后由后端完成结算与状态流转。 -->
<!-- 表单只收集业务事实，退款金额由后端按规则计算，页面不提交金额。 -->
  <!-- 归还验收弹窗 -->
<!-- 归还弹窗是唯一会写结算结果的入口，所有字段都会随提交进入后端校验与计算。 -->
<!-- 弹窗内不展示预估退款金额，防止用户把前端试算当作最终结算结果。 -->
<!-- 提交成功后列表与详情同时刷新，保证费用、押金状态与流转记录一致。 -->
  <div v-if="returnDialogVisible" class="mask show" @click.self="returnDialogVisible = false">
    <div class="dlg">
<!-- 归还弹窗比取件弹窗宽，因为需要承载验收表单与减免选项。 -->
      <div class="dlg-head">
        <h3>归还验收</h3>
        <button class="dlg-x" @click="returnDialogVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
<!-- 顶部只读信息：租赁单号与押金金额，帮助工作人员确认操作对象。 -->
<!-- 单号与押金放在最上方，作为填写验收信息前的上下文确认。 -->
      <div class="dlg-body">
        <div class="field">
          <label>租赁单号</label>
          <span class="code">{{ returnTarget?.orderNo || '—' }}</span>
        </div>
        <div class="field">
          <label>押金金额</label>
          <span class="money">{{ formatMoney(returnTarget?.depositAmount) }}</span>
        </div>
<!-- 实际归还时间：逾期费的计算依据，默认当前时间，可回填更早的真实时间。 -->
<!-- 验收结果：INTACT 完好 / MINOR 轻微损坏 / SEVERE 严重损坏。 -->
<!-- 只有 SEVERE 会产生损坏赔偿；MINOR 会更新工具损坏等级但不一定产生费用。 -->
<!-- 第一组表单：实际归还时间与验收结果，是结算的两个核心输入。 -->
<!-- 实际归还时间允许早于当前时间，但不能早于租赁开始时间（后端校验）。 -->
        <div class="form-grid">
          <div class="field">
            <label>实际归还时间</label>
<!-- 实际归还时间使用 datetime-local，浏览器会按本地时区解析并提交。 -->
            <input v-model="returnForm.returnTime" type="datetime-local" class="input" />
          </div>
          <div class="field">
            <label>验收结果</label>
<!-- 验收结果选项来自契约枚举，提交的是 code，后端负责转换为标签与赔偿规则。 -->
            <select v-model="returnForm.damageResult" class="select">
              <option v-for="item in damageResultOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </div>
        </div>
<!-- 减免控制：两个复选框分别全额减免逾期费与损坏赔偿。 -->
<!-- 减免原因在任一复选框勾选后变为必填，未勾选时输入框禁用。 -->
<!-- 减免只影响本次退款结果，不会删除费用明细中的原始金额记录。 -->
<!-- 第二组表单：减免选项与减免原因，原因输入框随勾选状态启用/禁用。 -->
<!-- 两个减免可以同时勾选，后端会分别全额减免对应费用。 -->
        <div class="form-grid">
          <div class="field">
            <label>减免</label>
            <div class="check-row">
<!-- 减免复选框独立控制两类费用，勾选后必须填写原因，否则前端阻止提交。 -->
              <label class="ck"><input v-model="returnForm.waiveOverdue" type="checkbox" /><span>减免逾期费</span></label>
              <label class="ck"><input v-model="returnForm.waiveDamage" type="checkbox" /><span>减免损坏赔偿</span></label>
            </div>
          </div>
          <div class="field">
            <label>减免原因</label>
<!-- 减免原因在两项都未勾选时禁用，避免填写了原因却没有实际减免。 -->
            <input
              v-model="returnForm.waiverReason"
              class="input"
              maxlength="500"
              :disabled="!returnForm.waiveOverdue && !returnForm.waiveDamage"
              placeholder="减免时必填"
            />
          </div>
        </div>
<!-- 带伤归还：当该租赁仍有未终结报修时使用。 -->
<!-- 勾选后后端允许归还，但工具保持维修中，备注会作为保持维修中的原因。 -->
<!-- 未勾选且存在未终结报修时，后端返回 409 并提示先完成维修。 -->
<!-- 带伤归还只影响工具状态与后端校验，不会改变租赁单本身的归还结论。 -->
<!-- 勾选后工具保持维修中，直至报修流程结束。 -->
        <div class="field">
          <label>带伤归还</label>
          <label class="ck">
<!-- 带伤归还只应在存在未终结报修时勾选；普通归还无需勾选。 -->
            <input v-model="returnForm.returnWithDamage" type="checkbox" />
            <span>存在未终结报修，仍确认归还</span>
          </label>
          <p class="hint">勾选后工具保持维修中；未勾选且存在未终结报修时，后端会拒绝归还。</p>
        </div>
<!-- 归还备注：选填，用于补充验收说明；带伤归还时建议填写具体原因。 -->
<!-- 长度上限 200，由控件与后端共同约束。 -->
<!-- 备注为选填项，带伤归还时后端会把备注或默认文案写入状态日志。 -->
        <div class="field">
          <label>归还备注</label>
<!-- 归还备注长度受限，既用于审计，也可能作为带伤归还保持维修中的原因。 -->
          <textarea v-model="returnForm.remark" class="textarea" rows="2" maxlength="200" placeholder="选填（带伤归还时作为保持维修中的原因）"></textarea>
        </div>
<!-- 退款金额说明：强调金额由系统计算，避免工作人员误以为需要手工填写。 -->
<!-- 实际退款在提交成功后可在详情抽屉的「结算明细」中查看。 -->
<!-- 预览区只解释退款由系统计算，不展示具体金额，因为费用需后端按规则结算。 -->
        <div class="refund-preview">
          <div>
<!-- 预览区不使用前端试算金额，避免前后端结算口径不一致造成误导。 -->
            <span class="rp-label">退款金额</span>
            <span class="rp-calc">
              由系统按实际归还时间、验收结果与减免计算，押金 {{ formatMoney(returnTarget?.depositAmount || 0) }}
            </span>
          </div>
        </div>
      </div>
<!-- 底部操作：取消关闭弹窗且不提交；确认归还按钮提交中禁用，防止重复结算。 -->
<!-- 确认归还提交成功后弹窗关闭，列表与详情刷新；失败时弹窗保留以便修正输入。 -->
      <div class="dlg-foot">
        <button type="button" class="btn btn-outline" @click="returnDialogVisible = false">取消</button>
<!-- 确认归还成功后，详情抽屉中的结算明细与支付流水会通过 refreshCurrentDetail 更新。 -->
        <button type="button" class="btn btn-primary" :disabled="returnSaving" @click="submitReturn">确认归还</button>
      </div>
    </div>
  </div>
</template>
