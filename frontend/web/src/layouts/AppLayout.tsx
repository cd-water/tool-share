import { Outlet, useLocation, useNavigate } from 'react-router'
import { BrandMark } from '@/components/BrandMark'
import { UserMenu } from '@/components/UserMenu'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'

const NAV_TABS = [
  { value: '/page-1', label: '页面1' },
  { value: '/page-2', label: '页面2' },
  { value: '/page-3', label: '页面3' },
]

export default function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()

  return (
    <div className="flex min-h-svh flex-col bg-background">
      <header className="sticky top-0 z-50 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-16 w-[min(1180px,calc(100vw-48px))] items-center gap-8">
          <div className="flex items-center gap-2.5">
            <BrandMark size={34} />
            <span className="text-[17px] font-semibold tracking-tight whitespace-nowrap text-foreground">
              ToolShare <span className="font-normal">|</span> 社区共享工具租赁系统
            </span>
          </div>
          <Tabs value={location.pathname} onValueChange={(value) => navigate(String(value))}>
            <TabsList variant="line">
              {NAV_TABS.map((page) => (
                <TabsTrigger key={page.value} value={page.value}>
                  {page.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
          <div className="ml-auto">
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="flex-1">
        <div className="mx-auto w-[min(1180px,calc(100vw-48px))] py-9">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
