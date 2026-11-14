/**
 * 文件职责：居民端 AI 助手页面（AiPage），路由 /ai。
 *
 * 页面包含两个标签：
 * - “智能问答”：基于社区知识库的流式问答（RAG），支持来源引用、失败重试、
 *   超时兜底、清空对话与快捷问题；
 * - “工具推荐”：复用 ToolRecommendPanel，按自然语言需求推荐工具。
 *
 * 右侧栏展示知识库覆盖范围、热门问题与人工服务信息。
 *
 * 数据与副作用：
 * - 问答通过 aiApi.chatStream 建立流式请求，逐段追加到本地消息列表；
 * - 前端维护 40s 空闲超时（后端已有 20s 生成超时），避免连接假死；
 * - 组件卸载或清空对话时通过 AbortController 中止请求；
 * - 本页不直接读写后端会话历史，刷新后对话即清空。
 *
 * 权限：页面位于 RequireAuth 之下，需要登录；问答内容仅为参考，
 * 涉及押金与定损以工作人员核验为准。
 */
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import {
  BookOpen,
  CircleAlert,
  Clock,
  MessageSquare,
  Phone,
  RefreshCw,
  Send,
  Sparkles,
  Trash2,
} from 'lucide-react'
import { aiApi } from '@/api/ai'
import type { AiSource } from '@/api/types'
import { ToolRecommendPanel } from '@/components/ToolRecommendPanel'
import { toast } from '@/lib/fx'

/**
 * 单条聊天消息的本地模型。
 *
 * @property id 前端生成的稳定 id，用于列表 key 与来源锚点。
 * @property role 发送方：user 或 assistant。
 * @property content 文本内容；流式回答会持续追加。
 * @property sources 助手消息引用的知识库片段；由服务端在流开始或中途下发。
 * @property question 仅助手消息：记录对应的用户问题，用于失败重试。
 * @property status 助手消息状态：streaming / done / error。
 * @property error 失败原因，展示在错误气泡中。
 *
 * 该类型只存在于前端内存，不持久化；刷新页面后对话清空。
 */
type ChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  sources?: AiSource[]
  /** 仅助手消息：用于失败后重试 */
  question?: string
  status?: 'streaming' | 'done' | 'error'
  error?: string
}

// 快捷问题：首次进入与侧栏“热门问题”共用。
// 覆盖预约、押金、逾期、报修四个高频主题，
// 文案尽量短，便于在窄屏上与图标并排显示。
// 点击后直接发送，不需要用户再点发送按钮。
const QUICK_QUESTIONS = [
  '如何预约租赁工具？',
  '押金什么时候退还？',
  '逾期费用怎么计算？',
  '工具损坏后如何报修？',
]

// 知识库覆盖范围说明，仅用于右侧栏展示。
// 这些条目应与后端 RAG 实际索引的主题保持一致，
// 避免向用户承诺系统并不掌握的知识范围。
// 若后端新增文档主题，应同步更新此列表。
const KB_ITEMS = ['租赁流程与预约规则', '押金支付与退还标准', '逾期与损坏计费说明', '工具使用与安全须知']

// 前端空闲超时：后端已有 20 秒生成超时，
// 这里在完全无事件时再兜底 40 秒，避免连接假死时永远显示“正在输入”。
// 每次收到 sources/message 事件都会重置计时器。
// 超时后中止请求并把消息标记为 error，允许用户重试。
/** 服务端已有 20 秒生成超时；前端在完全无事件时再兜底，避免连接假死。 */
const STREAM_IDLE_TIMEOUT = 40_000

/**
 * 生成前端消息 id。
 *
 * @returns 由时间戳与随机字符串组成的 id，例如 1710000000000-ab12cd。
 *
 * 副作用：无。id 只用于 React 列表与来源锚点，不发送给后端；
 * 使用时间戳 + 随机数可以在单次会话内保证足够低的碰撞概率。
 */
function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * AI 助手页面。
 *
 * @returns 标签切换、问答面板/推荐面板与右侧信息栏。
 *
 * 副作用：
 * - 消息变化或流式状态变化时自动滚动到底部；
 * - 卸载时中止进行中的流式请求；
 * - 发送、重试、清空会修改本地消息状态并触发网络请求。
 *
 * 状态说明：
 * - tab 控制问答/推荐两个视图；
 * - messages 保存本次会话的全部消息；
 * - input 为输入框受控值；
 * - streaming 表示当前是否有流式请求在进行。
 */
export default function AiPage() {
  const navigate = useNavigate()
  // 视图状态：chat 为默认标签，recommend 复用推荐面板。
  // 切换标签不会清空对话，用户可随时切回继续提问。
  // 推荐面板的状态由子组件自行管理，本页不持有。
  // 标签状态：
  // chat 与 recommend 互斥，切换时保留各自组件的内部状态。
  // 使用字面量联合类型而不是字符串，避免拼写错误。
  // 默认进入聊天，符合“AI 助手”的主入口预期。
  // 标签切换不写 URL，刷新后回到默认聊天。
  // 若需要分享推荐结果，应把标签与查询参数纳入路由。
  const [tab, setTab] = useState<'chat' | 'recommend'>('chat')
  // 对话消息列表：只存在内存中，刷新即清空。
  // 用户消息与助手消息按发送顺序追加；
  // 流式回答通过更新同一条助手消息实现“打字机”效果。
  // 对话消息列表：
  // 每次发送追加两条（用户消息 + 助手占位），
  // 流式回调只更新助手占位的内容，不新增消息。
  // 清空对话会重置为空数组，欢迎气泡不受影响。
  // 消息 id 由 createId 生成，保证列表 key 稳定。
  // 本状态不持久化，刷新页面即丢失。
  const [messages, setMessages] = useState<ChatMessage[]>([])
  // 输入框受控值；发送后清空，失败时保留原问题在消息里供重试。
  // 输入框受控值：
  // 发送成功后清空；发送失败时问题已进入消息列表，
  // 因此输入框仍清空，用户可通过错误气泡重试。
  // 不做本地草稿保存，保持简单。
  const [input, setInput] = useState('')
  // 是否正在接收流式回答：用于禁用发送按钮、显示光标与重试按钮。
  // 流式状态：
  // true 时禁用发送按钮、显示光标、禁用重试按钮。
  // 由 startStream 置 true，由 finally 在活动控制器匹配时置 false。
  // 该状态不区分“连接中”和“生成中”，UI 上统一表现为加载。
  const [streaming, setStreaming] = useState(false)
  // 聊天滚动容器引用：消息更新时滚动到底部。
  const scrollRef = useRef<HTMLDivElement>(null)
  // 当前流式请求的 AbortController；新请求会先中止旧请求。
  // 当前请求的 AbortController：
  // 新请求、清空、卸载都会调用 abort，防止旧响应继续写入。
  // 保存引用而不是每次新建，便于判断 finally 是否属于当前请求。
  // 置空表示没有进行中的请求。
  const abortRef = useRef<AbortController | null>(null)

  // 自动滚动：消息追加或流式状态变化时，把滚动容器滚到底部。
  // 使用 scrollTop = scrollHeight 而不是 scrollIntoView，
  // 避免整页滚动影响外部布局。
  // 依赖 messages 与 streaming，确保每个增量都能看到最新内容。
  // 自动滚动副作用：
  // 依赖 messages 与 streaming，任何增量都会触发。
  // 直接设置 scrollTop 而不是使用平滑滚动，
  // 避免高频增量下动画排队导致滞后。
  // 容器不存在时安全跳过。
  // 该副作用不修改 React 状态。
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, streaming])

  // 卸载清理：组件离开时中止进行中的流式请求，
  // 防止请求完成后对已卸载组件 setState。
  // 返回的函数只在卸载时执行一次。
  // 卸载副作用：
  // 返回清理函数，在组件卸载时中止请求。
  // 使用可选链避免 abortRef 为空时报错。
  // 不在这里清空消息，因为组件即将卸载。
  useEffect(() => () => abortRef.current?.abort(), [])

  /**
   * 局部更新一条助手消息。
   *
   * @param id 目标消息 id。
   * @param patch 要合并的字段（内容、状态、来源、错误等）。
   *
   * 副作用：调用 setMessages 触发重渲染；未命中 id 时列表保持不变。
   * 使用不可变更新，避免直接修改状态对象。
   */
  // updateAssistant 是流式回调的公共工具：
  // 通过 map + 展开合并实现不可变更新。
  // 只更新匹配 id 的消息，其他消息引用保持不变，
  // 有利于 React 跳过无关子树的渲染。
  // patch 可以只包含变化字段，未传字段保持原值。
  // 若回调到达时消息已被清空，map 不会命中，安全无副作用。
  function updateAssistant(id: string, patch: Partial<ChatMessage>) {
    setMessages((prev) => prev.map((message) => (message.id === id ? { ...message, ...patch } : message)))
  }

  /**
   * 启动一次流式问答。
   *
   * @param question 用户问题文本。
   * @param assistantId 预先插入的助手消息 id，流式内容会写入该消息。
   *
   * 副作用：
   * - 中止上一个请求并创建新的 AbortController；
   * - 启动/重置空闲超时计时器；
   * - 通过 aiApi.chatStream 的回调更新消息内容、来源与状态；
   * - 请求结束后清理计时器并复位 streaming。
   *
   * 关键约束：onDone 时若内容为空则标记为错误，避免留下空白气泡；
   * 若已因超时标记 error，则不再被后到的 onDone 覆盖。
   */
  // startStream 负责一次完整流式请求的生命周期：
  // 1) 中止旧请求、创建控制器并保存；
  // 2) 复位助手消息并置 streaming；
  // 3) 建立空闲超时计时器；
  // 4) 调用 aiApi.chatStream 并处理四类回调；
  // 5) finally 中清理计时器与状态。
  // 该函数不返回值，调用方通过消息状态感知结果。
  function startStream(question: string, assistantId: string) {
    // 先中止上一次请求：
    // 用户可能在回答过程中重试或发送新问题，
    // 旧连接必须关闭，否则两条回答会交错写入不同消息。
    abortRef.current?.abort()
    // 为本次请求创建独立控制器；
    // 保存到 ref 后，清空/卸载/超时都能统一中止。
    // AbortController 的 signal 会传给 fetch 层。
    const controller = new AbortController()
    // 同一时间只允许一个流式请求：
    // 先中止旧请求，再创建新的控制器并保存引用。
    // 这样用户快速重试时不会出现两条回答交错追加。
    // 置 streaming 后，发送与重试按钮都会禁用。
    // 保存当前控制器引用；finally 中通过比较引用判断
    // 自己是否仍是活动请求，避免旧请求复位新请求的状态。
    abortRef.current = controller
    setStreaming(true)
    // 重置助手消息：清空内容与来源，状态设为 streaming，
    // 同时记录 question，供失败后重试使用。
    // 保留原 id，React 列表不会重建节点。
    updateAssistant(assistantId, {
      content: '',
      sources: [],
      status: 'streaming',
      error: undefined,
      question,
    })

    // 空闲超时计时器：
    // 每次收到 sources 或 message 事件都调用 touch() 重置；
    // 超过 STREAM_IDLE_TIMEOUT 仍无事件时，标记错误并中止请求。
    // 使用 window.setTimeout 返回数值 id，便于 clearTimeout。
    // 空闲计时器初始值 0，表示尚未启动；
    // 实际由 touch() 在请求开始和每次事件时设置。
    // 使用数值而非 NodeJS.Timeout，适配浏览器环境。
    let timer = 0
    const touch = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => {
        updateAssistant(assistantId, { status: 'error', error: '回答超时，未收到完整内容。' })
        controller.abort()
      }, STREAM_IDLE_TIMEOUT)
    }
    // 请求开始即启动计时器：
    // 若服务端迟迟不返回任何事件，40 秒后主动失败。
    // 这比让用户无限等待更可控。
    touch()

      // 发起流式请求，三个回调分别处理：
      // onSources：来源列表，通常在建流后最先到达；
      // onMessage：文本增量，追加到当前助手消息；
      // onDone/onError：收尾与错误。
      // controller.signal 让卸载/清空/超时可以统一中止底层连接。
      // 调用流式接口：
      // 第一个参数是问题文本；
      // 第二个参数是回调集合；
      // 第三个参数是中止信号。
      // 接口返回 Promise，finally 中统一收尾。
    aiApi
      .chatStream(
        question,
        {
        // 收到来源：更新消息 sources，并重置空闲计时器。
        // 来源用于渲染引用编号与折叠列表，不参与正文解析。
          onSources: (sources) => {
            // sources 到达：更新来源并重置空闲计时器。
            // 来源可能分多次到达，后到的会覆盖前者。
            // 来源只影响引用展示，不改变正文内容。
            touch()
            updateAssistant(assistantId, { sources })
          },
        // 收到文本增量：按 id 找到助手消息并追加 content。
        // 使用函数式更新，避免闭包拿到旧 messages。
        // 这里不重置 status，保持 streaming 直到 onDone。
          onMessage: (delta) => {
            // message 增量到达：追加文本并重置计时器。
            // 使用函数式更新避免闭包过期。
            // 每条增量都可能很频繁，因此只做最小状态变更。
            touch()
            setMessages((prev) =>
              prev.map((message) =>
                message.id === assistantId ? { ...message, content: message.content + delta } : message,
              ),
            )
          },
        // 正常结束：
        // 清理计时器；若消息已被标记 error（例如超时），保持错误状态；
        // 内容为空则视为失败，提示用户重试；
        // 否则标记 done，触发“相关追问”与来源提示。
          onDone: () => {
            window.clearTimeout(timer)
            // onDone：先清理计时器，再根据内容与状态决定最终状态。
            // 若已被超时标记 error，则保持错误，不覆盖为 done。
            // 内容为空视为失败，提示用户重试。
            // 有内容则标记 done，触发追问区渲染。
            setMessages((prev) =>
              prev.map((message) => {
                if (message.id !== assistantId || message.status === 'error') return message
                const content = message.content.trim()
                return content
                  ? { ...message, status: 'done' }
                  : { ...message, status: 'error', error: '未收到回答，请重试。' }
              }),
            )
          },
        // 服务端/网络错误：清理计时器并写入错误信息。
        // 错误气泡中会展示“重试”按钮，复用原 question。
          onError: (message) => {
            window.clearTimeout(timer)
            updateAssistant(assistantId, { status: 'error', error: message })
          },
        },
        controller.signal,
      )
      // onError：服务端或网络错误。
      // 清理计时器并写入错误信息；
      // 错误气泡会提供重试入口。
      // 无论成功失败都清理计时器；
      // 只有当前控制器仍是活动控制器时才复位 streaming，
      // 避免旧请求的 finally 把新请求的状态提前清掉。
      // finally 是所有路径的统一收尾：
      // - 清理空闲计时器，避免泄漏；
      // - 仅当当前控制器仍是活动请求时，才置空并复位 streaming；
      // - 旧请求的 finally 不能影响新请求。
      // 这样快速重试时状态不会被旧请求提前改写。
      .finally(() => {
        window.clearTimeout(timer)
        if (abortRef.current === controller) {
          abortRef.current = null
          setStreaming(false)
        }
      })
  }

  /**
   * 发送一条问题。
   *
   * @param questionText 可选；不传时使用输入框内容。
   *
   * 副作用：
   * - 追加用户消息与占位助手消息；
   * - 清空输入框；
   * - 调用 startStream 发起请求。
   *
   * 空问题或正在流式回答时直接忽略，避免重复提交。
   */
  // send 是用户发送消息的唯一入口：
  // - 读取输入或显式传入的问题；
  // - 空问题或流式中直接忽略；
  // - 追加用户消息与助手占位；
  // - 清空输入并启动流。
  // 不在这里做长度校验，输入框已有 maxLength。
  // 不在这里捕获异常，startStream 内部通过回调处理错误。
  function send(questionText?: string) {
    // 优先使用显式传入的问题（快捷问题/追问），
    // 否则读取输入框；统一 trim 去掉首尾空白。
    // 空白问题视为未输入。
    const question = (questionText ?? input).trim()
    if (!question || streaming) return
    const assistantId = createId()
    // 一次性追加两条消息：
    // 用户消息使用新 id 与原始问题；
    // 助手消息使用预先分配的 assistantId，
    // 后续流式回调都写入这条消息。
    // 助手占位初始为 streaming 且内容为空，显示打字动画。
    setMessages((prev) => [
      ...prev,
      { id: createId(), role: 'user', content: question },
      { id: assistantId, role: 'assistant', content: '', sources: [], status: 'streaming' },
    ])
    setInput('')
    startStream(question, assistantId)
  }

  /**
   * 重试一条失败的助手消息。
   *
   * @param message 失败消息；必须带 question 字段。
   *
   * 副作用：复用原问题与消息 id 重新启动流式请求。
   * 若缺少 question 或正在流式回答，则忽略。
   */
  // retry 只针对失败消息：
  // 必须带有原始 question，且当前没有流式请求。
  // 复用原消息 id，重试内容会覆盖同一条气泡，
  // 不会在列表中新增重复消息。
  // 失败后再次点击重试仍然可用。
  function retry(message: ChatMessage) {
    if (!message.question || streaming) return
    startStream(message.question, message.id)
  }

  /**
   * 聚焦某个引用来源。
   *
   * @param messageId 助手消息 id。
   * @param order 来源序号（从 1 开始）。
   *
   * 副作用：滚动到对应来源卡片并添加短暂高亮动画。
   * 通过 void el.offsetWidth 强制重排，确保连续点击也能重新播放动画。
   * 找不到元素时静默返回。
   */
  // focusSource 处理正文引用 [n] 的点击：
  // 通过 id 约定 source-{messageId}-{order} 找到来源卡片。
  // 先平滑滚动到视野中央，再重播高亮动画。
  // 强制读取 offsetWidth 触发重排，保证连续点击也能重新动画。
  // 1.5 秒后移除高亮类，避免长期闪烁。
  function focusSource(messageId: string, order: number) {
    const el = document.getElementById(`source-${messageId}-${order}`)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.remove('flash')
    void el.offsetWidth
    el.classList.add('flash')
    window.setTimeout(() => el.classList.remove('flash'), 1500)
  }

  /**
   * 渲染助手回答，并把 [n] 形式的引用转换为可点击按钮。
   *
   * @param message 助手消息。
   * @returns React 节点数组；普通文本与引用按钮交错。
   *
   * 规则：只有序号在 1..sources.length 范围内的 [n] 才变成按钮，
   * 其他方括号文本原样展示，避免把用户内容误判为引用。
   * 副作用：点击引用会调用 focusSource 滚动到来源卡片。
   */
  // renderAnswer 把助手正文拆分为文本与引用按钮：
  // 使用正则捕获 [数字] 片段，逐段判断是否在来源范围内。
  // 合法引用渲染为 button，点击聚焦来源；
  // 非法序号或普通文本原样渲染为 span。
  // 该函数只做展示，不修改消息状态。
  function renderAnswer(message: ChatMessage) {
    const sourceCount = message.sources?.length ?? 0
    return message.content.split(/(\[\d+\])/g).map((part, index) => {
      const match = /^\[(\d+)\]$/.exec(part)
      const order = match ? Number(match[1]) : 0
      if (order > 0 && order <= sourceCount) {
        // 合法引用按钮：
        // - key 使用片段索引，正文更新时可能变化，但列表很短；
        // - title/aria-label 说明按钮用途；
        // - 点击调用 focusSource，不阻止其他交互。
        return (
          <button
            key={index}
            type="button"
            className="cite"
            title={`查看来源 ${order}`}
            aria-label={`查看来源 ${order}`}
            onClick={() => focusSource(message.id, order)}
          >
            {order}
          </button>
        )
      }
      return <span key={index}>{part}</span>
    })
  }

  /**
   * 清空当前对话。
   *
   * 副作用：中止进行中的请求、清空消息与流式状态，并 toast 提示。
   * 不删除知识库或服务端会话，仅重置前端视图。
   */
  // clearChat 重置整个对话：
  // - 中止进行中的请求并清空控制器引用；
  // - 清空消息列表与流式状态；
  // - toast 提示操作完成。
  // 不重置输入框内容，用户可以继续输入新问题；
  // 不删除右侧栏的静态信息。
  function clearChat() {
    abortRef.current?.abort()
    abortRef.current = null
    setMessages([])
    setStreaming(false)
    toast('对话已清空', { type: 'info' })
  }

  // 页面骨架：
  // - 顶部标签切换问答/推荐；
  // - 左侧主面板根据标签渲染聊天或推荐；
  // - 右侧栏展示知识库、热门问题与人工服务。
  // 问答面板与推荐面板互斥渲染，避免同时挂载两套请求。
  // 页面渲染前的最后说明：
  // 标签栏、主面板、右侧栏三层结构固定；
  // 主面板内部再根据 tab 切换聊天/推荐；
  // 聊天面板与推荐面板都自行管理滚动与加载态。
  // 右侧栏始终渲染，不随对话变化。
  // 所有交互状态都来自本组件或子组件，不依赖全局 store。
  return (
    <div>
    {/*
     * 标签栏：两个按钮共享 active 样式，
     * 切换只改变本地 tab 状态，不触发路由跳转。
     * 这样用户可以在同一页面内比较问答与推荐结果。
     */}
      <div className="tabs reveal" style={{ marginBottom: 16 }}>
      {/*
       * “智能问答”标签：默认视图。
       * 按钮使用 type=button，避免在表单场景中意外提交。
       */}
        <button type="button" className={tab === 'chat' ? 'active' : ''} onClick={() => setTab('chat')}>
          智能问答
        </button>
      {/*
       * “工具推荐”标签：复用 ToolRecommendPanel。
       * 推荐结果点击后会跳转到 /tools?tool=id 打开详情。
       */}
        <button type="button" className={tab === 'recommend' ? 'active' : ''} onClick={() => setTab('recommend')}>
          工具推荐
        </button>
      </div>

    {/*
     * 左右布局：左侧主面板自适应，右侧栏固定宽度（由样式层控制）。
     * 在窄屏下通常折叠为纵向排列。
     */}
      <div className="ai-layout">
      {/*
       * 条件渲染：chat 标签显示聊天，否则显示推荐。
       * 使用三元表达式而不是两个隐藏面板，
       * 避免推荐面板在后台挂载并触发不必要的请求。
       */}
        {tab === 'chat' ? (
        // 聊天主面板：卡片容器，包含头部、滚动区与输入区。
        // reveal 类提供进入动画，不影响交互。
          <section className="chat card reveal">
          {/*
           * 聊天头部：AI 头像、名称、在线状态、RAG 版本与清空按钮。
           * 头部不参与滚动，始终固定在卡片顶部。
           * “在线”只是视觉状态，实际可用性由请求结果决定。
           */}
        <header className="chat-head">
          <span className="ai-ava">
            <Sparkles className="ic" />
            <i className="ai-live"></i>
          </span>
          <div className="chat-id">
            <b>小享 · 社区工具助手</b>
            <span>
              <i className="online-dot"></i>在线 · 基于社区知识库回答
            </span>
          </div>
          <span className="badge brand" style={{ marginLeft: 'auto' }}>RAG · v2</span>
            {/*
             * 清空按钮：中止请求并重置本地消息。
             * 图标按钮带 title，桌面端悬停可见；读屏依赖 title/aria。
             * 清空操作不可撤销，但成本低（对话本来就不持久化）。
             */}
          <button className="icon-btn" type="button" onClick={clearChat} title="清空对话">
            <Trash2 className="ic sm" />
          </button>
        </header>

          {/*
           * 聊天滚动区：所有气泡与来源都在这里滚动。
           * 输入区固定在卡片底部，不随消息滚动。
           * 自动滚动逻辑通过 scrollRef 控制该容器。
           */}
        <div className="chat-scroll" ref={scrollRef}>
            {/*
             * 欢迎气泡：固定展示助手自我介绍与快捷问题。
             * 它不来自后端，也不计入 messages，因此清空对话后仍会显示。
             * 快捷问题点击后直接发送，适合首次使用。
             */}
          <div className="bubble-row ai">
            <span className="b-ava"><Sparkles className="ic sm" /></span>
            <div className="bubble">
              <p>
                您好！我是小享，社区工具房的智能助手。我可以帮您解答<b>借还流程、押金计费、工具使用与故障报修</b>等问题。
              </p>
              <div className="quick-wrap">
                <span className="tiny">试试这样问：</span>
                <div className="quick-grid">
            {/*
             * 快捷问题网格：由 QUICK_QUESTIONS 生成。
             * 每个按钮只发送文本，不携带额外参数。
             * 发送后 streaming 变为 true，按钮不禁用但 send 会忽略重复点击。
             */}
                  {QUICK_QUESTIONS.map((question) => (
                    <button key={question} type="button" onClick={() => send(question)}>
                      <MessageSquare className="ic" />
                      {question}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/*
           * 消息列表：按发送顺序渲染用户与助手气泡。
           * 助手消息可能处于 streaming 状态，此时显示打字动画或光标。
           * 用户消息与助手消息使用不同的头像与对齐方式（样式层控制）。
           */}
          {messages.map((message) => (
            // 单条消息行：role 决定 user/ai 样式。
            // 头像在用户侧显示“我”，助手侧显示 AI 图标。
            // 消息 id 作为 key，流式更新时不会重建整行。
            <div key={message.id} className={'bubble-row ' + (message.role === 'user' ? 'user' : 'ai')}>
              <span className="b-ava">{message.role === 'user' ? '我' : <Sparkles className="ic sm" />}</span>
              <div className="bubble">
              {/*
               * 流式且无内容时显示三点打字动画；
               * 有内容时渲染正文，并在末尾追加闪烁光标。
               * 这样用户能区分“正在思考”和“正在输出”。
               */}
                {message.role === 'assistant' && message.status === 'streaming' && !message.content ? (
                  <span className="typing"><i></i><i></i><i></i></span>
                ) : (
                // 正文使用 pre-wrap 保留换行；
                // 助手内容经过 renderAnswer 处理引用，
                // 用户内容原样展示，避免注入风险由 React 自动转义。
                  <p style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                    {message.role === 'assistant' ? renderAnswer(message) : message.content}
                    {message.role === 'assistant' && message.status === 'streaming' && message.content && (
                      <span className="cursor" />
                    )}
                  </p>
                )}
              {/*
               * 来源列表：仅助手消息且 sources 非空时渲染。
               * 每个来源带序号，与正文中的 [n] 按钮一一对应。
               * 来源卡片 id 供 focusSource 定位与高亮。
               */}
                {message.sources && message.sources.length > 0 && (
                  <div className="sources">
                    {message.sources.map((source, index) => (
                    // 单条来源：序号 + 标题 + 可选片段。
                    // 片段可能较长，由样式层限制行数或滚动。
                    // 来源不参与正文排序，只按服务端下发顺序展示。
                      <div
                        className="src-item"
                        id={`source-${message.id}-${index + 1}`}
                        key={`${source.title}-${index}`}
                      >
                        <span className="src-no">{index + 1}</span>
                        <div className="src-body">
                          <b>{source.title}</b>
                          {source.snippet && <p>{source.snippet}</p>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {/*
                 * 兜底回答提示：助手已完成但没有任何来源时，
                 * 说明该回答未命中知识库，仅供参考。
                 * 使用 warning 图标，提醒用户不要当作权威结论。
                 */}
                {message.role === 'assistant' &&
                  message.status === 'done' &&
                  message.content.trim() &&
                  !message.sources?.length && (
                    <p className="source-note">
                      <CircleAlert className="ic sm" />
                      未检索到知识库来源，本条回答为兜底内容，仅供参考。
                    </p>
                  )}
                {/*
                 * 错误气泡：展示错误原因与重试按钮。
                 * 重试期间禁用按钮，避免并发请求。
                 * 错误消息仍保留在原位置，不插入新消息。
                 */}
                {message.status === 'error' && (
                  <div className="chat-error">
                    <CircleAlert className="ic sm" />
                    <span>{message.error || '回答中断，请重试。'}</span>
                    {/*
                     * 重试按钮：调用 retry(message)，
                     * 复用 message.question 与 message.id。
                     * 若没有 question（例如历史消息），retry 会直接返回。
                     */}
                    <button
                      className="btn btn-outline btn-sm"
                      type="button"
                      disabled={streaming}
                      onClick={() => retry(message)}
                    >
                      <RefreshCw className="ic sm" />
                      重试
                    </button>
                  </div>
                )}
                {/*
                 * 相关追问：仅在回答完成且有内容时展示。
                 * 取快捷问题的前三个作为建议，点击直接发送。
                 * 这不代表模型生成，只是固定的引导问题。
                 */}
                {message.role === 'assistant' && message.status === 'done' && message.content && (
                  <div className="followups">
                    {/*
                     * 每个追问按钮只发送文本；
                     * 使用 key=question 保证列表稳定。
                     * 追问不会清空当前对话。
                     */}
                    {QUICK_QUESTIONS.slice(0, 3).map((question) => (
                      <button key={question} type="button" onClick={() => send(question)}>
                        {question}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>

          {/*
           * 输入区：固定在聊天卡片底部。
           * 包含多行输入框、发送按钮与免责声明。
           * 输入框不受消息滚动影响，始终可见。
           */}
        <div className="chat-input">
          <div className="ci-box">
            {/*
             * 输入框：
             * - Enter 发送，Shift+Enter 换行；
             * - 输入法组合态（isComposing）下不发送，避免中文选词误触；
             * - rows=1 由样式层控制高度，内容多时自动扩展（若样式支持）。
             */}
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault()
                  send()
                }
              }}
              placeholder="问问工具怎么借、怎么用、怎么还…（Enter 发送，Shift+Enter 换行）"
              rows={1}
            />
            {/*
             * 发送按钮：streaming 或输入为空时禁用。
             * 点击调用 send()，使用输入框当前值。
             * 图标为 Send，与输入框并排。
             */}
            <button className="btn btn-primary ci-send" type="button" disabled={streaming || !input.trim()} onClick={() => send()}>
              <Send className="ic sm" />
            </button>
          </div>
          {/*
           * 免责声明：说明回答由知识库生成，
           * 押金与定损以工作人员核验为准。
           * 这段文案是产品合规要求，不应删除。
           */}
          <p className="ci-hint">回答由社区知识库生成，仅供参考；涉及押金与定损以工作人员核验为准。</p>
        </div>
          </section>
        ) : (
          // 推荐标签页：复用 ToolRecommendPanel。
          // 点击推荐条目时跳转到工具广场并带上 tool 参数，
          // 由 ToolsPage 读取 query 打开详情弹窗。
          // 推荐面板自行处理加载、错误与空状态。
          <section className="chat card reveal" style={{ padding: 20, overflow: 'auto' }}>
            <ToolRecommendPanel onSelectTool={(id) => navigate(`/tools?tool=${id}`)} />
          </section>
        )}

      {/*
       * 右侧信息栏：知识库覆盖、热门问题与人工服务。
       * 它是辅助信息，不参与主对话流程。
       * 在窄屏下通常移动到页面底部或隐藏（样式层决定）。
       */}
      <aside className="ai-rail">
        {/*
         * 知识库覆盖面板：
         * 列出当前 RAG 掌握的主题，帮助用户判断该问什么。
         * 列表项只做展示，不可点击。
         */}
        <div className="panel tight reveal">
          <h3 className="rail-title"><BookOpen className="ic sm" />知识库覆盖</h3>
          {/*
           * 知识库条目列表：由 KB_ITEMS 生成。
           * 每项带书本图标，强调“可查询的资料”。
           * 条目数量固定，不随对话变化。
           */}
          <ul className="kb-list">
            {/*
             * 单条知识库主题：纯文本 + 图标。
             * 不使用链接，避免用户误以为可以打开原文档。
             */}
            {KB_ITEMS.map((item) => (
              <li key={item}><BookOpen className="ic" />{item}</li>
            ))}
          </ul>
        </div>
        {/*
         * 热门问题面板：
         * 与欢迎气泡使用同一组快捷问题。
         * 点击直接发送，适合用户不想自己组织语言时使用。
         */}
        <div className="panel tight reveal">
          <h3 className="rail-title"><Sparkles className="ic sm" />热门问题</h3>
          {/*
           * 热门问题按钮：纵向排列，每个按钮发送对应问题。
           * 与快捷问题网格的区别只是布局，行为一致。
           */}
          <div className="hot-q">
            {/*
             * 每个按钮只发送问题文本；
             * 发送后输入框清空，消息列表追加。
             */}
            {QUICK_QUESTIONS.map((question) => (
              <button key={question} type="button" onClick={() => send(question)}>
                <Sparkles className="ic" />
                {question}
              </button>
            ))}
          </div>
        </div>
        {/*
         * 人工服务面板：
         * 展示工具房开放时间、电话与现场咨询建议。
         * 这些信息是静态的，不来自后端。
         */}
        <div className="panel tight reveal rail-note">
          <h3 className="rail-title"><Clock className="ic sm" />人工服务</h3>
          {/*
           * 服务说明：开放时间与电话。
           * 复杂问题（如定损争议）建议转人工。
           * 电话使用加粗强调，便于快速识别。
           */}
          <p>
            工具房每日 08:00–20:00 开放。复杂问题可联系工作人员 <b>0571-8888-6666</b>，或前往工具房现场咨询。
          </p>
          {/*
           * 电话行：图标 + 号码，作为可复制的文本。
           * 不使用 tel: 链接，避免桌面端唤起外部应用。
           */}
          <p className="tiny"><Phone className="ic sm" /> 0571-8888-6666</p>
        </div>
      </aside>
      </div>
  {/*
   * 页面渲染结束：两个标签共享同一套右侧栏。
   * 切换标签不会卸载右侧栏，因此滚动位置与静态信息保持不变。
   * 若未来需要独立路由，应把 tab 状态提升到 URL 查询参数。
   */}
    </div>
  )
// 组件结束。AiPage 的职责边界：
// - 负责对话视图、流式状态与本地消息管理；
// - 不负责知识库索引、模型调用与来源生成（由后端完成）；
// - 不持久化对话，刷新即清空。
// 若需要多轮上下文，应扩展 aiApi.chatStream 的请求体并同步后端。
}
