<script setup lang="ts">
/**
 * ============================================================================
 * ToolManageView —— 管理端「工具管理」单文件组件
 * ============================================================================
 *
 * 页面定位：
 * - 该视图只面向后台管理端，用于维护工具档案、审核工具、驱动工具状态机、
 *   查看单工具的操作日志与历史租赁记录。
 * - 页面整体分为四层：顶部标题与筛选栏、工具表格、若干弹窗/抽屉、图片预览层。
 *
 * 权限模型（重点）：
 * - 组件通过 useAuthStore 取得当前登录角色，并用 isSuperAdmin 计算属性判断
 *   角色是否为 SUPER_ADMIN。
 * - 管理端接口虽然由路由守卫兜底，但本页仍做了显式的 UI 权限控制：
 *   1. 非超级管理员不会在 onMounted 时调用 loadList，因此列表为空；
 *   2. 「新增工具」按钮、表格右侧的全部操作按钮都包在 v-if="isSuperAdmin" 内；
 *   3. 审核、状态变更、报废恢复等写操作也只能由超级管理员触发。
 * - 这样即使用户直接访问路由，也不会看到可操作的入口，避免误触发后端 403。
 *
 * 审核业务（auditStatus）：
 * - 后端契约中的 auditStatus 是 TINYINT：0 待审核、1 已通过、2 需修改。
 * - 页面展示时优先使用后端返回的 auditStatusName，缺失时回退到本地
 *   auditStatusLabel() 映射；徽章颜色由 auditStatusTag() 决定。
 * - 审核请求体使用字符串枚举 'APPROVED' / 'NEEDS_CHANGE'，与数据库数值编码
 *   解耦，提交时由后端完成枚举到数值的转换。
 * - 退回（NEEDS_CHANGE）必须填写审核意见，前端在 submitAudit 中拦截空备注；
 *   通过审核时备注可选。
 * - 审核成功后刷新列表，使表格中的审核状态、意见与后端保持一致。
 *
 * 工具状态机（ToolStatus）：
 * - AVAILABLE（可借用）：正常在库，可被用户下单；
 * - RENTED（已租出）：存在进行中的租赁单；
 * - MAINTENANCE（维修中）：报修/保养期间不可借出；
 * - SCRAPPED（已报废）：终态，普通状态流转不可达，只能走「恢复」专用接口。
 * - 允许的流转集中定义在 STATUS_TRANSITIONS：
 *   AVAILABLE -> RENTED / MAINTENANCE / SCRAPPED
 *   RENTED    -> AVAILABLE / MAINTENANCE / SCRAPPED
 *   MAINTENANCE -> AVAILABLE / SCRAPPED
 *   SCRAPPED  -> （无普通流转）
 * - 页面的「状态」弹窗只展示当前状态允许的目标状态；SCRAPPED 行改为展示
 *   「恢复」按钮，避免把报废态重新走普通状态接口。
 *
 * 报废恢复：
 * - 仅超级管理员可见；恢复必须填写原因，接口为 toolApi.restore。
 * - 恢复后后端会把工具置为 AVAILABLE，因此前端提示「恢复为可借用」。
 * - 恢复前建议确认没有活动租赁与未完成报修，弹窗中用 notice 文案提醒。
 *
 * 使用指南的两种数据形态：
 * - 新格式：JSON 字符串，形如 [{"text":"...","imageUrl":"..."}, ...]，
 *   由 parseGuide() 解析、serializeGuide() 序列化。
 * - 旧格式：历史数据可能是 HTML 富文本或纯文本，parseGuide() 在 JSON 解析
 *   失败时回退到 stripHtml()，把 HTML 转成保留换行的纯文本，并置
 *   legacyGuide=true，在编辑器里提示「保存后自动转换」。
 * - 保存时无论新旧格式，都会统一提交 JSON 字符串；空指南提交空串。
 *
 * 图片上传：
 * - 图片文件先通过 uploadApi.uploadFile(file) 上传到 /admin/upload，
 *   返回的 data.url 再写入表单的 imageUrls 或步骤的 imageUrl。
 * - imageUrls 是字符串数组，既支持多图，也兼容后端历史单图字段 imageUrl。
 * - 展示时统一走 resolveFileUrl()：绝对 URL 原样使用，相对 key 拼 /api/files/。
 *
 * 筛选、排序与分页：
 * - 关键词、类别、状态、存放区、排序方式、最少租赁次数、维修时间范围共同
 *   组成 query；维修范围使用独立 ref repairScope 表示 ''/'7'/'30'/'never'。
 * - loadList() 在发送前把 repairScope 翻译成 neverRepaired 或
 *   repairedWithinDays，并用 cleanParams() 去掉空串/空值。
 * - 分页使用 page/size；改变每页条数时回到第 1 页；页码组件省略中间页。
 *
 * 操作日志：
 * - openLogs() 调用 toolApi.logs(id) 拉取该工具的时间线日志。
 * - 日志动作由 TOOL_LOG_ACTION_LABELS 映射；fromStatus/toStatus 可能是状态枚举
 *   或审核枚举，因此 logStatusLabel() 依次尝试两张映射表。
 *
 * 历史租赁：
 * - openRentals() 调用 toolApi.rentals(id, { page, size }) 分页拉取该工具的
 *   租赁单，抽屉内每页 5 条，展示单号、租用人、租期、押金与状态。
 *
 * 错误处理约定：
 * - 所有请求的失败提示由 @/api/request 的响应拦截器统一弹出；
 * - 组件内使用 try/finally 只负责复位 loading/saving 等本地状态，
 *   不吞掉异常，也不重复弹错误消息。
 *
 * 该文件只包含一个视图，不导出任何可复用逻辑；所有状态都随组件生命周期创建。
 */
 /*
 * ----------------------------------------------------------------------------
 * 补充说明一：页面生命周期与数据流
 * ----------------------------------------------------------------------------
 * 1. 组件挂载 -> onMounted 判断 isSuperAdmin -> 超级管理员调用 loadList；
 * 2. loadList 组装 ToolQuery -> cleanParams -> toolApi.list -> 写入 list/total；
 * 3. 表格渲染 list，分页控件依据 total 与 query.size 计算；
 * 4. 点击行内操作 -> 打开对应弹窗/抽屉 -> 调用 toolApi 写接口或查询接口；
 * 5. 写操作成功后统一调用 loadList，保证列表与后端状态一致。
 *
 * 这种「单一数据源 + 操作后刷新」的模式避免了在前端手工维护多条记录，
 * 代价是每次写操作会多一次列表请求，但对管理端的数据量来说可以接受。
 *
 * ----------------------------------------------------------------------------
 * 补充说明二：接口与页面能力的对应关系
 * ----------------------------------------------------------------------------
 * - toolApi.list(query)           -> 列表查询、筛选、排序、分页；
 * - toolApi.create(payload)       -> 新增工具；
 * - toolApi.update(id, payload)   -> 编辑工具；
 * - toolApi.audit(id, dto)        -> 审核通过/退回；
 * - toolApi.updateStatus(id, st)  -> 状态机流转；
 * - toolApi.restore(id, dto)      -> 报废恢复；
 * - toolApi.rentals(id, page)     -> 历史租赁；
 * - toolApi.logs(id)              -> 操作日志；
 * - uploadApi.uploadFile(file)    -> 图片上传。
 * 页面不直接拼接后端路径，所有路径与请求方法都收敛在 api 层。
 *
 * ----------------------------------------------------------------------------
 * 补充说明三：审核状态与工具状态的组合
 * ----------------------------------------------------------------------------
 * 审核状态与工具状态是两个正交维度：
 * - 审核状态回答「这条档案是否允许上架」；
 * - 工具状态回答「这件实物当前是否可借」。
 * 因此可能出现「已通过 + 维修中」「需修改 + 可借用」等组合。
 * 表格把两者放在同一列展示，但分别使用不同的徽章映射，避免混淆。
 * 审核通过/退回不会直接改变工具状态，状态变化必须走状态机接口。
 *
 * ----------------------------------------------------------------------------
 * 补充说明四：空值与后端命名字段的回退策略
 * ----------------------------------------------------------------------------
 * 模板中大量使用 `row.xxxName || enumLabel(...)`：
 * - 后端已经返回中文名时直接使用，便于后端统一运营文案；
 * - 后端未返回时用前端枚举映射兜底，避免出现英文枚举值；
 * - 两者都缺失时 enumLabel 会原样返回枚举值或 '—'。
 * 这种策略让页面既能享受后端文案，又不会因为字段缺失而空白。
 *
 * ----------------------------------------------------------------------------
 * 补充说明五：交互与可用性约定
 * ----------------------------------------------------------------------------
 * - 所有异步按钮在请求期间设置 disabled + loading，防止重复提交；
 * - 危险操作（状态变更、恢复）先弹确认框或提示风险；
 * - 删除类操作只作用于本地表单，保存前可反悔；
 * - 弹窗点击遮罩关闭，但抽屉/弹窗内部点击不会误关；
 * - 列表查询失败时保留旧数据，只复位 loading，不出现空白闪烁。
 *
 * ----------------------------------------------------------------------------
 * 补充说明六：本页不负责的事情
 * ----------------------------------------------------------------------------
 * - 不负责租赁下单、归还、结算（由租赁管理页承担）；
 * - 不负责报修流程（由报修管理页承担）；
 * - 不负责用户与角色管理（由系统管理页承担）；
 * - 不直接处理 401/403，认证与权限错误由请求拦截器统一处理。
 * 明确边界可以避免页面职责膨胀，也减少重复实现。
 * ----------------------------------------------------------------------------
 * 补充说明七：可测试性与调试入口
 * ----------------------------------------------------------------------------
 * 页面没有内置调试开关，排查问题时可借助以下线索：
 * - 浏览器 Network 面板查看 /admin/tools 系列请求的参数与响应；
 * - loadList 发送前可用 Vue Devtools 检查 query 与 repairScope 的实际值；
 * - 审核/状态/恢复失败时优先看后端返回的错误码与 message；
 * - 日志抽屉中的 fromStatus/toStatus 可帮助还原状态变化历史。
 * 由于所有写操作后都会刷新列表，若列表未变化，优先确认请求是否成功。
 *
 * ----------------------------------------------------------------------------
 * 补充说明八：文案与枚举的维护约定
 * ----------------------------------------------------------------------------
 * - 工具状态、审核状态、租赁状态的中文文案集中在 @/utils/enums；
 * - 页面内只保留少量交互文案（如「工具已更新」「请填写恢复原因」）；
 * - 新增枚举值时，除了改类型，还要补齐标签映射与徽章色调；
 * - 后端返回的 xxxName 字段优先于本地映射，便于运营调整文案。
 * 统一文案来源可以减少前后端不一致导致的展示问题。
 * ----------------------------------------------------------------------------
 * 补充说明九：与后端契约保持同步的检查清单
 * ----------------------------------------------------------------------------
 * 修改本页时，若后端契约发生变化，按以下顺序检查：
 * 1. @/api/types 中的 ToolVO/ToolQuery/ToolSaveDTO 是否新增字段；
 * 2. toolApi 的方法签名是否需要调整路径或请求体；
 * 3. 表单 form 是否需要新增字段并在 resetForm/openEdit 中回填；
 * 4. 表格列与筛选栏是否需要展示/支持新字段；
 * 5. 枚举标签与徽章映射是否补齐。
 * 这样可以避免「后端已返回但页面不展示」或「页面提交了后端不认识的字段」。
 */
import { computed, onMounted, reactive, ref } from 'vue'
/**
 * Vue 组合式 API：
 * - computed：派生只读状态（权限、分页、状态选项等）；
 * - onMounted：页面挂载后按权限首次加载列表；
 * - reactive：表单与筛选条件等可变对象；
 * - ref：基础类型或需要整体替换的数组/对象。
 */
 /*
 * 除本文件使用的四个 API 外，组合式 API 还提供 watch/watchEffect 等能力，
 * 但本页的派生逻辑用 computed 已足够，未引入额外侦听器。
 * onMounted 只执行一次，后续数据刷新由用户操作显式触发。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
/**
 * Element Plus 的命令式反馈：
 * - ElMessage 用于成功/警告等轻提示；
 * - ElMessageBox 用于状态变更前的二次确认弹窗。
 */
 /*
 * ElMessageBox 的 confirm 返回 Promise：确认 resolve、取消 reject，
 * 因此 submitStatus 用 try/catch 包住，取消时静默返回而不是弹错误。
 * 成功/警告消息使用 ElMessage.success/warning，错误消息不在组件内重复弹。
 */
import {
  ArrowDown,
  ArrowUp,
  Box,
  Close,
  Delete,
  Document,
  InfoFilled,
  Plus,
  Refresh,
  Search,
  Upload,
} from '@element-plus/icons-vue'
/**
 * 业务接口层：
 * - toolApi 封装 /admin/tools 下的列表、新增、编辑、审核、状态、恢复、
 *   日志与历史租赁接口；
 * - uploadApi 封装 /admin/upload 的 multipart 文件上传。
 * 组件不直接使用 http 实例，便于统一维护路径与类型。
 */
 /*
 * toolApi 与 uploadApi 都是普通对象集合，方法内部返回 Promise，
 * 组件通过 async/await 使用；请求头、token、错误提示由 http 实例统一处理。
 * 上传接口使用 multipart/form-data，不能手动设置 Content-Type。
 */
import { toolApi } from '@/api/tools'
import { uploadApi } from '@/api/upload'
/**
 * API 类型：
 * - RentalVO：租赁单展示字段；
 * - ToolLogVO：操作日志时间线条目；
 * - ToolQuery：管理端筛选与分页参数；
 * - ToolSaveDTO：新增/编辑共用保存体；
 * - ToolStatus：工具状态枚举；
 * - ToolVO：工具列表行数据。
 */
 /*
 * 这些类型来自后端契约生成文件，字段可空性与数据库保持一致；
 * 组件里对 null 的兜底（如 `?? ''`）是为了满足表单输入框的字符串要求。
 */
import type { RentalVO, ToolLogVO, ToolQuery, ToolSaveDTO, ToolStatus, ToolVO } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { badgeClass } from '@/utils/badge'
import { cleanParams } from '@/utils/params'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/utils/format'
/**
 * 展示层工具：
 * - 枚举中文标签与审核徽章映射，避免在模板里手写字符串；
 * - auditStatusLabel/auditStatusTag 处理审核状态数值编码；
 * - enumLabel/optionList 负责把标签表转成下拉选项；
 * - rentalStatusTag 决定历史租赁状态徽章颜色。
 */
 /*
 * 枚举映射是「单一事实来源」：新增状态或类别时只需改 utils/enums，
 * 本页的下拉选项、徽章文案会同步更新，不需要逐处修改模板。
 */
import {
  AUDIT_STATUS_NAME_LABELS,
  DAMAGE_LEVEL_LABELS,
  RENTAL_STATUS_LABELS,
  TOOL_CATEGORY_LABELS,
  TOOL_LOG_ACTION_LABELS,
  TOOL_STATUS_LABELS,
  auditStatusLabel,
  auditStatusTag,
  enumLabel,
  optionList,
  rentalStatusTag,
} from '@/utils/enums'

/**
 * 当前登录态与权限判定。
 * auth 是 Pinia store，isSuperAdmin 由 role === 'SUPER_ADMIN' 派生。
 * 该计算属性同时驱动：
 * - onMounted 是否加载列表；
 * - 新增按钮与行内操作按钮是否渲染。
 * 使用 computed 而非直接读 store，保证 role 变化时模板自动更新。
 */
 /*
 * 权限判断的细节：
 * - auth.isSuperAdmin 本身是 computed，这里再包一层 computed 便于模板解构；
 * - 不在组件内缓存角色字符串，避免登录切换后状态过期；
 * - 权限只控制 UI，真正的鉴权仍由后端接口完成，前端不能作为安全边界。
 * 非超级管理员访问本页时：
 * - 不加载列表，total 保持 0；
 * - 不渲染新增按钮和行内操作；
 * - 仍能看到标题、筛选栏和空表格骨架，页面不会报错。
 */
const auth = useAuthStore()
const isSuperAdmin = computed(() => auth.isSuperAdmin)

/**
 * 列表加载状态与数据：
 * - loading 控制表格骨架屏/加载态；
 * - list 保存当前页工具记录；
 * - total 保存服务端返回的总记录数，用于分页计算。
 * 三者都由 loadList() 维护，查询条件变化时重新赋值。
 */
 /*
 * 列表状态的初始值：
 * - loading=false，首帧不会闪骨架屏，onMounted 触发后才进入加载态；
 * - list 初始为空数组，模板可安全调用 .length；
 * - total 初始为 0，分页计算不会出现 NaN。
 * 刷新列表时只替换 list/total，不整体重建组件，保留筛选栏焦点与滚动位置。
 */
const loading = ref(false)
const list = ref<ToolVO[]>([])
const total = ref(0)

/**
 * 计算工具缩略图地址。
 * 入参 row：工具列表行。
 * 取值优先级：imageUrl（兼容历史单图）> imageUrls[0]（新多图数组）。
 * 返回值：空串表示无图；否则经 resolveFileUrl 处理成可直接用于 img src 的地址。
 * 该函数在模板中被多次调用（v-if 与 :src），纯函数、无副作用。
 */
 /*
 * rowImage 的边界情况：
 * - imageUrl 与 imageUrls 都为空 => 返回空串，模板显示占位符；
 * - imageUrl 是相对 key（如 uploads/abc.jpg）=> resolveFileUrl 拼 /api/files/；
 * - imageUrl 已是 http(s) 或 / 开头 => 原样返回，兼容对象存储直链；
 * - imageUrls[0] 为空串时也按无图处理。
 * 该函数不修改 row，可在模板中重复调用而不产生副作用。
 */
function rowImage(row: ToolVO): string {
  const raw = row.imageUrl || row.imageUrls?.[0] || ''
  return raw ? resolveFileUrl(raw) : ''
}

/**
 * ============================================================================
 * 审核模块
 * ============================================================================
 * 审核是工具上架前的质量门禁：
 * - 新增工具默认处于待审核（auditStatus=0）；
 * - 管理员在表格中点击「通过」或「退回」打开审核弹窗；
 * - 通过 => auditStatus=1，工具可正常流转；
 * - 退回 => auditStatus=2，必须填写审核意见供录入人修改。
 * 本模块只维护弹窗状态、表单和提交逻辑，列表数据由 loadList 统一刷新。
 */
 /*
 * 审核操作的前置条件（由后端最终校验，前端按钮仅做提示）：
 * - 工具必须存在且未被删除；
 * - 当前审核状态与目标结论不冲突（例如已通过时仍可退回，但按钮会按状态隐藏）；
 * - 退回必须提供意见，便于录入人定位问题。
 * 审核结果会写入操作日志，动作类型为 AUDIT，可在日志抽屉中查看。
 */
// —— 审核 ——
/**
 * 审核弹窗的本地状态：
 * - auditVisible 控制弹窗显隐；
 * - auditSaving 防止重复提交并在按钮上显示 loading；
 * - auditTarget 保存被审核的工具行，提交时取其 id；
 * - auditForm 与弹窗表单双向绑定，打开时由 openAudit 预填。
 */
 /*
 * 弹窗状态全部使用 ref，原因是它们需要整体替换或作为布尔开关；
 * auditTarget 保存的是列表行的引用，列表刷新后旧引用不会自动更新，
 * 因此提交成功后立即关闭弹窗，避免继续操作过期数据。
 */
const auditVisible = ref(false)
const auditSaving = ref(false)
const auditTarget = ref<ToolVO | null>(null)
/**
 * 审核表单：
 * - auditStatus 只允许两个字符串枚举，和接口 ToolAuditDTO 保持一致；
 * - remark 为审核意见，退回时必填，通过时选填。
 * 使用 reactive 便于模板 v-model 直接绑定字段。
 */
 /*
 * auditForm 使用 reactive 而不是 ref：
 * - 模板可以直接 v-model="auditForm.remark"；
 * - 打开弹窗时逐字段赋值即可，不需要替换整个对象。
 * auditStatus 的初始值 APPROVED 只是占位，openAudit 会立即覆盖。
 * 审核表单不包含工具 id 字段，id 从 auditTarget 读取；
 * 这样即使表单被复制或误改，也不会把审核提交到错误的工具上。
 */
const auditForm = reactive<{ auditStatus: 'APPROVED' | 'NEEDS_CHANGE'; remark: string }>({
  auditStatus: 'APPROVED',
  remark: '',
})

/**
 * 打开审核弹窗。
 * 入参 row：被审核的工具；auditStatus：本次要提交的结论。
 * 副作用：写入 auditTarget、预填审核意见（沿用上次的 auditRemark）、
 * 显示弹窗。这里不发起请求，真正的提交在 submitAudit。
 * 通过/退回按钮共用该函数，只是传入的 auditStatus 不同。
 */
 /*
 * openAudit 不做权限判断，因为调用它的按钮已经受 isSuperAdmin 控制；
 * 若未来从其他入口调用，应在调用方或后端补权限校验。
 * 预填 auditRemark 而不是清空，是为了让管理员在已有意见基础上修改，
 * 但也意味着上一次的意见可能被带到新一次审核，提交前需自行确认。
 */
function openAudit(row: ToolVO, auditStatus: 'APPROVED' | 'NEEDS_CHANGE') {
  auditTarget.value = row
  auditForm.auditStatus = auditStatus
  auditForm.remark = row.auditRemark ?? ''
  auditVisible.value = true
}

/**
 * 提交审核结论。
 * 流程：
 * 1. 校验 auditTarget 存在；
 * 2. 若结论为 NEEDS_CHANGE，则 remark.trim() 不能为空，否则警告并中止；
 * 3. 调用 toolApi.audit(target.id, { auditStatus, remark })，
 *    备注为空时传 undefined，避免提交无意义的空串；
 * 4. 成功提示后关闭弹窗并刷新列表；
 * 5. finally 复位 auditSaving，保证失败时按钮也能恢复可点。
 * 请求异常由拦截器提示，这里不再额外 catch。
 */
 /*
 * 审核提交的边界与异常：
 * - target 为空（弹窗被异常关闭）时直接返回，不弹错误；
 * - remark 使用 trim 后的值，避免只输入空格绕过必填校验；
 * - 通过审核时若 remark 为空，传 undefined 而不是空串，保持请求体干净；
 * - 成功后先关闭弹窗再刷新列表，避免刷新期间弹窗仍显示旧状态；
 * - 刷新失败不会重新打开弹窗，用户可手动刷新页面。
 * 请求失败时 auditSaving 在 finally 中复位，按钮恢复可点，用户可重试。
 * 注意：这里没有 catch，异常会继续向上抛给 Vue 的事件处理器，
 * 但请求拦截器已经展示过错误信息，避免重复提示。
 * 审核成功后的刷新是必要的：
 * - 列表中的 auditStatus/auditStatusName/auditRemark 都会变化；
 * - 若只改本地对象，可能与后端实际状态不一致；
 * - 刷新还能同步后端可能更新的 updatedTime 等审计字段。
 */
async function submitAudit() {
  const target = auditTarget.value
  if (!target) return
  if (auditForm.auditStatus === 'NEEDS_CHANGE' && !auditForm.remark.trim()) {
    ElMessage.warning('退回时审核意见不能为空')
    return
  }
  auditSaving.value = true
  try {
    await toolApi.audit(target.id, {
      auditStatus: auditForm.auditStatus,
      remark: auditForm.remark.trim() || undefined,
    })
    ElMessage.success(auditForm.auditStatus === 'APPROVED' ? '工具已通过审核' : '已退回修改')
    auditVisible.value = false
    await loadList()
  } finally {
    auditSaving.value = false
  }
}
/**
 * 列表查询条件。
 * 字段含义：
 * - page/size：分页页码（从 1 开始）与每页条数；
 * - keyword：名称/编码/型号模糊搜索；
 * - category/status：类别与状态筛选，空串表示全部；
 * - storageArea：存放区域模糊筛选；
 * - orderBy：排序策略，默认按创建时间；
 * - minRentalCount：最少租赁次数，undefined 表示不限。
 * 维修时间范围不放在这里，而由独立的 repairScope 管理，
 * 因为它的取值 'never' 与数值天数语义不同，发送前需要转换。
 */
 /*
 * query 的类型是 ToolQuery 与分页字段的交叉类型：
 * - ToolQuery 来自后端契约，保证字段名与后端一致；
 * - page/size 由前端补充，因为列表接口统一使用分页参数；
 * - category/status 允许空串，发送前由 cleanParams 去掉。
 * 修改 query 的字段不会自动触发请求，必须点击查询或调用 handleSearch，
 * 这样可以避免用户连续切换下拉时产生大量并发请求。
 * minRentalCount 使用 undefined 而不是 ''，因为后端按数字比较；
 * 模板中的空 option 绑定空串，cleanParams 会把它过滤掉。
 * 分页字段 page/size 与筛选字段放在同一个 reactive 对象中，
 * 是为了让 loadList 可以一次性展开成请求参数；
 * 如果未来需要「保留筛选但重置分页」的快捷操作，只需改 page 字段。
 */
const query = reactive<ToolQuery & { page: number; size: number }>({
  page: 1,
  size: 10,
  keyword: '',
  category: '',
  status: '',
  storageArea: '',
  orderBy: 'default',
  minRentalCount: undefined,
})
/**
 * 维修时间筛选。
 * 取值：
 * - ''      不限制；
 * - '7'     最近 7 天维修过；
 * - '30'    最近 30 天维修过；
 * - 'never' 从未维修。
 * 该值在 loadList 中翻译成 repairedWithinDays 或 neverRepaired，
 * 两者互斥，不能同时发送。
 */
 /*
 * 选择「从未维修」时不能同时传 repairedWithinDays，
 * 选择天数时也不能传 neverRepaired，否则后端条件会互相矛盾。
 * loadList 通过 delete 显式保证两者互斥，而不是依赖后端忽略。
 * repairScope 不进入 query 对象，避免它被意外序列化到请求参数中。
 */
/** 维修时间筛选：'' 不限 / '7' 近 7 天 / '30' 近 30 天 / 'never' 从未维修 */
const repairScope = ref<'' | '7' | '30' | 'never'>('')

/**
 * 下拉选项的派生数据。
 * optionList 把「枚举值 -> 中文标签」的映射转成 [{ value, label }]，
 * 模板用 v-for 渲染，避免在模板里重复写 option 标签。
 * 三张映射表均来自 @/utils/enums，保证与后端枚举同步。
 */
 /*
 * categoryOptions/statusOptions/damageLevelOptions 都是普通数组，
 * 不会随请求变化，因此在 setup 阶段计算一次即可。
 * 若未来枚举需要从后端动态加载，可改为 ref 并在 onMounted 中填充。
 */
const categoryOptions = optionList(TOOL_CATEGORY_LABELS)
const damageLevelOptions = optionList(DAMAGE_LEVEL_LABELS)
const statusOptions = optionList(TOOL_STATUS_LABELS)

/**
 * 排序方式选项。
 * default 走后端默认排序（创建时间倒序）；
 * rentalCount 按累计租赁次数倒序，便于发现热门工具；
 * lastRepairTime 按最近维修时间排序，便于排查维修频繁的工具。
 * value 直接作为 query.orderBy 发送给后端。
 */
 /*
 * orderBy 的默认值 'default' 必须与后端约定一致；
 * 若后端新增排序策略，只需在此数组追加选项，无需改模板。
 * 排序变化不会立即请求，仍需点击查询按钮生效。
 */
const orderByOptions = [
  { value: 'default', label: '默认（创建时间）' },
  { value: 'rentalCount', label: '租赁次数最多' },
  { value: 'lastRepairTime', label: '最近维修优先' },
]
/**
 * 最少租赁次数选项。
 * 只提供 1/5/10 三档，value 为数字，与 ToolQuery.minRentalCount 对应；
 * 选择空串时由 cleanParams 过滤，表示不限。
 */
 /*
 * 租赁次数阈值使用数字 value，模板绑定到 query.minRentalCount 后
 * 由 v-model 保持数字类型；空 option 的 value 为空串，会被清洗掉。
 * 这些阈值是运营经验值，不代表后端限制。
 */
const rentalCountOptions = [
  { value: 1, label: '≥ 1 次' },
  { value: 5, label: '≥ 5 次' },
  { value: 10, label: '≥ 10 次' },
]
/**
 * 维修时间范围选项。
 * value 与 repairScope 的联合类型一致，模板选中后直接写入该 ref；
 * loadList 负责把它翻译成后端参数，选项本身不带业务逻辑。
 */
 /*
 * repairScopeOptions 的 value 与 repairScope 联合类型严格对应，
 * 新增范围时需要同时改类型、选项和 loadList 的翻译逻辑。
 * 「从未维修」与天数范围互斥，因此共用一个下拉而不是两个控件。
 */
const repairScopeOptions = [
  { value: '7', label: '近 7 天维修' },
  { value: '30', label: '近 30 天维修' },
  { value: 'never', label: '从未维修' },
]

/**
 * 加载工具列表。
 * 步骤：
 * 1. 置 loading=true，表格进入骨架屏；
 * 2. 复制 query，避免修改用户正在编辑的筛选对象；
 * 3. 根据 repairScope 设置互斥参数：
 *    - never => neverRepaired=true，删除 repairedWithinDays；
 *    - '7'/'30' => repairedWithinDays=Number(...)，删除 neverRepaired；
 *    - 空 => 两个参数都删除；
 * 4. 用 cleanParams 去掉空串/空值后调用 toolApi.list；
 * 5. 把 records/total 写入 list/total；
 * 6. finally 复位 loading。
 * 异常由请求拦截器统一提示，调用方无需重复处理。
 */
 /*
 * loadList 的并发与竞态：
 * - 快速连续点击查询可能产生多个请求，后返回的响应会覆盖先返回的；
 * - 当前实现没有请求序号或取消机制，属于可接受的简化；
 * - 若未来筛选频繁，可引入 AbortController 或请求序号丢弃过期响应。
 * 参数复制使用浅拷贝：query 的字段都是原始值，浅拷贝足够；
 * params 上的 delete 只影响副本，不会修改用户正在编辑的 query。
 * cleanParams 会过滤 ''、null、undefined，但保留 0 和 false，
 * 因此 minRentalCount=0 这类合法值不会被误删。
 * 列表接口返回 PageResult<ToolVO>，records 可能为空数组，total 为总数。
 * 若后端返回的 records 为 null（契约异常），模板会在 list.length 处报错；
 * 当前信任后端返回数组，未做额外兜底，属于契约优先的简化。
 */
async function loadList() {
  loading.value = true
  try {
    const params: ToolQuery = { ...query }
    if (repairScope.value === 'never') {
      params.neverRepaired = true
      delete params.repairedWithinDays
    } else if (repairScope.value) {
      params.repairedWithinDays = Number(repairScope.value)
      delete params.neverRepaired
    } else {
      delete params.neverRepaired
      delete params.repairedWithinDays
    }
    const data = await toolApi.list(cleanParams(params))
    list.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/**
 * 点击「查询」或输入框回车时触发。
 * 关键行为：先把页码重置为 1，再请求列表。
 * 否则用户在第 5 页修改筛选条件后，可能落到一个没有数据的空页。
 */
 /*
 * handleSearch 同时服务于按钮点击和输入框回车；
 * 它只负责重置页码与触发加载，不修改其他筛选字段。
 * 如果用户没有改条件而直接点查询，也会重新请求，属于可接受的幂等操作。
 */
function handleSearch() {
  query.page = 1
  loadList()
}

/**
 * 重置全部筛选条件。
 * 将关键词、类别、状态、存放区、排序、最少租赁次数、维修范围恢复默认，
 * 页码回到 1，然后立即重新查询。
 * 注意：每页条数 size 不重置，保留用户当前的浏览偏好。
 */
 /*
 * handleReset 的字段清单必须与 query 的筛选字段保持同步：
 * 新增筛选项时若忘记在这里重置，会导致「重置」后条件仍然生效。
 * size 故意不重置，保留用户选择的每页条数。
 * 重置后立即 loadList，用户不需要再点一次查询。
 */
function handleReset() {
  query.keyword = ''
  query.category = ''
  query.status = ''
  query.storageArea = ''
  query.orderBy = 'default'
  query.minRentalCount = undefined
  repairScope.value = ''
  query.page = 1
  loadList()
}

/**
 * 每页条数变化。
 * 选择新的 size 后，原页码可能越界，因此统一回到第 1 页再加载。
 */
 /*
 * 每页条数由模板中的 select 绑定到 query.size；
 * change 事件触发 handleSizeChange，先回到第 1 页再请求。
 * 如果只改 size 不重置 page，可能出现 page 超出新总页数而返回空列表。
 */
function handleSizeChange() {
  query.page = 1
  loadList()
}

/**
 * ============================================================================
 * 前端分页
 * ============================================================================
 * 后端返回 total，前端据此计算总页数与页码按钮。
 * 工具列表使用完整页码（带省略号），历史租赁抽屉使用简单的上一页/下一页。
 */
// —— 分页 ——
/**
 * 总页数。
 * 用 Math.max(1, ...) 保证空列表时仍显示「第 1/1 页」，
 * 避免出现 0 页导致页码控件异常。
 */
 /*
 * 总页数的计算依赖 total 与 query.size，两者任一变化都会自动重算。
 * 后端返回的 total 是满足筛选条件的总记录数，不是当前页长度。
 */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))
/**
 * 生成页码序列，支持省略号。
 * 规则：
 * - 始终展示第 1 页与最后一页；
 * - 展示当前页前后各 span=2 页；
 * - 中间不连续的区间只插入一个 '…'。
 * 返回数组元素可能是数字或 '…'，模板据此决定渲染可点击页码还是禁用省略号。
 */
 /*
 * 省略号算法的细节：
 * - span=2 表示当前页左右各保留两页；
 * - 当总页数较少（<= 2*span+3）时不会出现省略号；
 * - 省略号只插入一次，连续不满足条件的页码会被合并；
 * - 返回的 '…' 在模板中渲染为禁用按钮，不可点击。
 * 该算法只影响展示，不改变实际分页请求。
 * 页码序列示例（总 10 页、当前第 5 页、span=2）：
 * 1 … 3 4 5 6 7 … 10
 * 总 4 页时：1 2 3 4（不出现省略号）。
 * 当前页靠近首尾时，省略号只出现在另一侧。
 */
const pageNumbers = computed<(number | '…')[]>(() => {
  const pages: (number | '…')[] = []
  const all = totalPages.value
  const cur = query.page
  const span = 2
  for (let i = 1; i <= all; i++) {
    if (i === 1 || i === all || (i >= cur - span && i <= cur + span)) {
      pages.push(i)
    } else if (pages[pages.length - 1] !== '…') {
      pages.push('…')
    }
  }
  return pages
})

/**
 * 跳转到指定页。
 * 边界校验：小于 1、大于总页数、或与当前页相同都直接返回，
 * 避免重复请求和无意义的状态更新。
 */
 /*
 * gotoPage 由页码按钮和上一页/下一页按钮共同调用；
 * 所有边界判断集中在这里，模板中的 disabled 只是视觉提示。
 * 页码变化后调用 loadList，loading 会驱动表格骨架屏。
 */
function gotoPage(p: number) {
  if (p < 1 || p > totalPages.value || p === query.page) return
  query.page = p
  loadList()
}

/**
 * ============================================================================
 * 状态与成色徽章
 * ============================================================================
 * 后端只返回枚举值，徽章颜色由前端映射到设计系统的修饰类：
 * ok=绿色、brand=品牌色、warn=橙色、bad=红色、info=蓝色、neutral=灰色。
 * 映射集中在此处，模板只负责绑定 class。
 */
// —— 状态 / 成色徽章映射 ——
/**
 * 工具状态 -> 徽章色调。
 * AVAILABLE 正常可用用绿色；RENTED 进行中用品牌色；
 * MAINTENANCE 需要关注用橙色；SCRAPPED 终态用红色；
 * 未知值回退 neutral，保证脏数据也能渲染。
 */
 /*
 * 徽章映射与 utils/enums 中的 toolStatusTag 有部分重叠，
 * 但本页需要直接返回设计系统的修饰类名（ok/brand/warn/bad），
 * 因此保留独立的映射函数，避免在模板中再包一层 badgeClass。
 * 若设计系统新增色调，只需修改这些 switch 分支。
 */
function toolStatusBadge(status?: string): string {
  switch (status) {
    case 'AVAILABLE':
      return 'ok'
    case 'RENTED':
      return 'brand'
    case 'MAINTENANCE':
      return 'warn'
    case 'SCRAPPED':
      return 'bad'
    default:
      return 'neutral'
  }
}

/**
 * 损坏程度 -> 徽章色调。
 * NEW 全新用绿色；LIGHT 轻微用蓝色；MEDIUM 中度用橙色；
 * SEVERE 严重用红色；未知值回退 neutral。
 * 该映射只影响展示，不参与审核或状态流转判断。
 */
 /*
 * 损坏程度与审核状态无关：损坏程度描述实物成色，
 * 审核状态描述档案是否通过，二者可以独立变化。
 * 例如「严重损坏 + 已通过」表示档案合规但实物需要维修，
 * 管理员应再走状态机把工具置为 MAINTENANCE。
 */
function damageLevelBadge(level?: string): string {
  switch (level) {
    case 'NEW':
      return 'ok'
    case 'LIGHT':
      return 'info'
    case 'MEDIUM':
      return 'warn'
    case 'SEVERE':
      return 'bad'
    default:
      return 'neutral'
  }
}

/**
 * ============================================================================
 * 新增 / 编辑弹窗
 * ============================================================================
 * 新增与编辑共用同一套表单和弹窗，通过 editingId 区分模式：
 * - editingId 为 null：新增，提交时调用 toolApi.create；
 * - editingId 有值：编辑，提交时调用 toolApi.update，且编码只读。
 * 表单包含基础档案、使用指南分步编辑器、工具多图上传三部分。
 * 打开编辑弹窗时会把 ToolVO 映射成 ToolSaveDTO，并把指南反序列化为步骤。
 */
 /*
 * 弹窗模式切换的关键：
 * - openCreate 先 resetForm，确保 editingId=null；
 * - openEdit 先回填再打开，确保 editingId 有值；
 * - 关闭弹窗不会自动重置表单，下次打开会由上述函数覆盖。
 * 表单校验错误在打开时清空，避免显示上一次的红框。
 * 编辑时工具编码只读，因为编码由后端按类别生成，前端不应修改。
 * 表单布局使用 CSS Grid 两列，full 类让长字段跨两列；
 * 在窄屏下全局样式会将网格降为单列，本组件不额外处理响应式断点。
 */
// —— 新增 / 编辑弹窗 ——
/**
 * 弹窗本地状态：
 * - dialogVisible 控制显隐；
 * - saving 防止重复提交；
 * - editingId 区分新增/编辑，并作为更新接口的路径参数；
 * - editingCode 仅用于编辑态展示后端生成的工具编码；
 * - uploading 表示工具主图是否正在上传。
 */
 /*
 * dialogVisible 与 statusDialogVisible、auditVisible、restoreVisible
 * 是相互独立的布尔状态，同一时间只应有一个弹窗打开（由 UI 操作保证）。
 * 如果未来出现嵌套弹窗，需要额外处理遮罩层级与 Esc 关闭顺序。
 */
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const editingCode = ref('')
const uploading = ref(false)

/**
 * 使用指南的单个步骤。
 * text：步骤说明文字，最多 500 字；
 * imageUrl：该步骤的配图地址（上传接口返回值），可为空。
 * 新格式把步骤数组序列化成 JSON 存入 usageGuide 字段。
 */
 /*
 * GuideStep 只描述前端编辑结构，不是后端契约类型；
 * 序列化时只提交 text/imageUrl 两个字段，多余字段会被丢弃。
 * imageUrl 保存的是上传接口返回的相对 key 或绝对 URL。
 */
type GuideStep = { text: string; imageUrl: string }
const guideSteps = ref<GuideStep[]>([{ text: '', imageUrl: '' }])
const legacyGuide = ref(false)
const stepUploading = ref<number | null>(null)

/**
 * 创建一个空步骤。
 * 返回全新的对象，避免多个步骤共享同一引用。
 * 新增步骤、重置表单、解析空指南时都会调用。
 */
 /*
 * 空步骤的 text/imageUrl 都是空串，序列化时会被过滤；
 * 因此用户新增步骤后不填写直接保存，不会产生空数组项。
 */
function emptyStep(): GuideStep {
  return { text: '', imageUrl: '' }
}

/**
 * 把旧版富文本说明转成纯文本，并尽量保留换行。
 * 处理顺序：
 * 1. <br> 替换为换行；
 * 2. </p>/</li>/</div>/</h1..6> 结束标签替换为换行，保留段落边界；
 * 3. 去掉其余所有标签；
 * 4. 还原 &nbsp; &lt; &gt; &amp; 等常见实体；
 * 5. 合并连续空行并 trim。
 * 该函数只用于旧数据兼容，新数据不会再写入 HTML。
 */
 /*
 * stripHtml 是一个宽松的转换器，不追求完整的 HTML 解析：
 * - 正则无法处理所有畸形 HTML，但足以覆盖历史富文本的常见标签；
 * - 先处理 <br> 和块级结束标签，再删除其余标签，避免段落粘连；
 * - 实体只还原最常见的几种，其余保持原样；
 * - 连续空行合并为一个换行，避免旧数据产生大量空白。
 * 该函数不执行 HTML，也不保留样式，只保留可读文字。
 * stripHtml 不处理 <script>/<style> 内容，因为历史数据来自受控后台输入；
 /*
 * 若未来接入不可信来源，应改用更严格的清洗库或后端清洗。
 */
/** 旧版富文本说明转纯文本（保留换行） */
function stripHtml(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|li|div|h\d)>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\n{2,}/g, '\n')
    .trim()
}

/**
 * 解析 usageGuide 字段，兼容新旧两种格式。
 * 入参 raw：后端返回的原始字符串，可能为 null/空串。
 * 返回 { steps, legacy }：
 * - 空值 => 一个空步骤，legacy=false；
 * - 以 '[' 开头且能 JSON.parse 成数组 => 逐项读取 text/imageUrl，
 *   过滤掉两者都为空的步骤；只要有有效步骤就按新格式返回；
 * - 其他情况（JSON 解析失败、不是数组、数组为空、或本来就是纯文本）
 *   => 把整段内容交给 stripHtml，作为单步骤，legacy=true。
 * 对数组项做了类型收窄：只接受字符串字段，防止脏 JSON 污染表单。
 * 解析失败不抛错，保证历史数据仍可编辑。
 */
 /*
 * parseGuide 的决策树（按顺序）：
 * 1. 原始值 trim 后为空 => 空步骤，legacy=false；
 * 2. 以 '[' 开头 => 尝试 JSON.parse：
 *    a. 解析成功且是数组 => 映射每项的 text/imageUrl；
 *    b. 过滤掉两者都为空的项；
 *    c. 若仍有步骤 => 新格式返回；
 * 3. JSON.parse 抛错 => 捕获后继续；
 * 4. 其余情况 => stripHtml 后作为单步骤，legacy=true。
 * 以 '[' 开头只是快速判断，真正的格式确认仍依赖 JSON.parse；
 * 如果旧文本恰好以 '[' 开头但不是 JSON，会走 catch 回退到纯文本。
 * 数组项使用 Record<string, unknown> 收窄，避免 any 污染；
 * text/imageUrl 只接受字符串，数字或对象一律置空，
 * 从而保证后续 serializeGuide 的输入始终是字符串。
 * 返回的 steps 至少有一个元素，模板无需处理空数组。
 * legacy=true 只影响提示文案，不阻止编辑和保存。
 * JSON 数组项如果包含额外字段（如 caption），当前实现会忽略，
 * 只保留 text/imageUrl，保证提交结构稳定。
 * 如果 JSON 是对象而不是数组，会走旧版文本回退，而不是报错。
 */
function parseGuide(raw?: string | null): { steps: GuideStep[]; legacy: boolean } {
  const value = (raw ?? '').trim()
  if (!value) return { steps: [emptyStep()], legacy: false }
  if (value.startsWith('[')) {
    try {
      const arr = JSON.parse(value)
      if (Array.isArray(arr)) {
        const steps = arr
          .map((item: unknown) => {
            const record = (item ?? {}) as Record<string, unknown>
            return {
              text: typeof record.text === 'string' ? record.text : '',
              imageUrl: typeof record.imageUrl === 'string' ? record.imageUrl : '',
            }
          })
          .filter((step) => step.text || step.imageUrl)
        if (steps.length) return { steps, legacy: false }
      }
    } catch {
      // 不是合法 JSON，按旧版文本处理
    }
  }
  return { steps: [{ text: stripHtml(value), imageUrl: '' }], legacy: true }
}

/**
 * 把步骤数组序列化回 usageGuide。
 * 规则：
 * - 对每个步骤的 text 做 trim，imageUrl 原样保留；
 * - 过滤掉文字和图片都为空的步骤，避免产生空步骤；
 * - 有有效步骤时返回 JSON.stringify(steps)，否则返回空串。
 * 这样后端始终收到统一的 JSON 格式，旧版纯文本在保存后完成升级。
 */
 /*
 * serializeGuide 与 parseGuide 互为逆操作，但并非严格可逆：
 * - parse 时会保留原始 imageUrl，serialize 时只 trim text；
 * - parse 会过滤空步骤，serialize 也会再次过滤，形成双重保险；
 * - 旧版纯文本在第一次保存后变成 JSON，之后 parse 会走新格式分支。
 * 空字符串表示「没有使用指南」，后端应将其视为未填写。
 * JSON.stringify 不会对中文转义，提交的是可读的 UTF-8 字符串。
 * 序列化后的字符串长度没有前端限制；
 * 但每个步骤的 text 在输入框中限制 500 字，步骤数量也未限制。
 * 若后端有总长度限制，提交失败时会由拦截器提示。
 */
function serializeGuide(): string {
  const steps = guideSteps.value
    .map((step) => ({ text: step.text.trim(), imageUrl: step.imageUrl }))
    .filter((step) => step.text || step.imageUrl)
  return steps.length ? JSON.stringify(steps) : ''
}

/**
 * 新增一个空步骤到末尾。
 * 不做数量上限限制；用户可在编辑器中继续填写或删除。
 */
 /*
 * addStep 不自动滚动到新步骤，用户需要手动滚动；
 * 如果步骤很多，可以考虑在新增后聚焦最后一个 textarea，
 * 但当前实现保持简单，避免引入额外的 DOM 引用。
 */
function addStep() {
  guideSteps.value.push(emptyStep())
}

/**
 * 删除指定步骤。
 * 边界：至少保留一个步骤，因此长度为 1 时直接返回，
 * 防止编辑器出现空状态无法继续添加。
 */
 /*
 * removeStep 只删除本地数组项，不调用后端；
 * 保存时 serializeGuide 会把剩余步骤整体提交。
 * 如果删除的是正在上传的步骤，stepUploadIndex 可能指向旧下标，
 * 因此模板在上传期间禁用删除按钮（由 stepUploading 控制）。
 */
function removeStep(index: number) {
  if (guideSteps.value.length <= 1) return
  guideSteps.value.splice(index, 1)
}

/**
 * 上移/下移步骤。
 * 入参 index：当前步骤下标；delta：-1 上移、1 下移。
 * 先计算目标下标并做边界校验，再通过 splice 取出并插入，
 * 保持 guideSteps 数组顺序，序列化后即用户看到的步骤顺序。
 */
 /*
 * moveStep 使用 splice 完成「取出再插入」：
 * - 先删除原位置，再插入目标位置；
 * - 数组长度不变，元素引用不变；
 * - 不会触发额外的上传或请求。
 * delta 只应为 -1 或 1，但函数本身不限制，调用方负责传值。
 */
function moveStep(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= guideSteps.value.length) return
  const [item] = guideSteps.value.splice(index, 1)
  guideSteps.value.splice(target, 0, item)
}

/**
 * 新增/编辑表单数据。
 * 字段与 ToolSaveDTO 对齐：
 * - name/model/category/damageLevel：基础档案；
 * - deposit：押金，输入框使用 v-model.number；
 * - storageArea/supplier/purchaseDate：仓储与采购信息；
 * - usageGuide：提交前由 serializeGuide 覆盖为步骤 JSON；
 * - imageUrls：工具图片数组，先上传再保存；
 * - status：工具状态，默认 AVAILABLE。
 * 使用 reactive 而不是 ref，模板可直接 v-model 到具体字段。
 */
 /*
 * form 的字段初始值与 resetForm 中的默认值必须保持一致；
 * 新增工具时类别默认 WX、成色默认 NEW、状态默认 AVAILABLE，
 * 这些默认值是业务约定，不是后端强制。
 * deposit 初始为 0，但校验允许 0（免费押金）；
 * purchaseDate 初始为 null，提交时保持 null 而不是空串。
 * usageGuide 在表单中始终是字符串，编辑时由 parseGuide 解析成步骤，
 * 提交时再由 serializeGuide 覆盖，form.usageGuide 本身只作为中间载体。
 * form 的响应式代理在 resetForm 与 openEdit 中通过 Object.assign 更新，
 * 不会替换代理本身，因此模板中的 v-model 绑定始终有效。
 */
const form = reactive<ToolSaveDTO>({
  name: '',
  model: '',
  category: 'WX',
  damageLevel: 'NEW',
  deposit: 0,
  storageArea: '',
  usageGuide: '',
  imageUrls: [],
  purchaseDate: null,
  supplier: '',
  status: 'AVAILABLE',
})

/**
 * 表单字段错误信息。
 * key 为字段名，value 为提示文案；模板通过 formErrors.xxx 控制红框与提示。
 * 每次校验前先清空，再整体 Object.assign，避免残留上一次的错误。
 */
 /*
 * formErrors 使用 Record<string, string> 而不是固定接口，
 * 便于按字段名动态增删；模板只读取已定义的几个 key。
 * 清空时使用 delete 而不是赋空串，避免残留 key 影响 Object.keys 判断。
 */
const formErrors = reactive<Record<string, string>>({})

/**
 * 判断押金是否非法。
 * 合法条件：非空、可转成数字、有限且 >= 0。
 * 注意 v-model.number 在输入框清空时可能得到 ''，这里显式拦截；
 * NaN/Infinity 也视为非法，防止提交后端无法解析的值。
 */
function depositInvalid(): boolean {
  const raw = form.deposit as unknown
  if (raw === '' || raw === null || raw === undefined) return true
  const n = Number(raw)
  return !Number.isFinite(n) || n < 0
}

/**
 * 提交前的前端校验。
 * 必填项：名称、类别、损坏程度、押金。
 * 校验结果写入 formErrors；返回 true 表示全部通过。
 * 这里只做「必填/格式」级别的校验，长度限制由输入框 maxlength 兜底，
 * 更复杂的业务规则由后端校验并返回错误。
 */
 /*
 * depositInvalid 的输入可能来自三种情况：
 * - 用户输入数字 => number，正常校验；
 * - 用户清空输入 => ''（v-model.number 的边界行为）；
 * - 用户输入非法字符 => 浏览器可能保留字符串，Number 后为 NaN。
 * Number.isFinite 同时排除 NaN、Infinity 和 -Infinity。
 * 返回 true 表示「非法」，函数命名与 validateForm 中的使用方式一致。
 * 押金为 0 是合法的（例如免费借用），因此不能用 `!form.deposit` 判断；
 * 必须显式区分空值、NaN 与负数。
 */
function validateForm(): boolean {
  const errors: Record<string, string> = {}
  if (!form.name.trim()) errors.name = '请输入工具名称'
  if (!form.category) errors.category = '请选择类别'
  if (!form.damageLevel) errors.damageLevel = '请选择损坏程度'
  if (depositInvalid()) errors.deposit = '请输入有效押金'
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  Object.assign(formErrors, errors)
  return Object.keys(errors).length === 0
}

/**
 * 重置表单到新增默认值。
 * 同时清空 editingId/editingCode、步骤数组、旧版标记和错误信息，
 * 保证下一次打开新增弹窗时不残留上一次编辑的数据。
 * 注意 imageUrls 赋新数组，避免与旧表单共享引用。
 */
 /*
 * validateForm 的错误对象只包含当前失败的字段；
 * 先删除 formErrors 的所有旧 key，再合并新错误，
 * 保证用户修正一个字段后，该字段的红框立即消失。
 * 校验顺序不影响结果，所有错误会一次性展示。
 * 前端校验通过不代表后端一定接受，后端仍会做长度、枚举、权限等校验。
 */
function resetForm() {
  editingId.value = null
  editingCode.value = ''
  Object.assign(form, {
    name: '',
    model: '',
    category: 'WX',
    damageLevel: 'NEW',
    deposit: 0,
    storageArea: '',
    usageGuide: '',
    imageUrls: [],
    purchaseDate: null,
    supplier: '',
    status: 'AVAILABLE',
  })
  guideSteps.value = [emptyStep()]
  legacyGuide.value = false
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
}

/**
 * 打开新增弹窗。
 * 先重置表单，再显示弹窗；不发起任何请求。
 * 保存成功后由 handleSubmit 调用 create 接口。
 */
 /*
 * openCreate 不接收参数，适合直接绑定到按钮 click；
 * resetForm 会清空 editingId，因此同一个弹窗可以安全地在新增/编辑间切换。
 */
function openCreate() {
  resetForm()
  dialogVisible.value = true
}

/**
 * 打开编辑弹窗并回填数据。
 * 入参 row：列表中的工具记录。
 * 关键映射：
 * - 基础字段直接复制，null 用空串/null 兜底；
 * - imageUrls 优先使用多图数组，否则用历史单图 imageUrl 包成数组；
 * - usageGuide 交给 parseGuide 解析成步骤与 legacy 标记；
 * - status 一并回填，允许管理员在编辑时调整状态。
 * 副作用：写入表单、步骤、旧版标记，清空错误并显示弹窗。
 */
 /*
 * openEdit 的回填细节：
 * - row.model/storageArea/supplier 可能为 null，统一转空串；
 * - row.purchaseDate 为 null 时保持 null，交给 date 输入框处理；
 * - row.imageUrls 可能为空数组，此时回退到单图 imageUrl；
 * - row.status 直接回填，编辑表单允许调整状态；
 * - usageGuide 可能为旧版 HTML，parseGuide 会标记 legacy。
 * 回填使用 Object.assign，不会替换 form 的响应式代理；
 * imageUrls 使用新数组，避免直接修改 row 的数据。
 * 编辑时若 row.imageUrls 同时包含多图和单图，优先使用多图数组；
 * 单图字段只在多图为空时作为兼容回退。
 */
function openEdit(row: ToolVO) {
  editingId.value = row.id
  editingCode.value = row.code
  Object.assign(form, {
    name: row.name,
    model: row.model ?? '',
    category: row.category,
    damageLevel: row.damageLevel,
    deposit: row.deposit,
    storageArea: row.storageArea ?? '',
    usageGuide: row.usageGuide ?? '',
    imageUrls: row.imageUrls?.length ? [...row.imageUrls] : row.imageUrl ? [row.imageUrl] : [],
    purchaseDate: row.purchaseDate ?? null,
    supplier: row.supplier ?? '',
    status: row.status,
  })
  const parsed = parseGuide(row.usageGuide)
  guideSteps.value = parsed.steps
  legacyGuide.value = parsed.legacy
  Object.keys(formErrors).forEach((k) => delete formErrors[k])
  dialogVisible.value = true
}

/**
 * 保存工具（新增或编辑）。
 * 流程：
 * 1. validateForm 不通过则直接返回；
 * 2. 组装 payload：复制表单，押金转数字，usageGuide 用 serializeGuide
 *    覆盖为步骤 JSON，imageUrls 复制新数组，purchaseDate 空值归一为 null；
 * 3. 根据 editingId 调用 update 或 create；
 * 4. 成功提示后关闭弹窗并刷新列表；
 * 5. finally 复位 saving。
 * 使用展开复制表单可避免后续请求期间表单被继续编辑造成脏数据。
 */
 /*
 * handleSubmit 的 payload 组装顺序很重要：
 * 1. 先展开 form，保留所有基础字段；
 * 2. 再覆盖 deposit、usageGuide、imageUrls、purchaseDate，
 *    确保提交的是规范化后的值，而不是输入框的临时状态。
 * Number(form.deposit) || 0 处理空串/NaN，但会把 0 保持为 0；
 * purchaseDate 的 '' 会转成 null，日期控件清空时提交 null。
 * 编辑成功后不重置 editingId，弹窗关闭即可；下次 openCreate 会重置。
 * 保存失败时弹窗保持打开，用户可修正后重试。
 * 保存成功后刷新列表，列表中的审核状态、状态、更新时间都会更新。
 * 新增成功后 editingId 仍为 null，弹窗关闭；
 * 若用户再次点击新增，resetForm 会清空表单，不会残留刚提交的数据。
 */
async function handleSubmit() {
  if (!validateForm()) return
  saving.value = true
  try {
    const payload: ToolSaveDTO = {
      ...form,
      deposit: Number(form.deposit) || 0,
      usageGuide: serializeGuide(),
      imageUrls: [...form.imageUrls],
      purchaseDate: form.purchaseDate || null,
    }
    if (editingId.value) {
      await toolApi.update(editingId.value, payload)
      ElMessage.success('工具已更新')
    } else {
      await toolApi.create(payload)
      ElMessage.success('工具已新增')
    }
    dialogVisible.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

/**
 * ============================================================================
 * 图片上传
 * ============================================================================
 * 页面使用两个隐藏的原生 file input：
 * - imageFileRef 负责工具主图，可多次选择追加到 imageUrls；
 * - stepFileRef 负责使用指南步骤配图。
 * 选择文件后先本地清空 input.value，确保同一文件可以再次选择；
 * 真正的上传统一走 uploadApi.uploadFile -> /admin/upload。
 */
 /*
 * 为什么使用原生 file input 而不是组件库上传组件：
 * - 需求简单，只需要选文件并调用统一上传接口；
 * - 原生 input 更容易控制「选完即清空 value」的行为；
 * - 避免引入额外的上传组件依赖和样式覆盖成本。
 * 上传接口返回的 URL 直接进入表单，保存工具时才写入业务数据。
 * 两个上传入口共用同一个 uploadApi.uploadFile，
 * 因此文件类型、大小、存储路径等规则完全一致；
 * 前端不需要区分「主图」与「步骤图」的存储策略。
 */
// —— 图片上传（原生 file input） ——
/**
 * 隐藏文件输入框与步骤上传上下文：
 * - imageFileRef：工具主图 input 的模板引用；
 * - stepFileRef：步骤图片 input 的模板引用；
 * - stepUploadIndex：当前正在上传图片的步骤下标，-1 表示无。
 * 用一个共享 input 服务所有步骤，避免为每个步骤创建 DOM 节点。
 */
 /*
 * 两个 input 都设置为 display:none，通过按钮点击间接触发；
 * ref 在模板中通过 ref="imageFileRef" 绑定，setup 中初始为 undefined。
 * stepFileRef 是所有步骤共享的，因此必须配合 stepUploadIndex 使用。
 */
const imageFileRef = ref<HTMLInputElement>()
const stepFileRef = ref<HTMLInputElement>()
const stepUploadIndex = ref(-1)

/**
 * 触发工具主图选择。
 * 通过模板引用调用原生 input.click()，没有选中文件时不会上传。
 */
 /*
 * pickImage 使用可选链，input 尚未挂载时不会报错；
 * 该方法只负责触发选择，不处理文件内容。
 */
function pickImage() {
  imageFileRef.value?.click()
}

/**
 * 工具主图选择回调。
 * 读取第一个文件后立即清空 input.value，避免重复选择同一文件不触发 change；
 * 没有文件则返回，否则交给 handleUpload 上传并追加 URL。
 */
 /*
 * onImageChange 的关键是 `input.value = ''`：
 * 浏览器在同一个 input 上选择相同文件时不会再次触发 change，
 * 清空 value 可以保证用户重复选择同一张图也能上传。
 * 读取 files?.[0] 后立即清空，避免后续异步上传期间用户再次选择造成混乱。
 */
function onImageChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  handleUpload(file)
}

/**
 * 记录要上传图片的步骤下标，并触发步骤文件选择。
 * 入参 index：当前步骤在 guideSteps 中的位置。
 * 由于所有步骤共用一个 input，必须先保存下标，change 回调才知道写到哪一步。
 */
 /*
 * pickStepImage 必须先写 stepUploadIndex，再触发 input.click；
 * 如果顺序反过来，change 回调可能读到旧下标。
 * 模板在上传期间禁用其他步骤的上传按钮，进一步降低竞态。
 */
function pickStepImage(index: number) {
  stepUploadIndex.value = index
  stepFileRef.value?.click()
}

/**
 * 步骤图片选择回调。
 * 取第一个文件并清空 input.value，然后读取 stepUploadIndex；
 * 下标非法（<0）时直接返回，防止上传结果写到错误步骤。
 */
 /*
 * onStepFileChange 读取的是共享 input 的文件；
 * 清空 value 保证同一文件可重复选择；
 * stepUploadIndex < 0 表示没有有效上下文，直接返回，避免写入 guideSteps[-1]。
 */
function onStepFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const index = stepUploadIndex.value
  if (index < 0) return
  handleStepUpload(file, index)
}

/**
 * 上传工具主图并追加到表单。
 * 入参 file：浏览器 File 对象。
 * 副作用：
 * - uploading=true 期间按钮显示 loading 且禁用；
 * - 调用 uploadApi.uploadFile(file)，把返回的 data.url push 到 imageUrls；
 * - 成功提示；finally 复位 uploading。
 * 上传失败由请求拦截器提示，表单保持原样。
 */
 /*
 * handleUpload 的异常处理：
 * - 上传失败时不会 push 任何 URL，表单保持原状；
 * - uploading 在 finally 中复位，按钮恢复可点；
 * - 成功提示使用 ElMessage.success，错误提示由拦截器负责。
 * 该函数不校验文件类型/大小，约束由后端上传接口执行；
 * 前端 accept="image/*" 只是选择器提示，不是安全边界。
 * 上传成功后返回的 URL 可能是相对 key，展示时由 resolveFileUrl 处理；
 * 表单中保存原始返回值，避免把带域名的绝对地址写入业务数据。
 */
async function handleUpload(file: File) {
  uploading.value = true
  try {
    const data = await uploadApi.uploadFile(file)
    form.imageUrls.push(data.url)
    ElMessage.success('图片上传成功')
  } finally {
    uploading.value = false
  }
}

/**
 * 移除工具图片。
 * 入参 index：imageUrls 中的下标，直接 splice；
 * 只影响本地表单，保存后才会同步到后端。
 */
 /*
 * removeImage 使用 splice 而不是 filter，
 * 因为模板中的 index 就是当前数组下标，splice 能精确删除。
 * 删除后 key 使用 url+index，剩余图片的 key 会变化，但不会影响渲染正确性。
 */
function removeImage(index: number) {
  form.imageUrls.splice(index, 1)
}

/**
 * 上传步骤配图并写回对应步骤。
 * 入参 file：File；index：步骤下标。
 * 副作用：
 * - stepUploading 记录正在上传的步骤，按钮显示 loading；
 * - 上传成功后把 data.url 写入 guideSteps[index].imageUrl；
 * - finally 复位为 null。
 * 注意 array[index] 的引用在等待期间可能因删除步骤而变化，
 * 因此模板在选择期间会禁用其他上传按钮，降低并发修改风险。
 */
 /*
 * handleStepUpload 与 handleUpload 的差异：
 * - 上传结果写入 guideSteps[index].imageUrl，而不是 push；
 * - stepUploading 记录下标，而不是布尔值，便于按钮显示 loading；
 * - 同一时间只允许一个步骤上传，避免共享 input 的下标错乱。
 * 上传期间如果用户删除步骤，写入可能落到新占用该下标的步骤上；
 * 模板通过禁用删除/其他上传按钮来规避这一风险。
 * 步骤图片上传期间，模板通过 stepUploading !== null 禁用所有上传按钮，
 * 这是一种简单但有效的互斥策略，避免共享 input 的上下文错乱。
 */
async function handleStepUpload(file: File, index: number) {
  stepUploading.value = index
  try {
    const data = await uploadApi.uploadFile(file)
    guideSteps.value[index].imageUrl = data.url
    ElMessage.success('步骤图片上传成功')
  } finally {
    stepUploading.value = null
  }
}

/**
 * ============================================================================
 * 图片预览
 * ============================================================================
 * 表格缩略图、步骤配图、工具图片点击后都调用 openPreview，
 * 由页面底部的预览遮罩展示大图；点击遮罩或图片即可关闭。
 */
 /*
 * 预览层是页面内最后的 DOM 节点，层级高于弹窗与抽屉；
 * 预览 URL 已经过 resolveFileUrl，因此可以直接用于 img src。
 * 预览不修改业务数据，关闭时只清空 previewUrl。
 */
// —— 图片预览 ——
/**
 * 当前预览图片地址，空串表示不显示预览层。
 */
 /*
 * previewUrl 为空串时 v-if 为 false，预览层不渲染；
 * 使用 ref 而不是布尔开关，是因为需要同时保存要展示的地址。
 */
const previewUrl = ref('')
/**
 * 打开图片预览。
 * 入参 url 应已经过 resolveFileUrl 处理，保证相对 key 也能正确加载。
 * 仅写入 previewUrl，不校验 URL 合法性。
 */
 /*
 * openPreview 不做 URL 校验，调用方需保证传入可访问地址；
 * 表格缩略图、步骤图、工具图都遵循这一约定。
 */
function openPreview(url: string) {
  previewUrl.value = url
}

/**
 * ============================================================================
 * 状态更新（状态机）
 * ============================================================================
 * 工具状态不允许任意跳转，页面只展示当前状态允许的目标状态。
 * 状态机定义在前端用于 UI 收口，后端仍会再次校验，
 * 双方共同保证不会出现非法流转。
 */
 /*
 * 状态机的 UI 收口只是第一道防线：
 * - 前端只展示合法目标，减少误操作；
 * - 后端仍会校验当前状态、活动租赁、报修等业务条件；
 * - 若后端拒绝，错误由拦截器提示，弹窗保持打开供用户调整。
 * 状态变更会写入操作日志，动作类型为 STATUS_CHANGE，
 * 日志中记录 fromStatus 与 toStatus，便于追溯。
 * 状态机表是纯数据，不依赖组件状态；
 * 如果未来需要在多个页面复用，可以抽到 utils 或常量文件。
 */
// —— 状态更新：只提供状态机允许的目标状态 ——
/**
 * 工具状态流转表。
 * 键为当前状态，值为允许的下一状态数组：
 * - AVAILABLE：可借出、送修、直接报废；
 * - RENTED：归还后可用、送修、报废；
 * - MAINTENANCE：修好可用、无法修复则报废；
 * - SCRAPPED：没有普通流转，只能走 restore 专用接口。
 * 该表既用于生成「状态」弹窗的下拉选项，也用于判断是否显示「状态」按钮。
 */
 /*
 * 状态语义补充：
 * - RENTED 通常由租赁流程自动进入，管理端手动置为 RENTED 属于纠错场景；
 * - MAINTENANCE 可由报修流程或管理员手动进入；
 * - SCRAPPED 表示实物退出流通，恢复需要单独接口与原因；
 * - AVAILABLE 是默认状态，也是恢复后的目标状态。
 * 表中故意不包含「同状态流转」，避免无意义请求；
 * 若后端未来新增状态，需要同步更新此表与 ToolStatus 类型。
 * statusChoices 直接读取此表，因此表的顺序就是下拉选项顺序。
 * 状态变更的日志会记录操作人，因此即使多人协作也能追溯是谁改的状态。
 */
const STATUS_TRANSITIONS: Record<ToolStatus, ToolStatus[]> = {
  AVAILABLE: ['RENTED', 'MAINTENANCE', 'SCRAPPED'],
  RENTED: ['AVAILABLE', 'MAINTENANCE', 'SCRAPPED'],
  MAINTENANCE: ['AVAILABLE', 'SCRAPPED'],
  SCRAPPED: [],
}

/**
 * 状态更新弹窗的本地状态：
 * - statusDialogVisible：显隐；
 * - statusSaving：提交中，防重复点击；
 * - statusTarget：目标工具行；
 * - statusValue：用户选择的目标状态，提交前由状态机选项初始化。
 */
 /*
 * 这些 ref 在弹窗关闭后不会自动清空 statusTarget/statusValue，
 * 但下次 openStatusDialog 会覆盖；弹窗内容使用可选链兜底。
 */
const statusDialogVisible = ref(false)
const statusSaving = ref(false)
const statusTarget = ref<ToolVO | null>(null)
const statusValue = ref<ToolStatus>('AVAILABLE')

/**
 * 当前可选的下一状态列表。
 * 依据 statusTarget.status 查 STATUS_TRANSITIONS；
 * 若目标为空或状态未知，返回空数组。
 * 每项转成 { value, label } 供下拉框渲染，label 来自中文枚举映射。
 */
 /*
 * statusChoices 使用 computed 而不是在 openStatusDialog 中计算，
 * 这样模板中的下拉选项会随 statusTarget 自动更新。
 * 当 statusTarget 为空时返回空数组，下拉框不显示任何选项。
 * map 中同时构造 value 与 label，避免模板中再调用 enumLabel。
 */
const statusChoices = computed(() => {
  const current = statusTarget.value?.status
  return (current ? STATUS_TRANSITIONS[current] ?? [] : []).map((status) => ({
    value: status,
    label: enumLabel(TOOL_STATUS_LABELS, status),
  }))
})

/**
 * 打开状态更新弹窗。
 * 入参 row：要变更状态的工具。
 * 若当前状态没有可流转目标（典型为 SCRAPPED），直接返回，
 * 不打开空弹窗；否则记录目标、默认选中第一个合法目标并显示弹窗。
 */
 /*
 * openStatusDialog 的默认选中值是 choices[0]：
 * - 对于 AVAILABLE，默认选中 RENTED；
 * - 对于 RENTED，默认选中 AVAILABLE；
 * - 对于 MAINTENANCE，默认选中 AVAILABLE。
 * 用户可以在下拉中改选其他合法目标。
 * 若 choices 为空（SCRAPPED），直接返回，不打开弹窗；
 * 模板中此时显示的是「恢复」按钮而不是「状态」按钮。
 */
function openStatusDialog(row: ToolVO) {
  const choices = STATUS_TRANSITIONS[row.status] ?? []
  if (!choices.length) return
  statusTarget.value = row
  statusValue.value = choices[0]
  statusDialogVisible.value = true
}

/**
 * 提交状态变更。
 * 流程：
 * 1. 校验 target 存在；
 * 2. 用 ElMessageBox.confirm 做二次确认，展示工具名与目标状态中文名；
 *    用户取消会抛异常，catch 后静默返回；
 * 3. 调用 toolApi.updateStatus(target.id, statusValue)；
 * 4. 成功提示、关闭弹窗、刷新列表；
 * 5. finally 复位 statusSaving。
 * 后端会再次校验状态机；若非法流转，错误由拦截器提示。
 */
 /*
 * submitStatus 的二次确认：
 * - 使用 ElMessageBox.confirm 展示工具名与目标状态中文名；
 * - 用户点取消会 reject，catch 后静默返回，不改变任何状态；
 * - 确认后才调用 updateStatus，避免误点直接改状态。
 * 成功后关闭弹窗并刷新列表；失败时弹窗保持打开，statusSaving 复位。
 * 这里没有 catch 请求异常，依赖拦截器提示。
 * 状态更新接口返回 void，因此不需要处理响应体。
 * 二次确认框的取消异常没有区分「用户取消」与「组件卸载」，
 * 两者都静默返回；对用户体验而言结果一致。
 */
async function submitStatus() {
  const target = statusTarget.value
  if (!target) return
  try {
    await ElMessageBox.confirm(
      `确认将「${target.name}」状态更新为「${enumLabel(TOOL_STATUS_LABELS, statusValue.value)}」？`,
      '状态更新',
      { type: 'warning' },
    )
  } catch {
    return
  }
  statusSaving.value = true
  try {
    await toolApi.updateStatus(target.id, statusValue.value)
    ElMessage.success('状态已更新')
    statusDialogVisible.value = false
    await loadList()
  } finally {
    statusSaving.value = false
  }
}

/**
 * ============================================================================
 * 报废恢复（仅系统管理员）
 * ============================================================================
 * SCRAPPED 是普通状态机的终态，不能通过 updateStatus 回到可用状态。
 * 后端提供独立的 restore 接口，要求填写恢复原因，并通常还会校验：
 * - 当前工具确实处于 SCRAPPED；
 * - 不存在活动租赁或未完成报修。
 * 恢复成功后工具变为 AVAILABLE，因此前端按钮与弹窗只对超级管理员开放。
 */
 /*
 * 为什么恢复必须单独一个接口：
 * - 报废是终态，普通状态机不允许 SCRAPPED -> AVAILABLE；
 * - 恢复需要记录原因，便于审计「为什么把报废工具重新投入使用」；
 * - 后端可能还需要检查租赁/报修数据，普通状态接口不适合承载这些规则。
 * 因此前端也把恢复做成独立弹窗与独立按钮，避免用户误以为可以随意改状态。
 * 恢复原因会写入日志与审计记录，前端限制 500 字，
 * 既保证信息量，又避免提交过长文本。
 */
// —— 报废恢复（仅系统管理员） ——
/**
 * 恢复弹窗的本地状态：
 * - restoreVisible：显隐；
 * - restoreSaving：提交中；
 * - restoreTarget：被恢复的工具；
 * - restoreReason：恢复原因，必填，最多 500 字。
 */
 /*
 * 恢复弹窗的状态与其他弹窗一致：显隐、提交中、目标、表单字段。
 * restoreReason 使用 ref<string> 而不是放进 reactive 表单，
 * 因为只有一个字段，单独 ref 更直接。
 */
const restoreVisible = ref(false)
const restoreSaving = ref(false)
const restoreTarget = ref<ToolVO | null>(null)
const restoreReason = ref('')

/**
 * 打开恢复弹窗。
 * 入参 row：已报废的工具。
 * 每次打开都清空原因，避免沿用上一次的文本；
 * 不发起请求，提交逻辑在 submitRestore。
 */
 /*
 * openRestore 每次清空原因，防止把上一次的恢复原因带到新一次操作；
 * 恢复原因属于审计信息，不应默认复用。
 */
function openRestore(row: ToolVO) {
  restoreTarget.value = row
  restoreReason.value = ''
  restoreVisible.value = true
}

/**
 * 提交报废恢复。
 * 校验：恢复原因 trim 后不能为空，否则警告并中止。
 * 调用 toolApi.restore(target.id, { reason })；
 * 成功后提示「已恢复为可借用」、关闭弹窗并刷新列表；
 * finally 复位 restoreSaving。
 * 后端可能因活动租赁/未完成报修拒绝恢复，错误由拦截器提示。
 */
 /*
 * submitRestore 的校验与提交：
 * - trim 后为空 => 警告并中止，不调用接口；
 * - 成功 => 提示「已恢复为可借用」，关闭弹窗并刷新列表；
 * - finally 复位 restoreSaving，失败可重试。
 * 恢复后的状态由后端决定，前端文案按约定写死为 AVAILABLE；
 * 若后端策略变化，应同步修改提示文案。
 * 恢复操作会写入操作日志，动作类型为 RESTORE。
 * 恢复按钮只对 SCRAPPED 行显示，因此正常状态下不会出现该入口；
 * 若后端返回未知状态，openStatusDialog 与 openRestore 都不会显示。
 */
async function submitRestore() {
  const target = restoreTarget.value
  if (!target) return
  if (!restoreReason.value.trim()) {
    ElMessage.warning('请填写恢复原因')
    return
  }
  restoreSaving.value = true
  try {
    await toolApi.restore(target.id, { reason: restoreReason.value.trim() })
    ElMessage.success('工具已恢复为可借用')
    restoreVisible.value = false
    await loadList()
  } finally {
    restoreSaving.value = false
  }
}

/**
 * ============================================================================
 * 操作日志
 * ============================================================================
 * 每次新增、编辑、审核、状态变更、恢复等操作都会在后端写入工具日志。
 * 页面以抽屉时间线展示：动作、操作人、时间、状态前后变化、备注/详情。
 * 日志按后端返回顺序展示（通常为新到旧），最后一条不显示连接线。
 */
 /*
 * 日志时间线的数据来源是后端审计表，不由前端拼接；
 * 前端只负责按返回顺序渲染，并做枚举到中文的映射。
 * 日志列表不分页，通常单工具日志量有限；
 * 若未来日志量很大，需要改为分页或虚拟滚动。
 * 日志抽屉与历史租赁抽屉共用 drawer-mask/drawer 全局样式，
 * 本组件只通过内联 width 区分宽度（520px 与 680px）。
 */
// —— 操作日志 ——
/**
 * 日志抽屉状态：
 * - logDrawerVisible：显隐；
 * - logLoading：加载中，显示骨架屏；
 * - logs：日志数组；
 * - logToolName：抽屉标题中的工具名称。
 */
 /*
 * logs 使用 ref<ToolLogVO[]> 而不是 reactive，
 * 因为每次打开抽屉都会整体替换数组；
 * logLoading 控制骨架屏，避免展示上一次工具的旧日志。
 */
const logDrawerVisible = ref(false)
const logLoading = ref(false)
const logs = ref<ToolLogVO[]>([])
const logToolName = ref('')

/**
 * 把日志中的状态字段翻译成中文。
 * 入参 value：可能是工具状态枚举（AVAILABLE 等），也可能是审核枚举名
 * （PENDING/APPROVED/NEEDS_CHANGE），还可能是空值。
 * 查找顺序：TOOL_STATUS_LABELS -> AUDIT_STATUS_NAME_LABELS -> 原值；
 * 空值显示 '—'，未知值原样返回，便于排查后端新增枚举。
 */
 /*
 * logStatusLabel 的回退顺序很重要：
 * - 工具状态枚举优先，因为状态变更日志最常见；
 * - 审核枚举名次之，因为审核日志的 from/to 可能是 AUDIT 枚举；
 * - 都没命中时原样返回，便于发现后端新增枚举；
 * - 空值统一显示 '—'，避免时间线出现空白。
 * 两张映射表的 key 不重叠（工具状态是 AVAILABLE 等，审核是 PENDING 等），
 * 因此顺序不会造成歧义。
 */
function logStatusLabel(value?: string | null): string {
  if (!value) return '—'
  return (
    TOOL_STATUS_LABELS[value as ToolStatus] ??
    AUDIT_STATUS_NAME_LABELS[value] ??
    value
  )
}

/**
 * 打开操作日志抽屉。
 * 入参 row：目标工具。
 * 副作用：记录工具名、显示抽屉、置加载态、清空旧日志；
 * 调用 toolApi.logs(row.id) 拉取时间线并写入 logs；
 * finally 复位 logLoading。
 * 抽屉先打开再加载，用户能立即看到骨架屏反馈。
 */
 /*
 * openLogs 的加载顺序：
 * 1. 记录工具名（用于标题）；
 * 2. 打开抽屉，让用户立即看到反馈；
 * 3. 置 loading=true 并清空旧日志；
 * 4. 请求 logs 接口；
 * 5. finally 复位 loading。
 * 如果请求失败，抽屉保持打开并显示空态/骨架结束后的空态；
 * 错误提示由拦截器负责，用户可关闭抽屉后重试。
 * 这里没有分页，因为日志接口返回完整数组。
 * 日志请求失败时 logs 保持空数组，抽屉显示「暂无状态日志」；
 * 这与「确实没有日志」在视觉上相同，用户可通过错误提示区分。
 */
async function openLogs(row: ToolVO) {
  logToolName.value = row.name
  logDrawerVisible.value = true
  logLoading.value = true
  logs.value = []
  try {
    logs.value = await toolApi.logs(row.id)
  } finally {
    logLoading.value = false
  }
}

/**
 * ============================================================================
 * 历史租赁
 * ============================================================================
 * 管理员可查看单个工具的全部租赁单，用于了解使用情况与纠纷追溯。
 * 数据通过 toolApi.rentals(id, { page, size }) 分页获取，
 * 抽屉内固定每页 5 条，只提供上一页/下一页。
 */
 /*
 * 历史租赁与租赁管理页的区别：
 * - 本抽屉只读，用于从工具视角查看使用历史；
 * - 租赁管理页负责下单、归还、结算等写操作；
 * - 本页不展示费用明细、支付记录等敏感/复杂信息，
 *   只保留单号、租用人、租期、押金、状态五个关键字段。
 * 这样既满足追溯需求，又避免页面职责过重。
 * 历史租赁抽屉只读，不提供跳转到租赁详情的能力；
 * 若需要查看费用明细，应前往租赁管理页按单号搜索。
 */
// —— 工具历史租赁 ——
/**
 * 历史租赁抽屉状态：
 * - rentalDrawerVisible：显隐；
 * - rentalLoading：加载中；
 * - rentalList/rentalTotal：当前页数据与总数；
 * - rentalPage/rentalSize：页码与每页条数（固定 5）；
 * - rentalTool：当前工具，用于标题与请求 id。
 */
 /*
 * 租赁分页使用独立的 rentalPage/rentalSize，
 * 不复用工具列表的 query.page/query.size，避免两个分页互相干扰。
 * rentalSize 固定为 5，模板中没有提供修改入口；
 * 若未来需要调整，可直接改初始值或增加下拉。
 * rentalTool 保存工具引用，loadRentals 从中取 id 与名称。
 */
const rentalDrawerVisible = ref(false)
const rentalLoading = ref(false)
const rentalList = ref<RentalVO[]>([])
const rentalTotal = ref(0)
const rentalPage = ref(1)
const rentalSize = ref(5)
const rentalTool = ref<ToolVO | null>(null)

/**
 * 历史租赁总页数。
 * 与工具列表一致，用 Math.max(1, ...) 保证空数据时页码显示正常。
 */
 /*
 * rentalPages 的边界与工具列表一致：
 * total=0 时仍为 1，模板显示「第 1/1 页」而不是「第 1/0 页」。
 */
const rentalPages = computed(() => Math.max(1, Math.ceil(rentalTotal.value / rentalSize.value)))

/**
 * 加载当前工具的历史租赁。
 * 无 rentalTool 时直接返回；
 * 请求参数只包含 page/size，目标工具 id 放在路径中；
 * 成功后将 records/total 写入 rentalList/rentalTotal；
 * finally 复位 rentalLoading。
 */
 /*
 * loadRentals 的请求参数只有 page/size，
 * 工具 id 放在路径 /admin/tools/{id}/rentals 中。
 * 成功后将 records/total 写入响应式状态，模板自动更新表格与分页。
 * 如果请求期间用户关闭抽屉，响应仍会写入状态，但抽屉已卸载，不影响使用；
 * 下次 openRentals 会重新初始化，避免脏数据。
 * 错误由拦截器提示，finally 保证 rentalLoading 复位。
 * 租赁接口的分页字段与工具列表不同名（page/size），
 * 但语义一致；这里单独维护，避免与主列表分页耦合。
 */
async function loadRentals() {
  const tool = rentalTool.value
  if (!tool) return
  rentalLoading.value = true
  try {
    const data = await toolApi.rentals(tool.id, { page: rentalPage.value, size: rentalSize.value })
    rentalList.value = data.records
    rentalTotal.value = data.total
  } finally {
    rentalLoading.value = false
  }
}

/**
 * 打开历史租赁抽屉。
 * 入参 row：目标工具。
 * 重置页码为 1、清空旧数据与总数，显示抽屉后立即调用 loadRentals。
 * 重置数据可避免切换工具时短暂显示上一个工具的租赁单。
 */
 /*
 * openRentals 先重置再加载：
 * - rentalPage=1，避免沿用上一个工具的页码；
 * - rentalList=[] 与 rentalTotal=0，避免切换工具时闪现旧数据；
 * - 显示抽屉后再 loadRentals，用户能看到加载态。
 * 这里没有 await loadRentals，因为函数本身不是 async；
 * 加载状态由 rentalLoading 驱动，模板独立渲染。
 */
function openRentals(row: ToolVO) {
  rentalTool.value = row
  rentalPage.value = 1
  rentalList.value = []
  rentalTotal.value = 0
  rentalDrawerVisible.value = true
  loadRentals()
}

/**
 * 历史租赁翻页。
 * 边界校验：小于 1、大于总页数、或与当前页相同都忽略；
 * 合法时更新 rentalPage 并重新加载。
 */
 /*
 * gotoRentalPage 只处理上一页/下一页，不做省略号；
 * 边界判断与工具列表一致，防止请求越界页码。
 * 页码变化后重新加载，rentalLoading 会显示骨架屏。
 */
function gotoRentalPage(page: number) {
  if (page < 1 || page > rentalPages.value || page === rentalPage.value) return
  rentalPage.value = page
  loadRentals()
}

/**
 * 页面挂载后的初始化。
 * 只有超级管理员才加载列表：非管理员即使能进入路由，也不会请求管理端数据，
 * 从而避免无意义的 403 和空白错误提示。
 * 权限判断使用 isSuperAdmin.value，因为 onMounted 回调运行在普通 JS 上下文。
 */
 /*
 * onMounted 的依赖只有 isSuperAdmin：
 * - 如果角色在页面加载后才变为超级管理员（例如异步 fetchInfo），
 *   当前实现不会自动重新加载，需要刷新页面或手动触发；
 * - 这是管理端常见的简化，因为登录信息通常在路由进入前已就绪。
 * 若未来需要响应角色变化，可改用 watch(isSuperAdmin, ...)。
 * 非管理员不调用 loadList，因此不会产生 403 请求；
 * 但页面仍会渲染空表格与筛选栏，保持布局稳定。
 * 初始化只加载第一页数据，不预取后续页；
 * 用户翻页时再请求，减少首屏请求量。
 */
onMounted(() => {
  if (isSuperAdmin.value) loadList()
})
</script>

<template>
<!-- ========================================================================= -->
<!-- 模板总览：整页为 .page 容器，依次是标题栏、筛选栏、表格、弹窗/抽屉、预览层。 -->
<!-- 所有写操作入口都受 isSuperAdmin 控制；只读展示对管理员角色开放。 -->
<!-- 弹窗与抽屉通过 v-if 整体挂载/卸载，关闭即销毁，避免残留滚动锁与表单状态。 -->
<!-- 模板中的枚举中文名优先取后端返回的 xxxName 字段，缺失时回退到本地映射。 -->
<!-- 金额、时间、文件地址统一走 formatMoney/formatDateTime/resolveFileUrl。 -->
<!-- 表格使用原生 table 实现，样式由 scoped CSS 与全局设计变量共同定义。 -->
<!-- 分页、筛选、排序都是受控组件，值变化后由脚本中的处理函数发起请求。 -->
<!-- 图片点击不会跳转，只打开页面内预览遮罩，避免管理端离开当前上下文。 -->
<!-- 审核、状态、恢复三个弹窗结构一致：头部说明、表单体、底部取消/确认按钮。 -->
<!-- 日志与历史租赁使用右侧抽屉，适合承载较多内容且不打断表格浏览。 -->
<!-- 页面根容器：承载所有区块，scoped 样式以 .page 为作用域起点。 -->
<!-- 模板补充：以下注释按区块说明渲染条件、数据来源与交互结果。 -->
<!-- 所有弹窗/抽屉都使用 v-if 而非 v-show，关闭后不保留 DOM，避免隐藏表单被误提交。 -->
<!-- 模板中不直接写业务判断，复杂逻辑都收敛到 script 中的函数或计算属性。 -->
<!-- 根容器 .page 同时是 scoped 样式的命名空间，内部所有选择器都相对它生效。 -->
<!-- 页面没有额外的布局组件包裹，宽度与内边距由全局 .page 样式控制。 -->
<!-- 模板补充：页面所有交互都围绕列表行展开，弹窗与抽屉只是列表操作的延伸。 -->
<!-- 阅读模板时可按「标题 -> 筛选 -> 表格 -> 弹窗/抽屉 -> 预览」的顺序理解。 -->
  <div class="page">
<!-- 标题栏：eyebrow 副标题、页面标题与说明文案，右侧放置新增入口。 -->
<!-- 标题区使用 reveal 动画类，进入页面时做一次淡入上浮，提升首屏质感。 -->
<!-- 标题栏左侧是文案区，右侧是操作区；两者通过 flex 两端对齐。 -->
<!-- eyebrow 是英文小标题，用于强化后台系统的视觉层级，不参与业务。 -->
<!-- 副标题概括了本页支持的筛选维度，帮助新管理员快速理解能力。 -->
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">TOOL INVENTORY</p>
        <h1>工具管理</h1>
        <p class="sub">维护工具档案、使用指南与状态，支持按分类、状态、存放区与维修范围筛选。</p>
      </div>
<!-- 标题栏右侧操作区：仅超级管理员可见「新增工具」按钮，点击调用 openCreate。 -->
<!-- 操作区使用 v-if 控制新增按钮；非管理员看到的是空容器，不影响布局。 -->
<!-- 新增按钮使用 btn-primary 主按钮样式，配合 Plus 图标提高识别度。 -->
      <div class="actions">
<!-- 新增按钮：v-if 受 isSuperAdmin 控制；Plus 图标为 Element Plus 图标组件。 -->
        <button v-if="isSuperAdmin" class="btn btn-primary" @click="openCreate">
          <Plus class="ic sm" />新增工具
        </button>
      </div>
    </div>

<!-- 筛选栏：关键词、类别、状态、存放区、排序、租赁次数、维修范围与查询/重置按钮。 -->
<!-- 所有筛选项都是受控绑定；点击查询或输入框回车后由 handleSearch 回到第 1 页。 -->
<!-- 筛选条件不会在输入时立即请求，避免频繁调用后端；需显式点击查询或回车。 -->
<!-- 右侧「共 N 条」显示服务端返回的 total，便于管理员确认筛选结果规模。 -->
<!-- 筛选栏是一个可换行的 flex 容器，f-item 控制各控件的最小宽度。 -->
<!-- 筛选栏不包含审核状态筛选，审核状态只在表格中展示；如需扩展可在此追加下拉。 -->
<!-- 查询与重置按钮放在筛选控件之后，视觉上从输入区过渡到操作区。 -->
<!-- grow 占位元素把「共 N 条」推到最右侧，形成左右分组。 -->
    <div class="filterbar reveal">
<!-- 关键词搜索框：绑定 query.keyword，支持名称/编码/型号模糊搜索，回车即查询。 -->
<!-- 搜索图标是装饰性的，不绑定点击事件；实际查询由按钮或回车触发。 -->
<!-- 搜索框支持名称、编码、型号三个字段，具体匹配规则由后端实现。 -->
      <div class="f-search">
        <Search class="ic" />
        <input
          class="input"
          v-model="query.keyword"
          placeholder="搜索名称 / 编码 / 型号"
          @keyup.enter="handleSearch"
        />
      </div>
<!-- 类别下拉：选项来自 categoryOptions（TOOL_CATEGORY_LABELS 转换），空值表示全部。 -->
<!-- 类别下拉的 v-model 直接绑定 query.category，选择后不会立即请求。 -->
<!-- option 的 value 为空串时表示「全部类别」，cleanParams 会在发送前删除该字段。 -->
      <select class="select f-item" v-model="query.category">
        <option value="">全部类别</option>
        <option v-for="item in categoryOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 状态下拉：选项来自 statusOptions（TOOL_STATUS_LABELS 转换），空值表示全部。 -->
<!-- 状态下拉的选项顺序由 TOOL_STATUS_LABELS 的对象键顺序决定。 -->
<!-- 状态筛选与审核状态无关，这里筛选的是实物状态而非档案审核状态。 -->
      <select class="select f-item" v-model="query.status">
        <option value="">全部状态</option>
        <option v-for="item in statusOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 存放区域输入：绑定 query.storageArea，支持类似「A区-01」的模糊匹配，回车查询。 -->
<!-- 存放区域输入框使用 f-item 保持与其他筛选控件一致的宽度。 -->
<!-- 区域文案示例「A区-01」只是提示，后端按模糊匹配处理。 -->
      <input
        class="input f-item"
        v-model="query.storageArea"
        placeholder="存放区域（如 A区-01）"
        @keyup.enter="handleSearch"
      />
<!-- 排序下拉：默认创建时间、租赁次数最多、最近维修优先，值直接写入 query.orderBy。 -->
<!-- 排序下拉没有空选项，因为 default 本身就是「默认排序」。 -->
<!-- 选择排序方式后仍需点击查询，避免每次选择都发请求。 -->
      <select class="select f-item" v-model="query.orderBy">
        <option v-for="item in orderByOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 最少租赁次数下拉：1/5/10 三档；值为数字，空串表示不限，由 cleanParams 过滤。 -->
<!-- 租赁次数下拉的空选项文案是「租赁次数不限」，与「全部」区分语义。 -->
<!-- 选择具体档位后，query.minRentalCount 变为数字，模板 v-model 会自动转换。 -->
      <select class="select f-item" v-model="query.minRentalCount">
        <option value="">租赁次数不限</option>
        <option v-for="item in rentalCountOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 维修范围下拉：近 7 天、近 30 天、从未维修；由 repairScope 独立管理并在加载时翻译。 -->
<!-- 维修范围下拉与租赁次数下拉并排，二者都是可选筛选条件。 -->
<!-- 选择「从未维修」时，后端会忽略 repairedWithinDays 条件。 -->
      <select class="select f-item" v-model="repairScope">
        <option value="">维修时间不限</option>
        <option v-for="item in repairScopeOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
<!-- 查询按钮：调用 handleSearch，重置页码为 1 后重新请求列表。 -->
<!-- 查询按钮是 btn-sm 尺寸，与筛选控件高度更协调。 -->
<!-- 点击查询会重置页码；如果用户在第 3 页，结果会回到第 1 页。 -->
      <button class="btn btn-primary btn-sm" @click="handleSearch">
        <Search class="ic" />查询
      </button>
<!-- 重置按钮：调用 handleReset，清空全部筛选条件并立即重新查询。 -->
<!-- 重置按钮使用 ghost 样式，视觉权重低于查询，符合次要操作定位。 -->
<!-- 重置不会清空每页条数，用户选择的 10/20/50 会保留。 -->
      <button class="btn btn-ghost btn-sm" @click="handleReset">
        <Refresh class="ic" />重置
      </button>
      <span class="grow"></span>
<!-- 结果总数：显示服务端 total，不随当前页数据长度变化。 -->
<!-- 总数文案使用 tiny 字号，弱化展示但保持可读。 -->
      <span class="tiny">共 {{ total }} 条</span>
    </div>

<!-- 表格容器：外层负责边框/圆角/阴影，内层 table-scroll 负责横向滚动。 -->
<!-- table-wrap 负责外边框与圆角，table-scroll 负责横向滚动，职责分离。 -->
<!-- reveal 动画类在数据加载前后不会重新触发，避免表格闪烁。 -->
<!-- 表格区域的视觉权重高于筛选栏，但仍低于弹窗，符合「浏览为主、操作为辅」的定位。 -->
<!-- 表格在数据为空时仍保留表头，帮助用户理解页面结构并保持布局稳定。 -->
    <div class="table-wrap reveal">
<!-- 横向滚动层：列较多时保证小屏也能完整查看，不压缩列内容。 -->
<!-- 横向滚动容器在列宽总和超过页面宽度时出现滚动条，不压缩列内容。 -->
      <div class="table-scroll">
<!-- 工具列表主表格：表头固定 11 列，下面是加载态、空态或数据态三种 tbody。 -->
<!-- 表格使用原生 table，表头与表体共享列宽，避免自定义网格的对齐问题。 -->
<!-- 表格没有斑马纹，依赖行边框与 hover 样式区分行，视觉更克制。 -->
<!-- 表格列顺序与表单字段大致对应，但把「状态/审核」提前到显眼位置，便于日常管理。 -->
<!-- 操作列固定在最后一列，避免按钮夹在数据列中间打断阅读。 -->
        <table class="tbl">
<!-- 表头：工具、型号、类别、损坏程度、状态、押金、存放区、租借、最近维修、创建时间、操作。 -->
<!-- 「押金」右对齐，「租借」居中，「操作」预留 240px 以容纳多个行内按钮。 -->
<!-- 表头单元格默认左对齐，只有押金右对齐、租借与操作居中。 -->
<!-- 表头文案保持简短，详细含义由列内容和操作按钮体现。 -->
          <thead>
            <tr>
              <th>工具</th>
              <th>型号</th>
              <th>类别</th>
              <th>损坏程度</th>
              <th>状态</th>
              <th class="r">押金</th>
              <th>存放区域</th>
              <th class="c">租借</th>
              <th>最近维修</th>
              <th>创建时间</th>
              <th style="width: 240px">操作</th>
            </tr>
          </thead>
<!-- 加载态：显示 5 行骨架屏，colspan=11 跨越全部列，避免表格结构错位。 -->
<!-- 骨架屏行使用 skel 类，高度 16px，模拟一行文本的占位。 -->
<!-- 加载态与数据态互斥，避免同时渲染骨架和真实数据。 -->
          <tbody v-if="loading">
            <tr v-for="i in 5" :key="`sk-${i}`">
              <td colspan="11"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
<!-- 空态：无数据时展示图标、标题与引导文案，提醒管理员从右上角新增工具。 -->
<!-- 空态使用 Box 图标，与工具管理的语义呼应。 -->
<!-- 空态文案引导用户点击右上角新增，但该按钮对非管理员不可见。 -->
          <tbody v-else-if="!list.length">
            <tr>
              <td colspan="11">
                <div class="empty">
                  <Box class="e-ic" />
                  <h4>暂无工具数据</h4>
                  <p>点击右上角「新增工具」开始录入第一件工具。</p>
                </div>
              </td>
            </tr>
          </tbody>
<!-- 数据态：遍历 list，每行以工具 id 为 key；所有单元格按列顺序渲染。 -->
<!-- 数据态 tbody 的 v-else 与 loading/空态形成完整分支，不会出现无内容表格。 -->
<!-- 每行 key 使用 row.id，保证翻页/刷新时行复用正确。 -->
          <tbody v-else>
            <tr v-for="row in list" :key="row.id">
<!-- 工具列：缩略图 + 名称 + 编码 + 录入人，缩略图点击可预览，无图时显示占位符。 -->
<!-- 工具列是信息密度最高的一列：图片、名称、编码、录入人四层信息。 -->
<!-- 缩略图使用 rowImage(row) 计算，模板中调用两次（v-if 与 :src），函数无副作用。 -->
<!-- 编码使用 code 类等宽字体，便于与后端日志、工单号对照。 -->
<!-- 录入人来自 createdByName，缺失时显示 '—'，不影响布局。 -->
              <td>
                <div class="cell-tool">
                  <img
                    v-if="rowImage(row)"
                    :src="rowImage(row)"
                    class="thumb"
                    alt=""
                    @click="openPreview(rowImage(row))"
                  />
<!-- 无图占位：保持与缩略图相同的 44x44 尺寸，避免行高跳动。 -->
                  <span v-else class="thumb-ph">—</span>
                  <div>
                    <div class="cell-main">{{ row.name }}</div>
                    <div class="cell-sub code">{{ row.code }}</div>
                    <div class="cell-sub">录入：{{ row.createdByName || '—' }}</div>
                  </div>
                </div>
              </td>
<!-- 型号列：无型号时显示 '—'，保持表格对齐。 -->
<!-- 型号列可能很长，表格默认不截断；若需要可加全局省略样式。 -->
              <td>{{ row.model || '—' }}</td>
<!-- 类别列：优先后端 categoryName，缺失时用 TOOL_CATEGORY_LABELS 本地映射。 -->
<!-- 类别文案优先使用后端 categoryName，保证与后端字典一致。 -->
              <td>{{ row.categoryName || enumLabel(TOOL_CATEGORY_LABELS, row.category) }}</td>
<!-- 损坏程度列：badge 颜色由 damageLevelBadge 映射；文案优先后端 name。 -->
<!-- 损坏程度徽章使用 damageLevelBadge，未知等级回退 neutral。 -->
              <td>
                <span class="badge" :class="damageLevelBadge(row.damageLevel)">
                  {{ row.damageLevelName || enumLabel(DAMAGE_LEVEL_LABELS, row.damageLevel) }}
                </span>
              </td>
<!-- 状态列：主状态徽章 + 审核状态徽章 + 可选审核意见，三行信息集中展示。 -->
<!-- 主状态颜色由 toolStatusBadge 映射，AVAILABLE/RENTED/MAINTENANCE/SCRAPPED 各不相同。 -->
<!-- 审核状态使用 auditStatusTag + badgeClass 映射；auditStatus 为数值 0/1/2。 -->
<!-- 审核意见仅在存在 auditRemark 时展示，便于录入人直接看到退回原因。 -->
<!-- 状态列同时承载主状态与审核状态，是管理员最关注的列。 -->
<!-- dot 小圆点是状态徽章的视觉锚点，颜色由父级 badge 修饰类决定。 -->
<!-- 审核意见可能较长，cell-sub 允许换行，避免撑破表格。 -->
              <td>
                <span class="badge" :class="toolStatusBadge(row.status)">
                  <span class="dot"></span>{{ row.statusName || enumLabel(TOOL_STATUS_LABELS, row.status) }}
                </span>
                <div class="cell-sub">
                  审核：
                  <span class="badge" :class="badgeClass(auditStatusTag(row.auditStatus))">
                    {{ row.auditStatusName || auditStatusLabel(row.auditStatus) }}
                  </span>
                </div>
                <div v-if="row.auditRemark" class="cell-sub">意见：{{ row.auditRemark }}</div>
              </td>
<!-- 押金列：右对齐，formatMoney 输出 ¥xx.xx；空值显示 '—'。 -->
<!-- 押金使用 money 类等宽数字，右对齐后小数点容易纵向对齐。 -->
              <td class="r"><span class="money">{{ formatMoney(row.deposit) }}</span></td>
<!-- 存放区域列：无值时显示 '—'，便于管理员识别未归档工具。 -->
<!-- 存放区域为空时显示 '—'，提示该工具尚未归档。 -->
              <td>{{ row.storageArea || '—' }}</td>
<!-- 租借次数列：居中显示 rentalCount，缺失按 0 处理，用于评估使用热度。 -->
<!-- 租借次数是累计值，不是当前页的统计；后端在工具维度维护。 -->
              <td class="c num">{{ row.rentalCount ?? 0 }}</td>
<!-- 最近维修列：有 lastRepairTime 时格式化展示，否则显示灰色「未维修」。 -->
<!-- 最近维修时间来自 lastRepairTime，空值表示从未维修。 -->
<!-- muted 类弱化「未维修」文案，避免与真实日期抢视觉。 -->
              <td>
                <span v-if="row.lastRepairTime">{{ formatDateTime(row.lastRepairTime) }}</span>
                <span v-else class="muted">未维修</span>
              </td>
<!-- 创建时间列：formatDateTime 输出 YYYY-MM-DD HH:mm，空值显示 '—'。 -->
<!-- 创建时间使用 formatDateTime，统一格式为 YYYY-MM-DD HH:mm。 -->
              <td>{{ formatDateTime(row.createTime) }}</td>
<!-- 操作列：仅超级管理员可见，包含审核、编辑、状态/恢复、历史租赁、日志入口。 -->
<!-- 「通过」在 auditStatus !== 1 时显示；「退回」在 auditStatus !== 2 时显示。 -->
<!-- 已报废行隐藏「状态」按钮，改为显示「恢复」，避免进入无合法目标的弹窗。 -->
<!-- 所有按钮都是 link-btn 风格，点击后打开对应弹窗或抽屉，不直接发请求。 -->
<!-- 操作列宽度固定 240px，确保多个 link-btn 不会换行溢出。 -->
<!-- 非管理员时整个 row-actions 不渲染，单元格保持空白。 -->
<!-- 审核按钮的显示条件基于数值 auditStatus：1 已通过、2 需修改。 -->
<!-- 已通过时隐藏「通过」，需修改时隐藏「退回」，减少重复操作。 -->
<!-- 状态按钮与恢复按钮互斥：非报废显示状态，报废显示恢复。 -->
<!-- 历史租赁与日志按钮对所有状态都可用，便于追溯。 -->
              <td>
                <div v-if="isSuperAdmin" class="row-actions">
<!-- 审核通过入口：调用 openAudit(row, 'APPROVED')，打开审核弹窗并预选通过。 -->
<!-- 「通过」按钮使用 ok 绿色文字，表达正向操作。 -->
                  <button v-if="row.auditStatus !== 1" class="link-btn ok" @click="openAudit(row, 'APPROVED')">
                    通过
                  </button>
<!-- 审核退回入口：调用 openAudit(row, 'NEEDS_CHANGE')，退回时必须填写意见。 -->
<!-- 「退回」按钮使用默认链接色，避免过度强调负向操作。 -->
                  <button v-if="row.auditStatus !== 2" class="link-btn" @click="openAudit(row, 'NEEDS_CHANGE')">
                    退回
                  </button>
<!-- 编辑入口：openEdit 回填表单并打开新增/编辑弹窗，编辑时编码只读。 -->
<!-- 「编辑」按钮不区分审核状态，管理员可随时修正档案。 -->
                  <button class="link-btn" @click="openEdit(row)">编辑</button>
<!-- 状态入口：仅非报废状态显示；openStatusDialog 只列出状态机允许的目标。 -->
<!-- 「状态」按钮仅非报废行显示，点击后进入状态机弹窗。 -->
                  <button v-if="row.status !== 'SCRAPPED'" class="link-btn" @click="openStatusDialog(row)">
                    状态
                  </button>
<!-- 恢复入口：仅报废状态显示；openRestore 打开恢复弹窗，需填写恢复原因。 -->
<!-- 「恢复」按钮仅报废行显示，使用 ok 绿色表达恢复为可用的语义。 -->
                  <button v-else class="link-btn ok" @click="openRestore(row)">恢复</button>
<!-- 历史租赁入口：openRentals 打开右侧抽屉，分页展示该工具的租赁单。 -->
<!-- 「历史租赁」按钮打开只读抽屉，不改变任何数据。 -->
                  <button class="link-btn" @click="openRentals(row)">历史租赁</button>
<!-- 日志入口：openLogs 打开右侧抽屉，展示该工具的操作时间线。 -->
<!-- 「日志」按钮打开时间线抽屉，适合审计与问题排查。 -->
                  <button class="link-btn" @click="openLogs(row)">日志</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
<!-- 表格底栏：左侧总数与当前页，右侧每页条数选择与页码按钮。 -->
<!-- 底栏在表格下方，与表格共享外边框，形成完整的卡片结构。 -->
      <div class="table-foot">
<!-- 分页摘要：显示总条数、当前页与总页数，总页数由 totalPages 计算。 -->
<!-- 分页摘要同时展示总条数与页码，信息量比单独页码更完整。 -->
        <span class="tiny">共 {{ total }} 条 · 第 {{ query.page }}/{{ totalPages }} 页</span>
        <div class="flex">
<!-- 每页条数选择：10/20/50；变更后 handleSizeChange 会回到第 1 页重新加载。 -->
<!-- 每页条数选择的高度被内联固定为 32px，与底栏高度对齐。 -->
          <select class="select" style="width: 116px; height: 32px" v-model="query.size" @change="handleSizeChange">
            <option :value="10">10 条/页</option>
            <option :value="20">20 条/页</option>
            <option :value="50">50 条/页</option>
          </select>
<!-- 页码区：上一页按钮、带省略号的页码序列、下一页按钮。 -->
<!-- 首尾页按钮在边界处 disabled；页码与 '…' 由 pageNumbers 计算属性生成。 -->
<!-- 上一页/下一页按钮使用字符 ‹ ›，不依赖图标组件，减少 DOM。 -->
<!-- 页码按钮的 active 类由当前页决定，省略号按钮始终 disabled。 -->
          <div class="pager">
            <button :disabled="query.page <= 1" @click="gotoPage(query.page - 1)">‹</button>
            <template v-for="p in pageNumbers" :key="p">
              <button v-if="p === '…'" disabled>…</button>
              <button v-else :class="{ active: p === query.page }" @click="gotoPage(p)">{{ p }}</button>
            </template>
            <button :disabled="query.page >= totalPages" @click="gotoPage(query.page + 1)">›</button>
          </div>
        </div>
      </div>
    </div>

<!-- 新增 / 编辑弹窗：点击遮罩空白处可关闭；wide 样式提供两列表单布局。 -->
<!-- 新增/编辑弹窗使用 wide 宽度，容纳两列表单与步骤编辑器。 -->
<!-- 遮罩点击关闭只作用于 .mask 自身，点击弹窗内部不会触发。 -->
    <!-- 新增 / 编辑 -->
    <div class="mask show" v-if="dialogVisible" @click.self="dialogVisible = false">
<!-- 弹窗主体：头部标题/说明、表单网格、隐藏文件输入、底部取消/保存按钮。 -->
<!-- dlg 结构分为 head/body/foot 三段，body 可滚动，head/foot 固定。 -->
      <div class="dlg wide">
<!-- 弹窗头部：根据 editingId 动态显示「编辑工具」或「新增工具」。 -->
<!-- 说明文案提示工具编码按类别自动生成，编辑时不可修改。 -->
<!-- 头部标题是动态的：editingId 有值时显示编辑，否则显示新增。 -->
<!-- 副标题说明编码生成规则，减少管理员对只读编码字段的疑问。 -->
        <div class="dlg-head">
          <div>
            <h3>{{ editingId ? '编辑工具' : '新增工具' }}</h3>
            <p class="d-sub">工具编码按类别自动生成，编辑时不可修改</p>
          </div>
<!-- 关闭按钮：直接置 dialogVisible=false，丢弃未保存的本地修改。 -->
<!-- 关闭按钮使用 Close 图标，点击直接丢弃未保存修改，不弹确认。 -->
          <button class="dlg-x" @click="dialogVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 表单体：包含基础档案、使用指南分步编辑器、工具图片上传三个区域。 -->
<!-- dlg-body 内包含一个 form-grid 和两个隐藏 input；隐藏 input 不参与布局。 -->
        <div class="dlg-body">
<!-- 表单网格：两列布局，full 类字段（使用指南、工具图片）独占整行。 -->
<!-- 每个 field 通过 is-err 类在对应 formErrors 存在时显示红框与错误文案。 -->
<!-- form-grid 的字段顺序即用户填写顺序：名称、型号、编码、类别、成色、押金... -->
<!-- 错误类 is-err 由 formErrors 对应字段的布尔值控制，红框与文案同步出现。 -->
          <div class="form-grid">
<!-- 工具名称：必填，maxlength=50；错误信息来自 formErrors.name。 -->
<!-- 名称是唯一同时参与搜索与展示的字段，因此放在第一行第一列。 -->
            <div class="field" :class="{ 'is-err': formErrors.name }">
              <label><span class="req">*</span>工具名称</label>
              <input class="input" v-model="form.name" maxlength="50" placeholder="请输入工具名称" />
              <p class="err">{{ formErrors.name }}</p>
            </div>
<!-- 型号：选填，maxlength=50；空值保存为空串。 -->
<!-- 型号为选填，但建议填写，便于用户端区分同名称的不同规格。 -->
            <div class="field">
              <label>型号</label>
              <input class="input" v-model="form.model" maxlength="50" placeholder="请输入型号" />
            </div>
<!-- 工具编码：只读展示；编辑态显示后端编码，新增态提示保存后生成。 -->
<!-- 编码字段始终 disabled，新增时显示提示文案，编辑时显示真实编码。 -->
<!-- 该字段使用 :value 而不是 v-model，避免用户输入被写回表单。 -->
            <div class="field">
              <label>工具编码</label>
              <input class="input" :value="editingId ? editingCode : '保存后按类别自动生成'" disabled />
            </div>
<!-- 类别：必填下拉，选项来自 categoryOptions；默认 WX，错误信息来自 formErrors.category。 -->
<!-- 类别影响编码生成规则，因此新增后不建议频繁修改。 -->
            <div class="field" :class="{ 'is-err': formErrors.category }">
              <label><span class="req">*</span>类别</label>
              <select class="select" v-model="form.category">
                <option v-for="item in categoryOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
              <p class="err">{{ formErrors.category }}</p>
            </div>
<!-- 损坏程度：必填下拉，选项来自 damageLevelOptions；默认 NEW。 -->
<!-- 损坏程度是实物属性，与审核状态、工具状态都不相同。 -->
            <div class="field" :class="{ 'is-err': formErrors.damageLevel }">
              <label><span class="req">*</span>损坏程度</label>
              <select class="select" v-model="form.damageLevel">
                <option v-for="item in damageLevelOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
              <p class="err">{{ formErrors.damageLevel }}</p>
            </div>
<!-- 押金：必填数字，min=0、step=0.01，v-model.number 保证提交数字；错误来自 formErrors.deposit。 -->
<!-- 押金输入使用 type=number，浏览器会提供数字键盘与步进按钮。 -->
<!-- v-model.number 会把输入转成数字，但清空时仍可能是空串，需 depositInvalid 兜底。 -->
            <div class="field" :class="{ 'is-err': formErrors.deposit }">
              <label><span class="req">*</span>押金（元）</label>
              <input
                class="input"
                type="number"
                min="0"
                step="0.01"
                v-model.number="form.deposit"
                placeholder="0.00"
              />
              <p class="err">{{ formErrors.deposit }}</p>
            </div>
<!-- 存放区域：选填，maxlength=50，placeholder 给出 A区-01 示例。 -->
<!-- 存放区域用于线下找货，建议使用统一的区域编码规范。 -->
            <div class="field">
              <label>存放区域</label>
              <input class="input" v-model="form.storageArea" maxlength="50" placeholder="如 A区-01" />
            </div>
<!-- 采购日期：原生 date 输入，空值提交 null；仅用于档案展示，不参与状态机。 -->
<!-- 采购日期使用原生 date 控件，值格式为 YYYY-MM-DD；清空时提交 null。 -->
            <div class="field">
              <label>采购日期</label>
              <input class="input" type="date" v-model="form.purchaseDate" />
            </div>
<!-- 供应商：选填，maxlength=50，用于采购来源追溯。 -->
<!-- 供应商为选填，便于后续采购对账与质保追溯。 -->
            <div class="field">
              <label>供应商</label>
              <input class="input" v-model="form.supplier" maxlength="50" placeholder="请输入供应商" />
            </div>
<!-- 状态：编辑时可直接选择目标状态；选项来自 statusOptions，默认 AVAILABLE。 -->
<!-- 状态字段在编辑时可选，但正常流程建议通过状态机操作而非直接改表单。 -->
            <div class="field">
              <label>状态</label>
              <select class="select" v-model="form.status">
                <option v-for="item in statusOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </div>

<!-- 使用指南分区：独占整行，使用分步骤编辑器维护文字与配图，最终序列化为 JSON。 -->
<!-- 使用指南从纯文本升级为结构化步骤后，支持图文混排与顺序调整。 -->
            <div class="field full">
              <label>使用指南</label>
<!-- 指南编辑器容器：纵向排列每个步骤块，底部是新增步骤按钮与提示。 -->
<!-- guide-editor 的步骤块使用 v-for，key 使用 index；调整顺序时 index 会变化。 -->
<!-- 由于步骤没有稳定 id，使用 index 作为 key 可能导致输入框状态复用； -->
<!-- 当前通过受控 v-model 保证数据正确，若未来出现输入错位可改用临时 id。 -->
              <div class="guide-editor">
<!-- 步骤块：显示步骤序号、上移/下移/删除操作、文字输入与配图上传。 -->
<!-- 步骤顺序即序列化后的数组顺序，用户端会按此顺序展示。 -->
<!-- 每个步骤块包含头部操作、文字输入、图片上传三部分，结构一致便于理解。 -->
                <div v-for="(step, index) in guideSteps" :key="index" class="g-step-block">
                  <div class="g-step-head">
<!-- 步骤编号：index + 1，仅用于展示，不参与提交数据。 -->
<!-- 步骤序号从 1 开始，仅用于展示，不随步骤删除而重新编号存储。 -->
                    <span class="g-num">步骤 {{ index + 1 }}</span>
<!-- 步骤操作区：上移、下移、删除；首尾与仅剩一步时对应按钮禁用。 -->
<!-- 操作按钮在首尾与仅剩一步时禁用，禁用态通过 CSS 降低透明度。 -->
                    <div class="g-step-actions">
<!-- 上移：moveStep(index, -1)；index=0 时禁用，避免越界。 -->
<!-- 上移按钮使用 ArrowUp 图标，禁用条件为 index === 0。 -->
                      <button class="link-btn" :disabled="index === 0" @click="moveStep(index, -1)">
                        <ArrowUp class="ic sm" />上移
                      </button>
<!-- 下移：moveStep(index, 1)；最后一步时禁用。 -->
<!-- 下移按钮使用 ArrowDown 图标，禁用条件为最后一步。 -->
                      <button
                        class="link-btn"
                        :disabled="index === guideSteps.length - 1"
                        @click="moveStep(index, 1)"
                      >
                        <ArrowDown class="ic sm" />下移
                      </button>
<!-- 删除：removeStep(index)；仅剩一个步骤时禁用，保证编辑器非空。 -->
<!-- 删除按钮使用 Delete 图标与 danger 样式，禁用条件为只剩一步。 -->
                      <button
                        class="link-btn danger"
                        :disabled="guideSteps.length === 1"
                        @click="removeStep(index)"
                      >
                        <Delete class="ic sm" />删除
                      </button>
                    </div>
                  </div>
<!-- 步骤文字：最多 500 字，只用文字或只用图片均可；两者都空时序列化会过滤该步骤。 -->
<!-- 步骤文字使用 textarea，rows=2 提供适中高度，maxlength=500 与后端限制对齐。 -->
<!-- placeholder 提示填写操作方法与注意事项，降低录入质量差异。 -->
                  <textarea
                    class="textarea"
                    v-model="step.text"
                    rows="2"
                    maxlength="500"
                    placeholder="这一步怎么操作、需要注意什么"
                  ></textarea>
<!-- 步骤配图区：有图时展示 96x72 预览并可点击放大，同时提供更换/移除按钮。 -->
<!-- 步骤配图不是必填，用户可以只写文字；图片上传后立即写入 imageUrl。 -->
                  <div class="g-step-image">
<!-- 步骤图片预览：resolveFileUrl 处理相对 key，点击调用 openPreview 放大。 -->
<!-- 步骤图片预览尺寸为 96x72，与上传按钮并排，视觉上形成「图 + 操作」组合。 -->
                    <img
                      v-if="step.imageUrl"
                      :src="resolveFileUrl(step.imageUrl)"
                      class="g-step-preview"
                      alt=""
                      @click="openPreview(resolveFileUrl(step.imageUrl))"
                    />
<!-- 上传/更换步骤图片：pickStepImage(index) 记录下标并触发共享文件输入。 -->
<!-- 任意步骤上传中时禁用所有上传按钮，避免共享 input 的下标被覆盖。 -->
<!-- 上传按钮的 loading 类由 stepUploading === index 控制，只高亮当前步骤。 -->
<!-- disabled 条件为 stepUploading !== null，即任意步骤上传中都会禁用其他上传。 -->
<!-- 按钮文案根据是否已有图片在「上传图片」与「更换图片」之间切换。 -->
                    <button
                      class="btn btn-outline btn-sm"
                      :class="{ loading: stepUploading === index }"
                      :disabled="stepUploading !== null"
                      @click="pickStepImage(index)"
                    >
                      <Upload class="ic sm" />{{ step.imageUrl ? '更换图片' : '上传图片' }}
                    </button>
<!-- 移除步骤图片：直接把当前步骤的 imageUrl 置空，保存后同步后端。 -->
<!-- 移除图片按钮只在有图时显示，点击后立即清空，不弹确认。 -->
                    <button v-if="step.imageUrl" class="link-btn danger" @click="step.imageUrl = ''">
                      移除图片
                    </button>
                  </div>
                </div>
<!-- 指南编辑器底部：新增步骤按钮与填写说明。 -->
<!-- 编辑器底部与步骤块之间保持间距，新增按钮使用 outline 次要样式。 -->
                <div class="guide-editor-footer">
<!-- 新增步骤：addStep 向 guideSteps 追加一个空步骤。 -->
<!-- 新增步骤不会自动聚焦，用户可以继续点击新增或直接填写。 -->
                  <button class="btn btn-outline btn-sm" @click="addStep">
                    <Plus class="ic sm" />新增步骤
                  </button>
<!-- 提示文案：说明每一步可只填文字、只放图片或图文都有，用户端按顺序展示。 -->
<!-- 提示文案明确了「只文字、只图片、图文都有」三种合法形态，减少误以为必填的困惑。 -->
                  <span class="muted">每一步可只填文字、只放图片，或图文都有；用户端会按步骤顺序展示</span>
                </div>
<!-- 旧版格式提示：legacyGuide=true 时出现，说明保存后会自动转换为分步骤 JSON。 -->
<!-- 旧版提示使用 InfoFilled 图标与信息蓝底色，属于非阻断式提示。 -->
<!-- 该提示只在 parseGuide 判定 legacy=true 时出现，保存后消失。 -->
                <div v-if="legacyGuide" class="legacy-note">
                  <InfoFilled class="ic sm" />
                  <span>检测到旧版纯文本说明，保存后将自动转换为分步骤格式</span>
                </div>
              </div>
            </div>

<!-- 工具图片分区：独占整行，支持多图上传、预览与移除。 -->
<!-- 工具图片支持多张，第一张通常作为列表缩略图。 -->
            <div class="field full">
              <label>工具图片</label>
<!-- 图片上传容器：上方是已上传图片列表，下方是上传按钮与格式提示。 -->
<!-- image-upload 是纵向布局：图片列表在上，上传按钮与提示在下。 -->
              <div class="image-upload">
<!-- 图片列表：v-for 遍历 imageUrls，key 使用 url+index，兼容重复 URL。 -->
<!-- 图片列表只在 imageUrls.length > 0 时渲染，避免空容器占位。 -->
                <div v-if="form.imageUrls.length" class="image-list">
                  <div v-for="(url, index) in form.imageUrls" :key="`${url}-${index}`" class="image-item">
<!-- 单张图片：resolveFileUrl 后展示，点击打开预览遮罩。 -->
<!-- 图片项的 key 使用 url+index，兼容同一 URL 被重复添加的情况。 -->
                    <img
                      :src="resolveFileUrl(url)"
                      class="image-preview"
                      alt=""
                      @click="openPreview(resolveFileUrl(url))"
                    />
<!-- 移除图片：removeImage(index) 从 imageUrls 中 splice，仅改本地表单。 -->
<!-- 删除按钮绝对定位在图片右上角，点击只删除本地数组项。 -->
                    <button class="image-remove" title="移除图片" @click="removeImage(index)">
                      <Delete class="ic sm" />
                    </button>
                  </div>
                </div>
                <div>
<!-- 上传主图按钮：pickImage 触发隐藏 input；uploading 时显示 loading 并禁用。 -->
<!-- 上传主图按钮在 uploading 时显示 loading 并禁用，防止并发上传。 -->
<!-- 与步骤图不同，主图上传没有互斥下标，因为结果只是 push 到数组。 -->
                  <button
                    class="btn btn-outline btn-sm"
                    :class="{ loading: uploading }"
                    :disabled="uploading"
                    @click="pickImage()"
                  >
                    <Upload class="ic sm" />上传图片
                  </button>
<!-- 上传提示：说明支持 jpg/png 等格式且可上传多张。 -->
<!-- 提示文案说明格式与数量，具体大小限制由后端上传接口决定。 -->
                  <div class="hint">支持 jpg、png 等图片格式，可上传多张。</div>
                </div>
              </div>
            </div>
          </div>

<!-- 隐藏文件输入（主图）：accept=image/*，change 后由 onImageChange 上传并追加 URL。 -->
<!-- 主图 input 的 accept 限制为 image/*，但用户仍可通过「所有文件」绕过，后端需校验。 -->
<!-- change 事件在选完文件后触发，onImageChange 会清空 value 以支持重复选择。 -->
          <input ref="imageFileRef" type="file" accept="image/*" style="display: none" @change="onImageChange" />
<!-- 隐藏文件输入（步骤图）：所有步骤共用，change 后按 stepUploadIndex 写回对应步骤。 -->
<!-- 步骤图 input 与主图 input 分离，避免两个上传流程互相干扰。 -->
          <input ref="stepFileRef" type="file" accept="image/*" style="display: none" @change="onStepFileChange" />
        </div>
<!-- 弹窗底部：取消直接关闭，保存调用 handleSubmit；saving 时按钮 loading 并禁用。 -->
<!-- 底部按钮顺序为「取消」在左、「保存」在右，符合表单弹窗的常见习惯。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="dialogVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: saving }" :disabled="saving" @click="handleSubmit">
            保存
          </button>
        </div>
      </div>
    </div>

<!-- 状态更新弹窗：窄弹窗，只展示状态机允许的目标状态，并做二次确认。 -->
<!-- 状态更新弹窗只承载状态机流转，不包含其他字段编辑。 -->
    <!-- 状态更新 -->
<!-- 状态弹窗遮罩：点击空白处关闭；不提供遮罩点击提交，避免误操作。 -->
<!-- 状态弹窗同样支持点击遮罩关闭；关闭不会提交任何变更。 -->
    <div class="mask show" v-if="statusDialogVisible" @click.self="statusDialogVisible = false">
<!-- 状态弹窗主体：头部展示工具名与当前状态，表单选择新状态。 -->
<!-- narrow 宽度适合单字段表单，避免弹窗过宽显得空洞。 -->
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>更新工具状态</h3>
<!-- 弹窗副标题：工具名 + 当前状态中文名；目标为空时显示 '—'。 -->
<!-- 副标题中的当前状态使用 enumLabel 映射，缺失时显示 '—'。 -->
            <p class="d-sub">
              {{ statusTarget?.name || '—' }} · 当前
              {{ statusTarget ? enumLabel(TOOL_STATUS_LABELS, statusTarget.status) : '—' }}
            </p>
          </div>
          <button class="dlg-x" @click="statusDialogVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 状态表单体：新状态下拉与提示文案。 -->
<!-- 状态弹窗体只有一个字段，因此没有使用 form-grid。 -->
        <div class="dlg-body">
<!-- 新状态：选项来自 statusChoices，仅包含 STATUS_TRANSITIONS 允许的目标。 -->
<!-- 新状态下拉只包含合法目标；若当前状态无目标，弹窗不会打开。 -->
          <div class="field">
            <label>新状态</label>
            <select class="select" v-model="statusValue">
              <option v-for="item in statusChoices" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
<!-- 提示：强调只展示合法流转，已报废工具需走「恢复」。 -->
<!-- 提示文案明确「已报废请使用恢复」，避免用户在状态弹窗中寻找不存在选项。 -->
          <p class="hint">仅展示状态机允许的流转；已报废工具请使用「恢复」。</p>
        </div>
<!-- 状态弹窗底部：取消关闭；确认更新调用 submitStatus（内部先二次确认）。 -->
<!-- 底部确认按钮文案为「确认更新」，与标题「更新工具状态」呼应。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="statusDialogVisible = false">取消</button>
          <button
            class="btn btn-primary"
            :class="{ loading: statusSaving }"
            :disabled="statusSaving"
            @click="submitStatus"
          >
            确认更新
          </button>
        </div>
      </div>
    </div>

<!-- 操作日志抽屉：右侧滑出，宽 520px，展示单工具的操作时间线。 -->
<!-- 操作日志抽屉宽度 520px，适合展示时间线文本。 -->
    <!-- 操作日志抽屉 -->
<!-- 日志抽屉遮罩：点击空白处关闭；抽屉内点击不会冒泡关闭。 -->
<!-- 抽屉遮罩使用 drawer-mask，点击遮罩关闭；抽屉内部点击不会冒泡到遮罩。 -->
    <div class="drawer-mask show" v-if="logDrawerVisible" @click.self="logDrawerVisible = false">
      <div class="drawer" style="width: 520px">
        <div class="dlg-head">
          <div>
            <h3>操作日志</h3>
            <p class="d-sub">{{ logToolName }}</p>
          </div>
          <button class="dlg-x" @click="logDrawerVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 日志抽屉主体：加载中显示 4 条骨架屏，否则显示时间线或空态。 -->
<!-- 日志体在加载时显示骨架屏，加载完成后根据 logs.length 显示时间线或空态。 -->
        <div class="dlg-body">
<!-- 加载态：4 条高度 18px 的骨架条，模拟日志行。 -->
<!-- 骨架屏条数与常见日志量匹配，避免加载时页面高度剧烈跳动。 -->
          <template v-if="logLoading">
            <div v-for="i in 4" :key="i" class="skel" style="height: 18px; margin-bottom: 14px"></div>
          </template>
<!-- 时间线：每条日志展示动作、操作人、时间、状态变化与备注/详情。 -->
<!-- done 类用于除最后一条外的日志，绘制连接线，形成时间线视觉效果。 -->
<!-- 时间线的 done 类用于非最后一条，绘制向下的连接线。 -->
<!-- 日志顺序由后端决定；前端不重新排序，保证与审计记录一致。 -->
          <ul v-else-if="logs.length" class="timeline">
            <li v-for="(log, index) in logs" :key="log.id" :class="{ done: index < logs.length - 1 }">
<!-- 日志动作：由 TOOL_LOG_ACTION_LABELS 映射（新增/编辑/审核/状态变更/恢复等）。 -->
<!-- 动作文案来自 TOOL_LOG_ACTION_LABELS，未知动作会原样显示。 -->
              <div class="tl-t">
                {{ enumLabel(TOOL_LOG_ACTION_LABELS, log.action) }}
<!-- 操作人徽章：仅 operatorName 存在时显示，便于审计追责。 -->
<!-- 操作人徽章使用 neutral 灰色，弱化但不隐藏审计信息。 -->
                <span v-if="log.operatorName" class="badge neutral">操作人 {{ log.operatorName }}</span>
              </div>
<!-- 日志时间：formatDateTime 输出到分钟，空值显示 '—'。 -->
<!-- 时间使用 formatDateTime，精确到分钟，足够审计定位。 -->
              <div class="tl-time">{{ formatDateTime(log.createTime) }}</div>
<!-- 状态变化：fromStatus -> toStatus；两侧都可能为空，logStatusLabel 兼容状态与审核枚举。 -->
<!-- 状态变化使用箭头文本，from 或 to 为空时由 logStatusLabel 显示 '—'。 -->
              <div v-if="log.fromStatus || log.toStatus" class="tl-d">
                {{ logStatusLabel(log.fromStatus) }} → {{ logStatusLabel(log.toStatus) }}
              </div>
<!-- 备注：优先展示 remark；没有 remark 且没有状态变化时回退到 detail。 -->
<!-- 备注与详情互斥展示：有备注优先备注，没有状态变化时回退详情。 -->
              <div v-if="log.remark" class="tl-d">备注：{{ log.remark }}</div>
              <div v-else-if="!log.fromStatus && !log.toStatus" class="tl-d">{{ log.detail || '—' }}</div>
            </li>
          </ul>
<!-- 日志空态：没有日志时显示文档图标与「暂无状态日志」。 -->
<!-- 日志空态文案为「暂无状态日志」，与抽屉标题「操作日志」保持语义一致。 -->
          <div v-else class="empty">
            <Document class="e-ic" />
            <h4>暂无状态日志</h4>
          </div>
        </div>
      </div>
    </div>

<!-- 审核弹窗：窄弹窗，通过/退回共用；退回时审核意见必填。 -->
<!-- 审核弹窗只做通过/退回两种结论，不支持批量审核。 -->
    <!-- 审核 -->
<!-- 审核弹窗遮罩：点击空白处关闭，放弃本次审核操作。 -->
<!-- 审核遮罩点击关闭会放弃未提交的审核意见，不弹二次确认。 -->
    <div class="mask show" v-if="auditVisible" @click.self="auditVisible = false">
<!-- 审核弹窗主体：头部展示工具名与当前审核状态，表单选择结论并填写意见。 -->
<!-- 审核弹窗头部展示当前审核状态，帮助管理员判断本次操作是否必要。 -->
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>工具审核</h3>
<!-- 审核弹窗副标题：工具名 + 当前审核状态；优先 auditStatusName，缺失时本地映射。 -->
<!-- 副标题优先使用后端 auditStatusName，保证与后端展示一致。 -->
            <p class="d-sub">
              {{ auditTarget?.name || '—' }} · 当前审核状态
              {{ auditTarget ? (auditTarget.auditStatusName || auditStatusLabel(auditTarget.auditStatus)) : '—' }}
            </p>
          </div>
          <button class="dlg-x" @click="auditVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 审核表单体：审核结果下拉与审核意见文本域。 -->
<!-- 审核体包含结论下拉与意见文本域，意见长度限制 500 字。 -->
        <div class="dlg-body">
<!-- 审核结果：APPROVED 通过 / NEEDS_CHANGE 退回修改，绑定 auditForm.auditStatus。 -->
<!-- 结论下拉的 value 是字符串枚举，提交时直接进入 ToolAuditDTO。 -->
          <div class="field">
            <label><span class="req">*</span>审核结果</label>
            <select class="select" v-model="auditForm.auditStatus">
              <option value="APPROVED">通过</option>
              <option value="NEEDS_CHANGE">退回修改</option>
            </select>
          </div>
<!-- 审核意见：退回时标签带必填星号，placeholder 提示填写需修改内容；通过时选填。 -->
<!-- 意见标签的必填星号只在 NEEDS_CHANGE 时显示，通过时意见为选填。 -->
<!-- placeholder 根据结论动态变化，退回时提示填写需修改内容。 -->
          <div class="field">
            <label><span v-if="auditForm.auditStatus === 'NEEDS_CHANGE'" class="req">*</span>审核意见</label>
            <textarea
              v-model="auditForm.remark"
              class="textarea"
              rows="3"
              maxlength="500"
              :placeholder="auditForm.auditStatus === 'NEEDS_CHANGE' ? '请填写需修改的内容' : '选填'"
            ></textarea>
          </div>
        </div>
<!-- 审核弹窗底部：取消关闭；提交审核调用 submitAudit，auditSaving 时 loading 并禁用。 -->
<!-- 审核底部确认按钮文案为「提交审核」，提交期间禁用防重复。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="auditVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: auditSaving }" :disabled="auditSaving" @click="submitAudit">
            提交审核
          </button>
        </div>
      </div>
    </div>

<!-- 报废恢复弹窗：仅超级管理员可打开，要求填写恢复原因。 -->
<!-- 恢复弹窗只对已报废工具开放，入口在操作列的「恢复」按钮。 -->
    <!-- 报废恢复 -->
<!-- 恢复弹窗遮罩：点击空白处关闭，不执行恢复。 -->
<!-- 恢复遮罩点击关闭不会执行恢复，恢复原因也不会保存。 -->
    <div class="mask show" v-if="restoreVisible" @click.self="restoreVisible = false">
<!-- 恢复弹窗主体：头部展示工具名与编码，主体包含风险提示与原因输入。 -->
<!-- 恢复弹窗头部展示工具名与编码，帮助管理员确认目标工具。 -->
      <div class="dlg narrow">
        <div class="dlg-head">
          <div>
            <h3>恢复已报废工具</h3>
            <p class="d-sub">{{ restoreTarget?.name }} · {{ restoreTarget?.code }}</p>
          </div>
          <button class="dlg-x" @click="restoreVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 恢复弹窗表单体：notice 提示 + 恢复原因文本域。 -->
<!-- 恢复体包含风险提示与原因输入，风险提示不是必读确认，但文案明确。 -->
        <div class="dlg-body">
<!-- 风险提示：恢复前确认无活动租赁与未完成报修，恢复后状态变为「可借用」。 -->
<!-- 风险提示涵盖活动租赁、未完成报修与恢复后的目标状态三点。 -->
          <div class="notice-sm">
            <InfoFilled class="ic" />
            <span>恢复前请确认工具无活动租赁与未完成报修，恢复后将变为「可借用」。</span>
          </div>
<!-- 恢复原因：必填，最多 500 字，trim 后为空会被 submitRestore 拦截。 -->
<!-- 恢复原因输入使用 textarea，maxlength=500，必填校验在 submitRestore 中完成。 -->
          <div class="field">
            <label><span class="req">*</span>恢复原因</label>
            <textarea
              v-model="restoreReason"
              class="textarea"
              rows="3"
              maxlength="500"
              placeholder="请填写恢复原因"
            ></textarea>
          </div>
        </div>
<!-- 恢复弹窗底部：取消关闭；确认恢复调用 submitRestore，restoreSaving 时禁用。 -->
<!-- 恢复底部确认按钮文案为「确认恢复」，提交期间禁用。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="restoreVisible = false">取消</button>
          <button class="btn btn-primary" :class="{ loading: restoreSaving }" :disabled="restoreSaving" @click="submitRestore">
            确认恢复
          </button>
        </div>
      </div>
    </div>

<!-- 历史租赁抽屉：右侧滑出，宽 680px，分页展示该工具的租赁单。 -->
<!-- 历史租赁抽屉宽度 680px，容纳五列表格与分页。 -->
    <!-- 历史租赁 -->
<!-- 租赁抽屉遮罩：点击空白处关闭；不影响表格当前筛选与分页。 -->
<!-- 租赁遮罩点击关闭；关闭后 rentalTool 仍保留，但下次打开会覆盖。 -->
    <div class="drawer-mask show" v-if="rentalDrawerVisible" @click.self="rentalDrawerVisible = false">
      <div class="drawer" style="width: 680px">
        <div class="dlg-head">
          <div>
            <h3>历史租赁</h3>
            <p class="d-sub">{{ rentalTool?.name }} · {{ rentalTool?.code }}</p>
          </div>
          <button class="dlg-x" @click="rentalDrawerVisible = false"><Close class="ic sm" /></button>
        </div>
<!-- 租赁抽屉主体：加载态、数据表格或空态三选一。 -->
<!-- 租赁体在加载、有数据、无数据三种状态间切换，避免空白抽屉。 -->
        <div class="dlg-body">
<!-- 加载态：4 条骨架条，与日志抽屉保持一致的反馈节奏。 -->
<!-- 加载骨架与日志抽屉一致，保持跨抽屉的反馈一致性。 -->
          <template v-if="rentalLoading">
            <div v-for="i in 4" :key="i" class="skel" style="height: 18px; margin-bottom: 14px"></div>
          </template>
<!-- 数据态：表格列包括租赁单号、租用人、租期、押金、状态。 -->
<!-- 有数据时渲染只读表格；表格不使用固定列宽，由内容自然撑开。 -->
          <template v-else-if="rentalList.length">
<!-- 租赁表格：只读展示，不提供归还/取消等操作，避免与租赁管理页职责重叠。 -->
<!-- 租赁表格复用全局 tbl 样式，与主列表保持视觉一致。 -->
            <table class="tbl">
              <thead>
<!-- 租赁表头：单号、租用人、租期、押金（右对齐）、状态（居中）。 -->
<!-- 表头五列覆盖追溯所需的最小信息集，不展示费用与支付明细。 -->
                <tr>
                  <th>租赁单号</th>
                  <th>租用人</th>
                  <th>租期</th>
                  <th class="r">押金</th>
                  <th class="c">状态</th>
                </tr>
              </thead>
              <tbody>
<!-- 租赁行：以租赁单 id 为 key；每行展示该工具的一次历史租赁。 -->
<!-- 每行以租赁单 id 为 key；单号 orderNo 用于对账与检索。 -->
                <tr v-for="item in rentalList" :key="item.id">
<!-- 租赁单号：code 样式使用等宽字体，便于对账与检索。 -->
<!-- 单号使用等宽字体，便于与后端日志、财务记录逐位核对。 -->
                  <td class="code">{{ item.orderNo }}</td>
<!-- 租用人：展示 renterName；敏感证件字段不在本页展示。 -->
<!-- 租用人只展示姓名，不展示证件号等敏感信息。 -->
                  <td>{{ item.renterName }}</td>
<!-- 租期：开始时间与结束时间两行展示，均使用 formatDateTime。 -->
<!-- 租期分两行展示，第一行开始时间，第二行「至 结束时间」。 -->
                  <td>
                    <div>{{ formatDateTime(item.startTime) }}</div>
                    <div class="cell-sub">至 {{ formatDateTime(item.endTime) }}</div>
                  </td>
<!-- 押金：formatMoney 输出金额，右对齐，便于纵向比较。 -->
<!-- 押金使用 formatMoney，右对齐后金额列更易比较。 -->
                  <td class="r money">{{ formatMoney(item.depositAmount) }}</td>
<!-- 租赁状态：rentalStatusTag 决定颜色，文案优先 statusName，缺失时本地映射。 -->
<!-- 租赁状态徽章颜色由 rentalStatusTag 决定，覆盖待支付/已预约/已取件等状态。 -->
                  <td class="c">
                    <span :class="badgeClass(rentalStatusTag(item.status))">
                      {{ item.statusName || enumLabel(RENTAL_STATUS_LABELS, item.status) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </template>
<!-- 租赁空态：该工具没有租赁记录时显示文档图标与「暂无租赁单」。 -->
<!-- 租赁空态使用 Document 图标，与日志空态保持一致。 -->
          <div v-else class="empty">
            <Document class="e-ic" />
            <h4>暂无租赁单</h4>
          </div>
        </div>
<!-- 租赁抽屉底栏：左侧总数/页码，右侧上一页/下一页按钮。 -->
<!-- 租赁底栏左侧是总数与页码，右侧是上一页/下一页。 -->
        <div class="dlg-foot">
<!-- 分页摘要：共 N 条、第 X/Y 页；Y 来自 rentalPages 计算属性。 -->
<!-- 页码摘要使用 rentalPages 计算，空数据时仍显示第 1/1 页。 -->
          <span class="tiny">共 {{ rentalTotal }} 条 · 第 {{ rentalPage }}/{{ rentalPages }} 页</span>
          <span class="grow"></span>
<!-- 简易分页器：首尾页禁用对应按钮；gotoRentalPage 做边界校验后重新加载。 -->
<!-- 简易分页器没有页码列表，适合每页 5 条、页数不多的租赁记录。 -->
          <div class="pager">
            <button :disabled="rentalPage <= 1" @click="gotoRentalPage(rentalPage - 1)">‹</button>
            <button :disabled="rentalPage >= rentalPages" @click="gotoRentalPage(rentalPage + 1)">›</button>
          </div>
        </div>
      </div>
    </div>

<!-- 图片预览层：previewUrl 非空时显示，点击遮罩或图片都会清空 URL 关闭。 -->
<!-- 图片预览层放在模板最后，确保层级高于所有弹窗和抽屉。 -->
    <!-- 图片预览 -->
<!-- 预览遮罩：preview-mask 只调整对齐；图片自适应视口，最大 86vw x 84vh。 -->
<!-- 预览遮罩点击自身关闭；preview-mask 只改变对齐方式，背景沿用全局 .mask。 -->
    <div class="mask show preview-mask" v-if="previewUrl" @click.self="previewUrl = ''">
<!-- 预览图片：cursor: zoom-out 提示可点击关闭；无额外操作按钮。 -->
<!-- 预览图片点击也会关闭；没有下载、缩放、旋转等额外操作，保持轻量。 -->
      <img :src="previewUrl" class="preview-img" alt="" @click="previewUrl = ''" />
    </div>
  </div>
<!-- 模板结束。以下为 scoped 样式，仅作用于本组件，避免污染全局。 -->
<!-- 模板结束：接下来是 scoped 样式，只影响本组件的 class。 -->
</template>

<style scoped>
/* ==========================================================================
 * 样式分区总览（scoped）：
 * - 工具单元格与缩略图占位；
 * - 使用指南分步编辑器；
 * - 工具图片上传与预览；
 * - 图片预览遮罩。
 * 颜色、圆角、字号尽量复用全局 CSS 变量，保持设计系统一致。
 * ========================================================================== */
/* 工具单元格：缩略图与文字横向排列，gap 控制间距，align-items 保证垂直居中。 */
/* 工具信息单元格：flex 横排，缩略图固定尺寸，右侧文字可自然换行。 */
/* 样式约定：
 * - 布局用 flex/grid，间距用 gap，避免 margin 叠加难以预测；
 * - 颜色使用全局变量（--line、--surface-2、--brand-bg 等），适配主题；
 * - 交互反馈使用 cursor、hover、disabled 透明度，不引入额外动画库；
 * - 所有尺寸尽量使用固定像素或变量，保证表格与弹窗在不同页面一致。
 */
/* 以下样式只服务本页特有的指南编辑器、图片上传与预览，通用样式来自全局。 */
/* 使用 align-items:center 让缩略图与多行文字垂直居中，gap 控制图文间距。 */
/* 本页样式的维护建议：
 * - 新增步骤块内元素时，优先复用现有 gap 与字号变量；
 * - 图片尺寸变化时同步调整容器与预览图，避免 object-fit 裁切异常；
 * - 删除按钮的负偏移依赖容器尺寸，改尺寸时需一起检查；
 * - 预览层不要添加阻止冒泡的逻辑，否则点击图片无法关闭。
 */
.cell-tool {
  display: flex;
  align-items: center;
  gap: 11px;
}
/* 缩略图：cursor: zoom-in 提示可点击放大；flex:none 防止被长名称挤压。 */
/* 缩略图尺寸由全局 .thumb 定义，这里只补充缩放光标与禁止收缩。 */
.cell-tool .thumb {
  cursor: zoom-in;
  flex: none;
}
/* 无图占位：与缩略图同尺寸、同圆角，使用表面色与边框保持视觉占位。 */
/* inline-flex + 居中让 '—' 垂直水平居中；flex:none 保持 44x44。 */
/* 占位符使用 inline-flex 而不是 block，便于在单元格中与文字并排。 */
/* font-size 使用 x-s 变量，让 '—' 不显得过于突出。 */
.thumb-ph {
  width: 44px;
  height: 44px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: var(--surface-2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  font-size: var(--t-xs);
}

/* 指南编辑器：纵向 flex，步骤块之间 10px 间距。 */
/* 指南编辑器本身不设最大高度，步骤较多时随弹窗 body 一起滚动。 */
.guide-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 步骤块：浅色卡片、圆角边框、内边距，内部再纵向排列标题/文本/图片。 */
/* 步骤块使用 surface-2 浅色底，与弹窗白色背景形成层次，但不抢眼。 */
/* border-radius 10px 与全局卡片圆角保持一致。 */
.g-step-block {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--surface-2);
  display: flex;
  flex-direction: column;
  gap: 9px;
}
/* 步骤头部：左侧步骤编号、右侧操作按钮，space-between 两端对齐。 */
/* 头部两端对齐：步骤编号靠左，操作按钮靠右，中间留白由 flex 自动分配。 */
.g-step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
/* 步骤编号：品牌浅底 + 品牌色文字，等宽字体，胶囊状小标签。 */
/* 编号标签使用等宽字体，步骤数字变化时宽度稳定，避免头部抖动。 */
.g-num {
  height: 26px;
  padding: 0 10px;
  border-radius: 8px;
  background: var(--brand-bg);
  color: var(--brand-2);
  font-weight: 700;
  font-size: var(--t-xs);
  display: inline-flex;
  align-items: center;
  font-family: var(--font-mono);
}
/* 步骤操作区：上移/下移/删除按钮横排，gap 很小以节省空间。 */
/* gap:2px 让三个操作按钮紧凑排列，减少头部占用宽度。 */
.g-step-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
/* 操作按钮字号缩小到 x-s，避免在步骤卡片里过于抢眼。 */
/* 操作按钮字号与编号标签一致，保持步骤块内的字号层级。 */
.g-step-actions .link-btn {
  font-size: var(--t-xs);
}
/* 禁用态：降低透明度并显示 not-allowed 光标，明确首尾/仅剩一步的边界。 */
/* 禁用态透明度 0.4 是全局常用的弱化值，保证仍可辨认但明确不可点。 */
.g-step-actions .link-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
/* 步骤配图区：预览、上传按钮、移除按钮横向排列，允许换行。 */
/* 配图区允许换行，图片预览、上传按钮、移除按钮在窄屏下会自动折行。 */
.g-step-image {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 步骤预览图：96x72 固定比例，object-fit: cover 裁切，圆角与边框统一。 */
/* 预览图使用 object-fit:cover，保证不同比例的图片都能填满 96x72 区域。 */
.g-step-preview {
  width: 96px;
  height: 72px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}
/* 编辑器底部：新增步骤按钮与提示文字横排，允许换行，字号偏小。 */
/* 底部提示字号为 x-s，颜色使用次级文字色，避免与可操作按钮竞争。 */
.guide-editor-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: var(--t-xs);
  color: var(--ink-2);
}
/* 旧版格式提示条：信息蓝底色、圆角、横向图标+文字，用于告知自动升级。 */
/* 旧版提示条使用 info 语义色，传达「信息」而非「警告」。 */
/* 图标与文字使用 gap:8px，垂直居中，阅读节奏与全局提示条一致。 */
.legacy-note {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-xs);
  color: var(--info);
  background: var(--info-bg);
  border-radius: 8px;
  padding: 9px 12px;
}

/* 图片上传区：纵向排列图片列表与上传按钮。 */
/* 图片上传区与指南编辑器都使用纵向 flex，保持表单区块的节奏一致。 */
.image-upload {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 图片列表：横向 flex + wrap，多图自动换行，间距 10px。 */
/* 图片列表 flex-wrap 让多张图片在弹窗宽度内自动换行。 */
.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* 单张图片容器：88x88 相对定位，供右上角删除按钮绝对定位。 */
/* 图片容器固定 88x88，与预览图尺寸一致，删除按钮才有稳定的定位参照。 */
.image-item {
  position: relative;
  width: 88px;
  height: 88px;
}
/* 图片预览：88x88 裁切填充，圆角边框，zoom-in 提示可放大。 */
/* 预览图与容器同尺寸，object-fit:cover 保证缩略图不变形。 */
.image-preview {
  width: 88px;
  height: 88px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid var(--line);
  cursor: zoom-in;
}
/* 删除按钮：绝对定位到图片右上角，红色圆形，白色图标，带轻阴影。 */
/* 通过负 top/right 让按钮部分悬出图片边缘，视觉上更紧凑。 */
/* 删除按钮使用圆形、红色底、白色图标，是全局危险操作的通用视觉语言。 */
/* 负偏移让按钮压在图片边角上，既节省空间又保证可点击面积。 */
.image-remove {
  position: absolute;
  top: -7px;
  right: -7px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: none;
  background: var(--bad);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}

/* 预览遮罩：覆盖全屏并居中图片，背景由全局 .mask 提供。 */
/* 预览遮罩只覆盖对齐方式，全屏定位与背景由全局 .mask 提供。 */
.preview-mask {
  align-items: center;
}
/* 预览大图：限制最大宽高，避免超出视口；圆角与阴影提升层次。 */
/* cursor: zoom-out 与打开时的 zoom-in 形成语义呼应。 */
/* 大图最大宽高使用 vw/vh，保证在不同屏幕尺寸下都不会溢出视口。 */
/* shadow-lg 提升浮层层次，圆角 12px 与全局弹窗圆角保持一致。 */
.preview-img {
  max-width: 86vw;
  max-height: 84vh;
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  cursor: zoom-out;
}
</style>
