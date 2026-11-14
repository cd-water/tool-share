/**
 * 文件职责：损坏报修页面（DamagePage），路由 /damage。
 *
 * 页面能力：
 * - 按状态标签（全部/已提交/已核验/维修中/已完成）筛选报修单；
 * - 按报修单号/工具编号/关键词搜索；
 * - 分页浏览服务端报修单；
 * - 发起报修：选择进行中的租赁、填写损坏部位与描述、上传 1–3 张照片；
 * - 查看报修详情：损坏描述、证明材料、处理进度时间线与报修信息。
 *
 * 数据来源：
 * - damageApi.list / damageApi.detail 查询报修单；
 * - damageApi.create 提交报修；
 * - rentalsApi.list 按状态拉取可报修的租赁（已取件/已逾期）；
 * - uploadApi.upload 上传证明材料。
 *
 * 入口联动：
 * - 从租赁页点击“报修”会带 ?rentalId=，本页读取后自动打开表单并预选租赁；
 * - 关闭表单时会从 URL 中移除 rentalId，避免刷新后重复打开。
 *
 * 错误/加载/空状态：
 * - 列表加载显示骨架卡片；
 * - 无报修记录显示引导性空状态；
 * - 上传/提交失败由全局拦截器提示，表单保持打开；
 * - 详情加载显示骨架，找不到时显示提示。
 */
import { Fragment, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Camera, Info, Loader2, Search, ShieldAlert, Wrench, X } from 'lucide-react'
import { damageApi } from '@/api/damage'
import { rentalsApi } from '@/api/rentals'
import { uploadApi } from '@/api/upload'
import type { DamageReport } from '@/api/types'
import { badgeClass } from '@/lib/badge'
import { formatDateTime, formatMoney, resolveFileUrl } from '@/lib/format'
import { queryKeys } from '@/lib/queryKeys'
import { damageStatusTone } from '@/lib/status'
import { toast } from '@/components/ui/toast'

// 每页条数：与后端分页配合。
// 报修单量通常不大，10 条足以覆盖一屏。
// 修改该值会影响分页器计算。
const PAGE_SIZE = 10

// 状态标签：
// 空字符串表示“全部”，其余与 DamageReport.status 对齐。
// 标签顺序按处理流程排列：提交 → 核验 → 维修 → 完成。
// 切换标签只改变查询参数并重置页码。
const REPORT_TABS: { value: '' | DamageReport['status']; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'VERIFIED', label: '已核验' },
  { value: 'REPAIRING', label: '维修中' },
  { value: 'COMPLETED', label: '已完成' },
]

/**
 * 把维修进度状态映射为徽标色调。
 *
 * @param status 维修进度状态（COMPLETED/FAILED/WAITING_PARTS/其他）。
 * @returns 对应 BadgeTone：完成成功、失败危险、等件警告、其余信息色。
 *
 * 规则：进度状态与报修单主状态不同，前者描述维修环节，
 * 后者描述整单生命周期。未知状态回退 info，避免无样式。
 * 副作用：无。
 */
  // 进度徽标只做颜色映射，不参与业务判断。
  // COMPLETED 表示维修完成，使用成功色；
  // FAILED 表示维修失败，使用危险色；
  // WAITING_PARTS 表示等待配件，使用警告色；
  // 其余状态（维修中/未知）统一使用信息色。
function progressBadgeClass(status?: string | null): string {
  if (status === 'COMPLETED') return 'badge ok'
  if (status === 'FAILED') return 'badge bad'
  if (status === 'WAITING_PARTS') return 'badge warn'
  return 'badge info'
}

// 报修表单校验规则：
// - rentalId 必选，字符串形式便于 select 受控；
// - damagePart 1–50 字，trim 后不能为空；
// - description 1–500 字，trim 后不能为空；
// - images 至少 1 张、最多 3 张，元素为上传后的 URL。
// 校验在提交前执行，失败不发起请求。
// 图片数量限制同时由 UI 与 schema 保证，防止绕过。
const damageSchema = z.object({
  rentalId: z.string().min(1, '请选择租赁单'),
  damagePart: z
    .string()
    .trim()
    .min(1, '请填写损坏部位')
    .max(50, '损坏部位不超过 50 字'),
  description: z
    .string()
    .trim()
    .min(1, '请描述损坏情况')
    .max(500, '描述不超过 500 字'),
  images: z
    .array(z.string())
    .min(1, '请上传至少 1 张损坏照片')
    .max(3, '最多上传 3 张图片'),
})

// 表单值类型：由 zod schema 推导，
// 保证 resolver 与 useForm 的类型一致。
// rentalId 使用 string 是因为 select 的值本质是字符串，
// 提交时再转换为 number。
type DamageValues = z.infer<typeof damageSchema>

/**
 * 报修表单（弹窗内）。
 *
 * @param props.defaultRentalId 从 URL 带入的租赁 id，用于预选关联租赁。
 * @param props.onCancel 取消回调，关闭弹窗。
 * @param props.onSuccess 提交成功回调，刷新缓存并关闭弹窗。
 * @returns 租赁选择、损坏部位、描述、图片上传与提交按钮。
 *
 * 副作用：
 * - 挂载时按 PICKED_UP/OVERDUE 两种状态查询可报修租赁；
 * - 选择文件后并发上传，成功后写入表单 images；
 * - 提交时调用 damageApi.create。
 *
 * 重要约束：只允许选择已取件或已逾期的租赁，
 * 因为未取件的工具尚未交付，无法确认损坏责任。
 */
  // ReportForm 是报修弹窗的核心表单：
  // 负责可报修租赁查询、表单校验、图片上传与提交。
  // 不负责弹窗开关，开关由父组件控制。
  // 不负责列表刷新，提交成功后通过 onSuccess 通知父组件。
  // 表单数据只存在组件内部，关闭即丢弃。
  // 所有网络请求都通过 API 层，组件不直接使用 fetch。
  // 上传与提交是两条独立路径：先上传得到 URL，再随表单提交。
  // 图片数量上限同时由 UI 与 schema 保证。
  // 报修条件限制为已取件/逾期订单。
  // 组件在卸载时无需额外清理，上传 Promise 完成后会落到已卸载组件，
  // 但 setState 由 React 保护，不会抛错。
  // 失败提示统一由 http 拦截器负责。
function ReportForm({
  defaultRentalId,
  onCancel,
  onSuccess,
}: {
  defaultRentalId?: number
  onCancel: () => void
  onSuccess: () => void
}) {
  // 隐藏的文件输入引用：
  // 由“上传图片”按钮触发 click，避免原生 file input 的默认外观。
  // 上传完成后会清空 value，允许重复选择同一文件。
  const fileInputRef = useRef<HTMLInputElement>(null)
  // 上传中标记：
  // 控制上传按钮的加载图标与禁用态，
  // 同时防止用户在并发上传时重复选择文件。
  // 上传态：
  // 为 true 时上传按钮显示加载图标并禁用；
  // 同时在提交按钮的 disabled 条件中参与判断。
  const [uploading, setUploading] = useState(false)

  // 进行中租赁分两次按状态查询，避免拉全量后在本地过滤；下拉选项量级有限。
  // 已取件租赁查询：
  // 后端分页接口按 status 过滤，size=100 覆盖常见数量。
  // 只拉取一次，用于表单下拉选项。
  // 与逾期查询分开，避免在本地合并全量数据。
  // 失败时下拉为空，提示文案会引导用户先取件。
  // 已取件租赁查询：
  // 使用 status=PICKED_UP、size=100 拉取下拉选项。
  // 该查询与逾期查询并行，互不阻塞。
  // 失败时下拉为空，提示文案会引导用户先取件。
  // 查询结果不缓存过久，进入表单时重新获取。
  // 不做本地过滤，状态过滤交给后端。
  // 只在表单挂载时请求一次。
  // 该查询不影响页面列表。
  const pickedUpQuery = useQuery({
    queryKey: queryKeys.rentals.list({ page: 1, size: 100, status: 'PICKED_UP' }),
    queryFn: () => rentalsApi.list({ page: 1, size: 100, status: 'PICKED_UP' }),
  })
  // 逾期租赁查询：
  // 逾期订单同样可以报修，与已取件合并展示。
  // 两个查询可以并行，互不依赖。
  // 加载状态取二者的并集。
  // 逾期租赁查询：
  // 与已取件查询结构相同，只是 status 不同。
  // 逾期订单仍可报修，因此合并展示。
  // 两个查询的加载状态取并集。
  // 失败时同样回退空列表。
  // 不允许报修已归还/已取消订单。
  // 查询参数固定，不随表单输入变化。
  // 该查询与图片上传无关。
  const overdueQuery = useQuery({
    queryKey: queryKeys.rentals.list({ page: 1, size: 100, status: 'OVERDUE' }),
    queryFn: () => rentalsApi.list({ page: 1, size: 100, status: 'OVERDUE' }),
  })

  // 合并可报修租赁：
  // 先放已取件，再放逾期；顺序稳定，便于用户查找。
  // 不做去重，因为一个订单不会同时处于两种状态。
  // 数据缺失时回退空数组，模板无需判空。
  // 合并可报修租赁：
  // 顺序为先已取件、后逾期，保持稳定。
  // 不排序、不去重，避免掩盖后端异常。
  // 空结果时下拉只显示占位项。
  // 该数组每次渲染重新计算，但数据来自缓存。
  // 不用 useMemo，因为数组很小且依赖简单。
  // 选项展示单号 + 工具名，便于用户区分。
  const inProgressRentals = [
    ...(pickedUpQuery.data?.records ?? []),
    ...(overdueQuery.data?.records ?? []),
  ]
  // 租赁加载状态：
  // 任一查询加载中即视为加载中。
  // 用于下拉下方的提示文案。
  // 加载状态：
  // 任一查询加载中即显示提示。
  // 两个查询通常同时完成。
  // 该状态不影响提交按钮，提交由 schema 校验。
  const rentalsLoading = pickedUpQuery.isLoading || overdueQuery.isLoading

  // 表单初始化：
  // resolver 使用 zod schema；
  // defaultRentalId 存在时预填为字符串。
  // images 初始为空数组，必须上传至少一张。
  // damagePart/description 初始为空。
  // 不使用 mode: onChange，避免输入过程中频繁报错。
  const {
    control,
    handleSubmit,
    setValue,
    watch,
    formState: { errors },
  } = useForm<DamageValues>({
    resolver: zodResolver(damageSchema),
    defaultValues: {
      rentalId: defaultRentalId ? String(defaultRentalId) : '',
      damagePart: '',
      description: '',
      images: [],
    },
  })

  // 监听图片数组：
  // 用于渲染缩略图、控制“还能上传几张”以及移除操作。
  // watch 只在需要响应式读取时使用，避免引入额外状态。
  // 监听图片数组：
  // 用于渲染缩略图、判断剩余名额与移除操作。
  // watch 返回当前值，表单更新时会触发重渲染。
  // images 是表单的单一数据源。
  // 不在本地维护额外的图片列表。
  // 该值变化会触发 schema 的图片数量校验。
  const images = watch('images')

  // 提交 mutation：
  // 提交前把 rentalId 转为 number，并 trim 文本字段；
  // images 直接使用已上传 URL 数组。
  // 成功后调用 onSuccess（刷新查询并关闭弹窗）。
  // 失败由全局拦截器提示，表单状态保留。
  // 不在这里做图片上传，上传在 handleFiles 中完成。
  // 提交 mutation：
  // 将表单值转换为后端 DTO 后调用 damageApi.create。
  // rentalId 由字符串转数字；
  // 文本字段 trim，避免首尾空白；
  // images 直接传 URL 数组。
  // 成功后调用 onSuccess，由父组件刷新列表并关闭弹窗。
  // 失败时不重置表单，用户可修改后重试。
  // 提交期间按钮禁用，防止重复创建报修单。
  // 不在此处上传图片，上传已在前一步完成。
  // 不在此处做 toast，父组件的 onSuccess 负责成功提示。
  const mutation = useMutation({
    // 组装请求体：
    // rentalId 必须是 number，因此显式转换；
    // damagePart/description 使用 trim 后的值；
    // images 至少 1 张，由 schema 保证。
    // 不发送额外字段，保持与后端契约一致。
    mutationFn: (values: DamageValues) =>
      damageApi.create({
        rentalId: Number(values.rentalId),
        damagePart: values.damagePart.trim(),
        description: values.description.trim(),
        images: values.images,
      }),
    // onSuccess 直接使用父组件传入的回调，
    // 不在表单内部硬编码刷新/关闭逻辑。
    onSuccess,
  })

  /**
   * 处理用户选择的图片文件。
   *
   * @param fileList 原生 file input 的文件列表，可为 null。
   *
   * 流程：
   * 1. 空选择直接返回；
   * 2. 根据剩余名额截断，超出时 toast 提示；
   * 3. 校验 MIME 类型必须以 image/ 开头；
   * 4. 并发上传，成功后合并到 form.images 并触发校验；
   * 5. 无论成功失败都复位上传态与 input value。
   *
   * 副作用：网络上传、表单值更新、toast 提示。
   * 上传失败不在此处 toast，交给全局请求拦截器统一提示。
   */
  // handleFiles 处理图片选择与上传：
  // 先做数量与类型校验，再并发上传。
  // 校验收敛在此处，避免在 JSX 中重复。
  // 上传成功后写回表单并触发校验。
  // 失败时不写入任何 URL，保持全有或全无。
  // 无论成功失败都复位上传态与 input。
  // 该函数是 async，但调用处不 await。
  // toast 只用于用户可理解的校验提示。
  // 网络错误交给全局拦截器。
  // 不做图片压缩，保持原始文件。
  async function handleFiles(fileList: FileList | null) {
    // 空选择：用户取消文件对话框时 fileList 为 null，
    // 或选择了 0 个文件，直接返回。
    // 空选择直接返回：
    // 用户取消对话框或未选文件。
    // 不提示，避免打扰。
    if (!fileList || fileList.length === 0) return
    // 剩余名额 = 3 - 已上传数量。
    // 名额用尽时提示并阻止继续选择。
    // 该限制与 schema 的 max(3) 一致。
    const remaining = 3 - images.length
    // 计算剩余名额：
    // 3 - 已上传数量，最小为 0。
    // 与 schema 的 max(3) 保持一致。
    if (remaining <= 0) {
    // 名额用尽时提示并返回，
    // 不允许继续上传。
      toast.add({ title: '最多上传 3 张图片', type: 'warning' })
      return
    }
    // 截断选择列表，避免一次上传超过剩余名额。
    // 若用户选择过多，额外 toast 说明实际可上传数量。
    // 截断发生在类型校验之前，保持提示顺序直观。
    const selected = Array.from(fileList).slice(0, remaining)
    // 截断选择列表：
    // 只取剩余名额内的文件，
    // 其余忽略并提示。
    if (fileList.length > remaining) {
    // 选择数量超过剩余名额时：
    // 提示实际还能上传几张。
    // 不阻止已截断的部分继续上传。
      toast.add({ title: `最多再上传 ${remaining} 张图片`, type: 'warning' })
    }
    // 类型校验：所有文件都必须是图片。
    // 只要有一个非图片就整体拒绝，避免部分成功造成歧义。
    // accept="image/*" 只是浏览器提示，不能替代此校验。
    if (selected.some((file) => !file.type.startsWith('image/'))) {
    // 类型校验：
    // 只要有一个非图片文件就整体拒绝。
    // accept 属性不能替代此检查。
      toast.add({ title: '请选择图片文件', type: 'error' })
      return
    }
    // 进入上传态：
    // 禁用上传按钮，防止并发选择。
    // 上传失败时由 finally 复位。
    setUploading(true)
    // 进入上传态：
    // 按钮显示加载图标并禁用。
    // finally 中复位。
      // 并发上传所有选中的文件：
      // 使用 Promise.all 保持结果顺序与选择顺序一致。
      // 每个文件独立请求，后端返回 url。
      // 任一失败会使整体进入 catch。
    try {
    // 开始并发上传：
    // Promise.all 保持结果顺序。
    // 任一失败进入 catch。
    // 上传使用独立的 uploadApi。
      const results = await Promise.all(selected.map((file) => uploadApi.upload(file)))
    // 等待所有文件上传完成：
    // 成功时 results 与 selected 顺序一致。
    // 每个结果包含 url 字段。
    // 不在此处合并去重。
      setValue('images', [...images, ...results.map((item) => item.url)], {
    // 写回表单：
    // 保留已有图片，追加新 URL；
    // shouldValidate 触发图片数量校验。
    // 写回后 uploading 仍在 finally 中复位。
        shouldValidate: true,
      })
      // 上传失败：不在此处 toast，
      // 全局 http 拦截器已经提示过错误。
      // 已成功的部分不会写入表单，保持“全有或全无”。
    } catch {
      // 上传失败提示由请求拦截器统一处理
    // 上传失败：
    // 不写入任何 URL，避免部分成功；
    // 全局拦截器已提示错误。
      // 无论成功失败都复位：
      // 关闭上传态；
      // 清空 input value，允许用户重新选择同一文件。
    } finally {
    // 无论成功失败都执行：
    // 复位 uploading；
    // 清空 input value，允许重选同一文件。
      setUploading(false)
    // 复位上传态，恢复按钮可用。
    // 该状态只影响上传按钮。
      if (fileInputRef.current) fileInputRef.current.value = ''
    // 清空文件输入：
    // 否则选择同一文件不会触发 change。
    // 输入为空时安全跳过。
    }
  }

  /**
   * 从表单中移除一张已上传图片。
   *
   * @param url 要移除的图片 URL。
   *
   * 副作用：过滤 form.images 并触发校验。
   * 仅影响表单值，不删除对象存储中的文件。
   */
  // removeImage 只修改表单值：
  // 从 images 中过滤掉指定 URL，
  // 并触发 schema 重新校验。
  // 不删除对象存储中的文件，避免误删其他报修引用。
  // 移除后若图片数量为 0，提交会被 schema 拦截。
  // 操作是同步的，无需 loading 状态。
  // 移除按钮在缩略图上，点击不影响其他图片。
  // 使用 URL 作为唯一标识。
  // removeImage 是同步的表单辅助函数：
  // 只做一次过滤与一次 setValue，
  // 不涉及上传、删除对象存储或额外请求。
  // 过滤后保持原顺序，缩略图不会跳位。
  // shouldValidate 让“至少 1 张”的规则立即重新计算。
  // 若移除后数量为 0，提交按钮仍可点击，但 schema 会阻止提交并显示错误。
  // 该函数不区分图片是否正在上传，因为上传完成前不会写入 images。
  // 移除操作不可撤销，用户需要重新上传。
  // 使用严格相等比较 URL，避免误删相似地址。
  // 该函数只由缩略图上的移除按钮调用。
  function removeImage(url: string) {
    setValue(
      'images',
      images.filter((item) => item !== url),
      { shouldValidate: true },
    )
  }

  // 表单渲染：
  // 依次是关联租赁、损坏部位、损坏描述、证明材料与底部操作。
  // 所有字段使用 Controller 接入 react-hook-form。
  // 校验错误通过 errors 与 aria-invalid 展示。
  // 表单不使用浏览器原生校验（noValidate）。
  // 表单渲染：
  // 所有字段使用 Controller 接入 react-hook-form；
  // 错误通过 formState.errors 展示；
  // 校验失败时不发起提交。
  // 表单设置 noValidate，避免浏览器原生提示与自定义提示冲突。
  // 字段顺序按填写习惯排列。
  // 图片上传与表单提交相互独立。
  // 底部按钮在提交或上传中禁用。
  // 不在此处处理成功提示，交给父组件。
  return (
    <form
      onSubmit={handleSubmit((values) => mutation.mutate(values))}
      noValidate
    >
      {/*
       * 关联租赁字段：
       * 下拉选项来自已取件/逾期查询；
       * 加载中、无可选租赁时给出不同提示。
       * rentalId 必填，未选择时提交会被 schema 拦截。
       */}
      <div className={'field' + (errors.rentalId ? ' is-err' : '')}>
        <label htmlFor="rentalId">
          <span className="req">*</span>关联租赁
        </label>
        {/*
         * Controller 把 select 接入表单：
         * field.value/onChange 由 react-hook-form 管理；
         * fieldState.invalid 驱动 aria-invalid。
         */}
        <Controller
          control={control}
          name="rentalId"
          render={({ field, fieldState }) => (
            <select
              id="rentalId"
              className="select"
              value={field.value}
              onChange={field.onChange}
              aria-invalid={fieldState.invalid}
            >
          {/*
           * 下拉选项：
           * 第一项为空，提示用户选择；
           * 其余展示单号 + 工具名，便于区分。
           * 选项数量受后端 size=100 限制。
           */}
              <option value="">请选择进行中的租赁</option>
              {inProgressRentals.map((rental) => (
                <option key={rental.id} value={rental.id}>
                  {rental.orderNo} · {rental.toolName}
                </option>
              ))}
            </select>
          )}
        />
        {errors.rentalId && <p className="err">{errors.rentalId.message}</p>}
        {/*
         * 租赁加载提示：
         * 加载中显示“正在加载”；
         * 加载完成且没有可报修租赁时，提示先取件；
         * 其余情况不渲染提示。
         */}
        {rentalsLoading ? (
          <p className="hint">正在加载租赁单…</p>
        ) : inProgressRentals.length === 0 ? (
          <p className="hint">当前没有已取件或逾期的租赁，取件后即可发起报修。</p>
        ) : null}
      </div>

      {/*
       * 损坏部位字段：
       * 文本输入，最多 50 字；
       * placeholder 给出常见部位示例。
       * 错误信息展示在字段下方。
       */}
      <div className={'field' + (errors.damagePart ? ' is-err' : '')}>
        <label htmlFor="damagePart">
          <span className="req">*</span>损坏部位
        </label>
        <Controller
          control={control}
          name="damagePart"
          render={({ field, fieldState }) => (
            <input
              id="damagePart"
              className="input"
              placeholder="如：钻头夹头、电源线、手柄"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        {errors.damagePart && <p className="err">{errors.damagePart.message}</p>}
      </div>

      {/*
       * 损坏描述字段：
       * 多行文本，最多 500 字；
       * placeholder 引导描述场景、现象与影响。
       * 该信息是核验与定损的重要依据。
       */}
      <div className={'field' + (errors.description ? ' is-err' : '')}>
        <label htmlFor="description">
          <span className="req">*</span>损坏描述
        </label>
        <Controller
          control={control}
          name="description"
          render={({ field, fieldState }) => (
            <textarea
              id="description"
              className="textarea"
              placeholder="请描述损坏发生的场景、现象和影响"
              aria-invalid={fieldState.invalid}
              {...field}
            />
          )}
        />
        {errors.description && <p className="err">{errors.description.message}</p>}
      </div>

      {/*
       * 证明材料字段：
       * 已上传图片以缩略图展示，可逐张移除；
       * 未满 3 张时显示上传按钮；
       * 隐藏的 file input 由按钮触发。
       * 至少上传 1 张才能提交。
       */}
      <div className="field">
        <label><span className="req">*</span>证明材料（1–3 张）</label>
        <div className="up-grid">
        {/*
         * 已上传图片列表：
         * 每张图带移除按钮，点击不影响对象存储文件。
         * 使用 URL 作为 key，避免重复选择时 key 冲突。
         * 图片仅用于本次报修核验。
         */}
          {images.map((url) => (
            <div className="up-item" key={url}>
              <img src={url} alt="证明材料" />
              <button
                type="button"
                onClick={() => removeImage(url)}
                aria-label="移除图片"
              >
                <X className="ic sm" />
              </button>
            </div>
          ))}
        {/*
         * 上传按钮：
         * 仅在未满 3 张时渲染；
         * 上传中显示旋转图标与“上传中”，并禁用重复点击。
         * 点击触发隐藏的 file input。
         */}
          {images.length < 3 && (
            <button
              type="button"
              className="up-add"
              disabled={uploading}
              onClick={() => fileInputRef.current?.click()}
            >
              {uploading ? (
                <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
              ) : (
                <Camera className="ic" />
              )}
              {uploading ? '上传中' : '上传图片'}
            </button>
          )}
        </div>
        {/*
         * 隐藏文件输入：
         * accept="image/*" 限制选择器；
         * multiple 允许一次选多张；
         * onChange 交给 handleFiles 处理。
         */}
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          hidden
          onChange={(event) => handleFiles(event.target.files)}
        />
        <p className="hint">支持 JPG / PNG，上传后仅用于本次报修核验，最多 3 张。</p>
        {errors.images && <p className="err">{errors.images.message}</p>}
      </div>

      {/*
       * 表单底部：
       * 取消关闭弹窗；
       * 提交在 mutation 进行中或上传中禁用，避免重复提交。
       * 提交按钮显示加载图标。
       */}
      <div className="flex" style={{ justifyContent: 'flex-end', marginTop: 8 }}>
        <button type="button" className="btn btn-outline" onClick={onCancel}>
          取消
        </button>
        <button
          type="submit"
          className="btn btn-primary"
          disabled={mutation.isPending || uploading}
        >
          {mutation.isPending && (
            <Loader2 className="ic" style={{ animation: 'spin .7s linear infinite' }} />
          )}
          提交报修
        </button>
      </div>
    </form>
  )
}

/**
 * 报修处理进度时间线。
 *
 * @param report 报修单。
 * @returns 按发生顺序排列的节点列表：提交、核验、维修进度、验收。
 *
 * 规则：
 * - 提交节点始终存在；
 * - 核验节点在 verifyTime 或 verifyResult 存在时追加；
 * - 维修进度节点在维修开始/进度状态/备注任一存在时追加；
 * - 验收节点在 acceptTime 或验收结果存在时追加。
 * 每个节点只展示已有信息，缺失字段自动过滤。
 * 副作用：无。
 */
  // ReportTimeline 只根据报修单字段拼接节点：
  // 不发起请求，不持有状态。
  // 节点顺序固定为提交 → 核验 → 维修 → 验收。
  // 每个节点只在相关字段存在时出现。
  // done 标记仅影响样式，不影响排序。
  // 时间可能为 null，formatDateTime 会给出占位。
  // 详情文本通过数组过滤空值后拼接。
  // 组件不处理点击或展开。
  // 该组件可独立复用于其他进度展示场景。
  // 不展示未来节点，避免误导用户。
  // 不使用国际化占位，文案与页面其他部分保持一致。
  // 节点数量最多 4 个，无需虚拟列表。
function ReportTimeline({ report }: { report: DamageReport }) {
  // 时间线节点数组：
  // 初始包含“提交报修”，这是必然发生的节点。
  // 后续节点按字段存在性 push。
  // 每个节点包含标题、时间、详情与 done 标记。
  // done=false 的节点在样式上表示“进行中”。
  // steps 初始包含“提交报修”：
  // 时间取 createTime，详情包含报修人。
  // done 固定为 true。
  // 后续节点通过 push 追加，保持顺序。
  // 类型显式声明，保证各节点字段一致。
  // 不修改 report 对象。
  // 数组在每次渲染时重建，成本很低。
  // 不做缓存，避免状态过期。
  const steps: {
    title: string
    time?: string | null
    detail?: string | null
    done?: boolean
  }[] = [
    {
      title: '提交报修',
      time: report.createTime,
      detail: `${report.reporterName || '报修人'}提交了损坏报修`,
      done: true,
    },
  ]

  // 核验节点：
  // verifyTime 或 verifyResult 存在即视为已核验。
  // 详情拼接核验人与核验结果，空值过滤。
  // 时间可能为空，formatDateTime 会处理。
    // 核验节点：
    // 只要核验时间或结果存在就展示。
    // 详情拼接核验人与结果，空值过滤。
    // done 为 true，表示该环节已完成。
  if (report.verifyTime || report.verifyResult) {
    steps.push({
      title: '核验处理',
      time: report.verifyTime,
      detail: [
        report.verifier ? `核验人：${report.verifier}` : '',
        report.verifyResult ? `核验结果：${report.verifyResult}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
      done: true,
    })
  }

  // 维修进度节点：
  // 维修开始时间、进度状态或备注任一存在时追加。
  // 时间优先 progressUpdateTime，回退 repairStartTime。
  // done 仅在进度状态为 COMPLETED 时为 true。
    // 维修进度节点：
    // 维修开始、进度状态或备注任一存在即展示。
    // 时间优先取进度更新时间。
    // done 仅在进度为 COMPLETED 时为 true。
  if (
    report.repairStartTime ||
    report.progressStatusName ||
    report.progressNote
  ) {
    steps.push({
      title: '维修进度',
      time: report.progressUpdateTime || report.repairStartTime,
      detail: [
        report.progressStatusName ? `状态：${report.progressStatusName}` : '',
        report.repairPerson ? `维修人：${report.repairPerson}` : '',
        report.progressNote || '',
      ]
        .filter(Boolean)
        .join(' · '),
      done: report.progressStatus === 'COMPLETED',
    })
  }

  // 验收节点：
  // acceptTime 或验收结果名称存在时追加。
  // 详情拼接验收人与结果。
  // 该节点通常表示流程结束。
    // 验收节点：
    // 验收时间或结果存在时展示。
    // 详情拼接验收人与结果。
    // 通常表示流程结束。
  if (report.acceptTime || report.acceptResultName) {
    steps.push({
      title: '维修验收',
      time: report.acceptTime,
      detail: [
        report.acceptor ? `验收人：${report.acceptor}` : '',
        report.acceptResultName ? `结果：${report.acceptResultName}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
      done: true,
    })
  }

  // 时间线渲染：
  // 每个节点展示标题、时间与可选详情。
  // done 节点使用完成样式（由样式层控制）。
  // key 使用标题 + 索引，避免重复标题冲突。
  // 时间线渲染：
  // 每个节点展示标题、时间与可选详情。
  // key 使用标题 + 索引，避免重复标题冲突。
  // done 节点使用完成样式。
  // 只读，不提供操作。
  // 空详情不渲染对应节点，保持紧凑。
  // 时间线只读，不可交互。
  return (
    <ul className="timeline">
    {/*
     * 遍历节点：
     * li 的 done 类控制连接线与圆点样式。
     * 详情为空时不渲染对应 div，避免空白。
     * 时间使用 formatDateTime 统一格式。
     */}
      {steps.map((step, index) => (
        <li key={`${step.title}-${index}`} className={step.done ? 'done' : ''}>
          <div className="tl-t">{step.title}</div>
          <div className="tl-time">{formatDateTime(step.time)}</div>
          {step.detail && <div className="tl-d">{step.detail}</div>}
        </li>
      ))}
    </ul>
  )
}

/**
 * 报修详情内容（弹窗内展示）。
 *
 * @param report 报修单详情。
 * @returns 状态徽标、损坏描述、证明材料、处理时间线与报修信息表。
 *
 * 说明：
 * - 证明材料仅在 images 非空时渲染，可点击查看原图；
 * - 信息表包含单号、报修人、电话、工具、部位、费用、验收结果与备注；
 * - 组件不发请求，数据由父级 detailQuery 提供。
 * 副作用：无。
 */
  // ReportDetail 是详情弹窗的只读视图：
  // 不发起请求，数据由父组件传入。
  // 顶部展示主状态与维修进度；
  // 中部展示损坏描述与证明材料；
  // 下部展示处理时间线与报修信息表。
  // 所有区块按固定顺序渲染。
  // 证明材料可点击查看原图。
  // 字段缺失统一回退“—”。
  // 费用为 null 时显示“—”，避免误解为 0 元。
  // 组件不处理任何操作。
  // 不访问路由或全局状态。
  // 可独立复用于其他报修详情场景。
function ReportDetail({ report }: { report: DamageReport }) {
  // 信息表行数据：
  // 使用 [label, value] 元组数组，便于统一 map。
  // 费用为 null 时显示“—”，避免显示 ¥0 造成误解。
  // 其余字段缺失时统一回退“—”。
  // 行顺序按用户核对信息的习惯排列。
  // 信息表行数据：
  // 使用元组数组，便于统一 map。
  // 标签与值都是字符串，已格式化。
  // 费用为 null 时显示“—”。
  // 顺序按用户核对信息的习惯排列。
  // 不包含图片 URL 等不适合表格展示的字段。
  // 备注为空时显示“—”。
  // 该数组在每次渲染时重建。
  const rows: [string, string][] = [
    ['报修单号', report.reportNo],
    ['报修人', report.reporterName || '—'],
    ['联系电话', report.reporterPhone || '—'],
    ['工具名称', report.toolName],
    ['工具编码', report.toolCode],
    ['损坏部位', report.damagePart],
    ['维修费用', report.repairCost === null ? '—' : formatMoney(report.repairCost)],
    ['验收结果', report.acceptResultName || '—'],
    ['备注', report.remark || '—'],
  ]

  // 详情渲染：
  // 顶部状态徽标与维修进度；
  // 损坏描述（保留换行）；
  // 证明材料（可点击原图）；
  // 处理进度时间线；
  // 报修信息表。
  // 所有区块顺序固定，便于用户快速定位。
  // 详情渲染：
  // 顶部状态徽标；
  // 损坏描述（保留换行）；
  // 证明材料（条件）；
  // 处理进度时间线；
  // 报修信息表。
  // 所有区块顺序固定。
  // 不使用折叠面板，信息量可控。
  // 图片链接在新窗口打开。
  return (
    <div>
        {/*
         * 状态区：
         * 主状态使用 damageStatusTone 映射颜色；
         * 维修进度状态作为次要徽标展示。
         * 两个状态可能同时存在，语义不同。
         */}
      <div className="flex" style={{ marginBottom: 16 }}>
        <span className={badgeClass(damageStatusTone(report.status))}>
          <span className="dot"></span>
          {report.statusName}
        </span>
        {report.progressStatusName && (
          <span className="badge neutral">{report.progressStatusName}</span>
        )}
      </div>

      {/*
       * 损坏描述区块：
       * 保留用户输入的换行（white-space: pre-wrap）。
       * 背景与边框弱化，突出正文。
       * 描述是核验与定损的重要依据。
       */}
      <div className="sec-label">损坏描述</div>
      <p
        style={{
          fontSize: 'var(--t-sm)',
          lineHeight: 1.7,
          color: 'var(--ink-2)',
          background: 'var(--surface-2)',
          border: '1px solid var(--line)',
          borderRadius: 11,
          padding: '13px 15px',
          margin: 0,
          whiteSpace: 'pre-wrap',
        }}
      >
        {report.description}
      </p>

      {/*
       * 证明材料区块：
       * 仅在存在图片时渲染；
       * 每张图是新窗口链接，便于查看原图。
       * 使用 noreferrer 防止反向导航。
       */}
      {report.images && report.images.length > 0 && (
        <>
          <div className="sec-label">证明材料</div>
          <div className="dmg-imgs">
            {report.images.map((url) => (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                <img src={url} alt="证明材料" />
              </a>
            ))}
          </div>
        </>
      )}

      {/*
       * 处理进度区块：
       * 复用 ReportTimeline，根据字段动态拼接节点。
       * 时间线只读，不提供操作。
       * 与报修信息表分开，保持信息层次。
       */}
      <div className="sec-label">处理进度</div>
      <ReportTimeline report={report} />

      {/*
       * 报修信息表：
       * 使用 dl/dt/dd 渲染 rows；
       * 每条记录用 Fragment 包裹，避免额外 DOM。
       * 字段值均为已格式化字符串。
       */}
      <div className="sec-label">报修信息</div>
      <dl className="kv">
        {rows.map(([label, value]) => (
          <Fragment key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </Fragment>
        ))}
      </dl>
    </div>
  )
}

/**
 * 损坏报修页面。
 *
 * @returns 头部、说明、筛选/搜索、报修列表/骨架/空状态、分页与两个弹窗。
 *
 * 副作用：
 * - 列表与详情查询；
 * - 从 URL 读取 rentalId 并自动打开报修表单；
 * - 提交成功后失效报修查询并 toast。
 *
 * 状态说明：
 * - status/keyword/page 构成列表查询参数；
 * - formOpen/detailId 控制两个弹窗；
 * - rentalIdParam 来自 URL，仅用于预选租赁。
 */
  // DamagePage 是路由页面组件：
  // 负责列表查询、筛选/搜索、分页与两个弹窗。
  // 不负责表单内部逻辑，交给 ReportForm。
  // 不负责详情渲染，交给 ReportDetail。
  // 从 URL 读取 rentalId 并自动打开表单。
  // 提交成功后失效报修列表缓存。
  // 所有筛选条件由服务端参数驱动。
  // 页面不做本地乐观更新。
  // 错误提示由全局拦截器负责。
  // 页面位于 RequireAuth 之下，需要登录。
  // 不持久化筛选条件，刷新后恢复默认。
  // 弹窗状态只存在内存中。
  // 卸载后查询缓存按默认策略回收。
  // 不在此处处理维修进度更新。
export default function DamagePage() {
  // queryClient 用于报修提交成功后失效列表缓存。
  // 不直接 refetch，避免在弹窗关闭前闪烁。
  // 该引用在组件生命周期内稳定。
  // queryClient 用于提交成功后失效列表缓存。
  // 不直接 refetch，避免弹窗关闭前闪烁。
  // 该引用在生命周期内稳定。
  const queryClient = useQueryClient()
  // URL 查询参数：
  // 读取 rentalId 用于预选租赁；
  // 关闭表单时移除该参数，避免刷新后重复打开。
  // setSearchParams 使用 replace，避免历史记录膨胀。
  // URL 查询参数：
  // rentalId 用于从租赁页跳转时预选租赁。
  // 关闭表单时移除该参数，避免刷新后重复打开。
  // setSearchParams 使用 replace，避免历史记录膨胀。
  // 其他查询参数不受影响。
  // 该 hook 会触发组件在参数变化时重渲染。
  const [searchParams, setSearchParams] = useSearchParams()
  // 从 URL 解析租赁 id：
  // 非数字或 0 视为未提供。
  // 该值只在表单打开时使用，不影响列表查询。
  const rentalIdParam = Number(searchParams.get('rentalId')) || undefined
  // 列表状态筛选：
  // 空字符串表示全部；
  // 切换时重置页码。
  // 进入服务端查询参数。
  // 状态筛选：
  // 空字符串表示全部；
  // 切换时重置页码。
  // 进入服务端查询参数。
  const [status, setStatus] = useState<'' | DamageReport['status']>('')
  // 搜索输入框即时值，
  // 与 keyword 同步以实现边输边搜。
  // 搜索输入即时值：
  // 与 keyword 同步以实现边输边搜。
  // 不直接进入查询。
  const [keywordInput, setKeywordInput] = useState('')
  // 实际提交的搜索词，
  // 进入服务端查询参数。
  // 实际提交的搜索词：
  // 进入服务端查询参数。
  // 变化时页码重置为 1。
  const [keyword, setKeyword] = useState('')
  // 当前页码，从 1 开始。
  // 筛选/搜索变化时重置。
  // 当前页码：
  // 从 1 开始，由分页按钮修改。
  // 筛选/搜索变化时重置。
  const [page, setPage] = useState(1)
  // 报修表单弹窗开关：
  // 初始值由 URL 中是否存在 rentalId 决定，
  // 这样从租赁页跳转时可直接打开表单。
  // 表单弹窗开关：
  // 初始值由 URL 是否带 rentalId 决定。
  // 从租赁页跳转时可直接打开。
  // 手动打开时不带 rentalId。
  const [formOpen, setFormOpen] = useState(Boolean(rentalIdParam))
  // 详情弹窗目标 id：
  // null 表示关闭；非空时启用详情查询。
  // 与表单弹窗互不干扰。
  // 详情弹窗目标 id：
  // null 表示关闭；
  // 非空时启用详情查询。
  // 与表单弹窗互不干扰。
  const [detailId, setDetailId] = useState<number | null>(null)

  // URL 中 rentalId 变化时：
  // 若存在则打开表单（覆盖手动关闭后的历史状态）。
  // 依赖 rentalIdParam，参数移除不会触发打开。
  // 该副作用不修改 URL，只同步弹窗状态。
  // URL 中 rentalId 变化时同步打开表单：
  // 存在 rentalId 则 setFormOpen(true)。
  // 依赖 rentalIdParam，参数移除不会触发。
  // 该副作用不修改 URL。
  // 用户手动关闭表单后，若 URL 参数仍在，刷新会再次打开。
  // 关闭表单时会移除参数，避免该情况。
  // 副作用只设置本地状态。
  // 不发起请求。
  useEffect(() => {
    if (rentalIdParam) setFormOpen(true)
  }, [rentalIdParam])

  // 列表查询参数：
  // 页码、每页条数、可选状态、可选关键词。
  // 空值使用 undefined，避免后端把空串当筛选。
  // 参数变化会命中新的 queryKey。
  // 列表查询参数：
  // 页码、每页条数、可选状态、可选关键词。
  // 空值使用 undefined，避免后端误判。
  // 参数变化会命中新的 queryKey。
  // 不包含弹窗状态。
  // 该对象每次渲染重建，React Query 会结构化比较。
  const reportParams = {
    page,
    size: PAGE_SIZE,
    status: status || undefined,
    keyword: keyword || undefined,
  } as const
  // 报修列表查询：
  // 失败时保留旧数据，页面不白屏。
  // 加载态驱动骨架卡片。
  // 查询参数中的空值统一转为 undefined，
  // 避免后端把空字符串当成有效筛选条件。
  // 提交成功后会 invalidate 该查询。
  // 报修列表查询：
  // 失败时保留旧数据，页面不白屏。
  // 加载态驱动骨架卡片。
  // 提交成功后会 invalidate 该查询。
  // 该查询不轮询。
  // 分页与筛选共享同一份配置。
  // 不在 queryFn 内转换数据。
  // 返回 records/total/size。
  // 详情查询与列表查询独立。
  // 列表查询是页面数据的唯一来源：
  // queryKey 由筛选参数决定，
  // queryFn 直接调用 damageApi.list。
  // 查询失败时保留上一次成功数据，
  // 因此翻页/筛选失败不会清空已有列表。
  // isLoading 只在首次加载时为 true，
  // 后台刷新使用 isFetching，不显示骨架。
  // 提交报修成功后会 invalidate 该查询，
  // 新报修单会在下次请求时出现。
  // 该查询不轮询，报修进度需用户刷新或重新进入页面查看。
  // 详情查询与本查询独立，打开详情不会刷新列表。
  // 分页器基于 total/size 计算，不依赖本地数组长度。
  const reportsQuery = useQuery({
    queryKey: queryKeys.damageReports.list(reportParams),
    queryFn: () => damageApi.list(reportParams),
  })

  // 当前页记录：未返回时为空数组。
  // total/pageCount 用于分页与空状态判断。
  // 当前页记录：
  // 未返回时为空数组。
  // 列表与空状态判断基于该数组。
  const reports = reportsQuery.data?.records ?? []
  // 总条数：0 时分页器隐藏。
  // 本地筛选不会改变该值。
  // 总条数：
  // 0 时分页器隐藏。
  // 本地不做筛选，因此与服务端一致。
  const total = reportsQuery.data?.total ?? 0
  // 总页数：至少 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE。
  // 总页数：
  // 至少 1，避免空列表显示 0 页。
  // size 缺失时回退 PAGE_SIZE。
  const pageCount = Math.max(1, Math.ceil(total / (reportsQuery.data?.size || PAGE_SIZE)))

  // 详情查询：
  // 仅在 detailId 非空时启用。
  // 查询结果优先于列表项，保证状态最新。
  // 关闭弹窗不清缓存，便于再次打开。
  // 详情查询：
  // 仅在 detailId 非空时启用。
  // 结果优先于列表项，保证状态最新。
  // 关闭弹窗不清缓存。
  // 失败时回退列表项。
  // 该查询不参与列表分页。
  // 加载态控制详情骨架。
  // 成功后直接交给 ReportDetail。
  // 不做额外数据转换。
  const detailQuery = useQuery({
    queryKey: queryKeys.damageReports.detail(detailId),
    queryFn: () => damageApi.detail(detailId as number),
    enabled: detailId !== null,
  })

  // 详情数据来源：
  // 优先详情接口，回退列表同 id 项。
  // 这样弹窗打开瞬间即可展示基础信息。
  // 详情数据来源：
  // 优先详情接口，回退列表同 id 项。
  // 弹窗打开瞬间即可展示基础信息。
  // 两者都缺失时显示“未找到”。
  const detailReport =
    detailQuery.data ??
    reports.find((item) => item.id === detailId)

  /**
   * 关闭报修表单弹窗。
   *
   * 副作用：关闭弹窗；若 URL 带 rentalId，则移除该参数（replace 模式）。
   * 不移除其他查询参数，避免影响页面其他状态。
   * 不重置表单内部状态，组件卸载后自然清空。
   */
  // 关闭报修表单：
  // 关闭弹窗；
  // 若 URL 带 rentalId，则移除该参数。
  // 使用 replace 避免历史记录膨胀。
  // 不移除其他参数。
  // 不重置表单内部状态，组件卸载后自然清空。
  // 不刷新列表，提交成功时才刷新。
  // 该函数只处理本地状态与 URL。
  function closeForm() {
    setFormOpen(false)
    if (rentalIdParam) {
      const next = new URLSearchParams(searchParams)
      next.delete('rentalId')
      setSearchParams(next, { replace: true })
    }
  }

  // 页面骨架：
  // 头部 → 报修说明 → 筛选/搜索 → 列表/骨架/空状态 → 分页 → 两个弹窗。
  // 列表区域由 isLoading 与 reports.length 决定分支。
  // 表单弹窗与详情弹窗独立渲染。
  // 所有区块共享同一根 div 的纵向间距。
  // 页面渲染：
  // 头部、说明、筛选/搜索、列表/骨架/空状态、分页与两个弹窗。
  // 列表分支由 isLoading 与 reports.length 决定。
  // 表单弹窗与详情弹窗独立渲染。
  // 所有区块在同一根 div 内纵向排列。
  // 该 return 是唯一输出，没有提前返回。
  // 弹窗放在最后，避免影响列表布局。
  // 页面不包含全局 Provider。
  return (
    <div>
    {/*
     * 页面头部：标题、说明与“发起报修”按钮。
     * 说明文案解释报修条件与费用结算方式。
     * 头部不参与滚动。
     */}
      <div className="page-head reveal">
        <div>
          <p className="eyebrow">DAMAGE &amp; REPAIR</p>
          <h1>损坏报修</h1>
          <p className="sub">工具使用中发生损坏时，在此提交报修并跟踪核验、维修与验收进度。</p>
        </div>
      {/*
       * 主操作：打开报修表单。
       * 不携带 rentalId，用户需在表单内选择租赁。
       * 从租赁页跳转时才会预选。
       */}
        <div className="actions">
          <button type="button" className="btn btn-primary" onClick={() => setFormOpen(true)}>
            <ShieldAlert className="ic" />
            发起报修
          </button>
        </div>
      </div>

    {/*
     * 报修说明提示块：
     * 说明可报修订单范围、需填写的信息与费用结算方式。
     * 使用 info 图标，与错误提示区分。
     * 该说明是静态文案，不随状态变化。
     */}
      <div className="notice reveal">
        <Info className="ic" />
        <div>
          <b>报修说明</b>
          <p>
            已取件或逾期的租赁可发起报修。请如实填写损坏部位与描述，并上传照片作为证明材料；提交后工作人员会核验并安排维修，维修费用将在验收后据实结算。
          </p>
        </div>
      </div>

    {/*
     * 筛选与搜索栏：
     * 左侧状态标签，右侧关键词搜索。
     * 窄屏自动换行。
     */}
      <div className="between reveal" style={{ marginBottom: 18, flexWrap: 'wrap' }}>
      {/*
       * 状态标签：
       * 每个标签对应 DamageReport.status（或全部）。
       * 点击切换状态并重置页码。
       * 选中态由 status 比较决定。
       */}
        <div className="tabs">
          {REPORT_TABS.map((tab) => (
            <button
              key={tab.value || 'all'}
              type="button"
              className={status === tab.value ? 'active' : ''}
              onClick={() => {
                setStatus(tab.value)
                setPage(1)
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>
        {/*
         * 搜索表单：
         * 提交时 trim 关键词并重置页码；
         * 输入时同步 keyword，实现边输边搜。
         * 搜索范围由后端决定（单号/工具编号等）。
         */}
        <form
          className="f-search"
          style={{ width: 260 }}
          onSubmit={(event) => {
            event.preventDefault()
            setKeyword(keywordInput.trim())
            setPage(1)
          }}
        >
          <Search className="ic" />
          <input
            className="input"
            value={keywordInput}
            onChange={(event) => {
              setKeywordInput(event.target.value)
              setKeyword(event.target.value.trim())
              setPage(1)
            }}
            placeholder="搜索报修单号 / 工具编号…"
          />
        </form>
      </div>

    {/*
     * 加载态：4 条骨架卡片。
     * 骨架结构与真实报修卡一致，减少布局跳动。
     * 仅首次加载显示。
     */}
      {reportsQuery.isLoading ? (
        <div className="rep-list">
          {Array.from({ length: 4 }).map((_, i) => (
            <div className="rep-card" key={i}>
              <div className="skel" style={{ height: 18, marginBottom: 12 }}></div>
              <div className="skel" style={{ height: 12, width: '70%', marginBottom: 8 }}></div>
              <div className="skel" style={{ height: 12, width: '90%' }}></div>
            </div>
          ))}
        </div>
    // 列表态：当前页有报修记录。
    // 每条记录可点击打开详情。
    // 已提交/核验/维修中/已完成通过状态徽标区分。
      ) : reports.length > 0 ? (
        <div className="rep-list">
      {/*
       * 遍历报修单：
       * 整卡可点击打开详情；
       * key 使用报修单 id。
       * 卡片展示工具图、名称、单号、状态、部位、描述与时间。
       */}
          {reports.map((report) => (
        // 单条报修卡：
        // role=button + tabIndex 提供键盘可达性；
        // 点击打开详情弹窗。
        // 不在此处提供操作按钮，避免嵌套交互。
            <article
              className="rep-card reveal in"
              key={report.id}
              role="button"
              tabIndex={0}
              onClick={() => setDetailId(report.id)}
            >
          {/*
           * 卡片顶部：
           * 左侧工具缩略图或占位图标；
           * 中间工具名与报修单号；
           * 右侧状态徽标。
           */}
              <div className="rc-top">
            {/*
             * 缩略图分支：
             * 有图时懒加载并解析相对地址；
             * 无图时显示扳手占位。
             */}
                {report.toolImageUrl ? (
                  <img src={resolveFileUrl(report.toolImageUrl)} alt={report.toolName} loading="lazy" />
                ) : (
                  <span className="rc-thumb-ph"><Wrench className="ic" /></span>
                )}
                <div>
                  <h3>{report.toolName}</h3>
                  <span className="code">{report.reportNo}</span>
                </div>
                <span
                  className={badgeClass(damageStatusTone(report.status))}
                  style={{ marginLeft: 'auto' }}
                >
                  <span className="dot"></span>
                  {report.statusName}
                </span>
              </div>
          {/*
           * 损坏部位行：
           * 图标 + 固定前缀 + 用户填写部位。
           * 部位可能较长，由样式层截断。
           */}
              <div className="rc-part">
                <Wrench className="ic" />
                损坏部位：{report.damagePart}
              </div>
          {/*
           * 损坏描述预览：
           * 展示用户输入的描述，样式层限制行数。
           * 完整描述在详情弹窗中查看。
           */}
              <p className="rc-desc">{report.description}</p>
          {/*
           * 卡片底部：
           * 左侧创建时间；
           * 右侧维修进度徽标或“待核验”。
           */}
              <div className="rc-foot">
                <span className="tiny">{formatDateTime(report.createTime)}</span>
                {report.progressStatusName ? (
                  <span className={progressBadgeClass(report.progressStatus)}>{report.progressStatusName}</span>
                ) : (
                  <span className="tiny">待核验</span>
                )}
              </div>
            </article>
          ))}
        </div>
    // 空状态：
    // 无报修记录或筛选后为空。
    // 文案区分“筛选无结果”和“暂无记录”。
    // 引导用户点击右上角发起报修。
      ) : (
        <div className="empty">
          <ShieldAlert className="ic e-ic" />
          <h4>{keyword || status ? '暂无符合条件的报修' : '暂无报修记录'}</h4>
          <p>工具完好是最好的消息。如有损坏，点击右上角发起报修。</p>
        </div>
      )}

    {/*
     * 分页器：总数大于 0 时显示。
     * 上一页/下一页在边界禁用；
     * 页码按钮直接渲染全部页数。
     */}
      {total > 0 && (
        <div className="between" style={{ marginTop: 18 }}>
          <span className="tiny">共 {total} 条</span>
          <div className="pager">
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            {Array.from({ length: pageCount }).map((_, index) => (
              <button
                key={index}
                type="button"
                className={page === index + 1 ? 'active' : ''}
                onClick={() => setPage(index + 1)}
              >
                {index + 1}
              </button>
            ))}
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
          </div>
        </div>
      )}

    {/*
     * 报修表单弹窗：
     * 仅 formOpen 时渲染。
     * 表单内部负责租赁下拉与图片上传。
     * 提交成功后刷新报修列表并关闭。
     */}
      {formOpen && (
        <div className="mask show" onClick={closeForm}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>发起报修</h3>
                <p className="d-sub">请选择关联的进行中租赁，并填写损坏情况</p>
              </div>
              <button className="dlg-x" onClick={closeForm} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            <div className="dlg-body">
              <ReportForm
                defaultRentalId={rentalIdParam}
                onCancel={closeForm}
                onSuccess={() => {
                  // 提交成功后的收尾：
                  // 失效报修列表缓存；
                  // toast 提示等待处理；
                  // 关闭表单弹窗。
                  // 不清空其他筛选条件。
                  // 列表重新请求后展示新报修单。
                  queryClient.invalidateQueries({ queryKey: queryKeys.damageReports.all })
                  toast.add({ title: '报修已提交，请等待工作人员处理', type: 'success' })
                  closeForm()
                }}
              />
            </div>
          </div>
        </div>
      )}

    {/*
     * 报修详情弹窗：
     * 仅 detailId 非空时渲染。
     * 数据优先 detailQuery，回退列表项。
     * 加载中显示骨架，未找到显示提示。
     */}
      {detailId !== null && (
        <div className="mask show" onClick={() => setDetailId(null)}>
          <div className="dlg" onClick={(event) => event.stopPropagation()}>
            <div className="dlg-head">
              <div>
                <h3>报修详情</h3>
                {detailReport?.reportNo ? (
                  <p className="d-sub">{detailReport.reportNo}</p>
                ) : null}
              </div>
              <button className="dlg-x" onClick={() => setDetailId(null)} aria-label="关闭">
                <X className="ic" />
              </button>
            </div>
            <div className="dlg-body">
              {/*
               * 详情加载中且无回退数据时显示骨架。
               * 一旦有数据立即渲染内容。
               * 骨架高度与详情大致相当，减少跳动。
               */}
              {detailQuery.isLoading && !detailReport ? (
                <div className="skel" style={{ height: 200 }}></div>
              // 有数据时渲染 ReportDetail。
              // 组件内部按字段条件展示各区块。
              // 不在此处做数据转换。
              ) : detailReport ? (
                <ReportDetail report={detailReport} />
              // 兜底：详情与列表都找不到记录。
              // 通常发生在数据被删除或 id 非法时。
              // 仍保留关闭按钮，用户可退出。
              ) : (
                <p className="muted">未找到报修信息</p>
              )}
            </div>
            <div className="dlg-foot">
              <button className="btn btn-outline" onClick={() => setDetailId(null)}>
                关闭
              </button>
            </div>
          </div>
        </div>
      )}
  {/*
   * 页面渲染结束：
   * 表单与详情弹窗互斥打开，关闭任一不影响列表状态。
   * 列表筛选/搜索/分页由服务端参数驱动。
   * 提交成功后通过 invalidate 刷新列表。
   * 从 URL 带入的 rentalId 只在表单打开时消费。
   * 页面不持久化筛选条件，刷新后回到默认状态。
   * 所有请求错误由全局拦截器提示。
   * 组件卸载后 React Query 缓存按默认策略回收。
   * 组件结束。DamagePage 的职责边界：
   * - 负责报修单的展示、提交入口与详情；
   * - 不负责维修进度更新（由工作人员端完成）；
   * - 不负责费用计算与验收结论（由后端/工作人员确定）。
   * 若后续支持用户补充材料，应在详情弹窗内增加独立操作，而不是修改列表。
   */}
    </div>
  )
}
