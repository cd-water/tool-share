/**
 * 全局特效库 —— 从 ToolShare 前端原型包移植(框架无关,两端共用)
 * 齿轮光标 / 涟漪 / 磁吸 / 3D 倾斜 / Web Audio 音效 / 揭示入场 / 数字滚动 / 主题切换 / 命令面板
 */

type El = HTMLElement

function $$(sel: string, root: ParentNode = document): El[] {
  return Array.from(root.querySelectorAll(sel))
}
function $(sel: string, root: ParentNode = document): El | null {
  return root.querySelector(sel)
}

/* ---------------- Web Audio 音效 ---------------- */
const ICON_SOUND_ON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>'
const ICON_SOUND_OFF =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4V5z"/><line x1="22" y1="9" x2="16" y2="15"/><line x1="16" y1="9" x2="22" y2="15"/></svg>'
const ICON_SUN =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>'
const ICON_MOON =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z"/></svg>'

const SoundFX = (() => {
  let ctx: AudioContext | null = null
  let on = localStorage.getItem('ts_sound') !== 'off'
  function ac() {
    if (!ctx) {
      try {
        ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
      } catch {
        /* noop */
      }
    }
    return ctx
  }
  function beep(f: number, d: number, type: OscillatorType = 'sine', g = 0.04) {
    if (!on) return
    const c = ac()
    if (!c) return
    if (c.state === 'suspended') c.resume()
    const o = c.createOscillator()
    const gain = c.createGain()
    o.type = type
    o.frequency.value = f
    gain.gain.setValueAtTime(g, c.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + d)
    o.connect(gain)
    gain.connect(c.destination)
    o.start()
    o.stop(c.currentTime + d)
  }
  return {
    isOn: () => on,
    toggle(): boolean {
      on = !on
      localStorage.setItem('ts_sound', on ? 'on' : 'off')
      return on
    },
    pop() {
      beep(660, 0.07, 'triangle', 0.05)
    },
    tick() {
      beep(880, 0.04, 'square', 0.02)
    },
    success() {
      beep(523, 0.09, 'sine', 0.05)
      setTimeout(() => beep(784, 0.12, 'sine', 0.05), 80)
    },
  }
})()

export function bindSoundToggle(btn: El | null) {
  if (!btn) return
  function paint() {
    btn!.innerHTML = SoundFX.isOn() ? ICON_SOUND_ON : ICON_SOUND_OFF
    btn!.title = SoundFX.isOn() ? '关闭音效' : '开启音效'
  }
  paint()
  btn.addEventListener('click', () => {
    SoundFX.toggle()
    paint()
    if (SoundFX.isOn()) SoundFX.pop()
  })
}

export function bindThemeToggle(btn: El | null) {
  if (!btn) return
  let dark = localStorage.getItem('ts_dark') === '1'
  function apply() {
    document.body.classList.toggle('dark', dark)
    btn!.innerHTML = dark ? ICON_SUN : ICON_MOON
    btn!.title = dark ? '切换亮色' : '切换暗色'
  }
  apply()
  btn.addEventListener('click', () => {
    dark = !dark
    localStorage.setItem('ts_dark', dark ? '1' : '0')
    apply()
    SoundFX.pop()
  })
}

/* ---------------- Toast(可撤销) ---------------- */
interface ToastOpt {
  type?: 'ok' | 'bad' | 'warn' | 'info'
  title?: string
  msg?: string
  undo?: () => void
}
export function toast(title: string, opt: ToastOpt = {}) {
  let stack = $('.toast-stack')
  if (!stack) {
    stack = document.createElement('div')
    stack.className = 'toast-stack'
    document.body.appendChild(stack)
  }
  const t = document.createElement('div')
  t.className = 'toast ' + (opt.type || 'info')
  const ic =
    opt.type === 'ok'
      ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'
      : opt.type === 'bad'
        ? '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/></svg>'
        : '<svg class="ic t-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>'
  t.innerHTML =
    ic +
    '<div class="t-body"><div class="t-title">' +
    title +
    '</div>' +
    (opt.msg ? '<div class="t-msg">' + opt.msg + '</div>' : '') +
    (opt.undo ? '<button class="t-undo">撤销</button>' : '') +
    '</div><button class="t-x"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg></button>'
  stack.appendChild(t)
  const timer = setTimeout(dismiss, 3600)
  function dismiss() {
    clearTimeout(timer)
    t.classList.add('out')
    setTimeout(() => t.remove(), 260)
  }
  t.querySelector('.t-x')!.addEventListener('click', dismiss)
  if (opt.undo) {
    t.querySelector('.t-undo')!.addEventListener('click', () => {
      opt.undo!()
      dismiss()
      toast('已撤销', { type: 'info' })
    })
  }
}

/* ---------------- 揭示入场 ---------------- */
let revealIO: IntersectionObserver | null | undefined
function initReveal() {
  if (revealIO === undefined) {
    revealIO =
      'IntersectionObserver' in window
        ? new IntersectionObserver(
            (es) =>
              es.forEach((en) => {
                if (en.isIntersecting) {
                  en.target.classList.add('in')
                  revealIO!.unobserve(en.target)
                }
              }),
            { threshold: 0.08 },
          )
        : null
  }
  const io = revealIO
  $$('.reveal').forEach((n, i) => {
    if (n.classList.contains('in')) return
    n.style.transitionDelay = Math.min(i, 8) * 45 + 'ms'
    if (io) io.observe(n)
    else n.classList.add('in')
  })
}

/* ---------------- 涟漪 ---------------- */
function initRipple() {
  document.addEventListener('click', (e) => {
    const b = (e.target as El).closest('.btn') as El | null
    if (!b || b.classList.contains('loading')) return
    const r = b.getBoundingClientRect()
    const s = Math.max(r.width, r.height)
    const rip = document.createElement('span')
    rip.className = 'ripple'
    rip.style.width = rip.style.height = s + 'px'
    rip.style.left = e.clientX - r.left - s / 2 + 'px'
    rip.style.top = e.clientY - r.top - s / 2 + 'px'
    b.appendChild(rip)
    rip.addEventListener('animationend', () => rip.remove())
  })
}

/* ---------------- 磁吸按钮 / 3D 倾斜 ---------------- */
function initMagnetic() {
  // 事件委托,支持 SPA 动态渲染的元素
  document.addEventListener('pointermove', (e) => {
    const b = (e.target as El).closest('.magnetic') as El | null
    $$('.magnetic').forEach((el) => (el.style.transform = ''))
    if (b) {
      const r = b.getBoundingClientRect()
      b.style.transform =
        'translate(' + (e.clientX - r.left - r.width / 2) * 0.12 + 'px,' + (e.clientY - r.top - r.height / 2) * 0.2 + 'px)'
    }
  })
}
function initTilt() {
  document.addEventListener('pointermove', (e) => {
    const c = (e.target as El).closest('.tilt') as El | null
    if (!c) return
    const r = c.getBoundingClientRect()
    const rx = ((e.clientY - r.top) / r.height - 0.5) * -8
    const ry = ((e.clientX - r.left) / r.width - 0.5) * 10
    c.style.transform = 'perspective(900px) rotateX(' + rx + 'deg) rotateY(' + ry + 'deg)'
    c.style.setProperty('--mx', ((e.clientX - r.left) / r.width) * 100 + '%')
    c.style.setProperty('--my', ((e.clientY - r.top) / r.height) * 100 + '%')
  })
  document.addEventListener('pointerout', (e) => {
    const c = (e.target as El).closest('.tilt') as El | null
    if (c && !c.contains(e.relatedTarget as Node)) c.style.transform = ''
  })
}

/* ---------------- 数字滚动 ---------------- */
function countUp(node: El, to: number, opt: { dur?: number; suffix?: string; format?: (v: number) => string } = {}) {
  const dur = opt.dur || 1100
  const suffix = opt.suffix || ''
  const fmt = opt.format
  const from = 0
  let start: number | null = null
  function step(ts: number) {
    if (!start) start = ts
    const p = Math.min(1, (ts - start) / dur)
    const e = 1 - Math.pow(1 - p, 3)
    const v = Math.round(from + (to - from) * e)
    node.textContent = (fmt ? fmt(v) : String(v)) + suffix
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

/* ---------------- 齿轮光标 ---------------- */
function initGearCursor() {
  if (document.getElementById('gearCursor')) return
  if (!window.matchMedia('(pointer: fine)').matches) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const root = document.createElement('div')
  root.className = 'gear-cursor'
  root.id = 'gearCursor'
  root.setAttribute('aria-hidden', 'true')
  root.innerHTML =
    '<svg class="gc-main" viewBox="0 0 32 32">' +
    '<path d="M25.2 16.0 L29.3 17.7 L28.6 20.5 L24.2 20.2 L22.5 22.5 L24.2 26.6 L21.7 28.1 L18.8 24.7 L16.0 25.2 L14.3 29.3 L11.5 28.6 L11.8 24.2 L9.5 22.5 L5.4 24.2 L3.9 21.7 L7.3 18.8 L6.8 16.0 L2.7 14.3 L3.4 11.5 L7.8 11.8 L9.5 9.5 L7.8 5.4 L10.3 3.9 L13.2 7.3 L16.0 6.8 L17.7 2.7 L20.5 3.4 L20.2 7.8 L22.5 9.5 L26.6 7.8 L28.1 10.3 L24.7 13.2 Z" fill="#E29B3C" stroke="#FFFDF7" stroke-width="2.6" stroke-linejoin="round" paint-order="stroke"/>' +
    '<path d="M25.2 16.0 L29.3 17.7 L28.6 20.5 L24.2 20.2 L22.5 22.5 L24.2 26.6 L21.7 28.1 L18.8 24.7 L16.0 25.2 L14.3 29.3 L11.5 28.6 L11.8 24.2 L9.5 22.5 L5.4 24.2 L3.9 21.7 L7.3 18.8 L6.8 16.0 L2.7 14.3 L3.4 11.5 L7.8 11.8 L9.5 9.5 L7.8 5.4 L10.3 3.9 L13.2 7.3 L16.0 6.8 L17.7 2.7 L20.5 3.4 L20.2 7.8 L22.5 9.5 L26.6 7.8 L28.1 10.3 L24.7 13.2 Z" fill="none" stroke="#3A2810" stroke-width="1" stroke-linejoin="round"/>' +
    '<circle cx="16" cy="16" r="6.6" fill="none" stroke="rgba(255,253,247,.5)" stroke-width=".9"/>' +
    '<circle cx="16" cy="16" r="2.6" fill="#FFFDF7" stroke="#3A2810" stroke-width=".9"/></svg>' +
    '<svg class="gc-ghost" viewBox="0 0 18 18"><path d="M14.0 9.0 L16.5 10.1 L16.0 11.9 L13.3 11.6 L12.1 12.9 L12.8 15.6 L11.1 16.3 L9.7 14.0 L7.9 13.9 L6.3 16.1 L4.6 15.2 L5.5 12.6 L4.5 11.2 L1.8 11.3 L1.4 9.4 L4.0 8.6 L4.5 6.8 L2.7 4.8 L3.9 3.3 L6.2 4.8 L7.9 4.1 L8.4 1.4 L10.3 1.5 L10.5 4.2 L12.1 5.1 L14.5 3.8 L15.7 5.3 L13.7 7.2 Z" fill="#C2791F" stroke="#FFFDF7" stroke-width="1.6" stroke-linejoin="round" paint-order="stroke"/></svg>'
  document.body.appendChild(root)
  document.body.classList.add('gear-cursor-on')
  root.classList.add('ready')
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      if (document.body.contains(root) && getComputedStyle(root).display !== 'none')
        document.body.classList.add('cursor-swapped')
    }),
  )
  const main = root.querySelector('.gc-main') as SVGElement
  const ghost = root.querySelector('.gc-ghost') as SVGElement
  let mx = innerWidth / 2
  let my = innerHeight / 2
  let x = mx
  let y = my
  let ang = 0
  let clickAnim: { t0: number; from: number; to: number } | null = null
  let hot = false
  let overInput = false
  let pressed = false
  let raf = 0
  let last = performance.now()
  function easeOutBack(p: number) {
    const c = 1.70158
    return 1 + (c + 1) * Math.pow(p - 1, 3) + c * Math.pow(p - 1, 2)
  }
  document.addEventListener('pointermove', (e) => {
    mx = e.clientX
    my = e.clientY
    const t = e.target as El
    overInput = !!(t.closest && t.closest('input,textarea,select,[contenteditable]'))
    hot = !!(t.closest && t.closest('a,button,.chip,.tcard,.cmdk-item,.nav-link,select'))
    root.classList.toggle('text-mode', overInput)
    root.classList.toggle('hot', hot && !overInput)
    root.classList.add('ready')
  })
  document.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || overInput) return
    pressed = true
    clickAnim = { t0: performance.now(), from: ang, to: ang + 360 }
    for (let i = 0; i < 6; i++) {
      const s = document.createElement('span')
      s.className = 'gc-spark'
      const a = Math.random() * Math.PI * 2
      const d = 22 + Math.random() * 40
      s.style.left = e.clientX + 'px'
      s.style.top = e.clientY + 'px'
      s.style.setProperty('--dx', Math.cos(a) * d + 'px')
      s.style.setProperty('--dy', Math.sin(a) * d + 'px')
      document.body.appendChild(s)
      s.addEventListener('animationend', () => s.remove())
    }
  })
  document.addEventListener('pointerup', () => {
    pressed = false
  })
  function tick(now: number) {
    try {
      const dt = Math.min(0.05, (now - last) / 1000)
      last = now
      x += (mx - x) * 0.35
      y += (my - y) * 0.35
      if (clickAnim) {
        const p = (now - clickAnim.t0) / 500
        if (p >= 1) {
          ang = clickAnim.to
          clickAnim = null
        } else ang = clickAnim.from + (clickAnim.to - clickAnim.from) * easeOutBack(p)
      } else ang += (hot ? 144 : 48) * dt
      const sc = (pressed ? 0.88 : hot ? 1.22 : 1) * (overInput ? 0.5 : 1)
      root.style.transform = 'translate3d(' + x + 'px,' + y + 'px,0) scale(' + sc + ')'
      main.style.transform = 'rotate(' + ang + 'deg)'
      ghost.style.transform = 'rotate(' + -ang * 1.4 + 'deg)'
      raf = requestAnimationFrame(tick)
    } catch {
      document.body.classList.remove('cursor-swapped')
      root.style.display = 'none'
      raf = 0
    }
  }
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      cancelAnimationFrame(raf)
      raf = 0
    } else if (!raf) {
      last = performance.now()
      raf = requestAnimationFrame(tick)
    }
  })
  raf = requestAnimationFrame(tick)
}

/* ---------------- 数字滚动批量(带 data-count 的节点) ---------------- */
function initCounts() {
  $$('[data-count]').forEach((n, i) => {
    if ((n as El & { __counted?: boolean }).__counted) return
    ;(n as El & { __counted?: boolean }).__counted = true
    const to = parseFloat(n.getAttribute('data-count') || '0') || 0
    const suffix = n.getAttribute('data-suffix') || ''
    const fmt = n.getAttribute('data-fmt')
    setTimeout(
      () =>
        countUp(n, to, {
          suffix,
          dur: 1100,
          format: fmt === 'money' ? (v) => '¥' + v.toFixed(0) : undefined,
        }),
      150 + i * 90,
    )
  })
}

/* ---------------- 命令面板(Ctrl/Cmd+K) ---------------- */
export interface CmdItem {
  label: string
  hint?: string
  href?: string
  fn?: () => void
}
let cmdkItems: CmdItem[] = []
export function setCmdItems(items: CmdItem[]) {
  cmdkItems = items
}
function initCmdK() {
  let mask = $('.cmdk-mask')
  if (!mask) {
    mask = document.createElement('div')
    mask.className = 'cmdk-mask'
    mask.innerHTML =
      '<div class="cmdk"><div class="cmdk-input"><svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg><input placeholder="搜索命令…"></div><div class="cmdk-list"></div><div class="cmdk-foot"><span>↑↓ 选择</span><span>Enter 执行</span><span>Esc 关闭</span></div></div>'
    document.body.appendChild(mask)
  }
  const input = $('.cmdk-input input', mask) as HTMLInputElement
  const list = $('.cmdk-list', mask) as El
  let sel = 0
  let filtered: CmdItem[] = cmdkItems
  function render() {
    list.innerHTML = ''
    filtered.forEach((it, i) => {
      const d = document.createElement('div')
      d.className = 'cmdk-item' + (i === sel ? ' sel' : '')
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
  function paint() {
    $$('.cmdk-item', list).forEach((n, i) => n.classList.toggle('sel', i === sel))
  }
  function go(it: CmdItem) {
    mask!.classList.remove('show')
    SoundFX.pop()
    if (it.href) location.href = it.href
    else if (it.fn) it.fn()
  }
  function open() {
    mask!.classList.add('show')
    input.value = ''
    filtered = cmdkItems
    sel = 0
    render()
    setTimeout(() => input.focus(), 50)
    SoundFX.pop()
  }
  document.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault()
      mask!.classList.contains('show') ? mask!.classList.remove('show') : open()
    }
  })
  input.addEventListener('input', () => {
    const q = input.value.trim().toLowerCase()
    filtered = cmdkItems.filter((it) => it.label.toLowerCase().indexOf(q) > -1)
    sel = 0
    render()
  })
  mask.addEventListener('click', (e) => {
    if (e.target === mask) mask.classList.remove('show')
  })
  document.addEventListener('click', (e) => {
    if ((e.target as El).closest('[data-cmdk]')) open()
  })
}

/* ---------------- 初始化入口 ---------------- */
export function initFx() {
  initReveal()
  initRipple()
  initMagnetic()
  initTilt()
  initGearCursor()
  initCounts()
  initCmdK()
  // SPA 动态渲染:监听 DOM 变化,自动初始化新出现的 reveal / data-count 元素
  const mo = new MutationObserver((mutations) => {
    mutations.forEach((m) => {
      if (m.type === 'attributes' && m.attributeName === 'data-count') {
        delete (m.target as El & { __counted?: boolean }).__counted
      }
    })
    initReveal()
    initCounts()
  })
  mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-count'] })
}

export { SoundFX }
