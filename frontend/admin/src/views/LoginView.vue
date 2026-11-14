<script setup lang="ts">
/**
 * LoginView.vue —— 社区工具房管理端「指挥中枢」登录页。
 *
 * 视觉结构：左侧雷达星图（LIVE SWEEP）+ 右侧登录控制台 + 底部状态条。
 * 业务结构：表单校验 -> auth.login -> 自检转场 -> 路由跳转。
 *
 * 本文件内部按以下顺序组织：
 *   1. 登录业务：表单模型、校验规则、演示账号、安全重定向解析；
 *   2. 雷达常量：viewBox 坐标系、极坐标换算、扫描波瓣、刻度与中心枢纽；
 *   3. 接触点数据：8 个工具种子数据、状态色/状态文案映射与补零工具；
 *   4. 雷达交互：requestAnimationFrame 扫描、命中检测、指针准星与悬浮提示；
 *   5. 遥测与时钟：数字缓动动画、每秒时钟、随机延迟；
 *   6. 自检转场：逐行揭示日志、授权提示、全屏擦除，最后执行 redirect；
 *   7. 生命周期：挂载时启动计时器/动画，卸载时统一清理，避免泄漏。
 *
 * 安全要点：
 *   - 登录成功后不直接信任 route.query.redirect，而是经 safeRedirectPath
 *     只放行站内绝对路径，阻断 //evil.com 这类协议相对地址的开放重定向；
 *   - 表单校验失败或登录接口抛错时只触发抖动反馈，不跳转、不写入凭据。
 *
 * 动效与可访问性：
 *   - prefers-reduced-motion 为 reduce 时，跳过雷达 rAF、数字滚动和自检遮罩，
 *     直接跳转，并把遥测值直接写成终值；
 *   - 所有定时器、rAF、ResizeObserver、resize 监听都在 onBeforeUnmount 中清理。
 *
 * 数据来源说明：雷达上的工具、遥测数字与自检日志都是登录页的静态演示数据，
 * 不发起额外网络请求；真实登录凭证只通过 auth.login 提交，避免在登录前泄露
 * 业务接口的调用时序。
 *
 * Element Plus 的输入框、标签、错误提示默认是浅色语义，这里借助 :deep()
 * 在 scoped 样式中逐项重绘为暗色，同时保留组件原有的焦点、禁用和错误态。
 *
 * 演示账号 admin / Aa123456 仅用于开发与验收环境，点击「一键填入」会同时
 * 弹出 toast 提示，提醒使用者还需点击「启动控制台」才会提交。
 *
 * 样式组织：<style scoped> 内按「左侧雷达 -> 右侧登录 -> 底部状态 -> 自检遮罩
 /*
 * -> 响应式 -> reduced-motion」分区；暗色配色固定为石墨底 + 铜金强调色，不随
 * 全局主题变化，以免登录页出现主题切换导致的对比度问题。
 */
// —— 依赖导入 ——
// Vue 组合式 API：onMounted 建立时钟/动画/观察器，onBeforeUnmount 对称清理；
// reactive 适合表单、接触点、遥测、准星这类按属性就地更新的对象；
// ref 适合基础类型或需要整体替换的引用（loading、DOM、布尔开关）。
// 导入顺序保持原有风格：运行时依赖在前，类型依赖与本地模块在后。
// 浏览器相关资源（rAF、定时器、ResizeObserver）统一在 setup/onMounted 中创建，
// 并由 onBeforeUnmount 回收，保证组件重复进入时不会叠加计时器。
// 这里不在模块顶层执行任何 DOM 查询或请求。
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
// useRoute 只读当前路由，用于读取登录后要返回的 redirect 查询参数；
// useRouter 负责在自检转场结束后执行最终跳转。
// 两者都在 setup 阶段取得，避免在异步回调里重复调用组合式函数。
// 注意：redirect 的内容不可信，必须再经过 safeRedirectPath 过滤。
import { useRoute, useRouter } from 'vue-router'
// Element Plus 图标组件直接作为 el-input 的 prefix-icon 传入，
// 由组件内部渲染为 SVG，无需额外注册全局组件。
// 只引入实际用到的两个图标，保持打包体积可控。
import { Lock, User } from '@element-plus/icons-vue'
// FormInstance 用于给模板 ref 标注类型，从而正确获得 validate 等方法；
// FormRules 约束 rules 对象的结构，让字段名和校验项在编译期就能被检查。
// 这两个都是纯类型导入，编译后不会产生运行时代码。
import type { FormInstance, FormRules } from 'element-plus'
// 认证状态仓库：login(username, password) 负责请求后端、写入会话与用户信息，
// 失败时抛出异常，由 handleLogin 统一捕获并给出抖动反馈。
// 登录页不直接操作 localStorage 或请求库，所有凭证细节都封装在 store 内，
// 这样后续切换认证方式（验证码、SSO）时只需修改 store，不影响本页面。
// 这里只解构出整个 store，而不是提前取 login 方法，避免在 store 初始化前
// 访问其内部状态；调用时机始终在用户提交表单之后。
import { useAuthStore } from '@/stores/auth'
// fx 提供两个轻量交互工具：
//   bindSoundToggle —— 给右上角音效按钮绑定开关逻辑，内部维护按钮的激活态；
//   toast —— 统一的轻提示，用于演示账号填入、忘记密码等即时反馈。
// 二者都不依赖登录业务，因此放在通用工具模块里复用。
// bindSoundToggle 需要真实 DOM，所以在 onMounted 中传入按钮元素调用；
// toast 则可在任意事件回调中直接调用。
import { bindSoundToggle, toast } from '@/lib/fx'
// 品牌标识组件：接收 size 属性控制尺寸，内部绘制 ToolShare 的图形标志。
// 使用组件而不是内联 SVG，保证登录页与侧边栏、加载页的品牌视觉一致。
// 颜色由父级 CSS 变量 --brand/--ink 覆盖，因此能融入本页的石墨铜金配色。
// 它只负责图形本身，不包含文字，文字标题由下方的 .cl-brand 自行排版。
import BrandMark from '@/components/BrandMark.vue'

// —— 路由与认证实例 ——
// route 是响应式的当前路由对象，redirect 参数可能在组件存活期间变化；
// 因此 safeRedirectPath 在真正跳转前才读取，而不是在 setup 时缓存一次。
// router 用于 push 目标地址，push 返回 Promise，调用处用 await 等待导航完成。
// auth 是 Pinia store 实例，保持引用即可，属性访问会自动解包。
// 三者都是模块级单例，重复创建没有意义，因此只在 setup 顶层获取一次。
// 这里不做任何路由守卫逻辑，认证校验由 auth store 与路由层负责。
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/* ---------- 登录业务（完整保留原逻辑） ---------- */
// formRef 指向 el-form 组件实例，handleLogin 通过它触发 Element Plus 的校验；
// 使用 ref<FormInstance>() 而不是 any，才能在 TS 中获得 validate 的类型提示。
// loading 同时承担两个职责：防止重复提交、驱动按钮的 loading 视觉状态。
// 提交期间按钮被 disabled，避免用户连点导致多次 auth.login 与多次跳转。
// form 使用 reactive 而不是 ref，模板里可以直接 form.username 双向绑定，
// 不需要在模板中反复写 .value；字段与后端登录接口的命名保持一致。
// 初始值为空字符串，保证首次渲染时输入框显示 placeholder 而不是 undefined。
// 表单只在点击按钮或按 Enter 时提交，输入过程中不做即时远程校验。
const formRef = ref<FormInstance>()
const loading = ref(false)
// 登录表单模型：只包含账号与密码两个字段。
// reactive 让 el-form 的 :model 获得稳定引用，字段变更可直接被校验器观察到。
// 不在模型中放 token 或验证码，避免把敏感值暴露给模板调试工具。
// 账号对应后端的管理员用户名，密码为明文仅存在于内存；
// 登录成功后由 auth store 负责持久化会话，本对象不承担存储职责。
// 组件卸载时随作用域一起回收，不会残留到下一次登录。
const form = reactive({
  username: '',
  password: '',
})

// Element Plus 表单校验规则：
//   username / password 均为必填，触发时机为 blur（失焦）。
// 选择 blur 而不是 change，是为了避免用户刚输入第一个字符就弹红字，
// 同时与 handleLogin 中的 validate() 形成「失焦即时提示 + 提交全量复核」。
// validate() 会返回 Promise<boolean>；任一字段失败即 reject，
// handleLogin 用 .catch(() => null) 把它折叠成 falsy，统一走抖动分支。
// 当前只做必填校验，复杂度校验（长度、字符集）交给后端，
// 避免前端规则与后端策略不一致时出现「前端通过、后端拒绝」的割裂体验。
// hide-required-asterisk 在模板上关闭了星号，但 required 规则仍然生效。
// 错误文案由 Element Plus 渲染在 .el-form-item__error 中，样式在 CSS 中重绘。
const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// 一键填入演示账号：
//   1. 直接把 admin / Aa123456 写入表单模型，触发输入框的响应式更新；
//   2. 用 toast 说明下一步操作，降低演示时的操作成本。
// 该函数不自动提交，也不绕过校验——仍然需要用户点击「启动控制台」，
// 这样演示路径与真实登录路径完全一致，不会掩盖校验或接口问题。
// 演示账号只适合开发/验收环境，生产环境应由部署配置决定是否展示该区域。
// 写入后表单保持 dirty 状态，用户可继续手动修改；
// 如果之前有校验错误，下一次提交会重新校验并清除错误提示。
// toast 的 type 为 info，与错误提示（error）在视觉上区分开。
// 这里不调用 formRef.clearValidate()，让错误提示自然随输入/提交更新。
// 函数没有返回值，调用方无需等待，交互是同步完成的。
// 密码以常量形式硬编码，仅用于本地演示，不参与任何持久化。
function fillDemo() {
  form.username = 'admin'
  form.password = 'Aa123456'
  toast('演示账号已填入', { type: 'info', msg: '点击「启动控制台」即可进入' })
}

// 计算登录成功后的落点，核心职责是阻止开放重定向（Open Redirect）。
//
// route.query.redirect 来自 URL，攻击者可以构造：
//   /login?redirect=https://evil.example   （绝对外链）
//   /login?redirect=//evil.example         （协议相对地址，会被浏览器当成外链）
// 因此这里只接受同时满足以下条件的字符串：
//   1. typeof redirect === 'string'，排除数组/对象等异常类型；
//   2. 以单个 "/" 开头，保证是站内绝对路径；
//   3. 不以 "//" 开头，避免协议相对地址逃逸到外部域名。
// 任一条不满足就回落到 "/dashboard"，保证用户始终留在管理端。
// 只放行站内路径还能保留查询串，例如 /tools?status=rented 会被完整带回。
// 反例 "javascript:alert(1)" 不以 "/" 开头，会被直接拒绝。
// 这里不校验目标路径是否存在，未知路由交由前端路由的兜底页处理。
// 也不做二次 URL 解码，避免解码后重新拼出外链造成绕过。
// 函数是纯读取：只读取 route.query 并返回字符串，不触发导航、不修改状态。
// 真正执行跳转的是 playBootAndRedirect 末尾的 router.push(dest)。
// 兜底地址固定为 /dashboard，与登录后的默认首页保持一致。
// 返回值始终是字符串，调用方无需再判空。
function safeRedirectPath(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/dashboard'
}

/* ---------- 雷达星图 · 常量与坐标 ---------- */
// —— 雷达 viewBox 坐标系 ——
// SVG 使用 viewBox="0 0 120 120"，以下三个常量都以这个逻辑坐标系为单位，
// 与屏幕像素无关：外层 .radar-stage 通过 stageSize 控制实际显示尺寸。
// CX / CY 是雷达圆心，取 60/60 让图形在 120×120 视口中居中。
// R 是最大距离圈半径，取 44 留出 16 个单位给方位刻度与度数文字。
// 所有极坐标、刻度、接触点、扫描波瓣都基于这三个值推导，
// 调整尺寸时只需改这里，不需要逐个修改 SVG 元素。
// 这些常量不参与响应式更新，缩放由 CSS 宽高完成。
// 逻辑坐标的好处是：放大/缩小雷达时，坐标计算与文本字号比例保持一致。
// 鼠标事件需要把像素坐标换算回 120 单位，换算逻辑见 onRadarMove。
const CX = 60
const CY = 60
const R = 44

// 极坐标转笛卡尔坐标：
//   deg 是以正上方为 0°、顺时针递增的方位角；
//   r   是距圆心的逻辑距离（0..R）。
// 先减去 90° 把「0° 朝上」转换成数学坐标系的「0° 朝右」，
// 再乘以 π/180 转成弧度，最后叠加圆心 CX/CY。
// 返回 x/y 均为浮点数，调用方按需 toFixed 后写入 SVG 属性，
// 避免生成过长的数值字符串。
// 该函数是纯函数，不读取任何响应式状态，便于复用到刻度、波瓣和接触点。
// 示例：polar(0, R) 得到圆心正上方的点，polar(90, R) 得到正右方，
// polar(180, R) 得到正下方，polar(270, R) 得到正左方。
// 方位角在 0..360 之外也能工作，三角函数天然支持周期性，
// 但调用方通常先通过 % 360 归一化，保持可读性。
// 返回对象每次都是新对象，调用方可以安全地缓存或修改，不会互相污染。
// 由于只做数学运算，测试环境调用也不会触碰浏览器 API。
// 精度问题可忽略：最终坐标保留两位小数，远高于 120 单位视口的显示需求。
function polar(deg: number, r: number): { x: number; y: number } {
  const a = (deg - 90) * (Math.PI / 180)
  return { x: CX + r * Math.cos(a), y: CY + r * Math.sin(a) }
}

// 扫描波瓣：用三层扇形叠加模拟雷达扫过后的余辉。
// make(from, to, op) 接收起始/结束方位角和不透明度，
// 通过 polar 取圆弧两端点，拼出 "M 圆心 L 起点 A 弧线 终点 Z" 的扇形路径。
// 三个波瓣分别是：
//   -18°..0°、opacity 0.30：紧贴扫描线的明亮前沿；
//   -42°..0°、opacity 0.16：中段余辉；
//   -70°..0°、opacity 0.08：最远处逐渐淡出的尾迹。
// SVG 的 A 命令使用 large-arc-flag=0、sweep-flag=1，保证沿顺时针画小弧。
// 整个 <g> 在模板中通过 rotate(sweepDeg 60 60) 旋转，因此这里只生成静态路径，
// 每帧只需更新 transform，避免重复计算 d 属性。
// toFixed(2) 控制路径字符串长度，减少模板 diff 带来的无谓重排。
// 波瓣数量固定为 3，既能有层次感，又不会让扫描区域显得浑浊。
// op 直接作为 fill-opacity 写入模板，颜色统一使用铜金 #D89B43。
// 这些路径只创建一次（IIFE 立即求值），后续动画不再触发重算。
// 若将来需要调整雷达半径，波瓣会随 R 自动缩放，无需改动画代码。
// 扫描方向为顺时针，与方位角 0° 朝上、顺时针递增的约定一致。
// 负角度表示扫描线后方，因此视觉上形成拖尾而不是预判光。
const sweepWedges = (() => {
  const make = (from: number, to: number, op: number) => {
    const p1 = polar(from, R)
    const p2 = polar(to, R)
    return {
      d: `M ${CX} ${CY} L ${p1.x.toFixed(2)} ${p1.y.toFixed(2)} A ${R} ${R} 0 0 1 ${p2.x.toFixed(2)} ${p2.y.toFixed(2)} Z`,
      op,
    }
  }
  return [make(-18, 0, 0.3), make(-42, 0, 0.16), make(-70, 0, 0.08)]
})()

// 扫描前沿的端点：方位角 0°、半径 R 处的坐标。
// 模板用它画两条重合的线（宽而暗 + 窄而亮），形成带光晕的扫描线。
// 因为 <g> 会整体旋转，这里只需要保存未旋转时的基准点。
// 该点与波瓣使用同一套极坐标换算，确保线与扇形边缘严格对齐。
// 常量只在模块初始化时计算一次，动画期间按引用读取。
const leadPoint = polar(0, R)

// 方位刻度：每 15° 一根，共 24 根，覆盖完整 360°。
// major 表示是否为 30° 的整数倍：主刻度更长、颜色更亮，并额外绘制度数文字。
// 每根刻度需要四个端点：
//   a -> b：从 R-(4|2) 到 R 的径向短线；
//   lx/ly：在 R+7 处放置度数字，避免与刻度线重叠。
// 结果一次性生成并缓存，模板中用 v-for 渲染，扫描动画不会重建数组。
// 度数文字只渲染 major 项，减少 24 个文本节点带来的布局开销。
// d 同时作为 v-for 的 key，因为 0..345 的 15° 步进值天然唯一。
// 主刻度 12 根、次刻度 12 根，主次交替让读数更直观。
// 坐标直接存为数字，模板绑定 x1/y1/x2/y2 时无需再调用 polar。
// 刻度线颜色在模板中按 major 三元表达式选择，样式不进入 CSS。
// 若视口尺寸变化，SVG 只做等比缩放，刻度逻辑坐标无需重算。
const ticks = Array.from({ length: 24 }, (_, k) => {
  const d = k * 15
  const major = d % 30 === 0
  const a = polar(d, R - (major ? 4 : 2))
  const b = polar(d, R)
  const lp = polar(d, R + 7)
  return { d, major, x1: a.x, y1: a.y, x2: b.x, y2: b.y, lx: lp.x, ly: lp.y }
})

// 中心枢纽：以圆心为中心、半径 8 的正六边形路径。
// 用 6 个 60° 等分点连成闭合多边形，作为雷达的「本站」标记。
// 先 map 成 "x y" 字符串再用 " L " 连接，减少模板中的拼接逻辑。
// 六边形比圆形更有仪表盘感，也与方位刻度的机械风格一致。
// 路径只生成一次，颜色和描边由模板中的属性控制。
// 半径 8 明显小于最内圈（R*0.25=11），保证枢纽不会盖住第一条距离圈。
// toFixed(2) 与波瓣保持一致，避免出现超长小数。
// 该路径位于所有接触点之后绘制，因此始终显示在最上层。
const hubPath = (() => {
  const pts = Array.from({ length: 6 }, (_, i) => polar(i * 60, 8))
  return `M ${pts.map((p) => p.x.toFixed(2) + ' ' + p.y.toFixed(2)).join(' L ')} Z`
})()

/* ---------- 8 个工具接触点（石墨铜金 · 状态着色） ---------- */
// 雷达接触点的运行时结构：
//   id/code/name/status 来自 TOOLS 种子数据，用于悬浮提示与状态着色；
//   deg 是接触点所在方位角，用来判断扫描线是否扫过；
//   x/y 是由 deg 与随机半径换算出的逻辑坐标，直接绑定到 SVG；
//   glow 是 0..1 的亮度包络，被扫过时置 1，然后逐帧衰减；
//   ping 是 0..1 的扩散环进度，被扫过时置 0，然后逐帧增长。
// glow 与 ping 共同构成「命中 -> 高亮 -> 扩散 -> 回落」的视觉反馈。
// 这些字段放在 reactive 数组中，frame() 每帧修改即可驱动视图更新。
// deg 只用于命中检测和调试，不直接渲染；
// x/y 才是 SVG 真正使用的坐标，二者在初始化时保持一致。
// status 使用后端返回的大写枚举（AVAILABLE/RENTED/MAINTENANCE），
// 展示层再通过 STATUS_TEXT 转成中文，避免把文案耦合进种子数据。
// glow/ping 初始分别为 0/1：页面刚加载时没有高亮，也没有正在扩散的环。
// 接口中的字段全部为必填，避免模板里出现 undefined 参与数学运算。
// 该接口只描述展示层需要的数据，不包含价格、位置等业务字段。
interface Blip {
  id: number
  code: string
  name: string
  status: string
  deg: number
  x: number
  y: number
  glow: number
  ping: number
}

// 8 个工具接触点的种子数据，仅用于登录页的雷达演示。
// 字段含义：
//   id     —— 稳定的数字编号，同时用于生成 T-01..T-08 标签；
//   code   —— 工具资产编码，前缀区分库位（WX 维修、QJ 清洁、YY 园艺、QT 其他）；
//   name   —— 展示名称，悬浮提示中显示；
//   status —— 大写状态枚举，决定接触点颜色与中文文案。
// 这些数据不会在登录前请求后端，避免未认证时泄露真实资产列表；
// 真实工具数据由登录后的管理页面加载。
// 状态分布刻意设置为 5 可借 / 2 租出 / 1 维修，与遥测区和自检日志保持一致。
// 编号 1~8 与模板中的 T-xx 标签一一对应，方便演示时口头指代。
// code 中的 SQ-G 前缀是示例资产编码，不代表真实库位规则。
// 名称中包含具体型号，是为了让悬浮提示看起来像真实资产而不是占位符。
// 第 3、7 项为 RENTED，第 5 项为 MAINTENANCE，其余为 AVAILABLE。
// 该数组是只读语义：运行期间不会被修改，修改的是由它派生出的 blips。
// 若将来接入真实数据，只需替换这个数组的来源，blips 的构建逻辑无需改动。
// 状态枚举必须与 STATUS_COLOR/STATUS_TEXT 的键保持一致，否则会走兜底色。
// 这里不使用 as const，保持与接口宽松的类型兼容。
const TOOLS: Array<{ id: number; code: string; name: string; status: string }> = [
  { id: 1, code: 'SQ-G-WX-01-01', name: '博世锂电手电钻 GSR 12V', status: 'AVAILABLE' },
  { id: 2, code: 'SQ-G-WX-01-02', name: '铝合金伸缩梯 3.8m', status: 'AVAILABLE' },
  { id: 3, code: 'SQ-G-WX-02-01', name: '世达综合扳手套装 46 件', status: 'RENTED' },
  { id: 4, code: 'SQ-G-YY-01-01', name: '嘉丁纳电动绿篱修剪机', status: 'AVAILABLE' },
  { id: 5, code: 'SQ-G-QJ-01-01', name: '凯驰高压清洗机 K3', status: 'MAINTENANCE' },
  { id: 6, code: 'SQ-G-QT-01-01', name: '折叠平板搬运车 300kg', status: 'AVAILABLE' },
  { id: 7, code: 'SQ-G-WX-02-02', name: '牧田角磨机 9533B', status: 'RENTED' },
  { id: 8, code: 'SQ-G-QJ-02-01', name: '戴森无线吸尘器 V8', status: 'AVAILABLE' },
]

// 由 TOOLS 派生出可动画的接触点数组。
// 每个接触点的位置用两个伪随机公式打散，避免 8 个点均匀排成规则形状：
//   deg = (i * 47 + 15) % 360          —— 方位角错开，47 与 360 互质使分布较散；
//   r   = (0.34 + ((i * 37) % 50) / 100) * R
//                                        —— 半径落在 0.34R..0.83R，既不贴圆心也不出界。
// 使用固定公式而不是 Math.random()，保证每次刷新页面布局一致，便于视觉验收。
// 初始 glow=0、ping=1：没有高亮，也没有正在扩散的环。
// reactive 包裹后，frame() 每帧修改 glow/ping 就能触发对应 SVG 属性更新。
// 接触点的 deg 与极坐标半径一经确定就不再变化，只有 glow/ping 是动画量。
// 因此命中检测可以放心使用 b.x/b.y，不需要每帧重算位置。
// 47 与 37 这两个乘数只是演示用的错位常数，没有业务含义。
// 半径下限 0.34R 让接触点避开中心六边形，上限 0.83R 避免压住方位刻度。
// 数组顺序与 TOOLS 一致，T-01..T-08 的编号因此稳定可预期。
// 这里用 map 生成新对象，而不是直接扩展 TOOLS，保持种子数据不可变。
// 若 TOOLS 增删条目，blips 会自动跟随，无需手动调整数量。
// reactive 的深层代理会追踪每个 Blip 的属性，数组长度本身不需要响应式替换。
const blips = reactive<Blip[]>(
  TOOLS.map((t, i) => {
    const deg = (i * 47 + 15) % 360
    const r = (0.34 + ((i * 37) % 50) / 100) * R
    const p = polar(deg, r)
    return { id: t.id, code: t.code, name: t.name, status: t.status, deg, x: p.x, y: p.y, glow: 0, ping: 1 }
  }),
)

// 状态 -> 颜色映射，同时服务于雷达接触点、悬浮提示和底部图例。
// 颜色选择兼顾暗色背景对比度与语义：
//   AVAILABLE   #3BB273 绿色，表示可借用；
//   RENTED      #E0A33C 琥珀色，表示已租出；
//   MAINTENANCE #E56A5E 珊瑚红，表示维修中。
// 这三个颜色也与遥测数字的 ok/warn/bad 以及状态条的绿色保持一致，
// 让「颜色即状态」的语义在整页统一。
// 键必须与 TOOLS/后端状态枚举完全一致，大小写敏感。
// 不在映射里放兜底色，未知状态由 statusColor 统一返回铜金 #D89B43。
// 这样新增状态时即使忘记补映射，也不会渲染成透明或黑色。
// 颜色值直接写成十六进制，避免依赖主题变量导致登录页变色。
const STATUS_COLOR: Record<string, string> = {
  AVAILABLE: '#3BB273',
  RENTED: '#E0A33C',
  MAINTENANCE: '#E56A5E',
}
// 状态 -> 中文文案映射，用于悬浮提示中的「可借用 / 已租出 / 维修中」。
// 与 STATUS_COLOR 的键保持一一对应，新增状态时需要同时补两处。
// 文案只描述工具当前状态，不包含操作建议，避免在登录页出现业务引导。
// 未知状态由 statusText 兜底为「未知」，不会把原始枚举直接暴露给用户。
// 映射在模块级只创建一次，模板中的每次悬浮读取都是普通对象查找。
// 这里不把颜色和文案合并成一个对象，是为了让各自的类型更简单。
const STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可借用',
  RENTED: '已租出',
  MAINTENANCE: '维修中',
}
// 读取状态颜色，未知状态回落到铜金 #D89B43。
// 使用 || 而不是 ??，因为映射缺失时返回的是 undefined，两者行为一致；
// 但值本身都是非空字符串，不存在空字符串被误判的情况。
// 该函数在模板的 SVG 属性中高频调用（每个接触点多次），
// 只做一次对象查找，没有额外分配，性能开销可忽略。
// 返回值直接作为 fill/stroke，因此必须是合法的 CSS 颜色字符串。
function statusColor(s: string): string {
  return STATUS_COLOR[s] || '#D89B43'
}
// 读取状态中文文案，未知状态显示「未知」。
// 与 statusColor 使用同一套兜底策略，保证颜色和文字不会出现一个有一个无。
// 返回值直接插入模板文本节点，Vue 会自动转义，不存在 XSS 风险。
// 不用国际化函数，是因为当前管理端只有中文界面，登录页无需引入 i18n。
// 若将来需要多语言，可在此处替换为 t(`status.${s}`)。
function statusText(s: string): string {
  return STATUS_TEXT[s] || '未知'
}
// 两位补零：把 0..99 的数字格式化为 "00" 风格。
// 用于 T-01 这样的接触点标签，以及 HUD 中的 CONTACTS 计数。
// String.prototype.padStart 是 ES2017 标准方法，无需引入工具库。
// 输入为负数时 padStart 不会补零，但本页所有调用都保证非负。
// 输入超出两位时原样返回，不会截断，便于调试时发现意外值。
function pad2(n: number): string {
  return String(n).padStart(2, '0')
}
// 三位补零：把方位角格式化为 "000".."359" 的度数。
// 先 Math.floor 再对 360 取模，保证扫描角连续增长时文字始终是三位的整度数；
// 例如 359.7 会显示为 "359"，而 360 会回到 "000"。
// 用于 HUD 的 BRG 读数和雷达周围的主刻度文字。
// 取模也顺手处理了负角度，避免出现 "-01" 这样的读数。
function pad3(n: number): string {
  return String(Math.floor(n) % 360).padStart(3, '0')
}

/* ---------- 雷达动画 ---------- */
// 读取系统的「减少动态效果」偏好，只在模块初始化时求值一次。
// 该偏好通常由操作系统辅助功能设置，用户改动后刷新页面即可生效。
// 为 true 时：
//   - frame() 不启动，雷达停留在初始角度；
//   - startCounts() 被跳过，遥测数字直接写终值；
//   - playBootAndRedirect() 跳过自检遮罩，直接 router.push。
// 这样既尊重无障碍偏好，也避免在低性能设备上同时跑 rAF 与多组定时器。
// CSS 侧还有一份 @media (prefers-reduced-motion: reduce) 关闭装饰动画，
// 两处配合覆盖 JS 动画与纯 CSS 动画。
const reducedMotion = !!window.matchMedia('(prefers-reduced-motion: reduce)').matches
// 当前扫描方位角，单位度，0..360 循环。
// frame() 每帧增加 0.55°，约 1.8 秒转一圈；该值同时用于：
//   - 旋转扫描波瓣的 transform；
//   - HUD 中的 BRG 读数；
//   - 判断接触点是否被扫描线扫过。
// reducedMotion 模式下不启动 frame，因此它会一直保持 0。
const sweepDeg = ref(0)
// 雷达锁定开关：登录成功、自检转场开始时置 true。
// 锁定后 frame() 仍会被 rAF 调度，但会提前 return，不再推进扫描角；
// 这样波瓣停住，视觉上表示「系统正在接管」，同时避免转场期间继续计算命中。
// 锁定只影响扫描，不影响已有点击/悬浮事件；转场遮罩会覆盖交互区域。
// 之所以不取消 rAF，是为了让转场结束后无需重新启动动画循环。
// 该值不会在组件生命周期内复位，因为登录成功后会直接离开当前页面。
const radarLocked = ref(false)
// 雷达容器的模板 ref，用于读取可用宽高、查询 .radar-stage，
// 以及作为 ResizeObserver 的观察目标。
// 必须在 onMounted 之后才有值，sizeRadar 内部做了空值保护。
const radarWrap = ref<HTMLElement>()
// 雷达当前边长（px），由 sizeRadar 根据容器宽高计算，最小 200。
// 通过 :style 同时设置 .radar-stage 的 width/height，
// 让 SVG 在保持正方形的前提下自适应左栏可用空间。
// 初始值 320 只是首帧的占位，onMounted 后会被真实尺寸覆盖。
// 使用 px 字符串拼接而不是 CSS 变量，便于 ResizeObserver 即时更新。
const stageSize = ref(320)
// 当前鼠标附近（阈值 14 个逻辑单位）的接触点；没有命中时为 null。
// 模板据此决定是否显示 .radar-tip 悬浮卡片。
// 只保存引用而不复制对象，因此卡片内容会随 glow/ping 之外的状态变化保持同步。
// 阈值 14 在 120 单位视口中大约相当于接触点半径的 5 倍，方便鼠标吸附。
// 多个接触点同时接近时取最近的一个，遍历逻辑在 onRadarMove 中。
const hoverBlip = ref<Blip | null>(null)
// 鼠标到雷达圆心的归一化距离，显示为 "00%".."100%"。
// 超出最大距离圈时显示 "--"，表示鼠标不在雷达有效范围内。
// HUD 与准星中心都会展示该读数，模拟雷达操作员的距离读数。
// 计算方式为 hypot(dx, dy) / R，再乘以 100 取整；
// 它不是屏幕像素距离，因此窗口缩放时读数保持一致。
const mouseRng = ref('--')
// 鼠标准星状态：
//   x/y 是相对 .radar-stage 的百分比坐标，模板用 left/top 定位；
//   on  控制准星是否可见（进入雷达显示、离开隐藏）。
// 使用百分比而不是像素，窗口缩放时准星无需重新计算。
// 初始值 50/50 只是占位，on=false 时不会渲染可见效果。
// 准星的四角括号由 .rt.tl/.tr/.bl/.br 四个 i 元素绘制。
const reticle = reactive({ x: 50, y: 50, on: false })

// 根据雷达容器当前宽高，计算正方形舞台的边长。
// 取 min(clientWidth, clientHeight) - 8，留出 8px 内边距，
// 并用 Math.max(200, ...) 保证极端窄屏下仍有一个可用的最小尺寸。
// 调用时机：
//   - onMounted 首次布局完成后；
//   - ResizeObserver 观察到容器尺寸变化时；
//   - 不支持 ResizeObserver 时，退化为 window resize 监听。
// 容器不存在时直接 return，避免在卸载竞态中访问 null。
// 该函数只改 stageSize，不触碰 SVG 坐标，坐标始终按 120 逻辑单位渲染。
// 减 8 而不是更大值，是为了让雷达尽量填满左栏，同时不贴边。
// clientWidth/clientHeight 不含边框，因此无需再扣除 border。
// 如果容器被隐藏（宽高为 0），结果会被下限 200 兜住，避免 SVG 消失。
// 该计算是幂等的，重复调用不会累积误差。
function sizeRadar() {
  const el = radarWrap.value
  if (!el) return
  stageSize.value = Math.max(200, Math.min(el.clientWidth, el.clientHeight) - 8)
}

// 判断角度 a 是否落在扫描线从 from 推进到 to 的区间内。
// 雷达角在 0..360 循环，因此需要分两种情况：
//   - to >= from：区间没有跨过 0°，条件是 from < a <= to；
//   - to <  from：区间跨过 0°（如 359° -> 1°），条件是 a > from 或 a <= to。
// 使用左开右闭区间，避免扫描线恰好停在某个接触点上时连续两帧重复命中。
// a/from/to 都是度，调用方保证 from/to 已归一化到 0..360。
// 该函数只做比较，不修改状态，便于单独推演边界情况。
// 例：from=359、to=1 时，a=0.5 命中，a=359.5 不命中（左开），a=1 命中。
// 例：from=10、to=20 时，a=10 不命中，a=10.1 命中，a=20 命中。
// 采用 <= 右端点，是为了让扫描线正好压住接触点时产生一次高亮。
// 若改成左闭右开，扫描线停在接触点上的那一帧会漏掉命中。
// 浮点角度下边界几乎不会精确相等，因此该选择主要影响可读性而非实际行为。
// 该函数不处理 from === to 的退化情况，调用方每帧都会推进 0.55°，可忽略。
function crossed(a: number, from: number, to: number): boolean {
  if (to >= from) return a > from && a <= to
  return a > from || a <= to
}

// 上一帧的扫描角，用于构造 crossed 的 [from, to] 区间。
// 放在模块作用域而不是 reactive，因为它只服务于动画计算，不需要驱动视图。
// 每帧先保存旧值再推进新值，保证区间长度恰好是一帧的增量。
// 扫描锁定时不会更新它，因此解锁后会从原位置继续。
let prevSweep = 0
// 雷达主循环，由 requestAnimationFrame 驱动。
// 执行顺序：
//   1. alive 为 false 时停止调度（卸载后的最后一帧保护）；
//   2. 先预约下一帧，保证即使本帧提前 return 循环也不会断；
//   3. radarLocked 时提前返回，扫描角与命中检测都暂停；
//   4. 记录 prevSweep，推进 sweepDeg 0.55° 并对 360 取模；
//   5. 遍历所有接触点：
//        - 若本帧扫描线扫过其方位角，则 glow 置 1、ping 置 0；
//        - glow 每帧衰减 0.016，约 60 帧（1 秒）回落到 0；
//        - ping 每帧增长 0.018，约 55 帧后达到 1，扩散环消失。
// 每帧只做常数级运算（8 个接触点），开销很小。
// 先预约下一帧再处理逻辑，可以避免某一帧异常导致动画永久停止。
// glow 的衰减与 ping 的增长相互独立：
//   glow 控制接触点本体的填充不透明度（0.3 + 0.7 * glow）；
//   ping 控制外层扩散环的半径与透明度，ping < 1 时才渲染。
// 命中瞬间 ping=0，扩散环从半径 3.5 开始向外扩张到 23.5。
// 0.016/0.018 是按 60fps 估算的经验值，帧率波动时动画时长会轻微变化，
// 但雷达是装饰性动效，不值得为此引入基于 deltaTime 的复杂计算。
// 扫描线每 1.8 秒转一圈，8 个接触点会依次被点亮，形成持续的呼吸感。
// 若接触点正好位于扫描起点，crossed 的左开区间会避免重复触发。
// 该循环不读取 DOM，也不触发布局，只修改响应式数值，由 Vue 批量更新 SVG。
// radarLocked 时提前 return 但仍继续预约下一帧，因此解锁后无需重新启动。
// alive 在 onBeforeUnmount 中置 false，防止组件销毁后回调继续访问 blips。
// 这是唯一一处持续运行的 rAF；时钟与延迟使用 setInterval，互不干扰。
// 每帧取模保证 sweepDeg 始终在 0..360，HUD 的 pad3 结果稳定为三位数。
// 这里没有使用 CSS 动画旋转波瓣，是因为接触点命中检测需要知道精确角度。
function frame() {
  if (!alive) return
  rafId = requestAnimationFrame(frame)
  if (radarLocked.value) return
  prevSweep = sweepDeg.value
  sweepDeg.value = (sweepDeg.value + 0.55) % 360
  blips.forEach((b) => {
    if (crossed(b.deg, prevSweep, sweepDeg.value)) {
      b.glow = 1
      b.ping = 0
    }
    b.glow = Math.max(0, b.glow - 0.016)
    b.ping = Math.min(1, b.ping + 0.018)
  })
}

// 指针在雷达上移动时的交互入口，绑定在 .cl-radar-wrap 上。
// 处理流程：
//   1. 找到 .radar-stage 并用 getBoundingClientRect 取得其屏幕位置；
//   2. 把指针的 clientX/clientY 换算成 120 逻辑单位坐标 x/y；
//   3. 计算到圆心的距离 rng，归一化后写入 mouseRng（超出 R 显示 "--"）；
//   4. 更新准星的百分比位置并置 on=true；
//   5. 在 14 逻辑单位的阈值内寻找最近的接触点，写入 hoverBlip。
// 使用 clientX/Y 而不是 offsetX/Y，避免事件冒泡到不同元素时坐标系错位。
// rect 每次都重新读取，因此页面滚动、缩放后仍然准确。
// 该处理函数只更新状态，不直接操作 DOM，渲染交给 Vue。
// 换算公式：((clientX - rect.left) / rect.width) * 120。
// 因为 SVG 使用 preserveAspectRatio="xMidYMid meet"，
// 实际绘制区域可能小于 rect，但在当前正方形舞台下两者一致，
// 因此可以安全地按 rect 宽高做线性映射。
// rng = hypot(dx, dy) / R，<=1 表示位于最大距离圈内。
// pad2(Math.round(rng * 100)) 得到两位百分比，例如 "37%"。
// 阈值 nearD 初始为 14，遍历中不断缩小，最终得到最近命中；
// 若所有接触点都更远，hoverBlip 会被置为 null，提示卡随之消失。
// 接触点距离使用其固定 x/y，而不是屏幕像素，因此缩放不影响吸附范围。
// reticle.x/y 使用百分比而非逻辑坐标，模板直接写 left/top 百分比。
// 准星与悬浮卡是两个独立元素，前者跟随鼠标，后者吸附到接触点。
// 这里没有做节流：pointermove 本身已按浏览器刷新率合并，
// 且每次计算只有 8 次 hypot，性能足够。
// 如果将来接触点数量大幅增加，可考虑引入空间索引或降低采样频率。
// 该函数不修改 glow/ping，命中高亮完全由扫描线驱动，避免鼠标干扰雷达语义。
// 指针离开时由 onRadarLeave 统一复位，保证不会残留提示。
function onRadarMove(e: PointerEvent) {
  const el = radarWrap.value
  if (!el) return
  const stage = el.querySelector('.radar-stage') as HTMLElement | null
  if (!stage) return
  const rect = stage.getBoundingClientRect()
  const x = ((e.clientX - rect.left) / rect.width) * 120
  const y = ((e.clientY - rect.top) / rect.height) * 120
  const dx = x - CX
  const dy = y - CY
  const rng = Math.hypot(dx, dy) / R
  mouseRng.value = rng <= 1 ? pad2(Math.round(rng * 100)) + '%' : '--'
  reticle.x = ((e.clientX - rect.left) / rect.width) * 100
  reticle.y = ((e.clientY - rect.top) / rect.height) * 100
  reticle.on = true

  let near: Blip | null = null
  let nearD = 14
  blips.forEach((b) => {
    const d = Math.hypot(b.x - x, b.y - y)
    if (d < nearD) {
      nearD = d
      near = b
    }
  })
  hoverBlip.value = near
}
// 指针离开雷达区域时复位所有指针相关的临时状态：
//   hoverBlip 置空 -> 悬浮提示消失；
//   mouseRng 复位为 "--" -> HUD 不再显示上一次的距离；
//   reticle.on 置 false -> 四角准星隐藏。
// 这里只清理指针态，不触碰扫描角、接触点 glow/ping 或遥测数据。
// 与 onRadarMove 对称，保证进入/离开时状态不会残留。
// 即使指针快速移出导致没有触发 move，leave 也会执行清理。
// 指针重新进入时会由 move 重新点亮准星。
function onRadarLeave() {
  hoverBlip.value = null
  mouseRng.value = '--'
  reticle.on = false
}

/* ---------- 遥测 / 时钟 / 延迟 ---------- */
// 遥测面板的四个数字：
//   online  —— 工具在线总数；
//   ready   —— 可借用数量；
//   out     —— 已租出数量；
//   service —— 维修中数量。
// 初始全为 0，挂载后由 countTo 从 0 缓动到目标值。
// keyof typeof tm 被 countTo 用作字段名，保证只能写入这四个键。
const tm = reactive({ online: 0, ready: 0, out: 0, service: 0 })
// 底部状态条中的本地时间字符串，格式 YYYY-MM-DD HH:mm:ss。
// 由 tickClock 每秒刷新一次，初始为空，挂载后立即填充。
// 使用字符串而不是 Date 对象，避免模板中每次渲染都重新格式化。
const clockStr = ref('')
// 模拟网络延迟（毫秒），初始 23，挂载后每 2.6 秒随机刷新为 18..31。
// 仅用于状态条的演示读数，不反映真实请求耗时。
// 使用 ref 是因为它会被定时器整体替换，而不是就地修改属性。
const ping = ref(23)

// 数字缓动动画：把 tm[key] 从 0 平滑增加到 to。
// 参数：
//   key —— tm 的四个字段之一；
//   to  —— 目标值，通常是 8/5/2/1。
// 实现要点：
//   - dur 固定 1100ms；
//   - 用 performance.now() 记录起点，每帧计算进度 p（0..1）；
//   - 缓动函数 e = 1 - (1 - p)^3，即 easeOutCubic，先快后慢；
//   - 结果四舍五入后写回 tm[key]，模板中的 .num 会感知更新；
//   - p < 1 时继续预约下一帧，否则自然结束。
// 每次调用都会新建一条 rAF 链，四个数字因此可以并行缓动。
// alive 检查确保组件卸载后不会继续写入已销毁的响应式对象。
// from 固定为 0，因为遥测数字每次进入页面都从零开始更符合仪表初始化语义。
// 如果将来需要从当前值继续动画，只需把 from 改成 tm[key]。
// Math.pow(1 - p, 3) 在 p=0 时为 1，e=0；p=1 时为 0，e=1。
// easeOutCubic 让数字快速接近目标，最后几十毫秒缓慢收敛，观感更自然。
// 四舍五入保证显示的是整数，避免出现 "7.999" 这类读数。
// 该函数不返回 Promise，调用方 startCounts 也不需要等待动画结束。
// 每条 rAF 链都会在 p>=1 时停止，不会长期占用主线程。
// 如果 reducedMotion 为 true，startCounts 根本不会调用它，直接写终值。
function countTo(key: keyof typeof tm, to: number) {
  const dur = 1100
  const from = 0
  const t0 = performance.now()
  function step(ts: number) {
    if (!alive) return
    const p = Math.min(1, (ts - t0) / dur)
    const e = 1 - Math.pow(1 - p, 3)
    tm[key] = Math.round(from + (to - from) * e)
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}
// 启动四个遥测数字的缓动，目标值与 TOOLS 的状态分布一致：
//   online=8、ready=5、out=2、service=1。
// 每个数字延迟 150 + i*90 ms 启动，形成从左到右依次跳动的节奏；
// 延迟本身不做清理，因为最长也只有 150 + 3*90 = 420ms，
// 且 countTo 内部有 alive 保护，卸载后不会继续预约帧。
// 只在非 reducedMotion 分支调用。
// 使用 setTimeout 而不是统一在一帧里启动，是为了制造错峰效果。
// targets 写成元组数组，key 受 keyof typeof tm 约束，拼写错误会在编译期暴露。
// 目标值刻意与种子数据状态计数吻合，避免视觉信息自相矛盾。
// 若将来接入真实统计，只需替换 targets 的来源，缓动逻辑不变。
function startCounts() {
  const targets: Array<[keyof typeof tm, number]> = [
    ['online', 8],
    ['ready', 5],
    ['out', 2],
    ['service', 1],
  ]
  targets.forEach(([key, to], i) => {
    setTimeout(() => countTo(key, to), 150 + i * 90)
  })
}

// 每秒刷新底部状态条的本地时间。
// 使用 new Date() 而不是服务端时间，因为这里只展示浏览器本地时钟。
// p(n) 是局部补零函数，保证月/日/时/分/秒始终两位；
// 年份保持四位，格式为 YYYY-MM-DD HH:mm:ss。
// getMonth() 返回 0..11，因此需要 +1。
// 该函数既在 onMounted 中立即调用一次，也由 setInterval 每秒调用。
// 立即调用避免首帧显示空字符串，造成状态条短暂塌陷。
// 时间字符串写入 ref，模板中的插值会自动更新。
// 不做时区转换，始终显示运行浏览器的本地时间。
function tickClock() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  clockStr.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ---------- 自检转场 ---------- */
// 自检日志的静态脚本，每行包含三段：
//   [0] 检查项名称，例如「身份核验」；
//   [1] 结果值，例如「通过」「完成」「8 件」；
//   [2] 补充说明，例如角色、数据量、区域与延迟。
// 模板按 revealedCount 逐行显示，未到达的行保持透明并左移 8px。
// 这些内容与雷达种子数据、遥测数字保持同一套演示口径：
//   8 件工具、5 可借、2 租出、1 维修，6 张租赁单、3 张报修单。
// 日志只在登录成功后播放，因此可以包含「权限矩阵」「安全通道」等提示。
// 它不是真实的后端自检结果，仅用于转场叙事。
// 五条日志对应：身份 -> 权限 -> 工具库 -> 业务数据 -> 安全通道，
// 顺序从认证到数据再到传输，读起来像一次完整的启动检查。
// 每行延迟 300ms 揭示，五行共 1.5 秒，加上头部与授权提示约 2.5 秒。
// 结果列在 CSS 中统一着绿色，补充说明着灰色，层级清晰。
// 使用数组而不是对象，是因为模板只需要按索引遍历并保持顺序。
// 文案中的数字如果将来变化，应同步更新遥测目标值，避免前后矛盾。
const BOOT_LINES: Array<[string, string, string]> = [
  ['身份核验', '通过', 'admin · 社区工作人员'],
  ['权限矩阵加载', '完成', 'OPERATIONS / FULL'],
  ['工具库同步', '8 件', '5 可借 · 2 租出 · 1 维修'],
  ['租赁 / 报修数据', '完成', '6 张租赁单 · 3 张报修单'],
  ['建立安全通道', 'TLS 1.3', 'cn-east-1 · 23ms'],
]
// 自检遮罩是否显示；登录成功后置 true，跳转前一直保持。
// 模板用 :hidden 控制，隐藏时不参与布局与交互。
// reducedMotion 模式下不会置 true，因此用户完全看不到遮罩。
const bootVisible = ref(false)
// 已揭示的日志行数，playBootAndRedirect 每 300ms 递增一次。
// 模板用 revealedCount > i 判断第 i 行是否点亮。
// 初值 0 表示所有行都处于未揭示状态。
const revealedCount = ref(0)
// 是否显示 ACCESS GRANTED 授权提示。
// 在日志全部揭示后置 true，并由 CSS 过渡放大淡入。
// 它比遮罩晚出现，形成「检查完成 -> 授权通过」的叙事顺序。
const grantVisible = ref(false)
// 是否启动全屏擦除动画（.wipe.go）。
// 在授权提示停留约 950ms 后置 true，擦除完成后再执行路由跳转。
// 擦除层是纯视觉元素，pointer-events: none，不拦截任何点击。
const wipeVisible = ref(false)

// 基于 setTimeout 的 Promise 化等待，供 playBootAndRedirect 串联动画节奏。
// 参数 ms 为毫秒；返回的 Promise 在定时器触发后 resolve。
// 不使用 requestAnimationFrame 计时，因为这里需要的是稳定的墙钟延迟，
// 而不是与刷新率绑定的帧间隔。
// 转场期间用户无法操作遮罩下的页面，因此这些等待不需要中途取消。
// 若将来支持取消登录，应在此处接收 AbortSignal 并清理定时器。
function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

// 登录成功后的自检转场，负责把「认证成功」演成一次系统接管。
// 流程：
//   1. 先计算安全落点 dest（必须在任何跳转前完成校验）；
//   2. reducedMotion 时直接 router.push(dest)，不播放任何动画；
//   3. 打开遮罩 bootVisible，锁定雷达扫描，并把所有接触点点亮；
//   4. 等待 200ms 让遮罩淡入；
//   5. 逐行揭示 BOOT_LINES，每行间隔 300ms；
//   6. 显示 ACCESS GRANTED，停留 950ms；
//   7. 启动横向擦除，等待 600ms 让擦除铺满屏幕；
//   8. 最后 await router.push(dest)，导航完成后组件卸载。
// 所有等待都是串行 await，保证动画顺序稳定，不会出现日志与擦除重叠。
// dest 在当前函数开头计算一次，后续不再读取可能变化的 route.query。
// 为什么最后才跳转：遮罩与擦除需要覆盖当前页面，
// 如果先 push 再播放动画，组件会立即卸载，动画根本来不及显示。
// 擦除动画 550ms，这里等待 600ms，留出约 50ms 余量。
// 日志五行 5*300=1500ms，加上头部淡入 200ms、授权 950ms、擦除 600ms，
// 整段转场约 3.25 秒；这是演示页可以接受的仪式感上限。
// radarLocked 置 true 后，扫描停在当前角度，接触点保持全亮，
// 视觉上从「持续扫描」切换为「目标锁定」。
// blips.forEach 直接把 glow 写成 1，而不是等扫描线依次扫过，
// 因为转场只有两三秒，逐个点亮来不及形成完整画面。
// 这里没有修改 ping，扩散环会继续按原节奏消失，避免所有点同时闪环。
// reducedMotion 分支不修改 bootVisible/radarLocked，也不启动任何定时器，
// 因此对辅助功能用户来说，登录就是一次普通的同步跳转。
// await router.push 可能因为导航守卫重定向而解析到别的地址，
// 这是路由层的职责，本函数不重复处理。
// 若 router.push 抛错（例如重复导航），错误会向上冒泡到 handleLogin，
// 但此时 loading 已在 finally 中复位，不会卡住按钮。
// 转场期间没有禁用表单，因为遮罩 z-index 高于表单且覆盖全屏，
// 指针事件无法到达下层；这比额外维护一个 disabled 状态更简单。
// 所有 setTimeout 延迟都很短，且页面即将卸载，不做取消是可以接受的取舍。
// 如果将来需要在转场中支持「取消」，应把这里的 sleep 换成可中断的实现。
async function playBootAndRedirect() {
  const dest = safeRedirectPath()
  if (reducedMotion) {
    await router.push(dest)
    return
  }
  bootVisible.value = true
  radarLocked.value = true
  blips.forEach((b) => (b.glow = 1))
  await sleep(200)
  for (let i = 0; i < BOOT_LINES.length; i++) {
    revealedCount.value = i + 1
    await sleep(300)
  }
  grantVisible.value = true
  await sleep(950)
  wipeVisible.value = true
  await sleep(600)
  await router.push(dest)
}

// 登录失败时的抖动开关，绑定到 .cl-card 的 shake 类。
// 置 true 后 CSS 动画播放 0.4s，结束后由 shake() 复位。
// 它只影响视觉反馈，不参与任何业务判断。
const shaking = ref(false)
// 触发一次卡片抖动，用于表达「校验失败 / 登录失败」。
// 先置 true 启动 CSS 动画，420ms 后复位。
// 420ms 比动画时长 400ms 略长，确保动画完整播放后再移除类，
// 避免用户在动画中途再次触发时类名没有变化、动画不重播。
// 该函数不区分失败原因，调用方在 validate 失败和 auth.login 抛错时都会调用。
// 多次快速失败时，后一次 setTimeout 会覆盖前一次的状态，
// 但由于都是置 false，不会出现状态错乱。
// 抖动是纯装饰反馈，不替代错误文案；字段错误仍由 Element Plus 显示。
// 抖动幅度仅 6px、时长 0.4s，强度较低，不会造成明显不适。
function shake() {
  shaking.value = true
  setTimeout(() => {
    shaking.value = false
  }, 420)
}

// 登录提交入口，点击按钮或按 Enter 都会触发。
// 步骤：
//   1. 调用 formRef.validate()，任一字段失败会 reject；
//      用 .catch(() => null) 把 reject 折叠为 null，统一用 falsy 判断；
//   2. 校验失败时触发 shake()，不调用 auth.login；
//   3. 校验通过后置 loading=true，按钮进入 loading 且 disabled；
//   4. await auth.login(username, password) 提交凭证；
//   5. 登录成功后 await playBootAndRedirect()，由它负责跳转；
//   6. 任意异常都走 catch，触发 shake() 反馈；
//   7. finally 中复位 loading，保证失败后按钮可以再次点击。
// 注意 loading 的复位发生在 playBootAndRedirect 之后，
// 因此转场期间按钮一直保持 loading，视觉上表示「正在接管」。
// formRef 可能尚未挂载（例如极早期调用），可选链会得到 undefined，
// validate 调用的结果是 undefined，await 后为 undefined，走 falsy 分支。
// validate() 成功时返回 true，失败时 reject；Element Plus 不会返回 false，
// 因此这里的 !valid 实际上只在 reject 被折叠为 null 时成立。
// auth.login 内部负责请求、会话持久化与用户信息写入，
// 本函数不关心它用的是密码还是验证码，只处理成功/失败两条路径。
// 登录成功后才播放转场，避免在凭证无效时展示「ACCESS GRANTED」。
// 播放转场期间不再次检查 loading，因为按钮已 disabled，指针事件被遮罩拦截。
// catch 不打印错误，是为了避免在控制台泄露后端返回的认证细节；
// 用户只需要知道「这次没成功」，具体原因由后端日志排查。
// finally 一定执行，即使 playBootAndRedirect 因导航异常抛错，
// 按钮也不会永久停留在 loading 状态。
// 这里没有手动清空密码，因为成功后会离开页面，失败时保留输入便于重试。
// 如果将来需要区分「网络错误」和「凭证错误」，可在 catch 中读取 error 类型，
// 但当前 UI 用统一的抖动 + 后端 toast 已经足够。
// 函数本身不返回任何值，模板事件处理器无需使用返回值。
// 表单的 @keyup.enter 绑定在同一函数上，键盘提交与点击提交走完全相同的路径。
// 这保证了两种提交方式在校验、loading、错误处理上不会出现分叉。
async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => null)
  if (!valid) {
    shake()
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await playBootAndRedirect()
  } catch {
    shake()
  } finally {
    loading.value = false
  }
}

// 「忘记密码」入口：当前没有自助找回流程，只提示联系管理员。
// 使用 info 类型的 toast，而不是跳转到空页面，避免制造死链。
// 不在这里收集账号或发送重置邮件，是因为管理端账号由运维统一管理，
// 自助重置会扩大攻击面，也不符合当前部署规模。
// 链接使用 href="javascript:void(0)"，仅为保持可聚焦的链接语义。
// 若将来接入找回流程，只需把该函数替换为 router.push('/forgot')。
// toast 文案明确给出下一步动作，减少用户反复尝试。
function onForgot() {
  toast('请联系系统管理员重置密码', { type: 'info' })
}

/* ---------- 生命周期 ---------- */
// 组件存活标志：onMounted 前为 true，onBeforeUnmount 中置 false。
// frame() 与 countTo() 的内层回调都会检查它，
// 防止组件销毁后仍写入响应式状态或继续预约 rAF。
// 它不是 ref，因为不需要驱动视图，只是一个普通的布尔哨兵。
// 使用模块作用域变量而不是全局变量，保证每次组件实例独立。
// 置 false 后不会复位；重新进入页面会创建新的组件实例和新变量。
let alive = true
// 当前雷达主循环的 requestAnimationFrame 句柄。
// 卸载时通过 cancelAnimationFrame(rafId) 取消最后一次预约。
// 初值 0 是 cancelAnimationFrame 可安全接受的无操作句柄。
let rafId = 0
// 每秒刷新时钟的 setInterval 句柄。
// 使用 window.setInterval 的返回类型（number），而不是 NodeJS.Timeout，
// 避免在浏览器与测试环境的类型定义之间产生冲突。
// 初值 undefined，卸载时先判空再 clearInterval。
let clockTimer: number | undefined
// 每 2.6 秒刷新模拟延迟的 setInterval 句柄。
// 与 clockTimer 分开保存，卸载时分别清理，互不影响。
// 2.6 秒的间隔让读数看起来在波动，但不会频繁到分散注意力。
// 随机范围 18..31 毫秒，与自检日志中的 23ms 保持同一量级。
let pingTimer: number | undefined
// 雷达容器的 ResizeObserver 句柄；不支持该 API 时为 undefined。
// 卸载时调用 disconnect()，停止观察并释放内部引用。
// 与 window resize 监听二选一，不会同时存在。
let ro: ResizeObserver | undefined

// 组件挂载后的初始化，按以下顺序执行：
//   1. bindSoundToggle 绑定右上角音效按钮；
//   2. sizeRadar 计算首帧雷达尺寸；
//   3. 优先使用 ResizeObserver 监听容器尺寸，否则退化为 window resize；
//   4. 立即刷新一次时钟，再启动每秒定时器；
//   5. 启动每 2.6 秒的模拟延迟定时器；
//   6. 非 reducedMotion 时启动雷达 rAF 与遥测数字动画，
//      否则直接把遥测写成终值。
// 所有注册的资源都在 onBeforeUnmount 中对称清理。
// bindSoundToggle 接收真实 DOM 元素，因此必须在 mounted 之后调用；
// 元素不存在时由工具函数内部容错，不会阻断后续初始化。
// sizeRadar 先手动调用一次，避免等待 ResizeObserver 首帧回调期间雷达尺寸为占位值。
// ResizeObserver 的观察目标是 .cl-radar-wrap，而不是 .radar-stage，
// 因为后者的尺寸正是由 sizeRadar 写入的，观察它会形成自反馈循环。
// 回退分支的 window resize 监听在卸载时会被 removeEventListener 移除；
// 即使当前环境不支持 ResizeObserver，也不会留下悬挂监听。
// tickClock 立即调用一次，保证底部状态条在首帧就有可读时间，
// 否则会先显示空字符串，布局出现一次轻微跳动。
// clockTimer 每秒触发一次，足够展示到秒；更高频率没有视觉收益。
// pingTimer 的随机值只影响状态条数字，不参与任何业务决策。
// 非 reducedMotion 分支中：
//   - requestAnimationFrame(frame) 启动雷达扫描与接触点高亮；
//   - startCounts() 启动四个遥测数字的错峰缓动。
// reducedMotion 分支直接把 tm 的四个字段写成终值，
// 因为 countTo 不会运行，若不写入就会一直显示 0。
// 这里不启动 frame()，扫描角保持 0，波瓣静止，接触点也不会被命中点亮。
// 音效按钮的初始状态由 bindSoundToggle 根据本地存储恢复，
// 本组件不参与音效偏好的持久化。
// onMounted 内部没有 await，所有初始化都是同步注册，挂载流程不会被阻塞。
// 若将来需要在挂载时请求数据，应使用独立的异步函数并处理 alive 竞态。
// 初始化顺序刻意把 DOM 相关操作放在最前，定时器放在最后，
// 这样即使某个定时器配置异常，也不会影响雷达尺寸与音效绑定。
// 所有 window API 都通过 window.setInterval 显式调用，
// 返回 number 类型，便于与 clearInterval 的参数类型匹配。
// ResizeObserver 只在 radarWrap 存在时创建，避免观察 null。
// 这里没有监听页面可见性变化；登录页通常短暂停留，rAF 在后台标签页会被浏览器自动节流。
// 如果页面长时间隐藏，扫描角会停止推进，恢复可见后继续，不需要额外处理。
// reducedMotion 在模块初始化时求值，onMounted 直接读取该常量，不再重复查询媒体查询。
// 时钟与延迟定时器在 reducedMotion 下仍然运行，因为它们展示的是状态信息而非装饰动画。
// 挂载阶段不调用 formRef，表单校验只在提交时触发。
// 这段初始化的副作用范围仅限当前组件，不会修改全局 store 之外的共享状态。
// 所有资源句柄都保存在 setup 作用域的变量中，卸载时可直接访问。
// 如果组件被 keep-alive 缓存，onBeforeUnmount 不会触发，
// 但当前登录页不在 keep-alive 列表中，离开即销毁。
onMounted(() => {
  bindSoundToggle(document.getElementById('soundToggle'))
  sizeRadar()
  if ('ResizeObserver' in window && radarWrap.value) {
    ro = new ResizeObserver(sizeRadar)
    ro.observe(radarWrap.value)
  } else {
    window.addEventListener('resize', sizeRadar)
  }
  tickClock()
  clockTimer = window.setInterval(tickClock, 1000)
  pingTimer = window.setInterval(() => {
    ping.value = 18 + Math.floor(Math.random() * 14)
  }, 2600)
  if (!reducedMotion) {
    rafId = requestAnimationFrame(frame)
    startCounts()
  } else {
    tm.online = 8
    tm.ready = 5
    tm.out = 2
    tm.service = 1
  }
})

// 组件卸载前的集中清理，与 onMounted 的注册顺序一一对应：
//   1. alive=false，让 frame/countTo 的内层回调在下一帧直接退出；
//   2. cancelAnimationFrame(rafId)，取消已预约但尚未执行的雷达帧；
//   3. clearInterval(clockTimer)，停止每秒时钟；
//   4. clearInterval(pingTimer)，停止模拟延迟刷新；
//   5. ro?.disconnect()，停止 ResizeObserver；
//   6. removeEventListener('resize', sizeRadar)，移除回退监听。
// 即使当前环境走的是 ResizeObserver 分支，移除一个从未添加的 resize 监听
// 也是安全的无操作，因此不需要额外记录分支状态。
// alive 必须最先置 false，避免清理过程中刚好有一帧回调排队执行，
// 又在 cancelAnimationFrame 之前写入了状态。
// rafId 可能为 0（reducedMotion 分支从未启动 rAF），
// cancelAnimationFrame(0) 是合法的无操作。
// clockTimer/pingTimer 使用 if 判空，因为 setInterval 尚未执行时它们是 undefined。
// ro 使用可选链，兼容不支持 ResizeObserver 的环境。
// removeEventListener 即使在回退分支没有添加过监听也不会抛错，
// 因为移除不存在的监听器在 DOM 规范中是静默忽略。
// 这些清理保证用户反复登录/退出时不会叠加计时器，
// 否则每次进入页面都会多一个时钟和延迟刷新，造成内存与 CPU 泄漏。
// 组件卸载通常发生在 router.push 之后，因此清理顺序与转场结束自然衔接。
// 这里不重置表单或 store，因为页面即将销毁，状态由下一次挂载重新创建。
// 音效按钮的事件监听由 bindSoundToggle 内部管理，若它使用全局委托则无需在此处理；
// 若未来改为元素级监听，应在此补充解除逻辑。
// 该钩子只做资源回收，不触发导航或网络请求，保持卸载路径轻量。
// 所有清理操作都是幂等的，即使重复调用也不会产生副作用。
// 通过集中清理，本页面的副作用边界清晰：进入时注册、离开时全部撤销。
onBeforeUnmount(() => {
  alive = false
  cancelAnimationFrame(rafId)
  if (clockTimer) clearInterval(clockTimer)
  if (pingTimer) clearInterval(pingTimer)
  ro?.disconnect()
  window.removeEventListener('resize', sizeRadar)
})
</script>

<!-- 模板结构总览： -->
<!-- 1. .cl-left 左侧雷达区：HUD、雷达舞台、图例、遥测、竖向侧标； -->
<!-- 2. .cl-right 右侧登录区：背景光斑、音效按钮、登录卡片； -->
<!-- 3. .cl-status 底部状态条：系统状态、节点、延迟、时钟； -->
<!-- 4. .boot / .wipe 登录成功后的自检遮罩与横向擦除。 -->
<!-- 雷达 SVG 的 viewBox 固定为 0 0 120 120，所有坐标来自脚本中的极坐标计算； -->
<!-- 模板只负责绑定数值，不在模板里做几何运算。 -->
<!-- 每个区块前都有独立注释，说明其职责与依赖的响应式状态。 -->
<!-- 模板不包含任何业务请求，所有交互最终都汇聚到 handleLogin。 -->
<!-- 样式类名以 cl- 为前缀，降低与全局样式的冲突概率。 -->
<!-- 装饰性 SVG 统一设置 aria-hidden，避免干扰屏幕阅读器。 -->
<!-- 表单控件保留原生可访问性，Element Plus 负责标签与错误关联。 -->
<template>
<!-- 根容器 .cl：固定暗色、左右 56/44 栅格、占满视口； -->
<!-- 所有子区域都相对它定位，底部状态条和自检遮罩是它的直接子节点。 -->
<!-- 根容器不随全局主题切换，变量在 CSS 中集中定义。 -->
<!-- 这里不绑定任何键盘事件，Enter 提交由内部 el-form 处理。 -->
  <div class="cl">
<!-- 左栏同时承担品牌氛围与系统状态展示，雷达本身是纯装饰性演示。 -->
<!-- 它不接收真实工具数据，也不会因为鼠标操作发起网络请求。 -->
<!-- 左栏在窄屏下会折叠到登录卡片上方，见 CSS 响应式分区。 -->
    <!-- 左侧 · 雷达星图 -->
<!-- .cl-left：左侧雷达栏，纵向 flex 布局。 -->
<!-- 内部从上到下依次是 HUD、雷达舞台、图例、遥测和竖排侧标。 -->
<!-- 背景网格与光晕是绝对定位的装饰层，不参与布局。 -->
<!-- 该区域的所有数字都来自脚本中的 ref/reactive。 -->
    <div class="cl-left">
<!-- 背景网格：CSS 绘制的 40px 方格，用径向遮罩淡出边缘。 -->
<!-- 纯装饰层，不包含文本，也不进入可访问性树。 -->
<!-- 网格位于最底层，后续内容通过 z-index 覆盖。 -->
<!-- 光晕层：铜金色径向渐变 + blur，带 9s 呼吸动画。 -->
<!-- 两个装饰层都 pointer-events: none，不拦截雷达鼠标事件。 -->
<!-- 它们的动画在 reduced-motion 下会被 CSS 关闭。 -->
      <div class="cl-grid"></div>
      <div class="cl-glow"></div>
<!-- HUD 顶栏：雷达的操作读数。 -->
<!-- hud-tag：LIVE SWEEP 状态灯，带脉冲动画。 -->
<!-- BRG：当前扫描方位角，pad3 格式化为三位度数。 -->
<!-- grow：弹性占位，把后半段读数推到右侧。 -->
<!-- CONTACTS：接触点总数，取 blips.length 而不是硬编码 8。 -->
<!-- RNG：鼠标距离读数，由 onRadarMove 写入，离开时显示空值占位。 -->
<!-- 所有读数使用等宽字体与大字距，模拟仪表盘。 -->
<!-- HUD 不接收点击，纯展示；z-index 高于雷达但低于悬浮提示。 -->
<!-- BRG 在 reducedMotion 下保持 000，因为扫描未启动。 -->
<!-- CONTACTS 使用 pad2，最多支持 99 个接触点。 -->
<!-- RNG 的空值占位表示指针不在雷达圆内或尚未进入。 -->
<!-- HUD 在移动端会减小间距，保证一行放得下。 -->
<!-- 这里的角度是逻辑方位角，不是屏幕旋转角。 -->
<!-- 所有插值都经过 pad2/pad3，避免出现 "1" 这样的短读数。 -->
      <div class="cl-hud cl-hud-top">
        <span class="hud-tag"><i class="hud-dot"></i>LIVE SWEEP</span>
        <span class="hud-kv">BRG <b>{{ pad3(sweepDeg) }}</b>°</span>
        <span class="grow"></span>
        <span class="hud-kv">CONTACTS <b>{{ pad2(blips.length) }}</b></span>
        <span class="hud-kv">RNG <b>{{ mouseRng }}</b></span>
      </div>

<!-- 雷达交互容器：负责接收 pointermove/pointerleave。 -->
<!-- ref="radarWrap" 供 sizeRadar 读取尺寸并作为 ResizeObserver 目标。 -->
<!-- 鼠标事件绑定在容器而不是 SVG 上，保证准星可以覆盖整个舞台。 -->
<!-- .radar-stage 是正方形舞台，宽高由 stageSize 内联设置。 -->
<!-- 它相对定位，便于准星与悬浮提示按百分比绝对定位。 -->
<!-- SVG 使用 120×120 逻辑坐标，preserveAspectRatio 保证等比缩放。 -->
<!-- aria-hidden="true"：雷达是装饰性图形，不向屏幕阅读器暴露内部节点。 -->
<!-- 真正的状态信息在 HUD、遥测和状态条中以文本形式存在。 -->
<!-- 舞台尺寸变化只影响 CSS 宽高，不触发 SVG 坐标重算。 -->
<!-- pointermove 会频繁触发，但处理函数只做常数级计算。 -->
<!-- pointerleave 负责复位准星、距离读数和悬浮提示。 -->
<!-- 触屏设备上 pointermove 只在触摸时触发，行为可接受。 -->
<!-- 图例 .cl-legend 位于容器底部，说明三种状态颜色。 -->
<!-- 雷达舞台与图例之间由 flex 布局留出间距。 -->
<!-- 该容器在窄屏下高度由左栏 flex 分配，最小高度为 0 防止溢出。 -->
<!-- 所有交互状态都是临时态，不会持久化。 -->
      <div ref="radarWrap" class="cl-radar-wrap" @pointermove="onRadarMove" @pointerleave="onRadarLeave">
        <div class="radar-stage" :style="{ width: stageSize + 'px', height: stageSize + 'px' }">
<!-- 雷达主 SVG：所有几何图形都在 120×120 视口中绘制。 -->
<!-- 选择 SVG 而不是 Canvas，是因为接触点需要响应式属性和悬浮提示。 -->
<!-- 扫描旋转通过 <g> 的 transform 完成，不重绘路径。 -->
<!-- 渐变定义在 defs 中，供底盘和中心枢纽引用。 -->
<!-- 图形层级从下到上：底盘 -> 距离圈 -> 刻度 -> 扫描 -> 接触点 -> 枢纽。 -->
<!-- 所有描边宽度都在 0.5~2 之间，保证缩放后仍然细腻。 -->
<!-- 颜色直接使用铜金与状态色的 rgba，不依赖 CSS 变量。 -->
<!-- 该 SVG 不包含可聚焦元素，键盘用户无需操作它。 -->
<!-- 文本元素使用 .rad-deg/.blip-t 类，由 scoped CSS 控制字号。 -->
<!-- 整个图形在 reduced-motion 下保持静态，但内容仍然完整可读。 -->
          <svg class="radar-svg" viewBox="0 0 120 120" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
<!-- 底盘径向渐变：从近黑到透明的圆形底。 -->
<!-- 0% 处不透明度 0.9，72% 处 0.5，100% 完全透明，形成柔和边缘。 -->
<!-- 中心枢纽渐变：铜金核心向外快速衰减到 6% 不透明度。 -->
<!-- 两个渐变都使用 objectBoundingBox 默认单位，随图形缩放。 -->
<!-- 渐变 id 带 cl- 前缀，降低与页面其他 SVG 冲突的概率。 -->
<!-- 底盘让雷达在深色背景上有轻微体积感。 -->
<!-- 枢纽渐变只在半径 5 的圆内可见，形成发光灯芯。 -->
<!-- 这些渐变是静态定义，动画不修改 stop 属性。 -->
<!-- 若将来增加主题，只需替换这两个渐变的 stop-color。 -->
<!-- defs 本身不渲染，位置不影响绘制顺序。 -->
<!-- 渐变引用使用 url(#id)，在 scoped 样式中不会被改写。 -->
<!-- 这里没有使用 filter，避免移动端 SVG 滤镜的性能开销。 -->
            <defs>
              <radialGradient id="cl-radarBase" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stop-color="#1A1D1F" stop-opacity="0.9" />
                <stop offset="72%" stop-color="#141719" stop-opacity="0.5" />
                <stop offset="100%" stop-color="#141719" stop-opacity="0" />
              </radialGradient>
              <radialGradient id="cl-hubCore" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stop-color="#F5C474" stop-opacity="0.9" />
                <stop offset="100%" stop-color="#D89B43" stop-opacity="0.06" />
              </radialGradient>
            </defs>

<!-- 雷达底盘：半径 R 的圆，填充底盘渐变。 -->
<!-- 它是所有距离圈和扫描波瓣的视觉基底。 -->
<!-- 圆心与半径直接绑定脚本常量 CX/CY/R。 -->
<!-- 底盘不描边，边界由最外圈距离圈负责。 -->
<!-- 该圆位于所有图形最底层，后绘制的元素自然覆盖。 -->
<!-- 填充来自 defs 中的 cl-radarBase 渐变。 -->
            <circle :cx="CX" :cy="CY" :r="R" fill="url(#cl-radarBase)" />

<!-- 距离圈使用 R 的 25%/50%/75%/100%，提供距离参考。 -->
<!-- 十字轴贯穿圆心，帮助判断方位。 -->
<!-- 所有线条使用低不透明度铜金，避免喧宾夺主。 -->
            <!-- 距离圈 + 十字轴 -->
<!-- 该 <g> 统一设置 fill="none"，内部圆和线只需写 stroke。 -->
<!-- 三条内圈使用 0.12 透明度，最外圈提高到 0.22 以强调边界。 -->
<!-- 十字轴透明度 0.08，比距离圈更弱，避免形成视觉十字。 -->
<!-- 线宽 0.5 逻辑单位，在 320px 舞台上约 1.3px。 -->
<!-- 距离圈不做动画，保持稳定的参考网格。 -->
<!-- 最外圈同时是鼠标距离归一化的边界（rng <= 1）。 -->
<!-- 这些圆和线没有 pointer-events，交互由父容器统一处理。 -->
<!-- 如果 R 调整，所有圈会等比缩放，无需改模板。 -->
            <g fill="none">
              <circle :cx="CX" :cy="CY" :r="R * 0.25" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R * 0.5" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R * 0.75" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R" stroke="rgba(216,155,67,.22)" stroke-width="0.5" />
              <line :x1="CX - R" :y1="CY" :x2="CX + R" :y2="CY" stroke="rgba(216,155,67,.08)" stroke-width="0.5" />
              <line :x1="CX" :y1="CY - R" :x2="CX" :y2="CY + R" stroke="rgba(216,155,67,.08)" stroke-width="0.5" />
            </g>

            <!-- 方位刻度 + 度数 -->
<!-- 方位刻度组：24 根短线 + 12 个主刻度度数。 -->
<!-- v-for 遍历 ticks，key 使用角度 d，天然唯一。 -->
<!-- 主刻度（30° 整数倍）使用更亮的描边，次刻度更暗。 -->
<!-- 刻度从 R-(4|2) 画到 R，主刻度更长。 -->
<!-- 度数文字只渲染 major 项，减少文本节点。 -->
<!-- text-anchor="middle" 与 dominant-baseline="middle" 让文字以 lx/ly 为中心。 -->
<!-- pad3(t.d) 保证读数始终三位，例如 000/030/060。 -->
<!-- 度数位于 R+7，落在最外圈之外，不与刻度线重叠。 -->
<!-- .rad-deg 类控制字体大小与颜色。 -->
<!-- 刻度是静态的，不随扫描角旋转。 -->
<!-- 0° 位于正上方，与 polar 的约定一致。 -->
<!-- 所有刻度线共用一个 <g>，便于将来整体调整透明度。 -->
<!-- 主刻度 12 根覆盖 0/30/.../330。 -->
<!-- 次刻度 12 根位于主刻度之间，覆盖 15/45/.../345。 -->
<!-- 刻度线不参与命中检测，鼠标吸附只针对接触点。 -->
<!-- 该组位于扫描波瓣之下，扫描经过时会暂时覆盖刻度。 -->
            <g>
              <line
                v-for="t in ticks"
                :key="'tick-' + t.d"
                :x1="t.x1"
                :y1="t.y1"
                :x2="t.x2"
                :y2="t.y2"
                :stroke="t.major ? 'rgba(201,198,188,.4)' : 'rgba(201,198,188,.18)'"
                stroke-width="0.5"
              />
              <text
                v-for="t in ticks.filter((x) => x.major)"
                :key="'deg-' + t.d"
                class="rad-deg"
                :x="t.lx"
                :y="t.ly"
                text-anchor="middle"
                dominant-baseline="middle"
              >
                {{ pad3(t.d) }}
              </text>
            </g>

<!-- 扫描波瓣组：通过 rotate(sweepDeg 60 60) 绕圆心旋转。 -->
<!-- 旋转中心写死 60/60，与 CX/CY 一致；若圆心变化需同步修改。 -->
<!-- 三个 path 由 sweepWedges 预计算，fill 固定铜金，透明度各不相同。 -->
<!-- 最亮的前沿紧贴扫描线，后方两层形成余辉拖尾。 -->
<!-- 两条扫描线：宽 2 的暗线做光晕，宽 0.7 的亮线做芯。 -->
<!-- 两条线共用 leadPoint（0° 方向的 R 端点），旋转后指向当前方位。 -->
<!-- 波瓣使用 fill-opacity 而不是 opacity，避免影响同组线条。 -->
<!-- 该组的 transform 每帧由 Vue 更新，浏览器只做合成层变换。 -->
<!-- reducedMotion 下 sweepDeg 不变，波瓣静止但仍可见。 -->
<!-- 扫描方向为顺时针，与方位角递增方向一致。 -->
<!-- 波瓣路径使用 A 命令画圆弧，半径与底盘一致，保证贴合最外圈。 -->
<!-- 三层透明度 0.30/0.16/0.08 形成自然的亮度梯度。 -->
<!-- 旋转不会改变路径本身，因此没有每帧重算几何的开销。 -->
<!-- 扫描线长度等于 R，不超出雷达范围。 -->
<!-- 该组位于接触点之下，接触点始终显示在扫描光之上。 -->
<!-- 若将来需要多扫描线，只需复制该组并给不同初始角度。 -->
            <!-- 扫描波瓣（旋转余辉） -->
            <g :transform="`rotate(${sweepDeg} 60 60)`">
              <path v-for="(w, i) in sweepWedges" :key="'wedge-' + i" :d="w.d" fill="#D89B43" :fill-opacity="w.op" />
              <line :x1="CX" :y1="CY" :x2="leadPoint.x" :y2="leadPoint.y" stroke="rgba(216,155,67,.28)" stroke-width="2" />
              <line :x1="CX" :y1="CY" :x2="leadPoint.x" :y2="leadPoint.y" stroke="rgba(240,190,110,.9)" stroke-width="0.7" />
            </g>

<!-- 工具接触点：每个 blip 渲染扩散环、本体圆和编号文字。 -->
<!-- v-for 的 key 使用 b.id，与 TOOLS 的稳定编号一致。 -->
<!-- 扩散环：仅当 ping < 1 时渲染。 -->
<!-- 半径从 3.5 增长到 23.5，描边颜色取状态色。 -->
<!-- 环的透明度 (1-ping)*0.6 随扩散逐渐消失。 -->
<!-- 本体圆：半径 2.8，填充不透明度 0.3 + 0.7*glow。 -->
<!-- glow 由扫描命中置 1、逐帧衰减，形成被扫过后的余亮。 -->
<!-- 本体描边使用同一状态色，透明度 0.5。 -->
<!-- 编号文字 T-01..T-08 放在接触点右上方，使用 pad2 补零。 -->
<!-- 状态颜色由 statusColor 统一映射，未知状态回落铜金。 -->
<!-- 扩散环与本体圆都不响应鼠标事件，吸附由父容器计算。 -->
<!-- 接触点坐标是固定逻辑坐标，只有 glow/ping 参与动画。 -->
<!-- 所有接触点共用同一套渲染结构，数量由 blips 长度决定。 -->
<!-- 扫描线扫过时先触发扩散环（ping=0），再逐渐回落。 -->
<!-- 本体高亮持续时间约 1 秒，足以让用户注意到命中。 -->
<!-- 编号文字不随 glow 变化，保持稳定可读。 -->
<!-- 接触点位于扫描波瓣之上，避免被旋转光效遮住。 -->
<!-- 状态色与底部图例、遥测数字保持一致。 -->
<!-- 8 个接触点的分布由固定公式生成，刷新后位置不变。 -->
<!-- 悬浮提示只在鼠标接近时显示，不依赖点击。 -->
<!-- 扩散环的半径上限 23.5 不会超出雷达底盘太多。 -->
<!-- glow/ping 的更新发生在 frame() 中，模板只负责绑定。 -->
<!-- 若将来接触点超过 99 个，pad2 会输出三位数，需要调整标签布局。 -->
<!-- 该组不包含任何交互元素，屏幕阅读器无需读取。 -->
            <!-- 工具接触点 -->
            <g v-for="b in blips" :key="'blip-' + b.id">
              <circle
                v-if="b.ping < 1"
                :cx="b.x"
                :cy="b.y"
                :r="3.5 + b.ping * 20"
                fill="none"
                :stroke="statusColor(b.status)"
                :stroke-opacity="(1 - b.ping) * 0.6"
                stroke-width="0.6"
              />
              <circle
                :cx="b.x"
                :cy="b.y"
                :r="2.8"
                :fill="statusColor(b.status)"
                :fill-opacity="0.3 + 0.7 * b.glow"
                :stroke="statusColor(b.status)"
                stroke-opacity="0.5"
                stroke-width="0.5"
              />
              <text class="blip-t" :x="b.x + 4" :y="b.y - 3">T-{{ pad2(b.id) }}</text>
            </g>

<!-- 中心枢纽：正六边形底座 + 两层发光圆。 -->
<!-- hubPath 由 6 个 60° 等分点生成，填充近黑、描边铜金。 -->
<!-- 半径 5 的圆使用 cl-hubCore 渐变，形成铜金光晕。 -->
<!-- 半径 1.6 的实心圆是最亮的核心。 -->
<!-- 枢纽绘制在接触点之后，因此始终位于最上层。 -->
<!-- 它代表「本站」，不参与命中检测。 -->
<!-- 六边形的机械感与方位刻度形成统一的仪表语言。 -->
<!-- 这些图形都是静态的，reducedMotion 下无需额外处理。 -->
            <!-- 中心枢纽 -->
            <path :d="hubPath" fill="rgba(23,26,28,.95)" stroke="rgba(216,155,67,.6)" stroke-width="0.7" />
            <circle :cx="CX" :cy="CY" r="5" fill="url(#cl-hubCore)" />
            <circle :cx="CX" :cy="CY" r="1.6" fill="#F5C474" />
          </svg>

<!-- 鼠标准星：四角括号 + 距离读数。 -->
<!-- class on 控制可见性，left/top 使用百分比跟随指针。 -->
<!-- 四个 i 元素分别绘制左上、右上、左下、右下角。 -->
<!-- em 中显示与 HUD 相同的 RNG 读数。 -->
<!-- 准星是绝对定位元素，不占用 SVG 布局。 -->
<!-- transform 由 CSS 负责居中，模板只写坐标。 -->
<!-- 指针离开雷达时 reticle.on 置 false，准星消失。 -->
<!-- 准星不拦截指针事件，避免影响 pointermove 的连续触发。 -->
<!-- 百分比定位保证窗口缩放时准星仍然贴合指针位置。 -->
<!-- 该元素在 reducedMotion 下仍然可用，因为它不是动画。 -->
          <div class="cl-reticle" :class="{ on: reticle.on }" :style="{ left: reticle.x + '%', top: reticle.y + '%' }">
            <i class="rt tl"></i><i class="rt tr"></i><i class="rt bl"></i><i class="rt br"></i>
            <em>RNG {{ mouseRng }}</em>
          </div>

<!-- 悬浮提示：仅当 hoverBlip 非空时渲染。 -->
<!-- left/top 把逻辑坐标（0..120）换算成百分比（除以 1.2）。 -->
<!-- 换算写在这里而不是脚本中，保持坐标数据与展示解耦。 -->
<!-- 第一行显示工具名称，第二行显示状态文案与资产编码。 -->
<!-- 状态文案的颜色与接触点状态色一致。 -->
<!-- 提示框 pointer-events: none，不会截获鼠标导致闪烁。 -->
<!-- 提示内容来自 TOOLS 种子数据，不发起请求。 -->
<!-- 鼠标移开后 onRadarLeave 置空 hoverBlip，提示立即消失。 -->
          <div v-if="hoverBlip" class="radar-tip" :style="{ left: (hoverBlip.x / 1.2) + '%', top: (hoverBlip.y / 1.2) + '%' }">
            <b>{{ hoverBlip.name }}</b>
            <span :style="{ color: statusColor(hoverBlip.status) }">{{ statusText(hoverBlip.status) }} · {{ hoverBlip.code }}</span>
          </div>
        </div>

<!-- 雷达图例：三种状态颜色的圆点 + 中文说明。 -->
<!-- 颜色写死为与 STATUS_COLOR 一致的值，避免图例与数据脱节。 -->
<!-- 图例绝对定位在雷达容器左下角。 -->
<!-- 它是静态说明，不随接触点动画变化。 -->
<!-- 三个颜色同时出现在遥测数字和状态条中，形成统一语义。 -->
<!-- 图例在窄屏下仍然保留，帮助理解雷达点颜色。 -->
<!-- 圆点尺寸 8px，与 HUD 状态灯尺寸接近。 -->
<!-- 图例不参与布局计算，不会挤压雷达舞台。 -->
        <div class="cl-legend">
          <span><i style="background: #3bb273"></i>可借</span>
          <span><i style="background: #e0a33c"></i>租出</span>
          <span><i style="background: #e56a5e"></i>维修</span>
        </div>
      </div>

<!-- 遥测面板：四个数字卡片，横向等分。 -->
<!-- 每张卡片包含：中文名、数值、英文单位。 -->
<!-- online 使用默认墨色，ready/out/service 分别使用绿/琥珀/红。 -->
<!-- 数字来自 tm，挂载后由 countTo 缓动到目标值。 -->
<!-- .num 类启用等宽数字，避免滚动时宽度跳动。 -->
<!-- 四张卡片的顺序与雷达图例、自检日志保持一致。 -->
<!-- 目标值 8/5/2/1 与 TOOLS 的状态分布对应。 -->
<!-- reducedMotion 下数字直接写终值，不播放缓动。 -->
<!-- 单位使用英文 UNITS/READY/OUT/SERVICE，增强仪表感。 -->
<!-- 卡片使用半透明背景与 backdrop-filter，浮在光晕之上。 -->
<!-- 数字字号较大，是左栏除雷达外的主要视觉焦点。 -->
<!-- 该区域不接收点击，纯展示。 -->
<!-- 遥测数字与底部状态条的延迟、时钟共同构成运行态信息。 -->
<!-- 在 600px 以下，四列会变成两列，见响应式分区。 -->
<!-- 卡片间距 10px，保证窄屏下仍可读。 -->
<!-- 所有数值都是整数，不需要格式化小数。 -->
      <div class="cl-telemetry">
        <div class="tm"><span class="tm-k">工具在线</span><b class="tm-v num">{{ tm.online }}</b><span class="tm-u">UNITS</span></div>
        <div class="tm"><span class="tm-k">可借用</span><b class="tm-v num ok">{{ tm.ready }}</b><span class="tm-u">READY</span></div>
        <div class="tm"><span class="tm-k">已租出</span><b class="tm-v num warn">{{ tm.out }}</b><span class="tm-u">OUT</span></div>
        <div class="tm"><span class="tm-k">维修中</span><b class="tm-v num bad">{{ tm.service }}</b><span class="tm-u">SERVICE</span></div>
      </div>
<!-- 竖排侧标：沿左栏左缘垂直书写的构建信息。 -->
<!-- writing-mode: vertical-rl + rotate(180deg) 让文字从下往上读。 -->
<!-- 纯装饰，低透明度，不参与交互。 -->
<!-- 在 1023px 以下隐藏，避免占用横向空间。 -->
      <div class="cl-rail"><span>TOOLSHARE OPERATIONS · RADAR STARMAP · BUILD 2.4.0</span></div>
    </div>

<!-- 右栏负责真正的登录业务：品牌、表单、演示账号和返回居民端入口。 -->
<!-- 背景光斑与音效按钮是装饰/辅助元素，表单卡片才是交互核心。 -->
<!-- 所有表单状态都绑定到脚本中的 form/formRef/loading。 -->
    <!-- 右侧 · 登录控制台 -->
<!-- .cl-right：右侧登录区，flex 居中卡片。 -->
<!-- overflow: hidden 裁掉背景光斑的溢出部分。 -->
<!-- 内边距在底部留出 64px，避免卡片被状态条遮挡。 -->
<!-- 背景是独立的径向渐变，与左栏形成冷暖对比。 -->
<!-- 该区域在窄屏下移到左栏下方。 -->
    <div class="cl-right">
<!-- 背景光斑容器：两个模糊的径向渐变圆，缓慢漂移。 -->
<!-- 纯装饰层，pointer-events: none。 -->
<!-- 使用 blur(70px) 让边缘完全柔化。 -->
<!-- 动画在 reduced-motion 下关闭。 -->
      <div class="cl-mesh"><i></i><i></i></div>
<!-- 音效开关按钮：由 bindSoundToggle 接管点击与激活态。 -->
<!-- id="soundToggle" 是工具函数查找元素的锚点。 -->
<!-- aria-label 提供可访问名称，图标由 CSS/工具函数绘制。 -->
<!-- 绝对定位在右上角，z-index 高于卡片。 -->
<!-- 按钮样式在 .cl-sound 中重绘为暗色玻璃态。 -->
<!-- 它不影响登录流程，只是可选的音效偏好。 -->
      <button class="icon-btn cl-sound" id="soundToggle" aria-label="音效开关"></button>

<!-- 登录卡片：承载品牌、标题、表单和演示账号。 -->
<!-- shake 类由登录失败触发，播放 0.4s 横向抖动。 -->
<!-- 卡片本身带入场动画 clIn，与抖动动画互不冲突。 -->
<!-- 宽度上限 420px，保证大屏下仍聚焦。 -->
      <div class="cl-card" :class="{ shake: shaking }">
<!-- 品牌区：BrandMark 图形 + 产品名 + 控制台副标题。 -->
<!-- BrandMark 的 size=46 与文字高度视觉对齐。 -->
<!-- BrandMark 使用的品牌色变量在 CSS 中覆盖，使标志融入铜金主题。 -->
<!-- 产品名使用较大的字重，副标题使用小字号大字距。 -->
<!-- 副标题中的 OPERATIONS 与 HUD、页眉保持一致。 -->
<!-- 该区块不接收点击，不是链接。 -->
<!-- 图形与文字使用 flex 横向排列，间距 13px。 -->
<!-- 品牌区下方留 24px，与表单拉开层次。 -->
        <div class="cl-brand">
          <BrandMark :size="46" />
          <div>
            <b>ToolShare</b>
            <span>管理控制台 · OPERATIONS</span>
          </div>
        </div>

<!-- 页眉小标签：盾牌图标 + OPERATIONS CONSOLE。 -->
<!-- 使用内联 SVG 而不是图标组件，减少一个依赖。 -->
<!-- 等宽字体与大字距强化「控制台」气质。 -->
<!-- 颜色使用青绿色，与 HUD 的 LIVE SWEEP 呼应。 -->
<!-- 图标 aria-hidden，文字本身已说明含义。 -->
<!-- 该标签是纯展示，不参与交互。 -->
        <p class="cl-eyebrow">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /><path d="M12 8v4M12 16h.01" />
          </svg>
          <span>OPERATIONS CONSOLE</span>
        </p>
<!-- 主标题「指挥中枢」：渐变色文字，是整个右栏的视觉焦点。 -->
<!-- 背景渐变从白色过渡到浅铜金，通过 background-clip: text 实现。 -->
<!-- 副标题说明页面用途：调度工具、租赁与报修。 -->
<!-- 文案中的「社区工具房」与左侧自检日志的身份信息一致。 -->
<!-- 标题与副标题之间的间距由 CSS 控制，模板不写内联样式。 -->
<!-- 副标题使用次级墨色，保证在暗色背景上的可读性。 -->
<!-- 标题保持 h1 语义，便于辅助技术识别页面主题。 -->
<!-- 这两段文字是静态的，不绑定任何状态。 -->
        <h1 class="cl-title">指挥中枢</h1>
        <p class="cl-sub">社区工具房的运营雷达已就绪。登录以调度工具、租赁与报修。</p>

<!-- Element Plus 登录表单：model 绑定 form，rules 绑定必填规则。 -->
<!-- label-position="top" 让标签位于输入框上方，适合窄卡片。 -->
<!-- hide-required-asterisk 关闭星号，但 required 校验仍然生效。 -->
<!-- @keyup.enter 让键盘提交与点击按钮走同一个 handleLogin。 -->
<!-- ref="formRef" 供 handleLogin 调用 validate()。 -->
<!-- 按钮是 type="button"，不会触发原生 submit，因此无需 @submit.prevent。 -->
<!-- 两个 el-form-item 的 prop 必须与 rules 的键一致，否则校验不会绑定。 -->
<!-- 校验失败时 Element Plus 在对应 item 下渲染错误文案。 -->
<!-- 表单区域不包含 loading 遮罩，loading 由按钮自身展示。 -->
<!-- 输入框的 autocomplete 分别设为 username/current-password， -->
<!-- 便于浏览器密码管理器正确识别。 -->
<!-- 提交期间按钮 disabled，但 Enter 仍可能再次触发 handleLogin； -->
<!-- 当前未在函数入口用 loading 提前 return，属于已知的轻量取舍。 -->
<!-- 表单值只存在于内存，登录成功后由 auth store 持久化会话。 -->
<!-- 错误文案与输入框错误态由 Element Plus 管理，CSS 只负责暗色重绘。 -->
<!-- 该表单不包含验证码字段，认证方式由 auth.login 内部决定。 -->
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" hide-required-asterisk @keyup.enter="handleLogin">
<!-- 账号项：prop="username" 与 rules.username 对应。 -->
<!-- v-model 双向绑定 form.username。 -->
<!-- placeholder 说明输入内容，不是校验文案。 -->
<!-- autocomplete="username" 帮助密码管理器填充。 -->
<!-- prefix-icon 使用 Element Plus 的 User 图标。 -->
<!-- 失焦时触发 required 校验，错误显示在下方。 -->
<!-- 输入框高度与密码框一致，均为 42px。 -->
<!-- 暗色样式通过 :deep(.el-input__wrapper) 重绘。 -->
<!-- 该字段没有长度或字符集限制，交给后端策略。 -->
<!-- 初始值为空字符串，首次渲染显示 placeholder。 -->
          <el-form-item prop="username" label="账号">
            <el-input
              v-model="form.username"
              placeholder="请输入管理员账号"
              autocomplete="username"
              :prefix-icon="User"
            />
          </el-form-item>
<!-- 密码项：prop="password" 与 rules.password 对应。 -->
<!-- type="password" 让输入内容以圆点显示。 -->
<!-- show-password 提供可见性切换按钮，便于核对输入。 -->
<!-- autocomplete="current-password" 提示密码管理器这是登录密码。 -->
<!-- prefix-icon 使用 Lock 图标。 -->
<!-- 与账号一样在 blur 时触发必填校验。 -->
<!-- 密码不会写入 URL、日志或 localStorage。 -->
<!-- 提交时以明文传给 auth.login，由其在传输层使用 HTTPS。 -->
<!-- 暗色样式与其他输入框共享同一套 :deep 规则。 -->
<!-- 登录失败后保留已输入的密码，方便用户重试。 -->
          <el-form-item prop="password" label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
              :prefix-icon="Lock"
            />
          </el-form-item>

<!-- 表单辅助行：记住设备复选框 + 忘记密码链接。 -->
<!-- 复选框当前仅视觉展示，checked 是静态属性，未绑定状态。 -->
<!-- 也就是说「记住此设备」暂不改变 auth 的持久化策略。 -->
<!-- accent-color 把原生复选框染成铜金色。 -->
<!-- 忘记密码使用链接语义，但点击只弹出 toast。 -->
<!-- href="javascript:void(0)" 避免页面跳转，同时保持可聚焦。 -->
<!-- 该行使用 space-between 把两个元素分列两端。 -->
<!-- 字号使用与表单标签相同的小号辅助字。 -->
<!-- 链接 hover 时变为铜金色，提供可点击反馈。 -->
<!-- 该行下方留 22px，与提交按钮拉开距离。 -->
          <div class="cl-row">
            <label class="ck"><input type="checkbox" checked /><span>记住此设备</span></label>
            <a href="javascript:void(0)" @click="onForgot">忘记密码？</a>
          </div>

<!-- 提交按钮：点击调用 handleLogin，Enter 由表单统一处理。 -->
<!-- type="button" 避免触发表单原生提交与页面刷新。 -->
<!-- 类 btn/btn-primary/btn-lg/btn-block 来自全局样式， -->
<!-- magnetic 提供指针吸附的磁力效果。 -->
<!-- class loading 由 loading 状态驱动，展示按钮的加载态。 -->
<!-- :disabled="loading" 阻止转场期间再次点击。 -->
<!-- 文案「启动控制台」与页面「指挥中枢」的叙事一致。 -->
<!-- 右侧 chevron SVG 指示「进入」方向。 -->
<!-- 按钮背景是铜金渐变，hover 时提亮 8%。 -->
<!-- loading 期间按钮保持原有尺寸，不会因文案变化抖动。 -->
<!-- 该按钮不使用 el-button，而是原生 button + 全局按钮类， -->
<!-- 便于完全控制渐变、阴影和磁力效果。 -->
          <button
            type="button"
            class="btn btn-primary btn-lg btn-block magnetic"
            :class="{ loading }"
            :disabled="loading"
            @click="handleLogin"
          >
            <span>启动控制台</span>
            <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="m9 18 6-6-6-6" />
            </svg>
          </button>
        </el-form>

<!-- 演示账号提示：展示 admin / Aa123456 与一键填入按钮。 -->
<!-- 账号和密码以纯文本展示，仅用于开发/验收环境。 -->
<!-- 生产部署应通过配置隐藏该区块，避免公开默认凭证。 -->
<!-- 一键填入调用 fillDemo，只写表单不自动提交。 -->
<!-- 左侧信息图标说明这是提示而不是错误。 -->
<!-- link-btn 是轻量文字按钮，样式与普通链接不同。 -->
<!-- 该区块不参与表单校验，也不影响 auth.login。 -->
<!-- 文案与 fillDemo 中的 toast 保持一致的下一步指引。 -->
<!-- 演示凭证是静态的，不随环境变量变化。 -->
<!-- 若修改演示密码，需要同时更新这里和 fillDemo。 -->
        <div class="cl-demo">
          <span><svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/></svg>演示账号</span>
          <b>admin</b>
          <span>密码</span>
          <b>Aa123456</b>
          <button type="button" class="link-btn" @click="fillDemo">一键填入</button>
        </div>
<!-- 返回居民端登录：使用绝对地址 http://localhost:5901/login。 -->
<!-- 该地址是本地开发环境的居民端端口，生产部署需替换。 -->
<!-- 左侧箭头图标表示返回方向。 -->
<!-- 链接位于卡片底部，与演示账号区块分隔。 -->
<!-- 使用普通 a 标签而不是 router-link，因为跨端跳转不在同一 SPA。 -->
<!-- 该链接不做 redirect 处理，也不携带当前页面状态。 -->
        <a class="cl-back" href="http://localhost:5901/login">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
          返回居民端登录
        </a>
      </div>
    </div>

<!-- 底部状态条：覆盖在页面底部，z-index 高于左右两栏。 -->
<!-- 第一段：绿色脉冲点 + 系统运行正常。 -->
<!-- 第二段：节点标识 cn-east-1，与自检日志中的区域一致。 -->
<!-- 第三段：模拟延迟读数，来自 ping ref，每 2.6 秒刷新。 -->
<!-- grow 占位把时钟推到最右侧。 -->
<!-- 最后一段：本地时间，来自 clockStr，每秒刷新。 -->
<!-- 所有文本使用等宽字体，字号为最小档辅助字。 -->
<!-- 状态条使用 backdrop-filter 模糊背景，保持可读性。 -->
<!-- 它固定定位，不参与左右两栏的 flex 布局。 -->
<!-- 在 600px 以下会减小间距和字号。 -->
<!-- 延迟数字使用 .num 类启用等宽数字，避免刷新时抖动。 -->
<!-- 时钟格式为 YYYY-MM-DD HH:mm:ss，由 tickClock 生成。 -->
<!-- 系统状态文案是静态的，不反映真实健康检查。 -->
<!-- 该区域不接收点击，纯展示。 -->
<!-- 状态点的脉冲动画在 reduced-motion 下关闭。 -->
<!-- 状态条的背景与页面底色接近，形成轻微的层次分隔。 -->
    <!-- 底部状态条 -->
    <div class="cl-status">
      <span><i class="st-dot"></i>系统运行正常</span>
      <span>节点 cn-east-1</span>
      <span>延迟 <b class="num">{{ ping }}</b> ms</span>
      <span class="grow"></span>
      <span>{{ clockStr }}</span>
    </div>

<!-- 自检遮罩：登录成功后覆盖全屏，播放启动日志。 -->
<!-- :hidden="!bootVisible" 由 bootVisible 控制显示。 -->
<!-- 遮罩内含 .boot-panel，限制日志面板宽度。 -->
<!-- 面板外的区域使用半透明背景 + blur，聚焦用户注意力。 -->
<!-- 该遮罩的 z-index 为 200，高于状态条，低于擦除层。 -->
<!-- 在 reducedMotion 下 bootVisible 保持 false，遮罩不会出现。 -->
<!-- 遮罩出现期间页面其他区域不可交互。 -->
<!-- 它不包含关闭按钮，转场结束后随路由跳转一起消失。 -->
    <!-- 自检转场 -->
<!-- 遮罩根节点：fixed 全屏，flex 居中面板。 -->
<!-- hidden 属性在 CSS 中映射为 display: none。 -->
<!-- 背景透明度 0.82，仍能隐约看到下面的登录页。 -->
    <div class="boot" :hidden="!bootVisible">
<!-- 自检面板：宽度上限 460px，圆角 16px。 -->
<!-- 背景为半透明深色，带铜金描边与双层阴影。 -->
<!-- overflow: hidden 让头部和日志区的背景不溢出圆角。 -->
<!-- 面板宽度在窄屏下使用 calc(100vw - 48px)，保留左右边距。 -->
<!-- 它是遮罩内唯一的内容容器。 -->
      <div class="boot-panel">
<!-- 面板头部：旋转图标 + SYSTEM SELF-CHECK 标题 + 加载圈。 -->
<!-- 左侧 SVG 是一个抽象的系统图标。 -->
<!-- 标题同时给出英文与中文，保持仪表风格。 -->
<!-- boot-spin 是右侧的旋转圈，表示检查进行中。 -->
<!-- 头部底部有 1px 分隔线。 -->
<!-- 等宽字体与大字距与 HUD 一致。 -->
<!-- 头部不随日志行数变化高度。 -->
<!-- 所有文字静态，不绑定状态。 -->
        <div class="boot-head">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3" />
          </svg>
          <span>SYSTEM SELF-CHECK · 系统自检</span>
          <i class="boot-spin"></i>
        </div>
<!-- 日志区：按 BOOT_LINES 顺序渲染五行。 -->
<!-- 每行由 v-for 生成，key 使用索引。 -->
<!-- class on 由 revealedCount > i 控制，未揭示的行透明并左移。 -->
<!-- 行内结构：> 提示符、检查项、省略号、结果值、补充说明。 -->
<!-- 结果值使用 <b> 着绿色，补充说明使用 .dim 灰色。 -->
<!-- 行高 2.1，保证多行日志有足够的呼吸空间。 -->
<!-- min-height 150px 防止日志逐行出现时面板高度跳动。 -->
<!-- 所有文本使用等宽字体，模拟终端输出。 -->
        <div class="boot-log">
          <div v-for="(ln, i) in BOOT_LINES" :key="'bl-' + i" class="bl" :class="{ on: revealedCount > i }">
            <span class="dim">&gt;</span> {{ ln[0] }} <span class="dim">……</span> <b>{{ ln[1] }}</b> <span class="dim">· {{ ln[2] }}</span>
          </div>
        </div>
<!-- 授权提示：ACCESS GRANTED + 中文说明。 -->
<!-- class on 由 grantVisible 控制，过渡带轻微回弹。 -->
<!-- 颜色为绿色，与日志中的成功结果一致。 -->
<!-- 字号较大，是转场的高潮信息。 -->
<!-- 中文说明「正在接管控制台」解释下一步行为。 -->
<!-- 该元素在日志全部揭示后才出现。 -->
        <div class="boot-grant" :class="{ on: grantVisible }">ACCESS GRANTED<span>授权通过 · 正在接管控制台</span></div>
      </div>
    </div>
<!-- 横向擦除层：从左侧展开覆盖全屏，再跳转。 -->
<!-- class go 由 wipeVisible 控制，transform: scaleX(0->1)。 -->
<!-- 子元素 i 是右缘的铜金光条。 -->
<!-- pointer-events: none，不拦截点击。 -->
<!-- z-index 210，高于自检遮罩。 -->
<!-- 擦除完成后 playBootAndRedirect 才执行 router.push。 -->
    <div class="wipe" :class="{ go: wipeVisible }"><i></i></div>
  </div>
</template>

<style scoped>
/*
 * scoped 样式：所有选择器只作用于本组件，避免污染其他页面。
 * 组织顺序：
 *   1. .cl 设计变量与根布局；
 *   2. 左侧雷达（网格、光晕、HUD、舞台、图例、遥测、侧标）；
 *   3. 右侧登录（光斑、音效、卡片、品牌、标题、表单、按钮、抖动）；
 *   4. 底部状态条；
 *   5. 自检遮罩与擦除；
 *   6. 响应式断点；
 *   7. reduced-motion 降级。
 * 暗色配色是登录页的固定视觉，不随全局主题切换。
 * 所有动画都使用 transform/opacity，尽量避免触发布局。
 * 颜色、圆角、字号优先复用全局变量（--ease/--fast/--t-* / --r-input）。
 * 需要本页专属的石墨/铜金色值时，集中在 .cl 的 --gc-* 变量中。
 */
/* 根容器：占满视口，固定暗色，左右 56/44 两栏。 */
/* 使用 grid 而不是 flex，是为了让两栏比例稳定且无需计算宽度。 */
/* min-height: 100vh 保证内容不足时也铺满屏幕。 */
/* overflow: hidden 裁掉装饰层与雷达的溢出部分。 */
/* 背景色与文字色都来自 --gc-* 变量，子元素直接继承。 */
/* position: relative 为底部状态条和遮罩提供定位上下文。 */
/* 56fr/44fr 让左栏略宽，给雷达足够展示空间。 */
/* 该容器不设置 max-width，登录页需要全屏氛围。 */
/* 暗色是刻意固定而不是跟随主题：登录页在认证前不应依赖用户主题偏好。 */
/* grid 子项默认 stretch，左右两栏自动等高。 */
/* 没有使用 gap，两栏之间的分隔由 .cl-left 的右边框负责。 */
/* 文字抗锯齿由全局样式处理，这里不重复设置。 */
/* 根容器的高度不依赖 JS 测量，避免首帧布局抖动。 */
/* 若将来需要浅色版，只需覆盖 --gc-* 变量，不需改结构。 */
/* overflow: hidden 同时防止 100vh 下的横向滚动条。 */
/* 该容器的 z-index 不设置，子元素各自管理层级。 */
.cl {
  /* 指挥中枢 · 石墨铜金（固定暗色，与主题无关） */
/* 设计变量集中在这里，子区块只引用变量，不重复写十六进制。 */
/* --gc-bg / --gc-bg-2：页面底色与略亮的卡片/面板底色。 */
/* --gc-ink / -2 / -3：三级文字墨色，从主标题到辅助说明逐级变淡。 */
/* --gc-line：1px 分隔线/边框使用的半透明白。 */
/* --gc-copper / -2 / -3：铜金强调色，用于描边、按钮渐变与光效。 */
/* 这些变量只在 .cl 作用域内生效，不会泄漏到全局。 */
/* 使用 rgba 而不是 hex 的地方，主要是需要叠加透明度的线条与遮罩。 */
/* 铜金色 #d89b43 是整页的主强调色，与雷达扫描光一致。 */
/* --gc-bg-2 比 --gc-bg 略亮，用于需要轻微浮起的面板。 */
/* 三级墨色的对比度依次降低，但都保持在深色背景上可读。 */
/* 变量命名前缀 gc 表示 graphite console，避免与全局变量冲突。 */
/* 若需要调整整体色温，改这几个变量即可覆盖大部分区域。 */
  --gc-bg: #101214;
  --gc-bg-2: #171a1c;
  --gc-ink: #edeae2;
  --gc-ink-2: #b4b0a6;
  --gc-ink-3: #8a877d;
  --gc-line: rgba(255, 255, 255, 0.07);
  --gc-copper: #d89b43;
  --gc-copper-2: #c07e28;
  --gc-copper-3: #b4741c;
/* 根布局：相对定位 + 视口高度 + 两栏栅格。 */
/* 背景色使用 --gc-bg，文字色使用 --gc-ink。 */
/* overflow: hidden 是装饰层不产生滚动条的关键。 */
/* 两栏比例 56:44，左栏承担雷达与遥测。 */
/* 根容器不设置 padding，内边距由各子区块自行控制。 */
/* grid 让两栏在高度上自动拉伸一致。 */
/* 该规则不涉及动画，reduced-motion 无需覆盖。 */
/* 所有子区块的 z-index 都相对于根容器的定位上下文。 */
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: 56fr 44fr;
  background: var(--gc-bg);
  color: var(--gc-ink);
  overflow: hidden;
}

/* 左栏：纵向 flex，雷达舞台占据剩余高度。 */
/* 背景是左上偏亮的径向渐变，让雷达区域有轻微聚光效果。 */
/* 右边框作为两栏之间的分隔线。 */
/* overflow: hidden 裁掉网格与光晕的溢出。 */
/* min-height: 100vh 与根容器一致，保证背景铺满。 */
/* 纵向布局顺序：HUD -> 雷达舞台 -> 遥测。 */
/* 侧标和网格是绝对定位，不参与 flex 排列。 */
/* 在窄屏下该栏会变为主栏上方的横向区块。 */
/* flex-direction: column 让雷达舞台的 flex: 1 能正确分配剩余空间。 */
/* 背景渐变的位置 (30% 40%) 与雷达圆心大致对齐。 */
/* —— 左侧雷达 —— */
.cl-left {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: radial-gradient(120% 90% at 30% 40%, #171a1c 0%, #101214 70%);
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  overflow: hidden;
}
/* 背景网格：两组 1px 线性渐变叠加成 40px 方格。 */
/* 使用 radial-gradient 遮罩让网格在边缘淡出，避免生硬裁切。 */
/* -webkit-mask-image 与 mask-image 同时写，兼容旧版 WebKit。 */
/* inset: 0 让网格铺满整个左栏。 */
/* 网格透明度只有 0.05，作为背景纹理而不是主视觉。 */
/* 该层绝对定位，位于内容之下。 */
/* 不设置 pointer-events，网格不会拦截雷达交互。 */
/* 网格尺寸固定 40px，不随视口缩放。 */
.cl-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(216, 155, 67, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(216, 155, 67, 0.05) 1px, transparent 1px);
  background-size: 40px 40px;
  -webkit-mask-image: radial-gradient(80% 80% at 50% 45%, #000 30%, transparent 100%);
  mask-image: radial-gradient(80% 80% at 50% 45%, #000 30%, transparent 100%);
}
/* 铜金光晕：位于左栏中部的模糊径向渐变。 */
/* left/top 20% + 60% 尺寸，让光晕大致覆盖雷达区域。 */
/* filter: blur(40px) 把渐变边缘完全柔化。 */
/* animation clGlow 9s 交替缩放与淡出，形成呼吸感。 */
/* pointer-events: none，不影响雷达指针事件。 */
/* 该层在 reduced-motion 下动画被关闭，但光晕仍静态可见。 */
.cl-glow {
  position: absolute;
  width: 60%;
  height: 60%;
  left: 20%;
  top: 20%;
  background: radial-gradient(circle, rgba(180, 116, 28, 0.16), transparent 65%);
  filter: blur(40px);
  pointer-events: none;
  animation: clGlow 9s ease-in-out infinite alternate;
}
/* clGlow：从默认状态放大到 1.15 倍、透明度 0.7。 */
/* alternate 让动画往复，不会在结束时跳回。 */
/* 只改 transform 与 opacity，保持合成层性能。 */
/* 9s 的周期足够慢，避免分散用户对登录表单的注意力。 */
/* ease-in-out 让缩放两端平滑。 */
@keyframes clGlow {
  to {
    transform: scale(1.15);
    opacity: 0.7;
  }
}
/* HUD 顶栏：雷达上方的操作读数条。 */
/* z-index: 3 保证它在网格/光晕之上。 */
/* flex + gap 18px 横向排列各读数。 */
/* padding 18px 26px 0：顶部与左右留白，底部不占额外空间。 */
/* 等宽字体 + 0.08em 字距模拟仪表读数。 */
/* 颜色为三级墨色，读数值由 .hud-kv b 提亮。 */
/* .grow 是弹性占位，把 CONTACTS/RNG 推到右侧。 */
/* HUD 不接收点击，pointer-events 保持默认即可。 */
/* 在 600px 以下 gap 与 padding 会缩小。 */
/* 该区域不参与雷达尺寸计算，雷达舞台独立分配剩余高度。 */
/* 所有读数都是文本，屏幕阅读器可以读取。 */
/* LIVE SWEEP 使用青绿色，与右侧页眉形成呼应。 */
/* HUD 的高度由内容决定，不会压缩雷达。 */
/* z-index 高于雷达，悬浮提示的 z-index 更高。 */
.cl-hud {
  position: relative;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 26px 0;
  font-family: var(--font-mono);
  font-size: var(--t-2xs);
  letter-spacing: 0.08em;
  color: var(--gc-ink-3);
}
.cl-hud .grow {
  flex: 1;
}
/* LIVE SWEEP 标签：小圆点 + 文本。 */
/* 使用 inline-flex 让圆点与文字垂直居中。 */
/* 青绿色 #7fb3ae 与右侧 OPERATIONS CONSOLE 一致。 */
/* font-weight: 600 让状态标签比普通读数更醒目。 */
/* gap 7px 控制圆点与文字间距。 */
/* 该标签没有交互，不设置 hover。 */
.hud-tag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #7fb3ae;
  font-weight: 600;
}
/* 状态圆点：7px 实心圆，青绿色。 */
/* box-shadow 的扩散环由 pulse 动画驱动。 */
/* animation 2s infinite 让脉冲持续循环。 */
/* 圆点本身不随扫描角变化，只表示 LIVE 状态。 */
/* 在 reduced-motion 下动画关闭，圆点保持静止。 */
/* 底部状态条的 .st-dot 复用同一套脉冲思路。 */
.hud-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7fb3ae;
  box-shadow: 0 0 0 0 rgba(127, 179, 174, 0.5);
  animation: pulse 2s infinite;
}
/* 读数键值对中的数值部分：比标签更亮。 */
/* #c9c6bc 在深色背景上有足够对比度。 */
/* font-weight: 600 让数字在等宽字体中不显单薄。 */
/* 该规则只作用于 HUD，不影响遥测数字。 */
.hud-kv b {
  color: #c9c6bc;
  font-weight: 600;
}
/* pulse：让 box-shadow 的扩散环从 0 扩大到 7px 并淡出。 */
/* 70% 时扩散到 7px 且透明度归零，100% 复位。 */
/* 只动画 box-shadow，开销较小。 */
/* 2s 周期与 HUD 状态灯的节奏一致。 */
/* reduced-motion 下通过 animation: none 关闭。 */
/* 该关键帧被 .hud-dot 与 .st-dot 共用。 */
@keyframes pulse {
  70% {
    box-shadow: 0 0 0 7px rgba(127, 179, 174, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(127, 179, 174, 0);
  }
}

/* 雷达交互容器：占据左栏剩余高度并居中舞台。 */
/* z-index: 2 高于网格/光晕，低于 HUD。 */
/* flex: 1 + min-height: 0 让它在纵向 flex 中正确收缩。 */
/* align/justify center 让正方形舞台居中。 */
/* margin 6px 18px 给舞台留出呼吸空间。 */
/* 该元素是 pointermove/pointerleave 的绑定目标。 */
/* 它不设置 overflow: hidden，避免裁掉悬浮提示。 */
/* 尺寸变化由 ResizeObserver 观察该元素。 */
.cl-radar-wrap {
  position: relative;
  z-index: 2;
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 6px 18px;
}
/* 雷达舞台：宽高由 stageSize 内联设置，保持正方形。 */
/* position: relative 为绝对定位的准星与提示提供参照。 */
/* flex: none 防止 flex 布局拉伸它。 */
/* 舞台本身不接收特定事件，事件由父容器统一处理。 */
.radar-stage {
  position: relative;
  flex: none;
}
/* SVG 填满舞台，并按 viewBox 等比缩放。 */
/* display: block 消除行内元素底部的基线间隙。 */
/* width/height 100% 与舞台尺寸同步。 */
/* 图形坐标始终使用 120 逻辑单位，不随屏幕像素变化。 */
.radar-svg {
  display: block;
  width: 100%;
  height: 100%;
}
/* 方位度数文字：填充低透明度灰白。 */
/* font-size 3.4px 是 SVG 逻辑单位，缩放后约 9px。 */
/* 等宽字体保证 000/030 等读数宽度一致。 */
/* 颜色不随主刻度/次刻度变化，只有刻度线区分主次。 */
/* 文字不可选中，避免拖动时出现选区。 */
/* 该样式只作用于 SVG 内的 text。 */
.rad-deg {
  fill: rgba(201, 198, 188, 0.5);
  font-size: 3.4px;
  font-family: var(--font-mono);
}
/* 接触点编号文字 T-01 等：字号略小于方位度数。 */
/* 透明度 0.55，避免与接触点本体争夺注意力。 */
/* 等宽字体让编号对齐。 */
/* 文字位置由模板中的 x+4/y-3 决定，样式不参与定位。 */
.blip-t {
  fill: rgba(201, 198, 188, 0.55);
  font-size: 3.2px;
  font-family: var(--font-mono);
}
/* 接触点悬浮提示：绝对定位在接触点上方。 */
/* translate(-50%, -128%) 让卡片水平居中并略高于接触点。 */
/* z-index: 5 高于 HUD 与雷达，保证不被遮挡。 */
/* 半透明深色背景 + 铜金描边，与登录卡片同一视觉语言。 */
/* box-shadow 提供浮起感。 */
/* pointer-events: none 防止提示跟随鼠标时产生闪烁。 */
/* white-space: nowrap 让长工具名保持单行。 */
/* min-width: 120px 保证短名称的卡片也有合理宽度。 */
/* 位置由模板的 left/top 百分比控制。 */
/* 该卡片只在 hoverBlip 非空时渲染，不做显示/隐藏动画。 */
.radar-tip {
  position: absolute;
  z-index: 5;
  transform: translate(-50%, -128%);
  min-width: 120px;
  padding: 6px 9px;
  border-radius: 7px;
  background: rgba(23, 26, 28, 0.94);
  border: 1px solid rgba(216, 155, 67, 0.45);
  box-shadow: 0 12px 30px -12px rgba(0, 0, 0, 0.8);
  pointer-events: none;
  white-space: nowrap;
}
/* 提示中的工具名称：块级、主墨色、600 字重。 */
/* 字号使用 --t-xs，保证长名称不会撑破卡片。 */
/* 单行显示，超出部分由外层 nowrap 控制。 */
/* 与第二行的状态信息形成主次层级。 */
.radar-tip b {
  display: block;
  font-size: var(--t-xs);
  color: var(--gc-ink);
  font-weight: 600;
}
/* 提示中的状态与资产编码：9px 等宽字体。 */
/* 颜色由模板内联为状态色，样式只负责字号与间距。 */
/* margin-top: 1px 让两行紧凑。 */
/* 块级显示，独占一行。 */
.radar-tip span {
  display: block;
  font-size: 9px;
  font-family: var(--font-mono);
  margin-top: 1px;
}
/* 雷达图例：绝对定位在容器左下角。 */
/* flex + gap 横向排列三个状态。 */
/* 字号 --t-2xs、三级墨色、等宽字体。 */
/* left/bottom 10px/6px 避免贴边。 */
/* 图例不参与雷达舞台的居中计算。 */
/* i 元素是 8px 圆点，颜色由模板内联。 */
/* 图例在窄屏下仍然可见。 */
/* 它不接收点击，也不显示悬浮说明。 */
.cl-legend {
  position: absolute;
  left: 10px;
  bottom: 6px;
  display: flex;
  gap: 14px;
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  font-family: var(--font-mono);
}
.cl-legend i {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 5px;
}

/* 遥测面板：四列等宽栅格。 */
/* z-index: 3 高于背景装饰，低于状态条。 */
/* gap 10px 控制卡片间距。 */
/* padding 0 26px 20px 与左栏边距对齐，底部留白。 */
/* 四列在 600px 以下改为两列，见响应式分区。 */
/* 该区域位于雷达舞台下方，由 flex 布局自然定位。 */
/* 卡片内部使用块级文字，无需额外 flex。 */
/* 面板本身不设置背景，透明地叠在左栏渐变上。 */
/* 高度由内容决定，不会挤压雷达舞台。 */
/* 数字与单位使用不同的字号/字距。 */
.cl-telemetry {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  padding: 0 26px 20px;
}
/* 单张遥测卡片：半透明深色背景 + 1px 边框 + 圆角。 */
/* backdrop-filter 模糊下层光晕，形成玻璃质感。 */
/* 同时写 -webkit- 前缀，兼容 Safari。 */
/* padding 12px 14px 让文字不贴边。 */
/* 卡片不设置 hover，保持静态展示。 */
/* 背景透明度 0.6，仍能透出左栏渐变。 */
/* 圆角 12px 与登录卡片的 20px 形成层级差异。 */
/* 卡片之间没有分隔线，间距由 grid gap 负责。 */
.tm {
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(16, 18, 20, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.07);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
/* 遥测中文标签：块级、二级小字、三级墨色。 */
/* margin-bottom 3px 与数值拉开。 */
/* 不设置字重，保持辅助信息的轻量感。 */
/* 四个标签的样式完全一致。 */
.tm-k {
  display: block;
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  margin-bottom: 3px;
}
/* 遥测数值：大字号、粗体、等宽数字。 */
/* line-height 1.1 让大数字不产生过多行高。 */
/* font-variant-numeric: tabular-nums 保证缓动时不抖动。 */
/* 默认墨色用于 ONLINE，其余状态由修饰类覆盖。 */
/* .num 全局类可能提供额外数字样式，这里叠加字重。 */
/* 字号 --t-3xl 是左栏除雷达外最大的视觉元素。 */
/* 数值由 countTo 从 0 缓动，模板只绑定整数。 */
/* 该规则不包含颜色，颜色交给 .ok/.warn/.bad。 */
.tm-v {
  display: block;
  font-size: var(--t-3xl);
  font-weight: 800;
  line-height: 1.1;
  color: var(--gc-ink);
  font-variant-numeric: tabular-nums;
}
/* 可借用数量：绿色 #3bb273。 */
/* 与雷达 AVAILABLE 接触点、图例颜色一致。 */
/* 只覆盖颜色，其他样式继承 .tm-v。 */
/* 语义色在暗色背景上有足够对比度。 */
.tm-v.ok {
  color: #3bb273;
}
/* 已租出数量：琥珀色 #e0a33c。 */
/* 与雷达 RENTED 接触点、图例颜色一致。 */
/* 只覆盖颜色。 */
/* 琥珀色在深色背景上用于「需要注意但非错误」的状态。 */
.tm-v.warn {
  color: #e0a33c;
}
/* 维修中数量：珊瑚红 #e56a5e。 */
/* 与雷达 MAINTENANCE 接触点、图例颜色一致。 */
/* 只覆盖颜色。 */
/* 该颜色也用于表单错误提示与错误态输入框边框。 */
.tm-v.bad {
  color: #e56a5e;
}
/* 遥测英文单位：9px、大字距、弱化墨色。 */
/* 等宽字体与 HUD 读数一致。 */
/* margin-top 2px 紧贴数值。 */
/* 块级显示，独占一行。 */
/* letter-spacing 0.18em 强化仪表标签感。 */
/* 单位不参与数字缓动。 */
.tm-u {
  display: block;
  font-size: 9px;
  letter-spacing: 0.18em;
  color: #6a675e;
  font-family: var(--font-mono);
  margin-top: 2px;
}
/* 竖排侧标：沿左栏左缘垂直排列的构建信息。 */
/* writing-mode: vertical-rl 让文字竖向流动。 */
/* rotate(180deg) 把阅读方向翻转为从下往上。 */
/* translateY(-50%) 配合 top: 50% 实现垂直居中。 */
/* 字号 9px、字距 0.32em，存在感很弱。 */
/* z-index: 3 保证在网格/光晕之上。 */
/* 在 1023px 以下隐藏，避免占用横向空间。 */
/* 纯装饰，不接收指针事件。 */
.cl-rail {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%) rotate(180deg);
  writing-mode: vertical-rl;
  font-size: 9px;
  letter-spacing: 0.32em;
  color: rgba(201, 198, 188, 0.3);
  font-family: var(--font-mono);
  z-index: 3;
}

/* 右栏：flex 居中登录卡片。 */
/* 背景径向渐变让右上略亮，与左栏形成冷暖对比。 */
/* padding 48px 40px 64px：顶部与左右留白，底部避开状态条。 */
/* overflow: hidden 裁掉背景光斑。 */
/* position: relative 为光斑、音效按钮和卡片提供定位上下文。 */
/* 该栏宽度由 grid 的 44fr 决定。 */
/* 在窄屏下移到左栏下方，padding 会减小。 */
/* 背景不依赖全局主题变量。 */
/* —— 右侧登录 —— */
.cl-right {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px 64px;
  overflow: hidden;
  background: radial-gradient(90% 70% at 70% 30%, #191d1f 0%, #101214 75%);
}
/* 背景光斑容器：铺满右栏，模糊处理两个渐变圆。 */
/* inset: 0 让它覆盖整个右栏。 */
/* filter: blur(70px) 把圆形边缘完全柔化。 */
/* pointer-events: none，不拦截登录表单的点击。 */
/* 两个 i 子元素分别是暖色与冷色光斑。 */
/* 光斑尺寸使用 vw，随视口缩放。 */
/* 动画 clMesh 20s/26s 交替漂移，形成缓慢流动感。 */
/* 光斑位于卡片之下，卡片 z-index 更高。 */
/* 模糊与低透明度保证不会影响文字对比度。 */
/* reduced-motion 下动画关闭，光斑保持静态。 */
/* 该层不设置背景色，只靠子元素提供颜色。 */
/* 容器本身没有尺寸动画，只有子元素移动。 */
.cl-mesh {
  position: absolute;
  inset: 0;
  pointer-events: none;
  filter: blur(70px);
}
.cl-mesh i {
  position: absolute;
  border-radius: 50%;
  opacity: 0.5;
}
/* 暖色光斑：铜金色，定位在右上角。 */
/* 34vw 尺寸 + right/top 负偏移，让光晕从角落渗入。 */
/* 20s 的正向漂移动画。 */
/* opacity 0.5 与 blur 叠加后非常柔和。 */
.cl-mesh i:nth-child(1) {
  width: 34vw;
  height: 34vw;
  right: -8vw;
  top: -10vw;
  background: radial-gradient(circle, rgba(180, 116, 28, 0.4), transparent 65%);
  animation: clMesh 20s ease-in-out infinite alternate;
}
/* 冷色光斑：青绿色，定位在左下角。 */
/* 26vw 尺寸 + 负偏移，与暖色光斑形成对角平衡。 */
/* 26s 的反向动画，避免两个光斑同步移动。 */
/* 低透明度保证不会与登录卡片争夺注意力。 */
.cl-mesh i:nth-child(2) {
  width: 26vw;
  height: 26vw;
  left: -6vw;
  bottom: -10vw;
  background: radial-gradient(circle, rgba(127, 179, 174, 0.32), transparent 65%);
  animation: clMesh 26s ease-in-out infinite alternate-reverse;
}
/* clMesh：光斑向左下漂移 5% 并放大 1.12 倍。 */
/* 只改 transform，保持合成层性能。 */
/* alternate 让两个光斑往复运动。 */
/* 20s/26s 的慢周期制造几乎察觉不到的流动感。 */
@keyframes clMesh {
  to {
    transform: translate3d(-5%, 5%, 0) scale(1.12);
  }
}
/* 音效按钮：绝对定位在右上角。 */
/* z-index: 6 高于光斑与卡片，保证可点击。 */
/* 半透明白背景 + 1px 边框，融入暗色界面。 */
/* hover 时背景与文字提亮。 */
/* 按钮尺寸与图标由全局 .icon-btn 提供。 */
/* 具体点击行为由 bindSoundToggle 绑定。 */
.cl-sound {
  position: absolute;
  top: 22px;
  right: 24px;
  z-index: 6;
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.12);
  color: #c9c6bc;
}
.cl-sound:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

/* 登录卡片：右栏的视觉核心。 */
/* z-index: 4 高于背景光斑。 */
/* width: min(420px, 100%) 保证大屏不超宽、窄屏不溢出。 */
/* padding 36px 36px 30px 提供充足内边距。 */
/* 圆角 20px、半透明深色背景、1px 亮边框。 */
/* backdrop-filter blur(20px) + saturate(1.2) 形成玻璃质感。 */
/* 双层阴影：外层大范围下沉 + 内层顶部高光。 */
/* animation clIn 0.7s 让卡片从下方淡入。 */
/* 该卡片是 .shake 动画的作用对象。 */
/* 背景透明度 0.72 保证文字可读。 */
/* 卡片宽度在 600px 以下由父级 padding 决定。 */
/* 内层高光让卡片顶部边缘有细微反光。 */
/* 所有表单控件都在这张卡片内。 */
/* 卡片本身不接收键盘事件，Enter 由表单处理。 */
/* 动画只执行一次，不循环。 */
/* 卡片入场动画与 shake 动画作用于同一元素，但触发时机不同。 */
.cl-card {
  position: relative;
  z-index: 4;
  width: min(420px, 100%);
  padding: 36px 36px 30px;
  border-radius: 20px;
  background: rgba(16, 18, 20, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.09);
  -webkit-backdrop-filter: blur(20px) saturate(1.2);
  backdrop-filter: blur(20px) saturate(1.2);
  box-shadow:
    0 40px 90px -30px rgba(0, 0, 0, 0.7),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  animation: clIn 0.7s var(--ease);
}
/* clIn：从透明度 0、下移 22px、缩小 0.98 进入。 */
/* 0.7s + --ease 让入场轻盈但不拖沓。 */
/* from 只定义起始态，结束态使用元素默认样式。 */
/* 只动画 opacity/transform，性能友好。 */
@keyframes clIn {
  from {
    opacity: 0;
    transform: translateY(22px) scale(0.98);
  }
}
/* 品牌区：横向排列标志与文字。 */
/* gap 13px 控制图形与文字间距。 */
/* margin-bottom 24px 与下方页眉拉开。 */
/* --brand/--ink/--brand-ink 覆盖 BrandMark 内部使用的颜色变量。 */
/* 这些变量只在本区块生效。 */
/* 标志与文字顶部对齐，视觉上以图形高度为基准。 */
/* 品牌区不设置背景，直接叠在卡片背景上。 */
/* 在窄屏下仍然保持横排。 */
.cl-brand {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 24px;
  --brand: #d89b43;
  --ink: rgba(255, 249, 236, 0.85);
  --brand-ink: #fff9ec;
}
/* 产品名 ToolShare：块级、较大字号、粗体。 */
/* line-height 1.2 让单行标题紧凑。 */
/* letter-spacing -0.01em 让英文标题更紧致。 */
/* 颜色继承卡片的 --gc-ink。 */
.cl-brand b {
  font-size: var(--t-xl);
  font-weight: 700;
  display: block;
  line-height: 1.2;
  letter-spacing: -0.01em;
}
/* 副标题：小字号、三级墨色、大字距。 */
/* 与产品名形成明显的主次层级。 */
/* 文案中的 OPERATIONS 与页眉/HUD 保持一致。 */
/* 块级显示，位于产品名下方。 */
.cl-brand span {
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  letter-spacing: 0.12em;
}
/* 页眉小标签：图标 + 英文。 */
/* flex + gap 7px 让图标与文字对齐。 */
/* 等宽字体、0.16em 字距、青绿色。 */
/* margin 0 0 8px 紧贴主标题。 */
/* 图标尺寸 14px。 */
/* 该标签不参与交互。 */
.cl-eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--t-2xs);
  font-weight: 600;
  letter-spacing: 0.16em;
  color: #7fb3ae;
  margin: 0 0 8px;
  font-family: var(--font-mono);
}
.cl-eyebrow .ic {
  width: 14px;
  height: 14px;
}
/* 主标题「指挥中枢」：超大字号、极粗字重。 */
/* letter-spacing -0.02em 让中文标题更紧凑。 */
/* 渐变背景 + background-clip: text 形成白到铜金的过渡。 */
/* -webkit-text-fill-color: transparent 让渐变透出。 */
/* 同时写标准 background-clip 与 -webkit- 前缀。 */
/* margin 0 0 8px 控制与副标题的间距。 */
/* 渐变角度 120deg，从左下到右上。 */
/* 现代浏览器均支持 background-clip: text，旧版 WebKit 使用带前缀版本。 */
.cl-title {
  font-size: var(--t-4xl);
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 0 0 8px;
  background: linear-gradient(120deg, #fff 30%, #e4be79);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
/* 副标题：说明登录页用途。 */
/* 使用次级墨色，保证在卡片背景上可读。 */
/* line-height 1.6 让两行文案有呼吸感。 */
/* margin 0 0 26px 与表单拉开距离。 */
/* 字号 --t-sm，小于正文。 */
/* 文案为静态内容，不绑定状态。 */
.cl-sub {
  font-size: var(--t-sm);
  color: var(--gc-ink-2);
  margin: 0 0 26px;
  line-height: 1.6;
}

/* —— Element Plus 暗色重绘 —— */
/* Element Plus 默认按浅色语义设计，以下 :deep() 规则逐项覆盖： */
/*   .el-form-item        间距； */
/*   .el-form-item__label 标签颜色与字号； */
/*   .el-form-item__error 错误文案颜色； */
/*   .el-input__wrapper   输入框背景、边框、圆角、阴影； */
/*   hover/focus/error    三种交互态； */
/*   .el-input__inner     输入文字与占位符； */
/*   prefix/suffix        前后缀图标颜色。 */
/* 只覆盖颜色与间距，不改变组件结构与行为。 */
/* :deep() 让 scoped 样式穿透到子组件内部。 */
/* 所有覆盖都限定在 .cl-card 内，避免影响其他页面的 Element Plus 组件。 */
/* 输入框高度统一为 42px，与按钮和卡片内边距协调。 */
/* 焦点态使用铜金描边 + 3px 光晕，与整页强调色一致。 */
/* 错误态使用珊瑚红，与遥测的 bad 色一致。 */
/* 这些规则不涉及 transition 之外的动画，reduced-motion 无需处理。 */
/* 占位符颜色单独设置，因为 Element Plus 默认占位符在暗色下过暗。 */
/* Element Plus 表单在暗色控制台中的重绘 */
/* 表单项下边距 18px，控制两个输入框之间的节奏。 */
/* 只影响 .cl-card 内的表单项。 */
/* 该间距也与辅助行、按钮的间距协调。 */
/* 不覆盖 el-form-item 的 display，保持组件默认布局。 */
/* 两个字段的间距一致，视觉上形成稳定的纵向节奏。 */
/* 错误文案出现时会占用额外高度，18px 间距已预留空间。 */
.cl-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
/* 表单标签：二级墨色、--t-sm、600 字重。 */
/* height: auto + line-height 1.4 让标签高度随内容自适应。 */
/* margin-bottom 7px 与输入框拉开。 */
/* 标签在输入框上方（label-position="top"）。 */
.cl-card :deep(.el-form-item__label) {
  color: var(--gc-ink-2);
  font-size: var(--t-sm);
  font-weight: 600;
  margin-bottom: 7px;
  height: auto;
  line-height: 1.4;
}
/* 校验错误文案：珊瑚红，与遥测 bad 色一致。 */
/* padding-top 4px 与输入框保持距离。 */
/* 字号继承 Element Plus 默认值。 */
/* 错误文案由 validate() 触发时渲染。 */
.cl-card :deep(.el-form-item__error) {
  color: #e56a5e;
  padding-top: 4px;
}
/* 输入框外壳：半透明白背景 + 1px 亮边框。 */
/* border-radius 使用全局 --r-input。 */
/* box-shadow: none 清除 Element Plus 默认的浅色阴影。 */
/* padding 0 12px 控制文字与边框的距离。 */
/* transition 只作用于边框色与阴影，避免布局动画。 */
/* 背景透明度 0.04，保持玻璃质感。 */
.cl-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: var(--r-input);
  box-shadow: none;
  padding: 0 12px;
  transition: border-color var(--fast), box-shadow var(--fast);
}
/* 输入框 hover：边框提亮到 0.2 透明度。 */
/* 不改变背景，避免 hover 时闪烁。 */
/* 该规则与 focus 规则叠加时，focus 优先级更高。 */
/* 鼠标与键盘用户都能获得反馈。 */
.cl-card :deep(.el-input__wrapper:hover) {
  border-color: rgba(255, 255, 255, 0.2);
}
/* 输入框 focus：铜金边框 + 3px 光晕。 */
/* 同时匹配 .el-input.is-focus 与 .el-input__wrapper.is-focus， */
/* 兼容 Element Plus 不同版本的类名结构。 */
/* 光晕使用 rgba(216,155,67,.25)，与按钮渐变同色系。 */
/* 焦点态是键盘可访问性的关键反馈。 */
.cl-card :deep(.el-input.is-focus .el-input__wrapper),
.cl-card :deep(.el-input__wrapper.is-focus) {
  border-color: var(--gc-copper);
  box-shadow: 0 0 0 3px rgba(216, 155, 67, 0.25);
}
/* 输入文字：主墨色、42px 高度、--t-base 字号。 */
/* line-height 与高度一致，保证垂直居中。 */
/* 高度与密码框保持一致。 */
/* 文字颜色在暗色背景上有足够对比度。 */
.cl-card :deep(.el-input__inner) {
  color: var(--gc-ink);
  height: 42px;
  line-height: 42px;
  font-size: var(--t-base);
}
/* 占位符颜色 #7c7a70，比三级墨色更弱。 */
/* 保证用户能区分「已输入」与「提示」。 */
/* 该颜色在暗色背景上仍可读。 */
/* 只作用于本卡片的输入框。 */
.cl-card :deep(.el-input__inner::placeholder) {
  color: #7c7a70;
}
/* 前后缀图标：三级墨色，与占位符协调。 */
/* 同时覆盖 prefix 与 suffix，保持图标颜色一致。 */
/* show-password 的切换按钮位于 suffix。 */
/* 图标尺寸由 Element Plus 内部样式控制。 */
.cl-card :deep(.el-input__prefix),
.cl-card :deep(.el-input__suffix) {
  color: var(--gc-ink-3);
}
/* 错误态输入框：珊瑚红边框 + 3px 红色光晕。 */
/* 只在 .el-form-item.is-error 时生效。 */
/* 与错误文案颜色一致，形成统一的错误语义。 */
/* focus 与 error 同时存在时，错误态规则在后方，优先级更高。 */
.cl-card :deep(.el-form-item.is-error .el-input__wrapper) {
  border-color: #e56a5e;
  box-shadow: 0 0 0 3px rgba(229, 106, 94, 0.2);
}

/* 辅助行：记住设备 + 忘记密码。 */
/* flex + space-between 分列两端。 */
/* margin 2px 0 22px 控制与表单、按钮的间距。 */
/* 字号 --t-sm 与表单标签一致。 */
/* 该行不参与表单校验。 */
/* 在窄屏下仍然保持两端对齐。 */
.cl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 22px;
  font-size: var(--t-sm);
}
/* 复选框标签：二级墨色、inline-flex 对齐。 */
/* gap 7px 控制复选框与文字间距。 */
/* cursor: pointer 让整段文字可点击。 */
/* 原生 checkbox 的尺寸由浏览器决定。 */
/* 该标签不使用 Element Plus 的 el-checkbox。 */
.cl .ck {
  color: var(--gc-ink-2);
  display: inline-flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
}
/* 用 accent-color 把原生复选框染成铜金色。 */
/* 该属性在现代浏览器中均受支持。 */
/* 不改变复选框尺寸与行为。 */
.cl .ck input {
  accent-color: #d89b43;
}
/* 忘记密码链接：二级墨色，hover 变铜金。 */
/* 与复选框文字颜色一致。 */
/* 链接本身由模板的 href="javascript:void(0)" 阻止跳转。 */
/* hover 过渡使用全局 --fast。 */
.cl-row a {
  color: var(--gc-ink-2);
}
.cl-row a:hover {
  color: #d89b43;
}
/* 主按钮配色：铜金渐变。 */
/* 135deg 让渐变从左下到右上。 */
/* 白色文字保证在深铜金背景上可读。 */
/* 双层阴影：外部铜金光晕 + 内部顶部高光。 */
/* hover 时整体提亮 8%。 */
/* 按钮尺寸、圆角与布局由全局 .btn/.btn-lg/.btn-block 提供。 */
.cl .btn-primary {
  background: linear-gradient(135deg, #c07e28, #b4741c);
  color: #fff;
  box-shadow:
    0 10px 30px -10px rgba(180, 116, 28, 0.7),
    inset 0 1px 0 rgba(255, 255, 255, 0.2);
}
.cl .btn-primary:hover {
  filter: brightness(1.08);
}

/* 登录失败时的卡片抖动。 */
/* 只改 translateX，不影响卡片其他布局。 */
/* 0.4s 的时长足够被感知但不拖沓。 */
/* 类名由 shaking 状态控制，播放结束后移除。 */
/* 抖动幅度最大 6px，属于轻量反馈。 */
/* 该动画不改变透明度，卡片内容始终可读。 */
.shake {
  animation: shake 0.4s;
}
/* shake：0/100% 归位，20/60% 左移 6px，40/80% 右移 6px。 */
/* 两次完整往复形成短促的「拒绝」反馈。 */
/* 只动画 transform，不触发布局。 */
/* 与 clIn 入场动画不会同时触发。 */
@keyframes shake {
  0%,
  100% {
    transform: translateX(0);
  }
  20%,
  60% {
    transform: translateX(-6px);
  }
  40%,
  80% {
    transform: translateX(6px);
  }
}

/* 底部状态条：固定覆盖在页面底部。 */
/* z-index: 8 高于左右两栏与遥测。 */
/* 横向 flex，gap 22px 分隔各项。 */
/* 半透明深色背景 + backdrop-filter，浮在内容之上。 */
/* 顶部 1px 分隔线。 */
/* 等宽字体与 --t-2xs 字号，与 HUD 一致。 */
/* .grow 把时钟推到最右侧。 */
/* 状态条不参与左右两栏的 grid 布局，是绝对定位层。 */
/* 在 600px 以下 gap 与字号会减小。 */
/* 背景透明度 0.72，保证下方内容不会干扰文字。 */
/* —— 底部状态条 —— */
.cl-status {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 12px 26px;
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(16, 18, 20, 0.72);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
  font-family: var(--font-mono);
}
.cl-status .grow {
  flex: 1;
}
.cl-status b {
  color: #c9c6bc;
}
/* 状态条绿色脉冲点：与 HUD 的 hud-dot 同一套视觉。 */
/* 7px 圆点、绿色、2s 脉冲。 */
/* margin-right 7px 与文字拉开。 */
/* box-shadow 扩散环由 pulse 关键帧驱动。 */
/* reduced-motion 下动画关闭。 */
.st-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #3bb273;
  margin-right: 7px;
  box-shadow: 0 0 0 0 rgba(59, 178, 115, 0.5);
  animation: pulse 2s infinite;
}

/* 自检遮罩：fixed 全屏覆盖。 */
/* z-index: 200 高于页面所有内容。 */
/* flex 居中 .boot-panel。 */
/* 背景 rgba(12,14,16,.82) + blur(6px)，弱化背景但保留轮廓。 */
/* animation fadeIn 0.3s 让遮罩淡入。 */
/* [hidden] 时 display: none，不参与布局。 */
/* 遮罩不包含关闭按钮，只能等待转场结束。 */
/* 该层在 reducedMotion 下不会出现。 */
/* 遮罩出现期间，底层页面的指针事件被阻断。 */
/* z-index 低于擦除层的 210。 */
/* —— 自检转场 —— */
.boot {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(12, 14, 16, 0.82);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
  animation: fadeIn 0.3s var(--ease);
}
.boot[hidden] {
  display: none;
}
@keyframes fadeIn {
  from {
    opacity: 0;
  }
}
/* 自检面板：宽度上限 460px，窄屏保留 48px 边距。 */
/* 圆角 16px，半透明深色背景。 */
/* 铜金描边 + 双层阴影（下沉 + 外发光）。 */
/* overflow: hidden 让子区块不溢出圆角。 */
/* 面板内容由头部、日志、授权提示三部分组成。 */
/* 面板本身不设置高度，随日志行数自然伸展。 */
.boot-panel {
  width: min(460px, calc(100vw - 48px));
  border-radius: 16px;
  background: rgba(24, 27, 29, 0.92);
  border: 1px solid rgba(216, 155, 67, 0.25);
  box-shadow:
    0 40px 90px -30px rgba(0, 0, 0, 0.8),
    0 0 60px -20px rgba(216, 155, 67, 0.4);
  overflow: hidden;
}
/* 面板头部：图标 + 标题 + 旋转圈。 */
/* flex + gap 9px 横向排列。 */
/* padding 14px 18px，底部 1px 分隔线。 */
/* 等宽字体、--t-xs、0.1em 字距。 */
/* 青绿色与 HUD 的 LIVE SWEEP 一致。 */
/* .boot-spin 使用 margin-left: auto 推到最右。 */
.boot-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 14px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  font-family: var(--font-mono);
  font-size: var(--t-xs);
  letter-spacing: 0.1em;
  color: #7fb3ae;
}
.boot-head .ic {
  width: 15px;
  height: 15px;
}
/* 旋转圈：13px 圆环，铜金顶边。 */
/* 2px 边框，其余三边低透明度。 */
/* animation spin 0.8s linear infinite。 */
/* 只旋转 transform，性能友好。 */
/* reduced-motion 下动画关闭，圆环保持静止。 */
.boot-spin {
  margin-left: auto;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid rgba(216, 155, 67, 0.25);
  border-top-color: #d89b43;
  animation: spin 0.8s linear infinite;
}
/* spin：旋转一整圈。 */
/* linear 保证匀速。 */
/* 0.8s 的周期让加载感明显但不焦躁。 */
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
/* 日志区：等宽字体、--t-xs、行高 2.1。 */
/* padding 18px 与头部对齐。 */
/* min-height 150px 防止逐行出现时面板高度跳动。 */
/* 文字颜色 #c9c6bc，与 HUD 读数一致。 */
/* 日志行由 .bl 控制揭示状态。 */
/* 该区域不滚动，五行日志始终完整可见。 */
/* 行内使用 > 提示符与省略号模拟终端输出。 */
/* 背景透明，直接使用面板背景。 */
.boot-log {
  padding: 18px;
  font-family: var(--font-mono);
  font-size: var(--t-xs);
  line-height: 2.1;
  color: #c9c6bc;
  min-height: 150px;
}
/* 单行日志的初始态：透明 + 左移 8px。 */
/* transition 0.3s 让揭示时平滑滑入。 */
/* white-space: nowrap 保证单行不折行。 */
/* .on 类由 revealedCount 控制。 */
/* 未揭示的行仍占位，因此面板高度稳定。 */
/* 揭示顺序严格按照 BOOT_LINES 的索引。 */
.boot-log .bl {
  opacity: 0;
  transform: translateX(-8px);
  transition: all 0.3s var(--ease);
  white-space: nowrap;
}
/* 揭示态：不透明并回到原位。 */
/* 与 .bl 的初始态形成过渡。 */
/* 每行间隔 300ms，由脚本控制。 */
.boot-log .bl.on {
  opacity: 1;
  transform: none;
}
/* 日志结果值：绿色、600 字重。 */
/* 与 ACCESS GRANTED 的成功色一致。 */
/* 结果值包括「通过」「完成」「8 件」等。 */
.boot-log .bl b {
  color: #3bb273;
  font-weight: 600;
}
/* 日志中的弱化文本：提示符、省略号、补充说明。 */
/* 颜色 #6a675e，比正文更淡。 */
/* 与结果值的绿色形成信息层级。 */
.boot-log .bl .dim {
  color: #6a675e;
}
/* 授权提示：大字、粗体、绿色、大字距。 */
/* 初始透明并缩小到 0.9。 */
/* .on 时恢复不透明与原尺寸。 */
/* 过渡使用带轻微回弹的 cubic-bezier。 */
/* padding 0 18px 20px 与日志区对齐。 */
/* 字号 --t-2xl，是遮罩内最大的文字。 */
/* 中文说明使用 span 块级显示。 */
/* 该提示在日志全部揭示后出现。 */
.boot-grant {
  padding: 0 18px 20px;
  font-family: var(--font-mono);
  font-weight: 700;
  font-size: var(--t-2xl);
  letter-spacing: 0.14em;
  color: #3bb273;
  opacity: 0;
  transform: scale(0.9);
  transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.boot-grant.on {
  opacity: 1;
  transform: none;
}
/* 授权提示的中文说明：小字号、常规字重、三级墨色。 */
/* margin-top 4px 与主文案拉开。 */
/* letter-spacing 0.08em 保持仪表感。 */
/* 块级显示，独占一行。 */
.boot-grant span {
  display: block;
  font-size: var(--t-2xs);
  font-weight: 400;
  letter-spacing: 0.08em;
  color: var(--gc-ink-3);
  margin-top: 4px;
}
/* 横向擦除层：fixed 全屏，初始 scaleX(0)。 */
/* z-index: 210 高于自检遮罩。 */
/* transform-origin: left 让擦除从左向右展开。 */
/* pointer-events: none 不拦截点击。 */
/* 背景是深石墨到深蓝的斜向渐变。 */
/* .go 时 scaleX(1)，0.55s 完成。 */
/* 子元素 i 是右缘的铜金光条。 */
/* 该层只负责视觉，真正的跳转由脚本在动画后执行。 */
.wipe {
  position: fixed;
  inset: 0;
  z-index: 210;
  background: linear-gradient(120deg, #171a1c, #12203a);
  transform: scaleX(0);
  transform-origin: left;
  pointer-events: none;
}
/* 擦除展开态：scaleX(1)。 */
/* 过渡使用 cubic-bezier(.7,0,.3,1)，先快后慢。 */
/* 0.55s 的时长与脚本中 600ms 的等待配合。 */
/* 过渡只作用于 transform。 */
.wipe.go {
  transform: scaleX(1);
  transition: transform 0.55s cubic-bezier(0.7, 0, 0.3, 1);
}
/* 擦除层右缘的铜金光条：3px 宽。 */
/* 上下渐变透明，中间铜金，模拟扫光边缘。 */
/* box-shadow 提供外发光。 */
/* 绝对定位在擦除层右侧。 */
/* 随擦除层一起展开，不需要独立动画。 */
.wipe i {
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0;
  width: 3px;
  background: linear-gradient(180deg, transparent, #d89b43, transparent);
  box-shadow: 0 0 30px 6px rgba(216, 155, 67, 0.6);
}

/* 响应式断点一：1023px，左右两栏改为上下堆叠。 */
/* 响应式断点二：600px，遥测四列变两列并缩小间距。 */
/* 断点选择基于常见平板/手机宽度。 */
/* 所有调整只改布局与间距，不改变信息结构。 */
/* 雷达在窄屏下仍保持正方形，尺寸由 sizeRadar 重新计算。 */
/* 状态条在窄屏下减小 gap 与字号，避免换行。 */
/* 侧标在 1023px 以下隐藏。 */
/* 自检面板在窄屏下使用 calc(100vw - 48px) 保留边距。 */
/* —— 响应式 —— */
/* 平板及以下：根容器改为单列。 */
/* 左栏变成上方区块，最小高度 52vh。 */
/* 左栏右边框改为下边框。 */
/* 右栏减小内边距。 */
/* 竖排侧标隐藏。 */
/* 雷达尺寸由 ResizeObserver 在布局变化后重新计算。 */
@media (max-width: 1023px) {
  .cl {
    grid-template-columns: 1fr;
  }
  .cl-left {
    min-height: 52vh;
    border-right: none;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  }
  .cl-right {
    padding: 40px 24px 70px;
  }
  .cl-rail {
    display: none;
  }
}
/* 手机：遥测四列变两列。 */
/* 登录卡片减小内边距。 */
/* 状态条减小 gap 与字号。 */
/* HUD 减小 gap 与 padding。 */
/* 主标题降低一档字号。 */
/* 这些调整保证 320px 宽度下仍可完整操作。 */
@media (max-width: 600px) {
  .cl-telemetry {
    grid-template-columns: repeat(2, 1fr);
  }
  .cl-card {
    padding: 28px 22px;
  }
  .cl-status {
    gap: 14px;
    font-size: 10px;
  }
  .cl-hud {
    gap: 12px;
    padding: 14px 18px 0;
  }
  .cl-title {
    font-size: var(--t-3xl);
  }
}
/* reduced-motion 降级：关闭所有装饰性动画。 */
/* 覆盖：背景光晕、背景光斑、HUD 状态灯、状态条脉冲、自检旋转圈。 */
/* 使用 animation: none !important 确保覆盖前面的动画声明。 */
/* 雷达扫描与遥测缓动由脚本中的 reducedMotion 分支控制，不在这里处理。 */
/* 自检遮罩由脚本直接跳过，因此不会出现静态遮罩残留。 */
/* 抖动反馈未在此关闭，因为它是短暂、低幅度且承载错误语义的反馈。 */
/* 关闭动画后，所有元素仍保持最终可见状态，不影响信息读取。 */
/* 该媒体查询放在最后，确保能覆盖前面的动画声明。 */
/* 若未来新增纯 CSS 动画，应同步加入这里的禁用列表。 */
/* 登录卡片入场动画 clIn 未列出，因为它只播放一次且幅度很小。 */
@media (prefers-reduced-motion: reduce) {
  .cl-glow,
  .cl-mesh i,
  .hud-dot,
  .st-dot,
  .boot-spin {
    animation: none !important;
  }
}
</style>
