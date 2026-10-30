import { Outlet, useLocation, useNavigate } from 'react-router'
import { BrandMark } from '@/components/BrandMark'
import { UserMenu } from '@/components/UserMenu'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'

const PAGES = [
  { value: '/page-1', label: '页面1' },
  { value: '/page-2', label: '页面2' },
  { value: '/page-3', label: '页面3' },
]

export default function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()

  return (
    <div className="flex min-h-full flex-col bg-[#FAFAF7]">
      <header className="grid h-16 shrink-0 grid-cols-[1fr_auto_1fr] items-center border-b border-border bg-background px-6">
        <div className="flex items-center gap-2.5">
          <BrandMark size={34} />
          <div className="leading-tight">
            <div className="text-[15px] font-semibold text-foreground">TicketBox</div>
            <div className="text-[15px] font-semibold text-foreground">票务盒子</div>
          </div>
        </div>
        <Tabs value={location.pathname} onValueChange={(value) => navigate(String(value))}>
          <TabsList variant="line">
            {PAGES.map((page) => (
              <TabsTrigger key={page.value} value={page.value}>
                {page.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
        <div className="justify-self-end">
          <UserMenu />
        </div>
      </header>
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  )
}
