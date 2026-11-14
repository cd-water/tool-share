<script setup lang="ts">
/**
 * DashboardView —— 管理端首页数据总览。
 * 职责：
 * 1. 并行拉取仪表盘统计快照与工具台账（用于分类聚合），驱动 KPI、状态环、分类柱、待办与动态。
 * 2. 所有展示数值都来自后端真实统计；后端没有按日趋势接口时明确显示空态，不伪造趋势。
 * 3. 用 Canvas 手绘环形图与柱状图，支持 DPR 高清、入场动画、主题切换与窗口缩放重绘。
 * 4. 依据登录用户是否超级管理员，决定快捷入口与待办跳转的目标路由。
 * 数据流：onMounted -> loadDashboard -> stats/tools -> computed 派生 -> watch 触发 Canvas 绘制。
 * 边界：统计接口失败会保持 stats 为 null，页面停留在骨架屏；工具分页拉取失败则整页加载失败。
 * 可维护性：图表全部由统计字段派生，新增指标时优先扩展 AdminDashboardVO 与对应 computed。
 * 该页面不持有全局状态，所有数据随组件创建/销毁，离开页面即释放。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
// 路由实例：快捷入口与待办项通过 router.push 做页内跳转，不刷新整页。
import { useRouter } from 'vue-router'
// 全局鉴权 Store：提供当前用户名与超级管理员标记，用于问候语和权限化入口。
import { useAuthStore } from '@/stores/auth'
// 仪表盘统计接口：一次返回工具、租赁、报修、用户等聚合计数。
import { dashboardApi } from '@/api/dashboard'
// 工具接口：分类图表需要按分类统计，故额外分页拉取工具台账。
import { toolApi } from '@/api/tools'
// 类型定义：AdminDashboardVO 为统计快照，ToolVO 为工具台账行。
import type { AdminDashboardVO, ToolVO } from '@/api/types'

// 取得路由对象；模板中直接调用 router.push，不做命名路由封装。
const router = useRouter()
// 注入鉴权 Store；auth.isSuperAdmin 是响应式的，可直接参与 computed。
const auth = useAuthStore()
// 超级管理员判定：仅用于“新增工具”按钮与“维修验收”待办的目标路由。
// 注意这是 computed，权限变化（如切换账号）后模板会自动更新。
const isSuperAdmin = computed(() => auth.isSuperAdmin)

// 页面级加载开关：控制加载态判断与请求期间的视觉反馈。
const loading = ref(false)
// 统计快照；null 表示尚未加载完成，模板据此展示骨架屏而非 0 值。
// 接口成功后整体替换，不保留旧值，避免短暂展示过期数据。
const stats = ref<AdminDashboardVO | null>(null)
// 工具台账全量结果（按后端分页拉齐），只用于按 category 聚合分类数量。
const tools = ref<ToolVO[]>([])

/* ---------- 统计 API 与数据逻辑 ---------- */
/**
 * 读取统计快照中的某个计数字段。
 * @param key AdminDashboardVO 的数值字段名，如 toolTotal / overdueRentalCount。
 * @returns 字段值；快照未加载时返回 0，保证模板与 Canvas 不会拿到 undefined。
 * 该函数被 KPI、状态环、待办、动态、迷你统计与欢迎语多处复用。
 * 只做读取，不修改快照，因此可以安全地在 computed 中反复调用。
 */
function statValue(key: keyof AdminDashboardVO): number {
// 可选链 + 空值合并：stats 为 null 或字段缺失时统一落回 0。
  return stats.value?.[key] ?? 0
}

/** 分类统计需要完整台账：后端每页上限 100，按总数补齐所有页。 */
/**
 * 拉取完整工具台账，供分类柱状图统计使用。
 * 背景：后端 toolApi.list 分页参数 size 有上限 100，直接请求大 size 会被截断。
 * 做法：先取第 1 页拿到 total，再按 ceil(total / 100) 计算总页数，并发补齐其余页。
 * @returns 所有页 records 按页序拼接后的工具数组。
 * 副作用：发起 1 + (pageCount - 1) 个并发 HTTP 请求。
 * 边界：total 为 0 时 pageCount 为 0，走 pageCount <= 1 分支直接返回首页空数组。
 * 失败语义：任一页请求失败都会让 Promise.all 整体 reject，由 loadDashboard 统一处理。
 */
async function loadAllTools(): Promise<ToolVO[]> {
// 固定每页 100：与后端允许的最大 size 对齐，避免被服务端截断。
  const size = 100
// 首页请求同时提供 total，用于推导还需要补几页。
// 首页请求携带 page=1 与 size=100；返回体同时包含 records 与 total。
// 这里必须等待首页完成，后续补页依赖 total。
  const first = await toolApi.list({ page: 1, size })
// 总页数 = 总数 / 每页 100 向上取整；total 缺失时按 0 处理。
  const pageCount = Math.ceil((first.total || 0) / size)
// 只有一页（或没有数据）时无需并发，直接返回，减少无谓请求。
  if (pageCount <= 1) return first.records
// 其余页并发请求；Promise.all 保证结果顺序与页码一致，失败会整体 reject。
// 补页请求并发发出，页数很少（工具规模通常远小于几百），无需限流。
// 每一页沿用相同 size，保证拼接后没有重复或遗漏。
  const rest = await Promise.all(
// Array.from 生成长度为 pageCount - 1 的页码序列，从第 2 页开始。
// 这里的下划线是占位参数，i 才是页偏移量。
    Array.from({ length: pageCount - 1 }, (_, i) => toolApi.list({ page: i + 2, size })),
  )
// 先按页序拼接再 flatMap 取 records，保证分类统计不受分页顺序影响。
  return [first, ...rest].flatMap((page) => page.records)
}

/**
 * 仪表盘主加载流程。
 * 并行请求统计快照与全量工具台账，任一失败都会进入 finally 并向上抛出，
 * 由调用处（onMounted 未捕获）表现为控制台错误，页面保持骨架屏。
 * 副作用：设置 loading；成功后同时写入 stats 与 tools。
 * 设计取舍：宁可整页失败也不展示半套数据，避免 KPI 与分类图口径不一致。
 * 不做轮询，数据只在进入页面时加载一次。
 */
async function loadDashboard() {
// 进入加载态：模板目前用 stats === null 判断骨架屏，loading 仅作辅助状态。
  loading.value = true
// try/finally 只负责复位 loading，不吞异常，方便定位接口问题。
  try {
// 两个请求互不依赖，用 Promise.all 并行，缩短首屏等待时间。
// 注意 all 是“全成功才成功”，统计接口失败时工具请求结果也不会被采用。
// dashboardApi.get() 返回聚合计数，loadAllTools() 返回台账明细。
// 二者并行执行，任一失败都会让本次加载整体失败。
    const [dashboard, allTools] = await Promise.all([dashboardApi.get(), loadAllTools()])
// 统计快照整体替换；watch(stats) 随后触发 Canvas 重绘。
    stats.value = dashboard
// 台账整体替换；categoryBars 会基于新数组重新计算。
    tools.value = allTools
// 无论成功失败都关闭加载态，避免按钮/骨架屏永久卡住。
  } finally {
// 复位加载开关。
    loading.value = false
  }
}

/**
 * 分类柱状图数据：把工具台账按后端分类编码聚合成四根柱子。
 * 编码映射：WX=维修、YY=园艺、QJ=清洁、QT=其他。
 * 输出字段：v 为数量，label 为中文名，c/c2 为渐变起止色（CSS 变量，绘制时解析）。
 * 说明：过滤基于已拉齐的全量台账，因此不会因为分页只统计到前 100 条。
 * 边界：未知分类编码不会进入固定四类，分类加总可能小于 toolTotal。
 */
const categoryBars = computed(() => {
// 后端分类编码到中文展示名的映射；未知编码不会进入固定四类。
// 映射表只覆盖后端约定的四类编码，新增分类时需要同步维护。
  const names: Record<string, string> = { WX: '维修', YY: '园艺', QJ: '清洁', QT: '其他' }
// 固定四类顺序输出，保证柱状图颜色与位置稳定，即使某类数量为 0 也保留位置。
// 颜色统一使用品牌主色/辅色渐变，与页面其他图表保持同一视觉体系。
  return ['WX', 'YY', 'QJ', 'QT'].map((key) => ({
// 每次 computed 重新遍历台账；数据量为全量工具数，属于可接受的 O(n) 过滤。
    v: tools.value.filter((tool) => tool.category === key).length,
    label: names[key],
    c: 'var(--brand)',
    c2: 'var(--brand-2)',
  }))
})

/* ---------- 问候与日期 ---------- */
// 页面打开时的时间快照：问候语和日期基于此刻计算，不随后台时间流逝自动刷新。
// 若需要跨天自动更新，可在页面重新获得焦点时刷新该快照。
const now = new Date()
// 立即执行函数按当前小时选择问候语，区间左闭右开：
// [0,6) 夜深、[6,12) 早上、[12,14) 中午、[14,18) 下午、其余晚上。
// 使用固定文案而非按分钟变化，避免用户长时间停留时文字频繁跳动。
const greet = (() => {
// 以浏览器本地时间为准；不做时区换算，因为问候语面向当前使用者。
  const h = now.getHours()
// 凌晨 0-5 点：避免使用“早上好”，用更贴切的“夜深了”。
  if (h < 6) return '夜深了'
// 6-11 点：标准早间问候。
  if (h < 12) return '早上好'
// 12-13 点：中午短暂区间。
  if (h < 14) return '中午好'
// 14-17 点：下午问候。
  if (h < 18) return '下午好'
// 18 点之后统一为晚上。
  return '晚上好'
})()
// 星期索引到中文的映射：Date.getDay() 返回 0=周日 ... 6=周六。
const WEEK_CN = ['日', '一', '二', '三', '四', '五', '六']
// 欢迎区副标题：拼接中文日期、星期与英文 OVERVIEW，
// 月份与日期使用 getMonth()+1 修正从 0 开始的月份。
// 该字符串在组件创建时固定，不做定时刷新（页面通常不会跨天驻留）。
const dateText = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 · 星期${WEEK_CN[now.getDay()]} · OVERVIEW`
// 展示名：优先使用鉴权 Store 中的用户名，缺失时回退为“工作人员”。
// 用 computed 包裹，账号信息变化时问候标题会自动更新。
const displayName = computed(() => auth.username || '工作人员')

/* ---------- 内联 SVG 图标(与原型同款描边风格) ---------- */
// 内联 SVG path 字典，避免为少量图标引入组件或额外依赖。
// 图标统一 24x24 viewBox、currentColor 描边，通过 icon() 包装为完整 <svg> 字符串。
const ICONS: Record<string, string> = {
// 工具箱：KPI“工具总数”。
  box: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="m3.3 7 8.7 5 8.7-5M12 22V12"/>',
// 列表：KPI“活跃租赁”与待办“租赁跟进”。
  list: '<path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
// 警告三角：逾期预警、待处理报修与对应待办。
  warn: '<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/>',
// 盾牌：报修/维修相关指标。
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
// 右箭头：待办项的行尾指示。
  chevron: '<path d="m9 18 6-6-6-6"/>',
}
/**
 * 生成内联 SVG 字符串，供 v-html 渲染。
 * @param name ICONS 中的图标键名，未知键名输出空内容而不报错。
 * @param cls 额外 class，默认 'ic'；调用方可传 'ic sm' 控制尺寸。
 * 使用固定描边参数，保证与页面其他图标视觉一致。
 * 返回的是受控字符串，内容只来自本文件常量，不拼接任何用户输入。
 */
function icon(name: string, cls = 'ic'): string {
// 统一设置 viewBox、描边宽度、圆角端点与 aria-hidden，避免屏幕阅读器重复朗读装饰图标。
// 未知键名用空字符串兜底，仍输出一个空 svg，不破坏布局。
// path 内容来自上方 ICONS 常量，不存在注入风险。
// 尺寸与颜色由 class 和 currentColor 控制，调用方无需关心内部细节。
  return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] ?? ''}</svg>`
}

/* ---------- KPI(主指标,仅展示真实统计) ---------- */
/**
 * KPI 主指标：只读取统计接口真实字段，不做任何环比/趋势推算。
 * 四项分别为工具总数、活跃租赁、逾期预警、待处理报修。
 * 每项包含文案、数值、图标、前景/背景色与脚注，模板按顺序渲染四张卡片。
 * 数值为 0 是合法状态，不会隐藏卡片，便于管理员感知“当前没有异常”。
 */
const kpiItems = computed(() => [
// 全部台账规模，来自 dashboard.toolTotal。
  { label: '工具总数', value: statValue('toolTotal'), icon: 'box', fg: 'var(--brand)', bg: 'var(--brand-bg)', note: '全部台账' },
// 预约 + 使用中的租赁数，来自 dashboard.activeRentalCount。
  { label: '活跃租赁', value: statValue('activeRentalCount'), icon: 'list', fg: 'var(--info)', bg: 'var(--info-bg)', note: '预约与使用中' },
// 已逾期的租赁数，来自 dashboard.overdueRentalCount。
  { label: '逾期预警', value: statValue('overdueRentalCount'), icon: 'warn', fg: 'var(--bad)', bg: 'var(--bad-bg)', note: '需跟进' },
// 未完结报修数，来自 dashboard.pendingDamageCount。
  { label: '待处理报修', value: statValue('pendingDamageCount'), icon: 'shield', fg: 'var(--warn)', bg: 'var(--warn-bg)', note: '未完结报修' },
])

/* ---------- 状态环 / 图例(真实数据) ---------- */
/**
 * 工具状态环图例与环形图数据源。
 * 三个状态由统计接口直接给出：可借用、已租出、维修中。
 * 颜色使用硬编码十六进制，与状态语义对应；Canvas 绘制时原样使用。
 * 注意：三者之和理论上应等于 toolTotal，但页面不强行校验。
 * 若后端新增状态，需要同时扩展此数组与对应统计字段。
 */
const statusLegend = computed(() => [
// 绿色：当前可借用的工具数量。
  { label: '可借用', v: statValue('availableToolCount'), c: '#2E7D46' },
// 琥珀色：已租出、尚未归还的工具数量。
  { label: '已租出', v: statValue('rentedToolCount'), c: '#B4741C' },
// 橙色：处于维修中的工具数量。
  { label: '维修中', v: statValue('maintenanceToolCount'), c: '#C77700' },
])

/* ---------- 待办事项(由统计推导) ---------- */
// 待办角标总数：把待处理报修与逾期租赁相加，仅用于标题右侧的提示徽标。
const todoCount = computed(() => statValue('pendingDamageCount') + statValue('overdueRentalCount'))
/**
 * 待办事项列表：全部由统计字段推导，不请求额外的待办接口。
 * 每项包含标题、数值、单位、配色、图标与点击跳转路由。
 * 数值为 0 的项会被过滤，因此“暂无待办”空态等价于所有相关计数为 0。
 * 跳转目标是静态路由，具体页面再按权限做二次校验。
 */
const todoItems = computed(() => {
  const list = [
// 报修待处理 -> 跳转报修管理，颜色与“待处理报修”KPI 保持一致。
// 数量单位用“条”，与报修业务口径一致。
    { title: '处理报修', value: statValue('pendingDamageCount'), unit: '条报修待处理', fg: 'var(--warn)', bg: 'var(--warn-bg)', icon: 'warn', path: '/damage' },
// 逾期租赁 -> 跳转租赁管理，使用危险色强调需要催还。
// 数量单位用“笔”，与租赁业务口径一致。
    { title: '催还逾期', value: statValue('overdueRentalCount'), unit: '笔租赁已逾期', fg: 'var(--bad)', bg: 'var(--bad-bg)', icon: 'warn', path: '/rentals' },
// 进行中的租赁仅作跟进提示，数量通常大于逾期数。
// 使用信息色，避免与警告/危险项争夺注意力。
    { title: '租赁跟进', value: statValue('activeRentalCount'), unit: '笔租赁进行中', fg: 'var(--info)', bg: 'var(--info-bg)', icon: 'list', path: '/rentals' },
// 维修验收：超级管理员跳工具管理，其他人跳报修管理，避免越权入口。
// 使用成功色，表示这是相对正向的处理动作。
    { title: '维修验收', value: statValue('maintenanceToolCount'), unit: '件工具维修中', fg: 'var(--ok)', bg: 'var(--ok-bg)', icon: 'shield', path: isSuperAdmin.value ? '/tools' : '/damage' },
  ]
// 过滤掉数量为 0 的待办，避免出现“0 条报修待处理”这类噪音项。
  return list.filter((i) => i.value > 0)
})

/* ---------- 最近动态(由统计推导) ---------- */
/**
 * 最近动态：用统计计数拼出“发生了什么”，并非真实事件流。
 * 只在对应计数大于 0 时追加一条，顺序按业务关注度排列。
 * 每条包含文案、圆点颜色与右侧时间标签；模板以“共享工具房”作为统一来源说明。
 * 这是有意的简化：没有逐条事件接口时，用聚合计数提供可读的概览。
 */
const feedItems = computed(() => {
  const list: Array<{ text: string; color: string; time: string }> = []
// 今日新增租赁：最有时效性的动态，放在第一条。
// 只有大于 0 才展示，避免空事件占位。
  if (statValue('todayRentalCount') > 0)
// 文案直接嵌入真实计数，时间标签标记为“今天”。
    list.push({ text: `今日新增 ${statValue('todayRentalCount')} 笔租赁预约`, color: 'var(--brand)', time: '今天' })
  if (statValue('pendingDamageCount') > 0)
// 待核验报修：需要管理员尽快处理，标记为“实时”。
    list.push({ text: `${statValue('pendingDamageCount')} 条报修待核验`, color: 'var(--warn)', time: '实时' })
// 逾期租赁：需要催还，标记为“需关注”。
  if (statValue('overdueRentalCount') > 0)
// 逾期条目使用危险色，与 KPI/待办的语义保持一致。
    list.push({ text: `${statValue('overdueRentalCount')} 笔租赁已逾期`, color: 'var(--bad)', time: '需关注' })
// 可借用工具：展示库存健康度。
  if (statValue('availableToolCount') > 0)
    list.push({ text: `${statValue('availableToolCount')} 件工具可借用`, color: 'var(--ok)', time: '工具' })
// 注册用户数：累计指标，用于体现用户规模。
// 使用中性钢色，弱于业务异常类动态。
  if (statValue('userCount') > 0)
    list.push({ text: `${statValue('userCount')} 位注册用户`, color: 'var(--steel)', time: '累计' })
  return list
})

/* ---------- 次要统计 ---------- */
/**
 * 底部迷你统计条：对 KPI 做补充，展示更多状态与今日新增。
 * 同样是统计接口字段的直接映射，不含趋势计算。
 * 各项使用独立色点，便于快速扫读。
 */
const miniStats = computed(() => [
// 可借用工具数，与状态环第一段同源。
  { label: '可借用', value: statValue('availableToolCount'), color: '#2E7D46' },
// 已租出工具数。
  { label: '已租出', value: statValue('rentedToolCount'), color: '#B4741C' },
// 维修中工具数。
  { label: '维修中', value: statValue('maintenanceToolCount'), color: '#C77700' },
// 累计注册用户数。
  { label: '注册用户', value: statValue('userCount'), color: '#0E7C86' },
// 今日新增租赁预约数。
  { label: '今日新增租赁', value: statValue('todayRentalCount'), color: '#F7A600' },
])

/* ---------- MiniChart(Canvas 柱/线/环/迷你图) ---------- */
// 柱状图数据项：v 为柱高依据，label 为横轴文字，c/c2 可选渐变起止色。
// 未提供 c/c2 时绘制函数会回退到品牌主色/辅色。
interface BarDatum { v: number; label: string; c?: string; c2?: string }
// 环形图数据项：label 用于图例，v 为扇区权重，c 为扇区颜色。
// v 是相对权重，不要求总和为 100。
interface DonutDatum { label: string; v: number; c: string }

/**
 * MiniChart 配置的判别联合：
 * - bar：柱状图，data 为 BarDatum 数组；
 * - donut：环形图，可指定中心数字与中心说明文字。
 * height 缺省时绘制函数回退到 180px。
 * 使用 type 字段做可辨识属性，绘制函数据此分支。
 */
type MiniChartCfg =
  | { type: 'bar'; height?: number; data: BarDatum[] }
  | { type: 'donut'; height?: number; center?: number; centerLabel?: string; data: DonutDatum[] }

/**
 * 在给定 canvas 上绘制一个迷你图表（柱状图或环形图）。
 * @param canvas 模板 ref 绑定的 canvas 元素。
 * @param cfg 图表类型与数据；类型决定调用 drawBar 还是 drawDonut。
 * 处理要点：
 * - 依据 devicePixelRatio 放大画布物理像素并 scale，避免高分屏模糊；
 * - 从 CSS 变量读取字体与颜色，主题切换后重新调用即可跟随新配色；
 * - 使用 requestAnimationFrame 做 900ms 缓动入场动画；
 * - 每次都按当前 clientWidth 重设尺寸，因此窗口 resize 后重绘会自动适配。
 * 副作用：修改 canvas 的 width/height/style，并启动动画帧循环。
 * 该函数不返回 Promise，动画结束后自行停止。
 */
function drawMiniChart(canvas: HTMLCanvasElement, cfg: MiniChartCfg) {
// DPR 上限取 2：兼顾清晰度与显存/绘制开销，避免 3x 屏画布过大。
// 取不到 devicePixelRatio 时按 1 处理。
// 先取 DPR 再取上下文，二者都不依赖画布尺寸。
// 上限 2 是性能与清晰度的折中，Retina 屏幕上肉眼已足够细腻。
  const dpr = Math.min(2, window.devicePixelRatio || 1)
// 获取 2D 上下文；浏览器不支持时直接放弃绘制。
  const ctx = canvas.getContext('2d')
// 没有上下文时静默返回，避免后续空指针。
  if (!ctx) return
// 优先使用 canvas 自身布局宽度；脱离文档流或尚未布局时退回父元素宽度。
// 最后兜底 300px，保证初次挂载时也能画出合理图形。
  const W = canvas.clientWidth || (canvas.parentNode as HTMLElement)?.clientWidth || 300
// 图表高度由配置指定，未指定时使用 180px。
  const H = cfg.height || 180
// 画布物理像素 = CSS 尺寸 * DPR，这样绘制内容不会在高分屏上被拉伸模糊。
  canvas.width = W * dpr
// 同步设置高度物理像素，与宽度保持相同缩放策略。
  canvas.height = H * dpr
// CSS 高度保持逻辑像素，避免元素在页面上被放大 DPR 倍。
  canvas.style.height = H + 'px'
// 把绘图坐标系缩放到 DPR，后续所有坐标都可直接按 CSS 像素书写。
  ctx.scale(dpr, dpr)
// 读取 body 的计算样式，用于解析主题中定义的 CSS 变量。
  const css = getComputedStyle(document.body)
// 字体优先取设计令牌 --font-sans，取不到时回退 sans-serif。
  const font = css.getPropertyValue('--font-sans').trim() || 'sans-serif'
// 颜色解析：以 var( 开头的值会去掉包装、读取 CSS 变量；
// 变量缺失或普通颜色字符串则按原值/兜底色使用。
// 这样主题切换时只要重新绘制就能拿到新变量值。
// var( 的截取范围是 4 到 -1，即去掉 “var(” 与右括号。
  const col = (v: string) => (v.startsWith('var(') ? css.getPropertyValue(v.slice(4, -1)).trim() || '#B4741C' : v)

// 圆角矩形路径工具：Canvas 没有内置 roundRect 时的手写实现。
// 只负责构建路径，不填充也不描边，由调用方决定样式。
  function roundRect(c: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
// 允许高度为负（例如从基线向上画）：先归正 y 与 h 再绘制。
    if (h < 0) { y += h; h = -h }
// 圆角半径不能超过高度或宽度的一半，防止路径自交。
    r = Math.min(r, h / 2, w / 2)
// 开始新路径，避免与上一根柱子的路径粘连。
    c.beginPath()
// 从左上角圆角起点开始。
    c.moveTo(x + r, y)
// 右上角：用 arcTo 画外切圆角。
    c.arcTo(x + w, y, x + w, y + h, r)
// 右下角：半径 0 表示直角。
    c.arcTo(x + w, y + h, x, y + h, 0)
// 左下角：仍为直角。
    c.arcTo(x, y + h, x, y, 0)
// 回到左上角，补上最后一个圆角。
    c.arcTo(x, y, x + w, y, r)
// 闭合路径，供调用方 fill。
    c.closePath()
  }

/**
 * 绘制柱状图。
 * @param p 动画进度 0~1，柱高与柱顶数值显隐都按它插值。
 * 布局：左右各留 pad 内边距，横向等分 n 个槽位，柱宽取槽位的 55%。
 * 细节：先画 4 条浅色横向网格线，再画带线性渐变的圆角柱，最后画标签与数值。
 * 数值只在动画接近结束时出现，避免入场过程中数字乱跳。
 */
// 柱状图绘制闭包捕获 W/H/ctx/col/font，避免在每帧重新计算主题变量。
// p 由外层动画循环传入，同一帧内所有柱子共享同一进度。
  const drawBar = (p: number) => {
// 数据项集合；类型已在配置联合中约束为 BarDatum[]。
    const d = cfg.data as BarDatum[]
// 柱子数量；调用侧保证有数据，否则后续除法会得到 Infinity。
    const n = d.length
// 图表左右内边距，为纵轴数值和首个/末个柱子留出呼吸空间。
    const pad = 28
// 柱宽 = 单槽宽度 * 0.55，剩余空间形成柱间空隙。
    const bw = ((W - pad * 2) / n) * 0.55
// 槽宽 = 可用宽度 / 柱子数量。
    const gap = (W - pad * 2) / n
// 取最大值作为高度归一化基准；全为 0 时兜底 1，避免除零。
// 数值差距很大时，小柱子会接近不可见，这是无对数轴的简化取舍。
    const max = Math.max(...d.map((x) => x.v)) || 1
// 网格线使用主题线条色，线宽 1px。
    ctx.strokeStyle = col('var(--line)')
// 网格线线宽固定 1 逻辑像素。
    ctx.lineWidth = 1
// 画 4 条水平网格线（g=0..3），形成 3 段等距参考区间。
// 网格线只做视觉参考，不绘制纵轴刻度文字。
    for (let g = 0; g <= 3; g++) {
// 底部预留 24px 给标签；可用绘图高度为 H - 48。
      const y = H - 24 - ((H - 48) * g) / 3
// 每条网格线独立开路径，避免与之前的路径相连。
      ctx.beginPath()
// 从左侧内边距画到右侧内边距。
      ctx.moveTo(pad, y)
// 保持无填充，仅描边。
      ctx.lineTo(W - pad, y)
// 提交当前路径。
      ctx.stroke()
    }
// 遍历数据项逐个绘制柱子。
    d.forEach((x, i) => {
// 柱高按数值占比与动画进度缩放。
      const h = (H - 48) * (x.v / max) * p
// 柱子在槽位内水平居中。
      const bx = pad + gap * i + (gap - bw) / 2
// 柱顶 y 坐标：基线在 H-24，向上减去柱高。
      const by = H - 24 - h
// 每根柱子创建独立的纵向线性渐变。
      const gr = ctx.createLinearGradient(0, by, 0, H - 24)
// 渐变顶部颜色：优先使用数据项自带 c，否则用品牌主色。
      gr.addColorStop(0, col(x.c || 'var(--brand)'))
// 渐变底部颜色：优先 c2，否则用品牌辅色。
      gr.addColorStop(1, col(x.c2 || 'var(--brand-2)'))
// 应用渐变作为填充样式。
      ctx.fillStyle = gr
// 用圆角矩形路径绘制柱子，圆角 5px。
      roundRect(ctx, bx, by, bw, h, 5)
// 填充柱子。
      ctx.fill()
// 标签使用次要文字色。
      ctx.fillStyle = col('var(--ink-3)')
// 横轴标签字号 11px，字体沿用主题字体。
      ctx.font = '11px ' + font
// 标签水平居中。
      ctx.textAlign = 'center'
// 在柱子正下方绘制分类名，纵向固定在底部预留区。
      ctx.fillText(x.label, bx + bw / 2, H - 7)
// 只有动画基本结束（进度 > 0.9）时才显示数值，避免入场过程中数字跳动。
      if (p > 0.9) {
// 数值使用正文次要色。
        ctx.fillStyle = col('var(--ink-2)')
// 数值加粗到 600，与标签区分。
        ctx.font = '600 11px ' + font
// 数值画在柱顶上方 6px。
        ctx.fillText(String(x.v), bx + bw / 2, by - 6)
      }
    })
  }

/**
 * 绘制环形图。
 * @param p 动画进度 0~1，控制每个扇区从 0 扫开到目标角度。
 * 结构：外半径 R、内半径 r（0.62R），从 12 点方向顺时针铺满整圈。
 * 中心显示自定义数字（默认总数）与说明文字。
 * 某个扇区权重为 0 时不会占据角度，但仍会参与循环。
 */
// 环形图同样复用外层已解析好的颜色函数与画布上下文。
// 扇区从 12 点方向开始，视觉上更符合“总量拆解”的阅读习惯。
  const drawDonut = (p: number) => {
// 扇区数据集合，已由配置约束为 DonutDatum[]。
    const d = cfg.data as DonutDatum[]
// 权重总和；为 0 时兜底 1，避免除零导致角度 NaN。
// 注意中心数字不一定等于该总和，调用方可以显式覆盖。
    const total = d.reduce((s, x) => s + x.v, 0) || 1
// 圆心 x 取画布水平中点。
    const cx = W / 2
// 圆心 y 取画布垂直中点。
    const cy = H / 2
// 外半径：取宽高较小者的一半，再留 14px 边距。
    const R = Math.min(W, H) / 2 - 14
// 内半径取外半径的 62%，形成环带宽度。
    const r = R * 0.62
// 起始角 -90°，对应 Canvas 的 12 点方向。
// 之后顺时针累加，形成从顶部开始的阅读顺序。
    let a = -Math.PI / 2
// 按数据顺序逐段绘制扇区，累计角度。
    d.forEach((x) => {
// 当前扇区角度 = 权重占比 * 2π * 动画进度。
      const ang = (x.v / total) * Math.PI * 2 * p
// 每个扇区独立路径。
      ctx.beginPath()
// 外弧：从当前角度顺指针画到结束角。
      ctx.arc(cx, cy, R, a, a + ang)
// 内弧：反向画回起点，形成环带而非实心扇形。
      ctx.arc(cx, cy, r, a + ang, a, true)
// 闭合并填充为一块环形扇区。
      ctx.closePath()
// 扇区颜色来自数据项。
      ctx.fillStyle = col(x.c)
// 填充当前扇区。
      ctx.fill()
// 起点推进到当前扇区结束角，供下一段衔接。
      a += ang
    })
// 中心主数字使用主文字色。
    ctx.fillStyle = col('var(--ink)')
// 主数字字号 24px、字重 700。
    ctx.font = '700 24px ' + font
// 主数字水平居中。
    ctx.textAlign = 'center'
// 垂直方向按 middle 基线居中，便于数字精确定位。
    ctx.textBaseline = 'middle'
// 收窄配置类型以读取 center / centerLabel；二者都是可选的。
    const c = cfg as { center?: number; centerLabel?: string }
// 中心数字优先使用显式 center（如工具总数），否则回退权重总和。
    ctx.fillText(String(c.center != null ? c.center : total), cx, cy - 7)
// 中心说明文字使用弱化颜色。
    ctx.fillStyle = col('var(--ink-3)')
// 说明文字字号 11px。
    ctx.font = '11px ' + font
// 说明画在主数字下方 15px，如“工具总数”或“总计”。
    ctx.fillText(c.centerLabel || '总计', cx, cy + 15)
// 恢复默认基线，避免影响后续其他文字绘制（虽然本函数通常只画一次）。
    ctx.textBaseline = 'alphabetic'
  }

// 动画起始时间戳；第一帧到来时记录，之后用于计算进度。
// t0 为 null 表示动画尚未开始；首帧赋值后不再改变。
  let t0: number | null = null
// 入场动画总时长 900ms。
// 固定时长让不同数据量的图表具有一致的入场节奏。
  const dur = 900
// 缓动函数 easeOutCubic：先快后慢，让柱子/扇区收尾更自然。
// 进度始终先截断到 1，缓动不会超过 1。
  const ease = (p: number) => 1 - Math.pow(1 - p, 3)
/**
 * requestAnimationFrame 回调：推进动画进度并重绘一帧。
 * @param ts 浏览器提供的高精度时间戳（毫秒）。
 * 每帧都整幅重绘，数据量很小，无需做局部刷新优化。
 */
  const draw = (ts: number) => {
// 第一帧只记录起始时间，此时进度为 0。
    if (t0 === null) t0 = ts
// 线性进度截断到 1 后套用缓动，得到 0~1 的绘制进度。
    const p = ease(Math.min(1, (ts - t0) / dur))
// 每帧先清空画布，避免旧帧叠加造成残影。
    ctx.clearRect(0, 0, W, H)
// 按配置类型分派到柱状图或环形图。
    if (cfg.type === 'bar') drawBar(p)
// 绘制函数内部读取同一进度 p，保证动画同步。
    else drawDonut(p)
// 未到 100% 时继续请求下一帧；到 1 后自然停止。
    if (p < 1) requestAnimationFrame(draw)
  }
// 启动动画循环；首帧时间戳由浏览器传入。
  requestAnimationFrame(draw)
}

/* ---------- 图表绘制编排 ---------- */
// 状态环 canvas 引用；模板通过 ref="statusCanvas" 写入。
// 两个 canvas 引用分开保存，便于 drawCharts 独立判空。
const statusCanvas = ref<HTMLCanvasElement | null>(null)
// 分类柱状图 canvas 引用；ref="catCanvas"。
const catCanvas = ref<HTMLCanvasElement | null>(null)

/**
 * 按当前 stats 与派生数据重绘两张 Canvas。
 * 状态环：中心显示工具总数，扇区为可借用/已租出/维修中。
 * 分类柱：四类工具数量，颜色走品牌渐变。
 * 没有 stats（加载中或失败）时直接跳过，避免画出空图。
 * 该函数会被 watch、resize 防抖与主题观察器共同调用，必须是幂等的。
 */
// drawCharts 不读取 loading，只以 stats 是否为空作为数据就绪信号。
function drawCharts() {
// 统计尚未就绪：不绘制，等待 watch(stats) 再次触发。
  if (!stats.value) return
// 状态环 canvas 已挂载时才绘制。
  if (statusCanvas.value) {
// 配置环形图：高度 200px，与右侧面板高度协调。
// 中心展示工具总数而非扇区总和，避免口径差异引起误解。
    drawMiniChart(statusCanvas.value, {
      type: 'donut',
// 中心数字取统计接口的 toolTotal。
      height: 200,
      center: statValue('toolTotal'),
      centerLabel: '工具总数',
// 图例与环图共用同一份数据，保证颜色和数字一致。
      data: statusLegend.value.map((s) => ({ label: s.label, v: s.v, c: s.c })),
    })
  }
// 分类柱 canvas 已挂载时才绘制。
  if (catCanvas.value) {
// 柱状图高度 210px；数据来自 categoryBars 的四类聚合。
    drawMiniChart(catCanvas.value, { type: 'bar', height: 210, data: categoryBars.value })
  }
}

/**
 * 统计快照就绪后重绘图表。
 * @param val 新的统计对象；为 null（首次/失败）时直接返回。
 * 使用 nextTick 等待 v-else 分支的 canvas 完成挂载，否则 ref 仍为 null。
 * deep 未开启：stats 是整体替换，浅层 watch 足够。
 */
// 监听的是整个 stats 对象引用；接口每次返回新对象，因此浅监听即可触发。
// 回调内部再做一次 null 判断，覆盖初始值和异常情况。
watch(stats, async (val) => {
// 空快照不绘制；保持骨架屏。
  if (!val) return
// 等待 DOM 更新：从骨架屏切到数据内容后 canvas 才存在。
  await nextTick()
// 执行两张图的绘制。
  drawCharts()
})

// resize 重绘的防抖定时器句柄；用 number 兼容浏览器 setTimeout 返回值。
// 定时器句柄在卸载时需要取消，否则回调可能访问已销毁的 canvas。
let redrawTimer: number | undefined
/**
 * 窗口尺寸变化的防抖重绘。
 * 160ms 内的连续 resize 只触发最后一次绘制，降低 Canvas 重建频率。
 * 副作用：设置/清除 redrawTimer。
 */
function scheduleRedraw() {
// 已有待执行任务先取消，重新计时。
  if (redrawTimer) clearTimeout(redrawTimer)
// 160ms 后统一重绘；此时布局已稳定，clientWidth 可取到最终值。
  redrawTimer = window.setTimeout(drawCharts, 160)
}
// 监听 body class 的观察器，用于捕获明暗主题切换。
// null 表示尚未在 onMounted 中初始化，卸载时用可选链安全断开。
// observer 句柄同样需要在卸载时断开，防止 body class 变化继续触发绘制。
let themeObserver: MutationObserver | null = null

/**
 * 组件挂载：启动数据加载并注册全局监听。
 * - resize：窗口尺寸变化后防抖重绘 Canvas；
 * - MutationObserver：body class 变化（如加 dark）时立即重绘，让 CSS 变量色生效。
 * 监听只注册一次，卸载时成对清理。
 */
// 挂载阶段只做三件事：拉数据、监听 resize、监听主题。
// 数据加载不阻塞后续监听注册，避免慢接口影响交互准备。
onMounted(() => {
// 异步加载统计与工具台账；不 await，避免阻塞挂载流程。
  loadDashboard()
// 使用防抖版本，避免拖动窗口时每像素都重建画布。
  window.addEventListener('resize', scheduleRedraw)
  // 主题切换(body.dark)时重绘 Canvas,让颜色跟随 CSS 变量
// 主题切换通常一次性完成，因此不做防抖。
// 仅在已有统计数据时重绘，加载阶段无需处理。
  themeObserver = new MutationObserver(() => {
// 数据就绪时立刻重绘，读取切换后的 CSS 变量颜色。
    if (stats.value) drawCharts()
  })
// 只观察 body 的 class 属性变化，忽略其他属性以减少回调。
// subtree 未开启：主题类通常加在 body 自身。
  themeObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] })
})

/**
 * 组件卸载清理：移除 resize 监听、断开 MutationObserver、取消未执行的防抖任务。
 * 这三项都是全局/异步资源，不清理会在组件销毁后继续触发绘制并造成泄漏。
 * 清理顺序不影响结果，但先断开观察器可避免清理期间的额外回调。
 */
// 卸载后组件不再渲染，任何延迟回调都必须被阻止。
// 清理逻辑与 onMounted 中的注册一一对应，便于审查是否遗漏。
onUnmounted(() => {
// 移除与挂载时同引用的防抖函数，保证 removeEventListener 生效。
  window.removeEventListener('resize', scheduleRedraw)
// 断开主题观察器，停止接收 body class 变化。
  themeObserver?.disconnect()
// 取消尚未执行的防抖重绘，避免卸载后访问已销毁的 canvas。
  if (redrawTimer) clearTimeout(redrawTimer)
})
</script>

<template>
<!-- 仪表盘页面根容器：内部按“欢迎区 -> KPI -> Bento 面板 -> 迷你统计”纵向排列。 -->
<!-- 页面数据未就绪时只渲染欢迎区与骨架屏；stats 有值后整体切换到真实内容。 -->
<!-- 根节点 class 同时作为样式作用域入口，内部所有规则均以 .dashboard 为上下文。 -->
<!-- 页面不包含全局滚动容器，滚动由管理端整体布局负责。 -->
  <div class="dashboard">
<!-- 欢迎区：展示日期、问候语、当日关键数字与快捷操作入口。 -->
    <!-- 欢迎区 -->
<!-- reveal 是全局入场动画类，页面首次渲染时淡入。 -->
    <section class="dash-welcome reveal">
      <div>
<!-- 顶部小字：完整日期 + 星期 + OVERVIEW 标识，来自脚本中的 dateText。 -->
        <p class="eyebrow">{{ dateText }}</p>
<!-- 主标题：按当前小时生成的问候语 + 当前登录用户名。 -->
        <h1>{{ greet }}，{{ displayName }}</h1>
<!-- 副标题：stats 就绪后展示今日新增预约、逾期、待处理报修三个真实计数。 -->
        <p class="sub">
<!-- v-if 分支：只有统计快照存在时才渲染具体数字，避免先闪 0。 -->
          <template v-if="stats">
            今天有
<!-- 今日新增租赁计数：data-count 供全局数字滚动动画读取，初始文本为 0。 -->
            <b class="num" style="color: var(--warn)" :data-count="statValue('todayRentalCount')">0</b> 笔新预约、
<!-- 逾期租赁计数：危险色强调需要跟进的数量。 -->
            <b class="num" style="color: var(--bad)" :data-count="statValue('overdueRentalCount')">0</b> 笔逾期、
<!-- 待处理报修计数：信息色，与 KPI 区保持同一数据源。 -->
            <b class="num" style="color: var(--info)" :data-count="statValue('pendingDamageCount')">0</b> 条报修待处理。
          </template>
<!-- v-else 分支：接口尚未返回或失败时提示正在加载。 -->
          <template v-else>正在加载今日概览…</template>
        </p>
      </div>
<!-- 快捷操作区：按钮是否出现及跳转目标由权限决定。 -->
      <div class="dash-quick">
<!-- 新增工具仅超级管理员可见，跳转工具管理页；普通管理员看不到该入口。 -->
        <button v-if="isSuperAdmin" class="btn btn-primary" @click="router.push('/tools')">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
          新增工具
        </button>
<!-- 核验报修对所有管理员开放，统一跳转报修管理页。 -->
        <button class="btn btn-outline" @click="router.push('/damage')">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
          核验报修
        </button>
<!-- 命令面板按钮：data-cmdk 由全局命令面板逻辑接管点击，不在本组件绑定事件。 -->
        <button class="btn btn-outline" data-cmdk>
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/></svg>
          命令面板
        </button>
      </div>
    </section>

    <!-- 骨架屏 -->
<!-- 骨架屏：stats 为 null 时渲染，尺寸与真实 KPI/Bento 布局大致对齐，减少切换抖动。 -->
    <template v-if="!stats">
      <div class="kpi-row">
<!-- 四张 KPI 骨架卡片，对应真实区域的四列布局。 -->
        <div class="kpi" v-for="i in 4" :key="i">
<!-- 模拟标题行。 -->
          <div class="skel" style="height: 12px; width: 60%; margin-bottom: 16px"></div>
<!-- 模拟大号数字行。 -->
          <div class="skel" style="height: 36px; width: 45%"></div>
<!-- 模拟脚注行。 -->
          <div class="skel" style="height: 12px; width: 75%; margin-top: 16px"></div>
        </div>
      </div>
<!-- Bento 区域骨架：五块占位，列跨度与真实面板一致。 -->
<!-- 骨架屏中的 Bento 仅做占位，不绑定任何 Canvas 或数据。 -->
<!-- 骨架块数量与真实面板数量一致，加载完成后布局不会出现大幅跳动。 -->
      <div class="bento">
<!-- 趋势面板占两列。 -->
        <div class="skel b-skel" style="grid-column: span 2"></div>
<!-- 状态环面板占一列。 -->
        <div class="skel b-skel"></div>
<!-- 待办面板占一列。 -->
        <div class="skel b-skel"></div>
<!-- 分类柱面板占两列；动态面板同样两列，由下一行继续占位。 -->
        <div class="skel b-skel" style="grid-column: span 2"></div>
      </div>
    </template>

    <!-- 数据内容 -->
<!-- 数据内容区：stats 已就绪后整体渲染。 -->
    <template v-else>
      <!-- KPI 主指标 -->
<!-- KPI 主指标：四张卡片顺序对应工具总数、活跃租赁、逾期预警、待处理报修。 -->
<!-- KPI 区四张卡片共用同一套结构，差异只来自 kpiItems 的数据。 -->
      <div class="kpi-row reveal">
<!-- 遍历 kpiItems；每项携带颜色、图标与脚注，卡片结构完全一致。 -->
        <div class="kpi" v-for="item in kpiItems" :key="item.label">
<!-- 卡片顶部：左侧指标名，右侧带底色的小图标。 -->
          <div class="k-top">
            <span class="k-label">{{ item.label }}</span>
<!-- v-html 渲染脚本生成的 SVG 字符串；背景/前景色来自数据项。 -->
            <span class="k-ic" :style="{ color: item.fg, background: item.bg }" v-html="icon(item.icon)"></span>
          </div>
<!-- 主数值：data-count 供数字滚动动画，初始文本 0 会在挂载后递增到目标值。 -->
          <b class="k-val num" :data-count="item.value">0</b>
<!-- 脚注：解释口径，如“全部台账”“预约与使用中”。 -->
          <div class="k-foot"><span>{{ item.note }}</span></div>
        </div>
      </div>

      <!-- Bento 布局 -->
<!-- Bento 网格：趋势/状态/待办/分类/动态五个面板按列跨度拼合。 -->
<!-- 真实数据下的 Bento 网格，与上方骨架屏使用相同的列跨度规则。 -->
      <div class="bento">
<!-- 趋势面板：后端暂无按日趋势接口，因此这里只展示明确的空态。 -->
<!-- 趋势面板是唯一明确标注“数据待接入”的区域，体现不虚构数据的原则。 -->
        <div class="panel b-trend reveal">
          <h3 class="panel-title">近 7 日租赁趋势</h3>
<!-- 空态文案直接说明“不虚构趋势”，避免把静态统计误读为 7 日走势。 -->
          <!-- 后端暂无按日趋势接口，接入真实数据后再绘制，不虚构趋势 -->
          <div class="empty">
            <h4>趋势数据待接入</h4>
<!-- 只有统计接口提供按日序列后，才应替换为真实折线/柱状图。 -->
            <p>当前只展示实时统计，不虚构趋势。</p>
          </div>
        </div>

<!-- 工具状态分布：环形图 + 图例，数据来自 statusLegend。 -->
<!-- 状态环面板的图例文本直接拼接状态名与数量，保持与扇区同源。 -->
        <div class="panel b-status reveal">
<!-- 图表标题。 -->
          <h3 class="panel-title">工具状态分布</h3>
<!-- 有工具时才渲染画布与图例；toolTotal 为 0 时走空态。 -->
          <template v-if="statValue('toolTotal') > 0">
<!-- chart-box 提供固定高度容器；canvas 由 drawMiniChart 按 DPR 设置尺寸。 -->
            <div class="chart-box"><canvas ref="statusCanvas"></canvas></div>
<!-- 图例逐项展示颜色、状态名与数量，与环图扇区一一对应。 -->
            <div class="legend">
              <span v-for="s in statusLegend" :key="s.label"><i :style="{ background: s.c }"></i>{{ s.label }} {{ s.v }}</span>
            </div>
          </template>
<!-- 空态：没有任何工具台账时提示先录入。 -->
          <div v-else class="empty">
            <h4>暂无工具数据</h4>
            <p>录入手工台账后展示状态分布。</p>
          </div>
        </div>

<!-- 待办事项：由统计计数推导，数量为 0 的项已在 computed 中过滤。 -->
<!-- 待办标题的徽标使用 danger 样式，突出“有事情要处理”。 -->
        <div class="panel b-todo reveal">
<!-- 标题右侧徽标显示待办总数（报修 + 逾期），即使列表为空也展示 0。 -->
          <h3 class="panel-title">待办事项<span class="badge bad">{{ todoCount }}</span></h3>
<!-- 有可处理事项时渲染列表；否则展示空态。 -->
          <ul v-if="todoItems.length" class="todo">
<!-- 整行可点击，跳转路由由数据项的 path 决定，权限差异已在数据层处理。 -->
            <li v-for="t in todoItems" :key="t.title" @click="router.push(t.path)">
<!-- 左侧图标带语义色背景，与 KPI/动态保持统一视觉语言。 -->
              <span class="t-ic" :style="{ color: t.fg, background: t.bg }" v-html="icon(t.icon)"></span>
              <div>
<!-- 待办标题，如“处理报修”“催还逾期”。 -->
                <b>{{ t.title }}</b>
<!-- 数值 + 单位，例如“3 条报修待处理”。 -->
                <span>{{ t.value }} {{ t.unit }}</span>
              </div>
<!-- 行尾箭头提示可跳转；复用 icon() 生成的 chevron。 -->
              <span class="t-go" v-html="icon('chevron', 'ic sm')"></span>
            </li>
          </ul>
<!-- 空态：所有待办计数都为 0 时显示。 -->
          <div v-else class="empty">
            <h4>暂无待办</h4>
            <p>当前没有需要处理的事项。</p>
          </div>
        </div>

<!-- 分类工具数量：四类柱状图，数据来自全量台账聚合。 -->
<!-- 分类面板标题与柱状图一一对应，四类顺序在脚本中固定。 -->
        <div class="panel b-cat reveal">
          <h3 class="panel-title">分类工具数量</h3>
<!-- tools 非空才绘制；canvas 尺寸与柱状图高度由 drawMiniChart 控制。 -->
          <div v-if="tools.length" class="chart-box"><canvas ref="catCanvas"></canvas></div>
<!-- 空态：工具台账为空时提示录入。 -->
          <div v-else class="empty">
            <h4>暂无工具数据</h4>
            <p>录入工具后展示分类数量。</p>
          </div>
        </div>

<!-- 最近动态：由统计计数拼出的事件摘要，点击“全部”跳转消息中心。 -->
<!-- 动态面板的“全部”链接进入消息中心，那里可以提供更细的事件列表。 -->
        <div class="panel b-feed reveal">
<!-- 标题行内嵌 router-link，使用 link-btn 样式与右侧对齐。 -->
          <h3 class="panel-title">最近动态<router-link class="link-btn" to="/messages">全部</router-link></h3>
<!-- 有动态时渲染列表；动态条数取决于哪些计数大于 0。 -->
          <ul v-if="feedItems.length" class="feed">
<!-- 用索引作为 key 是安全的：列表在每次 computed 求值时整体重建，顺序稳定。 -->
            <li v-for="(f, idx) in feedItems" :key="idx">
<!-- 状态圆点颜色与动态语义一致（品牌/警告/危险/成功/中性）。 -->
              <span class="f-dot" :style="{ background: f.color }"></span>
              <div>
<!-- 动态正文，如“今日新增 2 笔租赁预约”。 -->
                <b>{{ f.text }}</b>
<!-- 统一来源说明，强调数据来自共享工具房系统。 -->
                <span>共享工具房</span>
              </div>
<!-- 右侧时间标签：今天 / 实时 / 需关注 / 工具 / 累计。 -->
              <span class="f-time">{{ f.time }}</span>
            </li>
          </ul>
<!-- 空态：所有统计计数均为 0 时显示。 -->
          <div v-else class="empty">
            <h4>暂无动态</h4>
            <p>产生租赁、报修或用户注册后展示。</p>
          </div>
        </div>
      </div>

      <!-- 次要统计 -->
<!-- 底部迷你统计条：补充展示库存、用户与今日新增等次要指标。 -->
<!-- 迷你统计条与 KPI 区形成主次层级，不重复展示四张主卡片。 -->
      <div class="mini-stats reveal">
<!-- 每个 mini 项包含颜色点、数值与标签，横向排列并允许换行。 -->
        <div class="mini" v-for="m in miniStats" :key="m.label">
          <span class="m-dot" :style="{ background: m.color }"></span>
<!-- 数值同样参与全局数字滚动动画。 -->
          <b class="num" :data-count="m.value">0</b>
<!-- 指标名称，如“今日新增租赁”。 -->
          <span>{{ m.label }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 欢迎区：水平两端对齐，底部对齐；空间不足时快捷按钮换行到下一行。 */
/* 底部外边距使用 --sp-5，与下方 KPI 区拉开层次。 */
/* 该规则同时定义欢迎区与快捷操作区的响应式换行行为。 */
.dash-welcome{display:flex;align-items:flex-end;justify-content:space-between;gap:var(--sp-4);flex-wrap:wrap;margin-bottom:var(--sp-5)}
/* 问候主标题：3xl 字号 + 800 字重，负字距让大标题更紧凑。 */
/* 标题下方只留 1 级间距，副标题紧跟。 */
.dash-welcome h1{font-size:var(--t-3xl);font-weight:800;letter-spacing:-.02em;margin:0 0 var(--sp-1)}
/* 欢迎区副标题：小字号、次要文字色，默认无外边距。 */
.dash-welcome .sub{font-size:var(--t-sm);color:var(--ink-2);margin:0}
/* 快捷操作按钮组：横向排列，允许换行，按钮间距 2 级。 */
.dash-quick{display:flex;gap:var(--sp-2);flex-wrap:wrap}
/* KPI 主指标区：四列等宽网格，四张卡片在桌面端一行排开。 */
/* 底部外边距与 Bento 区隔开。 */
/* 四列固定网格在 1100px 以下由媒体查询降为两列。 */
.kpi-row{display:grid;grid-template-columns:repeat(4,1fr);gap:var(--sp-4);margin-bottom:var(--sp-4)}
/* KPI 卡片：表面色背景 + 1px 边框 + 卡片圆角 + 阴影。 */
/* position:relative 与 overflow:hidden 为后续装饰元素预留裁剪边界。 */
.kpi{padding:var(--sp-4) var(--sp-5);background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);box-shadow:var(--shadow);position:relative;overflow:hidden}
/* 卡片顶部行：指标名与图标左右分布。 */
/* 底部 10px 间距把标题行与大号数字分开。 */
.kpi .k-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
/* 指标名：xs 字号、次要色、600 字重，弱化但不失可读性。 */
.kpi .k-label{font-size:var(--t-xs);color:var(--ink-2);font-weight:600}
/* 图标容器：34x34 圆角方块，用 flex 居中内联 SVG。 */
.kpi .k-ic{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center}
/* 主数值：4xl 字号、800 字重、负字距，行高 1 让数字更醒目。 */
/* tabular-nums 保证不同数字等宽，避免数字滚动时布局抖动。 */
.kpi .k-val{display:block;font-size:var(--t-4xl);font-weight:800;letter-spacing:-.03em;line-height:1;font-variant-numeric:tabular-nums}
/* 卡片脚注：小间距横向排列，2xs 字号，使用三级文字色。 */
.kpi .k-foot{display:flex;align-items:center;gap:var(--sp-2);margin-top:var(--sp-2);font-size:var(--t-2xs);color:var(--ink-3)}
/* Bento 网格：四列等宽，面板之间统一间距。 */
/* 各面板通过 grid-column:span 控制跨列，形成大小不一的卡片布局。 */
/* Bento 的四列基准由各面板的 span 值共同决定最终布局。 */
.bento{display:grid;grid-template-columns:repeat(4,1fr);gap:var(--sp-4)}
/* 趋势面板占两列，是视觉主区域。 */
.b-trend{grid-column:span 2}
/* 状态环面板占一列，与待办并排。 */
.b-status{grid-column:span 1}
/* 待办面板占一列。 */
.b-todo{grid-column:span 1}
/* 分类柱面板占两列。 */
.b-cat{grid-column:span 2}
/* 最近动态面板占两列。 */
.b-feed{grid-column:span 2}
/* 待办列表：去掉默认列表样式，纵向排列。 */
/* 列表项之间用 2 级间距分隔。 */
.todo{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:var(--sp-2)}
/* 单个待办：图标 + 文案 + 箭头的横向布局。 */
/* 内边距、浅色背景、细边框与 10px 圆角形成可点击卡片。 */
/* transition 让 hover 位移与边框色变化更平滑。 */
.todo li{display:flex;align-items:center;gap:var(--sp-3);padding:var(--sp-3);border-radius:10px;background:var(--surface-2);border:1px solid var(--line);transition:all var(--fast);cursor:pointer}
/* hover：边框提亮并向右轻移 3px，暗示“可进入”。 */
.todo li:hover{border-color:var(--line-2);transform:translateX(3px)}
/* 待办图标容器：30x30、8px 圆角、flex 居中。 */
/* flex:none 防止在文字较长时被压缩。 */
.todo .t-ic{width:30px;height:30px;border-radius:8px;display:flex;align-items:center;justify-content:center;flex:none}
/* 待办标题：sm 字号、600 字重、块级独占一行。 */
.todo b{font-size:var(--t-sm);font-weight:600;display:block;line-height:1.3}
/* 待办描述：2xs 字号、三级文字色。 */
.todo span{font-size:var(--t-2xs);color:var(--ink-3)}
/* 行尾箭头：margin-left:auto 推到最右侧。 */
.todo .t-go{margin-left:auto;color:var(--ink-3)}
/* 动态列表：去掉默认样式，纵向排列且项间不留 gap。 */
.feed{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:0}
/* 单条动态：圆点 + 文案 + 时间的横向布局，底部分隔线。 */
.feed li{display:flex;gap:var(--sp-3);padding:var(--sp-3) 0;border-bottom:1px solid var(--line)}
/* 最后一条不画分隔线，避免列表尾部多出一条线。 */
.feed li:last-child{border-bottom:none}
/* 状态圆点：9px 圆形，顶部偏移 6px 与首行文字视觉对齐。 */
/* 外圈用 surface-2 阴影模拟描边，增强与背景的分离。 */
.feed .f-dot{width:9px;height:9px;border-radius:50%;margin-top:6px;flex:none;box-shadow:0 0 0 3px var(--surface-2)}
/* 动态正文：sm 字号、600 字重、块级显示。 */
.feed b{font-size:var(--t-sm);font-weight:600;display:block}
/* 动态副文本：2xs 字号、三级文字色。 */
.feed span{font-size:var(--t-2xs);color:var(--ink-3)}
/* 时间标签：推到最右侧，不换行，等宽数字避免跳动。 */
.feed .f-time{margin-left:auto;font-size:var(--t-2xs);color:var(--ink-3);white-space:nowrap;font-variant-numeric:tabular-nums}
/* 迷你统计条：横向排列、允许换行，与上方 Bento 拉开间距。 */
/* 迷你统计条不参与 Bento 网格，独立成行展示。 */
.mini-stats{display:flex;gap:var(--sp-3);flex-wrap:wrap;margin-top:var(--sp-4)}
/* 单项迷你统计：表面色胶囊卡片，3/4 级内边距，小圆角与阴影。 */
/* 内部元素等距排列，整体高度与 KPI 卡片形成节奏对比。 */
.mini{display:flex;align-items:center;gap:var(--sp-2);padding:var(--sp-3) var(--sp-4);background:var(--surface);border:1px solid var(--line);border-radius:11px;box-shadow:var(--shadow);font-size:var(--t-sm)}
/* 迷你统计数值：800 字重、lg 字号、等宽数字。 */
.mini b{font-weight:800;font-variant-numeric:tabular-nums;font-size:var(--t-lg)}
/* 迷你统计标签：次要色、xs 字号。 */
.mini span{color:var(--ink-2);font-size:var(--t-xs)}
/* 迷你统计圆点：8px 圆形色标。 */
.mini .m-dot{width:8px;height:8px;border-radius:50%}
/* 骨架屏大块占位：固定高度 280px，与真实面板高度接近。 */
/* 圆角复用卡片圆角，保证骨架与真实内容外形一致。 */
.b-skel{height:280px;border-radius:var(--r-card)}
/* 中等屏幕（<=1100px）：KPI 与 Bento 降为两列。 */
/* 趋势/分类/动态仍占满两列，状态与待办各占一列。 */
/* 断点内同时调整 KPI 与 Bento，保证两区在中等屏幕上的列数一致。 */
@media(max-width:1100px){.kpi-row{grid-template-columns:repeat(2,1fr)}.bento{grid-template-columns:repeat(2,1fr)}.b-trend,.b-cat,.b-feed{grid-column:span 2}.b-status,.b-todo{grid-column:span 1}}
/* 窄屏（<=680px）：所有区域单列堆叠。 */
/* 每个面板显式恢复 span 1，避免继承上一断点的两列设置。 */
@media(max-width:680px){.bento{grid-template-columns:1fr}.b-trend,.b-cat,.b-feed,.b-status,.b-todo{grid-column:span 1}.kpi-row{grid-template-columns:1fr}}
</style>
