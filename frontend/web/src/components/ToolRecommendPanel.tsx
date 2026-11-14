/**
 * 文件职责：AI 工具推荐面板（ToolRecommendPanel）。
 *
 * 使用场景：
 * - AiPage 的“工具推荐”标签页；
 * - ToolsPage 的“AI 帮我选”弹窗。
 *
 * 交互流程：
 * 1. 用户用自然语言描述需求（可选填写时间、类别、押金上限）；
 * 2. 前端做基础校验后调用 aiApi.recommend，后端结合知识库与工具库存返回
 *    推荐摘要、降级标记与若干工具条目；
 * 3. 用户点击条目时上报点击事件（recommendClick）并回调 onSelectTool，
 *    由外层决定打开详情弹窗或跳转工具广场；
 * 4. 用户可对本次推荐做“有帮助 / 没帮助”反馈（recommendFeedback）。
 *
 * 本组件不负责路由跳转、权限判断或工具详情渲染；它只处理推荐请求的
 * 表单、加载态、错误重试、空结果与反馈交互。所有文案均为中文。
 */
import { useState } from 'react'
import {
  ArrowRight,
  CircleAlert,
  Loader2,
  MapPin,
  RefreshCw,
  Sparkles,
  ThumbsDown,
  ThumbsUp,
  Wrench,
} from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { ToolCategory, ToolRecommendItem, ToolRecommendResult } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/components/ui/toast'
import { formatMoney } from '@/lib/format'

// 工具类别选项与 ToolsPage/后端枚举保持一致：
// WX 维修、YY 园艺、QJ 清洁、QT 其他。
// 这里只用于筛选表单，不参与推荐算法本身。
// 若后端新增类别，需要同步更新此常量与 API 类型定义。
const CATEGORY_OPTIONS: { value: ToolCategory; label: string }[] = [
  { value: 'WX', label: '维修工具' },
  { value: 'YY', label: '园艺工具' },
  { value: 'QJ', label: '清洁工具' },
  { value: 'QT', label: '其他' },
]

// 示例问题用于降低首次使用门槛：
// 三个例子分别覆盖“维修打孔”“园艺修剪”“清洁冲洗”高频场景。
// 点击后仅填入输入框，不自动提交，用户仍可修改。
// 文案应保持口语化，避免术语堆砌。
const EXAMPLES = ['墙上打孔挂窗帘', '院子里的树枝需要修剪', '想清洗阳台和地面']

/**
 * 推荐结果中的工具缩略图。
 *
 * @param props.item 推荐条目，使用其中的 imageUrl、name 字段。
 * @returns 有图时渲染懒加载图片；无图或加载失败时渲染渐变占位 + 扳手图标。
 *
 * 副作用：内部维护 failed 状态，图片 onError 后切换到占位；
 * 该状态只影响当前缩略图，不会触发父组件重渲染。
 * 图片使用 object-cover，保证任意比例都不变形。
 */
function ToolThumb({ item }: { item: ToolRecommendItem }) {
  const [failed, setFailed] = useState(false)
  // 无图或加载失败时进入占位分支：
  // 占位背景使用主题渐变，避免纯色块显得突兀；
  // 图标尺寸固定 size-8，与 80px 缩略图比例协调。
  // failed 一旦为 true 不再重试，避免破图反复请求。
  if (!item.imageUrl || failed) {
    return (
      // 占位分支：无图或加载失败时渲染渐变底 + 扳手图标。
      // 固定 size-full 让占位与图片尺寸一致，切换时不跳动。
      // 图标不参与交互，仅表达“这是一件工具”。
      <div className="flex size-full items-center justify-center bg-gradient-to-br from-secondary to-muted text-primary">
        <Wrench className="size-8" />
      </div>
    )
  }
  return (
  // 有图分支：懒加载 + object-cover，
  // onError 把 failed 置为 true，React 会重新渲染占位。
  // alt 使用工具名，保证图片语义可读。
  // 缩略图容器负责尺寸与圆角，图片本身撑满即可。
    // 有图分支：懒加载、等比裁切，失败后回退占位。
    // alt 使用工具名，保证图片内容可被读屏理解。
    // 图片尺寸完全由父容器决定，避免撑破卡片。
    <img
      src={item.imageUrl}
      alt={item.name}
      loading="lazy"
      className="size-full object-cover"
      onError={() => setFailed(true)}
    />
  )
}

/**
 * AI 工具推荐主面板。
 *
 * @param props.onSelectTool 选中某个推荐工具时的回调，参数为 toolId；
 *   外层通常用它打开工具详情弹窗或跳转到 /tools?tool=id。
 * @returns 推荐表单、错误条、结果列表与反馈区的组合。
 *
 * 副作用：
 * - 提交流程调用 aiApi.recommend；
 * - 选择条目时上报 aiApi.recommendClick（失败静默）；
 * - 反馈时调用 aiApi.recommendFeedback 并锁定反馈按钮。
 *
 * 状态说明：
 * - pending 控制请求中的按钮/输入禁用；
 * - result 为 null 时只显示表单；
 * - error 与 result 可同时存在（重试失败时保留旧结果）；
 * - feedback 一旦提交即锁定，避免重复反馈。
 */
export function ToolRecommendPanel({
  onSelectTool,
}: {
  onSelectTool?: (toolId: number) => void
}) {
  // 表单状态：
  // question 是需求描述（必填），其余筛选条件均为可选字符串，
  // 这样空字符串可以直接表示“未填写”，避免引入 undefined 联合。
  // startTime/endTime 使用 datetime-local 的字符串格式，提交时再转换。
  // 需求描述：唯一必填项，直接受控于文本框。
  // 使用字符串而非对象，方便与 textarea 双向绑定。
  // 不做本地持久化，关闭页面即清空。
  // 最大长度由 Textarea 的 maxLength 限制为 500。
  const [question, setQuestion] = useState('')
  // 可选开始时间，datetime-local 字符串。
  const [startTime, setStartTime] = useState('')
  // 可选结束时间，与开始时间成对校验。
  const [endTime, setEndTime] = useState('')
  // 可选类别，空字符串表示不限。
  const [category, setCategory] = useState('')
  // 可选押金上限，空字符串表示不限。
  const [maxDeposit, setMaxDeposit] = useState('')
  // 请求与结果状态：
  // pending 防止重复提交；result 保存最近一次成功结果；
  // feedback 记录本次推荐是否已被评价；error 保存可展示的失败原因。
  // result 不清空，便于重试失败时仍能看到上一次推荐。
  // 请求进行中标记：禁用提交与重试，防止并发。
  const [pending, setPending] = useState(false)
  // 最近一次成功结果；null 表示尚未推荐。
  const [result, setResult] = useState<ToolRecommendResult | null>(null)
  // 本次推荐是否已反馈：null 未反馈，1/2 已反馈。
  const [feedback, setFeedback] = useState<1 | 2 | null>(null)
  // 行内错误信息；与全局 toast 并存，便于重试。
  const [error, setError] = useState<string | null>(null)

/**
 * 提交推荐请求。
 *
 * @returns Promise<void>；无返回值，结果写入组件状态。
 *
 * 校验顺序：
 * 1. 需求描述不能为空；
 * 2. 开始/结束时间必须同时填写；
 * 3. 结束时间必须晚于开始时间；
 * 4. 押金上限若填写，必须是非负有限数字。
 *
 * 副作用：设置 pending/error/result/feedback，并调用 aiApi.recommend。
 * 校验失败通过 toast 提示，不发起网络请求。
 */
  // submit 是唯一发起推荐请求的入口：
  // 先做纯前端校验，再置 pending，最后调用 API。
  // 所有校验失败都通过 toast 提示并立即返回。
  // 不抛异常给调用方，按钮点击无需 try/catch。
  async function submit() {
    const text = question.trim()
    if (!text || pending) {
    // 需求描述为空时直接提示并返回：
    // 空描述无法让推荐算法理解场景，继续请求只会浪费一次调用。
    // pending 时同样直接返回，避免用户连点造成并发请求。
      if (!text) toast.add({ title: '请先描述你想做的事', type: 'error' })
      return
    }
    // 时间筛选必须成对出现：
    // 只填开始或只填结束都会让后端无法判断可用时段，
    // 因此前端提前拦截，并给出明确提示。
    if ((startTime && !endTime) || (!startTime && endTime)) {
      toast.add({ title: '请同时选择开始时间和结束时间', type: 'error' })
      return
    }
    // 时间顺序校验：结束必须严格晚于开始，
    // 相等或倒置都视为非法，避免后端返回空结果让用户困惑。
    // 使用 Date 解析 datetime-local 字符串，本地时区语义即可。
    if (startTime && endTime && new Date(endTime) <= new Date(startTime)) {
      toast.add({ title: '结束时间需晚于开始时间', type: 'error' })
      return
    }
    // 押金上限允许为空（表示不限）；
    // 非空时必须解析为有限数字且不小于 0，
    // 防止用户输入字母、负号或超大值导致后端校验失败。
    const deposit = maxDeposit.trim() === '' ? undefined : Number(maxDeposit)
    if (deposit !== undefined && (!Number.isFinite(deposit) || deposit < 0)) {
      toast.add({ title: '押金上限需为不小于 0 的数字', type: 'error' })
      return
    }

    // 校验全部通过后进入请求态：
    // 先清空旧错误，再置 pending，按钮与输入框据此禁用。
    // 不在这里清空旧 result，保留结果可减少界面闪烁。
    setPending(true)
    setError(null)
    try {
      // 调用推荐接口：
      // question 必传；时间、类别、押金仅在填写时传入；
      // limit=5 与后端约定一致，避免一次返回过多条目。
      // 接口失败由下方 catch 统一处理，成功则替换结果并重置反馈。
      const data = await aiApi.recommend({
        question: text,
        startTime: startTime || undefined,
        endTime: endTime || undefined,
        category: category ? (category as ToolCategory) : undefined,
        maxDeposit: deposit,
        limit: 5,
      })
      setResult(data)
      setFeedback(null)
      // 错误处理：http 拦截器已弹出全局提示，
      // 这里额外保存行内错误，提供“重试”入口。
      // 使用 Error.message，非 Error 时回退为通用文案。
    } catch (reason) {
      // http 拦截器已提示错误，这里补一个可重试的行内提示
      setError(reason instanceof Error ? reason.message : '推荐失败，请稍后重试。')
    } finally {
      setPending(false)
    }
  }

/**
 * 选择某个推荐工具。
 *
 * @param item 被点击的推荐条目。
 *
 * 副作用：
 * - 若本次推荐有 recommendationId，异步上报点击事件；上报失败静默，
 *   不阻塞用户进入详情；
 * - 调用 onSelectTool 通知外层打开详情。
 *
 * 点击上报用于评估推荐质量，属于“尽力而为”的埋点，
 * 因此不 await、不提示错误。
 */
  // handleSelect 同时承担“埋点上报”和“通知外层”两件事。
  // 埋点失败不影响用户进入详情，因此使用 void + catch。
  // onSelectTool 是可选回调，独立使用面板时可以不传。
  // toolId 由后端返回，外层用它查询详情或拼路由参数。
  function handleSelect(item: ToolRecommendItem) {
    if (result?.recommendationId) {
      void aiApi
        .recommendClick(result.recommendationId, item.toolId)
        .catch(() => undefined)
    }
    onSelectTool?.(item.toolId)
  }

/**
 * 提交推荐反馈。
 *
 * @param value 1 表示“有帮助”，2 表示“没帮助”。
 *
 * 副作用：
 * - 调用 aiApi.recommendFeedback 上报；
 * - 成功后写入 feedback 状态并 toast 提示；
 * - 失败静默（全局拦截器已提示），并允许用户再次点击重试。
 *
 * 前置条件：必须存在 recommendationId 且尚未反馈。
 */
  // sendFeedback 只允许对同一次推荐反馈一次。
  // recommendationId 缺失（例如旧接口）时直接忽略。
  // 请求成功后本地锁定按钮，避免重复提交。
  // 失败时保持未反馈状态，用户可再次点击。
  async function sendFeedback(value: 1 | 2) {
    if (!result?.recommendationId || feedback) return
    try {
      await aiApi.recommendFeedback(result.recommendationId, value)
      setFeedback(value)
      toast.add({
        title: value === 1 ? '感谢反馈' : '已记录，我们会继续优化推荐',
        type: 'success',
      })
    } catch {
      // http 拦截器已提示错误
    }
  }

  // 渲染分为四段：
  // 1) 推荐表单卡片（描述、可选筛选、示例、提交按钮）；
  // 2) 错误条（可重试）；
  // 3) 结果区（摘要、降级提示、工具列表或空状态）；
  // 4) 反馈区（有帮助/没帮助）。
  // 各段均按条件渲染，result 为空时只显示表单。
  return (
    // 根容器使用 space-y-5 统一四个区块的垂直间距：
    // 表单、错误条、结果、反馈依次排列。
    // 不使用 Fragment，保证外层可以按需设置宽度与背景。
    // 所有条件区块都不渲染占位，避免出现空白间隔。
    <div className="space-y-5">
      {/*
       * 表单卡片：白色背景、圆角边框，作为推荐的输入区域。
       * 它不使用 <form> 提交，而是由按钮触发 submit，
       * 因为面板可能嵌套在另一个表单/弹窗中，避免嵌套表单的非法结构。
       */}
      {/*
       * 表单卡片：
       * - 圆角、边框、卡片背景，与页面其他 panel 一致；
       * - 内边距在移动端略小，桌面端加大；
       * - 不使用 <form>，避免嵌套在弹窗表单中。
       */}
      <div className="rounded-2xl border border-border bg-card p-4 sm:p-5">
        {/*
         * 标题行：图标 + “描述你想做的事”，说明输入目的。
         * 图标使用主色，强化 AI 辅助的品牌感。
         */}
        {/*
         * 标题行：主色图标 + 说明文字。
         * 图标容器固定 28px，保证不同字号下对齐。
         * 文案直接说明“AI 帮你选工具”，降低理解成本。
         */}
        <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-foreground">
          <span className="flex size-7 items-center justify-center rounded-lg bg-primary/15 text-primary">
            <Sparkles className="size-4" />
          </span>
          描述你想做的事，AI 帮你选工具
        </div>
        {/*
         * 需求输入框：多行文本，最多 500 字。
         * placeholder 给出一个完整示例，降低表达门槛。
         * 输入内容直接受控于 question，不做防抖，因为不触发请求。
         */}
        {/*
         * 需求输入：
         * - 行数 3，适合一到两句话；
         * - placeholder 给出具体示例，而不是抽象提示；
         * - 背景使用卡片色，避免与表单卡片混在一起。
         */}
        <Textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={500}
          rows={3}
          placeholder="例如：家里墙面想打几个孔挂窗帘，希望工具轻便、押金不超过 300 元"
          className="bg-card"
        />
        {/*
         * 字数统计固定在右下角，仅展示不拦截；
         * maxLength 已由输入框限制，此处提供即时反馈。
         */}
        {/*
         * 字数统计：右对齐、次要文字色。
         * 只做提示，不阻止输入；超限由 maxLength 截断。
         */}
        <div className="mt-1 text-right text-xs text-muted-foreground">
          {question.length}/500
        </div>

        {/*
         * 可筛选条件网格：
         * 时间、类别、押金上限都是可选项，默认不限制。
         * 在移动端纵向堆叠，桌面端四列；由外层 grid 类控制。
         * 这些条件会原样传给后端，前端不做过多的语义推断。
         */}
        {/*
         * 筛选网格：
         * - 移动端单列，sm 两列，lg 四列；
         * - 四项均可选，留空表示不限；
         * - 与需求描述分开布局，避免输入区过密。
         */}
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {/*
           * 开始时间字段：Label + 原生 datetime-local 输入。
           */}
          <div>
          {/*
           * 开始时间：使用原生 datetime-local，
           * 浏览器保证格式合法，无需额外的日期选择器依赖。
           */}
            {/*
             * 标签使用 htmlFor 关联输入框 id，保证点击文字可聚焦控件。
             */}
            <Label htmlFor="recommend-start">开始时间（可选）</Label>
            <Input
              id="recommend-start"
              type="datetime-local"
              value={startTime}
              onChange={(event) => setStartTime(event.target.value)}
              className="mt-1 h-10 bg-card"
            />
          </div>
          {/*
           * 结束时间字段：与开始时间使用同样的控件与样式。
           */}
          <div>
          {/*
           * 结束时间：与开始时间成对校验，
           * 单独填写会被 submit 拦截并提示。
           */}
            <Label htmlFor="recommend-end">结束时间（可选）</Label>
            <Input
              id="recommend-end"
              type="datetime-local"
              value={endTime}
              onChange={(event) => setEndTime(event.target.value)}
              className="mt-1 h-10 bg-card"
            />
          </div>
          {/*
           * 类别字段：原生 select，选项来自 CATEGORY_OPTIONS。
           * “不限类别”对应空字符串，提交时转换为 undefined。
           */}
          <div>
          {/*
           * 类别筛选：不限类别 + 四个固定枚举，
           * 与工具广场的分类保持一致，便于用户理解。
           */}
            <Label htmlFor="recommend-category">类别（可选）</Label>
            <Select
              id="recommend-category"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              className="mt-1 h-10 bg-card"
            >
              <option value="">不限类别</option>
              {CATEGORY_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </Select>
          </div>
          {/*
           * 押金上限：数字输入，允许留空表示不限；
           * min=0/step=10 只是输入提示，真正校验在 submit。
           */}
          {/*
           * 押金上限字段：数字输入，空值表示不限。
           * step=10 只是输入步进提示，不做强制。
           */}
          <div>
            <Label htmlFor="recommend-deposit">押金上限（可选）</Label>
            <Input
              id="recommend-deposit"
              type="number"
              min={0}
              step={10}
              value={maxDeposit}
              onChange={(event) => setMaxDeposit(event.target.value)}
              placeholder="不限"
              className="mt-1 h-10 bg-card"
            />
          </div>
        </div>

        {/*
         * 底部操作行：左侧示例快捷填充，右侧提交按钮。
         * 示例按钮只写入输入框，不自动提交，用户仍可补充条件。
         * 提交按钮在 pending 或描述为空时禁用，避免无效请求。
         */}
        {/*
         * 底部操作行：
         * - 左侧示例快捷填充；
         * - 右侧主操作按钮；
         * - 两端对齐，窄屏自动换行。
         */}
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          {/*
           * 示例区：三个高频场景，点击只填入输入框。
           * 使用胶囊按钮，视觉上弱于主提交按钮。
           */}
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">试试：</span>
            {EXAMPLES.map((example) => (
            // 每个示例按钮直接 setQuestion，不触发请求；
            // 用户可在此基础上继续修改条件。
              <button
                key={example}
                type="button"
                onClick={() => setQuestion(example)}
                className="rounded-full border border-border bg-muted/20 px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
              >
                {example}
              </button>
            ))}
          </div>
          {/*
           * 提交按钮根据 pending 切换图标与文案：
           * 请求中显示旋转图标与“正在挑选…”，并禁用重复点击。
           * 图标与文字同时变化，兼顾视觉与读屏提示。
           */}
          {/*
           * 提交按钮：
           * - pending 或描述为空时禁用；
           * - 请求中换用旋转图标与“正在挑选…”；
           * - 成功后由结果区接管视觉焦点。
           */}
          <Button type="button" disabled={pending || !question.trim()} onClick={submit}>
            {pending ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <Sparkles className="size-4" />
            )}
            {pending ? '正在挑选…' : '帮我推荐'}
          </Button>
        </div>
      </div>

      {/*
       * 错误条：仅在请求失败时出现，展示后端/网络错误信息。
       * 右侧“重试”按钮复用 submit，保留用户已填写的条件。
       * 错误条与结果区可同时存在，便于对比上一次成功结果。
       */}
      {error && (
        // 错误条：
        // - 使用 destructive 低饱和背景；
        // - 展示后端或网络错误信息；
        // - 右侧重试按钮复用同一份表单数据。
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          <CircleAlert className="size-4 shrink-0" />
          <span className="min-w-0 flex-1">{error}</span>
          {/*
           * 重试按钮在 pending 时禁用，避免并发请求；
           * variant=outline 与错误条的低饱和背景区分层级。
           */}
          {/*
           * 重试按钮：pending 时禁用，避免重复请求；
           * outline 变体与错误条的警示色区分层级。
           */}
          <Button type="button" variant="outline" size="sm" disabled={pending} onClick={() => void submit()}>
            <RefreshCw className="size-3.5" />
            重试
          </Button>
        </div>
      )}

      {/*
       * 结果区：只有成功请求后才渲染。
       * 顶部是摘要与降级提示，下面是工具列表或空状态，
       * 最底部是反馈区。结果区不包含表单，避免重复输入。
       */}
      {result && (
        // 结果容器：摘要、降级提示、列表/空状态、反馈依次排列。
        // 使用 space-y-3 控制较紧凑的间距。
        <div className="space-y-3">
          {/*
           * 摘要行：展示后端生成的推荐说明，
           * 例如“根据你的需求，推荐以下工具”。
           * 文本允许换行，图标固定在左上角。
           */}
          {/*
           * 摘要卡片：
           * - 后端生成的推荐说明；
           * - 左侧固定 AI 图标，右侧文本可换行；
           * - 降级时在右侧追加 warning 徽标。
           */}
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-muted/20 px-4 py-3">
            <p className="flex items-start gap-2 text-sm text-foreground">
              <Sparkles className="mt-0.5 size-4 shrink-0 text-primary" />
              {result.summary}
            </p>
            {/*
             * 降级提示：后端在向量检索/模型不可用时会返回 degraded=true，
             * 此时结果按相关度排序，仍需明确告知用户。
             * 使用 warning 徽标，避免与成功态混淆。
             */}
            {/*
             * 降级标记：向量检索/模型不可用时，后端仍返回相关度结果。
             * 明确提示用户“部分智能服务不可用”，避免误解推荐质量。
             * 该分支只影响提示，不改变列表内容。
             */}
            {result.degraded && (
              <Badge tone="warning">部分智能服务不可用，已按相关度推荐</Badge>
            )}
          </div>

          {/*
           * 空结果分支：推荐接口成功但没有匹配工具。
           * 提供“换描述/放宽条件”的指引，而不是只显示“无数据”。
           * 使用 Empty 组合组件，与全站空状态风格一致。
           */}
          {result.items.length === 0 ? (
            // 空结果容器：虚线边框、较低背景对比度。
            // 与“错误”区分：这里是请求成功但没有合适工具。
            <div className="rounded-2xl border border-dashed border-border bg-card/50 p-8">
              {/*
               * Empty 组合组件：图标 + 标题 + 描述。
               * 标题说明“暂未找到”，描述给出放宽条件的建议。
               */}
              <Empty>
                <EmptyHeader>
                {/*
                 * 图标使用扳手，与工具主题一致；
                 * size-9 比常规图标略大，作为空状态视觉锚点。
                 */}
                  <Wrench className="mb-2 size-9 text-muted-foreground" />
                  <EmptyTitle>暂未找到合适的可用工具</EmptyTitle>
                  <EmptyDescription>
                    可以换一种描述，或放宽类别、押金、时间条件后再试。
                  </EmptyDescription>
                </EmptyHeader>
              </Empty>
            </div>
          ) : (
            // 结果网格：桌面端两列、移动端一列。
            // 每个条目是一个可点击卡片，支持鼠标与键盘（Enter/Space）。
            // 卡片内部按钮会阻止冒泡，避免一次点击触发两次选择。
            // 结果网格：两列布局，窄屏单列。
            // 每个卡片等宽，由 grid 自动分配。
            // 卡片数量由后端 limit 控制，前端不再截断。
            <div className="grid gap-3 sm:grid-cols-2">
              {result.items.map((item) => (
                // 单条推荐卡片：
                // - 整卡可点击，role=button + tabIndex 提供键盘可达性；
                // - aria-label 说明点击后查看详情；
                // - hover 时边框变为主色，提示可交互。
                <div
                  key={item.toolId}
                  role="button"
                  tabIndex={0}
                  aria-label={`查看 ${item.name} 详情`}
              // 点击整卡选择工具；键盘 Enter/Space 走同一逻辑。
              // 使用 event.stopPropagation 只在内部按钮上，
              // 避免内部“查看详情”按钮触发两次 handleSelect。
                  onClick={() => handleSelect(item)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      handleSelect(item)
                    }
                  }}
                  className="cursor-pointer overflow-hidden rounded-xl border border-border bg-card transition-colors hover:border-primary/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
                >
                {/*
                 * 卡片上半部：左侧缩略图，右侧名称、型号、押金与标签。
                 * 缩略图固定 80px，保证不同图片比例下卡片高度稳定。
                 */}
                  {/*
                   * 卡片上半部：缩略图 + 文本信息。
                   * gap-3 控制图片与文字的间距。
                   */}
                  <div className="flex gap-3 p-4">
                  {/*
                   * 缩略图容器负责尺寸、圆角与背景；
                   * 内部 ToolThumb 处理无图与加载失败。
                   */}
                    {/*
                     * 缩略图容器：固定 80px、圆角、浅背景。
                     * 内部 ToolThumb 负责图片与失败占位。
                     */}
                    <div className="size-20 shrink-0 overflow-hidden rounded-lg bg-muted/40">
                      <ToolThumb item={item} />
                    </div>
                    {/*
                     * 文本区域：min-w-0 允许内部 truncate 生效。
                     * 垂直方向按名称、型号、标签、位置排列。
                     */}
                    <div className="min-w-0 flex-1">
                  {/*
                   * 文本区域使用 min-w-0 + truncate，
                   * 保证长工具名/型号不会把押金挤出卡片。
                   */}
                      {/*
                       * 名称与押金同一行：
                       * 名称可截断，押金固定不换行，保证金额始终可见。
                       */}
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          {/*
                           * 工具名使用 truncate，避免长名称撑破卡片；
                           * 型号使用更小字号与次要色，形成层级。
                           */}
                          <h3 className="truncate font-semibold text-foreground">
                            {item.name}
                          </h3>
                          <p className="truncate text-xs text-muted-foreground">
                            {item.model}
                          </p>
                        </div>
                        {/*
                         * 押金右对齐、加粗，是用户决策的关键信息之一。
                         */}
                        {/*
                         * 押金右对齐、加粗，是用户比较工具时的关键字段。
                         */}
                        <span className="shrink-0 text-sm font-semibold text-foreground">
                          {formatMoney(item.deposit)}
                        </span>
                      </div>
                      {/*
                       * 标签行：类别与状态。
                       * 状态使用 success 色，表示当前可借（后端只会推荐可用工具）。
                       */}
                      {/*
                       * 标签行：类别（neutral）与状态（success）。
                       * 后端只推荐可用工具，因此状态固定为可用。
                       */}
                      <div className="mt-2 flex flex-wrap items-center gap-1.5">
                        <Badge tone="neutral">{item.categoryName}</Badge>
                        <Badge tone="success">{item.statusName}</Badge>
                      </div>
                      {/*
                       * 存放区域：长文本截断，避免撑破卡片。
                       * 图标仅作辅助，读屏通过文本理解。
                       */}
                      {/*
                       * 存放区域：图标 + 截断文本。
                       * 为空时显示“—”，避免出现空白行。
                       */}
                      <div className="mt-2 flex items-center gap-1 text-xs text-muted-foreground">
                        <MapPin className="size-3.5 shrink-0" />
                        <span className="truncate">{item.storageArea || '—'}</span>
                      </div>
                    </div>
                  </div>
                  {/*
                   * 卡片下半部：推荐理由与“查看详情”按钮。
                   * 推荐理由是 AI 生成的核心解释，必须完整展示，
                   * 因此允许换行、使用较小字号与较宽行高。
                   */}
                  {/*
                   * 卡片下半部：推荐理由 + 查看详情按钮。
                   * 与上半部用分隔线区分，保持信息层次。
                   */}
                  <div className="border-t border-border/70 px-4 py-3">
                    {/*
                     * 推荐理由：
                     * - 前缀“推荐理由：”加粗，帮助快速定位；
                     * - 正文使用次要色与宽松行高，适合多行阅读。
                     */}
                    <p className="text-xs leading-relaxed text-muted-foreground">
                      <span className="font-medium text-foreground">推荐理由：</span>
                      {item.reason}
                    </p>
                    {/*
                     * “查看详情”按钮阻止冒泡，避免与整卡点击重复触发；
                     * size=sm/outline 与卡片内的主要动作区分层级。
                     */}
                    {/*
                     * 查看详情按钮：
                     * - 阻止冒泡，避免与整卡点击重复触发；
                     * - 通栏宽度，移动端易点击；
                     * - 箭头暗示会进入详情。
                     */}
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="mt-2 w-full"
                      onClick={(event) => {
                        event.stopPropagation()
                        handleSelect(item)
                      }}
                    >
                      查看详情
                      <ArrowRight className="size-3.5" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/*
           * 反馈区：仅在存在推荐结果时出现。
           * 未反馈时展示两个按钮；反馈后替换为“已记录”文案，
           * 防止同一推荐被反复评价。
           */}
          {result.items.length > 0 && (
            // 反馈区：
            // - 仅在有条目时出现；
            // - 未反馈时展示两个操作；
            // - 已反馈时展示结果并锁定。
            <div className="flex flex-wrap items-center justify-center gap-2 pt-1 text-xs text-muted-foreground">
              {/*
               * 已反馈分支：根据 value 显示对应图标与文案，
               * 该状态在一次推荐内不可撤销，避免刷反馈。
               * 新的推荐请求成功后会把 feedback 重置为 null。
               * 已反馈分支：展示对应图标与“已记录”文案。
               * 该状态在当前推荐内不可撤销，避免重复计数。
               * 新推荐成功后 feedback 会被重置为 null。
               */}
              {feedback ? (
                <span className="inline-flex items-center gap-1.5 font-medium text-foreground">
                  {feedback === 1 ? <ThumbsUp className="size-3.5" /> : <ThumbsDown className="size-3.5" />}
                  已记录本次反馈：{feedback === 1 ? '有帮助' : '没帮助'}
                </span>
              // 未反馈分支：提示语 + 两个 ghost 按钮。
              // 按钮视觉权重低，不干扰推荐列表。
              // 点击后立即调用 sendFeedback，成功后切换到已反馈分支。
              ) : (
                // 未反馈分支：
                // 两个 ghost 按钮视觉权重低于主操作，
                // 点击后分别上报 1（有帮助）或 2（没帮助）。
                // 按钮不阻止冒泡，因为它们不在可点击卡片内部。
                <>
                  <span>这些推荐有帮助吗？</span>
                  {/*
                   * “有帮助”按钮：上报 value=1。
                   * 使用 ThumbsUp 图标，语义直观。
                   */}
                  <Button type="button" variant="ghost" size="sm" onClick={() => void sendFeedback(1)}>
                    <ThumbsUp className="size-3.5" />
                    有帮助
                  </Button>
                  {/*
                   * “没帮助”按钮：上报 value=2。
                   * 与“有帮助”并列，不预设用户倾向。
                   */}
                  <Button type="button" variant="ghost" size="sm" onClick={() => void sendFeedback(2)}>
                    <ThumbsDown className="size-3.5" />
                    没帮助
                  </Button>
                </>
              )}
            </div>
          )}
        </div>
      )}
  {/*
   * 面板渲染结束：所有分支都在同一个根 div 内，
   * 便于外层用 space-y 控制段落间距。
   * 组件不提供“关闭”按钮，关闭动作由外层弹窗/标签切换负责。
   * 不缓存上次表单条件，保持每次打开都是干净状态。
   */}
    </div>
  )
// 组件结束。ToolRecommendPanel 的职责边界：
// - 负责推荐输入、请求、结果展示与反馈；
// - 不负责工具详情、路由跳转、权限校验；
// - 不直接操作全局 toast 队列之外的 UI。
// 若后端推荐接口字段变化，只需同步本文件与 api/types。
}
