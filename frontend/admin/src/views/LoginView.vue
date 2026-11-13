<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { bindSoundToggle, toast } from '@/lib/fx'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/* ---------- 登录业务（完整保留原逻辑） ---------- */
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

function fillDemo() {
  form.username = 'admin'
  form.password = 'Aa123456'
  toast('演示账号已填入', { type: 'info', msg: '点击「启动控制台」即可进入' })
}

function safeRedirectPath(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/dashboard'
}

/* ---------- 雷达星图 · 常量与坐标 ---------- */
const CX = 60
const CY = 60
const R = 44

function polar(deg: number, r: number): { x: number; y: number } {
  const a = (deg - 90) * (Math.PI / 180)
  return { x: CX + r * Math.cos(a), y: CY + r * Math.sin(a) }
}

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

const leadPoint = polar(0, R)

const ticks = Array.from({ length: 24 }, (_, k) => {
  const d = k * 15
  const major = d % 30 === 0
  const a = polar(d, R - (major ? 4 : 2))
  const b = polar(d, R)
  const lp = polar(d, R + 7)
  return { d, major, x1: a.x, y1: a.y, x2: b.x, y2: b.y, lx: lp.x, ly: lp.y }
})

const hubPath = (() => {
  const pts = Array.from({ length: 6 }, (_, i) => polar(i * 60, 8))
  return `M ${pts.map((p) => p.x.toFixed(2) + ' ' + p.y.toFixed(2)).join(' L ')} Z`
})()

/* ---------- 8 个工具接触点（石墨铜金 · 状态着色） ---------- */
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

const blips = reactive<Blip[]>(
  TOOLS.map((t, i) => {
    const deg = (i * 47 + 15) % 360
    const r = (0.34 + ((i * 37) % 50) / 100) * R
    const p = polar(deg, r)
    return { id: t.id, code: t.code, name: t.name, status: t.status, deg, x: p.x, y: p.y, glow: 0, ping: 1 }
  }),
)

const STATUS_COLOR: Record<string, string> = {
  AVAILABLE: '#3BB273',
  RENTED: '#E0A33C',
  MAINTENANCE: '#E56A5E',
}
const STATUS_TEXT: Record<string, string> = {
  AVAILABLE: '可借用',
  RENTED: '已租出',
  MAINTENANCE: '维修中',
}
function statusColor(s: string): string {
  return STATUS_COLOR[s] || '#D89B43'
}
function statusText(s: string): string {
  return STATUS_TEXT[s] || '未知'
}
function pad2(n: number): string {
  return String(n).padStart(2, '0')
}
function pad3(n: number): string {
  return String(Math.floor(n) % 360).padStart(3, '0')
}

/* ---------- 雷达动画 ---------- */
const reducedMotion = !!window.matchMedia('(prefers-reduced-motion: reduce)').matches
const sweepDeg = ref(0)
const radarLocked = ref(false)
const radarWrap = ref<HTMLElement>()
const stageSize = ref(320)
const hoverBlip = ref<Blip | null>(null)
const mouseRng = ref('--')
const reticle = reactive({ x: 50, y: 50, on: false })

function sizeRadar() {
  const el = radarWrap.value
  if (!el) return
  stageSize.value = Math.max(200, Math.min(el.clientWidth, el.clientHeight) - 8)
}

function crossed(a: number, from: number, to: number): boolean {
  if (to >= from) return a > from && a <= to
  return a > from || a <= to
}

let prevSweep = 0
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
function onRadarLeave() {
  hoverBlip.value = null
  mouseRng.value = '--'
  reticle.on = false
}

/* ---------- 遥测 / 时钟 / 延迟 ---------- */
const tm = reactive({ online: 0, ready: 0, out: 0, service: 0 })
const clockStr = ref('')
const ping = ref(23)

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

function tickClock() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  clockStr.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/* ---------- 自检转场 ---------- */
const BOOT_LINES: Array<[string, string, string]> = [
  ['身份核验', '通过', 'admin · 社区工作人员'],
  ['权限矩阵加载', '完成', 'OPERATIONS / FULL'],
  ['工具库同步', '8 件', '5 可借 · 2 租出 · 1 维修'],
  ['租赁 / 报修数据', '完成', '6 订单 · 3 报修'],
  ['建立安全通道', 'TLS 1.3', 'cn-east-1 · 23ms'],
]
const bootVisible = ref(false)
const revealedCount = ref(0)
const grantVisible = ref(false)
const wipeVisible = ref(false)

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

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

const shaking = ref(false)
function shake() {
  shaking.value = true
  setTimeout(() => {
    shaking.value = false
  }, 420)
}

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

function onForgot() {
  toast('请联系系统管理员重置密码', { type: 'info' })
}

/* ---------- 生命周期 ---------- */
let alive = true
let rafId = 0
let clockTimer: number | undefined
let pingTimer: number | undefined
let ro: ResizeObserver | undefined

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

onBeforeUnmount(() => {
  alive = false
  cancelAnimationFrame(rafId)
  if (clockTimer) clearInterval(clockTimer)
  if (pingTimer) clearInterval(pingTimer)
  ro?.disconnect()
  window.removeEventListener('resize', sizeRadar)
})
</script>

<template>
  <div class="cl">
    <!-- 左侧 · 雷达星图 -->
    <div class="cl-left">
      <div class="cl-grid"></div>
      <div class="cl-glow"></div>
      <div class="cl-hud cl-hud-top">
        <span class="hud-tag"><i class="hud-dot"></i>LIVE SWEEP</span>
        <span class="hud-kv">BRG <b>{{ pad3(sweepDeg) }}</b>°</span>
        <span class="grow"></span>
        <span class="hud-kv">CONTACTS <b>{{ pad2(blips.length) }}</b></span>
        <span class="hud-kv">RNG <b>{{ mouseRng }}</b></span>
      </div>

      <div ref="radarWrap" class="cl-radar-wrap" @pointermove="onRadarMove" @pointerleave="onRadarLeave">
        <div class="radar-stage" :style="{ width: stageSize + 'px', height: stageSize + 'px' }">
          <svg class="radar-svg" viewBox="0 0 120 120" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
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

            <circle :cx="CX" :cy="CY" :r="R" fill="url(#cl-radarBase)" />

            <!-- 距离圈 + 十字轴 -->
            <g fill="none">
              <circle :cx="CX" :cy="CY" :r="R * 0.25" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R * 0.5" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R * 0.75" stroke="rgba(216,155,67,.12)" stroke-width="0.5" />
              <circle :cx="CX" :cy="CY" :r="R" stroke="rgba(216,155,67,.22)" stroke-width="0.5" />
              <line :x1="CX - R" :y1="CY" :x2="CX + R" :y2="CY" stroke="rgba(216,155,67,.08)" stroke-width="0.5" />
              <line :x1="CX" :y1="CY - R" :x2="CX" :y2="CY + R" stroke="rgba(216,155,67,.08)" stroke-width="0.5" />
            </g>

            <!-- 方位刻度 + 度数 -->
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

            <!-- 扫描波瓣（旋转余辉） -->
            <g :transform="`rotate(${sweepDeg} 60 60)`">
              <path v-for="(w, i) in sweepWedges" :key="'wedge-' + i" :d="w.d" fill="#D89B43" :fill-opacity="w.op" />
              <line :x1="CX" :y1="CY" :x2="leadPoint.x" :y2="leadPoint.y" stroke="rgba(216,155,67,.28)" stroke-width="2" />
              <line :x1="CX" :y1="CY" :x2="leadPoint.x" :y2="leadPoint.y" stroke="rgba(240,190,110,.9)" stroke-width="0.7" />
            </g>

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

            <!-- 中心枢纽 -->
            <path :d="hubPath" fill="rgba(23,26,28,.95)" stroke="rgba(216,155,67,.6)" stroke-width="0.7" />
            <circle :cx="CX" :cy="CY" r="5" fill="url(#cl-hubCore)" />
            <circle :cx="CX" :cy="CY" r="1.6" fill="#F5C474" />
          </svg>

          <div class="cl-reticle" :class="{ on: reticle.on }" :style="{ left: reticle.x + '%', top: reticle.y + '%' }">
            <i class="rt tl"></i><i class="rt tr"></i><i class="rt bl"></i><i class="rt br"></i>
            <em>RNG {{ mouseRng }}</em>
          </div>

          <div v-if="hoverBlip" class="radar-tip" :style="{ left: (hoverBlip.x / 1.2) + '%', top: (hoverBlip.y / 1.2) + '%' }">
            <b>{{ hoverBlip.name }}</b>
            <span :style="{ color: statusColor(hoverBlip.status) }">{{ statusText(hoverBlip.status) }} · {{ hoverBlip.code }}</span>
          </div>
        </div>

        <div class="cl-legend">
          <span><i style="background: #3bb273"></i>可借</span>
          <span><i style="background: #e0a33c"></i>租出</span>
          <span><i style="background: #e56a5e"></i>维修</span>
        </div>
      </div>

      <div class="cl-telemetry">
        <div class="tm"><span class="tm-k">工具在线</span><b class="tm-v num">{{ tm.online }}</b><span class="tm-u">UNITS</span></div>
        <div class="tm"><span class="tm-k">可借用</span><b class="tm-v num ok">{{ tm.ready }}</b><span class="tm-u">READY</span></div>
        <div class="tm"><span class="tm-k">已租出</span><b class="tm-v num warn">{{ tm.out }}</b><span class="tm-u">OUT</span></div>
        <div class="tm"><span class="tm-k">维修中</span><b class="tm-v num bad">{{ tm.service }}</b><span class="tm-u">SERVICE</span></div>
      </div>
      <div class="cl-rail"><span>TOOLSHARE OPERATIONS · RADAR STARMAP · BUILD 2.4.0</span></div>
    </div>

    <!-- 右侧 · 登录控制台 -->
    <div class="cl-right">
      <div class="cl-mesh"><i></i><i></i></div>
      <button class="icon-btn cl-sound" id="soundToggle" aria-label="音效开关"></button>

      <div class="cl-card" :class="{ shake: shaking }">
        <div class="cl-brand">
          <BrandMark :size="46" />
          <div>
            <b>ToolShare</b>
            <span>管理控制台 · OPERATIONS</span>
          </div>
        </div>

        <p class="cl-eyebrow">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /><path d="M12 8v4M12 16h.01" />
          </svg>
          <span>OPERATIONS CONSOLE</span>
        </p>
        <h1 class="cl-title">指挥中枢</h1>
        <p class="cl-sub">社区工具房的运营雷达已就绪。登录以调度工具、租赁与报修。</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" hide-required-asterisk @keyup.enter="handleLogin">
          <el-form-item prop="username" label="账号">
            <el-input
              v-model="form.username"
              placeholder="请输入管理员账号"
              autocomplete="username"
              :prefix-icon="User"
            />
          </el-form-item>
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

          <div class="cl-row">
            <label class="ck"><input type="checkbox" checked /><span>记住此设备</span></label>
            <a href="javascript:void(0)" @click="onForgot">忘记密码？</a>
          </div>

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

        <div class="cl-demo">
          <span><svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/></svg>演示账号</span>
          <b>admin</b>
          <span>密码</span>
          <b>Aa123456</b>
          <button type="button" class="link-btn" @click="fillDemo">一键填入</button>
        </div>
        <a class="cl-back" href="http://localhost:5901/login">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
          返回居民端登录
        </a>
      </div>
    </div>

    <!-- 底部状态条 -->
    <div class="cl-status">
      <span><i class="st-dot"></i>系统运行正常</span>
      <span>节点 cn-east-1</span>
      <span>延迟 <b class="num">{{ ping }}</b> ms</span>
      <span class="grow"></span>
      <span>{{ clockStr }}</span>
    </div>

    <!-- 自检转场 -->
    <div class="boot" :hidden="!bootVisible">
      <div class="boot-panel">
        <div class="boot-head">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3" />
          </svg>
          <span>SYSTEM SELF-CHECK · 系统自检</span>
          <i class="boot-spin"></i>
        </div>
        <div class="boot-log">
          <div v-for="(ln, i) in BOOT_LINES" :key="'bl-' + i" class="bl" :class="{ on: revealedCount > i }">
            <span class="dim">&gt;</span> {{ ln[0] }} <span class="dim">……</span> <b>{{ ln[1] }}</b> <span class="dim">· {{ ln[2] }}</span>
          </div>
        </div>
        <div class="boot-grant" :class="{ on: grantVisible }">ACCESS GRANTED<span>授权通过 · 正在接管控制台</span></div>
      </div>
    </div>
    <div class="wipe" :class="{ go: wipeVisible }"><i></i></div>
  </div>
</template>

<style scoped>
.cl {
  /* 指挥中枢 · 石墨铜金（固定暗色，与主题无关） */
  --gc-bg: #101214;
  --gc-bg-2: #171a1c;
  --gc-ink: #edeae2;
  --gc-ink-2: #b4b0a6;
  --gc-ink-3: #8a877d;
  --gc-line: rgba(255, 255, 255, 0.07);
  --gc-copper: #d89b43;
  --gc-copper-2: #c07e28;
  --gc-copper-3: #b4741c;
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: 56fr 44fr;
  background: var(--gc-bg);
  color: var(--gc-ink);
  overflow: hidden;
}

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
@keyframes clGlow {
  to {
    transform: scale(1.15);
    opacity: 0.7;
  }
}
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
.hud-tag {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #7fb3ae;
  font-weight: 600;
}
.hud-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #7fb3ae;
  box-shadow: 0 0 0 0 rgba(127, 179, 174, 0.5);
  animation: pulse 2s infinite;
}
.hud-kv b {
  color: #c9c6bc;
  font-weight: 600;
}
@keyframes pulse {
  70% {
    box-shadow: 0 0 0 7px rgba(127, 179, 174, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(127, 179, 174, 0);
  }
}

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
.radar-stage {
  position: relative;
  flex: none;
}
.radar-svg {
  display: block;
  width: 100%;
  height: 100%;
}
.rad-deg {
  fill: rgba(201, 198, 188, 0.5);
  font-size: 3.4px;
  font-family: var(--font-mono);
}
.blip-t {
  fill: rgba(201, 198, 188, 0.55);
  font-size: 3.2px;
  font-family: var(--font-mono);
}
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
.radar-tip b {
  display: block;
  font-size: var(--t-xs);
  color: var(--gc-ink);
  font-weight: 600;
}
.radar-tip span {
  display: block;
  font-size: 9px;
  font-family: var(--font-mono);
  margin-top: 1px;
}
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

.cl-telemetry {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  padding: 0 26px 20px;
}
.tm {
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(16, 18, 20, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.07);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
.tm-k {
  display: block;
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  margin-bottom: 3px;
}
.tm-v {
  display: block;
  font-size: var(--t-3xl);
  font-weight: 800;
  line-height: 1.1;
  color: var(--gc-ink);
  font-variant-numeric: tabular-nums;
}
.tm-v.ok {
  color: #3bb273;
}
.tm-v.warn {
  color: #e0a33c;
}
.tm-v.bad {
  color: #e56a5e;
}
.tm-u {
  display: block;
  font-size: 9px;
  letter-spacing: 0.18em;
  color: #6a675e;
  font-family: var(--font-mono);
  margin-top: 2px;
}
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
.cl-mesh i:nth-child(1) {
  width: 34vw;
  height: 34vw;
  right: -8vw;
  top: -10vw;
  background: radial-gradient(circle, rgba(180, 116, 28, 0.4), transparent 65%);
  animation: clMesh 20s ease-in-out infinite alternate;
}
.cl-mesh i:nth-child(2) {
  width: 26vw;
  height: 26vw;
  left: -6vw;
  bottom: -10vw;
  background: radial-gradient(circle, rgba(127, 179, 174, 0.32), transparent 65%);
  animation: clMesh 26s ease-in-out infinite alternate-reverse;
}
@keyframes clMesh {
  to {
    transform: translate3d(-5%, 5%, 0) scale(1.12);
  }
}
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
@keyframes clIn {
  from {
    opacity: 0;
    transform: translateY(22px) scale(0.98);
  }
}
.cl-brand {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 24px;
  --brand: #d89b43;
  --ink: rgba(255, 249, 236, 0.85);
  --brand-ink: #fff9ec;
}
.cl-brand b {
  font-size: var(--t-xl);
  font-weight: 700;
  display: block;
  line-height: 1.2;
  letter-spacing: -0.01em;
}
.cl-brand span {
  font-size: var(--t-2xs);
  color: var(--gc-ink-3);
  letter-spacing: 0.12em;
}
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
.cl-sub {
  font-size: var(--t-sm);
  color: var(--gc-ink-2);
  margin: 0 0 26px;
  line-height: 1.6;
}

/* Element Plus 表单在暗色控制台中的重绘 */
.cl-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.cl-card :deep(.el-form-item__label) {
  color: var(--gc-ink-2);
  font-size: var(--t-sm);
  font-weight: 600;
  margin-bottom: 7px;
  height: auto;
  line-height: 1.4;
}
.cl-card :deep(.el-form-item__error) {
  color: #e56a5e;
  padding-top: 4px;
}
.cl-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: var(--r-input);
  box-shadow: none;
  padding: 0 12px;
  transition: border-color var(--fast), box-shadow var(--fast);
}
.cl-card :deep(.el-input__wrapper:hover) {
  border-color: rgba(255, 255, 255, 0.2);
}
.cl-card :deep(.el-input.is-focus .el-input__wrapper),
.cl-card :deep(.el-input__wrapper.is-focus) {
  border-color: var(--gc-copper);
  box-shadow: 0 0 0 3px rgba(216, 155, 67, 0.25);
}
.cl-card :deep(.el-input__inner) {
  color: var(--gc-ink);
  height: 42px;
  line-height: 42px;
  font-size: var(--t-base);
}
.cl-card :deep(.el-input__inner::placeholder) {
  color: #7c7a70;
}
.cl-card :deep(.el-input__prefix),
.cl-card :deep(.el-input__suffix) {
  color: var(--gc-ink-3);
}
.cl-card :deep(.el-form-item.is-error .el-input__wrapper) {
  border-color: #e56a5e;
  box-shadow: 0 0 0 3px rgba(229, 106, 94, 0.2);
}

.cl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 22px;
  font-size: var(--t-sm);
}
.cl .ck {
  color: var(--gc-ink-2);
  display: inline-flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
}
.cl .ck input {
  accent-color: #d89b43;
}
.cl-row a {
  color: var(--gc-ink-2);
}
.cl-row a:hover {
  color: #d89b43;
}
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

.shake {
  animation: shake 0.4s;
}
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
.boot-spin {
  margin-left: auto;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid rgba(216, 155, 67, 0.25);
  border-top-color: #d89b43;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.boot-log {
  padding: 18px;
  font-family: var(--font-mono);
  font-size: var(--t-xs);
  line-height: 2.1;
  color: #c9c6bc;
  min-height: 150px;
}
.boot-log .bl {
  opacity: 0;
  transform: translateX(-8px);
  transition: all 0.3s var(--ease);
  white-space: nowrap;
}
.boot-log .bl.on {
  opacity: 1;
  transform: none;
}
.boot-log .bl b {
  color: #3bb273;
  font-weight: 600;
}
.boot-log .bl .dim {
  color: #6a675e;
}
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
.boot-grant span {
  display: block;
  font-size: var(--t-2xs);
  font-weight: 400;
  letter-spacing: 0.08em;
  color: var(--gc-ink-3);
  margin-top: 4px;
}
.wipe {
  position: fixed;
  inset: 0;
  z-index: 210;
  background: linear-gradient(120deg, #171a1c, #12203a);
  transform: scaleX(0);
  transform-origin: left;
  pointer-events: none;
}
.wipe.go {
  transform: scaleX(1);
  transition: transform 0.55s cubic-bezier(0.7, 0, 0.3, 1);
}
.wipe i {
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0;
  width: 3px;
  background: linear-gradient(180deg, transparent, #d89b43, transparent);
  box-shadow: 0 0 30px 6px rgba(216, 155, 67, 0.6);
}

/* —— 响应式 —— */
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
