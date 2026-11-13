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

const NAV_ITEMS = [
  { to: '/tools', label: '工具广场', icon: Wrench },
  { to: '/rentals', label: '我的租赁', icon: ClipboardList },
  { to: '/damage', label: '损坏报修', icon: ShieldAlert },
  { to: '/messages', label: '消息中心', icon: Bell },
  { to: '/ai', label: 'AI 助手', icon: Sparkles },
  { to: '/profile', label: '个人中心', icon: UserRound },
]

function NavBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return <span className="n-badge">{count > 99 ? '99+' : count}</span>
}

export default function AppLayout() {
  useEffect(() => {
    bindSoundToggle(document.getElementById('soundToggle'))
  }, [])

  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread-count'],
    queryFn: messagesApi.unreadCount,
    staleTime: 10_000,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const unreadCount = unreadQuery.data ?? 0

  const dashboardQuery = useQuery({
    queryKey: ['dashboard'],
    queryFn: dashboardApi.get,
    staleTime: 10_000,
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const pendingCount = dashboardQuery.data?.pendingPaymentCount ?? 0

  return (
    <>
      <div className="ambient"></div>
      <div className="shell">
        <header className="web-top">
          <div className="wrap">
            <a className="web-brand" href="/tools">
              <BrandMark size={34} />
              <span>
                <b>ToolShare</b>
                <small>社区工具共享租赁</small>
              </span>
            </a>
            <nav className="web-nav">
              {NAV_ITEMS.map((item) => {
                const Icon = item.icon
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
            <button className="icon-btn" id="soundToggle" title="关闭音效" aria-label="音效开关"></button>
            <UserMenu />
          </div>
        </header>
        <main className="main">
          <div className="wrap">
            <Outlet />
          </div>
        </main>
        <footer className="page-foot">
          <div className="wrap" style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span><b>ToolShare</b> · 社区共享工具租赁系统</span>
            <span>居民端 · 演示数据 · 2026</span>
          </div>
        </footer>
      </div>
    </>
  )
}
