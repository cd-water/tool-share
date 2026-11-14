/**
 * 全局特效库（从 ToolShare 前端原型包移植，框架无关，双端共用）。
 *
 * 提供：齿轮光标、点击涟漪、磁吸按钮、3D 倾斜、Web Audio 音效、
 * 揭示入场、数字滚动、主题切换、命令面板。
 *
 * 使用方式：应用挂载后调用一次 initFx()；
 * 它通过事件委托和 MutationObserver 兼容 SPA 动态渲染的节点。
 * 所有副作用都限定在浏览器环境，不引入框架依赖。
 */

// DOM 元素的简写别名，减少工具函数签名里的噪声。
type El = HTMLElement

/**
 * 查询匹配选择器的全部元素。
 *
 * @param sel CSS 选择器
 * @param root 查询根节点，默认 document
 * @returns 真正的数组（而非 NodeList），便于直接使用 forEach/map
 */
function $$(sel: string, root: ParentNode = document): El[] {
  return Array.from(root.querySelectorAll(sel))
}
/**
 * 查询第一个匹配元素。
 *
 * @param sel CSS 选择器
 * @param root 查询根节点，默认 document
 * @returns 元素或 null；调用方需自行判空
 */
function $(sel: string, root: ParentNode = document): El | null {
  return root.querySelector(sel)
}

/* ---------------- Web Audio 音效 ---------------- */
/* 顶栏小图标：内联 SVG，便于随 currentColor 着色，且不引入图标依赖。 */
const ICON_SOUND_ON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>'
const ICON_SOUND_OFF =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><line x1="22" y1="9" x2="16" y2="15"/><line x1="16" y1="9" x2="22" y2="15"/></svg>'
const ICON_SUN =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>'
const ICON_MOON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z"/></svg>'

/**
 * Web Audio 音效模块（IIFE 单例）。
 *
 * 首次播放时才创建 AudioContext，避免页面加载即占用音频设备；
 * 开关状态持久化到 localStorage 的 ts_sound。
 * 任何音频异常都静默降级：没有声音也不影响业务操作。
 */
const SoundFX = (() => {
  // AudioContext 懒加载缓存；null 表示尚未创建或环境不支持。
  let ctx: AudioContext | null = null
  // 音效开关：默认开启，用户关闭后写入 localStorage。
  let on = localStorage.getItem('ts_sound') !== 'off'
  /**
   * 获取（或惰性创建）AudioContext。
   *
   * @returns AudioContext；浏览器不支持或被策略阻止时返回 null
   * 副作用：首次调用会创建音频上下文。
   */
  function ac() {
    // 只创建一次，后续复用同一音频上下文。
    if (!ctx) {
      // 部分浏览器要求用户交互后才能创建，失败时保持静默。
      try {
        // 兼容旧版 WebKit 的 webkitAudioContext。
        ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
      // 创建失败不抛出：音效属于增强体验，不应阻断主流程。
      } catch {
        /* noop */
      }
    }
    // 可能为 null，调用方已判空。
    return ctx
  }
  /**
   * 播放一个短促的合成音。
   *
   * @param f 频率（Hz）
   * @param d 持续时间（秒）
   * @param type 波形，默认正弦波
   * @param g 初始音量（0~1），默认 0.04 避免刺耳
   * 副作用：创建振荡器与增益节点，播完自动回收。
   */
  function beep(f: number, d: number, type: OscillatorType = 'sine', g = 0.04) {
    // 用户关闭音效时直接跳过。
    if (!on) return
    const c = ac()
    // 当前环境无法创建音频上下文。
    if (!c) return
    // 浏览器自动播放策略可能让上下文处于 suspended，需恢复。
    if (c.state === 'suspended') c.resume()
    // 振荡器负责发声，增益节点负责音量与淡出包络。
    const o = c.createOscillator()
    // 增益节点负责音量包络，避免直接改振荡器音量。
    const gain = c.createGain()
    // 不同波形带来不同音色：sine 柔和、triangle 清脆、square 更硬。
    o.type = type
    // 频率决定音高，增益包络决定是否刺耳。
    o.frequency.value = f
    // 起音音量：立即设置，避免使用默认值造成爆音。
    gain.gain.setValueAtTime(g, c.currentTime)
    // 指数淡出到接近 0，听感自然且不会产生截断杂音。
    gain.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + d)
    // 连接音频图：振荡器 → 增益 → 输出设备。
    o.connect(gain)
    gain.connect(c.destination)
    // 启动并在时长结束后停止，节点随后可被回收。
    o.start()
    // 停止时间与淡出终点一致，避免尾音被截断。
    o.stop(c.currentTime + d)
  }
  // 对外暴露开关控制与三个常用语义音。
  return {
    // 查询当前开关状态，供按钮绘制图标。
    isOn: () => on,
    /**
     * 切换音效开关并持久化。
     *
     * @returns 切换后的状态（true 为开启）
     * 副作用：写入 localStorage，刷新页面后保持。
     */
    toggle(): boolean {
      // 取反后立即持久化，保证刷新后状态一致。
      on = !on
      // 只存 on/off，默认值逻辑集中在初始化处。
      localStorage.setItem('ts_sound', on ? 'on' : 'off')
      return on
    },
    // 轻快的点击音，用于按钮与命令面板。
    pop() {
      beep(660, 0.07, 'triangle', 0.05)
    },
    // 更短更轻的滴答音，用于高频交互。
    tick() {
      beep(880, 0.04, 'square', 0.02)
    },
    // 两音符上行提示音，用于操作成功。
    success() {
      beep(523, 0.09, 'sine', 0.05)
      setTimeout(() => beep(784, 0.12, 'sine', 0.05), 80)
    },
  }
})()

/**
 * 给按钮绑定音效开关。
 *
 * @param btn 目标按钮；传 null 时直接返回，方便调用方可选渲染
 * 副作用：写入按钮内容与 title，并注册 click 监听。
 * 按钮状态完全由 SoundFX 决定；重复调用会重复绑定，请只调用一次。
 */
export function bindSoundToggle(btn: El | null) {
  if (!btn) return
  // 根据当前开关状态重绘图标与提示文案。
  function paint() {
    // 开启显示喇叭，关闭显示静音图标。
    btn!.innerHTML = SoundFX.isOn() ? ICON_SOUND_ON : ICON_SOUND_OFF
    // title 同步更新，悬停即可知道按钮作用。
    btn!.title = SoundFX.isOn() ? '关闭音效' : '开启音效'
  }
  // 初始化时立即绘制，避免首次点击前按钮空白。
  paint()
  btn.addEventListener('click', () => {
    // 切换后立即重绘；开启时播放一声反馈音。
    SoundFX.toggle()
    paint()
    // 仅在开启时播放反馈，关闭操作保持安静。
    if (SoundFX.isOn()) SoundFX.pop()
  })
}

/**
 * 给按钮绑定亮/暗主题切换。
 *
 * @param btn 目标按钮；null 时直接返回
 * 副作用：切换 document.body 的 dark 类并持久化到 localStorage。
 * 主题只影响 CSS 变量，不重建组件；初始状态从存储恢复。
 */
export function bindThemeToggle(btn: El | null) {
  if (!btn) return
  // 从持久化值恢复主题，默认亮色。
  let dark = localStorage.getItem('ts_dark') === '1'
  // 把主题状态同步到 DOM 与按钮图标/文案。
  function apply() {
    // 单一 dark 类驱动全部主题变量，组件无需感知。
    document.body.classList.toggle('dark', dark)
    // 暗色时显示太阳，提示点击可切回亮色。
    btn!.innerHTML = dark ? ICON_SUN : ICON_MOON
    // 文案描述「点击后会发生什么」。
    btn!.title = dark ? '切换亮色' : '切换暗色'
  }
  // 初始化时同步一次，避免刷新后图标与主题不一致。
  apply()
  btn.addEventListener('click', () => {
  // 先更新状态并持久化，再应用样式，避免刷新后回退。
    dark = !dark
    localStorage.setItem('ts_dark', dark ? '1' : '0')
    // 状态更新后播放与其它按钮一致的反馈音。
    apply()
    SoundFX.pop()
  })
}

// Toast 不依赖第三方组件，登录页等未挂载完整 UI 的环境也能使用。
// 支持可选撤销回调，适合「删除后恢复」这类操作。
/* ---------------- Toast（可撤销） ---------------- */
/**
 * Toast 配置。
 *
 * type 决定图标与色调；title 为必填主文案；
 * msg 是可选补充说明；undo 存在时展示「撤销」按钮。
 */
interface ToastOpt {
  // 语义类型：成功 / 失败 / 警告 / 信息。
  type?: 'ok' | 'bad' | 'warn' | 'info'
  title?: string
  msg?: string
  // 撤销回调；传入后才渲染撤销按钮。
  undo?: () => void
}
/**
 * 弹出一个轻提示，可选撤销操作。
 *
 * @param title 主文案
 * @param opt 类型、补充说明与撤销回调
 * 副作用：按需创建 .toast-stack 容器并插入 DOM；
 * 3.6 秒后自动淡出移除，点击关闭按钮或撤销也会移除。
 * 容器复用，多个 toast 会纵向堆叠。
 */
export function toast(title: string, opt: ToastOpt = {}) {
  // 复用全局堆叠容器，避免每条提示都创建包裹层。
  let stack = $('.toast-stack')
  // 容器只创建一次，多个提示共用同一个堆叠区。
  if (!stack) {
    // 首次使用时惰性创建堆叠容器。
    stack = document.createElement('div')
    stack.className = 'toast-stack'
    document.body.appendChild(stack)
  }
  // 单条提示节点，类型类名决定配色。
  const t = document.createElement('div')
  // 默认信息类型；类名决定图标与配色。
  t.className = 'toast ' + (opt.type || 'info')
  // 根据类型选择内联图标：成功勾、失败叉、其余信息圈。
  const ic =
    opt.type === 'ok'
      ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'
      : opt.type === 'bad'
        ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/></svg>'
        : '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>'
  // 标题/消息来自调用方，按 HTML 渲染；调用方需保证内容可信。
  t.innerHTML =
    ic +
    '<div class="t-body"><div class="t-title">' +
    title +
    '</div>' +
    (opt.msg ? '<div class="t-msg">' + opt.msg + '</div>' : '') +
    (opt.undo ? '<button class="t-undo">撤销</button>' : '') +
    '</div><button class="t-x"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg></button>'
  // 插入 DOM 后开始计时，保证动画从可见状态开始。
  stack.appendChild(t)
  // 自动消失计时器；手动关闭时会在 dismiss 中清除。
  const timer = setTimeout(dismiss, 3600)
  // 先播放淡出动画，再移除节点；重复调用是安全的。
  function dismiss() {
    // 手动关闭时取消自动计时，避免重复移除。
    clearTimeout(timer)
    // out 触发淡出动画，延时与 transition 时长匹配后再移除。
    t.classList.add('out')
    // 与 CSS 淡出动画时长保持一致。
    setTimeout(() => t.remove(), 260)
  }
  // 右上角关闭按钮与自动计时共用 dismiss。
  t.querySelector('.t-x')!.addEventListener('click', dismiss)
  // 撤销是「先执行回调，再关闭提示」，并额外弹一条确认提示。
  if (opt.undo) {
    t.querySelector('.t-undo')!.addEventListener('click', () => {
      // 回调由调用方提供，常用于恢复刚删除的数据。
      opt.undo!()
      dismiss()
      // 给用户一个撤销已生效的确认反馈。
      toast('已撤销', { type: 'info' })
    })
  }
}

// 用 IntersectionObserver 替代滚动监听，避免频繁触发与手动计算位置。
// 对 SPA 动态渲染的节点可重复调用，只处理尚未入场的元素。
/* ---------------- 揭示入场（IntersectionObserver） ---------------- */
// 全局共享的观察器：undefined 表示未初始化，null 表示浏览器不支持。
let revealIO: IntersectionObserver | null | undefined
/**
 * 初始化（或增量刷新）.reveal 元素的入场动画。
 *
 * 元素进入视口 8% 时添加 .in 类并取消观察；
 * 支持 SPA 动态渲染，可重复调用。
 * 不支持 IntersectionObserver 时直接标记为已显示，保证内容可见。
 */
function initReveal() {
  // 观察器只初始化一次，后续调用直接复用。
  if (revealIO === undefined) {
    // 懒创建单例观察器；阈值 0.08 兼顾提前量与性能。
    revealIO =
    // 特性检测：老浏览器直接降级为全部显示。
      'IntersectionObserver' in window
        // 进入视口 8% 即视为可见，兼顾提前量与性能。
        ? new IntersectionObserver(
            // 进入视口即触发一次，随后取消观察避免重复回调。
            (es) =>
              es.forEach((en) => {
                if (en.isIntersecting) {
                  // .in 由 CSS 控制透明度与位移动画。
                  en.target.classList.add('in')
                  // 元素只需入场一次，避免滚动来回触发。
                  revealIO!.unobserve(en.target)
                }
              }),
            { threshold: 0.08 },
          )
        : null
  }
  // 保存到局部变量，避免回调中重复读取可变全局状态。
  const io = revealIO
  // 按顺序设置递增延迟，形成错落入场；最多累加 8 个，避免尾部等待过久。
  $$('.reveal').forEach((n, i) => {
    if (n.classList.contains('in')) return
    n.style.transitionDelay = Math.min(i, 8) * 45 + 'ms'
    // 有观察器则等待进入视口；否则立即显示。
    if (io) io.observe(n)
    // 无观察器时立即显示，保证内容可读。
    else n.classList.add('in')
  })
}

// 涟漪只依赖 CSS 动画，JS 负责在点击位置插入节点。
/* ---------------- 点击涟漪 ---------------- */
/**
 * 全局点击涟漪效果。
 *
 * 通过 document 事件委托监听所有 .btn，
 * 在点击坐标处创建圆形 span，动画结束后自动移除。
 * loading 状态的按钮不响应，避免重复触发。
 */
function initRipple() {
  // 事件委托：页面后续新增的按钮也能生效。
  document.addEventListener('click', (e) => {
    // 只有按钮需要涟漪；closest 兼容按钮内部图标/文字的点击。
    const b = (e.target as El).closest('.btn') as El | null
    // loading 按钮不叠加动画，避免与加载态冲突。
    if (!b || b.classList.contains('loading')) return
    // 以按钮尺寸作为涟漪直径，保证覆盖整个按钮。
    const r = b.getBoundingClientRect()
    // 取长边作为直径，圆形涟漪覆盖整个按钮。
    const s = Math.max(r.width, r.height)
    // 涟漪节点绝对定位，坐标相对按钮左上角计算。
    const rip = document.createElement('span')
    rip.className = 'ripple'
    // 同时设置宽高，保持正圆。
    rip.style.width = rip.style.height = s + 'px'
    // 让涟漪圆心落在点击位置。
    rip.style.left = e.clientX - r.left - s / 2 + 'px'
    // 垂直方向的圆心对齐点击位置。
    rip.style.top = e.clientY - r.top - s / 2 + 'px'
    // 涟漪挂到按钮内部，随按钮移动并被裁剪。
    b.appendChild(rip)
    // 动画结束自动清理，避免 DOM 节点累积。
    rip.addEventListener('animationend', () => rip.remove())
  })
}

// 两者都基于 pointermove 事件委托，注意避免读取布局造成抖动。
// 效果只写 transform / 自定义属性，尽量不触发重排。
/* ---------------- 磁吸按钮 / 3D 倾斜 ---------------- */
/**
 * 磁吸按钮效果。
 *
 * 指针移动时，距离按钮中心越远偏移越大（水平 0.12、垂直 0.2），
 * 产生轻微跟随感；移出后复位。
 * 使用事件委托，兼容 SPA 动态插入的 .magnetic 元素。
 */
function initMagnetic() {
  // 事件委托,支持 SPA 动态渲染的元素
  // 事件委托，兼容动态渲染的磁吸元素。
  document.addEventListener('pointermove', (e) => {
    // 先复位所有磁吸元素，再只偏移当前命中的那个。
    const b = (e.target as El).closest('.magnetic') as El | null
    // 每帧先复位，保证同一时刻只有一个元素被磁吸。
    $$('.magnetic').forEach((el) => (el.style.transform = ''))
    if (b) {
      // 相对按钮中心计算偏移量。
      const r = b.getBoundingClientRect()
      // 偏移量与到中心的距离成正比，形成轻微吸附感。
      b.style.transform =
        'translate(' + (e.clientX - r.left - r.width / 2) * 0.12 + 'px,' + (e.clientY - r.top - r.height / 2) * 0.2 + 'px)'
    }
  })
}
/**
 * 3D 倾斜卡片效果。
 *
 * 根据指针在卡片内的相对位置计算 rotateX/rotateY（最大约 ±8°/±10°），
 * 同时写入 --mx/--my 供高光层跟随。
 * pointerout 且真正移出卡片时复位，避免残留倾斜。
 */
function initTilt() {
  document.addEventListener('pointermove', (e) => {
    // 只处理 .tilt 卡片；其他元素直接忽略。
    const c = (e.target as El).closest('.tilt') as El | null
    if (!c) return
    const r = c.getBoundingClientRect()
    // 以卡片中心为原点，纵向位置映射为绕 X 轴旋转。
    const rx = ((e.clientY - r.top) / r.height - 0.5) * -8
    // 横向位置映射为绕 Y 轴旋转，与 rx 组合成 3D 倾斜。
    const ry = ((e.clientX - r.left) / r.width - 0.5) * 10
    // perspective 提供近大远小的立体感。
    c.style.transform = 'perspective(900px) rotateX(' + rx + 'deg) rotateY(' + ry + 'deg)'
    // 高光层用这两个百分比定位，增强立体感。
    c.style.setProperty('--mx', ((e.clientX - r.left) / r.width) * 100 + '%')
    c.style.setProperty('--my', ((e.clientY - r.top) / r.height) * 100 + '%')
  })
  // 指针离开卡片时复位倾斜。
  document.addEventListener('pointerout', (e) => {
    const c = (e.target as El).closest('.tilt') as El | null
    // 只有真正离开卡片才复位，移动到子元素时不抖动。
    if (c && !c.contains(e.relatedTarget as Node)) c.style.transform = ''
  })
}

// 数字滚动使用 rAF 而非定时器，保证与屏幕刷新同步。
/* ---------------- 数字滚动 ---------------- */
/**
 * 数字从 0 滚动到目标值。
 *
 * @param node 承载文本的 DOM 节点
 * @param to 目标数值
 * @param opt 时长、后缀与自定义格式化函数
 * 使用 requestAnimationFrame 与 ease-out-cubic 缓动，
 * 结束时精确落在目标值；节点内容会被持续覆盖。
 */
function countUp(node: El, to: number, opt: { dur?: number; suffix?: string; format?: (v: number) => string } = {}) {
  // 默认 1.1 秒，符合页面入场节奏。
  const dur = opt.dur || 1100
  const suffix = opt.suffix || ''
  // 可选格式化：金额、百分比等由调用方决定。
  const fmt = opt.format
  const from = 0
  // 首帧时间戳，用于把真实时间换算为 0~1 进度。
  let start: number | null = null
  // 进度 p 从 0 到 1；三次缓出使数字先快后慢。
  function step(ts: number) {
    // 记录首帧时间，后续按时间差计算进度。
    if (!start) start = ts
    const p = Math.min(1, (ts - start) / dur)
    // ease-out-cubic：起步快、收尾稳。
    const e = 1 - Math.pow(1 - p, 3)
    // 取整避免小数抖动；格式化函数可自行处理货币。
    const v = Math.round(from + (to - from) * e)
    // 后缀（如「件」）在格式化之后拼接。
    node.textContent = (fmt ? fmt(v) : String(v)) + suffix
    // 到 1 后停止续帧，避免空转。
    if (p < 1) requestAnimationFrame(step)
  }
  // 启动循环；step 内部按目标时长计算进度。
  requestAnimationFrame(step)
}

/**
 * 批量初始化数字滚动。
 *
 * 扫描带 data-count 的节点，读取目标值、后缀与格式，
 * 按顺序错峰启动 countUp；同一节点只执行一次。
 * 通过 __counted 标记避免 MutationObserver 反复触发时重复计数。
 */
function initCounts() {
  $$('[data-count]').forEach((n, i) => {
    // 已计数的节点直接跳过；属性变化时会把标记删掉以允许重播。
    if ((n as El & { __counted?: boolean }).__counted) return
    ;(n as El & { __counted?: boolean }).__counted = true
    // 非法或缺失的目标值按 0 处理，避免 NaN 渲染。
    const to = parseFloat(n.getAttribute('data-count') || '0') || 0
    const suffix = n.getAttribute('data-suffix') || ''
    // 支持 money 预设格式；其他值使用默认数字格式。
    const fmt = n.getAttribute('data-fmt')
    // 按索引递增延迟，形成自上而下的错峰入场。
    setTimeout(
      () =>
        countUp(n, to, {
          suffix,
          // 与单次 countUp 默认时长一致。
          dur: 1100,
          // 金额预设不保留小数，符合仪表盘概览的数字密度。
          format: fmt === 'money' ? (v) => '¥' + v.toFixed(0) : undefined,
        }),
      150 + i * 90,
    )
  })
}

// 命令列表保存在模块级变量中，业务页面通过 setCmdItems 注入；
// 面板本身只负责展示、过滤与键盘导航。
/* ---------------- 命令面板（Ctrl/Cmd+K） ---------------- */
/**
 * 命令面板条目。
 *
 * label 为展示与搜索文本；hint 是右侧快捷键提示；
 * href 与 fn 二选一：前者跳转，后者执行本地动作。
 */
export interface CmdItem {
  // 条目主文案，也是搜索匹配的字段。
  label: string
  // 右侧快捷键/说明，仅展示不参与搜索。
  hint?: string
  // 跳转地址；执行时优先于 fn。
  href?: string
  // 本地动作回调；没有 href 时执行。
  fn?: () => void
}
// 当前可用命令；由业务页面通过 setCmdItems 注入。
let cmdkItems: CmdItem[] = []
/**
 * 注册命令面板条目。
 *
 * @param items 命令列表；再次调用会整体替换
 * 副作用：只更新模块内变量，面板下次打开时生效。
 * 未打开面板时调用不会触发渲染，开销可忽略。
 */
export function setCmdItems(items: CmdItem[]) {
  cmdkItems = items
}
/**
 * 命令面板（Ctrl/Cmd+K）。
 *
 * 首次调用时创建遮罩与输入框，之后复用同一实例。
 * 支持：快捷键开关、输入过滤、上下键选择、Enter 执行、Esc/点击遮罩关闭。
 * 面板内的 SoundFX 反馈与页面其它交互保持一致。
 */
function initCmdK() {
  // 复用已存在的面板，支持重复初始化而不产生多份 DOM。
  let mask = $('.cmdk-mask')
  // 首次初始化：构建面板骨架。
  if (!mask) {
    mask = document.createElement('div')
    // 类名与全局样式约定一致。
    mask.className = 'cmdk-mask'
    // 输入框 + 列表 + 底部操作提示；样式由全局 CSS 提供。
    mask.innerHTML =
      '<div class="cmdk"><div class="cmdk-input"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg><input placeholder="搜索命令…"></div><div class="cmdk-list"></div><div class="cmdk-foot"><span>↑↓ 选择</span><span>Enter 执行</span><span>Esc 关闭</span></div></div>'
    document.body.appendChild(mask)
  }
  // 缓存输入框、列表与选中下标，避免每次操作重新查询。
  const input = $('.cmdk-input input', mask) as HTMLInputElement
  const list = $('.cmdk-list', mask) as El
  // 当前键盘选中项下标；列表重新渲染后归零。
  let sel = 0
  // 当前过滤结果；未输入时等于全部命令。
  let filtered: CmdItem[] = cmdkItems
  // 根据 filtered 重建列表；每次都重新绑定点击与悬停事件。
  function render() {
    // 先清空再重建，列表规模很小，无需做增量 diff。
    list.innerHTML = ''
    // 每项绑定点击与悬停；命令数量很小，无需虚拟列表。
    filtered.forEach((it, i) => {
      // 每个命令一个节点，样式由 cmdk-item 控制。
      const d = document.createElement('div')
      // sel 类表示键盘选中项，与鼠标悬停共用同一状态。
      d.className = 'cmdk-item' + (i === sel ? ' sel' : '')
      // 命令由业务代码注册，视为可信内容。
      d.innerHTML =
        '<span>' + it.label + '</span>' + (it.hint ? '<kbd>' + it.hint + '</kbd>' : '')
      // 点击直接执行对应命令。
      d.addEventListener('click', () => go(it))
      // 鼠标移入同步选中下标，保证键盘与鼠标操作不冲突。
      d.addEventListener('mouseenter', () => {
        // 鼠标悬停同步选中态。
        sel = i
        paint()
      })
      // 逐个挂载，保持与过滤结果顺序一致。
      list.appendChild(d)
    })
  }
  // 只更新选中样式，不重建 DOM，键盘移动时更流畅。
  function paint() {
    $$('.cmdk-item', list).forEach((n, i) => n.classList.toggle('sel', i === sel))
  }
  // 执行命令：先收起面板并播放反馈音，再跳转或调用回调。
  function go(it: CmdItem) {
    // 先关闭面板，避免动作触发导航或弹窗后面板残留。
    // 执行命令前先收起面板，并给一声轻反馈。
    mask!.classList.remove('show')
    SoundFX.pop()
    // href 优先；整页跳转可兼容后台不同入口。
    if (it.href) location.href = it.href
    // href 与 fn 都未提供时只关闭面板，视为空动作。
    else if (it.fn) it.fn()
  }
  // 打开面板：重置输入与选中项，聚焦输入框。
  function open() {
    // show 类触发遮罩淡入。
    mask!.classList.add('show')
    input.value = ''
    // 每次打开都重置过滤，展示完整命令列表。
    filtered = cmdkItems
    sel = 0
    render()
    // 等遮罩过渡开始后再聚焦，避免移动端键盘弹跳。
    setTimeout(() => input.focus(), 50)
    SoundFX.pop()
  }
  // Ctrl+K / Cmd+K 切换面板；preventDefault 阻止浏览器默认搜索。
  document.addEventListener('keydown', (e) => {
    // 同时兼容 Windows/Linux（Ctrl）与 macOS（Cmd）。
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      // 阻止浏览器把该组合键当作地址栏搜索。
      e.preventDefault()
      // 已打开则关闭，否则打开，实现快捷键切换。
      mask!.classList.contains('show') ? mask!.classList.remove('show') : open()
    }
  })
  // 输入即过滤：大小写不敏感的子串匹配，过滤后重置选中项。
  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase()
    // 子串匹配足够命令列表规模；无需引入模糊搜索依赖。
    filtered = cmdkItems.filter((it) => it.label.toLowerCase().indexOf(q) > -1)
    sel = 0
    render()
  })
  // 点击遮罩空白处关闭；点击面板内容不冒泡关闭。
  mask.addEventListener('click', (e) => {
    // 只有点击遮罩本身才关闭，点击面板内部不关闭。
    if (e.target === mask) mask.classList.remove('show')
  })
  // 任何带 data-cmdk 的元素都可作为打开入口（如顶栏搜索按钮）。
  document.addEventListener('click', (e) => {
    if ((e.target as El).closest('[data-cmdk]')) open()
  })
}

// 所有子模块均通过事件委托或单例工作，initFx 设计为只调用一次。
/* ---------------- 初始化入口 ---------------- */
/**
 * 初始化全部全局特效。
 *
 * 应用挂载后调用一次即可；内部各模块自身幂等，
 * 但由于会注册全局监听，仍应避免重复调用。
 *
 * 副作用：注入光标 DOM、注册点击/指针/键盘监听、启动 rAF 循环；
 * 最后用 MutationObserver 监听 SPA 动态渲染，自动补初始化新节点。
 */
export function initFx() {
  // 揭示入场与点击涟漪：一次性注册，通过事件委托覆盖后续节点。
  initReveal()
  initRipple()
  // 磁吸按钮：全局指针监听，动态节点同样生效。
  initMagnetic()
  // 3D 倾斜卡片：同样基于事件委托。
  initTilt()
  // 齿轮光标：内部会检查设备能力与无障碍偏好。
  // 数字滚动首次扫描 + 命令面板懒创建。
  initCounts()
  // 命令面板：首次调用时构建 DOM，之后复用。
  initCmdK()
  // SPA 动态渲染:监听 DOM 变化,自动初始化新出现的 reveal / data-count 元素
  /**
   * SPA 动态渲染适配。
   *
   * 新节点插入时重新扫描 reveal 与 data-count；
   * data-count 属性变化时清除 __counted 标记，允许重新计数。
   * 观察范围限定 body 子树，重复调用初始化是安全的。
   */
  const mo = new MutationObserver((mutations) => {
    // 属性变化单独处理：重置计数标记，让新目标值可以重新播放动画。
    mutations.forEach((m) => {
      // 仅关心 data-count 变化，忽略其它属性以降低开销。
      if (m.type === 'attributes' && m.attributeName === 'data-count') {
        delete (m.target as El & { __counted?: boolean }).__counted
      }
    })
    // 新节点可能包含 reveal 或计数元素，增量初始化。
    initReveal()
    // 重新扫描动态插入的计数节点。
    initCounts()
  })
  // 只观察业务关心的变化，避免全属性监听带来不必要的回调。
  mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-count'] })
}

// 音效单例供业务代码直接调用（如成功提示、开关状态查询）。
export { SoundFX }
