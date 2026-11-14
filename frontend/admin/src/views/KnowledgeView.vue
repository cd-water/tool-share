<script setup lang="ts">
/**
 * KnowledgeView —— 管理端知识库页面。
 * 职责：
 * 1. 展示知识库文档列表，支持按类型筛选与关键词搜索（标题/文件名/内容预览）。
 * 2. 通过 knowledgeApi 上传 TXT/Markdown/PDF/Word，多文件逐个提交并展示真实索引结果。
 * 3. 索引失败时提示系统会自动重试，列表中也保留失败状态供管理员识别。
 * 4. 删除文档会同时从后端与向量索引中移除，操作前二次确认。
 * 5. 预览使用后端返回的 contentPreview 文本，不额外请求文件内容。
 * 数据流：onMounted -> loadList -> docs -> stats/filtered 派生 -> 表格渲染。
 * 边界：列表接口失败时 loading 仍会复位，页面保留上次数据（首次为空）。
 * 该页面不缓存文档内容，所有状态以列表接口返回为准。
 * 上传成功后立即重新拉取列表，确保索引状态与后端一致。
 */
import { computed, onMounted, ref } from 'vue'
// Vue 组合式 API：computed 派生统计/筛选，onMounted 触发首次加载，ref 保存页面状态。
import { ElMessage, ElMessageBox } from 'element-plus'
// Element Plus 反馈组件：ElMessage 做轻提示，ElMessageBox 做删除二次确认。
import { Plus, UploadFilled } from '@element-plus/icons-vue'
// 图标组件：加号用于上传按钮，UploadFilled 用于拖拽上传区。
import { knowledgeApi } from '@/api/knowledge'
// 知识库接口封装：list 查询、upload 上传并索引、remove 删除并移出向量索引。
import type { KnowledgeDocVO } from '@/api/types'
// 文档视图对象：包含标题、文件名、索引状态、内容预览与上传时间等字段。
import { formatDateTime } from '@/utils/format'
// 时间格式化工具：把后端时间字符串统一格式化为页面展示格式。

// 列表加载开关：首次进入与每次增删后的重新拉取都会经过它。
// 模板用它避免空态在请求过程中闪现。
const loading = ref(false)
// 文档列表数据源；同时是统计与筛选 computed 的唯一输入。
// 不做本地缓存，每次 loadList 整体替换。
const docs = ref<KnowledgeDocVO[]>([])
// 搜索关键词：与标题、文件名、内容预览做不区分大小写的包含匹配。
const keyword = ref('')
// 类型筛选值：空字符串表示全部；其余为小写扩展名 pdf/word/md/txt。
// 注意 word 映射 doc/docx，与 docType 返回的 WORD 对应。
const typeFilter = ref('')

/**
 * 拉取知识库文档列表。
 * 副作用：设置 loading；成功后整体替换 docs。
 * 失败语义：不捕获接口异常，由调用方决定是否提示；finally 保证 loading 复位。
 * 被 onMounted、上传成功、删除成功三处复用。
 * 没有分页参数：当前接口一次返回全部文档，筛选在客户端完成。
 * 若后续文档量变大，应改为服务端分页并同步调整统计口径。
 * 该方法不做请求去重，并发调用时以最后返回的结果为准。
 */
async function loadList() {
// 进入加载态，模板可在表格上方展示 loading 或禁用交互。
  loading.value = true
// try/finally 只负责复位加载开关，不吞掉接口错误。
  try {
// 列表接口无参数；返回值直接作为页面数据源。
// 使用整体替换而非增量合并，避免删除后残留旧行。
    docs.value = await knowledgeApi.list()
// 无论成功失败都关闭加载态。
  } finally {
// 复位加载开关，保证下次请求前状态干净。
    loading.value = false
  }
}

/* ---------- 类型 / 大小 ---------- */
/**
 * 根据文件名后缀推导文档类型编码。
 * @param fileName 原始文件名，可能不含扩展名。
 * @returns PDF / WORD / MD / TXT / FILE 之一。
 * 规则：取最后一个点后的片段并转小写；未知或缺失扩展名统一归为 FILE。
 * 返回值为展示用大写编码，筛选时再统一转小写比较。
 * 该函数是纯函数，不依赖组件状态，可安全用于模板与 computed。
 */
function docType(fileName: string): string {
// 取最后一个点后的扩展名；无点或空字符串时回退空串，再统一转小写。
// split('.') 后取最后一段；文件名没有点时 pop 返回整个名字，再用扩展名匹配会落到 FILE。
// 统一小写后比较，兼容 .PDF / .Docx 等大小写混用。
  const ext = (fileName.split('.').pop() || '').toLowerCase()
// PDF 文档：后端可直接解析文本并建立索引。
// 每个分支返回固定的类型编码，模板与筛选逻辑都依赖这些大写值。
  if (ext === 'pdf') return 'PDF'
// Word 文档：doc 与 docx 都归为 WORD，避免展示两套类型。
  if (ext === 'doc' || ext === 'docx') return 'WORD'
// Markdown：保留原文结构，适合作为知识库条目。
  if (ext === 'md') return 'MD'
// 纯文本：最基础的索引来源。
  if (ext === 'txt') return 'TXT'
// 兜底类型：未知格式仍可展示，但上传入口已限制允许的扩展名。
  return 'FILE'
}
/**
 * 把文档类型映射为徽标 CSS 类名。
 * @param fileName 原始文件名，内部复用 docType 判定。
 * @returns 'type-badge' 加上类型专属类，控制不同格式的配色。
 * 未知类型回退到文本样式，保证徽标始终有基础外观。
 * 样式类由全局样式定义，本组件不再重复声明。
 */
function typeClass(fileName: string): string {
// 复用 docType，避免模板中重复判断扩展名。
  const type = docType(fileName)
// PDF 使用独立的红色系徽标。
  if (type === 'PDF') return 'type-badge type-pdf'
// Word 使用蓝色系徽标。
  if (type === 'WORD') return 'type-badge type-doc'
// Markdown 使用紫色/中性色徽标。
  if (type === 'MD') return 'type-badge type-md'
// TXT 与未知类型共用文本样式。
  return 'type-badge type-txt'
}
/**
 * 把字节数格式化为易读大小。
 * @param bytes 后端返回的文件字节数；0 或缺失时返回破折号。
 * 规则：>= 1MB 用 MB 并保留一位小数；否则换算为 KB 并至少显示 1 KB。
 * 小文件四舍五入后可能为 0，因此用 Math.max(1, ...) 兜底。
 * 只做展示，不参与任何业务判断。
 */
function formatSize(bytes: number): string {
// 0 或空值不显示“0 KB”，用破折号表示未知/空文件。
  if (!bytes) return '—'
// 1MB 及以上使用 MB，保留一位小数，避免数字过长。
// toFixed(1) 固定一位小数，例如 1.5 MB；四舍五入由 JS 处理。
// 不显示 GB：知识库单文件通常远小于 1GB，保持单位体系简单。
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
// 小于 1MB 时换算为 KB；四舍五入后最小显示 1 KB。
  return Math.max(1, Math.round(bytes / 1024)) + ' KB'
}
/* ---------- 统计 / 筛选 ---------- */
// 以组件创建时刻为基准计算“本周”起点，页面停留期间不滚动更新。
// 7 天 * 24 小时 * 3600 秒 * 1000 毫秒。
const weekAgo = Date.now() - 7 * 86_400_000
/**
 * 知识库统计卡片数据。
 * 文档总数：当前列表长度。
 * 已索引：indexed 为 true 的文档数。
 * 索引失败：总数减去已索引，包含失败与尚未完成的文档。
 * 本周新增：createTime 晚于 7 天前的文档数。
 * 所有数值均来自当前列表，不额外请求统计接口。
 */
const stats = computed(() => {
// 统计已成功建立向量索引的文档数量。
  const indexed = docs.value.filter((doc) => doc.indexed).length
// 计算创建时间在最近 7 天内的文档数量；createTime 缺失时不计入。
  const weekNew = docs.value.filter((doc) => doc.createTime && new Date(doc.createTime).getTime() >= weekAgo).length
// 固定四项顺序，与模板中的统计卡片一一对应。
  return [
// 文档总数：列表长度。
    { label: '文档总数', value: docs.value.length, tone: 'brand' },
// 已索引数量：由 indexed 字段直接统计。
    { label: '已索引', value: indexed, tone: 'ok' },
// 索引失败数：用总数减已索引推导，包含“尚未索引”的中间状态。
// 这比单独请求失败计数更简单，也与列表状态保持一致。
    { label: '索引失败', value: docs.value.length - indexed, tone: 'warn' },
// 本周新增：基于 createTime 的时间窗过滤。
    { label: '本周新增', value: weekNew, tone: 'info' },
  ]
})

/**
 * 客户端筛选结果。
 * 先按类型过滤（空值表示全部），再按关键词匹配标题/文件名/内容预览。
 * 关键词统一转小写，实现不区分大小写的包含匹配。
 * 类型比较时把 docType 的大写编码转小写，与 typeFilter 的取值对齐。
 * 空关键词直接放行，避免无意义的字符串拼接与匹配。
 */
// filtered 只依赖 docs/keyword/typeFilter，三者任一变化都会重新计算。
// 计算量是线性遍历，文档数量在管理端可接受。
const filtered = computed(() => {
// 去除首尾空格并转小写；空字符串表示未输入搜索词。
// trim 去掉首尾空格，避免用户误输入空格导致匹配不到。
  const kw = keyword.value.trim().toLowerCase()
// 对 docs 做浅层过滤，不修改原数组。
  return docs.value.filter((doc) => {
// 类型筛选优先：选中类型与文档类型不一致时直接排除。
    if (typeFilter.value && docType(doc.fileName).toLowerCase() !== typeFilter.value) return false
// 没有关键词时，通过类型筛选的文档全部保留。
// 无关键词时直接放行，省去拼接与 includes 的开销。
    if (!kw) return true
// 在标题、文件名、内容预览三者拼接后的文本中做包含匹配。
// contentPreview 可能为空，使用空字符串兜底避免出现 undefined。
    return `${doc.title}${doc.fileName}${doc.contentPreview || ''}`.toLowerCase().includes(kw)
  })
})

/* ---------- 上传（多文件，展示真实索引结果） ---------- */
/**
 * 上传队列项：记录文件本身、展示名与当前上传/索引状态。
 * status 取值：ready 待上传、uploading 上传中、done 已上传、error 上传失败。
 * indexed 只在服务端返回后写入：true 表示已建立向量索引，false/undefined 表示失败。
 */
// 该接口只在组件内部使用，字段与上传流程一一对应。
interface UploadItem {
// 浏览器 File 对象，提交时原样交给 knowledgeApi.upload。
  file: File
// 展示名：默认取文件名，上传时去掉扩展名作为文档标题。
  name: string
// 队列状态：驱动列表中的文案与按钮可用性。
  status: 'ready' | 'uploading' | 'done' | 'error'
// 服务端索引结果：上传接口返回 indexed 字段后写入。
  indexed?: boolean
}

// 上传弹窗可见性：true 时渲染遮罩与对话框。
// 每次打开弹窗都由 openUpload 重置，关闭弹窗不会立即清空队列。
const uploadVisible = ref(false)
// 批量上传进行中标记：禁用“开始上传”按钮并切换按钮文案。
const uploading = ref(false)
// 拖拽悬停标记：仅用于给拖拽区添加高亮 class。
const dropActive = ref(false)
// 当前上传队列；打开弹窗时清空，支持一次选择多个文件。
const uploadItems = ref<UploadItem[]>([])
// 隐藏的文件输入框引用，用于点击拖拽区时触发系统文件选择。
const fileInput = ref<HTMLInputElement>()

/**
 * 打开上传弹窗。
 * 副作用：显示弹窗、清空上一次的上传队列、重置文件输入框的值。
 * 重置 input.value 很关键：否则再次选择同一个文件不会触发 change 事件。
 * 不在这里发起任何网络请求。
 */
// 这里不校验用户权限：上传入口的可见性由页面路由与后端接口共同控制。
function openUpload() {
// 显示遮罩与对话框。
  uploadVisible.value = true
// 清空旧队列，避免上次的文件残留到本次上传。
  uploadItems.value = []
// 清空文件输入框，允许重新选择同名文件。
  if (fileInput.value) fileInput.value.value = ''
}

/**
 * 把选中的文件加入上传队列。
 * @param files 文件输入框的 FileList、拖拽事件的 File[]，或 null。
 * 校验：仅接受 .txt/.md/.pdf/.doc/.docx，扩展名匹配不区分大小写。
 * 不符合格式的文件逐个提示并跳过，其余文件继续入队。
 * 入队状态为 ready，真正上传由 submitUpload 触发。
 * 注意：模板文案声明单文件 ≤ 20MB，但前端这里只校验扩展名，
 * 大小限制依赖后端/上传接口；如需前置拦截应在此处补充 file.size 判断。
 */
// 选择与拖拽两条路径共用此函数，保证校验规则完全一致。
function addFiles(files: FileList | File[] | null) {
// 没有文件（用户取消选择或拖拽为空）时直接返回。
  if (!files) return
// FileList 不是数组，先转数组再遍历，便于使用 forEach。
// 逐个文件处理：不合规的提示后跳过，合规的继续入队，互不影响。
  Array.from(files).forEach((file) => {
// 扩展名白名单校验；与模板 accept 属性保持一致。
    if (!/\.(txt|md|pdf|doc|docx)$/i.test(file.name)) {
// 不支持的文件给出明确提示，包含文件名便于用户定位。
      ElMessage.warning(`不支持的格式：${file.name}`)
// 跳过该文件，继续处理下一个。
      return
    }
// 合法文件入队，初始状态为待上传。
    uploadItems.value.push({ file, name: file.name, status: 'ready' })
  })
// 清空 file input，保证下次选择同一文件仍会触发 change。
  if (fileInput.value) fileInput.value.value = ''
}

/**
 * 文件输入框 change 事件处理。
 * @param event 原生 change 事件，从 target 上读取 FileList。
 * 仅做转发，具体校验与入队逻辑在 addFiles 中。
 */
function onFileChange(event: Event) {
// 从 input 读取文件列表后交给统一入队逻辑。
  addFiles((event.target as HTMLInputElement).files)
}
/**
 * 拖拽放下事件处理。
 * 副作用：先清除拖拽高亮，再把 dataTransfer.files 交给 addFiles。
 * 模板已对 dragover/drop 调用 preventDefault，避免浏览器直接打开文件。
 * dataTransfer 可能为 null（非拖拽触发），因此用可选链并提供 null 兜底。
 */
// 拖拽放下后不自动上传，仍需用户点击“开始上传”，避免误拖即提交。
function onDrop(event: DragEvent) {
// 无论是否接受文件，都先取消高亮状态。
  dropActive.value = false
// 把拖入的文件按与手动选择相同的规则过滤入队。
  addFiles(event.dataTransfer?.files ?? null)
}
/**
 * 从上传队列移除一项。
 * @param index 队列下标；splice 会原地修改 uploadItems。
 * 允许在上传前或上传后移除，已上传成功的文件不会因此从服务端删除。
 */
// 下标来自当前渲染列表，splice 后 Vue 会重新渲染剩余项。
function removeUpload(index: number) {
// 原地删除指定项，模板的 v-for 会立即更新。
  uploadItems.value.splice(index, 1)
}

/**
 * 提交上传队列。
 * 流程：逐个文件调用 knowledgeApi.upload，串行执行以控制并发与状态更新。
 * 已 done 的项会跳过，因此重复点击不会重复上传成功项。
 * 每个文件独立 try/catch：单个失败标记 error，但继续处理后续文件。
 * 只有全部项都 done 时才关闭弹窗并刷新列表；存在 error 时保留弹窗供重试。
 * 全部完成后根据 indexed 汇总给出“已索引”或“部分索引失败将自动重试”的提示。
 * 副作用：修改队列项状态、uploading、弹窗可见性，并重新拉取列表。
 */
// 串行上传而非 Promise.all：便于逐项展示状态，也降低后端瞬时压力。
async function submitUpload() {
// 队列为空时不发起任何请求，按钮通常也会被禁用。
  if (!uploadItems.value.length) return
// 进入批量上传态，防止重复提交。
  uploading.value = true
// 外层 try/finally 保证 uploading 一定复位。
  try {
// 串行遍历：避免同时上传大量文件给后端造成压力，也便于逐项更新状态。
// 每次循环读取最新的 item 引用，状态修改会直接反映到上传队列列表。
    for (const item of uploadItems.value) {
// 已成功的项直接跳过，支持失败后再次点击“开始上传”只重试未完成项。
      if (item.status === 'done') continue
// 标记为上传中，列表文案立即切换为“上传中…”。
      item.status = 'uploading'
// 单个文件的异常不影响后续文件，使用内层 try/catch 隔离。
      try {
// 上传接口：传入 File 与去掉扩展名的文件名作为文档标题。
// 后端负责存储、解析文本并建立向量索引。
// 标题使用文件名去掉扩展名的部分；正则只去掉最后一个点及其后内容。
// 后端会以该标题建立文档记录，原始文件名仍保存在 fileName 字段。
        const doc = await knowledgeApi.upload(item.file, item.name.replace(/\.[^.]+$/, ''))
// 记录服务端返回的索引结果；只有严格为 true 才视为成功。
        item.indexed = doc.indexed === true
// 上传成功（无论是否已索引），标记为 done。
        item.status = 'done'
// 捕获单个文件的上传错误，避免中断整个批次。
      } catch {
// 标记为失败，弹窗保持打开，用户可再次点击上传重试。
        item.status = 'error'
      }
    }
// 只有所有项都完成后才考虑关闭弹窗；存在 error 时留在当前界面。
// 存在 error 项时条件为 false，弹窗保持打开，用户可修正后重试。
// 已 done 的项会在下一轮被跳过，不会重复上传。
    if (uploadItems.value.every((item) => item.status === 'done')) {
// 全部已索引时给出成功提示；indexed 为 undefined 会走 warning 分支。
// indexed 为 undefined（后端未返回）也会被视为未索引，从而给出重试提示。
      if (uploadItems.value.every((item) => item.indexed)) {
// 完整成功的反馈。
        ElMessage.success('文档已上传并完成索引')
// 上传成功但部分文档索引失败。
      } else {
// 明确告知系统会自动重试，避免用户误以为需要手动重新上传。
        ElMessage.warning('文档已上传，部分文档索引失败，系统将自动重试')
      }
// 关闭上传弹窗。
      uploadVisible.value = false
// 重新拉取列表，让表格展示最新的索引状态与上传时间。
      await loadList()
    }
// 外层 finally：无论成功、失败或提前 return，都复位上传态。
  } finally {
// 恢复按钮可用状态。
    uploading.value = false
  }
}

/* ---------- 预览 / 删除 ---------- */
// 预览中的文档；null 表示预览弹窗关闭。
// 直接复用列表行对象，不额外请求详情接口。
// 预览数据直接引用列表行对象，因此弹窗中的元信息与表格完全一致。
const previewDoc = ref<KnowledgeDocVO | null>(null)

/**
 * 删除文档。
 * @param row 表格行对应的文档，使用 id 调用删除接口。
 * 先弹出确认框，提示删除后将从向量索引中移除；用户取消则直接返回。
 * 确认后调用 knowledgeApi.remove，成功提示并重新拉取列表。
 * 副作用：可能删除服务端文件与向量索引，属于不可逆操作，因此必须二次确认。
 * 删除失败不捕获：异常会向上抛出，页面保留当前列表。
 */
// 删除是不可逆操作：既删除文档记录，也会移出向量索引。
async function handleDelete(row: KnowledgeDocVO) {
// ElMessageBox.confirm 在用户取消时会 reject，用 try/catch 区分取消与确认。
  try {
// 确认文案包含文档标题，并明确说明会从向量索引中移除。
    await ElMessageBox.confirm(`确认删除文档「${row.title}」？删除后将从向量索引中移除。`, '删除文档', {
// 警告图标与确认按钮文案。
      type: 'warning',
// 确认按钮使用“确认删除”，降低误操作概率。
      confirmButtonText: '确认删除',
    })
// 用户取消或关闭确认框：静默返回，不提示错误。
  } catch {
// 直接结束函数，后续删除逻辑不会执行。
    return
  }
// 调用删除接口；后端应同时清理存储对象与向量索引。
// 删除接口只传 id；后端负责级联清理，前端不做额外的索引操作。
  await knowledgeApi.remove(row.id)
// 删除成功提示。
  ElMessage.success('文档已删除')
// 重新拉取列表，确保失败/成功状态与后端一致。
  await loadList()
}

// 挂载后立即拉取列表；传入函数引用即可，onMounted 会调用它。
// 卸载时无需额外清理：本组件没有全局监听或定时器。
// 首次进入页面即加载列表；此后由上传/删除成功回调主动刷新。
onMounted(loadList)
</script>

<template>
<!-- 页面头部：标题、说明与上传入口，与其他管理页共用 page-head 布局。 -->
<!-- 说明文字明确列出支持格式，与上传弹窗的 accept 和校验规则保持一致。 -->
<!-- eyebrow 使用英文标识，作为视觉层级中的小标题。 -->
<!-- 头部区域使用 reveal 入场动画，与其他管理页保持一致的页面节奏。 -->
<!-- 标题与说明不依赖任何接口数据，加载期间也保持稳定。 -->
  <div class="page-head reveal">
    <div>
<!-- 英文小标题：KNOWLEDGE BASE。 -->
      <p class="eyebrow">KNOWLEDGE BASE</p>
<!-- 页面主标题。 -->
      <h1>知识库</h1>
<!-- 副标题：说明知识库用途（维护 AI 助手知识来源）与支持格式。 -->
      <p class="sub">维护 AI 助手的知识来源，支持 TXT / Markdown / PDF / Word 文档上传与预览。</p>
    </div>
<!-- 头部右侧操作区，当前只有“上传文档”一个入口。 -->
    <div class="actions">
<!-- 点击打开上传弹窗；加号图标来自 Element Plus。 -->
      <button class="btn btn-primary" @click="openUpload"><el-icon class="ic"><Plus /></el-icon>上传文档</button>
    </div>
  </div>

<!-- 统计卡片区：文档总数、已索引、索引失败、本周新增。 -->
<!-- 数据来自 stats computed，会随列表加载与增删自动更新。 -->
<!-- reveal 为全局入场动画类。 -->
<!-- 四张统计卡片不使用 v-if 骨架屏，列表加载期间短暂显示 0 值。 -->
<!-- 这样布局稳定，且 0 值本身是合法的空知识库状态。 -->
  <div class="kb-stats reveal">
<!-- 遍历四项统计，结构一致，仅图标色调与数值不同。 -->
    <div class="kb-stat" v-for="item in stats" :key="item.label">
<!-- 左侧图标容器，tone 控制成功/警告/信息等配色。 -->
      <span class="ks-ic" :class="item.tone">
<!-- 通用文档图标：描边风格，与知识库主题一致。 -->
        <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" /></svg>
      </span>
      <div>
<!-- 统计数值：data-count 供全局数字滚动动画，初始文本 0。 -->
        <b class="num" :data-count="item.value">0</b>
<!-- 统计标签，如“索引失败”“本周新增”。 -->
        <span>{{ item.label }}</span>
      </div>
    </div>
  </div>

<!-- 筛选栏：关键词搜索、类型下拉、结果计数。 -->
<!-- 所有筛选都在客户端完成，不触发额外接口请求。 -->
<!-- 筛选结果通过 filtered computed 同步到下方表格。 -->
<!-- 筛选栏不请求后端：关键词与类型都在已加载的 docs 上计算。 -->
<!-- 因此筛选即时生效，但只覆盖当前已加载的全部文档。 -->
  <div class="filterbar reveal">
<!-- 搜索框容器：左侧放大镜图标 + 输入框。 -->
    <div class="f-search">
<!-- 搜索图标，纯装饰，不绑定点击事件。 -->
      <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
<!-- v-model 双向绑定 keyword；输入即触发 filtered 重新计算。 -->
      <input v-model="keyword" class="input" placeholder="搜索标题 / 文件名 / 内容…" />
    </div>
<!-- 类型下拉：值与 docType 的小写形式对齐。 -->
    <select v-model="typeFilter" class="select f-item">
<!-- 空值表示不按类型过滤。 -->
      <option value="">全部类型</option>
<!-- PDF 选项。 -->
      <option value="pdf">PDF</option>
<!-- Word 选项：对应 doc 与 docx。 -->
      <option value="word">Word</option>
<!-- Markdown 选项。 -->
      <option value="md">Markdown</option>
<!-- 纯文本选项。 -->
      <option value="txt">TXT</option>
    </select>
<!-- 弹性占位，把结果计数推到筛选栏右侧。 -->
    <span class="grow"></span>
<!-- 当前筛选后的文档数量；不是后端总数。 -->
    <span class="tiny">共 {{ filtered.length }} 个文档</span>
  </div>

<!-- 表格外层：负责卡片外观与横向滚动。 -->
<!-- 窄屏下 table-scroll 允许表格横向滚动，避免内容被挤压。 -->
<!-- 表格外层同时承担卡片视觉与滚动边界，避免横向滚动溢出页面。 -->
<!-- 表格列宽由内容与操作列固定宽度共同决定。 -->
  <div class="table-wrap reveal">
<!-- 横向滚动容器：保证列较多时仍可完整查看。 -->
    <div class="table-scroll">
<!-- 文档列表表格。 -->
      <table class="tbl">
<!-- 表头：标题、文件名、类型、索引状态、内容预览、上传时间、操作。 -->
        <thead>
          <tr>
<!-- 文档标题列：通常为去掉扩展名的文件名。 -->
            <th>文档标题</th>
<!-- 原始文件名列；缺失时显示破折号。 -->
            <th>文件名</th>
<!-- 类型列：徽标文案与配色均由 docType/typeClass 推导。 -->
            <th>类型</th>
<!-- 索引状态列：已索引/索引失败徽标，失败时附加自动重试说明。 -->
            <th>索引状态</th>
<!-- 内容预览列：截断显示后端返回的 contentPreview。 -->
            <th>内容预览</th>
<!-- 上传时间列：使用 formatDateTime 统一格式化。 -->
            <th>上传时间</th>
<!-- 操作列固定宽度，容纳“预览/删除”两个链接按钮。 -->
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
<!-- 遍历筛选结果；key 使用文档 id，保证增删时行复用正确。 -->
<!-- 表格数据完全由 filtered 驱动，因此搜索与类型筛选即时生效。 -->
          <tr v-for="row in filtered" :key="row.id">
<!-- 文档标题，使用主单元格样式加粗显示。 -->
            <td class="cell-main">{{ row.title }}</td>
<!-- 原始文件名；为空时用破折号占位。 -->
            <td>{{ row.fileName || '—' }}</td>
<!-- 类型徽标：文案为 PDF/WORD/MD/TXT/FILE，class 控制颜色。 -->
            <td><span :class="typeClass(row.fileName)">{{ docType(row.fileName) }}</span></td>
            <td>
<!-- 索引状态徽标：indexed 为 true 用成功色，否则用危险色。 -->
<!-- 状态徽标：ok/bad 两个 class 控制圆点与文字颜色。 -->
              <span class="badge" :class="row.indexed ? 'ok' : 'bad'">
<!-- 圆点 + 文案，颜色由徽标 class 统一控制。 -->
                <span class="dot"></span>{{ row.indexed ? '已索引' : '索引失败' }}
              </span>
<!-- 索引失败时的补充说明：后端会自动重试，用户不必手动重传。 -->
              <div v-if="!row.indexed" class="cell-sub">系统将自动重试</div>
            </td>
<!-- 内容预览：ellipsis 单行省略，最大宽度 240px，避免撑开表格。 -->
            <td><span class="ellipsis" style="max-width: 240px; display: inline-block; vertical-align: middle">{{ row.contentPreview || '—' }}</span></td>
<!-- 上传时间：空值由 formatDateTime 自行处理。 -->
            <td>{{ formatDateTime(row.createTime) }}</td>
            <td>
<!-- 行操作区：预览与删除均为文本按钮。 -->
<!-- 操作按钮使用 link-btn 文本样式，减少表格中的视觉重量。 -->
              <div class="row-actions">
<!-- 预览：把当前行写入 previewDoc，弹窗由 v-if 控制。 -->
                <button class="link-btn" @click="previewDoc = row">预览</button>
<!-- 删除：触发二次确认后调用删除接口。 -->
                <button class="link-btn danger" @click="handleDelete(row)">删除</button>
              </div>
            </td>
          </tr>
<!-- 空态行：仅在非加载中且筛选结果为空时出现。 -->
<!-- colspan=7 横跨全部列，保持表格结构完整。 -->
<!-- 空态只在“加载完成且筛选无结果”时出现，避免加载中误报为空。 -->
          <tr v-if="!loading && filtered.length === 0">
            <td colspan="7"><div class="empty" style="padding: 40px"><h4>暂无知识库文档</h4></div></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

<!-- 上传弹窗整体结构：头部说明、拖拽区、文件队列、底部操作。 -->
<!-- 弹窗没有表单校验错误区，格式问题通过 ElMessage 即时提示。 -->
<!-- 关闭弹窗不会取消已经发出但尚未完成的单个上传请求。 -->
  <!-- 上传弹窗 -->
<!-- 上传弹窗：v-if 控制挂载；点击遮罩空白处关闭。 -->
<!-- @click.self 确保点击对话框内部不会误关闭。 -->
  <div v-if="uploadVisible" class="mask show" @click.self="uploadVisible = false">
<!-- narrow 变体：上传对话框比预览对话框更窄。 -->
<!-- narrow 控制弹窗宽度；上传内容较少，不需要预览弹窗那么宽。 -->
    <div class="dlg narrow">
<!-- 弹窗头部：标题、说明与关闭按钮。 -->
      <div class="dlg-head">
        <div>
<!-- 弹窗标题。 -->
          <h3>上传文档</h3>
<!-- 说明：上传后自动解析并纳入 AI 知识库。 -->
          <p class="d-sub">上传后自动解析并纳入 AI 知识库</p>
        </div>
<!-- 关闭按钮：点击后隐藏弹窗，队列数据保留到下次打开时清空。 -->
        <button class="dlg-x" @click="uploadVisible = false" aria-label="关闭">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
        </button>
      </div>
<!-- 弹窗主体：拖拽上传区 + 已选文件队列。 -->
      <div class="dlg-body">
<!-- 拖拽上传区：点击触发文件选择，同时支持拖拽。 -->
<!-- dropzone 同时是点击目标与拖拽目标，drag class 由 dropActive 控制。 -->
<!-- 拖拽事件都在模板上 preventDefault，防止浏览器打开文件。 -->
<!-- 点击整个区域打开系统文件选择器。 -->
<!-- 拖拽进入：阻止默认行为并高亮区域。 -->
<!-- 拖拽离开：取消高亮。 -->
<!-- 放下文件：阻止浏览器默认打开文件，交给 onDrop 处理。 -->
<!-- 上传图标，纯装饰。 -->
        <div
          class="dropzone"
          :class="{ drag: dropActive }"
          @click="fileInput?.click()"
          @dragover.prevent="dropActive = true"
          @dragleave.prevent="dropActive = false"
          @drop.prevent="onDrop"
        >
<!-- 主提示文案。 -->
          <el-icon class="ic lg"><UploadFilled /></el-icon>
<!-- 支持格式与大小提示；大小限制由后端最终校验。 -->
          <b>拖拽文件到此处，或点击选择</b>
<!-- 隐藏的文件输入：multiple 支持多选，accept 限制可选格式。 -->
          <span>支持 .txt / .md / .pdf / .doc / .docx，单文件 ≤ 20MB</span>
<!-- change 事件读取 FileList 并入队。 -->
<!-- 隐藏 input 只作为系统文件选择器的桥接，不直接展示。 -->
          <input ref="fileInput" type="file" accept=".txt,.md,.pdf,.doc,.docx" multiple hidden @change="onFileChange" />
<!-- 已选文件队列：有文件时才显示。 -->
        </div>
<!-- 遍历上传项；key 使用文件名 + 下标，允许同名文件共存。 -->
<!-- 文件队列仅在本次选择了文件后出现；关闭弹窗时由 openUpload 清空。 -->
        <div class="up-list" v-if="uploadItems.length">
<!-- 文件图标。 -->
<!-- 队列项展示文件名、大小与状态；key 允许同名文件同时存在。 -->
          <div class="up-file" v-for="(item, index) in uploadItems" :key="item.name + index">
            <span class="uf-ic"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6" /></svg></span>
<!-- 文件名。 -->
            <div>
<!-- 文件大小与当前状态文案。 -->
              <b>{{ item.name }}</b>
              <span>
<!-- 大小来自浏览器 File.size，仅用于展示。 -->
                {{ formatSize(item.file.size) }} ·
<!-- 已上传完成：区分“已索引”与“索引失败，将自动重试”。 -->
                <template v-if="item.status === 'done'">
<!-- 索引结果来自上传接口返回的 indexed 字段。 -->
                  {{ item.indexed ? '已上传 · 已索引' : '已上传 · 索引失败，将自动重试' }}
<!-- 未完成：展示上传失败/上传中/等待上传三种状态。 -->
                </template>
                <template v-else>{{ item.status === 'error' ? '上传失败' : item.status === 'uploading' ? '上传中…' : '等待上传' }}</template>
              </span>
<!-- 移除按钮：仅从本次队列移除，不影响服务端已上传文件。 -->
            </div>
            <button class="uf-x" type="button" aria-label="移除" @click="removeUpload(index)">×</button>
          </div>
        </div>
<!-- 弹窗底部：取消与开始上传。 -->
      </div>
<!-- 取消：直接关闭弹窗，未上传的文件不会提交。 -->
<!-- 底部按钮不随队列清空而隐藏，空队列时“开始上传”处于禁用态。 -->
      <div class="dlg-foot">
<!-- 开始上传：队列为空或正在上传时禁用，避免重复请求。 -->
        <button type="button" class="btn btn-outline" @click="uploadVisible = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="uploading || !uploadItems.length" @click="submitUpload">
          {{ uploading ? '上传中…' : '开始上传' }}
        </button>
      </div>
    </div>
<!-- 预览弹窗：v-if 绑定 previewDoc；点击遮罩关闭。 -->
<!-- 与上传弹窗共用 mask/dlg 样式，宽度使用默认值。 -->
  </div>
<!-- 预览对话框容器。 -->

<!-- 头部：文档标题、元信息与关闭按钮。 -->
<!-- 预览弹窗独立于上传弹窗，二者都通过 v-if 挂载/卸载。 -->
<!-- 预览内容来自列表接口的 contentPreview，不发起新的文件下载请求。 -->
<!-- 点击遮罩空白处或关闭按钮都会把 previewDoc 置空。 -->
  <!-- 预览弹窗 -->
  <div v-if="previewDoc" class="mask show" @click.self="previewDoc = null">
<!-- 预览标题使用文档 title。 -->
    <div class="dlg">
<!-- 元信息：文件名、类型、索引状态与上传时间，全部来自当前行数据。 -->
      <div class="dlg-head">
        <div>
<!-- 关闭按钮。 -->
          <h3>{{ previewDoc.title }}</h3>
          <p class="d-sub">{{ previewDoc.fileName }} · {{ docType(previewDoc.fileName) }} · {{ previewDoc.indexed ? '已索引' : '索引失败' }} · {{ formatDateTime(previewDoc.createTime) }}</p>
        </div>
        <button class="dlg-x" @click="previewDoc = null" aria-label="关闭">
<!-- 预览主体。 -->
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
<!-- 内容预览：保留换行（pre-wrap），最多由后端截断；为空时显示占位文案。 -->
        </button>
      </div>
<!-- 底部操作区，仅一个关闭按钮。 -->
      <div class="dlg-body">
<!-- 关闭预览弹窗。 -->
<!-- white-space: pre-wrap 保留后端预览文本中的换行；空内容显示占位文案。 -->
        <div style="font-size: var(--t-sm); line-height: 1.85; color: var(--ink-2); white-space: pre-wrap">{{ previewDoc.contentPreview || '暂无预览内容' }}</div>
      </div>
<!-- 预览弹窗底部只提供关闭操作，不提供编辑或重新索引入口。 -->
      <div class="dlg-foot">
        <button class="btn btn-outline" @click="previewDoc = null">关闭</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 以下三条规则只覆盖统计卡片的图标配色，其余样式复用全局设计令牌。 */
/* 组件样式使用 scoped，避免影响其他页面的 .kb-stat 图标。 */
/* 已索引：成功色背景 + 成功色图标，传达“状态正常”。 */
/* scoped 只作用于本组件的 .kb-stat，避免污染其他页面的统计卡片。 */
/* 背景与前景使用成对的语义令牌，切换主题时自动适配。 */
.kb-stat .ks-ic.ok {
  background: var(--ok-bg);
  color: var(--ok);
}
/* 索引失败：警告色背景 + 警告色图标，提示需要关注。 */
/* 警告态图标用于“索引失败”统计，与表格中的 bad 徽标语义一致。 */
/* 颜色只影响图标，卡片背景仍沿用全局统计区样式。 */
.kb-stat .ks-ic.warn {
  background: var(--warn-bg);
  color: var(--warn);
}
/* 本周新增：信息色，属于中性统计而非异常状态。 */
/* 信息态用于“本周新增”，表示中性、非异常的数量变化。 */
/* 三种 tone 由 stats 数据项直接给出，模板不写条件分支。 */
.kb-stat .ks-ic.info {
  background: var(--info-bg);
  color: var(--info);
}
</style>
