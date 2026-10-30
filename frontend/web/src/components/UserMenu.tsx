import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, LogOut } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { userApi } from '@/lib/api'
import { useUserInfo } from '@/lib/queries'
import { useAuthStore } from '@/stores/auth'

export function UserMenu() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const nickname = useAuthStore((s) => s.nickname)
  const avatar = useAuthStore((s) => s.avatar)
  const { data } = useUserInfo()

  const displayName = nickname || data?.nickname || '用户'

  const logoutMutation = useMutation({
    mutationFn: () => userApi.logout(),
    onSettled: () => {
      useAuthStore.getState().clear()
      queryClient.clear()
      navigate('/login', { replace: true })
    },
  })

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        openOnHover
        delay={100}
        closeDelay={120}
        className="flex items-center gap-2 rounded-full py-1 pr-2 pl-1 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <Avatar size="sm">
          <AvatarImage src={avatar || undefined} alt={displayName} />
          <AvatarFallback>{displayName.slice(0, 1)}</AvatarFallback>
        </Avatar>
        <span className="max-w-32 truncate text-sm font-medium">{displayName}</span>
        <ChevronDown className="size-4 text-muted-foreground" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-36">
        <DropdownMenuItem
          variant="destructive"
          onClick={() => logoutMutation.mutate()}
        >
          <LogOut />
          退出登录
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
