/**
 * 登录态 store（zustand + localStorage 持久化）。
 *
 * 只保存三样东西：token、昵称、头像。业务数据一律交给 React Query，
 * 避免把服务端状态复制进全局 store 导致两套缓存不一致。
 *
 * 持久化 key 为 `web-auth`，刷新页面后可恢复登录态；
 * `partialize` 显式限定写入字段，防止未来新增 action/临时状态被误持久化。
 *
 * 注意：localStorage 中的 token 可被同源脚本读取，因此页面必须做好
 * XSS 防护（富文本渲染统一走 `sanitizeHtml`）。请求层通过
 * `useAuthStore.getState().tokenValue` 注入 Bearer 头。
 */
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * 用户资料的展示子集。
 *
 * 与后端 `UserProfile` 解耦：store 只关心顶栏/头像需要的字段，
 * 完整资料仍由 `profileApi` + React Query 管理。
 */
interface Profile {
  nickname: string
  avatar: string
}

/**
 * 认证 store 的状态与动作。
 *
 * 状态：
 * - tokenValue：访问令牌，空串表示未登录；
 * - nickname/avatar：展示用资料，登录后由 `useUserInfo` 回填。
 *
 * 动作：
 * - setAuth：登录成功时写入 token；
 * - setProfile：拿到用户资料后更新展示字段；
 * - clear：登出/登录失效时重置全部字段。
 */
interface AuthState {
  tokenValue: string
  nickname: string
  avatar: string
  setAuth: (tokenValue: string) => void
  setProfile: (profile: Profile) => void
  clear: () => void
}

/**
 * 全局认证 store。
 *
 * 使用方式：
 * - 组件内用 selector 订阅，如 `useAuthStore((s) => s.tokenValue)`，
 *   避免无关字段变化导致重渲染；
 * - 非组件代码（请求拦截器、mutation 回调）用 `getState()` 读取或调用动作。
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      // 初始为未登录；若 localStorage 有持久化值，persist 会在水合时覆盖。
      tokenValue: '',
      nickname: '',
      avatar: '',
      /**
       * 写入登录 token。
       *
       * @param tokenValue 后端登录接口返回的访问令牌
       *
       * 仅更新 token，不清空资料；资料由随后的 userInfo 查询回填。
       */
      setAuth: (tokenValue) => set({ tokenValue }),
      /**
       * 更新展示用用户资料。
       *
       * @param profile 至少包含 nickname/avatar 的对象（可来自 UserProfile）
       */
      setProfile: ({ nickname, avatar }) => set({ nickname, avatar }),
      /**
       * 清空会话。
       *
       * 登录失效、主动退出时调用；重置后 RequireAuth 会重定向到登录页。
       */
      clear: () => set({ tokenValue: '', nickname: '', avatar: '' }),
    }),
    {
      // localStorage key；修改它会导致已登录用户需要重新登录。
      name: 'web-auth',
      /**
       * 只持久化数据字段，不持久化 action。
       *
       * 虽然函数本身不会被 JSON 序列化，但显式列出可以锁定存储结构，
       * 未来新增非敏感临时字段时不会意外落盘。
       */
      partialize: (state) => ({
        tokenValue: state.tokenValue,
        nickname: state.nickname,
        avatar: state.avatar,
      }),
    },
  ),
)
