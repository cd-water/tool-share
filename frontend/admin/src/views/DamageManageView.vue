<script setup lang="ts">
/**
 * ============================================================================
 * DamageManageView · 管理员端「报修管理」页面
 * ============================================================================
 *
 * 一、文件职责
 *   1. 分页展示居民提交的报修单，支持关键词、报修状态、维修进度三类筛选；
 *   2. 承载「核验 → 维修 → 验收」三段式状态机的全部管理操作；
 *   3. 通过详情抽屉展示报修单完整快照，并用时间线推导当前所处阶段；
 *   4. 所有写操作调用 @/api/damage 下的接口，成功后同时刷新列表与详情。
 *
 * 二、三段状态机（与后端枚举一一对应）
 *   提交 SUBMITTED
 *     └─ 管理员核验 verify
 *          ├─ 核验为误报 FALSE → 报修单驳回 REJECTED 或报废 SCRAPPED；
 *          └─ 核验通过 → 进入维修阶段。
 *   维修 VERIFIED / REPAIRING
 *     └─ 管理员按次更新进度 progress
 *          ├─ 维修中 REPAIRING；
 *          ├─ 等待配件 WAITING_PARTS；
 *          ├─ 维修完成 COMPLETED —— 只有此状态才允许验收；
 *          └─ 维修失败 FAILED —— 不能直接验收，需要重新安排维修。
 *   验收 accept
 *     ├─ 合格 QUALIFIED     → 报修单收口，工具通常恢复 AVAILABLE；
 *     ├─ 部分合格 PARTIAL   → 工具只能进入维护/报废等非可用状态；
 *     └─ 不合格 UNQUALIFIED → 工具同样不能置为可用。
 *
 * 三、操作判定
 *   canVerify / canProgress / canAccept 是三个纯函数，集中描述「什么状态能做什么」。
 *   模板只根据它们的返回值显隐按钮，不在模板里散落状态判断，便于状态机演进。
 *   同时后端仍会重新校验，前端判定只用于交互引导，不承担安全职责。
 *
 * 四、数据约定
 *   - 列表接口返回分页对象 { records, total }，query 中的 page / size 直接透传；
 *   - 维修进度筛选 progressFilter 是纯客户端筛选，不参与请求参数；
 *   - 详情接口返回 DamageReportVO，抽屉打开时先展示行数据，再异步拉取最新详情；
 *   - 金额 repairCost 使用 number，空值用 null 表示，避免 0 与「未填」混淆。
 *
 * 五、交互约定
 *   - 核验 / 进度 / 验收提交成功后统一执行：关闭弹窗 → 刷新列表 → 刷新当前详情；
 *   - 弹窗遮罩点击自身（@click.self）关闭，点击弹窗内部不会误关；
 *   - 保存期间对应 Saving 标记会禁用按钮，防止重复提交；
 *   - 列表的加载态与空态互斥，客户端筛选后为空也会进入空态。
 *
 * 六、依赖
 *   - @/api/damage   提供 list / detail / verify / progress / accept 五个接口；
 *   - @/api/types    提供请求 DTO、视图 VO 与各枚举类型；
 *   - @/utils/params cleanParams 负责剔除空查询条件；
 *   - @/utils/format 负责时间、金额与对象存储文件直链的格式化；
 *   - @/utils/enums  提供枚举中文标签、安全取值与下拉选项派生。
 *
 * 七、阅读顺序建议
 *   先看三个 can* 函数了解操作边界，再看 timelineStages 了解详情页的阶段推导，
 *   最后看三个 submit* 函数确认每个写操作实际提交了哪些字段。
 */
/**
 * Vue 组合式 API：
 *   computed  用于派生时间线、可见列表、分页数字与验收后工具状态下拉；
 *   reactive  用于查询参数与三个弹窗表单；
 *   ref       用于列表、加载态、弹窗开关与当前操作目标行；
 *   watch     用于验收结果与验收后工具状态之间的联动收口；
 *   onMounted 在页面挂载后拉取第一页数据。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
/**
 * Element Plus 的消息提示。
 * 只在写操作成功时给出 toast；失败提示由请求拦截器统一处理，这里不重复弹错，
 * 以免同一错误出现两条提示。
 */
import { ElMessage } from 'element-plus'
/**
 * 图标按用途分组：
 *   Search      搜索框与查询按钮；
 *   Refresh     重置按钮；
 *   Check       详情抽屉「核验信息」分区标题；
 *   Tools       详情抽屉「维修信息」分区标题；
 *   CircleCheck 详情抽屉「验收信息」分区标题；
 *   Document    详情空态；
 *   Warning     列表空态；
 *   Close       三个弹窗与详情抽屉的关闭按钮。
 * 图标均通过 Element Plus 的 <component> 形式直接使用，不额外注册组件。
 */
import { Check, CircleCheck, Close, Document, Refresh, Search, Tools, Warning } from '@element-plus/icons-vue'
/**
 * 报修领域接口封装。本页用到的五个方法：
 *   list(query)        分页查询报修单，返回 { records, total }；
 *   detail(id)         查询单条报修详情；
 *   verify(id, dto)    提交核验结果，推进第一段状态机；
 *   progress(id, dto)  追加一次维修进度，推进第二段状态机；
 *   accept(id, dto)    提交验收，收口第三段状态机并决定工具最终状态。
 * 页面不直接使用 axios，全部经由该模块，方便统一处理鉴权、前缀与错误。
 */
import { damageApi } from '@/api/damage'
/**
 * 后端契约类型：
 *   AcceptResult       验收结果枚举：QUALIFIED / PARTIAL / UNQUALIFIED；
 *   DamageAcceptDTO    验收请求体：验收结果、验收后工具状态、维修费用、备注；
 *   DamageProgressDTO  进度请求体：进度状态、进度说明，以及可选的负责人/预计时长/费用；
 *   DamageQuery        列表查询条件：关键词 keyword 与报修状态 status；
 *   DamageReportVO     报修单视图对象，列表行与详情抽屉共用同一结构；
 *   DamageVerifyDTO    核验请求体：核验结果与可选的维修安排信息；
 *   ProgressStatus     维修进度枚举：REPAIRING / WAITING_PARTS / COMPLETED / FAILED；
 *   ToolStatusAfter    验收后工具状态枚举：AVAILABLE / MAINTENANCE / SCRAPPED 等；
 *   VerifyResult       核验结果枚举：CONFIRMED / FALSE 等。
 * 这些类型同时约束表单、请求体与接口返回值，字段改动会在这里暴露为类型错误。
 */
import type {
  AcceptResult,
  DamageAcceptDTO,
  DamageProgressDTO,
  DamageQuery,
  DamageReportVO,
  DamageVerifyDTO,
  ProgressStatus,
  ToolStatusAfter,
  VerifyResult,
} from '@/api/types'
/**
 * cleanParams 会剔除值为 undefined / null / 空字符串的字段，
 * 保证空关键词或空状态不会以「空条件」形式发给后端，
 * 避免后端把空串当成有效筛选值而返回空列表。
 */
import { cleanParams } from '@/utils/params'
/**
 * 展示层格式化工具：
 *   formatDateTime  将 ISO 时间转成统一的可读格式，空值显示占位符；
 *   formatMoney     将 number 金额格式化为人民币文本；
 *   resolveFileUrl  把对象存储返回的 key / 相对路径拼成可直接访问的直链。
 * 所有时间与金额展示都走这三个函数，保证全站格式一致。
 */
import { formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
/**
 * 枚举展示工具：
 *   *_LABELS 是「枚举值 → 中文标签」的映射表；
 *   enumLabel(map, value) 安全取值，未知值回退为原值或占位符；
 *   optionList(map) 把映射表转成下拉框需要的 { label, value } 数组。
 * 状态徽章、筛选下拉与详情文本都复用这套映射，避免在模板里硬编码中文，
 * 也保证新增枚举值时只需改一处。
 */
import {
  ACCEPT_RESULT_LABELS,
  PROGRESS_STATUS_LABELS,
  REPORT_STATUS_LABELS,
  TOOL_STATUS_LABELS,
  VERIFY_RESULT_LABELS,
  enumLabel,
  optionList,
} from '@/utils/enums'

/**
 * 列表加载态。
 * 请求期间置为 true，用于展示骨架屏，并避免用户把旧数据误认为新结果。
 * 由 loadList 在 finally 中复位，异常路径也不会让骨架屏卡住。
 */
const loading = ref(false)
/**
 * 当前页的报修单列表，直接来自接口返回的 records。
 * 页面上的「本页筛选」只在这份数据之上做客户端过滤，不会修改它本身；
 * 翻页或重新查询时整体替换。
 */
const list = ref<DamageReportVO[]>([])
/**
 * 服务端返回的报修单总数。
 * 用于分页器、总页数计算与「共 N 单」统计，不受客户端进度筛选影响。
 */
const total = ref(0)
/**
 * 列表查询参数。
 *   page / size 分页信息，size 可在 10 / 20 / 50 之间切换；
 *   keyword     模糊搜索，命中报修单号、工具名或报修人；
 *   status      报修单状态筛选，空串表示全部状态。
 * 注意：维修进度筛选不在 query 中，它在客户端完成（见 progressFilter），
 * 因此本对象可以原样传给 cleanParams 与列表接口。
 */
const query = reactive<DamageQuery & { page: number; size: number }>({
  page: 1,
  size: 10,
  keyword: '',
  status: '',
})

/**
 * 维修进度的客户端筛选值。
 * 由于列表接口只支持按报修状态筛选，进度筛选只能在当前页数据上即时计算：
 * 切换进度不会重新请求后端，不会改变 total，也不会影响服务端分页结果。
 * 这是本页筛选行为中唯一与「查询」不一致的地方。
 */
const progressFilter = ref('')
/**
 * 经过客户端进度筛选后真正渲染的列表。
 *   progressFilter 为空：直接返回原列表，保持接口返回顺序；
 *   progressFilter 非空：按 progressStatus 精确匹配，可能得到空数组并触发空态。
 * 使用 computed 而不是 watch，切换筛选时无需额外状态，也不会造成重复渲染。
 */
const visibleList = computed(() =>
  progressFilter.value ? list.value.filter((row) => row.progressStatus === progressFilter.value) : list.value,
)

/**
 * 报修状态下拉选项，供筛选栏使用。
 * 选项顺序由 REPORT_STATUS_LABELS 的声明顺序决定，
 * 新增状态时只需更新枚举映射，这里自动生效。
 */
const statusOptions = optionList(REPORT_STATUS_LABELS)
/**
 * 核验结果下拉选项（通过 / 误报等），顺序取自 VERIFY_RESULT_LABELS。
 * 与核验弹窗共享同一份选项，保证筛选与编辑语义一致。
 */
const verifyResultOptions = optionList(VERIFY_RESULT_LABELS)
/**
 * 维修进度下拉选项。
 * 既用于筛选栏的客户端进度筛选，也用于进度弹窗的状态选择，
 * 两处共享同一份标签映射，避免出现两套进度名称。
 */
const progressStatusOptions = optionList(PROGRESS_STATUS_LABELS)
/**
 * 验收结果下拉选项（合格 / 部分合格 / 不合格）。
 * 验收结果的取值会进一步影响「验收后工具状态」的可选项。
 */
const acceptResultOptions = optionList(ACCEPT_RESULT_LABELS)

/**
 * 拉取报修单列表。
 * 入参：无，直接读取响应式 query。
 * 行为：
 *   1. 置 loading 为 true，展示骨架屏；
 *   2. cleanParams 去掉空筛选条件后调用 damageApi.list；
 *   3. 用返回的 records / total 覆盖本地列表与总数；
 *   4. finally 中复位 loading，保证异常时骨架屏也能退出。
 * 调用方：页面挂载、查询、重置、切换每页条数、翻页，
 * 以及三个写操作成功后的列表刷新。
 */
async function loadList() {
// 进入请求前先点亮加载态：模板会立即切换到骨架屏，
// 旧列表暂时保留在 list 中，但被 v-if 分支遮挡，不会与骨架屏同时显示。
  loading.value = true
  try {
// 展开 query 是为了避免把响应式代理直接传给请求层；
// cleanParams 再剔除空字符串条件，最终只发送有效筛选字段。
    const data = await damageApi.list(cleanParams({ ...query }))
// 用接口返回覆盖当前页数据：records 的顺序由后端分页与排序决定，
// 前端不再做二次排序，保证与 total 的统计口径一致。
    list.value = data.records
// total 是服务端筛选后的总数，不能从 records.length 推导，
// 否则客户端进度筛选后会得到错误的总页数。
    total.value = data.total
  } finally {
// 无论请求成功还是抛异常都复位加载态；
// 异常提示由请求拦截器负责，这里不吞异常，只是保证 UI 可恢复。
    loading.value = false
  }
}

/**
 * 点击查询按钮或输入框回车时触发。
 * 先把页码重置为 1，再按当前 keyword / status 请求，
 * 避免用户在深页用新条件查询时得到「第 N 页无数据」的错觉。
 */
function handleSearch() {
// 查询条件变化后必须回到第一页：
// 否则可能在新的结果集里停留在越界页码，出现「有总数但当前页为空」。
  query.page = 1
// 复用 loadList，不在这里额外拼请求参数，
// 保证查询、翻页、写操作后的刷新走同一条数据通道。
  loadList()
}

/**
 * 重置筛选条件。
 * 清空关键词与报修状态，页码回到 1，然后重新拉取列表。
 * 维修进度筛选是客户端状态，这里没有主动清空，保留用户当前选择；
 * 如需完全重置，可在进度下拉中手动切回「全部维修进度」。
 */
function handleReset() {
// 重置只清空服务端筛选条件；progressFilter 是客户端状态，故意保留。
  query.keyword = ''
// status 清空为空串，cleanParams 会在请求前把这个字段剔除。
  query.status = ''
// 与查询一致，条件变化后回到第一页。
  query.page = 1
// 重新请求，让列表与总页数按「全部状态、无关键词」重新计算。
  loadList()
}

/**
 * 切换每页条数后触发。
 * 页码重置为 1，防止原页码在新页大小下越界（例如从 50 条/页降到 10 条/页）。
 * 随后重新请求，序号与分页器都会按新 size 重算。
 */
function handleSizeChange() {
// 每页条数变了，原页码可能已越界，先回到第一页。
  query.page = 1
// size 已由 v-model 写入 query，loadList 直接读取最新值。
  loadList()
}

// —— 分页 ——
/**
 * 总页数，至少为 1。
 * 即使 total 为 0 也显示「第 1/1 页」，避免出现 0 或 NaN 页码；
 * total 未被 10 整除时向上取整，保证最后一页可达。
 */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))
/**
 * 生成省略号分页序列。
 * 规则：
 *   - 始终保留第一页与最后一页；
 *   - 当前页左右各保留 2 页（span = 2）；
 *   - 中间被跳过的区间插入一个 '…'；
 *   - 连续多个间隔只插入一次省略号，避免出现「… …」；
 *   - 返回值为数字或字符串 '…'，模板据此决定渲染页码按钮还是禁用占位。
 * 例：共 20 页、当前第 10 页 → 1 … 8 9 10 11 12 … 20。
 * 该算法只负责展示，真正请求由 gotoPage 完成。
 */
const pageNumbers = computed<(number | '…')[]>(() => {
// 结果数组只包含数字页码与省略号字符串，模板按类型分支渲染。
  const pages: (number | '…')[] = []
// 总页数来自 computed，total 或 size 变化时会自动重算。
  const all = totalPages.value
// 当前页同样来自响应式 query，翻页后本计算属性会重新执行。
  const cur = query.page
// 当前页左右各保留两页，窗口总宽约 5 个页码，
// 既能让管理员看到邻近页，又不会在页数很多时撑破分页器。
  const span = 2
// 从第一页遍历到最后一页，逐页决定是否保留。
  for (let i = 1; i <= all; i++) {
// 首末页永远保留；当前页附近的页码也保留。
    if (i === 1 || i === all || (i >= cur - span && i <= cur + span)) {
      pages.push(i)
// 被跳过的区间只插入一个省略号；
// 判断上一个元素是否已经是省略号，避免连续插入多个。
    } else if (pages[pages.length - 1] !== '…') {
      pages.push('…')
    }
  }
  return pages
})

/**
 * 跳转到指定页。
 * 边界保护：小于 1、大于总页数、或与当前页相同都直接返回，
 * 避免重复请求和无效翻页；合法时更新页码并重新加载列表。
 * 上一页/下一页按钮也复用此函数，并各自用 disabled 做首末页保护。
 */
function gotoPage(p: number) {
// 三个边界条件分别对应：越界、超过末页、点击当前页。
// 任一命中都直接返回，不产生请求。
  if (p < 1 || p > totalPages.value || p === query.page) return
// 只有合法的新页码才写入 query，触发依赖它的 computed 重算。
  query.page = p
// 页码变化后重新请求服务端数据，分页不做本地切片。
  loadList()
}

// —— 状态徽章映射（设计系统 .badge 色调） ——
/**
 * 报修单状态 → 设计系统徽章色调。
 * 映射规则：
 *   SUBMITTED 已提交、待处理   → warn    （黄色，提示需要关注）
 *   VERIFIED  已核验、待维修   → info    （蓝色，流程进行中）
 *   REPAIRING 维修中           → brand   （品牌色，当前主流程）
 *   COMPLETED 已完成           → ok      （绿色，正常收口）
 *   REJECTED  已驳回           → bad     （红色，异常终止）
 *   SCRAPPED  已报废           → bad     （红色，异常终止）
 *   其他 / 未知                → neutral （灰色兜底）
 * 返回的字符串会拼到 .badge 的 class 上，由全局设计系统样式统一着色。
 * 这里只负责「语义 → 色调」，不关心具体颜色值。
 */
function reportBadge(status?: string): string {
  switch (status) {
    case 'SUBMITTED':
      return 'warn'
    case 'VERIFIED':
      return 'info'
    case 'REPAIRING':
      return 'brand'
    case 'COMPLETED':
      return 'ok'
    case 'REJECTED':
    case 'SCRAPPED':
      return 'bad'
    default:
      return 'neutral'
  }
}

/**
 * 维修进度 → 徽章色调。
 *   COMPLETED     维修完成 → ok    （可以进入验收）
 *   FAILED        维修失败 → bad   （需要重新安排维修）
 *   WAITING_PARTS 等待配件 → warn  （流程受阻，需要关注）
 *   其余状态（REPAIRING 等）→ info （维修正在进行）
 * 未开始维修时 progressStatus 为空，模板会直接显示短横线，不会调用本函数。
 */
function progressBadge(status?: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'ok'
    case 'FAILED':
      return 'bad'
    case 'WAITING_PARTS':
      return 'warn'
    default:
      return 'info'
  }
}

/**
 * 验收结果 → 徽章色调。
 *   QUALIFIED   合格     → ok      （绿色）
 *   UNQUALIFIED 不合格   → bad     （红色）
 *   PARTIAL     部分合格 → warn    （黄色）
 *   未验收 / 未知        → neutral （灰色兜底）
 * 与 reportBadge / progressBadge 一样，只返回色调关键字。
 */
function acceptBadge(result?: string): string {
  switch (result) {
    case 'QUALIFIED':
      return 'ok'
    case 'UNQUALIFIED':
      return 'bad'
    case 'PARTIAL':
      return 'warn'
    default:
      return 'neutral'
  }
}

// —— 详情抽屉 ——
/**
 * 详情抽屉开关。
 * 打开时同时写入目标行并异步拉取详情；关闭仅切换该标记，不主动清空 detail，
 * 这样再次打开能先复用上一次的数据骨架，减少闪烁。
 */
const detailVisible = ref(false)
/**
 * 详情请求加载态。
 * 为 true 时抽屉内展示骨架屏；请求结束（无论成功失败）都复位。
 */
const detailLoading = ref(false)
/**
 * 当前查看的报修单详情。
 * 打开抽屉时先放入列表行数据以便立即渲染，随后由 detail 接口覆盖为完整数据；
 * 接口返回前，抽屉展示的是列表快照，字段可能少于详情接口。
 */
const detail = ref<DamageReportVO | null>(null)

/**
 * 按 id 拉取报修单最新详情。
 * 只负责请求与赋值，不管理弹窗开关；请求异常交给全局拦截器提示。
 * finally 复位 detailLoading，保证骨架屏不会卡住。
 */
async function fetchDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await damageApi.detail(id)
  } finally {
    detailLoading.value = false
  }
}

/**
 * 打开详情抽屉。
 * 先用行数据填充 detail，让抽屉第一帧就有内容，避免空白闪烁；
 * 同时发起详情请求，用后端返回的完整字段替换行数据。
 * 这里不等待详情请求完成，抽屉会随 detail 更新而自动重渲染。
 */
function openDetail(row: DamageReportVO) {
  detail.value = row
  detailVisible.value = true
  fetchDetail(row.id)
}

/**
 * 写操作成功后刷新详情。
 * 仅当抽屉仍然打开，且正在看的就是这张单时才重新请求。
 * 这样即使用户在弹窗操作期间关闭了抽屉或切换到别的单，也不会产生无意义请求，
 * 更不会把别的单的详情覆盖成当前单。
 */
async function refreshCurrentDetail(id: number) {
  if (detailVisible.value && detail.value?.id === id) {
    await fetchDetail(id)
  }
}

// 三段状态机时间线：核验 → 维修 → 验收
/**
 * 时间线阶段的渲染模型。
 *   title  阶段名称（核验 / 维修 / 验收）；
 *   done   该阶段是否已结束；
 *   active 是否为当前正在进行的阶段（第一个未完成阶段）；
 *   time   阶段完成时间，未完成时为空；
 *   desc   阶段说明，包含操作人、枚举结果或下一步提示。
 * 该接口只服务于模板，不与后端字段一一对应，避免模板里写推导逻辑。
 */
interface TimelineStage {
  title: string
  done: boolean
  active: boolean
  time?: string | null
  desc: string
}

/**
 * 由报修单字段推导三段式时间线。
 *
 * 完成判定：
 *   核验完成 = 状态不再是 SUBMITTED，即管理员已经处理过（确认或误报）；
 *   维修完成 = 报修单已完成/已报废，或维修进度为 COMPLETED / FAILED；
 *   验收完成 = 报修单已完成/已报废，或存在 acceptTime。
 *
 * 时间取值：
 *   核验阶段用 verifyTime；
 *   维修阶段优先用进度更新时间 progressUpdateTime，缺失时退回 repairStartTime；
 *   验收阶段用 acceptTime。
 *
 * 说明文案：
 *   已完成的阶段拼接「操作人 · 枚举标签」；
 *   未完成时给出下一步提示：等待核验、等待安排维修、维修后可验收等；
 *   驳回单明确显示「已驳回，未进入维修」；
 *   维修失败是特殊分支，明确提示需重新安排维修，避免管理员误点验收。
 *
 * active 计算：
 *   找到第一个未完成阶段的索引，仅该阶段标记 active，用于时间线高亮；
 *   全部完成时没有任何 active 阶段。
 *
 * 边界：
 *   detail 为空时返回空数组，模板不会渲染时间线；
 *   字段缺失时用空串或占位符拼接，不会产生 "undefined" 文本。
 */
const timelineStages = computed<TimelineStage[]>(() => {
// 抽屉尚未加载出详情时没有可推导的数据，直接返回空时间线。
  const d = detail.value
  if (!d) return []
// 只要状态离开 SUBMITTED，就说明核验动作已经发生过；
// 这里不区分核验通过还是误报，两者都算完成核验阶段。
  const verifyDone = d.status !== 'SUBMITTED'
// 维修完成的判定覆盖两类收口：
// 一是报修单已经完成或报废，二是维修进度自身到达完成或失败。
// 维修失败也视为维修阶段结束，但后续验收会被特殊提示阻断。
  const repairDone =
    d.status === 'COMPLETED' ||
    d.status === 'SCRAPPED' ||
    d.progressStatus === 'COMPLETED' ||
    d.progressStatus === 'FAILED'
// 单独记录失败标记，用于验收阶段给出「需重新安排维修」的说明。
  const repairFailed = d.progressStatus === 'FAILED'
// 验收完成的判定覆盖：报修单终态，或明确存在验收时间。
// 这样即使历史数据的 status 尚未同步，只要写过 acceptTime 也能正确显示。
  const acceptDone = d.status === 'COMPLETED' || d.status === 'SCRAPPED' || !!d.acceptTime

// 三个阶段固定按业务流程顺序排列，模板直接遍历，
// 阶段数量与顺序不随数据变化，变化的只是 done / time / desc。
  const stages: TimelineStage[] = [
    {
// 核验阶段：完成时展示核验人与核验结果，未完成时提示等待管理员核验。
// time 只在该阶段完成时传入，未完成时留空，模板不会渲染时间行。
// 以下三个 stage 对象分别对应核验、维修、验收，
// 每个对象的字段含义见 TimelineStage 接口注释。
      title: '核验',
      done: verifyDone,
      active: false,
      time: verifyDone ? d.verifyTime : undefined,
      desc: verifyDone
        ? `${d.verifier ? d.verifier + ' · ' : ''}${enumLabel(VERIFY_RESULT_LABELS, d.verifyResult)}`
        : '等待管理员核验',
    },
    {
// 维修阶段：完成时优先展示进度更新时间，缺失时退回开始维修时间。
// 驳回单单独提示「未进入维修」；其余未完成情况提示等待安排维修。
// 注意：维修阶段的 time 取值顺序是 progressUpdateTime → repairStartTime，
// 前者代表最近一次进度更新，更贴近「阶段结束」的语义。
      title: '维修',
      done: repairDone,
      active: false,
      time: repairDone ? d.progressUpdateTime || d.repairStartTime : undefined,
      desc: repairDone
        ? `${d.repairPerson ? d.repairPerson + ' · ' : ''}${
            d.progressStatus ? enumLabel(PROGRESS_STATUS_LABELS, d.progressStatus) : '维修中'
          }`
        : d.status === 'REJECTED'
          ? '已驳回，未进入维修'
          : '等待安排维修',
    },
    {
// 验收阶段：完成时展示验收人与验收结果。
// 维修失败时给出明确的阻断提示，避免管理员把失败单当成可验收。
// 验收阶段的 desc 在未完成时区分了「维修失败」与「维修完成后验收」，
// 这两种提示会影响管理员下一步操作，因此不能合并成同一句。
      title: '验收',
      done: acceptDone,
      active: false,
      time: acceptDone ? d.acceptTime : undefined,
      desc: acceptDone
        ? `${d.acceptor ? d.acceptor + ' · ' : ''}${
            d.acceptResult ? enumLabel(ACCEPT_RESULT_LABELS, d.acceptResult) : ''
          }`
        : repairFailed
          ? '维修失败，需重新安排维修后再验收'
          : '维修完成后进行验收',
    },
  ]

// 第一个未完成阶段就是当前阶段；全部完成时 findIndex 返回 -1。
  const firstPending = stages.findIndex((s) => !s.done)
// 只把第一个未完成阶段标记为 active，其余阶段保持普通样式；
// findIndex 为 -1 时没有任何阶段会被标记。
  stages.forEach((s, i) => {
    s.active = firstPending === i
  })
  return stages
})

// 图片预览
/**
 * 图片预览地址。
 * 空串表示预览层关闭；非空时模板渲染全屏遮罩并展示大图。
 */
const previewUrl = ref('')
/**
 * 打开图片大图预览。
 * 入参 url 已经过 resolveFileUrl 处理，可直接用于 <img :src>；
 * 点击遮罩或图片本身都会把 previewUrl 置空以关闭预览。
 */
function openPreview(url: string) {
  previewUrl.value = url
}

// —— 核验 ——
// ────────────────────────────────────────────────────────────────
// 核验弹窗状态：SUBMITTED → VERIFIED / REJECTED，可同时登记维修安排
// ────────────────────────────────────────────────────────────────
const verifyVisible = ref(false)
const verifySaving = ref(false)
const verifyTarget = ref<DamageReportVO | null>(null)
/**
 * 核验表单。
 *   verifyResult    必填，默认 CONFIRMED；
 *   repairPerson    选填，仅在非误报时有意义；
 *   repairEstimate  选填，自由文本，如「3 天」；
 *   remark          管理员备注，提交前 trim，空串不发送。
 * 表单在每次打开弹窗时由 openVerify 重置，避免上一次输入残留。
 */
const verifyForm = reactive<{
  verifyResult: VerifyResult
  repairPerson: string
  repairEstimate: string
  remark: string
}>({
  verifyResult: 'CONFIRMED',
  repairPerson: '',
  repairEstimate: '',
  remark: '',
})

/**
 * 打开核验弹窗。
 * 把目标行存入 verifyTarget，重置全部字段，然后打开弹窗。
 * 核验结果默认选中「确认」，覆盖最常见的正常路径；
 * 误报路径由管理员手动切换，切换后维修安排字段会隐藏。
 */
function openVerify(row: DamageReportVO) {
  verifyTarget.value = row
  verifyForm.verifyResult = 'CONFIRMED'
  verifyForm.repairPerson = ''
  verifyForm.repairEstimate = ''
  verifyForm.remark = ''
  verifyVisible.value = true
}

/**
 * 提交核验。
 * 校验：verifyTarget 必须存在，否则直接返回。
 * 请求体：
 *   verifyResult 必传；
 *   负责人、预计时长、备注先在本地 trim，非空才放入 payload，
 *   避免把空字符串当作有效值提交。
 * 副作用：
 *   成功后提示、关闭弹窗、刷新列表；
 *   如果详情抽屉仍打开且仍是这张单，同时刷新详情；
 *   finally 复位 verifySaving，失败时按钮恢复可点，异常由拦截器提示。
 */
async function submitVerify() {
// 取当前弹窗绑定的目标行。正常情况下一定存在，
// 但类型上允许 null，因此仍需守卫，避免提交到 undefined.id。
  const target = verifyTarget.value
  if (!target) return
// 必填字段直接放入请求体；可选字段先 trim，非空才追加，
// 这样后端收到的对象结构更干净，也避免空串覆盖已有值。
  const payload: DamageVerifyDTO = { verifyResult: verifyForm.verifyResult }
  if (verifyForm.repairPerson.trim()) payload.repairPerson = verifyForm.repairPerson.trim()
  if (verifyForm.repairEstimate.trim()) payload.repairEstimate = verifyForm.repairEstimate.trim()
  if (verifyForm.remark.trim()) payload.remark = verifyForm.remark.trim()
// 保存标记在请求前点亮，用于禁用按钮并显示 loading；
// finally 中复位，失败时管理员可以修正后再次提交。
  verifySaving.value = true
  try {
// 核验接口会推进报修单状态；成功返回后本地不做乐观更新，
// 统一通过重新拉取列表与详情获取后端权威状态。
    await damageApi.verify(target.id, payload)
// 成功提示使用简短文案，不包含业务细节，细节由列表/详情展示。
    ElMessage.success('报修核验已完成')
// 先关闭弹窗再刷新数据：即使刷新较慢，用户也能立即看到操作已结束。
    verifyVisible.value = false
// 刷新列表以更新当前页的状态徽章与可用操作按钮。
    await loadList()
// 若详情抽屉仍打开且仍查看该单，同步刷新详情；
// refreshCurrentDetail 内部会判断抽屉可见性与 id，避免无意义请求。
    await refreshCurrentDetail(target.id)
  } finally {
// 无论接口成功或失败都复位保存态，防止按钮永久禁用。
    verifySaving.value = false
  }
}

// —— 维修进度 ——
// ────────────────────────────────────────────────────────────────
// 维修进度弹窗状态：VERIFIED / REPAIRING 下按次追加进度与费用
// ────────────────────────────────────────────────────────────────
const progressVisible = ref(false)
const progressSaving = ref(false)
const progressTarget = ref<DamageReportVO | null>(null)
/**
 * 维修进度表单。
 *   progressStatus  本次更新后的进度状态，默认 REPAIRING；
 *   progressNote    必填，用于留痕，最多 200 字；
 *   repairCost      number 或 null，null 表示本次不更新费用；
 *   repairEstimate  预计时长，可覆盖或补充核验时登记的信息；
 *   repairPerson    维修负责人，可在此更新。
 * 费用单独用 null 表达「未填写」，因为 0 元也是合法金额。
 */
const progressForm = reactive<{
  progressStatus: ProgressStatus
  progressNote: string
  repairCost: number | null
  repairEstimate: string
  repairPerson: string
}>({
  progressStatus: 'REPAIRING',
  progressNote: '',
  repairCost: null,
  repairEstimate: '',
  repairPerson: '',
})

/**
 * 打开进度弹窗。
 * 进度状态沿用行数据中的当前值，缺失时回退为 REPAIRING；
 * 负责人与预计时长回填已有值，方便管理员只修改变化的字段；
 * 进度说明与费用每次重置，避免把上一次的说明当成新进度提交。
 */
function openProgress(row: DamageReportVO) {
  progressTarget.value = row
  progressForm.progressStatus = row.progressStatus ?? 'REPAIRING'
  progressForm.progressNote = ''
  progressForm.repairCost = null
  progressForm.repairEstimate = row.repairEstimate ?? ''
  progressForm.repairPerson = row.repairPerson ?? ''
  progressVisible.value = true
}

/**
 * 提交维修进度。
 * 校验：目标行存在，且 progressNote 去掉首尾空格后不能为空；
 * 空说明会给出 warning 并中止提交，不发送请求。
 * 请求体：
 *   progressStatus / progressNote 必传；
 *   repairCost 仅在非 null 时提交，允许为 0（免费维修）；
 *   repairPerson / repairEstimate trim 后非空才提交。
 * 副作用：
 *   成功后提示、关闭弹窗、刷新列表与当前详情；
 *   失败时不关闭弹窗，用户修正后可重试。
 */
async function submitProgress() {
// 与核验一致：目标行缺失时直接返回，避免访问 undefined.id。
  const target = progressTarget.value
  if (!target) return
// 进度说明是留痕的核心字段，前端先做必填校验；
// 仅空白字符也视为空，避免提交一条没有信息量的进度。
  if (!progressForm.progressNote.trim()) {
    ElMessage.warning('请填写进度说明')
    return
  }
// 必填字段组成请求体基础结构；可选字段随后按需追加。
  const payload: DamageProgressDTO = {
    progressStatus: progressForm.progressStatus,
    progressNote: progressForm.progressNote.trim(),
  }
  if (progressForm.repairCost !== null) payload.repairCost = progressForm.repairCost
  if (progressForm.repairEstimate.trim()) payload.repairEstimate = progressForm.repairEstimate.trim()
  if (progressForm.repairPerson.trim()) payload.repairPerson = progressForm.repairPerson.trim()
// 请求期间禁用保存按钮，防止连续点击产生多条重复进度。
  progressSaving.value = true
  try {
// 进度接口按次追加记录，成功后不在这里手工改本地状态，
// 而是重新拉取列表与详情，确保时间线、费用与进度说明一致。
    await damageApi.progress(target.id, payload)
// 成功提示与核验保持统一语气，便于操作日志检索。
    ElMessage.success('维修进度已更新')
// 关闭弹窗，随后刷新列表与详情。
    progressVisible.value = false
// 列表刷新后，进度徽章与验收按钮的显隐会随新状态更新。
    await loadList()
// 详情刷新后，维修时间线、费用、进度说明会展示本次提交结果。
    await refreshCurrentDetail(target.id)
  } finally {
// 失败时保持弹窗打开，保留用户已填内容，便于修正后重试。
    progressSaving.value = false
  }
}

// —— 验收 ——
// ────────────────────────────────────────────────────────────────
// 验收弹窗状态：仅 REPAIRING + COMPLETED 开放，验收结果决定工具去向
// ────────────────────────────────────────────────────────────────
const acceptVisible = ref(false)
const acceptSaving = ref(false)
const acceptTarget = ref<DamageReportVO | null>(null)
/**
 * 验收表单。
 *   acceptResult    必填，默认 QUALIFIED；
 *   toolStatusAfter 决定验收后工具写入的状态，默认 AVAILABLE；
 *   repairCost      最终结算金额，打开时回填已有费用；
 *   remark          验收备注，提交前 trim，空串不发送。
 * 三个字段共同决定报修单如何收口，以及工具能否再次对外借出。
 */
const acceptForm = reactive<{
  acceptResult: AcceptResult
  toolStatusAfter: ToolStatusAfter
  repairCost: number | null
  remark: string
}>({
  acceptResult: 'QUALIFIED',
  toolStatusAfter: 'AVAILABLE',
  repairCost: null,
  remark: '',
})

/**
 * 打开验收弹窗。
 * 结果默认合格、工具默认可用，对应正常维修成功的主路径；
 * 费用回填进度阶段录入的 repairCost，允许在验收时做最终调整。
 * 其他字段每次重置，避免上一次验收的备注或状态残留。
 */
function openAccept(row: DamageReportVO) {
  acceptTarget.value = row
  acceptForm.acceptResult = 'QUALIFIED'
  acceptForm.toolStatusAfter = 'AVAILABLE'
  acceptForm.repairCost = row.repairCost ?? null
  acceptForm.remark = ''
  acceptVisible.value = true
}

/**
 * 提交验收。
 * 校验：目标行必须存在；验收结果与工具状态由下拉保证合法。
 * 请求体：
 *   acceptResult / toolStatusAfter 必传；
 *   repairCost 非 null 时提交，0 元同样有效；
 *   remark trim 后非空才提交。
 * 约束：
 *   部分合格 / 不合格时工具状态不能是 AVAILABLE；
 *   前端下拉已过滤，watch 也会兜底纠正，后端还会再次校验，三重收口避免脏数据。
 * 副作用：
 *   成功后提示、关闭弹窗、刷新列表与当前详情。
 */
async function submitAccept() {
// 目标行守卫：验收弹窗正常打开时必有值，这里做类型收窄与防御。
  const target = acceptTarget.value
  if (!target) return
// 验收结果与工具状态是必填项，直接放入请求体；
// 费用与备注按「有值才提交」的规则追加。
  const payload: DamageAcceptDTO = {
    acceptResult: acceptForm.acceptResult,
    toolStatusAfter: acceptForm.toolStatusAfter,
  }
  if (acceptForm.repairCost !== null) payload.repairCost = acceptForm.repairCost
  if (acceptForm.remark.trim()) payload.remark = acceptForm.remark.trim()
// 验收期间禁用按钮，避免一次维修被重复验收。
  acceptSaving.value = true
  try {
// 验收接口是状态机最后一步，成功后会同时更新报修单与工具状态。
    await damageApi.accept(target.id, payload)
// 成功提示明确动作已完成，随后关闭弹窗并同步数据。
    ElMessage.success('维修验收已完成')
// 关闭弹窗，避免用户再次点击造成重复验收。
    acceptVisible.value = false
// 刷新列表：报修单状态与操作按钮会随验收结果变化。
    await loadList()
// 刷新详情：验收信息分区与工具状态会展示本次结果。
    await refreshCurrentDetail(target.id)
  } finally {
// 失败时复位保存态，弹窗保持打开，用户可调整后重试。
    acceptSaving.value = false
  }
}

// 原生 number 输入：空串回退为 null，保持与 el-input-number 清空语义一致
/**
 * 读取原生 number 输入框的值。
 * 浏览器清空输入时 value 为空串，这里转换为 null，
 * 与「未填写」语义保持一致，避免 Number('') === 0 把空值误当成免费维修。
 * 只处理 input 事件，不负责校验负数或小数位，min/step 由输入框属性约束。
 */
function readCost(e: Event): number | null {
  const raw = (e.target as HTMLInputElement).value
  return raw === '' ? null : Number(raw)
}

/**
 * 是否允许核验：只有 SUBMITTED（待核验）的报修单可以操作。
 * 一旦核验过，状态会离开 SUBMITTED，按钮随之消失；
 * 该判断只影响按钮显隐，真正的权限与状态校验在后端。
 */
function canVerify(row: DamageReportVO): boolean {
  return row.status === 'SUBMITTED'
}

/**
 * 是否允许更新维修进度：已核验或维修中的单子。
 * 维修完成 / 失败后不再显示进度按钮，需走验收或重新安排维修；
 * 驳回、待核验等状态也不会出现该按钮。
 */
function canProgress(row: DamageReportVO): boolean {
  return row.status === 'VERIFIED' || row.status === 'REPAIRING'
}

/**
 * 是否允许验收。
 * 当前原型要求同时满足：报修单处于 REPAIRING 且维修进度为 COMPLETED。
 * 维修失败、等待配件等状态都不能验收，防止在未修好的情况下收口；
 * 只有维修完成的单子才会出现绿色的「验收」入口。
 */
function canAccept(row: DamageReportVO): boolean {
  // 原型：仅维修中且维修进度已完成时开放验收
  return row.status === 'REPAIRING' && row.progressStatus === 'COMPLETED'
}

// 部分合格 / 不合格不能直接置为可用，后端会拒绝；这里同步收口选项
/**
 * 验收后工具状态的可选值。
 * 过滤规则：
 *   1. 排除 RENTED：验收动作不应直接把工具置为租出；
 *   2. 验收结果不是 QUALIFIED 时，再排除 AVAILABLE，
 *      因为部分合格 / 不合格的工具不能直接回到可借状态。
 * 该计算属性随 acceptForm.acceptResult 变化而实时重算；
 * 与后端校验保持一致，减少用户提交后被拒绝的情况。
 * 注意：选项被移除时 watch 会把非法值纠正为 MAINTENANCE。
 */
const toolStatusAfterOptions = computed(() =>
  optionList(TOOL_STATUS_LABELS).filter(
    (item) => item.value !== 'RENTED' && (acceptForm.acceptResult === 'QUALIFIED' || item.value !== 'AVAILABLE'),
  ),
)

/**
 * 验收结果变化时的联动。
 * 从「合格」切到「部分合格 / 不合格」时，如果工具状态仍是 AVAILABLE，
 * 自动改为 MAINTENANCE，避免留下一个不合法组合。
 * 反向切换（改回合格）不会自动改回 AVAILABLE，保留用户当前选择。
 */
watch(
  () => acceptForm.acceptResult,
  (result) => {
    if (result !== 'QUALIFIED' && acceptForm.toolStatusAfter === 'AVAILABLE') {
      acceptForm.toolStatusAfter = 'MAINTENANCE'
    }
  },
)

/**
 * 工具状态变化时的兜底。
 * 即使下拉选项被绕过，只要验收结果不是合格且状态被设为 AVAILABLE，
 * 立即纠正为 MAINTENANCE，保证提交数据始终合法。
 * 该 watch 与上一个 watch 互为补充，覆盖两个字段的任意修改顺序。
 */
watch(
  () => acceptForm.toolStatusAfter,
  (status) => {
    if (acceptForm.acceptResult !== 'QUALIFIED' && status === 'AVAILABLE') {
      acceptForm.toolStatusAfter = 'MAINTENANCE'
    }
  },
)

/**
 * 页面挂载后立即加载第一页列表。
 * 不在 setup 顶层直接请求，避免副作用在组件实例尚未挂载时执行；
 * onMounted 只执行一次，后续刷新都由用户操作或写操作触发。
 */
onMounted(loadList)
/**
 * ============================================================================
 * 附录：本页字段、接口与状态迁移速查
 * ============================================================================
 *
 * 一、接口清单
 *   GET  /damage/list     分页查询。参数：page、size、keyword、status。
 *                         返回：{ records: DamageReportVO[], total: number }。
 *   GET  /damage/{id}     查询详情。返回：DamageReportVO。
 *   POST /damage/{id}/verify    核验。请求体：DamageVerifyDTO。
 *   POST /damage/{id}/progress  更新维修进度。请求体：DamageProgressDTO。
 *   POST /damage/{id}/accept    验收。请求体：DamageAcceptDTO。
 *   以上方法统一封装在 @/api/damage 的 damageApi 对象中，
 *   本页不直接拼 URL，也不直接处理鉴权头。
 *
 * 二、列表查询字段 DamageQuery
 *   page     页码，从 1 开始；由分页器与写操作后的刷新共同维护。
 *   size     每页条数，可选 10 / 20 / 50；切换时页码重置为 1。
 *   keyword  模糊搜索，匹配报修单号、工具名或报修人；空串会被 cleanParams 剔除。
 *   status   报修单状态精确筛选；空串表示全部状态，同样会被剔除。
 *
 * 三、核验请求字段 DamageVerifyDTO
 *   verifyResult    必填。核验结论，决定后续状态走向。
 *   repairPerson    选填。维修负责人，可在后续进度中覆盖。
 *   repairEstimate  选填。预计时长，自由文本，仅用于展示。
 *   remark          选填。核验备注，展示在详情损坏描述下方。
 *   提交前所有字符串字段都会 trim，空值不会进入请求体。
 *
 * 四、进度请求字段 DamageProgressDTO
 *   progressStatus  必填。本次更新后的维修进度。
 *   progressNote    必填。本次进度说明，最多 200 字。
 *   repairCost      选填。number；未填写时为 null，0 是合法金额。
 *   repairEstimate  选填。可在此覆盖核验时填写的预计时长。
 *   repairPerson    选填。可在此更新维修负责人。
 *
 * 五、验收请求字段 DamageAcceptDTO
 *   acceptResult    必填。验收结论：合格 / 部分合格 / 不合格。
 *   toolStatusAfter 必填。验收后工具状态，决定工具能否再次借出。
 *   repairCost      选填。最终结算金额，可回填并调整进度阶段的费用。
 *   remark          选填。验收备注。
 *
 * 六、报修单视图字段 DamageReportVO（本页实际使用的部分）
 *   id                  报修单主键，用作列表 key 与详情请求参数。
 *   reportNo            报修单号，展示在列表首列与详情标题。
 *   toolName            工具名称。
 *   toolCode            工具编码，用于在工具台账中唯一定位。
 *   toolImageUrl        工具图片 key / 路径，经 resolveFileUrl 转直链。
 *   reporterName        报修人姓名。
 *   reporterPhone       报修人电话。
 *   damagePart          损坏部位。
 *   description         损坏描述，居民提交的原始说明。
 *   images              证明材料图片地址数组，可能为空。
 *   status              报修单主状态。
 *   statusName          后端返回的状态中文名，优先于枚举映射。
 *   progressStatus      维修进度，未开始维修时为空。
 *   progressStatusName  后端返回的进度中文名，优先于枚举映射。
 *   progressNote        最近一次进度说明。
 *   verifier            核验人。
 *   verifyTime          核验时间。
 *   verifyResult        核验结果。
 *   repairPerson        当前维修负责人。
 *   repairEstimate      预计维修时长。
 *   repairStartTime     开始维修时间。
 *   progressUpdateTime  最近一次进度更新时间。
 *   repairCost          维修费用，number 或 null。
 *   acceptor            验收人。
 *   acceptTime          验收时间。
 *   acceptResult        验收结果。
 *   acceptResultName    后端返回的验收结果中文名，优先于枚举映射。
 *   toolStatusAfter     验收后工具状态。
 *   rentalId            关联租赁 id，未关联时为空。
 *   createTime          报修单提交时间。
 *   remark              报修备注。
 *
 * 七、枚举与中文标签
 *   REPORT_STATUS_LABELS 覆盖 SUBMITTED / VERIFIED / REPAIRING / COMPLETED /
 *                        REJECTED / SCRAPPED 等报修单状态。
 *   PROGRESS_STATUS_LABELS 覆盖 REPAIRING / WAITING_PARTS / COMPLETED / FAILED。
 *   VERIFY_RESULT_LABELS 覆盖 CONFIRMED / FALSE 等核验结论。
 *   ACCEPT_RESULT_LABELS 覆盖 QUALIFIED / PARTIAL / UNQUALIFIED。
 *   TOOL_STATUS_LABELS 覆盖 AVAILABLE / RENTED / MAINTENANCE / SCRAPPED 等工具状态。
 *   枚举映射只用于展示与下拉选项，不参与后端判定。
 *
 * 八、状态迁移速查
 *   SUBMITTED --核验确认--> VERIFIED --开始维修--> REPAIRING
 *   SUBMITTED --核验误报--> REJECTED
 *   VERIFIED / REPAIRING --进度更新--> WAITING_PARTS（可继续更新）
 *   WAITING_PARTS / REPAIRING --进度完成--> REPAIRING + COMPLETED
 *   REPAIRING + COMPLETED --验收--> COMPLETED（合格）
 *   REPAIRING + COMPLETED --验收--> 部分合格 / 不合格（工具不可用）
 *   REPAIRING --进度失败--> FAILED（需重新安排维修，不能直接验收）
 *   迁移由后端最终落库，本页只负责按状态显隐入口与提交合法请求。
 *
 * 九、前端不变量
 *   1. 空关键词 / 空状态不进入请求参数；
 *   2. 维修进度筛选只影响当前页渲染，不影响 total；
 *   3. 验收结果非 QUALIFIED 时，toolStatusAfter 不能为 AVAILABLE；
 *   4. 验收后工具状态不能为 RENTED；
 *   5. 进度说明不能为空；
 *   6. 金额空值用 null 表达，0 是合法金额；
 *   7. 写操作成功后列表与详情必须同时刷新（详情按可见性按需刷新）。
 *
 * 十、常见排查路径
 *   - 列表没有数据：先看 query.status / keyword 是否残留，再看 progressFilter；
 *   - 验收按钮不出现：确认 status 是否为 REPAIRING 且 progressStatus 是否为 COMPLETED；
 *   - 工具状态选项变少：检查 acceptResult 是否为 PARTIAL / UNQUALIFIED；
 *   - 时间线阶段不前进：检查对应时间字段与状态是否已由后端写入；
 *   - 提交被后端拒绝：对照上方请求字段与状态迁移，确认前端是否绕过了状态机。
 */
/**
 * ============================================================================
 * 附录二：响应式状态、模板结构与样式分区速查
 * ============================================================================
 *
 * 一、响应式状态一览
 *   loading          列表加载态；loadList 进入时 true，finally 复位。
 *   list             当前页报修单原始数据；只由接口返回覆盖。
 *   total            服务端总数；驱动总页数与统计文案。
 *   query            列表查询参数；page / size / keyword / status。
 *   progressFilter   客户端维修进度筛选；不参与请求。
 *   visibleList      客户端筛选后的渲染列表；空数组触发空态。
 *   detailVisible    详情抽屉开关。
 *   detailLoading    详情加载态；控制抽屉内骨架屏。
 *   detail           详情数据；打开抽屉时先用行数据占位。
 *   previewUrl       图片预览地址；空串表示关闭。
 *   verifyVisible    核验弹窗开关。
 *   verifySaving     核验保存态；禁用确认按钮。
 *   verifyTarget     核验目标行；提交时读取 id。
 *   verifyForm       核验表单；打开弹窗时整体重置。
 *   progressVisible  进度弹窗开关。
 *   progressSaving   进度保存态。
 *   progressTarget   进度目标行。
 *   progressForm     进度表单；状态与负责人会回填，说明与费用每次重置。
 *   acceptVisible    验收弹窗开关。
 *   acceptSaving     验收保存态。
 *   acceptTarget     验收目标行。
 *   acceptForm       验收表单；结果与工具状态默认合格/可用。
 *
 * 二、计算属性一览
 *   visibleList          进度筛选后的列表。
 *   totalPages           总页数，至少为 1。
 *   pageNumbers          省略号页码序列。
 *   toolStatusAfterOptions 验收后工具状态的可选值。
 *   timelineStages       核验 / 维修 / 验收三段推导结果。
 *
 * 三、模板结构导览
 *   .page
 *     ├─ .page-head        标题与流程说明
 *     ├─ .filterbar        关键词、状态、进度、查询/重置、总数
 *     ├─ .table-wrap       表格、骨架屏、空态、表尾分页
 *     ├─ 核验弹窗          核验结果 + 可选维修安排 + 备注
 *     ├─ 进度弹窗          进度状态 + 说明 + 负责人/时长 + 费用
 *     ├─ 验收弹窗          验收结果 + 工具状态 + 费用 + 备注
 *     ├─ 详情抽屉          徽章、时间线、描述、基础信息、材料、三段信息
 *     └─ 预览层            全屏图片预览
 *
 * 四、样式分区说明
 *   .badge-row                 详情顶部徽章行的下间距。
 *   .timeline li.active::before 当前阶段的高亮外环。
 *   .dmg-desc / .dmg-note      损坏描述卡片与补充备注。
 *   .dmg-imgs / img            证明材料缩略图容器与固定尺寸裁切。
 *   .rep-section / h4 / .ic    核验、维修、验收三个信息分区卡片。
 *   .preview-mask / .preview-img 图片预览遮罩与受限尺寸大图。
 *   其余基础样式（按钮、表格、弹窗、抽屉）均来自全局设计系统，
 *   本文件只补充与报修详情强相关的局部样式。
 *
 * 五、错误与并发处理
 *   1. 请求异常不在这里逐个 catch，由 request 拦截器统一提示；
 *      但所有加载态与保存态都在 finally 中复位，避免 UI 卡死。
 *   2. 三个 submit 函数在请求前设置 Saving，按钮同时 disabled，
 *      从交互层避免重复提交；后端仍应保证接口幂等或做状态校验。
 *   3. 写操作成功后的刷新是顺序 await：先列表、后详情，
 *      保证详情刷新时列表状态已经是最新，不会出现短暂不一致。
 *   4. 关闭弹窗早于数据刷新，用户感知更快；
 *      刷新失败时列表保持旧数据，下一次操作或重新进入会再次拉取。
 *
 * 六、交互与可访问性细节
 *   1. 弹窗与抽屉都支持点击遮罩关闭，但点击内容区不会冒泡关闭。
 *   2. 关闭按钮使用图标按钮，图标本身来自 Element Plus，语义由按钮承担。
 *   3. 所有可点击缩略图使用 cursor: zoom-in，预览大图使用 zoom-out 提示可关闭。
 *   4. 表格空态与详情空态都提供图标 + 标题 + 说明，避免只显示一行灰字。
 *   5. 分页首末页按钮通过 disabled 表达不可用，而不是隐藏，位置保持稳定。
 *   6. 状态一律用「颜色 + 文本」双通道表达，不只依赖颜色区分。
 */
/**
 * ============================================================================
 * 附录三：维护与扩展提示
 * ============================================================================
 *
 * 一、新增一个报修状态时
 *   1. 在 @/utils/enums 的 REPORT_STATUS_LABELS 中补充中文标签；
 *   2. 若新状态需要独立色调，在 reportBadge 中增加 case；
 *   3. 评估 canVerify / canProgress / canAccept 是否需要放行；
 *   4. 若新状态参与时间线完成判定，同步修改 timelineStages 的 done 条件；
 *   5. 检查后端契约与数据库枚举，前端 enumLabel 只做展示兜底。
 *
 * 二、新增一个维修进度时
 *   1. 在 PROGRESS_STATUS_LABELS 中补充标签，筛选栏与进度弹窗会自动出现；
 *   2. 在 progressBadge 中决定新进度的色调；
 *   3. 明确它是否算「维修完成」：只有 COMPLETED 会让验收按钮出现；
 *   4. 明确它是否算「维修失败」：FAILED 会影响验收阶段的提示文案。
 *
 * 三、新增一个验收结果时
 *   1. 在 ACCEPT_RESULT_LABELS 中补充标签；
 *   2. 在 acceptBadge 中决定色调；
 *   3. 检查 toolStatusAfterOptions 的过滤规则：
 *      新结果是否允许工具回到 AVAILABLE，必须与后端保持一致；
 *   4. 检查两个 watch 的兜底条件，避免非法工具状态被提交。
 *
 * 四、列表新增一列时
 *   1. 在 <thead> 增加表头，并同步调整骨架屏与空态的 colspan；
 *   2. 在数据行增加对应单元格，保持列顺序一致；
 *   3. 若新列需要格式化，优先复用 @/utils/format；
 *   4. 若新列参与筛选，先确认列表接口是否支持该参数；
 *      不支持时只能像 progressFilter 一样做客户端筛选。
 *
 * 五、调整分页大小时
 *   1. 修改每页条数下拉的选项与 query.size 默认值；
 *   2. 确认后端对 size 上限的限制，避免一次请求过多数据；
 *   3. pageNumbers 的 span 可独立调整，不影响请求逻辑；
 *   4. 切换 size 时 handleSizeChange 已负责把页码重置为 1。
 *
 * 六、新增写操作时
 *   1. 在 @/api/damage 中新增方法，不在组件里直接拼请求；
 *   2. 新增对应的 Visible / Saving / Target / Form 四类状态；
 *   3. 提交成功后沿用「关闭弹窗 → 刷新列表 → 刷新详情」的顺序；
 *   4. 提交前对可选字符串 trim，对金额区分 null 与 0；
 *   5. 同步更新 can* 判定与模板按钮，保证入口与后端状态机一致。
 *
 * 七、删除或重命名字段时
 *   1. 先在 @/api/types 中修改类型，让编译错误暴露所有引用；
 *   2. 搜索模板中的字段名，确认详情抽屉、时间线与列表都已同步；
 *   3. 检查附录中的字段字典与状态迁移说明是否需要更新；
 *   4. 不要只改展示不改判定，否则按钮显隐会与真实状态脱节。
 */
</script>

<template>
<!-- ============================================================ -->
<!-- 页面根容器：包含头部、筛选栏、列表、三个弹窗、详情抽屉与预览层 -->
<!-- 所有业务区块都在这一个根节点内，方便统一设置页面内边距与滚动行为 -->
<!-- 弹窗与抽屉虽然视觉上是浮层，但在 DOM 中仍挂在本页，关闭即销毁 -->
<!-- ============================================================ -->
  <div class="page">
<!-- 页面头部区块：只做信息展示，不承载业务操作 -->
<!-- reveal 是全局进入动画类，页面首次渲染时触发渐显 -->
<!-- 管理员在这里主要获得流程提示：核验 → 维修 → 验收，以及据实结算的原则 -->
<!-- 本页没有「新建报修」按钮，报修单由居民端提交，管理员只负责处理 -->
    <div class="page-head reveal">
      <div>
<!-- 小标题：英文 eyebrow，用于强化模块识别，不参与任何逻辑 -->
<!-- 使用大写文本与辅助色，与主标题形成层级对比 -->
        <p class="eyebrow">REPAIR WORKFLOW</p>
        <h1>报修管理</h1>
<!-- 主标题下方的流程说明：告知管理员本页处理的完整链路 -->
<!-- 文案中的「全程留痕、据实结算」对应维修进度与费用字段的录入要求 -->
        <p class="sub">按「核验 → 维修 → 验收」流程处理居民报修，全程留痕、据实结算。</p>
      </div>
    </div>

<!-- 筛选栏：关键词 + 报修状态 + 维修进度 + 查询 / 重置 -->
<!-- 关键词与报修状态会进入 query，随分页请求一起发给后端 -->
<!-- 维修进度只做客户端过滤，不进入接口参数，这是本页筛选的关键差异 -->
<!-- 回车与点击「查询」等价；重置不会清空进度筛选，保留下拉当前值 -->
<!-- 右侧「共 N 单」显示的是服务端总数，不受客户端进度筛选影响 -->
    <div class="filterbar reveal">
<!-- 搜索输入框容器：左侧 Search 图标仅作视觉提示，不拦截输入 -->
<!-- v-model 直接绑定 query.keyword，输入时不请求，回车或点查询才请求 -->
<!-- placeholder 说明搜索范围：报修单号 / 工具 / 报修人 -->
      <div class="f-search">
        <Search class="ic" />
<!-- 搜索输入框：keyup.enter 触发 handleSearch，与点击查询按钮行为一致 -->
<!-- 不在 input 事件上做防抖请求，避免每输入一个字就刷新列表 -->
<!-- 输入值允许为空，提交时 cleanParams 会把空关键词剔除 -->
        <input
          class="input"
          v-model="query.keyword"
          placeholder="报修单号 / 工具 / 报修人"
          @keyup.enter="handleSearch"
        />
      </div>
<!-- 报修状态下拉：绑定 query.status，空串代表全部状态 -->
<!-- 选项来自 REPORT_STATUS_LABELS 派生的 statusOptions，顺序与枚举声明一致 -->
<!-- 该筛选会进入请求参数，由后端在分页查询中执行 -->
      <select class="select f-item" v-model="query.status">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 维修进度下拉：绑定 progressFilter，只影响当前页的 visibleList -->
<!-- 切换后不会重新请求后端，因此 total 仍是服务端总数 -->
<!-- 这是纯客户端筛选：只能过滤当前页已有的记录，不能跨页搜索 -->
<!-- 表尾会额外提示本页筛选后的条数，避免管理员误以为总数变了 -->
<!-- 选项来自 PROGRESS_STATUS_LABELS，与进度弹窗共享同一份标签 -->
      <select class="select f-item" v-model="progressFilter">
        <option value="">全部维修进度</option>
        <option v-for="item in progressStatusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 查询按钮：回到第一页并按当前 query 重新请求 -->
<!-- 使用主按钮样式，是筛选栏的主要操作入口 -->
      <button class="btn btn-primary btn-sm" @click="handleSearch">
        <Search class="ic" />查询
      </button>
<!-- 重置按钮：清空关键词与状态，页码回到 1 后重新请求 -->
<!-- 使用幽灵按钮样式，视觉权重低于查询，避免误操作 -->
      <button class="btn btn-ghost btn-sm" @click="handleReset">
        <Refresh class="ic" />重置
      </button>
      <span class="grow"></span>
<!-- 服务端总数统计：只反映 query 筛选后的总数，与 progressFilter 无关 -->
<!-- 使用 grow 占位把右侧内容推到末端的布局由筛选栏 flex 完成 -->
<!-- 文案使用 tiny 小字号，避免与表格数据争夺注意力 -->
      <span class="tiny">共 {{ total }} 单</span>
    </div>

<!-- 列表主体容器：骨架屏 / 空态 / 数据三种状态互斥渲染 -->
<!-- table-scroll 负责横向滚动，窄屏下表格列不会被强行压缩 -->
<!-- table-foot 固定展示统计与分页，不随表格横向滚动而移动 -->
    <div class="table-wrap reveal">
      <div class="table-scroll">
        <table class="tbl">
<!-- 表头列定义：单号、工具、报修人、损坏部位、状态、进度、负责人、时间、操作 -->
<!-- 操作列固定宽度 200px，保证多个按钮在窄屏下不挤压其他列 -->
<!-- 列顺序按管理员阅读习惯排列：先定位单据，再看对象与状态，最后操作 -->
          <thead>
            <tr>
<!-- 列一：报修单号，主行是单号，次行是提交时间，便于快速定位 -->
<!-- 单号使用等宽 code 样式，复制核对时不易看错字符 -->
              <th>报修单号</th>
<!-- 列二：工具信息，缩略图 + 工具名 + 工具编码 -->
<!-- 图片缺失时显示占位短横线，保持行高稳定 -->
              <th>工具</th>
<!-- 列三：报修人，姓名与联系电话上下排列 -->
<!-- 联系电话可能为空，空值不渲染额外文本 -->
              <th>报修人</th>
<!-- 列四：损坏部位，来自居民报修时填写的描述字段 -->
<!-- 使用 muted 弱化颜色，避免与状态列争夺注意力 -->
              <th>损坏部位</th>
<!-- 列五：报修状态，徽章色调由 reportBadge 映射 -->
<!-- 这是三段状态机的主状态，决定当前行能做哪些操作 -->
              <th>状态</th>
<!-- 列六：维修进度，徽章色调由 progressBadge 映射 -->
<!-- 未进入维修时 progressStatus 为空，显示短横线而不是空徽章 -->
              <th>维修进度</th>
<!-- 列七：维修负责人，未安排维修时显示短横线 -->
<!-- 负责人可能在核验或进度弹窗中被更新，列表刷新后同步 -->
              <th>负责人</th>
<!-- 列八：创建时间，统一由 formatDateTime 格式化 -->
<!-- 与单号下方的次行时间一致，保留两处是为了按列浏览时方便 -->
              <th>创建时间</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
<!-- 加载态：渲染 5 行骨架屏 -->
<!-- 每个单元格用 colspan=9 横跨整行，避免表格列宽在加载时跳动 -->
<!-- 骨架条高度 16px，与真实文本行高接近 -->
          <tbody v-if="loading">
            <tr v-for="i in 5" :key="`sk-${i}`">
              <td colspan="9"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
<!-- 空态：接口无数据，或客户端进度筛选后当前页无匹配 -->
<!-- 用 Warning 图标 + 标题 + 说明组成，告知居民报修单会出现在这里 -->
<!-- 空态不区分「真的没有单」与「本页筛选为空」，通过表尾筛选条数可辅助判断 -->
          <tbody v-else-if="!visibleList.length">
            <tr>
              <td colspan="9">
                <div class="empty">
                  <Warning class="e-ic" />
                  <h4>暂无报修记录</h4>
                  <p>居民提交的报修单会显示在这里。</p>
                </div>
              </td>
            </tr>
          </tbody>
<!-- 数据行：key 使用报修单 id，保证翻页或刷新时行复用正确 -->
<!-- 每行包含定位信息、状态徽章、负责人与按状态机显隐的操作按钮 -->
<!-- 行内不直接编辑，所有写操作都通过弹窗完成，便于集中校验 -->
          <tbody v-else>
            <tr v-for="row in visibleList" :key="row.id">
              <td>
<!-- 单号 + 创建时间：单号使用等宽代码样式，时间使用次要文本 -->
<!-- reportNo 为空时仍渲染空文本，正常数据不会出现该情况 -->
                <span class="code">{{ row.reportNo }}</span>
                <div class="cell-sub">{{ formatDateTime(row.createTime) }}</div>
              </td>
<!-- 工具单元格：有图显示缩略图，无图显示占位 -->
<!-- resolveFileUrl 将对象存储 key 转为可访问直链 -->
<!-- 工具名缺失时显示短横线，工具编码为空时不额外渲染 -->
<!-- 缩略图固定尺寸、object-fit cover，避免不同比例图片撑破布局 -->
              <td>
                <div class="cell-tool">
                  <img v-if="row.toolImageUrl" :src="resolveFileUrl(row.toolImageUrl)" class="thumb" alt="" />
                  <span v-else class="thumb-ph">—</span>
                  <div>
                    <div class="cell-main">{{ row.toolName || '—' }}</div>
                    <div class="cell-sub">{{ row.toolCode || '' }}</div>
                  </div>
                </div>
              </td>
<!-- 报修人姓名与电话，缺失时用占位符 -->
<!-- 电话单独一行、次要颜色，方便管理员快速联系报修人 -->
              <td>
                <div class="cell-main">{{ row.reporterName || '—' }}</div>
                <div class="cell-sub">{{ row.reporterPhone || '' }}</div>
              </td>
<!-- 损坏部位：纯文本展示，长文本由单元格宽度自然换行 -->
              <td class="muted">{{ row.damagePart || '—' }}</td>
<!-- 报修状态徽章：优先使用后端返回的 statusName，缺失时用枚举映射兜底 -->
<!-- 圆点 + 文本由设计系统 .badge 统一样式，色调由 reportBadge 决定 -->
<!-- 数据行与详情抽屉使用同一套映射，保证两处颜色语义一致 -->
              <td>
                <span class="badge" :class="reportBadge(row.status)">
                  <span class="dot"></span>{{ row.statusName || enumLabel(REPORT_STATUS_LABELS, row.status) }}
                </span>
              </td>
<!-- 维修进度徽章：没有进度时显示短横线 -->
<!-- 显示短横线是为了避免把「未开始维修」误读为某个进度状态 -->
<!-- 徽章文本同样优先使用后端 progressStatusName，再用枚举映射兜底 -->
              <td>
                <span v-if="row.progressStatus" class="badge" :class="progressBadge(row.progressStatus)">
                  {{ row.progressStatusName || enumLabel(PROGRESS_STATUS_LABELS, row.progressStatus) }}
                </span>
                <span v-else class="muted">—</span>
              </td>
<!-- 当前维修负责人：核验或进度更新时写入，未安排时显示短横线 -->
              <td class="muted">{{ row.repairPerson || '—' }}</td>
<!-- 创建时间：与单号下方时间重复，便于按列扫描时查看 -->
<!-- 统一使用 formatDateTime，避免各页面时间格式不一致 -->
              <td>{{ formatDateTime(row.createTime) }}</td>
              <td>
<!-- 行操作区：详情始终可用，其余按钮由状态机判定显隐 -->
<!-- 按钮使用链接样式，避免表格中出现过多实心按钮造成视觉噪音 -->
<!-- 操作顺序固定为：详情 → 核验 → 进度 → 验收，符合流程推进顺序 -->
                <div class="row-actions">
<!-- 详情：打开抽屉，先展示行数据再异步拉取完整详情 -->
                  <button class="link-btn" @click="openDetail(row)">详情</button>
<!-- 核验：仅 SUBMITTED 状态显示，提交后状态离开 SUBMITTED，按钮自然消失 -->
                  <button v-if="canVerify(row)" class="link-btn" @click="openVerify(row)">核验</button>
<!-- 进度：VERIFIED / REPAIRING 状态显示，用于追加维修进度与费用 -->
                  <button v-if="canProgress(row)" class="link-btn" @click="openProgress(row)">进度</button>
<!-- 验收：REPAIRING 且进度 COMPLETED 时显示 -->
<!-- 使用 ok 绿色链接样式，突出这是维修完成后的主操作 -->
                  <button v-if="canAccept(row)" class="link-btn ok" @click="openAccept(row)">验收</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
<!-- 表尾：左侧统计与页码，右侧每页条数与分页器 -->
<!-- 统计使用服务端 total，与当前页实际渲染条数可能不同 -->
<!-- 分页器只发请求，不做本地切片，页码变化由 gotoPage 统一处理 -->
      <div class="table-foot">
<!-- 统计文本：服务端总数 + 当前页码 / 总页数 -->
<!-- 存在客户端进度筛选时，额外显示本页筛选后的条数，提示总数未变 -->
<!-- total 为 0 时仍显示第 1/1 页，避免出现 0 页的异常文案 -->
        <span class="tiny">
          共 {{ total }} 条<template v-if="progressFilter">（本页筛选 {{ visibleList.length }} 条）</template>
          · 第 {{ query.page }}/{{ totalPages }} 页
        </span>
<!-- 每页条数选择与分页器并排，使用 flex 容器右对齐 -->
        <div class="flex">
<!-- 每页条数下拉：变更后 handleSizeChange 会把页码重置到第一页 -->
<!-- 选项为 10 / 20 / 50，直接写入 query.size，随后重新请求 -->
<!-- 宽度与高度固定，保持与分页按钮同一水平线 -->
          <select class="select" style="width: 116px; height: 32px" v-model="query.size" @change="handleSizeChange">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
<!-- 分页器：上一页、页码序列（含省略号）、下一页 -->
<!-- 页码序列由 pageNumbers 计算，始终保留首末页与当前页附近页码 -->
          <div class="pager">
<!-- 上一页：当前页为 1 时禁用，避免无效请求 -->
            <button :disabled="query.page <= 1" @click="gotoPage(query.page - 1)">‹</button>
<!-- 页码序列循环：'…' 渲染为禁用按钮，数字渲染为可点击页码 -->
<!-- key 使用页码本身或省略号字符串，序列变化时 Vue 会正确复用节点 -->
            <template v-for="p in pageNumbers" :key="p">
              <button v-if="p === '…'" disabled>…</button>
<!-- 页码按钮：当前页高亮 active，点击跳转并重新请求 -->
<!-- active 样式由全局分页器样式提供，这里只负责绑定 class -->
              <button v-else :class="{ active: p === query.page }" @click="gotoPage(p)">{{ p }}</button>
            </template>
<!-- 下一页：当前页到达末页时禁用，避免越界请求 -->
            <button :disabled="query.page >= totalPages" @click="gotoPage(query.page + 1)">›</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 核验 -->
<!-- 核验弹窗遮罩：点击遮罩自身关闭，点击弹窗内部不会触发 -->
<!-- show 类控制可见性，v-if 控制是否渲染，关闭后表单状态仍保留到下次打开 -->
<!-- 遮罩层没有独立业务逻辑，只负责拦截背景点击 -->
    <div class="mask show" v-if="verifyVisible" @click.self="verifyVisible = false">
<!-- 核验弹窗主体：窄版对话框，适合字段较少的表单 -->
<!-- 表单只包含核验结果、维修安排与备注，不在此处修改报修单其他字段 -->
      <div class="dlg narrow">
        <div class="dlg-head">
<!-- 弹窗头部：标题 + 单号与工具名，便于确认操作对象 -->
<!-- 右上角关闭按钮与底部取消按钮行为一致，都只关闭弹窗 -->
          <div>
            <h3>报修核验</h3>
            <p class="d-sub">{{ verifyTarget ? `${verifyTarget.reportNo} · ${verifyTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="verifyVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 弹窗主体：所有字段纵向排列，错误提示由全局样式统一处理 -->
        <div class="dlg-body">
<!-- 核验结果字段：必填，默认 CONFIRMED -->
<!-- 选项来自 verifyResultOptions，与筛选栏共享同一份枚举映射 -->
<!-- 选择 FALSE（误报）后，下面的维修安排字段会通过 v-if 隐藏 -->
          <div class="field">
            <label><span class="req">*</span>核验结果</label>
            <select class="select" v-model="verifyForm.verifyResult">
              <option v-for="item in verifyResultOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
<!-- 非误报时才展示维修安排字段：误报不需要负责人和预计时长 -->
<!-- 这里的判断与后端核验语义一致，避免给驳回单登记维修信息 -->
<!-- 已填写的值会保留在表单状态中，但不会随误报请求提交 -->
          <template v-if="verifyForm.verifyResult !== 'FALSE'">
<!-- 维修负责人：选填，如「王师傅」，可在后续进度弹窗中覆盖 -->
<!-- 输入值提交前会 trim，仅空白字符串不会被发送 -->
            <div class="field">
              <label>维修负责人</label>
              <input class="input" v-model="verifyForm.repairPerson" placeholder="选填，如 王师傅" />
            </div>
<!-- 预计时长：自由文本，如「3 天」，仅用于展示与提醒，不参与时间计算 -->
<!-- 选填，空值不进入请求体 -->
            <div class="field">
              <label>预计时长</label>
              <input class="input" v-model="verifyForm.repairEstimate" placeholder="如 3 天" />
            </div>
          </template>
<!-- 核验备注：选填，多行文本，提交前 trim -->
<!-- 备注会展示在详情抽屉的损坏描述下方 -->
          <div class="field">
            <label>备注</label>
            <textarea class="textarea" v-model="verifyForm.remark" rows="2" placeholder="选填"></textarea>
          </div>
        </div>
<!-- 弹窗底部：取消 / 确认核验 -->
<!-- 保存期间确认按钮禁用并显示 loading，防止重复提交 -->
        <div class="dlg-foot">
<!-- 取消：仅关闭弹窗，不主动重置表单 -->
<!-- 下次打开时由 openVerify 重置，保证不会带着上次输入进入 -->
          <button class="btn btn-outline" @click="verifyVisible = false">取消</button>
<!-- 确认核验：调用 damageApi.verify，提交核验结果与可选的维修安排 -->
<!-- 成功后刷新列表与当前详情；失败时保持弹窗打开，用户可修正后重试 -->
          <button class="btn btn-primary" :class="{ loading: verifySaving }" :disabled="verifySaving" @click="submitVerify">
            确认核验
          </button>
        </div>
      </div>
    </div>

    <!-- 进度 -->
<!-- 维修进度弹窗遮罩：点击自身关闭，与核验弹窗行为一致 -->
<!-- 使用 v-if 控制渲染，关闭后组件销毁，避免隐藏层仍占用表单状态 -->
    <div class="mask show" v-if="progressVisible" @click.self="progressVisible = false">
<!-- 进度弹窗主体：窄版对话框，字段比核验多一组费用与说明 -->
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>更新维修进度</h3>
            <p class="d-sub">{{ progressTarget ? `${progressTarget.reportNo} · ${progressTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="progressVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 弹窗主体：进度状态、进度说明、负责人/预计时长、维修费用 -->
<!-- 每次提交代表一条新的维修记录，用于形成可追溯的维修轨迹 -->
        <div class="dlg-body">
<!-- 进度状态：必填，默认沿用行数据中的当前进度 -->
<!-- 选择 COMPLETED 后，列表行的验收按钮才会出现 -->
<!-- 选择 FAILED 时，详情时间线会提示需重新安排维修，且不能验收 -->
          <div class="field">
            <label><span class="req">*</span>进度状态</label>
            <select class="select" v-model="progressForm.progressStatus">
              <option v-for="item in progressStatusOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
<!-- 进度说明：必填，最多 200 字，提交前 trim -->
<!-- 空说明会被 submitProgress 拦截并给出 warning，不发送请求 -->
<!-- 这里是维修留痕的核心字段，详情抽屉会展示最近一次说明 -->
          <div class="field">
            <label><span class="req">*</span>进度说明</label>
            <textarea
              class="textarea"
              v-model="progressForm.progressNote"
              rows="3"
              maxlength="200"
              placeholder="请填写本次维修进度说明"
            ></textarea>
          </div>
<!-- 负责人与预计时长并排展示，均可选填 -->
<!-- 使用 form-grid 两列布局，窄屏下由全局样式降级为单列 -->
          <div class="form-grid">
<!-- 维修负责人：回填已有值，可在此更新 -->
<!-- 提交前 trim，空值不进入请求体 -->
            <div class="field">
              <label>维修负责人</label>
              <input class="input" v-model="progressForm.repairPerson" placeholder="选填" />
            </div>
<!-- 预计时长：回填核验阶段的值，可在此调整 -->
            <div class="field">
              <label>预计时长</label>
              <input class="input" v-model="progressForm.repairEstimate" placeholder="选填" />
            </div>
          </div>
<!-- 维修费用：原生 number 输入，min 0、步进 0.01 -->
<!-- 空串通过 readCost 转为 null，表示本次不更新费用；0 是合法金额 -->
<!-- 不使用 el-input-number，避免组件清空语义与原生输入不一致 -->
<!-- 费用会进入结算，验收弹窗打开时会回填该值 -->
          <div class="field">
            <label>维修费用（元）</label>
            <input
              class="input"
              type="number"
              min="0"
              step="0.01"
              :value="progressForm.repairCost ?? ''"
              placeholder="选填"
              @input="progressForm.repairCost = readCost($event)"
            />
          </div>
        </div>
<!-- 底部：取消 / 保存进度 -->
        <div class="dlg-foot">
<!-- 取消：关闭弹窗，不提交任何字段 -->
          <button class="btn btn-outline" @click="progressVisible = false">取消</button>
<!-- 保存进度：前端先校验进度说明非空，再调用 progress 接口 -->
<!-- 成功后关闭弹窗并刷新列表/详情；失败时停留以便修正 -->
          <button class="btn btn-primary" :class="{ loading: progressSaving }" :disabled="progressSaving" @click="submitProgress">
            保存进度
          </button>
        </div>
      </div>
    </div>

    <!-- 验收 -->
<!-- 验收弹窗遮罩：点击自身关闭，与其他弹窗一致 -->
<!-- 验收是不可逆的收口动作，因此弹窗内字段需要谨慎确认 -->
    <div class="mask show" v-if="acceptVisible" @click.self="acceptVisible = false">
<!-- 验收弹窗主体：窄版对话框，集中决定报修单终态与工具去向 -->
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>维修验收</h3>
            <p class="d-sub">{{ acceptTarget ? `${acceptTarget.reportNo} · ${acceptTarget.toolName || '—'}` : '—' }}</p>
          </div>
          <button class="dlg-x" @click="acceptVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 弹窗主体：验收结果、验收后工具状态、最终费用与备注 -->
        <div class="dlg-body">
<!-- 验收结果：必填，决定报修单如何收口 -->
<!-- QUALIFIED 表示维修合格；PARTIAL / UNQUALIFIED 表示未完全修好 -->
<!-- 结果变化会触发 watch，联动纠正工具状态，并重算工具状态下拉选项 -->
          <div class="field">
            <label><span class="req">*</span>验收结果</label>
            <select class="select" v-model="acceptForm.acceptResult">
              <option v-for="item in acceptResultOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
<!-- 验收后工具状态：选项由 toolStatusAfterOptions 动态过滤 -->
<!-- 排除 RENTED：验收动作不应直接产生租赁状态 -->
<!-- 部分合格 / 不合格时排除 AVAILABLE，工具不能直接回到可借状态 -->
<!-- watch 会兜底纠正非法组合，后端也会再次校验 -->
          <div class="field">
            <label><span class="req">*</span>验收后工具状态</label>
            <select class="select" v-model="acceptForm.toolStatusAfter">
              <option v-for="item in toolStatusAfterOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
<!-- 最终维修费用：回填进度阶段金额，可在此做结算调整 -->
<!-- 空值表示不更新费用；0 元表示免费维修，同样会提交 -->
          <div class="field">
            <label>维修费用（元）</label>
            <input
              class="input"
              type="number"
              min="0"
              step="0.01"
              :value="acceptForm.repairCost ?? ''"
              placeholder="选填"
              @input="acceptForm.repairCost = readCost($event)"
            />
          </div>
<!-- 验收备注：选填，多行文本，提交前 trim -->
<!-- 可用于记录验收时的补充说明或结算依据 -->
          <div class="field">
            <label>备注</label>
            <textarea class="textarea" v-model="acceptForm.remark" rows="2" placeholder="选填"></textarea>
          </div>
        </div>
<!-- 底部：取消 / 确认验收 -->
        <div class="dlg-foot">
<!-- 取消：关闭弹窗，不改变报修单状态 -->
          <button class="btn btn-outline" @click="acceptVisible = false">取消</button>
<!-- 确认验收：调用 accept 接口，提交验收结果、工具状态、费用与备注 -->
<!-- 成功后刷新列表与详情，报修单进入 COMPLETED 等终态，工具状态同步更新 -->
          <button class="btn btn-primary" :class="{ loading: acceptSaving }" :disabled="acceptSaving" @click="submitAccept">
            确认验收
          </button>
        </div>
      </div>
    </div>

    <!-- 详情抽屉 -->
<!-- 详情抽屉遮罩：点击遮罩自身关闭抽屉 -->
<!-- 抽屉宽度固定 600px，内容较多时在抽屉内部滚动 -->
<!-- 详情请求期间显示骨架屏，避免先展示不完整字段再跳动 -->
    <div class="drawer-mask show" v-if="detailVisible" @click.self="detailVisible = false">
<!-- 抽屉主体：复用弹窗头部样式，但不包含底部操作区 -->
<!-- 详情是只读视图，所有写操作仍回到列表行的对应弹窗 -->
      <div class="drawer" style="width: 600px">
<!-- 抽屉头部：标题 + 报修单号，关闭按钮位置与弹窗一致 -->
        <div class="dlg-head">
          <div>
            <h3>报修详情</h3>
            <p class="d-sub">{{ detail?.reportNo }}</p>
          </div>
          <button class="dlg-x" @click="detailVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 抽屉主体：加载态 / 详情 / 空态三选一 -->
        <div class="dlg-body">
<!-- 详情加载中：渲染 6 行骨架条，高度 18px，模拟键值表行高 -->
          <template v-if="detailLoading">
            <div v-for="i in 6" :key="i" class="skel" style="height: 18px; margin-bottom: 14px"></div>
          </template>
<!-- 详情已就绪：按徽章、时间线、描述、基础信息、材料、三段信息依次展示 -->
          <template v-else-if="detail">
<!-- 顶部徽章行：报修状态 + 维修进度（若有） -->
<!-- 两个徽章复用列表的 reportBadge / progressBadge 色调映射 -->
            <div class="badge-row flex">
              <span class="badge" :class="reportBadge(detail.status)">
                <span class="dot"></span>{{ detail.statusName || enumLabel(REPORT_STATUS_LABELS, detail.status) }}
              </span>
              <span v-if="detail.progressStatus" class="badge" :class="progressBadge(detail.progressStatus)">
                {{ detail.progressStatusName || enumLabel(PROGRESS_STATUS_LABELS, detail.progressStatus) }}
              </span>
            </div>

<!-- 三段式时间线：核验 → 维修 → 验收 -->
<!-- done 阶段显示完成时间；active 是第一个未完成阶段，用高亮圈提示当前进度 -->
<!-- 时间线数据完全由 timelineStages 推导，模板不做状态判断 -->
            <ul class="timeline">
<!-- 单个阶段节点：done / active 类控制节点颜色与高亮圈 -->
<!-- li 的伪元素负责节点圆点与连接线，样式来自全局时间线样式 -->
              <li v-for="s in timelineStages" :key="s.title" :class="{ done: s.done, active: s.active }">
<!-- 阶段名称：核验 / 维修 / 验收 -->
                <div class="tl-t">{{ s.title }}</div>
<!-- 阶段完成时间：未完成时不渲染该行 -->
                <div v-if="s.time" class="tl-time">{{ formatDateTime(s.time) }}</div>
<!-- 阶段说明：操作人、枚举结果或下一步提示 -->
                <div class="tl-d">{{ s.desc }}</div>
              </li>
            </ul>

<!-- 分区标题：损坏描述 -->
<!-- 统一使用 sec-label 样式，与下方三个信息分区形成层级 -->
            <div class="sec-label">损坏描述</div>
            <p class="dmg-desc">{{ detail.description || '—' }}</p>
<!-- 报修备注（若有）：仅在 remark 非空时渲染，避免出现空行 -->
            <p v-if="detail.remark" class="dmg-note">备注：{{ detail.remark }}</p>

<!-- 基础信息键值表：单号、工具、部位、报修人、时间、关联租赁 -->
<!-- 使用 dl/dt/dd 语义化结构，样式由全局 kv 类统一控制 -->
            <dl class="kv">
<!-- 报修单号：等宽样式，便于复制核对 -->
              <dt>报修单号</dt>
              <dd><span class="code">{{ detail.reportNo }}</span></dd>
<!-- 工具名称：缺失时显示短横线 -->
              <dt>工具</dt>
              <dd>{{ detail.toolName || '—' }}</dd>
<!-- 工具编码：用于在工具台账中唯一定位 -->
              <dt>工具编码</dt>
              <dd>{{ detail.toolCode || '—' }}</dd>
<!-- 损坏部位：居民报修时填写的部位描述 -->
              <dt>损坏部位</dt>
              <dd>{{ detail.damagePart || '—' }}</dd>
<!-- 报修人及电话：电话存在时用中点追加显示 -->
              <dt>报修人</dt>
              <dd>{{ detail.reporterName || '—' }}<template v-if="detail.reporterPhone"> · {{ detail.reporterPhone }}</template></dd>
<!-- 提交时间：统一格式化 -->
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(detail.createTime) }}</dd>
<!-- 关联租赁 id：未关联租赁时显示短横线 -->
              <dt>关联租赁</dt>
              <dd>{{ detail.rentalId ?? '—' }}</dd>
            </dl>

<!-- 证明材料：仅在存在图片时渲染整个区块 -->
<!-- 图片地址经 resolveFileUrl 转为可访问直链 -->
<!-- 缩略图点击后打开全屏预览，预览层独立于抽屉 -->
            <template v-if="detail.images?.length">
<!-- 分区标题：证明材料 -->
              <div class="sec-label">证明材料</div>
<!-- 缩略图列表：使用 flex 换行排列，尺寸由 CSS 固定 -->
              <div class="dmg-imgs">
                <img
                  v-for="(url, index) in detail.images"
                  :key="`${url}-${index}`"
                  :src="resolveFileUrl(url)"
                  alt=""
                  @click="openPreview(resolveFileUrl(url))"
                />
              </div>
            </template>

<!-- 核验信息分区：核验人、时间、结果与预计时长 -->
<!-- 这些字段在未核验时多为空值，统一用短横线兜底 -->
<!-- 分区标题前的 Check 图标用于快速识别阶段 -->
            <div class="rep-section">
<!-- 核验信息标题：图标 + 文本，图标颜色由 CSS 控制 -->
              <h4><Check class="ic" />核验信息</h4>
<!-- 核验信息键值表 -->
              <dl class="kv">
<!-- 核验人：执行核验的管理员 -->
                <dt>核验人</dt>
                <dd>{{ detail.verifier || '—' }}</dd>
<!-- 核验时间：时间线中核验阶段的完成时间 -->
                <dt>核验时间</dt>
                <dd>{{ formatDateTime(detail.verifyTime) }}</dd>
<!-- 核验结果：枚举映射为中文，未知值由 enumLabel 兜底 -->
                <dt>核验结果</dt>
                <dd>{{ enumLabel(VERIFY_RESULT_LABELS, detail.verifyResult) }}</dd>
<!-- 预计维修时长：核验或进度阶段录入的自由文本 -->
                <dt>预计时长</dt>
                <dd>{{ detail.repairEstimate || '—' }}</dd>
              </dl>
            </div>

<!-- 维修信息分区：负责人、开始时间、进度、费用与说明 -->
<!-- 该分区在未进入维修时也可能存在，字段为空则显示短横线 -->
<!-- 分区标题前的 Tools 图标用于快速识别阶段 -->
            <div class="rep-section">
<!-- 维修信息标题：图标 + 文本 -->
              <h4><Tools class="ic" />维修信息</h4>
<!-- 维修信息键值表 -->
              <dl class="kv">
<!-- 维修负责人：可能在核验或进度更新时写入 -->
                <dt>维修负责人</dt>
                <dd>{{ detail.repairPerson || '—' }}</dd>
<!-- 开始维修时间：首次进入维修阶段的时间 -->
                <dt>开始维修</dt>
                <dd>{{ formatDateTime(detail.repairStartTime) }}</dd>
<!-- 当前维修进度：最近一次进度更新的状态 -->
                <dt>维修进度</dt>
                <dd>{{ enumLabel(PROGRESS_STATUS_LABELS, detail.progressStatus) }}</dd>
<!-- 进度更新时间：时间线中维修阶段优先取此值 -->
                <dt>更新进度</dt>
                <dd>{{ formatDateTime(detail.progressUpdateTime) }}</dd>
<!-- 维修费用：formatMoney 格式化，null 时显示占位 -->
                <dt>维修费用</dt>
                <dd>{{ formatMoney(detail.repairCost) }}</dd>
<!-- 最近一次进度说明：维修留痕的核心文本 -->
                <dt>进度说明</dt>
                <dd>{{ detail.progressNote || '—' }}</dd>
              </dl>
            </div>

<!-- 验收信息分区：验收人、时间、结果与验收后工具状态 -->
<!-- 未验收时这些字段为空，使用短横线兜底 -->
<!-- 分区标题前的 CircleCheck 图标用于快速识别阶段 -->
            <div class="rep-section">
<!-- 验收信息标题：图标 + 文本 -->
              <h4><CircleCheck class="ic" />验收信息</h4>
<!-- 验收信息键值表 -->
              <dl class="kv">
<!-- 验收人：执行验收的管理员 -->
                <dt>验收人</dt>
                <dd>{{ detail.acceptor || '—' }}</dd>
<!-- 验收时间：时间线中验收阶段的完成时间 -->
                <dt>验收时间</dt>
                <dd>{{ formatDateTime(detail.acceptTime) }}</dd>
<!-- 验收结果：用徽章展示，未验收时显示短横线 -->
<!-- 徽章色调由 acceptBadge 映射 -->
                <dt>验收结果</dt>
                <dd>
                  <span v-if="detail.acceptResult" class="badge" :class="acceptBadge(detail.acceptResult)">
                    {{ detail.acceptResultName || enumLabel(ACCEPT_RESULT_LABELS, detail.acceptResult) }}
                  </span>
                  <span v-else class="muted">—</span>
                </dd>
<!-- 验收后工具状态：由验收动作写入，决定工具后续可借性 -->
<!-- 部分合格 / 不合格时不会显示为可用 -->
                <dt>工具状态</dt>
                <dd>{{ enumLabel(TOOL_STATUS_LABELS, detail.toolStatusAfter) }}</dd>
              </dl>
            </div>
          </template>
<!-- 详情兜底空态：理论上打开抽屉必有数据，用于请求异常或 id 丢失 -->
<!-- 用 Document 图标 + 文案提示，不提供重试按钮，关闭后重新打开即可 -->
          <div v-else class="empty">
            <Document class="e-ic" />
            <h4>暂无详情</h4>
          </div>
        </div>
      </div>
    </div>

    <!-- 图片预览 -->
<!-- 图片预览遮罩：点击遮罩或图片自身关闭 -->
<!-- previewUrl 为空时整个预览层不渲染 -->
<!-- 预览层层级高于抽屉与弹窗，避免被遮挡 -->
    <div class="mask show preview-mask" v-if="previewUrl" @click.self="previewUrl = ''">
<!-- 预览图：限制最大宽高，保持比例，cursor 提示可关闭 -->
<!-- 图片地址已在打开预览前经过 resolveFileUrl 处理 -->
      <img :src="previewUrl" class="preview-img" alt="" @click="previewUrl = ''" />
    </div>
  </div>
</template>

<style scoped>
/*
 * 详情徽章行与描述区之间的间距。
 * 徽章行单独占一行，避免与下方时间线贴得过近。
 */
.badge-row {
  margin-bottom: 16px;
}

/*
 * 当前阶段节点的高亮圈。
 * active 阶段由 timelineStages 推导，这里只负责视觉强调：
 * 用品牌色外环模拟「当前进度」的呼吸感。
 */
.timeline li.active::before {
  box-shadow: 0 0 0 4px var(--brand-ring);
}

/*
 * 损坏描述卡片。
 * 使用浅色背景 + 边框与正文区分，保留换行并允许长词断行，
 * 防止用户输入的连续字符撑破抽屉宽度。
 */
.dmg-desc {
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--ink-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 13px 15px;
  margin: 0 0 6px;
  word-break: break-word;
}

/*
 * 报修备注：字号更小、颜色更浅，作为描述卡的补充信息。
 */
.dmg-note {
  font-size: var(--t-xs);
  color: var(--ink-3);
  margin: 6px 0 0;
}

/*
 * 证明材料缩略图容器：横向排列并允许换行。
 */
.dmg-imgs {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

/*
 * 证明材料缩略图：固定 90×90，圆角裁切。
 * object-fit: cover 保证不同比例图片不变形；
 * cursor: zoom-in 提示图片可点击放大。
 */
.dmg-imgs img {
  width: 90px;
  height: 90px;
  border-radius: 10px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}

/*
 * 核验 / 维修 / 验收三个信息分区的外框。
 * 每个分区独立成卡片，便于在长抽屉中快速分段阅读。
 */
.rep-section {
  border: 1px solid var(--line);
  border-radius: 11px;
  padding: 14px 16px;
  margin-bottom: 12px;
}

/*
 * 分区标题：小字号加粗，与键值表形成层级。
 * 使用 flex 让图标与文字垂直居中。
 */
.rep-section h4 {
  font-size: var(--t-sm);
  font-weight: 700;
  margin: 0 0 11px;
  display: flex;
  align-items: center;
  gap: 8px;
}

/*
 * 分区标题图标：统一 15px，使用品牌辅助色，
 * 避免三个图标颜色各异造成视觉噪音。
 */
.rep-section h4 .ic {
  width: 15px;
  height: 15px;
  color: var(--brand-2);
}

/*
 * 图片预览遮罩：仅调整交叉轴对齐，
 * 其余遮罩样式复用全局 .mask.show。
 */
.preview-mask {
  align-items: center;
}

/*
 * 预览大图：限制最大宽高，保持原始比例；
 * 圆角与阴影使其从背景中浮起，cursor: zoom-out 提示点击关闭。
 */
.preview-img {
  max-width: 86vw;
  max-height: 84vh;
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  cursor: zoom-out;
}
</style>
