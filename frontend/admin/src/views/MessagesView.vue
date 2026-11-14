<!-- MessagesView.vue —— 运营端「消息中心」页面。 -->
<!-- 页面职责：集中展示报修、逾期、预约、公告与系统通知，并提供已读处理。 -->
<!-- 数据入口一：@/api/messages 导出的 messageApi，负责消息列表、未读数、单条已读与全部已读。 -->
<!-- 数据入口二：同文件的 messageConfigApi，负责消息渠道配置的读取与保存。 -->
<!-- 权限：渠道配置入口仅对 SUPER_ADMIN（系统管理员）显示，普通工作人员不可见。 -->
<!-- 分页：采用服务端分页，page/size 直接传给后端，前端不持有全量消息。 -->
<!-- 分栏：类型分栏属于客户端过滤，只过滤当前页数据，分栏计数也只代表当前页。 -->
<!-- 未读数：由独立接口返回，是全局未读总数，不是当前页未读条数。 -->
<!-- 已读：单条已读与全部已读成功后都会统一刷新列表和未读数，保持两处状态一致。 -->
<!-- 详情：点击任意消息打开详情弹窗；未读消息在打开时立即调用单条已读接口。 -->
<!-- 跳转：详情弹窗的「去处理」按消息类型映射到对应业务路由，不做后端调用。 -->
<!-- 样式：图标颜色、徽章色调全部复用全局 CSS 变量，保持与运营端其他页面一致。 -->
<!-- 维护提示：本文件的状态彼此独立，改动接口字段时需同步检查模板中的取值回退逻辑。 -->
<!-- 以下按脚本、模板、样式三部分组织，注释按区块说明用途与边界。 -->
<script setup lang="ts">
/**
 * Vue 组合式 API：
 * computed 用于派生类型计数、过滤结果与分页页码；
 * onMounted 用于页面首次加载；
 * reactive 用于服务端分页查询对象；
 * ref 用于加载态、列表、总数、未读数、弹窗与表单等基础响应式值。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import type { Component } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
/**
 * Element Plus 图标组件：
 * Bell 用于空状态以及公告/系统类消息；
 * Box 用于领取与工具可借类消息；
 * Calendar 用于预约类消息；
 * Check 用于「全部已读」按钮；
 * Clock 用于租期到期提醒；
 * Close 用于两个弹窗的关闭按钮；
 * Refresh 用于刷新按钮；
 * Tools 用于报修类消息；
 * Warning 用于逾期类消息。
 * 这些图标只在 TYPE_META 或按钮模板中通过组件方式使用。
 */
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
/**
 * 消息接口聚合模块。
 * messageApi 提供：
 *   - list：按 page/size 获取服务端分页列表；
 *   - unreadCount：获取全局未读总数；
 *   - read：把单条消息标记为已读；
 *   - readAll：把全部消息标记为已读。
 * messageConfigApi 提供：
 *   - get：读取渠道配置；
 *   - update：保存渠道配置。
 * 统一从 @/api/messages 导入，便于替换请求实现。
 */
import { messageApi, messageConfigApi } from '@/api/messages'
/**
 * 后端视图对象类型：
 * MessageVO 描述一条消息，包含 id、title、content、type、typeName、
 * channel、deliveryStatus、readStatus、createTime 等字段；
 * MessageConfigVO 描述渠道配置，包含 defaultChannel 与 smsEnabled。
 */
import type { MessageConfigVO, MessageVO } from '@/api/types'
/**
 * 认证 store：
 * username 用于其他页面判断「是否操作自己」，本页主要使用 isSuperAdmin；
 * isSuperAdmin 决定是否渲染「渠道配置」入口。
 */
import { useAuthStore } from '@/stores/auth'
/**
 * badgeClass 把业务语义状态转换成徽章样式类。
 * 本页只把它与 deliveryStatusTag 组合，用于送达状态徽章的色调。
 */
import { badgeClass } from '@/utils/badge'
/**
 * formatDateTime 统一格式化时间字符串。
 * 列表右侧的创建时间与详情弹窗副标题都使用同一格式，避免出现多种时间样式。
 */
import { formatDateTime } from '@/utils/format'
/**
 * 枚举展示工具：
 * DELIVERY_STATUS_LABELS：送达状态（如已送达/失败）的中文文案；
 * MESSAGE_CHANNEL_LABELS：发送渠道（站内/短信）的中文文案；
 * MESSAGE_TYPE_LABELS：消息类型的中文文案；
 * deliveryStatusTag：送达状态到徽章语义的映射；
 * enumLabel：按枚举值取中文文案，缺失时回退为原始值；
 * isReadStatus：把后端阅读状态归一化为是否已读的布尔判断。
 * 模板中的徽章文案与已读样式都依赖这些函数。
 */
import {
  DELIVERY_STATUS_LABELS,
  MESSAGE_CHANNEL_LABELS,
  MESSAGE_TYPE_LABELS,
  deliveryStatusTag,
  enumLabel,
  isReadStatus,
} from '@/utils/enums'

/**
 * 路由与认证实例：
 * router 供详情弹窗执行「去处理」跳转；
 * auth 供头部按钮判断当前登录账号是否为系统管理员。
 */
const router = useRouter()
const auth = useAuthStore()

/**
 * 列表加载状态。
 * 仅表示 messageApi.list 是否正在进行。
 * 首次加载且列表为空时，模板显示骨架屏；
 * 已有数据时刷新只更新内容，不清空列表，避免页面闪烁。
 */
const loading = ref(false)
/**
 * 当前页消息列表，来源是 messageApi.list 返回的 data.records。
 * 类型计数、客户端分栏和列表渲染都基于这份数据，
 * 因此这些派生结果天然只覆盖当前页，而不是全部消息。
 */
const messages = ref<MessageVO[]>([])
/**
 * 消息总数，来源是 messageApi.list 返回的 data.total。
 * 用于底部「共 N 条」文案，以及计算服务端分页的总页数。
 * 这个值是后端统计的全量数量，与当前页 messages.length 不同。
 */
const total = ref(0)
/**
 * 全局未读消息总数，来源是独立的 messageApi.unreadCount 接口。
 * 顶部未读提示、未读圆点语义以及「全部已读」按钮的禁用状态都依赖它。
 * 单条或全部已读成功后会重新拉取，保证数值及时更新。
 */
const unreadCount = ref(0)
/**
 * 服务端分页查询参数：
 * page：当前页码，从 1 开始；
 * size：每页条数，默认 10，可在底部切换为 20 或 50。
 * 这两个值会原样传给 messageApi.list，由后端完成分页；
 * 修改 size 时必须同时重置 page，见 handleSizeChange。
 */
const query = reactive({
  page: 1,
  size: 10,
})

/**
 * 加载当前页消息列表。
 *
 * 入参：无，直接读取响应式的 query.page 与 query.size。
 * 流程：
 *   1. 将 loading 置为 true，触发模板的加载态；
 *   2. 调用 messageApi.list，传入页码与每页条数；
 *   3. 用返回的 data.records 覆盖 messages；
 *   4. 用返回的 data.total 覆盖 total。
 * 副作用：改写 loading、messages、total 三个响应式状态。
 * 边界：使用 try/finally 保证请求失败时 loading 也能复位；
 *       错误提示交给统一请求层，本函数不重复弹错。
 * 调用方：onMounted、refresh、handleSizeChange、goPage、
 *         handleRead 与 handleReadAll 在需要刷新时也会触发。
 */
/**
 * 数据流小结：
 * 用户操作改变 query 或 activeType，
 * 前者触发 loadList 重新请求，后者只触发客户端 computed 重算；
 * 服务端只负责分页，类型分栏与过滤全部在本页完成；
 * 已读操作先写后端再整体刷新，前端不做乐观更新，
 * 因此列表与未读数最终一定以服务端返回为准。
 */
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

/**
 * 加载全局未读消息总数。
 *
 * 入参：无。
 * API：messageApi.unreadCount。
 * 副作用：直接覆盖 unreadCount，
 *       进而影响顶部未读提示和「全部已读」按钮是否禁用。
 * 边界：请求异常交由统一请求层处理，本函数不额外捕获，
 *       因此失败时不会错误地把未读数改成 0。
 */
async function loadUnreadCount() {
  unreadCount.value = await messageApi.unreadCount()
}

/**
 * 刷新列表与未读数。
 *
 * 行为：用 Promise.all 并发请求当前页列表和全局未读数，
 *       两者都完成后 refresh 才结束。
 * 目的：让列表内容与顶部未读角标在一次操作后保持同步。
 * 调用场景：页面首次挂载、顶部刷新按钮、
 *           单条已读与全部已读成功后的状态同步。
 */
async function refresh() {
  await Promise.all([loadList(), loadUnreadCount()])
}

/**
 * 每页条数切换处理。
 *
 * 触发时机：底部「N 条/页」下拉框的 change 事件。
 * 行为：
 *   1. 先把当前页重置为第 1 页；
 *   2. 再调用 loadList 按新 size 请求数据。
 * 为什么重置页码：切换每页条数后旧页码可能超出新的总页数，
 *                 重置可以避免停留在空页。
 * 副作用：改写 query.page 并触发一次服务端请求。
 * 注意：这里不 await loadList，加载态由 loadList 内部的 loading 承担。
 */
function handleSizeChange() {
  query.page = 1
  loadList()
}

/**
 * 将单条消息标记为已读。
 *
 * 入参：row，当前消息对象。
 * 前置校验：isReadStatus(row.readStatus) 为真时直接返回，
 *           避免对已读消息重复发送请求。
 * API：messageApi.read(row.id)。
 * 副作用：
 *   1. 成功后提示「已标记为已读」；
 *   2. 调用 refresh 同时刷新当前页列表与未读总数，
 *      让行样式、未读圆点和顶部未读角标保持一致。
 * 边界：网络异常由请求层统一提示；成功提示只在接口成功后出现。
 */
async function handleRead(row: MessageVO) {
  if (isReadStatus(row.readStatus)) return
  await messageApi.read(row.id)
  ElMessage.success('已标记为已读')
  await refresh()
}

/**
 * 「全部已读」请求进行中的状态。
 * 请求期间按钮显示加载态并阻止重复点击，
 * 避免用户连续触发多次全部已读。
 */
const readingAll = ref(false)

/**
 * 将全部消息标记为已读。
 *
 * 前置校验：unreadCount 为 0 时直接返回，
 *           不弹确认框，也不发送无意义的请求。
 * 确认：使用 ElMessageBox.confirm 进行二次确认；
 *       用户取消时抛出的异常被捕获并静默返回。
 * API：messageApi.readAll。
 * 副作用：
 *   1. 成功后提示「全部消息已标记为已读」；
 *   2. 调用 refresh 刷新列表与未读数；
 *   3. readingAll 在 finally 中复位，失败时按钮也能恢复。
 * 边界：确认框取消不是错误，不显示提示；接口失败由请求层处理。
 */
/**
 * 与单条已读的区别：
 * 单条已读针对某一行，接口成功后同样走 refresh；
 * 全部已读没有行参数，直接清空服务端的未读标记；
 * 两者都不做前端乐观更新，避免请求失败时界面提前变成已读。
 * 全部已读影响范围更大，因此额外增加二次确认。
 */
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
/**
 * activeType 保存当前选中的类型分栏。
 * 空字符串代表「全部」，其他值必须与后端消息 type 字段一致；
 * 它只影响 filteredMessages 与按钮高亮，不参与接口请求，
 * 也不会改变 messages、total 或 unreadCount。
 */
const activeType = ref('')
/**
 * 类型分栏的固定选项。
 * 元素结构为 [枚举值, 中文短标签]，模板通过 v-for 解构使用。
 * 顺序按运营关注优先级排列：报修、逾期、预约、公告、系统。
 * 注意：这里只列常用类型；后端还可能返回 PICKUP、RENT_DUE、
 *       TOOL_AVAILABLE 等类型，它们会出现在「全部」分栏中，
 *       但不会单独生成分栏按钮。
 */
const typeOptions: Array<[string, string]> = [
  ['REPAIR', '报修'],
  ['OVERDUE', '逾期'],
  ['RESERVATION', '预约'],
  ['ANNOUNCEMENT', '公告'],
  ['SYSTEM', '系统'],
]

/**
 * 当前页各消息类型的数量统计。
 *
 * 数据范围：仅 messages.value，即当前页已加载的消息。
 * 因此分栏上的数字会随翻页变化，不代表全量消息分布，
 * 这是客户端分栏的已知边界。
 * 返回：以消息类型为键、数量为值的普通对象。
 * 模板使用 typeCounts[key] || 0 兜底，避免未出现的类型显示 undefined。
 */
/**
 * 统计示例：若当前页有 3 条 REPAIR、2 条 OVERDUE，
 * 则返回 { REPAIR: 3, OVERDUE: 2 }。
 * 未出现在当前页的类型不会出现在对象里，
 * 模板统一用 typeCounts[key] || 0 兜底。
 */
const typeCounts = computed(() => {
  const map: Record<string, number> = {}
  for (const m of messages.value) {
    map[m.type] = (map[m.type] || 0) + 1
  }
  return map
})

/**
 * 按当前选中类型过滤后的消息列表。
 *
 * activeType 为空字符串表示「全部」，此时直接返回 messages；
 * 否则只保留 type 与 activeType 完全相同的消息。
 * 该 computed 不修改原列表，只生成新的视图数组；
 * 结果为空时模板展示空状态。
 */
const filteredMessages = computed(() =>
  activeType.value ? messages.value.filter((m) => m.type === activeType.value) : messages.value,
)

/* —— 消息类型：徽章色调 + 图标 —— */
/**
 * 单一消息类型的展示元数据。
 *
 * badge：徽章语义色调类名，供模板拼接到 badge 上；
 * fg：图标前景色，使用全局 CSS 变量；
 * bg：图标背景色，使用全局 CSS 变量；
 * icon：Element Plus 图标组件引用，供 <component :is> 动态渲染。
 */
interface TypeMeta {
  badge: string
  fg: string
  bg: string
  icon: Component
}

/**
 * 消息类型到展示元数据的映射表。
 *
 * 覆盖后端可能返回的主要类型：
 * RESERVATION 预约、PICKUP 领取、RENT_DUE 租期到期、
 * OVERDUE 逾期、REPAIR 报修、TOOL_AVAILABLE 工具可借、
 * ANNOUNCEMENT 公告、SYSTEM 系统通知。
 * 颜色统一使用全局 CSS 变量，确保与运营端其他页面视觉一致；
 * 未登记的类型由 metaOf 回退到 SYSTEM，避免渲染异常。
 */
/**
 * 映射表是普通常量对象，不参与响应式追踪。
 * 新增消息类型时，在此补充条目即可同时获得列表与详情弹窗的样式；
 * 若暂时不确定样式，依赖 metaOf 的 SYSTEM 回退即可，无需改模板。
 */
const TYPE_META: Record<string, TypeMeta> = {
  RESERVATION: { badge: 'brand', fg: 'var(--brand-2)', bg: 'var(--brand-bg)', icon: Calendar },
  PICKUP: { badge: 'info', fg: 'var(--info)', bg: 'var(--info-bg)', icon: Box },
  RENT_DUE: { badge: 'warn', fg: 'var(--warn)', bg: 'var(--warn-bg)', icon: Clock },
  OVERDUE: { badge: 'bad', fg: 'var(--bad)', bg: 'var(--bad-bg)', icon: Warning },
  REPAIR: { badge: 'info', fg: 'var(--info)', bg: 'var(--info-bg)', icon: Tools },
  TOOL_AVAILABLE: { badge: 'ok', fg: 'var(--ok)', bg: 'var(--ok-bg)', icon: Box },
  ANNOUNCEMENT: { badge: 'brand', fg: 'var(--brand-2)', bg: 'var(--brand-bg)', icon: Bell },
  SYSTEM: { badge: 'neutral', fg: 'var(--ink-2)', bg: 'var(--surface-2)', icon: Bell },
}

/**
 * 读取某个消息类型对应的展示元数据。
 *
 * 入参：type，消息类型，可能为空或为后端新增的未知值。
 * 返回：命中的 TypeMeta；未命中时返回 TYPE_META.SYSTEM。
 * 这样模板始终能拿到 badge、fg、bg 与 icon，
 * 不会因为类型缺失导致样式或图标渲染报错。
 */
function metaOf(type?: string): TypeMeta {
  return TYPE_META[type || ''] || TYPE_META.SYSTEM
}

/* —— 详情弹窗 + 跳转处理 —— */
/**
 * 详情弹窗是否可见。
 * detail 保存当前查看的消息，关闭弹窗后仍保留最后一次的数据；
 * 下次打开时会先被新消息覆盖，因此不会显示过期内容。
 */
const dialogVisible = ref(false)
const detail = ref<MessageVO | null>(null)

/**
 * 打开消息详情弹窗。
 *
 * 入参：row，被点击的消息。
 * 行为：
 *   1. 把 row 写入 detail，供弹窗展示标题、时间、渠道和内容；
 *   2. 将 dialogVisible 置为 true；
 *   3. 若该消息尚未已读，立即调用 handleRead 标记已读。
 * 因此「打开详情即已读」不需要用户额外点击。
 * 副作用：可能触发 messageApi.read 并刷新列表与未读数。
 * 边界：已读消息只打开弹窗，不重复请求。
 */
function openDetail(row: MessageVO) {
  detail.value = row
  dialogVisible.value = true
  if (!isReadStatus(row.readStatus)) handleRead(row)
}

/**
 * 详情弹窗「去处理」：按消息类型跳转对应业务页面。
 *
 * 入参：无，读取当前 detail.value。
 * 映射规则：
 *   REPAIR         → /damage     报修处理；
 *   OVERDUE        → /rentals    逾期租赁处理；
 *   RESERVATION    → /rentals    预约相关租赁；
 *   PICKUP         → /rentals    领取相关租赁；
 *   RENT_DUE       → /rentals    租期到期处理；
 *   TOOL_AVAILABLE → /dashboard  工具可借，回首页；
 *   ANNOUNCEMENT   → /dashboard  公告，回首页；
 *   SYSTEM         → /dashboard  系统消息，回首页。
 * 未命中映射或 detail 为空时统一跳转 /dashboard。
 * 副作用：先关闭详情弹窗，再执行前端路由跳转，不调用后端接口。
 */
/**
 * 跳转映射集中在这里维护，而不是散落在模板中。
 * 新增消息类型时只需补一个键值对；
 * 若某类型暂无对应页面，落到 /dashboard 是安全的默认行为。
 * 路由跳转本身不会失败回调，目标页是否存在由路由表保证。
 */
function handleGo() {
  if (!detail.value) return
  const path =
    {
      REPAIR: '/damage',
      OVERDUE: '/rentals',
      RESERVATION: '/rentals',
      PICKUP: '/rentals',
      RENT_DUE: '/rentals',
      TOOL_AVAILABLE: '/dashboard',
      ANNOUNCEMENT: '/dashboard',
      SYSTEM: '/dashboard',
    }[detail.value.type] || '/dashboard'
  dialogVisible.value = false
  router.push(path)
}

/* —— 渠道配置（仅系统管理员） —— */
/**
 * 渠道配置弹窗是否可见。
 * 入口只对系统管理员渲染；普通工作人员不会看到该按钮，
 * 后端接口自身也应继续做权限校验。
 */
const configVisible = ref(false)
const configLoading = ref(false)
const configSaving = ref(false)
/**
 * 渠道配置编辑表单。
 *
 * defaultChannel：默认发送渠道，限定为 SYSTEM（站内）或 SMS（Mock 短信）；
 * smsEnabled：是否启用 Mock 短信发送。
 * 打开弹窗时用 messageConfigApi.get 回填；
 * 保存时把这两个字段整体提交给 messageConfigApi.update。
 */
const configForm = reactive<{ defaultChannel: 'SYSTEM' | 'SMS'; smsEnabled: boolean }>({
  defaultChannel: 'SYSTEM',
  smsEnabled: false,
})

/**
 * 打开并加载消息渠道配置。
 *
 * 行为：
 *   1. 先显示弹窗，避免等待接口时界面没有反馈；
 *   2. 打开 configLoading，模板显示骨架占位；
 *   3. 调用 messageConfigApi.get 读取配置；
 *   4. 把后端 defaultChannel 归一化为 SYSTEM 或 SMS，
 *      非 SMS 的值一律回退 SYSTEM；
 *   5. 把 smsEnabled 强转为布尔值。
 * 副作用：改写 configVisible、configLoading 与 configForm。
 * 边界：finally 复位 configLoading；失败时弹窗仍可手动关闭。
 */
/**
 * 打开配置前不重复校验角色，因为入口已按 isSuperAdmin 控制；
 * 真正的权限仍应由后端接口保证，前端隐藏只是体验层限制。
 * 读取配置是只读操作，失败时不会破坏当前表单的其他值。
 */
async function openConfig() {
  configVisible.value = true
  configLoading.value = true
  try {
    const data: MessageConfigVO = await messageConfigApi.get()
    configForm.defaultChannel = data.defaultChannel === 'SMS' ? 'SMS' : 'SYSTEM'
    configForm.smsEnabled = Boolean(data.smsEnabled)
  } finally {
    configLoading.value = false
  }
}

/**
 * 保存消息渠道配置。
 *
 * 行为：
 *   1. 打开 configSaving，保存按钮显示「保存中…」并禁用；
 *   2. 调用 messageConfigApi.update 提交当前表单值；
 *   3. 成功后提示「消息渠道配置已保存」并关闭弹窗。
 * 副作用：保存后的配置立即作用于后续提醒发送。
 * 本页不缓存配置，每次打开弹窗都会重新读取。
 * 边界：finally 复位 configSaving；接口失败由统一请求层提示。
 */
async function saveConfig() {
  configSaving.value = true
  try {
    await messageConfigApi.update({
      defaultChannel: configForm.defaultChannel,
      smsEnabled: configForm.smsEnabled,
    })
    ElMessage.success('消息渠道配置已保存')
    configVisible.value = false
  } finally {
    configSaving.value = false
  }
}

/* —— 分页 —— */
/**
 * 服务端分页的总页数。
 *
 * 计算方式：Math.ceil(total / query.size)。
 * 使用 Math.max(1, ...) 保证没有数据时总页数仍为 1，
 * 这样底部页码区域不会出现 0 页，上一页/下一页按钮也便于判断。
 * 注意：页数基于后端 total，而不是当前页的 messages 数量。
 */
/**
 * 页码计算只依赖 total 与 size，
 * 因此切换每页条数或重新加载列表后会自动重算，
 * 不需要额外的 watch。
 * total 变化时页码窗口也会随 totalPages 一起更新。
 */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))

/**
 * 当前可见的页码窗口，最多 5 个连续页码。
 *
 * 算法：
 *   1. 读取总页数 tp 与当前页 cur；
 *   2. start 先取 cur-2，再夹在 1 到 tp-4 之间，
 *      让当前页尽量居中，同时避免窗口越过首页或尾页；
 *   3. end 取 start+4，再夹在总页数以内；
 *   4. 生成 [start, end] 的连续整数数组。
 * 边界：总页数不足 5 时窗口自然缩短；
 *       totalPages 至少为 1，因此 start 不会小于 1。
 */
const pageList = computed(() => {
  const tp = totalPages.value
  const cur = query.page
  const start = Math.max(1, Math.min(cur - 2, tp - 4))
  const end = Math.min(tp, start + 4)
  const pages: number[] = []
  for (let i = start; i <= end; i++) pages.push(i)
  return pages
})

/**
 * 跳转到指定页码。
 *
 * 入参：p，目标页码。
 * 校验：p 小于 1、大于总页数，或与当前页相同，都直接返回，
 *       避免无效请求和重复加载。
 * 副作用：先更新 query.page，再调用 loadList 请求新一页。
 */
function goPage(p: number) {
  if (p < 1 || p > totalPages.value || p === query.page) return
  query.page = p
  loadList()
}

/**
 * 页面首次挂载时执行 refresh：
 * 同时加载第一页消息列表与全局未读数。
 * 页面卸载时不需要额外清理，
 * 请求结果与响应式状态会随组件实例一起释放。
 */
onMounted(refresh)
</script>

<!-- ===== 模板部分 ===== -->
<!-- 模板只负责渲染与事件绑定，业务逻辑集中在脚本区。 -->
<!-- 列表、分栏、弹窗三块共享同一份 messages/detail 状态。 -->
<!-- 所有按钮的禁用与加载态都由脚本区的布尔值驱动。 -->
<!-- 模板中的中文文案是运营端最终展示文案。 -->
<!-- 修改模板时注意保留 v-if 与 v-else 的互斥关系。 -->
<!-- 弹窗遮罩统一使用 @click.self，避免点击内容区误关闭。 -->
<!-- ================== -->
<template>
<!-- 页面根容器：沿用全局 .page 布局与内边距。 -->
<!-- 所有区块都在这个容器内，便于统一控制最大宽度与间距。 -->
  <div class="page">
<!-- 页头区域：左侧是标题与说明，右侧是操作按钮组。 -->
<!-- reveal 是全局入场动画类，后续区块也复用它。 -->
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">OPERATIONS INBOX</p>
        <h1>消息中心</h1>
        <p class="sub">报修待办、逾期预警、工具与系统通知，运营事项集中处理。</p>
      </div>
<!-- 操作按钮组：渠道配置、刷新、全部已读。 -->
<!-- 排列顺序按使用频率从低到高，主操作「全部已读」放在最右侧。 -->
      <div class="actions">
<!-- 渠道配置入口：仅在 auth.isSuperAdmin 为真时渲染。 -->
<!-- 这说明它是仅系统管理员可见的页面能力，普通工作人员看不到。 -->
        <button v-if="auth.isSuperAdmin" class="btn btn-outline" @click="openConfig">
          渠道配置
        </button>
<!-- 刷新按钮：重新加载当前页列表与未读数。 -->
<!-- loading 时按钮追加 loading 类，用于展示加载态并阻止重复点击。 -->
        <button class="btn btn-outline" :class="{ loading }" @click="refresh">
          <Refresh class="ic sm" />
          刷新
        </button>
<!-- 全部已读按钮：主操作样式。 -->
<!-- 未读数为 0 时禁用；readingAll 期间显示加载态。 -->
<!-- 点击后进入 handleReadAll，先二次确认再调用接口。 -->
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

<!-- 类型分栏与未读提示的横向布局容器。 -->
<!-- flex-wrap 允许窄屏时分栏和未读提示换行，避免横向溢出。 -->
    <div class="between reveal" style="margin-bottom: 18px; flex-wrap: wrap">
<!-- 类型分栏按钮组：固定「全部」加常用类型选项。 -->
<!-- 每个按钮上的数字来自当前页统计，点击只切换 activeType，不请求后端。 -->
<!-- 「全部」的计数直接使用 messages.length。 -->
<!-- 选中项通过 active 类高亮，activeType 为空表示全部。 -->
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
<!-- 未读总数提示：仅在 unreadCount > 0 时显示。 -->
<!-- 数据来自独立未读接口，与当前页过滤结果无关。 -->
      <span v-if="unreadCount > 0" class="badge bad">
        <span class="dot"></span>未读 {{ unreadCount }} 条
      </span>
    </div>

<!-- 消息列表卡片容器。 -->
<!-- 外层 overflow: hidden 裁掉行背景，内层 padding 控制列表留白。 -->
<!-- 列表区域按三种状态渲染： -->
<!-- 1. loading 且无数据：骨架屏； -->
<!-- 2. 有过滤结果：消息行； -->
<!-- 3. 其余情况：空状态。 -->
<!-- 三种状态互斥，不会同时出现。 -->
<!-- filteredMessages 已包含 activeType 过滤，模板不再重复判断类型。 -->
<!-- 消息行的已读样式只影响视觉，不改变数据。 -->
<!-- 列表本身不处理滚动加载，翻页由底部分页器完成。 -->
    <div class="msg-list card reveal">
<!-- 首次加载骨架屏：仅在 loading 且 messages 为空时展示。 -->
<!-- 已有数据时刷新不会显示骨架，避免列表内容闪烁。 -->
<!-- 固定渲染 4 行占位，实际数据条数可能不同。 -->
      <template v-if="loading && messages.length === 0">
        <div v-for="i in 4" :key="'sk' + i" class="msg">
          <span class="m-ic skel"></span>
          <div class="m-body">
            <div class="skel" style="height: 14px; width: 36%; margin-bottom: 9px"></div>
            <div class="skel" style="height: 12px; width: 82%"></div>
          </div>
        </div>
      </template>

<!-- 有数据时逐条渲染客户端过滤后的 filteredMessages。 -->
<!-- key 使用消息 id，保证翻页或切换分栏时行组件复用正确。 -->
<!-- 已读消息附加 read 类，用于弱化标题并隐藏未读圆点。 -->
<!-- 整行可点击，点击调用 openDetail 打开详情并触发已读逻辑。 -->
      <template v-else-if="filteredMessages.length">
        <div
          v-for="m in filteredMessages"
          :key="m.id"
          class="msg"
          :class="{ read: isReadStatus(m.readStatus) }"
          @click="openDetail(m)"
        >
<!-- 类型图标容器：颜色与背景来自 metaOf 返回的元数据。 -->
<!-- 内部用动态组件渲染对应图标，既有语义色也便于快速识别类型。 -->
          <span class="m-ic" :style="{ color: metaOf(m.type).fg, background: metaOf(m.type).bg }">
            <component :is="metaOf(m.type).icon" class="ic" />
          </span>
          <div class="m-body">
<!-- 消息头部信息行：标题、类型徽章、渠道徽章、送达状态徽章。 -->
<!-- 类型文案优先使用后端 typeName，缺失时按 MESSAGE_TYPE_LABELS 回退。 -->
<!-- 渠道徽章中 SMS 使用品牌色，站内消息使用中性色。 -->
<!-- 送达状态徽章色调由 badgeClass(deliveryStatusTag(...)) 计算。 -->
            <div class="m-top">
              <h3>{{ m.title }}</h3>
              <span class="badge" :class="metaOf(m.type).badge">
                {{ m.typeName || enumLabel(MESSAGE_TYPE_LABELS, m.type) }}
              </span>
              <span class="badge" :class="m.channel === 'SMS' ? 'brand' : 'neutral'">
                {{ enumLabel(MESSAGE_CHANNEL_LABELS, m.channel) }}
              </span>
              <span class="badge" :class="badgeClass(deliveryStatusTag(m.deliveryStatus))">
                {{ enumLabel(DELIVERY_STATUS_LABELS, m.deliveryStatus) }}
              </span>
            </div>
<!-- 消息内容预览：CSS 限制为单行并省略溢出。 -->
<!-- 完整内容需要打开详情弹窗查看。 -->
            <p class="m-preview">{{ m.content }}</p>
          </div>
<!-- 创建时间：使用全局 formatDateTime 统一格式。 -->
<!-- 未读圆点由 .read 类控制，已读时隐藏。 -->
          <span class="m-time">{{ formatDateTime(m.createTime) }}</span>
          <span class="m-dot"></span>
<!-- 单条「标记已读」按钮：仅未读消息显示。 -->
<!-- @click.stop 阻止事件冒泡，避免同时触发行点击的 openDetail。 -->
<!-- 点击后调用 handleRead，成功后刷新列表与未读数。 -->
          <button
            v-if="!isReadStatus(m.readStatus)"
            class="link-btn m-mark"
            @click.stop="handleRead(m)"
          >
            标记已读
          </button>
        </div>
      </template>

<!-- 空状态：当前分栏下没有消息时展示。 -->
<!-- 图标、标题与说明共同提示「没有相关消息」，避免列表区域空白。 -->
      <div v-else class="empty">
        <div class="e-ic"><Bell class="ic" /></div>
        <h4>没有相关消息</h4>
        <p>当前分类下暂无通知。</p>
      </div>
    </div>

<!-- 列表底部：左侧显示总数与每页条数，右侧显示分页按钮。 -->
<!-- 底部区域分为左右两组： -->
<!-- 左侧是总数与每页条数选择，右侧是页码按钮。 -->
<!-- 总数来自服务端 total，不是 filteredMessages.length。 -->
<!-- 因此做客户端分栏过滤时，总数仍显示全量消息数。 -->
<!-- 每页条数变化会重置页码，避免越界。 -->
<!-- 页码窗口由 pageList 控制，最多 5 个。 -->
<!-- 上一页/下一页在边界处禁用。 -->
<!-- 分页按钮点击统一走 goPage，由它做范围校验。 -->
    <div class="list-foot reveal">
      <div class="flex" style="gap: 12px">
        <span class="tiny">共 {{ total }} 条</span>
<!-- 每页条数选择器：10、20、50 三种。 -->
<!-- 选项通过 :value 绑定数字，保证 query.size 为 number 类型。 -->
<!-- change 时调用 handleSizeChange，先重置到第 1 页再请求。 -->
<!-- 分页由服务端完成，前端不对全量数据切片。 -->
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
<!-- 自绘分页按钮组。 -->
<!-- ‹ 与 › 分别是上一页、下一页，到达边界时禁用。 -->
<!-- 中间页码来自 pageList，最多显示 5 个连续页码。 -->
<!-- 当前页通过 active 类高亮；goPage 内部还会再校验范围。 -->
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

<!-- 渠道配置弹窗：v-if 控制是否挂载，mask show 控制遮罩显示。 -->
<!-- @click.self 只在点击遮罩本身时关闭，点击弹窗内部不会误关。 -->
<!-- 该弹窗的入口只对系统管理员可见。 -->
<!-- 渠道配置弹窗的显示条件与详情弹窗不同： -->
<!-- 这里使用 v-if，关闭后直接卸载，表单状态仍保留在脚本区的 configForm 中。 -->
<!-- 每次打开都会重新调用 messageConfigApi.get， -->
<!-- 因此不会展示上一次未保存的修改。 -->
<!-- 加载期间只显示骨架，用户看不到旧值。 -->
<!-- 保存成功后弹窗关闭，失败则保持打开便于重试。 -->
<!-- 默认渠道与短信开关共同决定后续提醒的发送方式。 -->
<!-- 关闭按钮、取消按钮与遮罩点击都能关闭弹窗。 -->
    <div v-if="configVisible" class="mask show" @click.self="configVisible = false">
      <div class="dlg narrow">
<!-- 弹窗头部：标题、说明和关闭按钮。 -->
<!-- 说明强调保存后会立即作用于后续提醒发送。 -->
        <div class="dlg-head">
          <div>
            <h3>消息渠道配置</h3>
            <p class="d-sub">保存后立即作用于后续提醒发送</p>
          </div>
          <button class="dlg-x" aria-label="关闭" @click="configVisible = false">
            <Close class="ic sm" />
          </button>
        </div>
<!-- 弹窗主体：加载中显示骨架，加载完成后显示表单。 -->
<!-- 使用 template v-else 保证骨架和表单不会同时出现。 -->
        <div class="dlg-body">
          <div v-if="configLoading" class="skel" style="height: 120px"></div>
          <template v-else>
<!-- 默认渠道字段：站内消息或 Mock 短信。 -->
<!-- 居民未单独设置偏好时使用该默认值。 -->
<!-- 下拉框直接绑定 configForm.defaultChannel。 -->
            <div class="field">
              <label>默认渠道</label>
              <select v-model="configForm.defaultChannel" class="select">
                <option value="SYSTEM">站内消息</option>
                <option value="SMS">Mock 短信</option>
              </select>
              <p class="hint">居民未单独设置偏好时使用该渠道。</p>
            </div>
<!-- 短信通道字段：复选框控制是否启用 Mock 短信发送。 -->
<!-- 关闭时，短信偏好会自动回退为站内消息。 -->
            <div class="field">
              <label>短信通道</label>
              <label class="ck">
                <input v-model="configForm.smsEnabled" type="checkbox" />
                <span>启用 Mock 短信发送</span>
              </label>
              <p class="hint">关闭时短信偏好自动回退为站内消息。</p>
            </div>
          </template>
        </div>
<!-- 弹窗底部：取消直接关闭弹窗。 -->
<!-- 保存期间按钮禁用并显示「保存中…」，避免重复提交。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="configVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="configLoading || configSaving" @click="saveConfig">
            {{ configSaving ? '保存中…' : '保存配置' }}
          </button>
        </div>
      </div>
    </div>

<!-- 详情弹窗：始终渲染，通过 dialogVisible 控制 show 类。 -->
<!-- 这样关闭时仍能保留过渡动画，不会因 v-if 立即移除节点。 -->
<!-- 点击遮罩自身时关闭弹窗。 -->
<!-- 详情弹窗始终存在于 DOM 中，只用 show 类控制显隐。 -->
<!-- 这样做可以保留 CSS 过渡动画，关闭时不会瞬间消失。 -->
<!-- detail 保存当前消息，关闭后不清空， -->
<!-- 但下次点击其他消息会立即覆盖。 -->
<!-- 未读数与已读样式在打开时由 handleRead 触发刷新。 -->
<!-- 「去处理」只在 detail 存在时执行跳转。 -->
<!-- 跳转前先关闭弹窗，避免返回页面时弹窗仍然打开。 -->
<!-- 弹窗内容全部来自当前消息，不额外请求详情接口。 -->
    <div class="mask" :class="{ show: dialogVisible }" @click.self="dialogVisible = false">
      <div class="dlg narrow">
<!-- 详情弹窗头部：消息标题与时间、渠道、送达状态。 -->
<!-- detail 为空时使用可选链，避免读取属性报错。 -->
<!-- 渠道与送达状态通过枚举工具转成中文文案。 -->
        <div class="dlg-head">
          <div>
            <h3>{{ detail?.title }}</h3>
            <p class="d-sub">
              {{ formatDateTime(detail?.createTime) }}
              <template v-if="detail">
                · {{ enumLabel(MESSAGE_CHANNEL_LABELS, detail.channel) }}
                · 送达：{{ enumLabel(DELIVERY_STATUS_LABELS, detail.deliveryStatus) }}
              </template>
            </p>
          </div>
          <button class="dlg-x" aria-label="关闭" @click="dialogVisible = false">
            <Close class="ic sm" />
          </button>
        </div>
<!-- 详情弹窗主体：类型徽章与完整消息内容。 -->
<!-- 内容不截断，保证长消息可以完整阅读。 -->
<!-- 类型徽章同样使用 metaOf 的色调，与列表保持一致。 -->
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
<!-- 详情弹窗底部：关闭弹窗，或跳转到对应业务页面处理。 -->
<!-- 「去处理」调用 handleGo，按消息类型做前端路由跳转。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="dialogVisible = false">关闭</button>
          <button class="btn btn-primary" @click="handleGo">去处理</button>
        </div>
      </div>
    </div>
  </div>
</template>

<!-- ===== 样式部分 ===== -->
<!-- 所有样式均为 scoped，只作用于本组件。 -->
<!-- 颜色、字号、动效时长全部引用全局 CSS 变量。 -->
<!-- 列表行、图标、徽章、分页与弹窗共用全局设计语言。 -->
<!-- 悬停与已读状态只改变颜色和可见性，不改变布局尺寸。 -->
<!-- 响应式行为主要依赖 flex-wrap 与父级布局。 -->
<!-- 新增元素时优先复用现有类名和变量。 -->
<!-- ===================== -->
<style scoped>
/*
 * .msg-list：消息列表卡片。
 * overflow: hidden 让内部行的悬停背景与圆角贴合卡片；
 * padding 在卡片内留出 6px，使消息行不紧贴边缘。
 */
.msg-list {
  overflow: hidden;
  padding: 6px;
}

/*
 * .msg：单条消息行。
 * 使用 flex 横向排列图标、正文、时间、未读圆点和已读按钮；
 * position: relative 为内部绝对定位元素预留定位上下文；
 * cursor: pointer 表示整行可点击打开详情；
 * align-items: flex-start 让多行正文与图标顶部对齐。
 */
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

/*
 * .msg:hover：整行悬停反馈。
 * 使用次表面色作为背景，帮助用户识别当前指向的消息行。
 */
.msg:hover {
  background: var(--surface-2);
}

/*
 * .msg + .msg：相邻消息行之间的分隔线。
 * 只给非首行加顶部边框，避免列表最上方出现多余线条。
 */
/*
 * 使用相邻兄弟选择器而不是给每行加边框，
 * 可以避免最后一行出现多余的下边框；
 * 边框颜色使用全局分隔线变量。
 */
.msg + .msg {
  border-top: 1px solid var(--line);
}

/*
 * .m-ic：类型图标容器。
 * 固定 42×42，圆角 11px；
 * flex: none 防止在长标题下被压缩；
 * 内部居中显示图标，颜色与背景由行内 style 提供。
 */
/*
 * 图标背景与前景色由行内 style 设置，
 * 这样同一套尺寸样式可以复用于不同消息类型；
 * .ic 内部图标尺寸由全局类控制。
 */
.m-ic {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
}

/*
 * .m-body：消息正文区域。
 * flex: 1 占据剩余宽度；
 * min-width: 0 允许内部文本正常收缩并触发省略号，
 * 否则 flex 子项会因内容宽度撑破布局。
 */
/*
 * 正文宽度变化时，省略号只在 .m-preview 内生效；
 * 标题和徽章行仍保持自然宽度，
 * 因此需同时限制 min-width 与溢出行为。
 */
.m-body {
  flex: 1;
  min-width: 0;
}

/*
 * .m-top：标题与多个徽章的信息行。
 * 横向排列、垂直居中，徽章之间用 gap 控制间距。
 */
/*
 * 信息行允许徽章按内容宽度排列；
 * 标题过长时由正文区域的布局约束吸收，
 * 不会把右侧时间挤出可视区。
 */
.m-top {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 3px;
}

/*
 * .m-top h3：消息标题。
 * 使用基础字号与粗体，margin 归零由父级 gap 控制布局。
 */
.m-top h3 {
  font-size: var(--t-base);
  font-weight: 700;
  margin: 0;
}

/*
 * .m-preview：消息内容预览。
 * 使用 -webkit-line-clamp 限制为单行；
 * 配合 overflow: hidden 与 box-orient 实现省略号效果。
 */
.m-preview {
  font-size: var(--t-sm);
  color: var(--ink-2);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/*
 * .m-time：创建时间。
 * 使用最小字号与弱化色，靠右并禁止收缩；
 * margin-top 让时间与标题基线大致对齐。
 */
/*
 * 时间不参与换行，flex: none 保证它始终可见；
 * 弱化颜色让视觉焦点停留在标题和内容上。
 */
.m-time {
  font-size: var(--t-2xs);
  color: var(--ink-3);
  flex: none;
  margin-top: 3px;
}

/*
 * .m-dot：未读圆点。
 * 8×8 圆形，使用警示色；
 * 外层 box-shadow 形成浅色光晕，提高未读提示的可见度；
 * flex: none 防止被压缩。
 */
/*
 * 圆点颜色固定为警示色，不随消息类型变化；
 * 它表达的是「未读」状态，而不是消息严重程度。
 */
.m-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--bad);
  flex: none;
  margin-top: 7px;
  box-shadow: 0 0 0 3px var(--bad-bg);
}

/*
 * .msg.read .m-top h3：已读消息的标题样式。
 * 降低字重并改用次级文字色，与未读消息形成视觉区分。
 */
/*
 * 已读状态只降低标题权重，
 * 不改变字号和行高，避免列表在已读后发生布局跳动。
 */
.msg.read .m-top h3 {
  font-weight: 600;
  color: var(--ink-2);
}

/*
 * .msg.read .m-dot：已读消息隐藏未读圆点。
 * 使用 visibility 而不是 display，保持行内布局宽度不变，避免跳动。
 */
.msg.read .m-dot {
  visibility: hidden;
}

/*
 * .m-mark：行内「标记已读」按钮。
 * flex: none 防止被压缩；
 * align-self: center 在行内垂直居中；
 * 左侧留 2px 间距，与时间、圆点区分开。
 */
/*
 * 链接式按钮沿用全局 .link-btn 的颜色与悬停样式，
 * 这里只补充布局约束，避免覆盖全局交互样式。
 */
.m-mark {
  flex: none;
  align-self: center;
  margin-left: 2px;
}

/*
 * .list-foot：列表底部分页栏。
 * 使用 flex 在总数/每页条数与分页按钮之间分散对齐；
 * flex-wrap 允许窄屏换行，gap 控制两组的间距。
 */
.list-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}
</style>
