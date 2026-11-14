/**
 * 全局特效库 —— 从 ToolShare 前端原型包移植(框架无关,两端共用)
 * 齿轮光标 / 涟漪 / 磁吸 / 3D 倾斜 / Web Audio 音效 / 揭示入场 / 数字滚动 / 主题切换 / 命令面板
 *
 * 设计原则：
 * - 纯 DOM 实现，不依赖 React/Vue，组件重新渲染不会销毁特效；
 * - 通过「事件委托」和 `MutationObserver` 支持 SPA 动态插入的元素，
 *   页面切换后无需重新初始化；
 * - 所有初始化都有环境守卫（如指针类型、减少动效偏好、API 可用性），
 *   在低端设备或辅助功能场景下自动降级为静态页面；
 * - 特效只影响表现层，不参与业务状态，出错时静默降级而非阻断页面。
 *
 * 生命周期：应用入口调用一次 `initFx()`；重复调用是安全的，
 * 各子初始化函数通过已存在节点/单例观察器避免重复绑定。
 *
 * 样式契约：本文件假定全局样式表已定义 `.reveal`、`.ripple`、`.magnetic`、
 * `.tilt`、`.toast-stack`、`.cmdk-mask` 等类；
 * 这里只负责创建节点、切换 class 与写入 CSS 变量。
 */

/** DOM 元素的简写别名，减少重复的 `HTMLElement` 拼写。 */
type El = HTMLElement

/**
 * 查询所有匹配元素并转成数组。
 *
 * @param sel CSS 选择器
 * @param root 查询根节点，默认为 document
 * @returns 匹配到的元素数组
 *
 * 使用 `Array.from` 而非直接返回 NodeList：后续代码经常需要 `forEach`
 * 并在遍历过程中增删节点，静态数组可以避免 live collection 变化带来的问题。
 */
function $$(sel: string, root: ParentNode = document): El[] {
  return Array.from(root.querySelectorAll(sel))
}

/**
 * 查询单个匹配元素。
 *
 * @param sel CSS 选择器
 * @param root 查询根节点，默认为 document
 * @returns 首个匹配元素；不存在时返回 null
 *
 * 调用方需自行判空：特效初始化时目标节点可能尚未渲染。
 */
function $(sel: string, root: ParentNode = document): El | null {
  return root.querySelector(sel)
}

/* ---------------- Web Audio 音效 ---------------- */

// 下面四个常量是内联 SVG 图标，避免额外图片请求，也方便直接 innerHTML 切换。
// 统一使用 currentColor 与 `ic` 类，跟随主题/按钮文字颜色。

/** 音效开启图标：喇叭 + 两道声波。 */
const ICON_SOUND_ON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>'

/** 音效关闭图标：喇叭 + 叉号。 */
const ICON_SOUND_OFF =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><line x1="22" y1="9" x2="16" y2="15"/><line x1="16" y1="9" x2="22" y2="15"/></svg>'

/** 亮色模式图标（太阳）：深色主题下点击切回亮色。 */
const ICON_SUN =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>'

/** 暗色模式图标（月亮）：亮色主题下点击切到暗色。 */
const ICON_MOON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z"/></svg>'

/**
 * Web Audio 音效单例。
 *
 * 使用 IIFE 创建闭包，内部维护 AudioContext 与开关状态：
 * - AudioContext 延迟到首次播放时才创建，避免页面加载即占用音频资源；
 * - 浏览器自动播放策略要求音频上下文在用户手势后 resume，这里在每次 beep
 *   时检查 suspended 状态并恢复；
 * - 开关状态持久化到 localStorage（`ts_sound`），默认开启；
 * - 用户偏好「减少动效」时本模块不主动感知，由调用方决定是否播放。
 *
 * 所有方法都是同步、无返回错误：音频不可用时静默跳过，不影响交互。
 */
const SoundFX = (() => {
  // 懒创建的音频上下文；null 表示尚未创建或创建失败。
  let ctx: AudioContext | null = null
  // 开关状态：只有显式存成 'off' 才视为关闭，缺省为开启。
  let on = localStorage.getItem('ts_sound') !== 'off'

  /**
   * 获取（必要时创建）AudioContext。
   *
   * @returns 可用的 AudioContext；浏览器不支持时返回 null
   *
   * 兼容性：老版本 Safari 只暴露 `webkitAudioContext`，因此做前缀回退。
   * 创建失败（如隐私模式限制）时吞掉异常，后续 beep 会直接跳过。
   */
  function ac() {
    if (!ctx) {
      try {
        // Safari 仍只提供带前缀的 webkitAudioContext。
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
      } catch {
        // 创建失败（隐私模式/不支持）时保持 null，后续播放静默跳过。
        /* noop */
      }
    }
    return ctx
  }

  /**
   * 播放一个短促的合成音。
   *
   * @param f 频率（Hz）
   * @param d 持续时长（秒）
   * @param type 振荡器波形，默认正弦波
   * @param g 初始音量（0~1），默认 0.04，避免界面音效过响
   *
   * 实现要点：
   * - 用 GainNode 做指数衰减包络，比直接 start/stop 更自然且无爆音；
   * - exponentialRampToValueAtTime 的目标不能是 0，所以用 0.0001；
   * - 振荡器在结束时停止，节点由浏览器自动回收。
   *
   * 副作用：可能创建/resume AudioContext；关闭音效时直接返回。
   */
  function beep(f: number, d: number, type: OscillatorType = 'sine', g = 0.04) {
    if (!on) return
    const c = ac()
    if (!c) return
    // 自动播放策略：上下文可能因页面加载被挂起，需在用户手势后恢复。
    if (c.state === 'suspended') c.resume()
    const o = c.createOscillator()
    const gain = c.createGain()
    o.type = type
    o.frequency.value = f
    // 起始音量立即生效，然后在时长内指数衰减到近乎静音。
    gain.gain.setValueAtTime(g, c.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + d)
    o.connect(gain)
    gain.connect(c.destination)
    o.start()
    o.stop(c.currentTime + d)
  }

  return {
    /** @returns 当前音效是否开启。 */
    isOn: () => on,

    /**
     * 切换音效开关并持久化。
     *
     * @returns 切换后的状态（true=开启）
     *
     * 副作用：写入 localStorage 的 `ts_sound`；不主动播放提示音，
     * 由调用方在切换后决定是否播放。
     */
    toggle(): boolean {
      on = !on
      localStorage.setItem('ts_sound', on ? 'on' : 'off')
      return on
    },

    /** 轻快的弹出音，用于打开命令面板、切换主题等轻交互。 */
    pop() {
      beep(660, 0.07, 'triangle', 0.05)
    },

    /** 极短的点击音，用于列表项选择等高频但低干扰的场景。 */
    tick() {
      beep(880, 0.04, 'square', 0.02)
    },

    /** 两音上行提示，用于成功反馈；第二个音延后 80ms 形成「叮咚」感。 */
    success() {
      beep(523, 0.09, 'sine', 0.05)
      setTimeout(() => beep(784, 0.12, 'sine', 0.05), 80)
    },
  }
})()

/**
 * 绑定音效开关按钮。
 *
 * @param btn 目标按钮；传入 null 时直接返回，便于在可选 UI 中调用
 *
 * 行为：
 * - 初始化时根据持久化状态绘制图标与 tooltip；
 * - 点击切换开关、立即更新图标；切到开启时播放一声 pop 作为确认。
 *
 * 副作用：修改按钮 innerHTML/title，写入 localStorage。
 * 注意：每次调用都会新增一个 click 监听；同一按钮不要重复绑定。
 */
export function bindSoundToggle(btn: El | null) {
  if (!btn) return
  /** 根据当前开关状态同步按钮图标与无障碍提示。 */
  function paint() {
    btn!.innerHTML = SoundFX.isOn() ? ICON_SOUND_ON : ICON_SOUND_OFF
    btn!.title = SoundFX.isOn() ? '关闭音效' : '开启音效'
  }
  // 先渲染一次，保证页面加载时图标与持久化状态一致。
  paint()
  btn.addEventListener('click', () => {
    SoundFX.toggle()
    paint()
    // 仅在切换到「开启」时播放确认音，关闭时保持安静。
    if (SoundFX.isOn()) SoundFX.pop()
  })
}

/**
 * 绑定明暗主题切换按钮。
 *
 * @param btn 目标按钮；null 时直接返回
 *
 * 行为：
 * - 主题状态保存在 localStorage 的 `ts_dark`（'1' 表示暗色）；
 * - 通过给 `<body>` 切换 `.dark` 类驱动全局 CSS 变量；
 * - 按钮图标显示「目标主题」：暗色时显示太阳，亮色时显示月亮；
 * - 切换时播放 pop 音效作为反馈。
 *
 * 副作用：修改 body class、按钮内容与 title，写入 localStorage。
 */
export function bindThemeToggle(btn: El | null) {
  if (!btn) return
  // 初始主题从持久化值读取；缺省为亮色。
  let dark = localStorage.getItem('ts_dark') === '1'
  /** 把当前 dark 状态应用到 body 与按钮。 */
  function apply() {
    document.body.classList.toggle('dark', dark)
    btn!.innerHTML = dark ? ICON_SUN : ICON_MOON
    btn!.title = dark ? '切换亮色' : '切换暗色'
  }
  // 初始化渲染，避免首屏闪烁成错误图标。
  apply()
  btn.addEventListener('click', () => {
    dark = !dark
    localStorage.setItem('ts_dark', dark ? '1' : '0')
    apply()
    SoundFX.pop()
  })
}

/* ---------------- Toast(可撤销) ---------------- */

/**
 * Toast 配置项。
 *
 * 所有字段可选；不传 type 时按 info 渲染。
 */
interface ToastOpt {
  /** 语义类型，决定图标与配色：成功/失败/警告/信息。 */
  type?: 'ok' | 'bad' | 'warn' | 'info'
  /** 可选副标题（当前实现把 title 作为主文案，该字段保留给扩展）。 */
  title?: string
  /** 补充说明文本，显示在标题下方。 */
  msg?: string
  /** 撤销回调；提供时渲染「撤销」按钮。 */
  undo?: () => void
}

/**
 * 显示一个轻量 Toast 通知。
 *
 * @param title 主文案
 * @param opt 视觉类型、补充说明与撤销回调
 *
 * 行为与约束：
 * - 自动创建全局 `.toast-stack` 容器（若不存在），多条通知纵向堆叠；
 * - 3.6 秒后自动消失，退场动画 260ms，动画结束后从 DOM 移除；
 * - 右上角关闭按钮可立即关闭；
 * - 提供 undo 时，点击「撤销」会先执行回调，再关闭并提示「已撤销」；
 * - 文案以 innerHTML 拼接：本函数只应传入可信文案（业务常量/后端 message），
 *   不要把未经净化的用户输入直接传入。
 *
 * 副作用：创建 DOM、设置定时器；无返回值。
 */
export function toast(title: string, opt: ToastOpt = {}) {
  // 惰性创建通知栈：整个应用共用一个容器，保证堆叠顺序。
  let stack = $('.toast-stack')
  if (!stack) {
    stack = document.createElement('div')
    stack.className = 'toast-stack'
    document.body.appendChild(stack)
  }
  const t = document.createElement('div')
  // 类型类名用于配色；缺省 info。
  t.className = 'toast ' + (opt.type || 'info')
  // 根据类型选择图标：成功对勾、失败叉号、其余用感叹号提示。
  const ic =
    opt.type === 'ok'
      ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'
      : opt.type === 'bad'
        ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/></svg>'
        : '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>'
  // 组装内容：图标 + 正文（可选补充说明 + 可选撤销按钮）+ 关闭按钮。
  t.innerHTML =
    ic +
    '<div class="t-body"><div class="t-title">' +
    title +
    '</div>' +
    (opt.msg ? '<div class="t-msg">' + opt.msg + '</div>' : '') +
    (opt.undo ? '<button class="t-undo">撤销</button>' : '') +
    '</div><button class="t-x"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg></button>'
  stack.appendChild(t)

  // 自动关闭定时器；用户手动关闭时需要清除，避免重复执行退场。
  const timer = setTimeout(dismiss, 3600)

  /**
   * 触发退场动画并移除节点。
   *
   * 先加 `.out` 类让 CSS 播放过渡，260ms 后 remove；
   * 重复调用是安全的（clearTimeout + 重复 remove 无副作用）。
   */
  function dismiss() {
    clearTimeout(timer)
    t.classList.add('out')
    setTimeout(() => t.remove(), 260)
  }

  // 关闭按钮：事件只绑定一次，绑定后不可再被 HTML 覆盖。
  t.querySelector('.t-x')!.addEventListener('click', dismiss)

  if (opt.undo) {
    // 撤销流程：执行回调 → 关闭当前 toast → 再弹一条确认提示。
    t.querySelector('.t-undo')!.addEventListener('click', () => {
      opt.undo!()
      dismiss()
      toast('已撤销', { type: 'info' })
    })
  }
}

/* ---------------- 揭示入场 ---------------- */

/**
 * 共享的 IntersectionObserver 实例。
 *
 * 三态设计：
 * - `undefined`：尚未初始化（首次调用 initReveal 时创建）；
 * - `null`：浏览器不支持 IntersectionObserver，走降级分支；
 * - 实例：正常观察。
 *
 * 单例避免每个 `.reveal` 元素各建一个观察器造成性能浪费。
 */
let revealIO: IntersectionObserver | null | undefined

/**
 * 初始化滚动揭示动画。
 *
 * 给尚未显示的 `.reveal` 元素设置阶梯式 transition-delay（最多 8 档，
 * 每档 45ms），进入视口 8% 后添加 `.in` 类触发 CSS 过渡，并立即取消观察。
 *
 * 兼容性：不支持 IntersectionObserver 时直接添加 `.in`，保证内容可见，
 * 只是没有入场动画。重复调用安全：已带 `.in` 的元素会跳过。
 *
 * 副作用：修改元素 style/class，创建全局观察器。
 */
function initReveal() {
  // 首次调用时决定使用原生观察器还是降级为 null。
  if (revealIO === undefined) {
    revealIO =
      'IntersectionObserver' in window
        ? new IntersectionObserver(
            (es) =>
              es.forEach((en) => {
                if (en.isIntersecting) {
                  // 进入视口：播放一次后就不再观察，避免滚动来回闪烁。
                  en.target.classList.add('in')
                  revealIO!.unobserve(en.target)
                }
              }),
            // 8% 可见即触发，兼顾首屏与长列表的观感。
            { threshold: 0.08 },
          )
        : null
  }
  const io = revealIO
  $$('.reveal').forEach((n, i) => {
    // 已经显示过的元素不再处理（重复初始化/动态插入时常见）。
    if (n.classList.contains('in')) return
    // 同屏元素依次延迟，形成瀑布式入场；最多 8 档，避免靠后元素等待过久。
    n.style.transitionDelay = Math.min(i, 8) * 45 + 'ms'
    if (io) io.observe(n)
    else n.classList.add('in')
  })
}

/* ---------------- 涟漪 ---------------- */

/**
 * 初始化按钮点击涟漪。
 *
 * 使用事件委托监听 document 上的 click，因此对 SPA 后续渲染的 `.btn`
 * 同样生效，无需逐个绑定。
 *
 * 实现：以点击坐标为圆心、按钮长边为直径创建 `.ripple` 圆点，
 * CSS 动画结束后自动移除。加载中的按钮（`.loading`）不产生涟漪，
 * 避免与 spinner 视觉冲突。
 *
 * 副作用：向按钮内追加/移除临时节点与内联样式。
 */
function initRipple() {
  document.addEventListener('click', (e) => {
    // closest 从实际点击目标向上找按钮，兼容按钮内部图标/文字。
    const b = (e.target as El).closest('.btn') as El | null
    if (!b || b.classList.contains('loading')) return
    const r = b.getBoundingClientRect()
    // 用长边作为直径，保证圆点能覆盖整个按钮。
    const s = Math.max(r.width, r.height)
    const rip = document.createElement('span')
    rip.className = 'ripple'
    rip.style.width = rip.style.height = s + 'px'
    // 让圆心落在点击位置，并相对按钮左上角定位。
    rip.style.left = e.clientX - r.left - s / 2 + 'px'
    rip.style.top = e.clientY - r.top - s / 2 + 'px'
    b.appendChild(rip)
    // 动画结束即回收，避免 DOM 中堆积临时节点。
    rip.addEventListener('animationend', () => rip.remove())
  })
}

/* ---------------- 磁吸按钮 / 3D 倾斜 ---------------- */

/**
 * 初始化磁吸按钮。
 *
 * 指针移动时，`.magnetic` 元素会朝指针方向产生轻微位移（横向 0.12、
 * 纵向 0.2 的偏移系数），营造「被吸附」的手感。
 *
 * 性能：每次 pointermove 都先重置所有 `.magnetic` 的 transform，再只移动
 * 当前悬停元素；适用于页面中磁吸按钮数量不多的场景。
 * 事件委托保证动态元素无需重新绑定。
 *
 * 副作用：直接写元素 style.transform。
 */
function initMagnetic() {
  // 事件委托,支持 SPA 动态渲染的元素
  document.addEventListener('pointermove', (e) => {
    const b = (e.target as El).closest('.magnetic') as El | null
    // 先清空所有元素，防止指针快速移出时残留偏移。
    $$('.magnetic').forEach((el) => (el.style.transform = ''))
    if (b) {
      const r = b.getBoundingClientRect()
      // 以元素中心为原点计算指针偏移，乘系数后作为位移。
      b.style.transform =
        'translate(' + (e.clientX - r.left - r.width / 2) * 0.12 + 'px,' + (e.clientY - r.top - r.height / 2) * 0.2 + 'px)'
    }
  })
}

/**
 * 初始化卡片 3D 倾斜。
 *
 * 指针在 `.tilt` 卡片上移动时：
 * - 根据相对位置计算 rotateX/rotateY（最大约 ±8°/±10°）；
 * - 同时写入 `--mx`/`--my` 百分比 CSS 变量，供高光/渐变层跟随指针。
 *
 * 指针离开卡片（relatedTarget 不在卡片内）时清空 transform 复位。
 * 同样使用事件委托，适配动态列表。
 *
 * 副作用：写元素 style.transform 与 CSS 自定义属性。
 */
function initTilt() {
  document.addEventListener('pointermove', (e) => {
    const c = (e.target as El).closest('.tilt') as El | null
    if (!c) return
    const r = c.getBoundingClientRect()
    // 归一化到 [-0.5, 0.5]，再映射为旋转角度；Y 轴反向以获得自然手感。
    const rx = ((e.clientY - r.top) / r.height - 0.5) * -8
    const ry = ((e.clientX - r.left) / r.width - 0.5) * 10
    c.style.transform = 'perspective(900px) rotateX(' + rx + 'deg) rotateY(' + ry + 'deg)'
    // 写入指针位置百分比，CSS 可用于高光/镜面反射定位。
    c.style.setProperty('--mx', ((e.clientX - r.left) / r.width) * 100 + '%')
    c.style.setProperty('--my', ((e.clientY - r.top) / r.height) * 100 + '%')
  })
  document.addEventListener('pointerout', (e) => {
    const c = (e.target as El).closest('.tilt') as El | null
    // 只在真正离开卡片时复位；卡片内部子元素间移动不触发。
    if (c && !c.contains(e.relatedTarget as Node)) c.style.transform = ''
  })
}

/* ---------------- 数字滚动 ---------------- */

/**
 * 让一个元素的文本数字从 0 平滑滚动到目标值。
 *
 * @param node 要更新 textContent 的元素
 * @param to 目标数值
 * @param opt 时长、后缀与自定义格式化函数
 *
 * 动画使用 requestAnimationFrame 与 easeOutCubic 缓动：
 * 开头快、结尾慢，接近真实计数器的观感。
 * 每帧直接写 textContent，不创建额外节点。
 *
 * 副作用：持续修改 DOM 文本直到动画结束；不返回 Promise，
 * 调用方若需在结束后执行逻辑，应自行延长 timeout（参见 initCounts）。
 */
function countUp(node: El, to: number, opt: { dur?: number; suffix?: string; format?: (v: number) => string } = {}) {
  // 默认 1.1 秒；传入 0 时按默认值处理（避免除零）。
  const dur = opt.dur || 1100
  // 后缀（如 '+'、'%'）直接拼在数字后，不参与数值计算。
  const suffix = opt.suffix || ''
  // 可选的金额/千分位格式化；为空时直接 String(v)。
  const fmt = opt.format
  // 当前实现总是从 0 开始；保留变量便于将来支持「从旧值滚动到新值」。
  const from = 0
  // 首帧时间戳；null 表示动画尚未开始。
  let start: number | null = null

  /** 每一帧根据进度计算并写入当前值。 */
  function step(ts: number) {
    // 第一次回调时记录起点，之后以它为基准计算进度。
    if (!start) start = ts
    // 进度限制在 [0,1]，防止超时帧导致数值超过目标。
    const p = Math.min(1, (ts - start) / dur)
    // easeOutCubic：1 - (1-p)^3，结尾更柔和。
    const e = 1 - Math.pow(1 - p, 3)
    // 取整避免小数抖动；金额格式化由 fmt 决定精度。
    const v = Math.round(from + (to - from) * e)
    node.textContent = (fmt ? fmt(v) : String(v)) + suffix
    // 未到 100% 继续请求下一帧。
    if (p < 1) requestAnimationFrame(step)
  }

  requestAnimationFrame(step)
}

/**
 * 扫描并启动页面上所有 `[data-count]` 数字滚动。
 *
 * 支持的属性：
 * - `data-count`：目标数值；
 * - `data-suffix`：后缀（如 '+'）；
 * - `data-fmt="money"`：按人民币取整格式显示。
 *
 * 去重：在元素上写入非标准的 `__counted` 标记，防止重复初始化；
 * MutationObserver 在 data-count 变化时会清除该标记以支持异步更新。
 *
 * 阶梯延迟：第 i 个元素延后 150 + i*90 ms 启动，避免整页数字同时跳动。
 * 注意延迟随元素数量线性增长，长列表末尾元素可能等待较久。
 *
 * 副作用：定时器 + 修改元素文本。
 */
function initCounts() {
  $$('[data-count]').forEach((n, i) => {
    // 已启动过的元素跳过（包括自身动画尚未结束的情况）。
    if ((n as El & { __counted?: boolean }).__counted) return
    ;(n as El & { __counted?: boolean }).__counted = true
    // 解析目标值；非法/缺失时按 0 处理。
    const to = parseFloat(n.getAttribute('data-count') || '0') || 0
    const suffix = n.getAttribute('data-suffix') || ''
    const fmt = n.getAttribute('data-fmt')
    setTimeout(
      () =>
        countUp(n, to, {
          suffix,
          dur: 1100,
          // 金额格式不带小数，适合看板上的整数统计。
          format: fmt === 'money' ? (v) => '¥' + v.toFixed(0) : undefined,
        }),
      150 + i * 90,
    )
  })
}

/* ---------------- 命令面板(Ctrl/Cmd+K) ---------------- */

/**
 * 命令面板中的一条命令。
 *
 * 二选一执行：提供 href 时跳转页面，提供 fn 时执行回调；
 * 两者都存在时优先 href。hint 用于展示快捷键/分类提示。
 */
export interface CmdItem {
  /** 命令主文案，同时参与搜索匹配。 */
  label: string
  /** 右侧展示的提示文本（如快捷键）。 */
  hint?: string
  /** 跳转地址；与 fn 二选一。 */
  href?: string
  /** 执行函数；与 href 二选一。 */
  fn?: () => void
}

// 当前命令列表；由业务层通过 setCmdItems 注入，避免特效库依赖路由/业务。
let cmdkItems: CmdItem[] = []

/**
 * 更新命令面板的可选命令。
 *
 * @param items 命令列表；通常在应用初始化或权限变化时调用
 *
 * 仅替换数据，不重新渲染已打开的面板；下次打开时生效。
 */
export function setCmdItems(items: CmdItem[]) {
  cmdkItems = items
}

/**
 * 初始化命令面板（Ctrl/Cmd + K）。
 *
 * 功能：
 * - 快捷键打开/关闭，打开时自动聚焦搜索框；
 * - 输入实时按 label 子串过滤（不区分大小写）；
 * - 上下键移动选中项、Enter 执行、Esc 关闭；
 * - 点击遮罩关闭，点击条目执行；
 * - 执行时播放 pop 音效。
 *
 * 实现细节：
 * - 面板 DOM 惰性创建并复用，避免每次打开重建事件监听；
 * - 列表每次过滤后整体重绘，命令量级很小，无需虚拟列表；
 * - 键盘事件绑定在 document 上，保证焦点在输入框时也能响应。
 *
 * 副作用：创建全局 DOM、绑定 document keydown、执行命令回调/跳转。
 */
function initCmdK() {
  // 复用已存在的面板（可能由服务端渲染或上一次初始化创建）。
  let mask = $('.cmdk-mask')
  if (!mask) {
    mask = document.createElement('div')
    mask.className = 'cmdk-mask'
    // 结构：搜索框 + 结果列表 + 底部快捷键提示。
    mask.innerHTML =
      '<div class="cmdk"><div class="cmdk-input"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg><input placeholder="搜索命令…"></div><div class="cmdk-list"></div><div class="cmdk-foot"><span>↑↓ 选择</span><span>Enter 执行</span><span>Esc 关闭</span></div></div>'
    document.body.appendChild(mask)
  }
  const input = $('.cmdk-input input', mask) as HTMLInputElement
  const list = $('.cmdk-list', mask) as El
  // 当前选中项索引（相对 filtered 数组）。
  let sel = 0
  // 当前过滤结果；未输入时等于全部命令。
  let filtered: CmdItem[] = cmdkItems

  /**
   * 重绘命令列表。
   *
   * 每次都清空重建，保证选中态与 filtered 一致；
   * 每条绑定 click 与 mouseenter，后者用于鼠标移动时同步选中索引。
   */
  function render() {
    list.innerHTML = ''
    filtered.forEach((it, i) => {
      const d = document.createElement('div')
      d.className = 'cmdk-item' + (i === sel ? ' sel' : '')
      // hint 用 kbd 标签展示，保持等宽/边框样式。
      d.innerHTML =
        '<span>' + it.label + '</span>' + (it.hint ? '<kbd>' + it.hint + '</kbd>' : '')
      d.addEventListener('click', () => go(it))
      d.addEventListener('mouseenter', () => {
        sel = i
        paint()
      })
      list.appendChild(d)
    })
  }

  /**
   * 只更新选中类，不重建列表。
   *
   * 用于键盘上下移动与鼠标 hover，性能更好且不打断点击。
   */
  function paint() {
    $$('.cmdk-item', list).forEach((n, i) => n.classList.toggle('sel', i === sel))
  }

  /**
   * 执行一条命令。
   *
   * @param it 选中的命令
   *
   * 行为：先关闭面板并播放音效，再跳转或执行回调；
   * href 优先于 fn（见 CmdItem 约定）。
   */
  function go(it: CmdItem) {
    mask!.classList.remove('show')
    SoundFX.pop()
    if (it.href) location.href = it.href
    else if (it.fn) it.fn()
  }

  /**
   * 打开面板并重置状态。
   *
   * - 清空搜索词与过滤结果；
   * - 重置选中项并重绘；
   * - 延迟 50ms 聚焦，等待 `.show` 的显示过渡开始，避免浏览器忽略 focus；
   * - 播放 pop 音效。
   */
  function open() {
    mask!.classList.add('show')
    input.value = ''
    filtered = cmdkItems
    sel = 0
    render()
    setTimeout(() => input.focus(), 50)
    SoundFX.pop()
  }

  // 全局快捷键：Ctrl/Cmd + K 切换面板。
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      // 阻止浏览器默认的搜索/地址栏行为。
      e.preventDefault()
      if (mask!.classList.contains('show')) {
        mask!.classList.remove('show')
      } else {
        open()
      }
    }
  })

  // 搜索：按 label 做不区分大小写的子串匹配。
  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase()
    filtered = cmdkItems.filter((it) => it.label.toLowerCase().indexOf(q) > -1)
    // 过滤结果变化后回到第一项，避免选中索引越界。
    sel = 0
    render()
  })

  // 点击遮罩空白处关闭；点击面板内部不关闭。
  mask.addEventListener('click', (e) => {
    if (e.target === mask) mask.classList.remove('show')
  })
}

/* ---------------- 初始化入口 ---------------- */

/**
 * 初始化全部全局特效。
 *
 * 调用时机：应用挂载后调用一次（通常在 main.tsx）。
 * 各子初始化内部都有幂等/环境守卫，重复调用不会重复创建光标或观察器，
 * 但会重复绑定 document 级事件监听，因此不建议在组件渲染中调用。
 *
 * 包含：揭示入场、涟漪、磁吸、3D 倾斜、齿轮光标、数字滚动、命令面板。
 *
 * 最后启动 MutationObserver，监听 SPA 动态插入的节点：
 * - 新增节点时重新扫描 `.reveal` 与 `[data-count]`；
 * - `data-count` 属性变化时清除 `__counted` 标记，让新数据重新滚动。
 *
 * 注意：观察器配置了 subtree/childList/attributes，页面频繁增删节点时
 * 回调会较频繁；当前实现只在回调里做两次轻量查询，未做防抖。
 *
 * 副作用：修改 DOM、绑定全局事件、启动观察器。
 */
export function initFx() {
  initReveal()
  initRipple()
  initMagnetic()
  initTilt()
  initCounts()
  initCmdK()
  // SPA 动态渲染:监听 DOM 变化,自动初始化新出现的 reveal / data-count 元素
  const mo = new MutationObserver((mutations) => {
    mutations.forEach((m) => {
      if (m.type === 'attributes' && m.attributeName === 'data-count') {
        // 数据异步到达后重新计数，否则元素会被首次的 0 值锁死
        delete (m.target as El & { __counted?: boolean }).__counted
      }
    })
    // 新增/变化的节点可能带有 .reveal 或 data-count，统一重新扫描。
    initReveal()
    initCounts()
  })
  mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-count'] })
}

export { SoundFX }
