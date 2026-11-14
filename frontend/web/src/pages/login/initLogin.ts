/**
 * 文件职责：居民登录页的原生交互引擎（initLogin）。
 *
 * 该文件从 ToolShare 原型包 `01-居民登录.html` 一比一移植，负责：
 * - 登录视图内的机械装置动画（charge 单一状态量驱动齿圈/进度弧/刻度/指针/读数/光晕）；
 * - 拖动上紧、点击变形、键盘上紧；
 * - 密码显示/隐藏的百叶窗效果；
 * - 登录成功后的螺栓下落 + 齿轮虹膜转场；
 * - 轻量音效、火花/螺母粒子、尘埃背景；
 * - 表单校验、验证码倒计时与真实的登录/发码桥接。
 *
 * 设计原则：
 * - 所有视觉状态由 charge(0..1) 与少量状态机变量驱动，避免多源真相；
 * - 动画使用 requestAnimationFrame，页面隐藏时暂停；
 * - 尊重 prefers-reduced-motion：减少粒子与转场动画；
 * - 原生 DOM 操作集中在 initLogin 内，React 只负责挂载/卸载；
 * - 所有网络请求通过 LoginBridge 注入，便于测试与替换。
 *
 * 生命周期：
 * - initLogin 返回清理函数，移除事件监听、定时器、覆盖层与音效控件；
 * - 登录成功转场完成后覆盖层会被移除；
 * - 页面卸载时清理函数负责释放所有资源。
 *
 * 注意：本文件不包含 JSX，也不直接依赖 React。
 */
/**
 * 居民登录页交互 —— 从 ToolShare 原型包 01-居民登录.html 一比一移植。
 * 唯一连续状态量 charge(0..1) 驱动齿圈 / 进度弧 / 8 格刻度 / 指针 / 读数 / 光晕；
 * 其余交互（拖动上紧、点击变形、密码遮罩百叶窗、键盘上紧、齿轮虹膜转场）全部保留。
 */
import { bindSoundToggle, SoundFX, toast } from '@/lib/fx'
import { OVERLAY_HTML } from './loginViewHtml'

// 登录模式：
// code 表示手机号 + 验证码；pwd 表示手机号 + 密码。
// 与 UI 的两个标签一一对应。
// 切换模式只改变表单字段显示，不刷新页面。
// 该类型同时用于桥接参数与内部状态。
export type LoginMode = 'code' | 'pwd'

// 登录表单值：
// mode 决定使用 code 还是 password；
// phone 始终必填；
// code 与 password 在不同模式下使用，另一个可为空。
// 该对象只在提交时构造，不持久化。
// 密码不会写入 localStorage。
// 验证码只在本次会话内使用。
// 桥接层根据 mode 选择真实接口。
// 该类型与后端请求体解耦。
// 便于后续新增登录方式时扩展。
// 所有字段都是字符串，避免输入框值转换。
// 空字符串表示未填写。
// 校验在 onSubmit 中完成。
export interface LoginValues {
  mode: LoginMode
  phone: string
  code: string
  password: string
}

// LoginBridge 是 React 页面与原生交互层之间的契约：
// - adminUrl 用于改写管理端入口链接；
// - login 执行真实登录并返回 token，但不写 store；
// - sendCode 发送验证码，Mock 环境可返回验证码；
// - enter 在转场盖满屏幕后写入登录态并跳转。
// 把副作用拆成 login 与 enter 两个阶段，
// 是为了让转场动画不被登录态变化提前卸载。
// 页面组件负责实现该接口，initLogin 只消费。
// 失败统一返回 { ok:false, message }，避免原生层处理异常类型。
// 成功结果使用 as const 保持可判别联合。
// 该接口不依赖 React，便于在测试中替换。
// 不在此处暴露 token 之外的敏感信息。
// 所有方法都是异步或同步的一次性调用。
export interface LoginBridge {
  /** 管理端入口地址 */
  adminUrl: string
  /** 真实登录：只验证并返回 token，不写 store（保证转场不被提前卸载打断） */
  login(values: LoginValues): Promise<{ ok: true; token: string } | { ok: false; message: string }>
  /** 发送验证码，返回 MOCK 验证码（便于演示） */
  sendCode(phone: string): Promise<{ ok: true; code?: string } | { ok: false; message: string }>
  /** 转场盖满屏幕后：写入登录态并跳转 */
  enter(token: string): void
}

// 眼睛图标（显示密码）：
// 以内联 SVG 字符串形式保存，便于直接写入按钮 innerHTML。
// 与登录视图中的其他图标风格一致。
// 不包含事件绑定。
// 切换密码可见性时与 ICON_EYE_OFF 互换。
// SVG 使用 currentColor，颜色由按钮样式控制。
// 尺寸由 .ic 类控制。
// 该常量不会被修改。
// 只用于密码输入框右侧按钮。
const ICON_EYE =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg>'
// 眼睛图标（隐藏密码）：
// 带一条斜线，表示当前密码可见，点击可隐藏。
// 与 ICON_EYE 成对使用。
// 同样使用 currentColor 与 .ic 尺寸。
// 通过 innerHTML 替换，不创建 React 节点。
// 不包含可访问名称，名称由按钮 aria-label 提供。
// 字符串内容固定，不拼接用户输入。
// 切换时同时更新 aria-label。
const ICON_EYE_OFF =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7c2 0 3.7.6 5.2 1.5M22 12s-3.5 7-10 7c-2 0-3.7-.6-5.2-1.5"/><path d="M4 4l16 16"/></svg>'

/**
 * 初始化登录页交互。
 *
 * @param host 登录视图的宿主元素（包含 LOGIN_VIEW_HTML 注入的 DOM）。
 * @param bridge 与 React/API 层交互的桥接对象。
 * @returns 清理函数；调用后移除事件监听、定时器、覆盖层与音效控件。
 *
 * 副作用：
 * - 改写管理端/协议链接；
 * - 向 body 追加转场覆盖层、音效控件与 fx 层；
 * - 绑定窗口/表单/装置事件；
 * - 启动 requestAnimationFrame 主循环与尘埃动画；
 * - 在登录成功时播放转场并调用 bridge.enter。
 *
 * 若宿主中找不到登录视图或核心装置，函数会尽早返回空清理函数。
 * 所有 DOM 查询都限定在 host 内（覆盖层除外）。
 * 该函数是命令式的，不返回任何 UI 状态。
 */
export function initLogin(host: HTMLElement, bridge: LoginBridge): () => void {
  // 查找登录视图根节点：
  // 找不到时说明注入失败或结构变化，直接返回空清理函数。
  // 后续所有查询都限定在该节点内。
  // 覆盖层与音效控件挂在 body，不受此限制。
  const loginView = host.querySelector<HTMLElement>('#loginView')
    // 结构缺失时安全退出：
    // 不抛错，避免整页白屏。
    // 返回空函数以保持调用方契约一致。
  if (!loginView) return () => {}

  // 是否偏好减少动态效果：
  // 为 true 时跳过粒子、尘埃与大部分转场动画。
  // 这是无障碍要求，不是可选优化。
  // 音效仍由用户开关控制。
  // 该值在初始化时读取一次。
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  /* ---------------- 转场覆盖层（挂在 body 上，导航后仍可用） ---------------- */
  // 创建转场覆盖层：
  // 挂到 body 而不是 host，保证导航后仍能显示。
  // 内容来自 OVERLAY_HTML，包含螺栓与虹膜 SVG。
  // 覆盖层初始隐藏，由 playTransition 控制。
  // 登录失败时不会显示。
  // 清理函数会移除整个覆盖层。
  // 覆盖层内还包含音效开关。
  // 覆盖层容器使用 ts-login-overlay 类，
  // 样式由 login.css 控制。
  // 容器本身不拦截点击，除非内部元素可见。
  // 不包含用户数据。
  const overlayWrap = document.createElement('div')
  overlayWrap.className = 'ts-login-overlay'
  // 注入覆盖层结构：
  // 包括螺栓层与虹膜层。
  // 注入后立即查询关键节点，避免重复查询。
  // 内容来自受信任的原型字符串。
  overlayWrap.innerHTML = OVERLAY_HTML
  // 挂载到 body：
  // 保证页面路由切换后覆盖层仍存在。
  // 由清理函数负责移除。
  document.body.appendChild(overlayWrap)
  // 螺栓层：
  // 登录成功时先显示，模拟“拧下螺栓”。
  // 初始 hidden，由 playTransition 打开。
  const boltLayer = overlayWrap.querySelector<HTMLElement>('#boltLayer')!
  // 大螺栓 SVG：
  // 通过添加 drop/r1/r2/fade 类播放动画。
  // 动画结束后隐藏。
  const boltBig = overlayWrap.querySelector<SVGElement>('.bolt-big')!
  // 虹膜覆盖层：
  // 由齿轮形状的 mask 逐渐放大盖满屏幕。
  // 初始 hidden。
  const irisCover = overlayWrap.querySelector<HTMLElement>('#irisCover')!
  // 虹膜齿轮路径：
  // 通过 transform 缩放实现展开。
  // 尺寸根据视口对角线计算。
  const irisGear = overlayWrap.querySelector<SVGElement>('#irisGear')!

  // 查找或创建 fx 层：
  // 用于承载火花与螺母粒子。
  // 若页面已有（例如其他组件创建），直接复用。
  // 否则创建一个 id=fxLayer 的容器。
  // 清理时只在本次创建时移除？当前实现总是移除。
  // 该层不拦截交互。
  let fx = document.getElementById('fxLayer') as HTMLElement | null
  if (!fx) {
    // 创建 fx 层：
    // 固定定位、铺满视口，由样式层控制。
    // pointer-events 应为 none。
    // 粒子通过绝对定位放置。
    fx = document.createElement('div')
    fx.className = 'fx-layer'
    fx.id = 'fxLayer'
    document.body.appendChild(fx)
  }
  // 保存 fx 层引用，
  // 避免每次生成粒子时重新查询。
  // 该引用在初始化期间稳定。
  // 清理时移除。
  const fxLayer = fx

  // 给 body 添加 login-page 类：
  // 用于登录页专属的全局样式（隐藏滚动条等）。
  // 清理时移除。
  // 该类不影响其他页面。
  // 与覆盖层样式配合。
  // 不修改 body 的其他类。
  document.body.classList.add('login-page')

  /* 顶部音效开关（原型位于登录视图之外，为全站唯一全局控件） */
  // 创建音效开关：
  // 原型中位于登录视图之外，是全站唯一全局控件。
  // 使用 icon-btn sound-fab 类，固定在页面角落。
  // 点击由 bindSoundToggle 绑定。
  // 开关状态由 SoundFX 持久化管理。
  // 清理时随覆盖层一起移除。
  // 该按钮不提交表单。
  // 仅影响本地音效，不发请求。
  // 音效按钮的 id 固定为 soundToggle，
  // 方便布局层在进入主应用后重新绑定。
  // title/aria-label 由 bindSoundToggle 或样式提供。
  // 按钮内容由 CSS 绘制。
  // 不使用 React 状态。
  const soundBtn = document.createElement('button')
  soundBtn.className = 'icon-btn sound-fab'
  soundBtn.id = 'soundToggle'
  soundBtn.title = '音效'
  overlayWrap.appendChild(soundBtn)
  // 绑定音效开关：
  // 由 lib/fx 统一处理图标与状态切换。
  // 该调用会注册点击监听。
  // 清理时通过移除按钮本身释放。
  // 不在此处保存绑定结果。
  bindSoundToggle(soundBtn)

  /* 静态链接改到 SPA 路由 / 管理端 */
  // 静态链接改写：
  // 管理端入口指向 bridge.adminUrl（开发本地/生产 /admin/）。
  // 协议链接指向 /ai 页面。
  // 原型中的 HTML 文件名在 SPA 中不存在，必须改写。
  // 找不到链接时安全跳过。
  // 只修改 href，不改文案。
  // 这些链接不在登录流程内。
  const adminLink = host.querySelector<HTMLAnchorElement>('.admin-entry')
  // 管理端入口：
  // href 由 bridge 提供，可能是完整 URL 或站内路径。
  // 不添加 target，保持当前窗口行为。
  // 点击会离开居民端。
  // 该链接不携带登录态。
  // 不影响表单校验。
  if (adminLink) adminLink.href = bridge.adminUrl
  // 协议链接：
  // 统一指向 /ai 页面（当前占位）。
  // 使用 setAttribute 而非直接赋值，保持属性形式。
  // 找不到链接时跳过。
  // 不打开新窗口。
  // 与登录/注册无关。
  // 后续可替换为真实协议页面。
  host.querySelectorAll<HTMLAnchorElement>('.lc-foot a').forEach((a) => a.setAttribute('href', '/ai'))

  // 音效实现：
  // 使用 Web Audio API 合成短音，不加载音频文件。
  // 所有音效都受 SoundFX.isOn() 控制。
  // AudioContext 惰性创建，首次用户交互后恢复。
  // 音量很低，避免打扰。
  // 不同动作使用不同波形/频率。
  // 浏览器不支持时静默降级。
  // 不阻塞主线程。
  // 清理时不显式关闭 AudioContext（页面卸载后由浏览器回收）。
  // 该实现只用于登录页反馈。
  // 不用于通知或语音。
  /* ---------------- 音效（克制，绑定可见动作） ---------------- */
  // 惰性 AudioContext：
  // 首次调用时创建，兼容 webkit 前缀。
  // 创建失败时返回 null，后续调用静默跳过。
  // 若处于 suspended，尝试 resume。
  // 每个页面只有一个实例。
  // 不在构造时立即创建，避免自动播放策略警告。
  // 不持久化。
  // 不跨页面共享。
  // 所有音效共用该上下文。
  // 清理时不关闭，避免影响其他页面音效。
  // 该变量在初始化作用域内。
  let actx: AudioContext | null = null
  // 获取/创建 AudioContext：
  // 使用 window.AudioContext 或 webkit 前缀。
  // 构造函数可能抛错（隐私模式等），捕获后返回 null。
  // suspended 时调用 resume，恢复播放。
  // 返回的上下文可能仍不可用，调用方需判空。
  // 该函数无副作用之外的状态变更。
  // 首次调用会创建上下文。
  // 不在每次 beep 时重复创建。
  // 不处理设备切换。
  // 不设置全局音量。
  function ac(): AudioContext | null {
    if (!actx) {
      try {
        actx = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)()
      } catch {
        /* noop */
      }
    }
  // 恢复被浏览器挂起的上下文：
  // 自动播放策略要求用户交互后才能播放。
  // 登录页的点击/输入会触发该调用。
  // resume 返回 Promise，但不等待。
  // 失败时静默，不影响交互。
  // 不在此处处理错误提示。
  // 不重试。
  // 仅对 suspended 状态调用。
  // 不修改全局状态。
  // 该调用幂等。
    if (actx && actx.state === 'suspended') actx.resume()
    return actx
  }
  // 合成一个短音：
  // @param f 频率（Hz）
  // @param d 持续时间（秒）
  // @param type 波形
  // @param g 峰值增益
  // 使用 oscillator + gain 包络，避免爆音。
  // 先线性上升到 g，再指数衰减到接近 0。
  // 音效总时长略长于 d，确保完全衰减。
  // 关闭音效时直接返回。
  // 上下文不可用时返回。
  // 不创建持久节点。
  // 每次调用创建并销毁节点。
  // 不等待播放完成。
  // 不处理并发限制。
  function beep(f: number, d: number, type: OscillatorType = 'sine', g = 0.04) {
    if (!SoundFX.isOn()) return
    const c = ac()
    if (!c) return
    const o = c.createOscillator()
    const v = c.createGain()
    o.type = type
    o.frequency.value = f
    v.gain.setValueAtTime(0, c.currentTime)
    v.gain.linearRampToValueAtTime(g, c.currentTime + 0.01)
    v.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + d)
    o.connect(v)
    v.connect(c.destination)
    o.start()
    o.stop(c.currentTime + d + 0.02)
  }
  // 音效集合：
  // click 轻点、success 成功、error 失败、pop 切换、
  // servo 变形伺服声、ratchet 上紧棘轮声。
  // 每个方法只调用 beep 或自定义包络。
  // 所有方法都检查 SoundFX.isOn()。
  // 不返回音频节点。
  // 不记录播放历史。
  // 不参与业务状态。
  // 音量经过调校，保持克制。
  // 失败音使用三角波，更易辨识。
  // 成功音使用两个上行音符。
  const snd = {
    click: () => beep(1500, 0.06, 'sine', 0.025),
    success: () => {
      beep(880, 0.1, 'sine', 0.04)
      setTimeout(() => beep(1320, 0.14, 'sine', 0.04), 90)
    },
    error: () => beep(220, 0.18, 'triangle', 0.05),
    pop: () => beep(660, 0.07, 'sine', 0.03),
    servo() {
      if (!SoundFX.isOn()) return
      const c = ac()
      if (!c) return
      const o = c.createOscillator()
      const v = c.createGain()
      o.type = 'sawtooth'
      o.frequency.setValueAtTime(220, c.currentTime)
      o.frequency.exponentialRampToValueAtTime(660, c.currentTime + 0.16)
      v.gain.setValueAtTime(0.0001, c.currentTime)
      v.gain.linearRampToValueAtTime(0.022, c.currentTime + 0.03)
      v.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + 0.2)
      o.connect(v)
      v.connect(c.destination)
      o.start()
      o.stop(c.currentTime + 0.22)
    },
    ratchet() {
      if (!SoundFX.isOn()) return
      const c = ac()
      if (!c) return
      const o = c.createOscillator()
      const v = c.createGain()
      o.type = 'square'
      o.frequency.value = 190
      v.gain.setValueAtTime(0.03, c.currentTime)
      v.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + 0.05)
      o.connect(v)
      v.connect(c.destination)
      o.start()
      o.stop(c.currentTime + 0.06)
    },
  }

  // 基础工具：
  // $/$$ 是限定 host 的查询封装；
  // lerp 线性插值；
  // clamp 限制范围；
  // rnd 生成区间随机数；
  // easeInOut 三次缓动。
  // 这些函数无副作用。
  // 不使用第三方动画库，保持零依赖。
  // 所有数值计算使用 number。
  // 动画循环中高频调用，保持简单。
  // 不处理 NaN 输入。
  // 输入应为有效数值。
  /* ---------------- 基础工具 ---------------- */
  // 查询单个元素：
  // 限定在 host 内，避免命中页面其他区域。
  // 泛型允许调用方指定元素类型。
  // 找不到时返回 null。
  // 不抛错。
  // 高频调用时应缓存结果。
  // 覆盖层元素不走该函数。
  // 查询结果不自动监听变化。
  // 适用于初始化时的静态结构。
  // 不处理 Shadow DOM。
  // 类型参数只影响 TS 推断。
  const $ = <T extends Element = HTMLElement>(sel: string) => host.querySelector<T>(sel)
  // 查询多个元素：
  // 返回真实数组而非 NodeList，便于 forEach/map。
  // 限定在 host 内。
  // 元素顺序与 DOM 顺序一致。
  // 空结果返回空数组。
  // 不抛错。
  // 高频调用时会产生临时数组。
  // 初始化阶段使用一次。
  // 结果不随 DOM 变化自动更新。
  // 泛型允许指定元素类型。
  // 不处理 Shadow DOM。
  const $$ = <T extends Element = HTMLElement>(sel: string) => Array.from(host.querySelectorAll<T>(sel))
  // 线性插值：
  // t=0 返回 a，t=1 返回 b。
  // 不限制 t 范围，调用方自行 clamp。
  // 用于平滑跟随鼠标/目标值。
  // 无副作用。
  // 数值稳定性依赖输入。
  // 不处理非数值。
  // 高频调用。
  // 动画循环核心工具。
  // 不产生对象。
  // 可安全内联。
  const lerp = (a: number, b: number, t: number) => a + (b - a) * t
  // 限制数值范围：
  // 返回 min/max 之间的值。
  // 用于鼠标归一化、charge 限制等。
  // 无副作用。
  // 不处理 NaN。
  // 不改变输入。
  // 高频调用。
  // 与 Math.min/max 组合等价。
  // 保持可读性。
  // 不抛错。
  // 支持负数区间。
  const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v))
  // 区间随机数：
  // 返回 [a,b) 之间的数。
  // 用于粒子方向、速度、尺寸。
  // 无副作用但使用 Math.random。
  // 不保证密码学安全。
  // 粒子效果不要求可复现。
  // 高频调用。
  // 不处理 a>b。
  // 调用方保证区间合法。
  // 返回浮点数。
  // 不缓存随机源。
  const rnd = (a: number, b: number) => a + Math.random() * (b - a)
  // 三次缓入缓出：
  // p<0.5 时加速，p>=0.5 时减速。
  // 返回值在 0..1 附近。
  // 用于虹膜展开动画。
  // 无副作用。
  // 不限制输入范围。
  // 输入超出 0..1 时结果可能超出。
  // 调用方保证 p 在 0..1。
  // 高频调用。
  // 不处理 NaN。
  // 与 CSS ease-in-out 类似。
  function easeInOut(p: number) {
    return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2
  }

  /* ---------------- 火花 / 螺母粒子 ---------------- */
  /**
   * 生成火花粒子。
   *
   * @param x 屏幕坐标 x（视口像素）。
   * @param y 屏幕坐标 y。
   * @param n 粒子数量。
   *
   * 副作用：向 fx 层追加 n 个 span，并注册 animationend 自动移除。
   * 粒子颜色在四种暖色中循环。
   * 方向随机、距离 24–64px、带随机旋转。
   * 使用 CSS 变量 --dx/--dy/--rot/--c 驱动动画。
   * reduced 模式下直接返回，不生成粒子。
   * 每个粒子独立移除，不集中清理。
   * 高峰时可能产生较多 DOM，但生命周期很短。
   * 不处理坐标越界。
   * 不限制同时存在的粒子总数。
   * 不参与业务状态。
   */
  function spawnSparks(x: number, y: number, n: number) {
    // reduced 模式跳过：
    // 粒子属于装饰性动画，可被无障碍设置关闭。
    // 直接返回，不创建 DOM。
    if (reduced) return
    // 颜色集合：
    // 品牌黄、铜棕、亮金与近白。
    // 通过模运算循环使用。
    const colors = ['#F7A600', '#C77B3F', '#FFD98A', '#FFFDF7']
    // 逐个生成粒子：
    // 每颗独立随机方向与距离。
    // 使用 span 承载纯装饰内容。
    for (let i = 0; i < n; i++) {
    // 创建粒子节点：
    // class=spark 由样式层定义动画。
    // 不设置文本内容。
      const s = document.createElement('span')
      s.className = 'spark'
    // 随机方向与距离：
    // 方向覆盖完整圆周。
    // 距离决定飞散范围。
      const a = rnd(0, Math.PI * 2)
      const d = rnd(24, 64)
      s.style.left = x + 'px'
    // 定位到触发点：
    // left/top 使用视口坐标。
    // 粒子绝对定位于 fx 层。
    // fx 层应固定铺满视口。
      s.style.top = y + 'px'
      s.style.setProperty('--dx', Math.cos(a) * d + 'px')
      s.style.setProperty('--dy', Math.sin(a) * d + 'px')
    // 写入动画变量：
    // --dx/--dy 控制位移终点；
    // --rot 控制旋转；
    // --c 控制颜色。
    // 具体动画由 CSS 完成。
      s.style.setProperty('--rot', rnd(-200, 200) + 'deg')
      s.style.setProperty('--c', colors[i % colors.length])
      fxLayer.appendChild(s)
    // 动画结束自动移除：
    // 避免粒子长期占用 DOM。
    // 不依赖统一清理。
    // 页面卸载时 fx 层整体移除。
      s.addEventListener('animationend', () => s.remove())
    }
  }
  /**
   * 生成螺母粒子（登录成功时）。
   *
   * @param x 屏幕坐标 x。
   * @param y 屏幕坐标 y。
   * @param n 粒子数量。
   *
   * 副作用：向 fx 层追加 n 个 span，animationend 后移除。
   * 方向只取上半圆，配合重力感下落。
   * 距离 50–150px，随机旋转。
   * 颜色在钢灰/铜棕/品牌黄之间循环。
   * reduced 模式下直接返回。
   * 比火花更大、更重，用于成功反馈。
   * 不限制粒子总数。
   * 不处理坐标越界。
   * 不参与业务状态。
   */
  function spawnNuts(x: number, y: number, n: number) {
    // reduced 模式跳过：
    // 成功动画的粒子部分可被无障碍设置关闭。
    // 音效仍可能播放。
    if (reduced) return
    // 逐个生成螺母：
    // 每个粒子独立随机方向与距离。
    // 使用 span 承载纯装饰内容。
    for (let i = 0; i < n; i++) {
    // 创建节点：
    // class=nut 由样式层定义形状与动画。
    // 不设置文本内容。
      const s = document.createElement('span')
      s.className = 'nut'
    // 方向限制在上半圆：
    // 与“螺母被崩飞后下落”的直觉一致。
    // 距离越大，飞散越远。
      const a = rnd(-Math.PI, 0)
      const d = rnd(50, 150)
      s.style.left = x + 'px'
    // 定位到触发点：
    // 使用视口坐标。
    // 绝对定位于 fx 层。
      s.style.top = y + 'px'
      s.style.setProperty('--dx', Math.cos(a) * d + 'px')
      s.style.setProperty('--dy', Math.sin(a) * d + rnd(60, 140) + 'px')
    // 写入动画变量：
    // --dx/--dy 控制位移；
    // --rot 控制旋转；
    // 背景色在三种金属色中循环。
      s.style.setProperty('--rot', rnd(-360, 360) + 'deg')
      s.style.background = ['#8C97A3', '#C77B3F', '#F7A600'][i % 3]
      fxLayer.appendChild(s)
    // 动画结束自动移除：
    // 避免长期占用 DOM。
    // 页面卸载时 fx 层整体移除。
      s.addEventListener('animationend', () => s.remove())
    }
  }

  /* ---------------- 舞台 / 装置元素 ---------------- */
  // 舞台与装置元素缓存：
  // 初始化时一次性查询，避免动画循环中反复 querySelector。
  // 这些引用在页面生命周期内稳定（结构不会动态替换）。
  // 查询失败的元素可能为 null，使用处需判空。
  // 覆盖层元素单独查询，不在 host 内。
  // 元素引用不持有 React 状态。
  // 所有动画通过 style/class 直接操作。
  // 不监听这些元素的属性变化。
  // 不创建 MutationObserver。
  // 查询失败时对应动画部分静默跳过。
  // 视差层：
  // 带 data-depth 的元素随鼠标轻微位移。
  // 深度值越大，位移越明显。
  // spotlight 用于旋转光斑。
  const layers = $$<HTMLElement>('.layer[data-depth]')
  const spot = $<HTMLElement>('.spotlight')
  // 核心装置：
  // mascot 是整体容器，负责倾斜与缩放；
  // cogSvg 用于测量装置位置；
  // pupilL/R 是眼睛，跟随鼠标；
  // cogHead/cogRoot 控制头部/整体倾斜；
  // cogRim 是齿圈，随 charge 旋转；
  // chargeArc 是进度弧；
  // needle/readout 是指针与百分比读数；
  // coreHalo/coreBounce 是光晕与成功弹跳；
  // coreTicks/coreLamps/corePips 是刻度、指示灯与提示点；
  // hint/hintStrong 是操作提示文案。
  const mascot = $<HTMLElement>('#coreDevice')!
  const cogSvg = $<SVGElement>('#cogSvg')
  const pupilL = $<SVGElement>('#pupilL')
  const pupilR = $<SVGElement>('#pupilR')
  const cogHead = $<SVGElement>('#cogHead')
  const cogRoot = $<SVGElement>('#cogRoot')
  const cogRim = $<SVGElement>('#cogRim')
  const chargeArc = $<SVGCircleElement>('.charge-arc')
  const needle = $<SVGElement>('.core-needle')
  const readout = $<SVGTextElement>('.core-readout')
  const coreHalo = $<SVGElement>('.core-halo')
  const coreBounce = $<SVGElement>('.core-bounce')
  const coreTicks = $$<SVGLineElement>('.core-tick')
  const coreLamps = $$<SVGCircleElement>('.core-lamp')
  const corePips = $$<HTMLElement>('.core-pips i')
  const hint = $<HTMLElement>('.core-hint')
  const hintStrong = hint?.querySelector('strong') ?? null
  // 变形舞台：
  // morphStage 在点击装置时显示工具变形动画；
  // morphTools 是八个工具 SVG 分组。
  // 初始化时查询一次。
  const morphStage = $<HTMLElement>('#morphStage')
  const morphTools = $$<HTMLElement>('.morph-tool')

  // 核心结构缺失时安全退出：
  // 没有装置就无法播放上紧/变形动画。
  // 返回空清理函数，保持契约。
  // 不抛错，避免整页不可用。
  if (!mascot || !morphStage) return () => {}

  /* ---------------- charge 状态机 ---------------- */
  // 装置状态机：
  // idle 默认、watch 输入聚焦、shy 密码聚焦、
  // loading 登录中、success 登录成功。
  // 状态影响眼睛偏移、倾斜与提示。
  // 状态切换由 setState 统一处理 class。
  // 不直接操作 DOM class。
  // loading/success 会把 charge 目标抬高。
  // 错误不会进入独立状态，而是短暂 error class。
  // 状态只存在内存。
  let state: 'idle' | 'watch' | 'shy' | 'loading' | 'success' = 'idle'
  // 当前登录模式：
  // 与表单标签同步。
  // 影响校验分支与字段显示。
  let mode: LoginMode = 'code'
  // 变形动画索引：
  // -1 表示未变形。
  // 取值 0..7，对应八个工具。
  let morphIdx = -1
  // 是否正在变形：
  // 防止重复触发。
  // 变形期间拖拽/点击逻辑会跳过。
  let morphing = false
  // 变形主计时器：
  // 用于展示工具并自动结束。
  // 清理时清除。
  let morphTimer = 0
  // 变形隐藏计时器：
  // 用于淡出工具并恢复装置。
  // 清理时清除。
  let morphHideTimer = 0
  // 是否正在转场：
  // 登录成功播放覆盖层动画时为 true。
  // 转场期间忽略拖拽/点击/提交。
  let transiting = false
  // charge 是核心连续状态量（0..1）：
  // 驱动齿圈、进度弧、刻度、指针、读数与光晕。
  // 拖动/点击/键盘都会增加。
  // 登录中/成功时目标值会被抬高。
  // 错误时会回退少量。
  // 达到 1 时触发 onCharged。
  let charge = 0
  // 渲染用 charge：
  // 通过插值平滑逼近 charge，避免跳变。
  // reduced 模式下直接使用目标值。
  // 只用于绘制，不影响逻辑判断。
  let renderCharge = 0
  // 鼠标接近度（0..1）：
  // 用于装置轻微放大的效果。
  // 平滑插值。
  let proximity = 0
  // 拖拽速度：
  // 用于额外加快齿圈旋转。
  // 松手后逐渐归零。
  let dragVel = 0
  // 齿圈累计角度：
  // 每帧按 charge 与拖拽速度递增。
  // 不归零，保持连续旋转。
  let rimAngle = 0
  // 上一次显示的百分比：
  // 仅在整数变化时更新 DOM，减少重排。
  let lastPct = -1
  // 上一次点亮的刻度数：
  // 仅在变化时切换 class。
  let lastTick = -1
  // 是否已触发“上紧完成”：
  // 防止 charge=1 时重复播放成功反馈。
  // mascotError 会重置。
  let chargedFired = false
  // 入场动画进度（0..1）：
  // 首次加载时短暂抬升 charge，形成“启动”效果。
  let introT = 0
  // 入场动画开始时间：
  // 使用 performance.now()，与 rAF 时间基准一致。
  const introStart = performance.now()
  // 入场动画是否完成：
  // 完成后不再覆盖目标 charge。
  let introDone = false
  // 鼠标视口坐标：
  // 初始化在屏幕中心。
  // 由 pointermove 更新。
  // 用于视差、眼睛跟随与距离计算。
  let mouseX = innerWidth / 2
  let mouseY = innerHeight / 2
  // 归一化鼠标坐标（-1..1）：
  // 由舞台矩形计算。
  // 用于视差与倾斜。
  let nx = 0
  let ny = 0
  // 眼睛插值后的偏移：
  // 左右眼分别平滑。
  // 用于跟随鼠标。
  let plx = 0
  let ply = 0
  let prx = 0
  let pry = 0
  // 头部倾斜与整体倾斜：
  // idle 时随鼠标轻微倾斜。
  // 其他状态归零。
  let headTilt = 0
  let rootLean = 0
  // 整体位移：
  // 随鼠标接近度产生轻微平移与缩放。
  let leanX = 0
  let leanY = 0
  // 缓存的矩形：
  // stage 与 cogSvg 的 boundingClientRect。
  // 在 resize 时重新测量。
  // null 表示尚未测量，使用兜底值。
  let stageRect: DOMRect | null = null
  let cogRect: DOMRect | null = null

  /**
   * 重新测量舞台与装置的矩形。
   *
   * 副作用：写入 stageRect/cogRect 缓存。
   * 在初始化与窗口 resize 时调用。
   * 不触发布局写入，只读取。
   * 找不到元素时保持 null。
   * 坐标用于视差、拖拽中心与粒子位置。
   * 不缓存滚动位置，rect 已包含视口偏移。
   */
  function measureRects() {
    // 查询舞台元素：
    // 用于计算鼠标归一化坐标。
    // 找不到时 stageRect 保持 null。
    const st = $('.stage')
    // 查询装置 SVG：
    // 用于拖拽中心与粒子发射点。
    // 找不到时 cogRect 保持 null。
    if (st) stageRect = st.getBoundingClientRect()
    if (cogSvg) cogRect = cogSvg.getBoundingClientRect()
  }

  /**
   * 增加 charge。
   *
   * @param d 增量（可为负）。
   * @param quiet 是否静音/无粒子。
   *
   * 副作用：
   * - 更新 charge 并按 8 格判断是否播放棘轮声/火花；
   * - 达到 1 时触发 onCharged（仅一次）。
   * 使用 floor(charge*8) 判断刻度变化，避免每帧都播放声音。
   * charge 始终限制在 0..1。
   * quiet 用于静默调整（模式切换、演示填充）。
   * 不直接更新 DOM，绘制由主循环完成。
   */
  function addCharge(d: number, quiet?: boolean) {
    // 变化前的刻度索引：
    // 以 1/8 为一格。
    // 用于判断是否跨格。
    const before = Math.floor(charge * 8 + 1e-6)
    charge = clamp(charge + d, 0, 1)
    // 变化后的刻度索引：
    // 跨格时播放棘轮声与火花。
    const after = Math.floor(charge * 8 + 1e-6)
    if (after > before) {
    // 跨格反馈：
    // 声音每格一次；
    // 非静音且非 reduced 时喷出少量火花。
    // 火花位置取装置中心偏上。
      snd.ratchet()
      if (!quiet && !reduced && cogRect) spawnSparks(cogRect.left + cogRect.width * 0.5, cogRect.top + cogRect.height * 0.36, 3)
    }
    if (charge >= 0.999 && !chargedFired) {
      chargedFired = true
      onCharged()
    // 达到满格：
    // 只触发一次 onCharged。
    // chargedFired 由 mascotError 重置。
    // 不在此处重置 charge。
    }
  }

  /**
   * charge 达到 1 时的成功反馈。
   *
   * 副作用：
   * - 喷出较多火花（非 reduced）；
   * - 播放两次棘轮声；
   * - 给提示与登录按钮添加就绪样式；
   * - toast 提示。
   * 不启动登录，用户仍需点击“启动工坊”。
   * 不修改 charge。
   * 可重复触发？由 chargedFired 保证只一次。
   */
  function onCharged() {
    // 火花：
    // 装置中心偏上喷出 14 颗。
    // reduced 模式跳过。
    if (cogRect && !reduced) spawnSparks(cogRect.left + cogRect.width * 0.5, cogRect.top + cogRect.height * 0.36, 14)
    snd.ratchet()
    // 声音：
    // 连续两次棘轮，强化“上紧到位”。
    // 第二次延迟 110ms。
    setTimeout(() => snd.ratchet(), 110)
    hint?.classList.add('ready')
    // 视觉状态：
    // 提示文案切换为 ready；
    // 登录按钮添加 is-charged 类。
    loginBtn?.classList.add('is-charged')
    // 轻提示：
    // 告知用户核心已就绪。
    // 不自动登录。
    toast('工坊已上紧', { type: 'ok', msg: '核心就绪 · 点击「启动工坊」即可开工' })
  }

  /**
   * 计算当前应绘制的 charge 值。
   *
   * @param now performance.now() 时间戳。
   * @returns 0..1 的渲染值。
   *
   * 逻辑：
   * - loading/success 时目标至少 0.97；
   * - 入场动画期间短暂抬升目标；
   * - 非 reduced 时用 lerp 平滑逼近；
   * - charge 满格时直接返回 1。
   * 副作用：更新 renderCharge 与入场进度。
   * 不直接写 DOM。
   */
  function coreValue(now: number) {
    // 默认目标为当前 charge。
    // 后续状态可能抬高目标。
    let target = charge
    // loading/success：
    // 目标至少 0.97，让装置保持“高能”状态。
    // 不直接设为 1，保留一点动画余量。
    if (state === 'loading' || state === 'success') target = Math.max(target, 0.97)
    if (!introDone && !reduced) {
      introT = Math.min(1, (now - introStart) / 1150)
    // 入场动画：
    // 首次加载的 1150ms 内用正弦曲线抬升目标。
    // 完成后 introDone=true，不再覆盖。
    // reduced 模式跳过。
      target = Math.max(target, Math.sin(introT * Math.PI) * 0.78)
      if (introT >= 1) introDone = true
    } else {
      introDone = true
    }
    renderCharge = reduced ? target : lerp(renderCharge, target, 0.14)
    // 平滑插值：
    // 非 reduced 时以 0.14 系数逼近目标。
    // 该系数决定跟随速度。
    if (charge >= 0.999) {
      renderCharge = 1
    // 满格直接返回 1：
    // 避免插值导致读数在 99% 停留。
    // 同时保证刻度全亮。
      return 1
    }
    return renderCharge
  }

  /**
   * 根据 charge 绘制核心装置的视觉状态。
   *
   * @param v 当前渲染 charge（0..1）。
   * @param dt 帧间隔（秒）。
   * @param now 当前时间戳。
   *
   * 副作用：直接写 DOM style/class/textContent。
   * 更新内容：
   * - 齿圈旋转角度；
   * - 进度弧 dashoffset；
   * - 刻度/指示灯点亮数量；
   * - 指针角度；
   * - 百分比读数与提示文案；
   * - 光晕、弹跳与 touched 类。
   * 只在数值变化时写 DOM，减少重排。
   * 不读取布局，避免强制同步布局。
   */
  function paintCore(v: number, dt: number, now: number) {
    // 齿圈旋转：
    // 基础速度 + charge 加速 + 拖拽速度加成。
    // 角度持续累加，不回绕。
    // 使用 toFixed(2) 减少字符串长度。
    rimAngle += (5 + v * 215 + dragVel * 230) * dt
    if (cogRim) cogRim.style.transform = 'rotate(' + rimAngle.toFixed(2) + 'deg)'
    // 进度弧：
    // dasharray 固定 352，通过 dashoffset 表现进度。
    // v=0 时完全隐藏，v=1 时完全显示。
    if (chargeArc) chargeArc.style.strokeDashoffset = (352 * (1 - v)).toFixed(1)
    // 点亮刻度数：
    // 满格时全部点亮，否则按 floor(v*8)。
    // 仅在变化时更新 class，减少 DOM 操作。
    const lit = v >= 0.999 ? 8 : Math.floor(v * 8)
    if (lit !== lastTick) {
      lastTick = lit
    // 指针：
    // 从 -120deg 到 +120deg 线性映射。
    // 满格时指向上方。
      coreTicks.forEach((t, i) => t.classList.toggle('on', i < lit))
      corePips.forEach((p, i) => p.classList.toggle('on', i < lit))
    }
    if (needle) needle.style.transform = 'rotate(' + (-120 + v * 240).toFixed(1) + 'deg)'
    // 读数：
    // 满格显示 READY；
    // 小于 10% 补零，保持两位数。
    // 仅在整数变化时更新。
    const pct = Math.round(v * 100)
    if (pct !== lastPct) {
    // 提示文案：
    // 满格提示可启动；
    // 有进度时显示百分比；
    // 无进度时显示操作提示。
    // 文案由 hintStrong 承载。
      lastPct = pct
      if (readout) readout.textContent = charge >= 0.999 ? 'READY' : (pct < 10 ? '0' : '') + pct + '%'
      if (hintStrong)
    // 光晕：
    // 透明度与缩放随 charge 增加。
    // 使用 toFixed 减少样式字符串抖动。
        hintStrong.textContent =
          charge >= 0.999 ? '工坊已就绪 · 启动工坊' : charge > 0.001 ? '已上紧 ' + pct + '% · 继续拖动' : '拖动外圈，为工坊上紧'
    }
    // 指示灯：
    // 所有灯随 charge 整体变亮。
    // 不单独控制每个灯。
    if (coreHalo) {
      coreHalo.style.opacity = (0.2 + v * 0.55).toFixed(3)
    // 成功弹跳：
    // 仅在满格且非 reduced 时显示正弦呼吸。
    // 其他情况透明度为 0。
      coreHalo.style.transform = 'scale(' + (1 + v * 0.16).toFixed(3) + ')'
    }
    coreLamps.forEach((l) => (l.style.opacity = (0.3 + v * 0.7).toFixed(2)))
    // 触摸标记：
    // charge 超过 0.001 即视为已交互。
    // 用于样式层改变装置状态。
    // 不参与逻辑判断。
    if (coreBounce) coreBounce.style.opacity = (v >= 0.999 && !reduced ? 0.32 + 0.22 * Math.sin(now / 180) : 0).toFixed(3)
    if (charge > 0.001) mascot.classList.add('is-touched')
  }

  /**
   * 每帧更新场景。
   *
   * @param dt 帧间隔（秒）。
   * @param now 当前时间戳。
   *
   * 副作用：写入各元素的 style 与核心装置绘制。
   * 步骤：
   * - 计算鼠标归一化坐标；
   * - 更新视差层与光斑；
   * - 计算装置接近度与倾斜；
   * - 更新眼睛偏移、头部/整体倾斜；
   * - 调用 paintCore 绘制核心。
   * reduced 模式跳过视差。
   * 不读取布局（rect 已缓存）。
   * 不处理业务状态。
   */
  function drawScene(dt: number, now: number) {
    // 舞台矩形兜底：
    // 未测量时使用视口尺寸。
    // 保证归一化坐标不出现 NaN。
    const st = stageRect || { left: 0, top: 0, width: innerWidth, height: innerHeight }
    // 鼠标归一化：
    // 映射到 -1..1 并限制范围。
    // 用于视差与倾斜。
    nx = clamp(((mouseX - st.left) / st.width) * 2 - 1, -1, 1)
    ny = clamp(((mouseY - st.top) / st.height) * 2 - 1, -1, 1)
    // 视差层：
    // 每层按 data-depth 放大位移。
    // 仅非 reduced 时更新。
    // spotlight 随水平位置旋转。
    if (!reduced) {
      layers.forEach((l) => {
        const d = parseFloat(l.getAttribute('data-depth') || '1')
        l.style.transform = 'translate3d(' + nx * d * 14 + 'px,' + ny * d * 10 + 'px,0)'
      })
      if (spot) spot.style.transform = 'rotate(' + nx * 5 + 'deg)'
    }
    // 装置矩形兜底：
    // 未测量时使用 240×260。
    // 保证距离计算不出现 NaN。
    const cr = cogRect || { left: 0, top: 0, width: 240, height: 260 }
    // 鼠标到装置中心的距离：
    // 中心偏上（36% 高度处）。
    // 用于接近度。
    const dist = Math.hypot(mouseX - (cr.left + cr.width * 0.5), mouseY - (cr.top + cr.height * 0.36))
    // 接近度：
    // 1 - 归一化距离，限制 0..1。
    // 平滑插值后用于缩放与倾斜。
    const near = clamp(1 - dist / (Math.max(cr.width, cr.height) * 1.25), 0, 1)
    // 倾斜与位移：
    // 随鼠标位置与接近度变化。
    // 使用 translate3d + scale 触发合成层。
    proximity = lerp(proximity, near, 0.09)
    leanX = lerp(leanX, nx * 7 * proximity, 0.12)
    leanY = lerp(leanY, ny * 5 * proximity, 0.12)
    mascot.style.transform =
      'translate3d(' + leanX.toFixed(2) + 'px,' + leanY.toFixed(2) + 'px,0) scale(' + (1 + proximity * 0.03).toFixed(3) + ')'
    // 眼睛目标偏移：
    // 不同状态使用不同偏移：
    // shy 向下、watch 向右、loading 向下、idle 跟随鼠标。
    // 平滑插值后应用到左右眼。
    let tx: number
    let ty: number
    if (state === 'shy') {
      tx = 0
      ty = 3
    } else if (state === 'watch') {
      tx = 4
      ty = 2.2
    } else if (state === 'loading') {
      tx = 0
      ty = 4
    } else {
      tx = clamp((mouseX - (cr.left + cr.width * 0.435)) / 62, -1, 1) * 4.4
      ty = clamp((mouseY - (cr.top + cr.height * 0.36)) / 62, -1, 1) * 4
    }
    plx = lerp(plx, tx, 0.18)
    ply = lerp(ply, ty, 0.18)
    prx = lerp(prx, tx * 0.92, 0.18)
    pry = lerp(pry, ty, 0.18)
    if (pupilL) pupilL.style.transform = 'translate(' + plx.toFixed(2) + 'px,' + ply.toFixed(2) + 'px)'
    if (pupilR) pupilR.style.transform = 'translate(' + prx.toFixed(2) + 'px,' + pry.toFixed(2) + 'px)'
    // 头部/整体倾斜：
    // 仅 idle 状态跟随鼠标。
    // 其他状态归零，保持稳定。
    const tiltT = state === 'idle' ? nx * 5 : 0
    const leanT = state === 'idle' ? nx * 3.5 : 0
    headTilt = lerp(headTilt, tiltT, 0.12)
    // 应用头部旋转与整体水平位移。
    // 使用 toFixed 减少字符串抖动。
    rootLean = lerp(rootLean, leanT, 0.12)
    if (cogHead) cogHead.style.transform = 'rotate(' + headTilt.toFixed(2) + 'deg)'
    if (cogRoot) cogRoot.style.transform = 'translateX(' + rootLean.toFixed(2) + 'px)'
    // 绘制核心：
    // 先计算渲染 charge，再更新视觉。
    // 该调用每帧一次。
    paintCore(coreValue(now), dt, now)
  }

  /* ---------------- 装置状态 ---------------- */
  /**
   * 切换装置状态并同步 class。
   *
   * @param s 新状态。
   *
   * 副作用：写入 state 变量并切换 mascot 的状态类。
   * 只影响视觉，不触发请求。
   * 状态类由样式层定义。
   * 不重置 charge 或其他动画变量。
   * 可重复设置同一状态（class toggle 幂等）。
   */
  function setState(s: typeof state) {
    // 更新状态变量。
    // 后续绘制与交互读取该值。
    state = s
    mascot.classList.toggle('is-watch', s === 'watch')
    // 切换状态类：
    // is-watch/is-shy/is-loading/is-success。
    // class toggle 保证同一状态重复设置不会出错。
    // 不直接操作其他元素。
    mascot.classList.toggle('is-shy', s === 'shy')
    mascot.classList.toggle('is-loading', s === 'loading')
    mascot.classList.toggle('is-success', s === 'success')
  }

  let blinkTimer = 0
  /**
   * 安排下一次眨眼。
   *
   * 副作用：设置 blinkTimer，并在随机 3–6 秒后给装置添加 blink 类 150ms。
   * 递归调度，页面卸载时由清理函数清除计时器。
   * shy 状态或装置隐藏时跳过眨眼。
   * 不修改状态机。
   */
  function scheduleBlink() {
    // 设置下一次眨眼计时器。
    // 计时器 id 保存在闭包变量中。
    blinkTimer = window.setTimeout(() => {
      if (state !== 'shy' && !mascot.classList.contains('hidden-mascot')) {
    // 跳过条件：
    // shy 状态（害羞）或装置已隐藏。
    // 其他状态正常眨眼。
        mascot.classList.add('blink')
        setTimeout(() => mascot.classList.remove('blink'), 150)
    // 添加 blink 类并 150ms 后移除。
    // 动画本身由 CSS 控制。
      }
      scheduleBlink()
    }, rnd(3000, 6000))
    // 递归安排下一次。
    // 使用随机间隔避免机械感。
  }

  /**
   * 播放错误反馈。
   *
   * 副作用：
   * - charge 回退 0.1；
   * - 重置 chargedFired；
   * - 移除就绪样式；
   * - 重新触发 is-error 动画；
   * - 1.3 秒后移除 error 类。
   * 用于表单校验失败或登录失败。
   * 不显示 toast（由调用方负责）。
   * 不改变状态机。
   * 可连续触发，通过强制重排重播动画。
   */
  function mascotError() {
    // charge 回退：
    // 让用户看到“能量泄漏”的反馈。
    // 限制在 0..1。
    charge = clamp(charge - 0.1, 0, 1)
    chargedFired = false
    // 重置满格标记：
    // 允许再次上紧到满格。
    loginBtn?.classList.remove('is-charged')
    hint?.classList.remove('ready')
    // 移除就绪样式：
    // 提示与按钮恢复未就绪状态。
    mascot.classList.remove('is-error')
    void mascot.offsetWidth
    // 重播错误动画：
    // 先移除类，强制读取 offsetWidth 触发重排，
    // 再添加类。
    // 1.3 秒后移除。
    mascot.classList.add('is-error')
    setTimeout(() => mascot.classList.remove('is-error'), 1300)
  }

  /* ---------------- 点击变形 ---------------- */
  /**
   * 尝试播放工具变形动画。
   *
   * 副作用：
   * - 根据 charge 选择工具索引；
   * - 给工具墙对应槽位添加 borrowed 类；
   * - 播放伺服音；
   * - 隐藏装置并显示变形舞台；
   * - 2.4 秒后淡出并恢复装置。
   * 变形期间 morphing=true，阻止重复触发。
   * reduced 模式缩短/跳过部分动画。
   * 转场中不触发。
   * 不修改 charge。
   * 不发起请求。
   */
  function tryMorph() {
    // 前置条件：
    // 正在变形或转场时直接返回。
    // 避免动画叠加。
    if (morphing || transiting) return
    // 计算工具索引：
    // 根据 charge 映射到 0..7。
    // 满格时取 7。
    morphIdx = Math.min(7, Math.floor(charge * 8)) % 8
    // 标记变形中：
    // 阻止拖拽/点击再次触发。
    morphing = true
    // 高亮工具墙上的对应槽位：
    // borrowed 类表示“被借出”。
    const wall = $<SVGElement>('#wall-' + morphIdx)
    wall?.classList.add('borrowed')
    // 播放伺服音。
    // 与机械变形动作配合。
    snd.servo()
    mascot.classList.add('scatter')
    // 装置散开效果：
    // 添加 scatter 类。
    window.setTimeout(
    // 延时执行变形：
    // reduced 模式立即执行。
    // 否则等待 220ms 让散开动画先播放。
      () => {
        mascot.classList.add('hidden-mascot')
        // 隐藏装置，显示变形舞台。
        // 只让当前索引的工具可见。
        // 添加 motion-on 类启动动画。
        morphStage!.hidden = false
        morphTools.forEach((g, i) => {
          g.classList.toggle('on', i === morphIdx)
          g.classList.remove('off')
        })
        morphStage!.classList.add('motion-on')
      },
      reduced ? 0 : 220,
    )
    morphTimer = window.setTimeout(
      () => {
        // 变形展示结束：
        // 给当前工具添加 off 类开始淡出。
        const g = morphTools[morphIdx]
        g?.classList.add('off')
        // 淡出后恢复：
        // 隐藏舞台、移除动画类、恢复装置。
        // 移除工具墙 borrowed 类。
        // 复位 morphing。
        // 若仍在 idle，根据焦点恢复 watch/idle。
        morphHideTimer = window.setTimeout(
          () => {
            morphStage!.hidden = true
            morphStage!.classList.remove('motion-on')
            g?.classList.remove('on', 'off')
            mascot.classList.remove('hidden-mascot')
            void mascot.offsetWidth
            mascot.classList.remove('scatter')
            wall?.classList.remove('borrowed')
            morphing = false
            if (state === 'idle') {
              const active = document.activeElement
              setState(active === phone || active === code ? 'watch' : 'idle')
            }
          },
          reduced ? 0 : 200,
        )
      },
      reduced ? 0 : 2400,
    )
  }

  /* ---------------- 舞台鼠标 / 拖动 ---------------- */
  // 拖拽状态：
  // dragging 表示是否按住核心；
  // dragMoved 累计角度变化，用于区分点击与拖动；
  // dragLast 上一次角度；
  // coreCX/coreCY 装置中心坐标；
  // coreDown 是否在核心上按下。
  // 这些变量只在交互期间有效。
  // 不持久化。
  // 不参与业务状态。
  // 重置逻辑集中在 pointerup/cancel。
  // 拖动会持续增加 charge。
  let dragging = false
  let dragMoved = 0
  let dragLast = 0
  let coreCX = 0
  let coreCY = 0
  let coreDown = false
  /**
   * 计算装置中心坐标。
   *
   * 副作用：写入 coreCX/coreCY。
   * 中心取宽度 50%、高度 36%。
   * 在 pointerdown 时调用。
   * 不缓存，每次按下重新测量。
   */
  function coreCenter() {
    const r = mascot.getBoundingClientRect()
    coreCX = r.left + r.width * 0.5
    coreCY = r.top + r.height * 0.36
  }
  /**
   * 根据拖拽位置计算上紧量。
   *
   * @param x 当前指针 x。
   * @param y 当前指针 y。
   *
   * 副作用：
   * - 更新 dragLast/dragMoved/dragVel；
   * - 调用 addCharge 增加 charge。
   * 通过 atan2 计算绕核心的角度变化。
   * 角度差归一化到 -π..π。
   * 顺时针增加略多、逆时针略少。
   * 拖动速度用于额外旋转。
   */
  function windFromDrag(x: number, y: number) {
    const a = Math.atan2(y - coreCY, x - coreCX)
    let d = a - dragLast
    if (d > Math.PI) d -= 2 * Math.PI
    if (d < -Math.PI) d += 2 * Math.PI
    dragLast = a
    dragMoved += Math.abs(d)
    addCharge((Math.abs(d) / (Math.PI * 2)) * (d > 0 ? 1.45 : 1.05))
    dragVel = Math.min(1.4, Math.abs(d) / 0.11)
  }
  /**
   * 判断事件目标是否为可编辑控件。
   *
   * @param t 事件目标。
   * @returns 是 input/textarea/select/contenteditable 时为 true。
   *
   * 用于避免在输入框内点击时触发变形/火花。
   * 副作用：无。
   */
  function isInputTarget(t: EventTarget | null) {
    return !!(t instanceof Element && t.closest('input,textarea,select,[contenteditable]'))
  }

  // 指针移动：
  // 更新鼠标坐标；
  // 拖拽时计算上紧量。
  // 监听在 window 上，保证拖出装置仍能继续。
  const onPointerMove = (e: PointerEvent) => {
    mouseX = e.clientX
    mouseY = e.clientY
    if (dragging) windFromDrag(e.clientX, e.clientY)
  }
  // 指针按下：
  // - 仅响应左键；
  // - 在核心命中区按下时进入拖拽；
  // - 其他区域点击产生火花/音效，可能触发变形；
  // - 输入控件内不触发装饰效果。
  // 拖拽时阻止默认行为，避免选中文字。
  const onPointerDown = (e: PointerEvent) => {
    if (e.button !== 0) return
    const t = e.target as Element
    const onCore = !!(t.closest && t.closest('.core-hit'))
    if (onCore && !transiting && !morphing) {
      coreCenter()
      dragging = true
      coreDown = true
      dragMoved = 0
      dragVel = 0
      dragLast = Math.atan2(e.clientY - coreCY, e.clientX - coreCX)
      mascot.classList.add('is-dragging')
      e.preventDefault()
      return
    }
    const input = isInputTarget(t)
    if (!input && !transiting) {
      spawnSparks(e.clientX, e.clientY, 5)
      if (!(t.closest && t.closest('button,a,.btn'))) snd.click()
      if (!(t.closest && t.closest('button,a,.btn,input,textarea,.btn'))) tryMorph()
    }
  }
  // 指针抬起：
  // 结束拖拽；
  // 若位移很小则视为点击，增加一格 charge 并尝试变形；
  // 复位拖拽状态。
  const onPointerUp = (e: PointerEvent) => {
    if (dragging) {
      dragging = false
      mascot.classList.remove('is-dragging')
      dragVel = 0
      if (coreDown && dragMoved < 0.14) {
        spawnSparks(e.clientX, e.clientY, 6)
        snd.click()
        addCharge(1 / 8, true)
        tryMorph()
      }
      coreDown = false
    }
  }
  // 指针取消：
  // 与抬起类似，但不触发点击奖励。
  // 复位所有拖拽状态。
  const onPointerCancel = () => {
    dragging = false
    coreDown = false
    dragVel = 0
    mascot.classList.remove('is-dragging')
  }
  // 装置键盘操作：
  // Enter/空格触发一格上紧并尝试变形。
  // 阻止默认滚动。
  // 装置需可聚焦（tabindex）。
  const onDeviceKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault()
      addCharge(1 / 8)
      tryMorph()
    }
  }
  // 窗口尺寸变化：
  // 重新测量矩形并调整尘埃画布。
  // 不重建粒子。
  const onResize = () => {
    measureRects()
    sizeDust()
  }

  // 绑定事件：
  // 装置键盘、窗口指针移动/按下/抬起/取消、resize。
  // 清理函数中逐一移除。
  // 使用具名函数便于移除。
  // 不使用 { once:true }，需要持续交互。
  // 指针事件覆盖鼠标/触摸/笔。
  // passive 未显式设置，按下时可能 preventDefault。
  mascot.addEventListener('keydown', onDeviceKey)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerdown', onPointerDown)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
  window.addEventListener('resize', onResize)

  /* ---------------- 尘埃 ---------------- */
  // 尘埃背景：
  // 使用 canvas 绘制缓慢上升的微粒。
  // 初始化时创建粒子数组。
  // 每帧更新位置并绘制。
  // reduced 模式不创建粒子。
  // 粒子数量固定 30。
  // canvas 尺寸随窗口变化。
  // 不处理鼠标交互。
  // 不参与业务状态。
  // 清理时停止绘制即可。
  const dustC = $<HTMLCanvasElement>('#dust')
  const dctx = dustC?.getContext('2d') ?? null
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  let dust: { x: number; y: number; r: number; vy: number; vx: number; a: number; ph: number }[] = []
  /**
   * 调整尘埃画布尺寸。
   *
   * 副作用：设置 canvas 宽高与缩放变换。
   * 使用 devicePixelRatio（上限 2）保证清晰度。
   * 父元素尺寸变化时需重新调用。
   * 不重建粒子。
   */
  function sizeDust() {
    if (!dustC || !dctx) return
    const r = dustC.parentElement!.getBoundingClientRect()
    dustC.width = r.width * dpr
    dustC.height = r.height * dpr
    dctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  /**
   * 初始化尘埃粒子。
   *
   * 副作用：根据 reduced 创建 0 或 30 个粒子。
   * 每个粒子包含位置、半径、速度、透明度与相位。
   * 位置使用归一化坐标（0..1），绘制时乘以画布尺寸。
   * 不立即绘制。
   */
  function initDust() {
    sizeDust()
    dust = []
    const n = reduced ? 0 : 30
    for (let i = 0; i < n; i++)
      dust.push({ x: Math.random(), y: Math.random(), r: rnd(0.8, 2.4), vy: rnd(0.008, 0.03), vx: rnd(-0.006, 0.006), a: rnd(0.12, 0.45), ph: rnd(0, 6) })
  }
  /**
   * 绘制尘埃。
   *
   * @param t 当前时间戳。
   *
   * 副作用：清空并重绘 canvas。
   * 每帧更新粒子位置，越界后从顶部/侧边回绕。
   * 透明度带正弦相位，形成闪烁。
   * reduced 或无粒子时直接返回。
   * 不读取布局。
   */
  function drawDust(t: number) {
    if (!dctx || !dustC || reduced || !dust.length) return
    const w = dustC.width / dpr
    const h = dustC.height / dpr
    dctx.clearRect(0, 0, w, h)
    for (const p of dust) {
      p.y -= p.vy / 60
      p.x += p.vx / 60 + Math.sin(t / 1400 + p.ph) * 0.0002
      if (p.y < -0.02) {
        p.y = 1.02
        p.x = Math.random()
      }
      if (p.x < -0.02) p.x = 1.02
      if (p.x > 1.02) p.x = -0.02
      dctx.beginPath()
      dctx.arc(p.x * w, p.y * h, p.r, 0, 7)
      dctx.fillStyle = 'rgba(255,224,150,' + p.a + ')'
      dctx.fill()
    }
  }

  /* ---------------- 表单 ---------------- */
  // 表单控件引用：
  // 手机号、验证码、密码、发码按钮、登录按钮、表单、
  // 模式标签、字段容器、密码眼睛、演示按钮、记住手机号复选框。
  // 全部在初始化时查询一次。
  // 使用 ! 断言存在性，结构缺失会在早期检查中退出。
  // 不监听属性变化。
  // 事件绑定在下方统一完成。
  // 清理时逐一移除。
  // 引用不持有 React 状态。
  // 不创建副本。
  const phone = $<HTMLInputElement>('#phone')!
  const code = $<HTMLInputElement>('#code')!
  const pw = $<HTMLInputElement>('#password')!
  const sendBtn = $<HTMLButtonElement>('#sendCode')!
  const loginBtn = $<HTMLButtonElement>('#loginBtn')!
  const form = $<HTMLFormElement>('#loginForm')!
  const tabCode = $<HTMLButtonElement>('#tabCode')!
  const tabPw = $<HTMLButtonElement>('#tabPw')!
  const codeField = $<HTMLElement>('#codeField')!
  const pwField = $<HTMLElement>('#pwField')!
  const pwEye = $<HTMLButtonElement>('#pwEye')!
  const demoChip = $<HTMLButtonElement>('#demoChip')!
  const remember = $<HTMLInputElement>('#remember')!

  // 倒计时与提交状态：
  // cdTimer 倒计时计时器 id；
  // cdLeft 剩余秒数；
  // sending 是否正在发送验证码；
  // submitting 是否正在提交登录。
  // 这些变量防止重复操作。
  // 不持久化。
  // 清理时清除计时器。
  // 倒计时结束恢复按钮。
  // 提交期间禁用登录按钮。
  let cdTimer = 0
  let cdLeft = 0
  let sending = false
  let submitting = false

  /**
   * 切换登录模式。
   *
   * @param m code 或 pwd。
   *
   * 副作用：
   * - 更新 mode 变量；
   * - 微增 charge；
   * - 切换标签 active/aria-selected；
   * - 显示/隐藏验证码或密码字段；
   * - 播放 pop 音。
   * 从密码模式切回验证码时，若处于 shy 状态则恢复 idle。
   * 不改动已输入的值。
   * 不触发校验。
   * 不发起请求。
   */
  function setMode(m: LoginMode) {
    // 更新当前模式。
    // 后续校验与提交读取该值。
    mode = m
    addCharge(0.03, true)
    // 微增 charge：
    // 让切换动作也有机械反馈。
    // quiet 避免多余火花。
    tabCode.classList.toggle('active', m === 'code')
    tabPw.classList.toggle('active', m === 'pwd')
    // 标签状态：
    // active 类控制选中样式；
    // aria-selected 同步无障碍状态。
    tabCode.setAttribute('aria-selected', String(m === 'code'))
    tabPw.setAttribute('aria-selected', String(m === 'pwd'))
    codeField.style.display = m === 'code' ? '' : 'none'
    pwField.style.display = m === 'pwd' ? '' : 'none'
    // 字段显示：
    // code 模式显示验证码，pwd 模式显示密码。
    // 使用 style.display 直接切换。
    // 隐藏字段仍保留在 DOM 中，值不会丢失。
    if (m === 'code' && state === 'shy') setState('idle')
    // 从密码切回验证码时：
    // 若装置处于 shy（害羞）状态，恢复正常。
    // 其他状态保持不变。
    snd.pop()
    // 播放轻提示音。
    // 失败不影响切换。
  }
  /**
   * 校验手机号格式。
   *
   * @returns 1 开头且共 11 位数字时为 true。
   *
   * 副作用：无。只读取 phone.value。
   * 不 trim 之外的归一化。
   * 不校验号段真实性。
   */
  function validPhone() {
    return /^1\d{10}$/.test(phone.value.trim())
  }
  /**
   * 显示/清除手机号错误态。
   *
   * @param on 是否显示错误。
   *
   * 副作用：切换 is-err 类；显示时重播 shake 动画。
   * 通过移除类 + 强制重排 + 添加类重播动画。
   * 不修改错误文案。
   * 不聚焦输入框。
   */
  function phoneErr(on: boolean) {
    // 查询手机号字段容器。
    // 找不到时安全返回。
    const f = $<HTMLElement>('#fPhone')
    if (!f) return
    // 切换错误类。
    // 该类由样式层控制边框/提示。
    f.classList.toggle('is-err', on)
    if (on) {
    // 显示错误时重播抖动：
    // 先移除类，强制读取 offsetWidth，
    // 再添加类。
    // 保证连续错误也能重播动画。
      f.classList.remove('shake')
      void f.offsetWidth
      f.classList.add('shake')
    }
  }
  /**
   * 启动验证码倒计时。
   *
   * 副作用：
   * - 设置 cdLeft=60；
   * - 禁用发送按钮；
   * - 每秒更新按钮文案；
   * - 结束后恢复按钮。
   * 使用 setInterval，清理函数会清除。
   * 倒计时期间重复点击被 onSend 拦截。
   * 不发起请求。
   */
  function countdown() {
    // 初始剩余 60 秒。
    // 与后端发送频率限制大致对应。
    cdLeft = 60
    // 禁用按钮，防止重复发送。
    // 文案显示剩余时间。
    sendBtn.disabled = true
    sendBtn.textContent = cdLeft + 's 后重发'
    // 每秒递减：
    // 大于 0 时更新文案；
    // 到 0 时清除计时器并恢复按钮。
    cdTimer = window.setInterval(() => {
      cdLeft--
      if (cdLeft <= 0) {
        clearInterval(cdTimer)
        sendBtn.disabled = false
        sendBtn.textContent = '获取验证码'
      } else sendBtn.textContent = cdLeft + 's 后重发'
    }, 1000)
  }

  // 标签点击：
  // 分别切换到验证码/密码模式。
  // 只调用 setMode，不直接操作 DOM。
  // 不提交表单。
  const onTabCode = () => setMode('code')
  const onTabPw = () => setMode('pwd')
  // 手机号输入：
  // 清除错误态；
  // 微增 charge；
  // idle 时进入 watch 状态（眼睛跟随输入）。
  // 不做实时格式校验。
  // 不触发请求。
  const onPhoneInput = () => {
    phoneErr(false)
    addCharge(0.008, true)
    if (state === 'idle') setState('watch')
  }
  // 手机号聚焦：
  // 增加 charge；
  // 进入 watch 状态。
  // 不选中文本。
  const onPhoneFocus = () => {
    addCharge(0.05)
    if (state !== 'shy') setState('watch')
  }
  // 手机号失焦：
  // 从 watch 回到 idle。
  // 不立即校验格式。
  const onPhoneBlur = () => {
    if (state === 'watch') setState('idle')
  }
  // 验证码聚焦：
  // 增加 charge 并进入 watch 状态。
  // 不自动填充。
  const onCodeFocus = () => {
    addCharge(0.05)
    setState('watch')
  }
  // 验证码失焦：
  // 从 watch 回到 idle。
  // 保留已输入内容。
  const onCodeBlur = () => {
    if (state === 'watch') setState('idle')
  }
  // 验证码输入：
  // 微增 charge；
  // 清除验证码错误态。
  // 不做实时格式校验。
  const onCodeInput = () => {
    addCharge(0.01, true)
    $('#fCode')?.classList.remove('is-err')
  }
  // 密码聚焦：
  // 增加 charge 并进入 shy 状态。
  // 装置会做出“回避”动作。
  const onPwFocus = () => {
    addCharge(0.05)
    setState('shy')
  }
  // 密码失焦：
  // 从 shy 回到 idle；
  // 移除 peek（偷看）类。
  // 不隐藏已显示的密码。
  const onPwBlur = () => {
    if (state === 'shy') {
      setState('idle')
      mascot.classList.remove('peek')
    }
  }
  // 密码输入：
  // 微增 charge；
  // 清除密码错误态。
  // 不实时校验长度。
  const onPwInput = () => {
    addCharge(0.01, true)
    $('#fPw')?.classList.remove('is-err')
  }
  /**
   * 切换密码可见性。
   *
   * 副作用：
   * - 切换 input.type；
   * - 替换眼睛图标；
   * - 更新 aria-label；
   * - 切换装置 peek 类。
   * 不修改密码值。
   * 不触发表单提交。
   */
  const onPwEye = () => {
    // 当前是否隐藏（type=password）。
    // 以此决定切换方向。
    const show = pw.type === 'password'
    pw.type = show ? 'text' : 'password'
    // 切换 type：
    // 显示时改为 text，隐藏时改回 password。
    pwEye.innerHTML = show ? ICON_EYE_OFF : ICON_EYE
    // 替换图标：
    // 显示时用带斜线的眼睛，隐藏时用普通眼睛。
    pwEye.setAttribute('aria-label', show ? '隐藏密码' : '显示密码')
    // 更新无障碍名称：
    // 显示时提示“隐藏密码”，隐藏时提示“显示密码”。
    mascot.classList.toggle('peek', show)
    // 装置偷看效果：
    // 显示密码时添加 peek 类。
    // 隐藏时移除。
  }
  /**
   * 发送验证码。
   *
   * 副作用：
   * - 校验手机号；
   * - 调用 bridge.sendCode；
   * - 成功后启动倒计时、增加 charge、自动填入 Mock 验证码；
   * - 失败时 toast 并恢复按钮。
   * 发送期间置 sending，防止重复。
   * 成功/失败都复位 sending。
   * 不修改登录状态。
   * 不提交表单。
   */
  const onSend = async () => {
    // 手机号格式校验：
    // 非法时显示错误态、播放错误反馈与音效。
    // 不发起请求。
    if (!validPhone()) {
      phoneErr(true)
      mascotError()
    // 倒计时中或发送中：
    // 直接返回，避免重复请求。
      snd.error()
      return
    // 进入发送态：
    // 禁用按钮并显示“发送中…”。
    // finally 中恢复。
    }
    // 调用桥接发送验证码：
    // 捕获异常并转换为失败结果。
    // 不依赖异常类型。
    if (cdLeft > 0 || sending) return
    sending = true
    // 同步等待结果：
    // 该函数是 async，但内部逻辑顺序执行。
    // 结果包含 ok/code/message。
    sendBtn.disabled = true
    sendBtn.textContent = '发送中…'
    // 失败处理：
    // 恢复按钮；
    // 播放错误音；
    // toast 展示失败原因。
    // 不启动倒计时。
    let res: Awaited<ReturnType<LoginBridge['sendCode']>>
    try {
      res = await bridge.sendCode(phone.value.trim())
    // 成功处理：
    // 启动倒计时；
    // 增加 charge；
    // 进入 watch 状态；
    // Mock 环境自动填入验证码并提示。
    } catch {
    // 清除验证码错误态。
    // 便于用户直接看到自动填入的值。
      res = { ok: false, message: '网络异常，请稍后重试' }
    // 若有 Mock 验证码：
    // 直接写入输入框；
    // toast 说明已自动填入。
    // 生产环境 code 通常为空。
    }
    sending = false
    // 无 Mock 验证码：
    // 提示查看后端日志。
    // 不自动填充。
    if (!res.ok) {
      sendBtn.disabled = false
      sendBtn.textContent = '获取验证码'
      snd.error()
      toast('验证码发送失败', { type: 'bad', msg: res.message })
      return
    }
    countdown()
    addCharge(0.1)
    setState('watch')
    if (res.code) {
      code.value = res.code
      $('#fCode')?.classList.remove('is-err')
      toast('验证码已发送', { type: 'info', msg: '演示环境验证码：' + res.code + '，已自动填入' })
    } else {
      toast('验证码已发送', { type: 'info', msg: '请查看后端日志中的 MOCK SMS 验证码' })
    }
  }
  /**
   * 一键填入演示账号。
   *
   * 副作用：
   * - 写入手机号与密码；
   * - 清空不合法验证码；
   * - 增加 charge、播放 pop 音；
   * - 验证码模式下自动请求真实验证码并填入。
   * 演示账号仅用于本地/Mock 环境。
   * 不直接登录，用户仍需点击“启动工坊”。
   * 发码失败时提示手动获取。
   * 不修改记住手机号选项。
   */
  const onDemo = async () => {
    // 写入演示手机号与密码。
    // 与后端种子账号一致。
    phone.value = '13800000001'
    // 验证码模式下：
    // 若当前验证码不是 6 位数字，清空。
    // 避免沿用旧值。
    pw.value = '123456'
    if (mode === 'code' && !/^\d{6}$/.test(code.value)) code.value = ''
    // 清除各字段错误态。
    // 让用户从干净状态开始。
    phoneErr(false)
    $('#fCode')?.classList.remove('is-err')
    // 增加 charge 并播放 pop 音。
    // 提供“演示账号已填入”的即时反馈。
    $('#fPw')?.classList.remove('is-err')
    addCharge(0.14, true)
    snd.pop()
    // 验证码模式：
    // 必须先向后端申请真实验证码（一次性消费）。
    // 发送中禁用按钮。
    // 成功则启动倒计时并填入 Mock 验证码。
    // 失败则恢复按钮并提示手动获取。
    // 验证码登录必须先向后端申请真实验证码（一次性消费）
    if (mode === 'code') {
    // 请求验证码。
    // 失败/异常统一走恢复逻辑。
      sendBtn.disabled = true
      sendBtn.textContent = '发送中…'
      try {
    // 成功：
    // 启动倒计时；
    // 有 Mock 验证码则填入并提示；
    // 否则提示查看日志。
        const res = await bridge.sendCode(phone.value)
        if (res.ok) {
          countdown()
          if (res.code) {
    // 失败：
    // 恢复按钮并提示手动获取。
    // 不阻止用户继续输入。
            code.value = res.code
            toast('演示账号已填入', { type: 'info', msg: '真实验证码已自动填入，点击「启动工坊」即可进入' })
          } else {
            toast('演示账号已填入', { type: 'info', msg: '验证码已发送，请查看后端日志中的 MOCK SMS 验证码后填写' })
          }
    // 密码模式：
    // 只需提示用户点击启动。
    // 不需要验证码。
        } else {
          sendBtn.disabled = false
          sendBtn.textContent = '获取验证码'
          toast('演示账号已填入', { type: 'info', msg: '请先点击「获取验证码」后再登录' })
        }
      } catch {
        sendBtn.disabled = false
        sendBtn.textContent = '获取验证码'
        toast('演示账号已填入', { type: 'info', msg: '请先点击「获取验证码」后再登录' })
      }
    } else {
      toast('演示账号已填入', { type: 'info', msg: '点击「启动工坊」即可进入' })
    }
  }

  // 恢复“记住手机号”：
  // localStorage 中保存的号码在初始化时回填。
  // 同时勾选复选框。
  // 只读一次，不监听后续变化。
  // 不保存密码或验证码。
  // 清除时由提交逻辑负责。
  // 存储键为 ts_phone。
  // 异常/隐私模式下 localStorage 可能不可用，需容错。
  const saved = localStorage.getItem('ts_phone')
  if (saved) {
    phone.value = saved
    remember.checked = true
  }

  /**
   * 播放登录成功转场。
   *
   * 副作用：
   * - 设置 transiting；
   * - reduced 模式下直接 enter；
   * - 否则依次播放螺栓下落、旋转、虹膜展开；
   * - 转场盖满屏幕后调用 bridge.enter；
   * - 最后移除覆盖层与 fx 层。
   * 该函数是 async，但调用处不 await。
   * 转场期间忽略其他交互。
   * 不修改表单值。
   * 失败时不回滚（转场通常不会失败）。
   */
  async function playTransition() {
    // 标记转场中：
    // 阻止拖拽/点击/重复提交。
    transiting = true
    // reduced 模式：
    // 直接进入系统，跳过所有动画。
    // 仍需调用 enter 写入登录态。
    if (reduced) {
      bridge.enter(pendingToken)
    // 等待工具函数：
    // 用 Promise + setTimeout 实现串行动画节奏。
    // 不依赖第三方动画库。
      return
    }
    // 初始延迟：
    // 让成功粒子和音效先播放。
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))
    await wait(430)
    // 登录视图“拧下”：
    // 添加 unscrew 类；
    // 显示螺栓层并播放下落动画。
    loginView!.classList.add('unscrew')
    boltLayer.hidden = false
    requestAnimationFrame(() => boltBig.classList.add('drop'))
    // 螺栓旋转两段：
    // r1/r2 类依次添加。
    // 每段配棘轮声。
    await wait(300)
    boltBig.classList.add('r1')
    snd.ratchet()
    // 虹膜层出现：
    // 螺栓淡出，齿轮开始放大。
    // 根据视口对角线计算缩放目标。
    await wait(180)
    boltBig.classList.add('r2')
    snd.ratchet()
    await wait(160)
    irisCover.hidden = false
    // 虹膜展开动画：
    // 使用 easeInOut 缓动。
    // 每帧设置 transform translate + scale。
    // 完成后 resolve。
    boltBig.classList.add('fade')
    const w = innerWidth
    const h = innerHeight
    const S = (Math.hypot(w, h) / 2 + 60) / 112
    await new Promise<void>((resolve) => {
      const t0 = performance.now()
      const grow = () => {
        const p = Math.min(1, (performance.now() - t0) / 560)
        const s = easeInOut(p) * S
        irisGear.setAttribute('transform', 'translate(' + w / 2 + ',' + h / 2 + ') scale(' + s + ')')
    // 覆盖层盖满屏幕后：
    // 写入登录态并跳转。
    // 覆盖层挂在 body，导航后仍存在。
        if (p < 1) requestAnimationFrame(grow)
        else resolve()
    // 收尾：
    // 隐藏覆盖层与螺栓；
    // 移除动画类与 unscrew；
    // 移除覆盖层与 fx 层。
    // 释放 DOM，避免残留。
      }
      requestAnimationFrame(grow)
    })
    // 虹膜已盖满：写入登录态并跳转；覆盖层挂在 body，导航后仍然存在
    bridge.enter(pendingToken)
    await wait(240)
    irisCover.hidden = true
    boltLayer.hidden = true
    boltBig.classList.remove('drop', 'r1', 'r2', 'fade')
    loginView!.classList.remove('unscrew')
    overlayWrap.remove()
    fxLayer.remove()
  }

  let pendingToken = ''
  /**
   * 处理登录表单提交。
   *
   * 副作用：
   * - 校验手机号与验证码/密码；
   * - 记住/清除手机号；
   * - 调用 bridge.login；
   * - 成功后播放转场，失败时恢复状态并 toast。
   * 转场中或提交中直接返回。
   * 校验失败时播放错误反馈与音效。
   * 不在此处写 store（由 enter 负责）。
   * 不在此处导航。
   */
  const onSubmit = async (e: Event) => {
    // 阻止表单默认提交。
    // 转场中或提交中直接返回。
    e.preventDefault()
    if (transiting || submitting) return
    // 校验状态：
    // ok=false 时不发起请求。
    let ok = true
    // 手机号校验：
    // 非法时显示错误态。
    if (!validPhone()) {
      phoneErr(true)
      ok = false
    // 验证码模式：
    // 必须为 6 位数字。
    // 密码模式：
    // 至少 6 位。
    }
    if (mode === 'code') {
      if (!/^\d{6}$/.test(code.value.trim())) {
        $('#fCode')?.classList.add('is-err')
        ok = false
      }
    } else if (pw.value.length < 6) {
      $('#fPw')?.classList.add('is-err')
    // 校验失败：
    // 播放错误反馈与音效。
    // 不请求后端。
      ok = false
    }
    if (!ok) {
    // 记住手机号：
    // 勾选时写入 localStorage，否则移除。
    // 只保存手机号，不保存密码/验证码。
      mascotError()
      snd.error()
      return
    // 进入提交态：
    // 按钮加载并禁用；
    // 装置进入 loading 状态。
    }
    if (remember.checked) localStorage.setItem('ts_phone', phone.value.trim())
    else localStorage.removeItem('ts_phone')
    // 调用桥接登录：
    // 传入模式、手机号、验证码/密码。
    // 捕获异常并转换为失败结果。
    submitting = true
    loginBtn.classList.add('loading')
    // 恢复按钮与提交状态。
    // 成功后转场，失败后回到 idle。
    loginBtn.disabled = true
    setState('loading')
    let res: Awaited<ReturnType<LoginBridge['login']>>
    // 失败：
    // 恢复 idle；
    // 播放错误反馈与音效；
    // toast 展示原因。
    // 不播放转场。
    try {
      res = await bridge.login({ mode, phone: phone.value.trim(), code: code.value.trim(), password: pw.value })
    } catch {
      res = { ok: false, message: '网络异常，请稍后重试' }
    }
    submitting = false
    loginBtn.classList.remove('loading')
    // 成功：
    // 保存 token（延迟到 enter 写入）；
    // 进入 success 状态；
    // 喷出螺母粒子并播放成功音；
    // 启动转场。
    loginBtn.disabled = false
    if (!res.ok) {
      setState('idle')
      mascotError()
      snd.error()
      toast('登录失败', { type: 'bad', msg: res.message })
      return
    }
    pendingToken = res.token
    setState('success')
    const r = mascot.getBoundingClientRect()
    spawnNuts(r.left + r.width / 2, r.top + r.height * 0.3, 16)
    snd.success()
    playTransition()
  }

  // 绑定表单与标签事件：
  // 标签点击、手机号输入/聚焦/失焦、
  // 验证码输入/聚焦/失焦、
  // 密码输入/聚焦/失焦、
  // 眼睛切换、发送验证码、演示账号、表单提交。
  // 所有监听在清理函数中逐一移除。
  // 使用具名函数便于移除。
  // 输入类事件不使用防抖。
  // 不在捕获阶段监听。
  // 不阻止事件冒泡（除非处理函数自行处理）。
  tabCode.addEventListener('click', onTabCode)
  tabPw.addEventListener('click', onTabPw)
  phone.addEventListener('input', onPhoneInput)
  phone.addEventListener('focus', onPhoneFocus)
  phone.addEventListener('blur', onPhoneBlur)
  code.addEventListener('focus', onCodeFocus)
  code.addEventListener('blur', onCodeBlur)
  code.addEventListener('input', onCodeInput)
  pw.addEventListener('focus', onPwFocus)
  pw.addEventListener('blur', onPwBlur)
  pw.addEventListener('input', onPwInput)
  pwEye.addEventListener('click', onPwEye)
  sendBtn.addEventListener('click', onSend)
  demoChip.addEventListener('click', onDemo)
  form.addEventListener('submit', onSubmit)

  /* ---------------- 主循环 ---------------- */
  let raf = 0
  let last = performance.now()
  let running = true
  /**
   * 主循环。
   *
   * @param now requestAnimationFrame 提供的时间戳。
   *
   * 副作用：
   * - 计算 dt（限制最大 50ms，避免切回标签页时跳变）；
   * - 更新场景与尘埃；
   * - 安排下一帧。
   * 页面隐藏时由 visibilitychange 暂停。
   * running=false 时停止。
   * 不使用 setInterval。
   * 不处理业务状态。
   */
  function tick(now: number) {
    // running=false 时停止循环。
    // 清理函数会设置该标记。
    if (!running) return
    // 计算帧间隔：
    // 最大 0.05s，避免后台恢复时大跳。
    // last 更新为当前时间。
    const dt = Math.min(0.05, (now - last) / 1000)
    last = now
    drawScene(dt, now)
    drawDust(now)
    raf = requestAnimationFrame(tick)
  }
  const onVisibility = () => {
  // 页面可见性变化：
  // 隐藏时取消 rAF，避免后台消耗；
  // 恢复时重置时间并重新启动。
  // 不重建场景或粒子。
  // 不触发数据请求。
  // 清理时移除监听。
    if (document.hidden) {
      cancelAnimationFrame(raf)
      raf = 0
    } else if (!raf) {
      last = performance.now()
      raf = requestAnimationFrame(tick)
    }
  }
  document.addEventListener('visibilitychange', onVisibility)

  measureRects()
  initDust()
  scheduleBlink()
  raf = requestAnimationFrame(tick)

  /* ---------------- 清理 ---------------- */
  // 清理函数：
  // 停止主循环；
  // 清除所有计时器（眨眼、变形、倒计时）；
  // 移除可见性/resize/指针/键盘/表单事件；
  // 移除 body 类与覆盖层/fx 层。
  // 若正在转场，则保留覆盖层（由转场收尾移除）。
  // 保证重复调用安全。
  // 不重置全局音效状态。
  // 不影响其他页面。
  // 所有 DOM 引用随节点移除释放。
  // 不发起请求。
  return () => {
    running = false
    cancelAnimationFrame(raf)
    clearTimeout(blinkTimer)
    clearTimeout(morphTimer)
    clearTimeout(morphHideTimer)
    clearInterval(cdTimer)
    document.removeEventListener('visibilitychange', onVisibility)
    window.removeEventListener('resize', onResize)
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerdown', onPointerDown)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerCancel)
    mascot.removeEventListener('keydown', onDeviceKey)
    tabCode.removeEventListener('click', onTabCode)
    tabPw.removeEventListener('click', onTabPw)
    phone.removeEventListener('input', onPhoneInput)
    phone.removeEventListener('focus', onPhoneFocus)
    phone.removeEventListener('blur', onPhoneBlur)
    code.removeEventListener('focus', onCodeFocus)
    code.removeEventListener('blur', onCodeBlur)
    code.removeEventListener('input', onCodeInput)
    pw.removeEventListener('focus', onPwFocus)
    pw.removeEventListener('blur', onPwBlur)
    pw.removeEventListener('input', onPwInput)
    pwEye.removeEventListener('click', onPwEye)
    sendBtn.removeEventListener('click', onSend)
    demoChip.removeEventListener('click', onDemo)
    form.removeEventListener('submit', onSubmit)
    document.body.classList.remove('login-page')
    if (!transiting) {
      overlayWrap.remove()
      fxLayer.remove()
    }
  }
}
