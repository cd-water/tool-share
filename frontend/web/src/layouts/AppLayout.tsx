/**
 * 文件职责：居民端主布局（AppLayout）。
 *
 * 结构自上而下：
 * - 环境背景层（ambient）与页面外壳（shell）；
 * - 顶部导航：品牌、六个主入口、音效开关、用户菜单；
 * - 主内容区：通过 <Outlet /> 渲染子路由页面；
 * - 页脚：系统名称与演示数据说明。
 *
 * 数据依赖：未读消息数与待支付订单数来自 React Query，30 秒轮询并
 * 在窗口重新聚焦时刷新，用于驱动导航上的小红点。音效开关在挂载时绑定，
 * 复用登录页创建的全站控件（若不存在则静默跳过）。
 *
 * 该布局不做权限判断，登录守卫由 RequireAuth 在路由层完成。
 */
import { useEffect } from 'react'
import { NavLink, Outlet } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import {
  Bell,
  ClipboardList,
  Sparkles,
  UserRound,
  ShieldAlert,
  Wrench,
} from 'lucide-react'
import { messagesApi } from '@/api/messages'
import { dashboardApi } from '@/api/dashboard'
import { BrandMark } from '@/components/BrandMark'
import { UserMenu } from '@/components/UserMenu'
import { bindSoundToggle } from '@/lib/fx'
import { queryKeys } from '@/lib/queryKeys'

// 导航项配置：集中声明路径、文案与图标，渲染时统一 map。
// 新增页面入口时在此追加即可，无需改动 JSX 结构。
// 顺序即视觉顺序，建议按“找工具 → 用工具 → 售后 → 消息 → 智能 → 账户”排列。
const NAV_ITEMS = [
  { to: '/tools', label: '工具广场', icon: Wrench },
  { to: '/rentals', label: '我的租赁', icon: ClipboardList },
  { to: '/damage', label: '损坏报修', icon: ShieldAlert },
  { to: '/messages', label: '消息中心', icon: Bell },
  { to: '/ai', label: 'AI 助手', icon: Sparkles },
  { to: '/profile', label: '个人中心', icon: UserRound },
]

/**
 * 导航角标（小红点/数字）。
 *
 * @param props.count 待展示的数量，通常是未读消息数或待支付订单数。
 * @returns count <= 0 时返回 null；否则展示数字，超过 99 显示 “99+”。
 *
 * 副作用：无。角标只是提示，点击行为由外层 NavLink 承担。
 * 使用 <= 0 而不是 === 0，可兼容后端返回负数等异常值。
 */
function NavBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return <span className="n-badge">{count > 99 ? '99+' : count}</span>
}

/**
 * 主布局组件。
 *
 * @returns 顶部导航 + 内容 Outlet + 页脚的完整页面骨架。
 *
 * 副作用：
 * - 挂载时调用 bindSoundToggle 绑定音效按钮（返回清理函数由该工具内部管理）；
 * - 订阅未读消息与仪表盘查询，30s 轮询并在窗口聚焦时刷新；
 * - 查询失败不会阻断页面渲染，角标回退为 0。
 */
export default function AppLayout() {
  // 音效开关是全站唯一实例，登录页在 body 上创建它；
  // 进入主布局后再次绑定，确保从登录页直接跳转时事件仍然生效。
  // 依赖数组为空：只在布局挂载时执行一次，避免重复绑定。
  useEffect(() => {
    bindSoundToggle(document.getElementById('soundToggle'))
  }, [])

  // 未读消息数：用于消息中心入口角标。
  // staleTime 10s 避免路由切换时重复请求；30s 轮询保证提醒及时；
  // refetchOnWindowFocus 让用户切回标签页时立刻同步。
  // 该查询失败时 data 为 undefined，角标回退为 0，不影响导航可用性。
  const unreadQuery = useQuery({
    queryKey: queryKeys.messages.unreadCount,
    queryFn: messagesApi.unreadCount,
    staleTime: 10_000,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const unreadCount = unreadQuery.data ?? 0

  // 待支付订单数：用于“我的租赁”入口角标。
  // 与 unreadQuery 使用相同的刷新策略，保证两个角标节奏一致。
  // pendingPaymentCount 是仪表盘接口的聚合字段，无需额外请求订单列表。
  const dashboardQuery = useQuery({
    queryKey: queryKeys.dashboard,
    queryFn: dashboardApi.get,
    staleTime: 10_000,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const pendingCount = dashboardQuery.data?.pendingPaymentCount ?? 0

  // 根 Fragment 下并列背景层与外壳：
  // ambient 固定铺满视口，提供纹理/光晕；shell 承载实际内容。
  // 二者分离避免背景影响内容布局与滚动。
  // 渲染前先说明页面骨架的数据来源：
  // 顶部角标来自两个 React Query 结果，页面内容由 Outlet 注入。
  // 布局本身不持有业务状态，任何查询失败都只影响角标数字。
  // 所有子页面共享同一套导航与页脚，因此这里不重复挂载 Toast/Provider。
  // 背景层与内容层分离，滚动时背景保持固定，内容自然滚动。
  // 该 return 是布局的唯一输出，没有提前返回分支。
  return (
    <>
      {/*
       * ambient 是纯装饰层：固定定位、不拦截点击，
       * 由 CSS 提供纹理与光晕；对读屏与键盘完全隐藏。
       */}
      <div className="ambient"></div>
      {/*
       * shell 是内容外壳，承载顶栏、主内容与页脚。
       * 它不设置固定高度，页面高度由内容自然撑开。
       */}
      <div className="shell">
        {/*
         * 顶栏使用 wrap 限制最大宽度并水平居中；
         * 品牌链接直接指向 /tools（工具广场），符合“回到首页”的直觉。
         * 使用原生 <a> 而非 NavLink，因为品牌点击应重新加载工具广场状态。
         */}
        {/*
         * 顶栏使用 sticky/flex 布局（样式层负责），
         * 内部 wrap 限制最大宽度并居中，保证大屏下导航不散开。
         * 顶栏本身不参与数据请求，只消费布局层的查询结果。
         * 在所有子页面上保持一致的导航顺序与高亮规则。
         */}
        <header className="web-top">
          <div className="wrap">
          {/*
           * 品牌区：标识 + 中英文名称，整体是一个回到工具广场的链接。
           * 使用原生 a 标签以获得完整刷新语义，避免残留页面级弹窗状态。
           */}
            <a className="web-brand" href="/tools">
              <BrandMark size={34} />
              <span>
                <b>ToolShare</b>
                <small>社区工具共享租赁</small>
              </span>
            </a>
            {/*
             * 导航项由 NAV_ITEMS 统一生成：
             * NavLink 的 isActive 回调决定高亮类，避免手写路径匹配。
             * 图标对读屏隐藏（lucide 默认 aria-hidden），文字提供名称。
             * 角标只挂载在“我的租赁”和“消息中心”两个入口上。
             */}
            {/*
             * 主导航：由 NAV_ITEMS 驱动，当前路由由 NavLink 自动高亮。
             * “我的租赁”与“消息中心”额外挂载数量角标。
             * 图标仅作辅助，文本标签始终可见，保证可读性。
             */}
            <nav className="web-nav">
              {NAV_ITEMS.map((item) => {
                const Icon = item.icon
                // 每个导航项渲染前取出图标组件，
                // 交给 NavLink 作为子元素；NavLink 负责 active 类。
                // 角标条件渲染避免出现 0 或负数。
                // 这里不处理点击跳转，交给路由组件。
                return (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    className={({ isActive }) => (isActive ? 'active' : '')}
                  >
                    <Icon className="ic" />
                    <span>{item.label}</span>
                    {item.to === '/rentals' && <NavBadge count={pendingCount} />}
                    {item.to === '/messages' && <NavBadge count={unreadCount} />}
                  </NavLink>
                )
              })}
            </nav>
            {/*
             * 音效开关是空按钮：视觉图标由 CSS 伪元素/背景绘制，
             * 事件由 bindSoundToggle 绑定；title 与 aria-label 提供说明。
             * 如后续需要 React 受控状态，可改为读取 SoundFX.isOn()。
             */}
            {/*
             * 全站唯一的音效开关：图标与开合状态由 CSS/工具函数维护。
             * 空按钮内容是有意为之，避免 React 状态与全局 SoundFX 双写。
             */}
            <button className="icon-btn" id="soundToggle" title="关闭音效" aria-label="音效开关"></button>
            {/*
             * 用户菜单独立取数与交互，放在导航最右侧；
             * 它与布局的查询互不依赖，避免登录态变化引起整页刷新。
             */}
            {/*
             * 用户菜单独立请求用户信息，放在导航最右侧。
             */}
            <UserMenu />
          </div>
        </header>
        {/*
         * 主内容区只负责宽度约束与内边距；
         * 具体页面的标题、筛选、列表均由 Outlet 渲染。
         * 不使用额外的 key，保证路由切换时 React Query 缓存可复用。
         */}
          {/*
           * 主内容区：只负责宽度约束与上下留白。
           * 具体页面的加载/错误/空状态都由子页面自行处理。
           * 不使用 key 强制重挂载，保证查询缓存与滚动位置可复用。
           */}
        <main className="main">
          <div className="wrap">
            <Outlet />
          </div>
        </main>
        {/*
         * 页脚展示系统名称与“演示数据”声明，
         * 避免用户把演示环境的数据误认为真实业务数据。
         * 页脚不参与导航，也不包含需要权限的操作。
         */}
        {/*
         * 页脚：品牌名、系统说明与演示数据声明。
         * 不包含导航或权限操作，避免与顶栏职责重叠。
         * 在窄屏下由样式层改为纵向排列。
         */}
        <footer className="page-foot">
          <div className="wrap" style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span><b>ToolShare</b> · 社区共享工具租赁系统</span>
            <span>居民端 · 演示数据 · 2026</span>
          </div>
        </footer>
      </div>
    </>
// 布局组件结束。该文件只处理“框架级”关注点：
// 导航、全局角标、音效控件、页面容器与页脚。
// 业务页面的数据与交互应留在各自页面，避免布局变成第二个“上帝组件”。
// 若未来需要侧边栏或响应式抽屉，应在此扩展，而不是在页面内重复导航。
  // 布局输出结束：根 Fragment 只包含背景层与 shell 两个子节点。
  // 不额外包裹 div，避免在页面级样式之外引入无意义的布局层。
  // 若未来需要全局弹窗容器，应在此处添加而不是修改每个页面。
  )
// 组件结束。布局不订阅路由变化，因此路由切换只会重渲染 Outlet 内容。
// 全局查询在布局层常驻，页面切换不会重复触发未读数与仪表盘请求。
// 顶部角标与用户菜单的异常都被隔离，不会导致整页白屏。
}
