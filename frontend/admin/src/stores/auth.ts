/**
 * 管理端登录状态 store（Pinia setup 语法）。
 *
 * 保存 token、用户名和角色，并提供登录、拉取资料、注销等动作。
 * 开启 persist 后状态写入 localStorage：刷新页面不会丢失登录态；
 * token 由 request 拦截器读取并注入 Authorization 头。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'

/**
 * 鉴权 store。
 *
 * 状态刻意保持扁平：tokenValue 是登录态的唯一来源，
 * username/role 只用于展示和路由角色校验。
 */
export const useAuthStore = defineStore(
  'auth',
  () => {
    // 访问令牌；空串表示未登录，是所有登录判断的依据。
    const tokenValue = ref('')
    // 当前管理员用户名，用于顶栏展示。
    const username = ref('')
    // 当前管理员角色（STAFF / SUPER_ADMIN），用于路由和按钮权限。
    const role = ref('')
    // 计算属性：只根据 token 是否存在判断登录态。
    const isLoggedIn = computed(() => tokenValue.value !== '')
    // 是否系统管理员；模板中常用于控制管理类入口的显隐。
    const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')

    /**
     * 登录并初始化会话。
     *
     * @param username 登录名
     * @param password 明文密码
     * 副作用：写入 token 后立即拉取用户信息，保证 role/username 就绪。
     * @throws 登录失败时直接向上抛出，由调用方展示错误或停留在登录页。
     */
    async function login(username: string, password: string) {
      // 登录请求失败会直接抛给调用方，不在此处吞错。
      const data = await authApi.login(username, password)
      // 先落 token，后续 info 请求才能带上 Authorization 头。
      tokenValue.value = data.tokenValue
      // 登录响应只有 token，用户名与角色统一再查一次资料。
      await fetchInfo()
    }

    /**
     * 拉取当前管理员资料并更新 username/role。
     *
     * 用于登录后初始化，以及旧会话（只持久化了 token）中补齐角色。
     * @throws token 失效或网络异常时向上抛出。
     */
    async function fetchInfo() {
      // 以服务端返回为准刷新本地资料，避免信任过期缓存。
      const data = await authApi.info()
      // 只更新展示字段，token 保持不变。
      username.value = data.username
      role.value = data.role
    }

    /**
     * 清空本地会话状态。
     *
     * 纯同步操作，不发请求；由 logout 或 401 拦截逻辑调用。
     */
    function reset() {
      // 三个字段一起清空，保证 isLoggedIn/isSuperAdmin 同步失效。
      tokenValue.value = ''
      username.value = ''
      role.value = ''
    }

    /**
     * 注销登录。
     *
     * 先通知后端使 token 失效，再在 finally 中清理本地状态：
     * 即使注销请求失败，也不会让用户停留在「假登录」状态。
     * 注意：本方法不负责跳转，路由由调用方决定。
     */
    async function logout() {
      // 即使后端注销失败，本地会话也必须清理。
      try {
        // 通知服务端使当前 token 失效。
        await authApi.logout()
      // finally 保证本地状态一定被清空。
      } finally {
        // 统一走 reset，避免遗漏字段。
        reset()
      }
    }

    // 对外暴露状态与动作；setup store 返回的 ref 会被自动解包。
    return { tokenValue, username, role, isLoggedIn, isSuperAdmin, login, fetchInfo, logout, reset }
  },
  // 开启 Pinia 持久化：默认写入 localStorage，刷新后恢复登录态。
  // 只持久化可序列化的状态，computed 与函数不会被保存。
  { persist: true },
)
