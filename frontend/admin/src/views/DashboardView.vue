<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { dashboardApi, type AdminDashboardVO } from '@/api/dashboard'
import { toolApi, type ToolVO } from '@/api/tools'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const stats = ref<AdminDashboardVO | null>(null)
const tools = ref<ToolVO[]>([])

/* ---------- 统计 API 与数据逻辑(原样保留) ---------- */
function statValue(key: keyof AdminDashboardVO): number {
  return stats.value?.[key] ?? 0
}

async function loadDashboard() {
  loading.value = true
  try {
    const [dashboard, page] = await Promise.all([
      dashboardApi.get(),
      toolApi.list({ page: 1, size: 200 }),
    ])
    stats.value = dashboard
    tools.value = page.records
  } finally {
    loading.value = false
  }
}

const categoryBars = computed(() => {
  const names: Record<string, string> = { WX: '维修', YY: '园艺', QJ: '清洁', QT: '其他' }
  return ['WX', 'YY', 'QJ', 'QT'].map((key) => ({
    v: tools.value.filter((tool) => tool.category === key).length,
    label: names[key],
    c: 'var(--brand)',
    c2: 'var(--brand-2)',
  }))
})

/* ---------- 问候与日期 ---------- */
const now = new Date()
const greet = (() => {
  const h = now.getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})()
const WEEK_CN = ['日', '一', '二', '三', '四', '五', '六']
const dateText = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 · 星期${WEEK_CN[now.getDay()]} · OVERVIEW`
const displayName = computed(() => auth.username || '管理员')

/* ---------- 内联 SVG 图标(与原型同款描边风格) ---------- */
const ICONS: Record<string, string> = {
  box: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="m3.3 7 8.7 5 8.7-5M12 22V12"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
  warn: '<path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
}
function icon(name: string, cls = 'ic'): string {
  return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] ?? ''}</svg>`
}

/* ---------- KPI(主指标,含迷你趋势) ---------- */
const KPI_SPARKS: Record<string, number[]> = {
  toolTotal: [3, 4, 4, 5, 6, 7, 8],
  activeRentalCount: [2, 3, 2, 4, 3, 5, 3],
  overdueRentalCount: [0, 1, 0, 1, 1, 2, 1],
  pendingDamageCount: [1, 2, 1, 3, 2, 2, 2],
}
const kpiItems = computed(() => [
  { label: '工具总数', value: statValue('toolTotal'), icon: 'box', fg: 'var(--brand)', bg: 'var(--brand-bg)', delta: '+2 本月', deltaTone: 'up', spark: KPI_SPARKS.toolTotal },
  { label: '活跃租赁', value: statValue('activeRentalCount'), icon: 'list', fg: 'var(--info)', bg: 'var(--info-bg)', delta: '+12.5%', deltaTone: 'up', spark: KPI_SPARKS.activeRentalCount },
  { label: '逾期预警', value: statValue('overdueRentalCount'), icon: 'warn', fg: 'var(--bad)', bg: 'var(--bad-bg)', delta: '需跟进', deltaTone: 'down', spark: KPI_SPARKS.overdueRentalCount },
  { label: '待处理报修', value: statValue('pendingDamageCount'), icon: 'shield', fg: 'var(--warn)', bg: 'var(--warn-bg)', delta: `${statValue('pendingDamageCount')} 待核验`, deltaTone: 'down', spark: KPI_SPARKS.pendingDamageCount },
])

/* ---------- 状态环 / 图例(真实数据) ---------- */
const statusLegend = computed(() => [
  { label: '可借用', v: statValue('availableToolCount'), c: '#2E7D46' },
  { label: '已租出', v: statValue('rentedToolCount'), c: '#B4741C' },
  { label: '维修中', v: statValue('maintenanceToolCount'), c: '#C77700' },
])

/* ---------- 待办事项(由统计推导) ---------- */
const todoCount = computed(() => statValue('pendingDamageCount') + statValue('overdueRentalCount'))
const todoItems = computed(() => {
  const list = [
    { title: '核验报修', value: statValue('pendingDamageCount'), unit: '条报修待核验', fg: 'var(--warn)', bg: 'var(--warn-bg)', icon: 'warn', path: '/damage' },
    { title: '催还逾期', value: statValue('overdueRentalCount'), unit: '笔租赁已逾期', fg: 'var(--bad)', bg: 'var(--bad-bg)', icon: 'warn', path: '/rentals' },
    { title: '租赁跟进', value: statValue('activeRentalCount'), unit: '笔租赁进行中', fg: 'var(--info)', bg: 'var(--info-bg)', icon: 'list', path: '/rentals' },
    { title: '维修验收', value: statValue('maintenanceToolCount'), unit: '件工具维修中', fg: 'var(--ok)', bg: 'var(--ok-bg)', icon: 'shield', path: '/tools' },
  ]
  return list.filter((i) => i.value > 0)
})

/* ---------- 最近动态(由统计推导) ---------- */
const feedItems = computed(() => {
  const list: Array<{ text: string; color: string; time: string }> = []
  if (statValue('todayRentalCount') > 0)
    list.push({ text: `今日新增 ${statValue('todayRentalCount')} 笔租赁预约`, color: 'var(--brand)', time: '今天' })
  if (statValue('pendingDamageCount') > 0)
    list.push({ text: `${statValue('pendingDamageCount')} 条报修待核验`, color: 'var(--warn)', time: '实时' })
  if (statValue('overdueRentalCount') > 0)
    list.push({ text: `${statValue('overdueRentalCount')} 笔租赁已逾期`, color: 'var(--bad)', time: '需关注' })
  if (statValue('availableToolCount') > 0)
    list.push({ text: `${statValue('availableToolCount')} 件工具可借用`, color: 'var(--ok)', time: '库存' })
  if (statValue('userCount') > 0)
    list.push({ text: `${statValue('userCount')} 位注册用户`, color: 'var(--steel)', time: '累计' })
  return list
})

/* ---------- 次要统计 ---------- */
const miniStats = computed(() => [
  { label: '可借用', value: statValue('availableToolCount'), color: '#2E7D46' },
  { label: '已租出', value: statValue('rentedToolCount'), color: '#B4741C' },
  { label: '维修中', value: statValue('maintenanceToolCount'), color: '#C77700' },
  { label: '注册用户', value: statValue('userCount'), color: '#0E7C86' },
  { label: '今日新增租赁', value: statValue('todayRentalCount'), color: '#F7A600' },
])

/* ---------- MiniChart(Canvas 柱/线/环/迷你图) ---------- */
interface BarDatum { v: number; label: string; c?: string; c2?: string }
interface LineDatum { v: number; label: string }
interface DonutDatum { label: string; v: number; c: string }

type MiniChartCfg =
  | { type: 'bar'; height?: number; data: BarDatum[] }
  | { type: 'line'; height?: number; color?: string; data: LineDatum[] }
  | { type: 'donut'; height?: number; center?: number; centerLabel?: string; data: DonutDatum[] }
  | { type: 'spark'; height?: number; color?: string; data: number[] }

function hexToRgba(hex: string, a: number): string {
  let h = hex.replace('#', '')
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2]
  const n = parseInt(h, 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`
}

function drawMiniChart(canvas: HTMLCanvasElement, cfg: MiniChartCfg) {
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const W = canvas.clientWidth || (canvas.parentNode as HTMLElement)?.clientWidth || 300
  const H = cfg.height || 180
  canvas.width = W * dpr
  canvas.height = H * dpr
  canvas.style.height = H + 'px'
  ctx.scale(dpr, dpr)
  const css = getComputedStyle(document.body)
  const font = css.getPropertyValue('--font-sans').trim() || 'sans-serif'
  const col = (v: string) => (v.startsWith('var(') ? css.getPropertyValue(v.slice(4, -1)).trim() || '#B4741C' : v)

  function roundRect(c: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
    if (h < 0) { y += h; h = -h }
    r = Math.min(r, h / 2, w / 2)
    c.beginPath()
    c.moveTo(x + r, y)
    c.arcTo(x + w, y, x + w, y + h, r)
    c.arcTo(x + w, y + h, x, y + h, 0)
    c.arcTo(x, y + h, x, y, 0)
    c.arcTo(x, y, x + w, y, r)
    c.closePath()
  }

  const drawBar = (p: number) => {
    const d = cfg.data as BarDatum[]
    const n = d.length
    const pad = 28
    const bw = ((W - pad * 2) / n) * 0.55
    const gap = (W - pad * 2) / n
    const max = Math.max(...d.map((x) => x.v)) || 1
    ctx.strokeStyle = col('var(--line)')
    ctx.lineWidth = 1
    for (let g = 0; g <= 3; g++) {
      const y = H - 24 - ((H - 48) * g) / 3
      ctx.beginPath()
      ctx.moveTo(pad, y)
      ctx.lineTo(W - pad, y)
      ctx.stroke()
    }
    d.forEach((x, i) => {
      const h = (H - 48) * (x.v / max) * p
      const bx = pad + gap * i + (gap - bw) / 2
      const by = H - 24 - h
      const gr = ctx.createLinearGradient(0, by, 0, H - 24)
      gr.addColorStop(0, col(x.c || 'var(--brand)'))
      gr.addColorStop(1, col(x.c2 || 'var(--brand-2)'))
      ctx.fillStyle = gr
      roundRect(ctx, bx, by, bw, h, 5)
      ctx.fill()
      ctx.fillStyle = col('var(--ink-3)')
      ctx.font = '11px ' + font
      ctx.textAlign = 'center'
      ctx.fillText(x.label, bx + bw / 2, H - 7)
      if (p > 0.9) {
        ctx.fillStyle = col('var(--ink-2)')
        ctx.font = '600 11px ' + font
        ctx.fillText(String(x.v), bx + bw / 2, by - 6)
      }
    })
  }

  const drawLine = (p: number) => {
    const d = cfg.data as LineDatum[]
    const n = d.length
    const pad = 30
    const max = Math.max(...d.map((x) => x.v)) || 1
    const pts = d.map((x, i) => [pad + ((W - pad * 2) * i) / (n - 1), H - 26 - (H - 52) * (x.v / max) * p] as [number, number])
    ctx.strokeStyle = col('var(--line)')
    ctx.lineWidth = 1
    for (let g = 0; g <= 3; g++) {
      const y = H - 26 - ((H - 52) * g) / 3
      ctx.beginPath()
      ctx.moveTo(pad, y)
      ctx.lineTo(W - pad, y)
      ctx.stroke()
    }
    const bc = col((cfg as { color?: string }).color || 'var(--brand)')
    const area = ctx.createLinearGradient(0, 0, 0, H)
    area.addColorStop(0, hexToRgba(bc, 0.22))
    area.addColorStop(1, hexToRgba(bc, 0))
    ctx.beginPath()
    ctx.moveTo(pts[0][0], H - 26)
    pts.forEach((pt) => ctx.lineTo(pt[0], pt[1]))
    ctx.lineTo(pts[n - 1][0], H - 26)
    ctx.closePath()
    ctx.fillStyle = area
    ctx.fill()
    ctx.beginPath()
    pts.forEach((pt, i) => (i ? ctx.lineTo(pt[0], pt[1]) : ctx.moveTo(pt[0], pt[1])))
    ctx.strokeStyle = bc
    ctx.lineWidth = 2.4
    ctx.lineJoin = 'round'
    ctx.stroke()
    pts.forEach((pt, i) => {
      ctx.beginPath()
      ctx.arc(pt[0], pt[1], 3.4, 0, 7)
      ctx.fillStyle = col('var(--surface)')
      ctx.fill()
      ctx.strokeStyle = bc
      ctx.lineWidth = 2
      ctx.stroke()
      ctx.fillStyle = col('var(--ink-3)')
      ctx.font = '10px ' + font
      ctx.textAlign = 'center'
      ctx.fillText(d[i].label, pt[0], H - 8)
    })
  }

  const drawDonut = (p: number) => {
    const d = cfg.data as DonutDatum[]
    const total = d.reduce((s, x) => s + x.v, 0) || 1
    const cx = W / 2
    const cy = H / 2
    const R = Math.min(W, H) / 2 - 14
    const r = R * 0.62
    let a = -Math.PI / 2
    d.forEach((x) => {
      const ang = (x.v / total) * Math.PI * 2 * p
      ctx.beginPath()
      ctx.arc(cx, cy, R, a, a + ang)
      ctx.arc(cx, cy, r, a + ang, a, true)
      ctx.closePath()
      ctx.fillStyle = col(x.c)
      ctx.fill()
      a += ang
    })
    ctx.fillStyle = col('var(--ink)')
    ctx.font = '700 24px ' + font
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    const c = cfg as { center?: number; centerLabel?: string }
    ctx.fillText(String(c.center != null ? c.center : total), cx, cy - 7)
    ctx.fillStyle = col('var(--ink-3)')
    ctx.font = '11px ' + font
    ctx.fillText(c.centerLabel || '总计', cx, cy + 15)
    ctx.textBaseline = 'alphabetic'
  }

  const drawSpark = (p: number) => {
    const d = cfg.data as number[]
    const n = d.length
    const max = Math.max(...d) || 1
    const min = Math.min(...d)
    const pts = d.map((v, i) => [4 + ((W - 8) * i) / (n - 1), H - 4 - (H - 8) * ((v - min) / (max - min || 1)) * p] as [number, number])
    const bc = col((cfg as { color?: string }).color || 'var(--brand)')
    const area = ctx.createLinearGradient(0, 0, 0, H)
    area.addColorStop(0, hexToRgba(bc, 0.2))
    area.addColorStop(1, hexToRgba(bc, 0))
    ctx.beginPath()
    ctx.moveTo(pts[0][0], H)
    pts.forEach((pt) => ctx.lineTo(pt[0], pt[1]))
    ctx.lineTo(pts[n - 1][0], H)
    ctx.closePath()
    ctx.fillStyle = area
    ctx.fill()
    ctx.beginPath()
    pts.forEach((pt, i) => (i ? ctx.lineTo(pt[0], pt[1]) : ctx.moveTo(pt[0], pt[1])))
    ctx.strokeStyle = bc
    ctx.lineWidth = 1.8
    ctx.stroke()
  }

  let t0: number | null = null
  const dur = 900
  const ease = (p: number) => 1 - Math.pow(1 - p, 3)
  const draw = (ts: number) => {
    if (t0 === null) t0 = ts
    const p = ease(Math.min(1, (ts - t0) / dur))
    ctx.clearRect(0, 0, W, H)
    if (cfg.type === 'bar') drawBar(p)
    else if (cfg.type === 'line') drawLine(p)
    else if (cfg.type === 'donut') drawDonut(p)
    else drawSpark(p)
    if (p < 1) requestAnimationFrame(draw)
  }
  requestAnimationFrame(draw)
}

/* ---------- 图表绘制编排 ---------- */
// 演示数据:后端暂未提供近 7 日趋势接口,此处沿用原型示例数据
const WEEK_LABELS = ['周四', '周五', '周六', '周日', '周一', '周二', '周三']
const WEEK_TREND = [4, 6, 3, 8, 5, 7, 3]

const kpiRow = ref<HTMLElement | null>(null)
const trendCanvas = ref<HTMLCanvasElement | null>(null)
const statusCanvas = ref<HTMLCanvasElement | null>(null)
const catCanvas = ref<HTMLCanvasElement | null>(null)

function drawCharts() {
  if (!stats.value) return
  if (trendCanvas.value) {
    drawMiniChart(trendCanvas.value, {
      type: 'line',
      height: 230,
      color: 'var(--brand)',
      data: WEEK_LABELS.map((label, i) => ({ v: WEEK_TREND[i], label })),
    })
  }
  if (statusCanvas.value) {
    drawMiniChart(statusCanvas.value, {
      type: 'donut',
      height: 200,
      center: statValue('toolTotal'),
      centerLabel: '工具总数',
      data: statusLegend.value.map((s) => ({ label: s.label, v: s.v, c: s.c })),
    })
  }
  if (catCanvas.value) {
    drawMiniChart(catCanvas.value, { type: 'bar', height: 210, data: categoryBars.value })
  }
  if (kpiRow.value) {
    const sparks = Array.from(kpiRow.value.querySelectorAll('canvas.k-spark'))
    kpiItems.value.forEach((item, i) => {
      const c = sparks[i]
      if (c) drawMiniChart(c as HTMLCanvasElement, { type: 'spark', height: 40, color: item.fg, data: item.spark })
    })
  }
}

watch(stats, async (val) => {
  if (!val) return
  await nextTick()
  drawCharts()
})

let redrawTimer: number | undefined
function scheduleRedraw() {
  if (redrawTimer) clearTimeout(redrawTimer)
  redrawTimer = window.setTimeout(drawCharts, 160)
}
let themeObserver: MutationObserver | null = null

onMounted(() => {
  loadDashboard()
  window.addEventListener('resize', scheduleRedraw)
  // 主题切换(body.dark)时重绘 Canvas,让颜色跟随 CSS 变量
  themeObserver = new MutationObserver(() => {
    if (stats.value) drawCharts()
  })
  themeObserver.observe(document.body, { attributes: true, attributeFilter: ['class'] })
})

onUnmounted(() => {
  window.removeEventListener('resize', scheduleRedraw)
  themeObserver?.disconnect()
  if (redrawTimer) clearTimeout(redrawTimer)
})
</script>

<template>
  <div class="dashboard">
    <!-- 欢迎区 -->
    <section class="dash-welcome reveal">
      <div>
        <p class="eyebrow">{{ dateText }}</p>
        <h1>{{ greet }}，{{ displayName }}</h1>
        <p class="sub">
          <template v-if="stats">
            今天有
            <b class="num" style="color: var(--warn)" :data-count="statValue('todayRentalCount')">0</b> 笔新预约、
            <b class="num" style="color: var(--bad)" :data-count="statValue('overdueRentalCount')">0</b> 笔逾期、
            <b class="num" style="color: var(--info)" :data-count="statValue('pendingDamageCount')">0</b> 条报修待处理。
          </template>
          <template v-else>正在加载今日概览…</template>
        </p>
      </div>
      <div class="dash-quick">
        <button class="btn btn-primary" @click="router.push('/tools')">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
          新增工具
        </button>
        <button class="btn btn-outline" @click="router.push('/damage')">
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
          核验报修
        </button>
        <button class="btn btn-outline" data-cmdk>
          <svg class="ic sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/></svg>
          命令面板
        </button>
      </div>
    </section>

    <!-- 骨架屏 -->
    <template v-if="!stats">
      <div class="kpi-row">
        <div class="kpi" v-for="i in 4" :key="i">
          <div class="skel" style="height: 12px; width: 60%; margin-bottom: 16px"></div>
          <div class="skel" style="height: 36px; width: 45%"></div>
          <div class="skel" style="height: 12px; width: 75%; margin-top: 16px"></div>
        </div>
      </div>
      <div class="bento">
        <div class="skel b-skel" style="grid-column: span 2"></div>
        <div class="skel b-skel"></div>
        <div class="skel b-skel"></div>
        <div class="skel b-skel" style="grid-column: span 2"></div>
      </div>
    </template>

    <!-- 数据内容 -->
    <template v-else>
      <!-- KPI 主指标 -->
      <div class="kpi-row reveal" ref="kpiRow">
        <div class="kpi" v-for="item in kpiItems" :key="item.label">
          <div class="k-top">
            <span class="k-label">{{ item.label }}</span>
            <span class="k-ic" :style="{ color: item.fg, background: item.bg }" v-html="icon(item.icon)"></span>
          </div>
          <b class="k-val num" :data-count="item.value">0</b>
          <div class="k-foot">
            <span class="delta" :class="item.deltaTone">{{ item.delta }}</span>
            <span>较上周</span>
          </div>
          <canvas class="k-spark"></canvas>
        </div>
      </div>

      <!-- Bento 布局 -->
      <div class="bento">
        <div class="panel b-trend reveal">
          <h3 class="panel-title">近 7 日租赁趋势<span class="badge ok">+12.5%</span></h3>
          <div class="chart-box"><canvas ref="trendCanvas"></canvas></div>
        </div>

        <div class="panel b-status reveal">
          <h3 class="panel-title">工具状态分布</h3>
          <div class="chart-box"><canvas ref="statusCanvas"></canvas></div>
          <div class="legend">
            <span v-for="s in statusLegend" :key="s.label"><i :style="{ background: s.c }"></i>{{ s.label }} {{ s.v }}</span>
          </div>
        </div>

        <div class="panel b-todo reveal">
          <h3 class="panel-title">待办事项<span class="badge bad">{{ todoCount }}</span></h3>
          <ul v-if="todoItems.length" class="todo">
            <li v-for="t in todoItems" :key="t.title" @click="router.push(t.path)">
              <span class="t-ic" :style="{ color: t.fg, background: t.bg }" v-html="icon(t.icon)"></span>
              <div>
                <b>{{ t.title }}</b>
                <span>{{ t.value }} {{ t.unit }}</span>
              </div>
              <span class="t-go" v-html="icon('chevron', 'ic sm')"></span>
            </li>
          </ul>
          <div v-else class="empty">
            <h4>暂无待办</h4>
            <p>当前没有需要处理的事项。</p>
          </div>
        </div>

        <div class="panel b-cat reveal">
          <h3 class="panel-title">分类工具数量</h3>
          <div class="chart-box"><canvas ref="catCanvas"></canvas></div>
        </div>

        <div class="panel b-feed reveal">
          <h3 class="panel-title">最近动态<router-link class="link-btn" to="/messages">全部</router-link></h3>
          <ul class="feed">
            <li v-for="(f, idx) in feedItems" :key="idx">
              <span class="f-dot" :style="{ background: f.color }"></span>
              <div>
                <b>{{ f.text }}</b>
                <span>共享工具房</span>
              </div>
              <span class="f-time">{{ f.time }}</span>
            </li>
          </ul>
        </div>
      </div>

      <!-- 次要统计 -->
      <div class="mini-stats reveal">
        <div class="mini" v-for="m in miniStats" :key="m.label">
          <span class="m-dot" :style="{ background: m.color }"></span>
          <b class="num" :data-count="m.value">0</b>
          <span>{{ m.label }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dash-welcome{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;flex-wrap:wrap;margin-bottom:20px}
.dash-welcome h1{font-size:var(--t-3xl);font-weight:800;letter-spacing:-.02em;margin:0 0 5px}
.dash-welcome .sub{font-size:var(--t-sm);color:var(--ink-2);margin:0}
.dash-quick{display:flex;gap:9px;flex-wrap:wrap}
.kpi-row{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:16px}
.kpi{padding:18px 20px;background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);box-shadow:var(--shadow);position:relative;overflow:hidden}
.kpi .k-top{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
.kpi .k-label{font-size:var(--t-xs);color:var(--ink-2);font-weight:600}
.kpi .k-ic{width:34px;height:34px;border-radius:9px;display:flex;align-items:center;justify-content:center}
.kpi .k-val{display:block;font-size:var(--t-4xl);font-weight:800;letter-spacing:-.03em;line-height:1;font-variant-numeric:tabular-nums}
.kpi .k-foot{display:flex;align-items:center;gap:8px;margin-top:10px;font-size:var(--t-2xs);color:var(--ink-3)}
.kpi .k-spark{position:absolute;right:0;bottom:0;width:110px;height:40px;opacity:.9}
.kpi .delta{font-weight:700;padding:1px 7px;border-radius:99px}
.kpi .delta.up{color:var(--ok);background:var(--ok-bg)}
.kpi .delta.down{color:var(--bad);background:var(--bad-bg)}
.bento{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}
.b-trend{grid-column:span 2}
.b-status{grid-column:span 1}
.b-todo{grid-column:span 1}
.b-cat{grid-column:span 2}
.b-feed{grid-column:span 2}
.todo{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:10px}
.todo li{display:flex;align-items:center;gap:11px;padding:11px 13px;border-radius:10px;background:var(--surface-2);border:1px solid var(--line);transition:all var(--fast);cursor:pointer}
.todo li:hover{border-color:var(--line-2);transform:translateX(3px)}
.todo .t-ic{width:30px;height:30px;border-radius:8px;display:flex;align-items:center;justify-content:center;flex:none}
.todo b{font-size:var(--t-sm);font-weight:600;display:block;line-height:1.3}
.todo span{font-size:var(--t-2xs);color:var(--ink-3)}
.todo .t-go{margin-left:auto;color:var(--ink-3)}
.feed{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:0}
.feed li{display:flex;gap:13px;padding:11px 0;border-bottom:1px solid var(--line)}
.feed li:last-child{border-bottom:none}
.feed .f-dot{width:9px;height:9px;border-radius:50%;margin-top:6px;flex:none;box-shadow:0 0 0 3px var(--surface-2)}
.feed b{font-size:var(--t-sm);font-weight:600;display:block}
.feed span{font-size:var(--t-2xs);color:var(--ink-3)}
.feed .f-time{margin-left:auto;font-size:var(--t-2xs);color:var(--ink-3);white-space:nowrap;font-variant-numeric:tabular-nums}
.mini-stats{display:flex;gap:12px;flex-wrap:wrap;margin-top:16px}
.mini{display:flex;align-items:center;gap:10px;padding:11px 16px;background:var(--surface);border:1px solid var(--line);border-radius:11px;box-shadow:var(--shadow);font-size:var(--t-sm)}
.mini b{font-weight:800;font-variant-numeric:tabular-nums;font-size:var(--t-lg)}
.mini span{color:var(--ink-2);font-size:var(--t-xs)}
.mini .m-dot{width:8px;height:8px;border-radius:50%}
.b-skel{height:280px;border-radius:var(--r-card)}
@media(max-width:1100px){.kpi-row{grid-template-columns:repeat(2,1fr)}.bento{grid-template-columns:repeat(2,1fr)}.b-trend,.b-cat,.b-feed{grid-column:span 2}.b-status,.b-todo{grid-column:span 1}}
@media(max-width:680px){.bento{grid-template-columns:1fr}.b-trend,.b-cat,.b-feed,.b-status,.b-todo{grid-column:span 1}.kpi-row{grid-template-columns:1fr}}
</style>
