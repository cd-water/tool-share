/**
 * 居民登录页交互 —— 从 ToolShare 原型包 01-居民登录.html 一比一移植。
 * 唯一连续状态量 charge(0..1) 驱动齿圈 / 进度弧 / 8 格刻度 / 指针 / 读数 / 光晕；
 * 其余交互（拖动上紧、点击变形、密码遮罩百叶窗、键盘上紧、齿轮虹膜转场）全部保留。
 */
import { bindSoundToggle, SoundFX, toast } from '@/lib/fx'
import { OVERLAY_HTML } from './loginViewHtml'

export type LoginMode = 'code' | 'pwd'

export interface LoginValues {
  mode: LoginMode
  phone: string
  code: string
  password: string
}

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

const ICON_EYE =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg>'
const ICON_EYE_OFF =
  '<svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12s3.5-7 10-7c2 0 3.7.6 5.2 1.5M22 12s-3.5 7-10 7c-2 0-3.7-.6-5.2-1.5"/><path d="M4 4l16 16"/></svg>'

export function initLogin(host: HTMLElement, bridge: LoginBridge): () => void {
  const loginView = host.querySelector<HTMLElement>('#loginView')
  if (!loginView) return () => {}

  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  /* ---------------- 转场覆盖层（挂在 body 上，导航后仍可用） ---------------- */
  const overlayWrap = document.createElement('div')
  overlayWrap.className = 'ts-login-overlay'
  overlayWrap.innerHTML = OVERLAY_HTML
  document.body.appendChild(overlayWrap)
  const boltLayer = overlayWrap.querySelector<HTMLElement>('#boltLayer')!
  const boltBig = overlayWrap.querySelector<SVGElement>('.bolt-big')!
  const irisCover = overlayWrap.querySelector<HTMLElement>('#irisCover')!
  const irisGear = overlayWrap.querySelector<SVGElement>('#irisGear')!

  let fx = document.getElementById('fxLayer') as HTMLElement | null
  if (!fx) {
    fx = document.createElement('div')
    fx.className = 'fx-layer'
    fx.id = 'fxLayer'
    document.body.appendChild(fx)
  }
  const fxLayer = fx

  document.body.classList.add('login-page')

  /* 顶部音效开关（原型位于登录视图之外，为全站唯一全局控件） */
  const soundBtn = document.createElement('button')
  soundBtn.className = 'icon-btn sound-fab'
  soundBtn.id = 'soundToggle'
  soundBtn.title = '音效'
  overlayWrap.appendChild(soundBtn)
  bindSoundToggle(soundBtn)

  /* 静态链接改到 SPA 路由 / 管理端 */
  const adminLink = host.querySelector<HTMLAnchorElement>('.admin-entry')
  if (adminLink) adminLink.href = bridge.adminUrl
  host.querySelectorAll<HTMLAnchorElement>('.lc-foot a').forEach((a) => a.setAttribute('href', '/ai'))

  /* ---------------- 音效（克制，绑定可见动作） ---------------- */
  let actx: AudioContext | null = null
  function ac(): AudioContext | null {
    if (!actx) {
      try {
        actx = new (window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext)()
      } catch {
        /* noop */
      }
    }
    if (actx && actx.state === 'suspended') actx.resume()
    return actx
  }
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

  /* ---------------- 基础工具 ---------------- */
  const $ = <T extends Element = HTMLElement>(sel: string) => host.querySelector<T>(sel)
  const $$ = <T extends Element = HTMLElement>(sel: string) => Array.from(host.querySelectorAll<T>(sel))
  const lerp = (a: number, b: number, t: number) => a + (b - a) * t
  const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v))
  const rnd = (a: number, b: number) => a + Math.random() * (b - a)
  function easeInOut(p: number) {
    return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2
  }

  /* ---------------- 火花 / 螺母粒子 ---------------- */
  function spawnSparks(x: number, y: number, n: number) {
    if (reduced) return
    const colors = ['#F7A600', '#C77B3F', '#FFD98A', '#FFFDF7']
    for (let i = 0; i < n; i++) {
      const s = document.createElement('span')
      s.className = 'spark'
      const a = rnd(0, Math.PI * 2)
      const d = rnd(24, 64)
      s.style.left = x + 'px'
      s.style.top = y + 'px'
      s.style.setProperty('--dx', Math.cos(a) * d + 'px')
      s.style.setProperty('--dy', Math.sin(a) * d + 'px')
      s.style.setProperty('--rot', rnd(-200, 200) + 'deg')
      s.style.setProperty('--c', colors[i % colors.length])
      fxLayer.appendChild(s)
      s.addEventListener('animationend', () => s.remove())
    }
  }
  function spawnNuts(x: number, y: number, n: number) {
    if (reduced) return
    for (let i = 0; i < n; i++) {
      const s = document.createElement('span')
      s.className = 'nut'
      const a = rnd(-Math.PI, 0)
      const d = rnd(50, 150)
      s.style.left = x + 'px'
      s.style.top = y + 'px'
      s.style.setProperty('--dx', Math.cos(a) * d + 'px')
      s.style.setProperty('--dy', Math.sin(a) * d + rnd(60, 140) + 'px')
      s.style.setProperty('--rot', rnd(-360, 360) + 'deg')
      s.style.background = ['#8C97A3', '#C77B3F', '#F7A600'][i % 3]
      fxLayer.appendChild(s)
      s.addEventListener('animationend', () => s.remove())
    }
  }

  /* ---------------- 舞台 / 装置元素 ---------------- */
  const layers = $$<HTMLElement>('.layer[data-depth]')
  const spot = $<HTMLElement>('.spotlight')
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
  const morphStage = $<HTMLElement>('#morphStage')
  const morphTools = $$<HTMLElement>('.morph-tool')

  if (!mascot || !morphStage) return () => {}

  /* ---------------- charge 状态机 ---------------- */
  let state: 'idle' | 'watch' | 'shy' | 'loading' | 'success' = 'idle'
  let mode: LoginMode = 'code'
  let morphIdx = -1
  let morphing = false
  let morphTimer = 0
  let morphHideTimer = 0
  let transiting = false
  let charge = 0
  let renderCharge = 0
  let proximity = 0
  let dragVel = 0
  let rimAngle = 0
  let lastPct = -1
  let lastTick = -1
  let chargedFired = false
  let introT = 0
  const introStart = performance.now()
  let introDone = false
  let mouseX = innerWidth / 2
  let mouseY = innerHeight / 2
  let nx = 0
  let ny = 0
  let plx = 0
  let ply = 0
  let prx = 0
  let pry = 0
  let headTilt = 0
  let rootLean = 0
  let leanX = 0
  let leanY = 0
  let stageRect: DOMRect | null = null
  let cogRect: DOMRect | null = null

  function measureRects() {
    const st = $('.stage')
    if (st) stageRect = st.getBoundingClientRect()
    if (cogSvg) cogRect = cogSvg.getBoundingClientRect()
  }

  function addCharge(d: number, quiet?: boolean) {
    const before = Math.floor(charge * 8 + 1e-6)
    charge = clamp(charge + d, 0, 1)
    const after = Math.floor(charge * 8 + 1e-6)
    if (after > before) {
      snd.ratchet()
      if (!quiet && !reduced && cogRect) spawnSparks(cogRect.left + cogRect.width * 0.5, cogRect.top + cogRect.height * 0.36, 3)
    }
    if (charge >= 0.999 && !chargedFired) {
      chargedFired = true
      onCharged()
    }
  }

  function onCharged() {
    if (cogRect && !reduced) spawnSparks(cogRect.left + cogRect.width * 0.5, cogRect.top + cogRect.height * 0.36, 14)
    snd.ratchet()
    setTimeout(() => snd.ratchet(), 110)
    hint?.classList.add('ready')
    loginBtn?.classList.add('is-charged')
    toast('工坊已上紧', { type: 'ok', msg: '核心就绪 · 点击「启动工坊」即可开工' })
  }

  function coreValue(now: number) {
    let target = charge
    if (state === 'loading' || state === 'success') target = Math.max(target, 0.97)
    if (!introDone && !reduced) {
      introT = Math.min(1, (now - introStart) / 1150)
      target = Math.max(target, Math.sin(introT * Math.PI) * 0.78)
      if (introT >= 1) introDone = true
    } else {
      introDone = true
    }
    renderCharge = reduced ? target : lerp(renderCharge, target, 0.14)
    if (charge >= 0.999) {
      renderCharge = 1
      return 1
    }
    return renderCharge
  }

  function paintCore(v: number, dt: number, now: number) {
    rimAngle += (5 + v * 215 + dragVel * 230) * dt
    if (cogRim) cogRim.style.transform = 'rotate(' + rimAngle.toFixed(2) + 'deg)'
    if (chargeArc) chargeArc.style.strokeDashoffset = (352 * (1 - v)).toFixed(1)
    const lit = v >= 0.999 ? 8 : Math.floor(v * 8)
    if (lit !== lastTick) {
      lastTick = lit
      coreTicks.forEach((t, i) => t.classList.toggle('on', i < lit))
      corePips.forEach((p, i) => p.classList.toggle('on', i < lit))
    }
    if (needle) needle.style.transform = 'rotate(' + (-120 + v * 240).toFixed(1) + 'deg)'
    const pct = Math.round(v * 100)
    if (pct !== lastPct) {
      lastPct = pct
      if (readout) readout.textContent = charge >= 0.999 ? 'READY' : (pct < 10 ? '0' : '') + pct + '%'
      if (hintStrong)
        hintStrong.textContent =
          charge >= 0.999 ? '工坊已就绪 · 启动工坊' : charge > 0.001 ? '已上紧 ' + pct + '% · 继续拖动' : '拖动外圈，为工坊上紧'
    }
    if (coreHalo) {
      coreHalo.style.opacity = (0.2 + v * 0.55).toFixed(3)
      coreHalo.style.transform = 'scale(' + (1 + v * 0.16).toFixed(3) + ')'
    }
    coreLamps.forEach((l) => (l.style.opacity = (0.3 + v * 0.7).toFixed(2)))
    if (coreBounce) coreBounce.style.opacity = (v >= 0.999 && !reduced ? 0.32 + 0.22 * Math.sin(now / 180) : 0).toFixed(3)
    if (charge > 0.001) mascot.classList.add('is-touched')
  }

  function drawScene(dt: number, now: number) {
    const st = stageRect || { left: 0, top: 0, width: innerWidth, height: innerHeight }
    nx = clamp(((mouseX - st.left) / st.width) * 2 - 1, -1, 1)
    ny = clamp(((mouseY - st.top) / st.height) * 2 - 1, -1, 1)
    if (!reduced) {
      layers.forEach((l) => {
        const d = parseFloat(l.getAttribute('data-depth') || '1')
        l.style.transform = 'translate3d(' + nx * d * 14 + 'px,' + ny * d * 10 + 'px,0)'
      })
      if (spot) spot.style.transform = 'rotate(' + nx * 5 + 'deg)'
    }
    const cr = cogRect || { left: 0, top: 0, width: 240, height: 260 }
    const dist = Math.hypot(mouseX - (cr.left + cr.width * 0.5), mouseY - (cr.top + cr.height * 0.36))
    const near = clamp(1 - dist / (Math.max(cr.width, cr.height) * 1.25), 0, 1)
    proximity = lerp(proximity, near, 0.09)
    leanX = lerp(leanX, nx * 7 * proximity, 0.12)
    leanY = lerp(leanY, ny * 5 * proximity, 0.12)
    mascot.style.transform =
      'translate3d(' + leanX.toFixed(2) + 'px,' + leanY.toFixed(2) + 'px,0) scale(' + (1 + proximity * 0.03).toFixed(3) + ')'
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
    const tiltT = state === 'idle' ? nx * 5 : 0
    const leanT = state === 'idle' ? nx * 3.5 : 0
    headTilt = lerp(headTilt, tiltT, 0.12)
    rootLean = lerp(rootLean, leanT, 0.12)
    if (cogHead) cogHead.style.transform = 'rotate(' + headTilt.toFixed(2) + 'deg)'
    if (cogRoot) cogRoot.style.transform = 'translateX(' + rootLean.toFixed(2) + 'px)'
    paintCore(coreValue(now), dt, now)
  }

  /* ---------------- 装置状态 ---------------- */
  function setState(s: typeof state) {
    state = s
    mascot.classList.toggle('is-watch', s === 'watch')
    mascot.classList.toggle('is-shy', s === 'shy')
    mascot.classList.toggle('is-loading', s === 'loading')
    mascot.classList.toggle('is-success', s === 'success')
  }

  let blinkTimer = 0
  function scheduleBlink() {
    blinkTimer = window.setTimeout(() => {
      if (state !== 'shy' && !mascot.classList.contains('hidden-mascot')) {
        mascot.classList.add('blink')
        setTimeout(() => mascot.classList.remove('blink'), 150)
      }
      scheduleBlink()
    }, rnd(3000, 6000))
  }

  function mascotError() {
    charge = clamp(charge - 0.1, 0, 1)
    chargedFired = false
    loginBtn?.classList.remove('is-charged')
    hint?.classList.remove('ready')
    mascot.classList.remove('is-error')
    void mascot.offsetWidth
    mascot.classList.add('is-error')
    setTimeout(() => mascot.classList.remove('is-error'), 1300)
  }

  /* ---------------- 点击变形 ---------------- */
  function tryMorph() {
    if (morphing || transiting) return
    morphIdx = Math.min(7, Math.floor(charge * 8)) % 8
    morphing = true
    const wall = $<SVGElement>('#wall-' + morphIdx)
    wall?.classList.add('borrowed')
    snd.servo()
    mascot.classList.add('scatter')
    window.setTimeout(
      () => {
        mascot.classList.add('hidden-mascot')
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
        const g = morphTools[morphIdx]
        g?.classList.add('off')
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
  let dragging = false
  let dragMoved = 0
  let dragLast = 0
  let coreCX = 0
  let coreCY = 0
  let coreDown = false
  function coreCenter() {
    const r = mascot.getBoundingClientRect()
    coreCX = r.left + r.width * 0.5
    coreCY = r.top + r.height * 0.36
  }
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
  function isInputTarget(t: EventTarget | null) {
    return !!(t instanceof Element && t.closest('input,textarea,select,[contenteditable]'))
  }

  const onPointerMove = (e: PointerEvent) => {
    mouseX = e.clientX
    mouseY = e.clientY
    if (dragging) windFromDrag(e.clientX, e.clientY)
  }
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
  const onPointerCancel = () => {
    dragging = false
    coreDown = false
    dragVel = 0
    mascot.classList.remove('is-dragging')
  }
  const onDeviceKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault()
      addCharge(1 / 8)
      tryMorph()
    }
  }
  const onResize = () => {
    measureRects()
    sizeDust()
  }

  mascot.addEventListener('keydown', onDeviceKey)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerdown', onPointerDown)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
  window.addEventListener('resize', onResize)

  /* ---------------- 尘埃 ---------------- */
  const dustC = $<HTMLCanvasElement>('#dust')
  const dctx = dustC?.getContext('2d') ?? null
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  let dust: { x: number; y: number; r: number; vy: number; vx: number; a: number; ph: number }[] = []
  function sizeDust() {
    if (!dustC || !dctx) return
    const r = dustC.parentElement!.getBoundingClientRect()
    dustC.width = r.width * dpr
    dustC.height = r.height * dpr
    dctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  function initDust() {
    sizeDust()
    dust = []
    const n = reduced ? 0 : 30
    for (let i = 0; i < n; i++)
      dust.push({ x: Math.random(), y: Math.random(), r: rnd(0.8, 2.4), vy: rnd(0.008, 0.03), vx: rnd(-0.006, 0.006), a: rnd(0.12, 0.45), ph: rnd(0, 6) })
  }
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

  let cdTimer = 0
  let cdLeft = 0
  let sending = false
  let submitting = false

  function setMode(m: LoginMode) {
    mode = m
    addCharge(0.03, true)
    tabCode.classList.toggle('active', m === 'code')
    tabPw.classList.toggle('active', m === 'pwd')
    tabCode.setAttribute('aria-selected', String(m === 'code'))
    tabPw.setAttribute('aria-selected', String(m === 'pwd'))
    codeField.style.display = m === 'code' ? '' : 'none'
    pwField.style.display = m === 'pwd' ? '' : 'none'
    if (m === 'code' && state === 'shy') setState('idle')
    snd.pop()
  }
  function validPhone() {
    return /^1\d{10}$/.test(phone.value.trim())
  }
  function phoneErr(on: boolean) {
    const f = $<HTMLElement>('#fPhone')
    if (!f) return
    f.classList.toggle('is-err', on)
    if (on) {
      f.classList.remove('shake')
      void f.offsetWidth
      f.classList.add('shake')
    }
  }
  function countdown() {
    cdLeft = 60
    sendBtn.disabled = true
    sendBtn.textContent = cdLeft + 's 后重发'
    cdTimer = window.setInterval(() => {
      cdLeft--
      if (cdLeft <= 0) {
        clearInterval(cdTimer)
        sendBtn.disabled = false
        sendBtn.textContent = '获取验证码'
      } else sendBtn.textContent = cdLeft + 's 后重发'
    }, 1000)
  }

  const onTabCode = () => setMode('code')
  const onTabPw = () => setMode('pwd')
  const onPhoneInput = () => {
    phoneErr(false)
    addCharge(0.008, true)
    if (state === 'idle') setState('watch')
  }
  const onPhoneFocus = () => {
    addCharge(0.05)
    if (state !== 'shy') setState('watch')
  }
  const onPhoneBlur = () => {
    if (state === 'watch') setState('idle')
  }
  const onCodeFocus = () => {
    addCharge(0.05)
    setState('watch')
  }
  const onCodeBlur = () => {
    if (state === 'watch') setState('idle')
  }
  const onCodeInput = () => {
    addCharge(0.01, true)
    $('#fCode')?.classList.remove('is-err')
  }
  const onPwFocus = () => {
    addCharge(0.05)
    setState('shy')
  }
  const onPwBlur = () => {
    if (state === 'shy') {
      setState('idle')
      mascot.classList.remove('peek')
    }
  }
  const onPwInput = () => {
    addCharge(0.01, true)
    $('#fPw')?.classList.remove('is-err')
  }
  const onPwEye = () => {
    const show = pw.type === 'password'
    pw.type = show ? 'text' : 'password'
    pwEye.innerHTML = show ? ICON_EYE_OFF : ICON_EYE
    pwEye.setAttribute('aria-label', show ? '隐藏密码' : '显示密码')
    mascot.classList.toggle('peek', show)
  }
  const onSend = async () => {
    if (!validPhone()) {
      phoneErr(true)
      mascotError()
      snd.error()
      return
    }
    if (cdLeft > 0 || sending) return
    sending = true
    sendBtn.disabled = true
    sendBtn.textContent = '发送中…'
    let res: Awaited<ReturnType<LoginBridge['sendCode']>>
    try {
      res = await bridge.sendCode(phone.value.trim())
    } catch {
      res = { ok: false, message: '网络异常，请稍后重试' }
    }
    sending = false
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
  const onDemo = async () => {
    phone.value = '13800000001'
    pw.value = '123456'
    if (mode === 'code' && !/^\d{6}$/.test(code.value)) code.value = ''
    phoneErr(false)
    $('#fCode')?.classList.remove('is-err')
    $('#fPw')?.classList.remove('is-err')
    addCharge(0.14, true)
    snd.pop()
    // 验证码登录必须先向后端申请真实验证码（一次性消费）
    if (mode === 'code') {
      sendBtn.disabled = true
      sendBtn.textContent = '发送中…'
      try {
        const res = await bridge.sendCode(phone.value)
        if (res.ok) {
          countdown()
          if (res.code) {
            code.value = res.code
            toast('演示账号已填入', { type: 'info', msg: '真实验证码已自动填入，点击「启动工坊」即可进入' })
          } else {
            toast('演示账号已填入', { type: 'info', msg: '验证码已发送，请查看后端日志中的 MOCK SMS 验证码后填写' })
          }
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

  const saved = localStorage.getItem('ts_phone')
  if (saved) {
    phone.value = saved
    remember.checked = true
  }

  async function playTransition() {
    transiting = true
    if (reduced) {
      bridge.enter(pendingToken)
      return
    }
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))
    await wait(430)
    loginView!.classList.add('unscrew')
    boltLayer.hidden = false
    requestAnimationFrame(() => boltBig.classList.add('drop'))
    await wait(300)
    boltBig.classList.add('r1')
    snd.ratchet()
    await wait(180)
    boltBig.classList.add('r2')
    snd.ratchet()
    await wait(160)
    irisCover.hidden = false
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
        if (p < 1) requestAnimationFrame(grow)
        else resolve()
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
  const onSubmit = async (e: Event) => {
    e.preventDefault()
    if (transiting || submitting) return
    let ok = true
    if (!validPhone()) {
      phoneErr(true)
      ok = false
    }
    if (mode === 'code') {
      if (!/^\d{6}$/.test(code.value.trim())) {
        $('#fCode')?.classList.add('is-err')
        ok = false
      }
    } else if (pw.value.length < 6) {
      $('#fPw')?.classList.add('is-err')
      ok = false
    }
    if (!ok) {
      mascotError()
      snd.error()
      return
    }
    if (remember.checked) localStorage.setItem('ts_phone', phone.value.trim())
    else localStorage.removeItem('ts_phone')
    submitting = true
    loginBtn.classList.add('loading')
    loginBtn.disabled = true
    setState('loading')
    let res: Awaited<ReturnType<LoginBridge['login']>>
    try {
      res = await bridge.login({ mode, phone: phone.value.trim(), code: code.value.trim(), password: pw.value })
    } catch {
      res = { ok: false, message: '网络异常，请稍后重试' }
    }
    submitting = false
    loginBtn.classList.remove('loading')
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
  function tick(now: number) {
    if (!running) return
    const dt = Math.min(0.05, (now - last) / 1000)
    last = now
    drawScene(dt, now)
    drawDust(now)
    raf = requestAnimationFrame(tick)
  }
  const onVisibility = () => {
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
