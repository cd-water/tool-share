/**
 * 文件职责：导航栏右侧的用户菜单（UserMenu）。
 *
 * 展示当前用户头像、昵称与下拉箭头；展开后提供“退出登录”操作。
 * 昵称优先取 auth store 中的实时值（登录/资料保存后更新），缺失时回退到
 * useUserInfo 查询结果，再次回退为“用户”。头像为空时由 AvatarFallback
 * 显示昵称首字，因此不会出现破图。
 *
 * 退出登录通过 useLogout hook 完成：清理登录态、清空 React Query 缓存并跳转
 * 登录页。本组件不直接调用 API，保持与具体登出实现解耦。
 */
import { ChevronDown, LogOut } from 'lucide-react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useLogout } from '@/hooks/useLogout'
import { useUserInfo } from '@/hooks/useUserInfo'
import { useAuthStore } from '@/stores/auth'

/**
 * 用户菜单组件。
 *
 * @returns 一个悬停/点击展开的 DropdownMenu，触发器为头像 + 昵称。
 *
 * 副作用：
 * - useUserInfo 会在挂载时拉取用户信息（命中缓存则不重复请求）；
 * - 点击“退出登录”触发登出 mutation，成功后清空缓存并导航。
 *
 * 交互细节：桌面端悬停 100ms 打开、移出 120ms 关闭，兼顾鼠标与键盘；
 * 键盘用户可通过 Tab 聚焦触发器并用 Enter/Space 展开。
 */
export function UserMenu() {
  const logoutMutation = useLogout()
  const nickname = useAuthStore((s) => s.nickname)
  const avatar = useAuthStore((s) => s.avatar)
  const { data } = useUserInfo()
  // 昵称显示优先级：store（最新）→ 用户信息接口（兜底）→ 常量“用户”。
  // store 在资料保存成功后由 ProfilePage 写入，因此能立即反映修改。
  // 使用 || 而不是 ??，是为了把空字符串也视为缺失。

  const displayName = nickname || data?.nickname || '用户'

  // displayName 计算完成后进入渲染：
  // 触发器始终显示头像 + 昵称，菜单内容只在展开时挂载。
  // 组件不读取路由信息，因此不会因页面切换而重渲染。
  // 登出逻辑通过 mutation 注入，保持 UI 与请求实现解耦。
  return (
  // 下拉菜单根节点由 base-ui 管理开合状态与焦点；
  // 本组件不持有 open 状态，减少与路由/登录态无关的重渲染。
    <DropdownMenu>
      <DropdownMenuTrigger
        openOnHover
        delay={100}
        closeDelay={120}
        className="flex items-center gap-2 rounded-full py-1 pr-2 pl-1 outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {/*
         * 头像使用 sm 档位，与导航栏高度匹配；
         * 图片失败时 base-ui 自动回退到文字头像，首字取昵称第一个字符。
         * 昵称限制最大宽度并截断，避免长昵称挤压导航其他入口。
         * ChevronDown 仅作视觉提示，不单独处理点击。
         */}
        <Avatar size="sm">
          <AvatarImage src={avatar || undefined} alt={displayName} />
          <AvatarFallback>{displayName.slice(0, 1)}</AvatarFallback>
        </Avatar>
        {/*
         * 昵称文本限制最大宽度并截断，避免长昵称把导航入口挤出视口。
         * 箭头只作视觉提示，点击触发器任意位置都能展开菜单。
         */}
        <span className="max-w-32 truncate text-sm font-medium">{displayName}</span>
        <ChevronDown className="size-4 text-muted-foreground" />
      </DropdownMenuTrigger>
      {/*
       * 菜单内容靠右对齐（align="end"），与触发器右边缘对齐，
       * 避免在窄屏上溢出视口。最小宽度 36（9rem）容纳“退出登录”。
       */}
      <DropdownMenuContent align="end" className="min-w-36">
        {/*
         * 退出项使用 destructive 变体，提示这是不可逆的状态清理操作。
         * 点击后由 mutation 负责请求、清理缓存与导航，菜单随之卸载。
         * 本组件不提供二次确认：退出登录风险低，且可重新登录。
         */}
        <DropdownMenuItem
          variant="destructive"
          onClick={() => logoutMutation.mutate()}
        >
          <LogOut />
          退出登录
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  // 渲染结束：DropdownMenu 关闭后菜单内容卸载，触发器保持可见。
  // 头像与昵称的兜底逻辑保证任何登录态下都不会出现空白触发器。
  // 登出请求进行中时菜单可能已卸载，mutation 生命周期由 hook 管理。
  )
// 组件结束。UserMenu 只负责“展示身份 + 提供登出入口”：
// 用户信息取数、缓存与登出副作用分别由 useUserInfo / useLogout 承担。
// 若后续增加“个人中心”等菜单项，应在此扩展，而不是在布局层拼接。
}
