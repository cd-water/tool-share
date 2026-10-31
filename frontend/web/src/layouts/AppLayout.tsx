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
import { BrandMark } from '@/components/BrandMark'
import { UserMenu } from '@/components/UserMenu'
import { cn } from '@/lib/utils'

const NAV_ITEMS = [
  { to: '/tools', label: '工具广场', icon: Wrench },
  { to: '/rentals', label: '我的租赁', icon: ClipboardList },
  { to: '/damage', label: '损坏报修', icon: ShieldAlert },
  { to: '/messages', label: '消息中心', icon: Bell },
  { to: '/ai', label: 'AI 助手', icon: Sparkles },
  { to: '/profile', label: '个人中心', icon: UserRound },
]

function NavItems({
  unreadCount,
  className,
}: {
  unreadCount: number
  className?: string
}) {
  return (
    <nav
      className={cn(
        'flex items-center gap-1 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
        className,
      )}
    >
      {NAV_ITEMS.map((item) => {
        const Icon = item.icon
        return (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              cn(
                'flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground',
              )
            }
          >
            <Icon className="size-4" />
            <span>{item.label}</span>
            {item.to === '/messages' && unreadCount > 0 && (
              <span className="ml-0.5 inline-flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-4 font-semibold text-white">
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </NavLink>
        )
      })}
    </nav>
  )
}

export default function AppLayout() {
  const unreadQuery = useQuery({
    queryKey: ['messages', 'unread-count'],
    queryFn: messagesApi.unreadCount,
    staleTime: 30_000,
    refetchInterval: 60_000,
  })

  const unreadCount = unreadQuery.data ?? 0

  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto w-[min(1180px,calc(100vw-32px))]">
          <div className="flex h-16 items-center gap-4">
            <div className="flex shrink-0 items-center gap-2.5">
              <BrandMark size={34} />
              <span className="text-[17px] font-semibold tracking-tight whitespace-nowrap text-foreground">
                ToolShare
                <span className="hidden md:inline">
                  {' '}
                  <span className="font-normal">|</span> 社区共享工具租赁系统
                </span>
              </span>
            </div>
            <NavItems
              unreadCount={unreadCount}
              className="hidden flex-1 md:flex"
            />
            <div className="ml-auto md:ml-0">
              <UserMenu />
            </div>
          </div>
          <NavItems
            unreadCount={unreadCount}
            className="-mx-1 flex pb-2 md:hidden"
          />
        </div>
      </header>
      <main className="flex-1">
        <div className="mx-auto w-[min(1180px,calc(100vw-32px))] py-7 sm:py-9">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
