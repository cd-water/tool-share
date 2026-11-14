<script setup lang="ts">
/**
 * 文件：frontend/admin/src/layouts/AdminLayout.vue
 * 职责：管理端所有页面的外层骨架，负责侧边导航、顶栏、命令面板注册、
 *       登录态兜底、主题/音效开关以及退出登录。
 *
 * 权限模型：
 * - 侧边栏只渲染当前角色可见的入口。工具管理、知识库、管理员账号仅 SUPER_ADMIN 可见；
 *   仪表盘、租赁、报修、消息中心对 STAFF 与 SUPER_ADMIN 都开放。
 * - 具体接口是否越权仍由后端鉴权，前端菜单过滤只是体验层面的收口。
 *
 * 与接口的关系：
 * - 首次挂载时若本地只有 token 而没有角色信息，会调用 auth.fetchInfo() 补齐会话；
 *   失败时交给 axios 拦截器统一跳转登录页，布局本身不重复处理。
 * - auth.logout() 负责清理本地会话，完成后无论成功与否都跳转 /login。
 *
 * 与工具库的关系：
 * - setCmdItems 把可见菜单、主题切换、退出登录注册到全局命令面板（Ctrl+K）。
 * - bindSoundToggle / bindThemeToggle 把顶栏两个占位按钮绑定到统一交互库。
 */
/**
 * 依赖说明：
 * - vue-router 提供当前路由与跳转能力，用于菜单高亮和命令面板导航。
 * - Element Plus 的 ElMessageBox 只用于退出前的二次确认。
 * - @element-plus/icons-vue 的图标用于侧边栏菜单项。
 * - useAuthStore 提供 username、role、isSuperAdmin、fetchInfo、logout 等会话字段。
 * - BrandMark 是统一品牌标识，避免每个页面重复绘制 Logo。
 * - @/lib/fx 统一处理音效、主题与命令面板，布局层不直接操作 localStorage。
 */
/**
 * import 分组约定：
 * 1. Vue 运行时 API；
 * 2. vue-router 路由 API；
 * 3. Element Plus 交互组件；
 * 4. Element Plus 图标；
 * 5. 项目内 store、组件与工具库。
 * 这样在排查权限问题时，可以快速定位菜单数据来源与跳转逻辑；
 * 在排查视觉问题时，可以快速定位图标和品牌组件的来源。
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell,
  Box,
  Odometer,
  Reading,
  Tickets,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import BrandMark from '@/components/BrandMark.vue'
import { bindSoundToggle, bindThemeToggle, setCmdItems } from '@/lib/fx'

/**
 * 路由与认证实例：
 * - route 用于读取 path/meta.title，支撑菜单激活态与面包屑标题。
 * - router 用于命令面板跳转以及退出后的登录页跳转。
 * - auth 是整个管理端的会话来源，后续权限渲染都以它为准。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 菜单配置：
 * - path 必须与 router 中的管理端路由保持一致，否则高亮和跳转会失效。
 * - roles 省略表示所有已登录管理员可见；显式列出时按 auth.role 过滤。
 * - 图标直接存组件引用，模板里通过 <component :is> 动态渲染。
 * - 消息中心保留红点占位，真正的未读数量在消息页内部单独查询。
 */
const menuItems = [
  { path: '/dashboard', title: '仪表盘', icon: Odometer },
  { path: '/tools', title: '工具管理', icon: Box, roles: ['SUPER_ADMIN'] },
  { path: '/rentals', title: '租赁管理', icon: Tickets },
  { path: '/damage', title: '报修管理', icon: Warning },
  { path: '/knowledge', title: '知识库', icon: Reading, roles: ['SUPER_ADMIN'] },
  { path: '/accounts', title: '管理员账号', icon: User, roles: ['SUPER_ADMIN'] },
  { path: '/messages', title: '消息中心', icon: Bell },
]

/**
 * 可见菜单计算属性：
 * - 过滤规则与后端角色矩阵保持一致：没有 roles 字段的菜单对 STAFF 与 SUPER_ADMIN 都可见。
 * - 之所以用 computed 而不是在 onMounted 里算一次，是为了在角色被修改后立即刷新菜单。
 * - 返回值同时被模板和命令面板复用，避免两处维护不同过滤逻辑。
 */
/**
 * 过滤时使用 includes 而不是精确比较，是为了兼容未来可能出现的复合角色。
 * 如果后端将来新增审核员等角色，只需在菜单项 roles 中追加即可，
 * 布局层不需要再写新的 if 分支。
 */
const visibleMenuItems = computed(() =>
  menuItems.filter((item) => !item.roles || item.roles.includes(auth.role)),
)

/**
 * 生命周期挂载逻辑：
 * 1. 旧版本会话可能只持久化 token，没有缓存 role；这里补拉一次用户信息，
 *    避免命令面板和菜单在角色为空时把所有受限入口都渲染出来。
 * 2. fetchInfo 失败通常意味着 token 已失效，axios 拦截器会负责跳转登录页，
 *    因此这里只吞掉异常，不再重复弹窗或跳转。
 * 3. 绑定顶栏的音效与主题开关。两个按钮在模板里只有 id，没有独立状态，
 *    状态完全由 fx 模块维护，刷新后仍能恢复。
 * 4. 注册命令面板条目：可见菜单 + 主题切换 + 退出登录。
 *    命令面板入口本身也受角色影响，避免 STAFF 通过快捷键进入管理员专属页面。
 */
/**
 * 挂载阶段刻意保持轻量：
 * - 不在这里拉取仪表盘统计、工具列表或消息未读数，
 *   这些数据由各自页面负责，避免布局切换时产生额外请求。
 * - 仅补拉会话信息，因为菜单渲染依赖 role。
 * - 命令面板条目在角色补拉完成后才注册，
 *   否则受限入口可能被短暂写入命令列表。
 */
onMounted(async () => {
  // 旧会话可能只持久化了 token，先补拉角色，避免命令面板出现无权限入口
  if (!auth.role) {
    try {
      await auth.fetchInfo()
    } catch {
      // 拦截器已处理登录失效
    }
  }
  bindSoundToggle(document.getElementById('soundToggle'))
  bindThemeToggle(document.getElementById('themeToggle'))
  setCmdItems([
    ...visibleMenuItems.value.map((item) => ({ label: item.title, fn: () => router.push(item.path) })),
    { label: '切换主题', fn: () => document.getElementById('themeToggle')?.click() },
    { label: '退出登录', fn: handleLogout },
  ])
})

/**
 * 退出登录：
 * - 先弹出确认框，用户取消时直接返回，不触发任何网络请求。
 * - auth.logout() 放到 try/finally 中，即使后端登出接口报错也要回到登录页，
 *   防止本地残留 token 让用户卡在一个已失效的会话里。
 * - 使用 router.push 而不是整页刷新，保留前端应用实例。
 */
/**
 * 退出流程的错误边界：
 * - 确认框取消属于用户主动行为，不提示错误。
 * - 登出接口失败仍然清理前端路由，避免用户停留在无权限页面。
 * - 不清理主题与音效偏好，这些是设备级体验设置，不属于会话数据。
 */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await auth.logout()
  } finally {
    await router.push('/login')
  }
}
</script>

<!-- 模板根节点：ambient 负责全局背景光效，admin-shell 负责左右分栏布局。 -->
<!-- 布局不承载业务数据，只消费 auth store 与路由元信息。 -->
<!-- 侧栏固定宽度，主区域自适应；移动端样式由全局样式表统一处理。 -->
<!-- 所有导航入口、顶栏操作和退出按钮都在本模板内完成组装。 -->
<!-- 模板结构总览：最外层 admin-shell 使用左右两列，左侧固定导航、右侧自适应主内容。 -->
<!-- ambient 与侧栏背景层只负责营造视觉氛围，不接收指针事件，也不进入业务布局流。 -->
<!-- 侧栏由品牌、导航、占位、用户信息四段组成，方便未来在导航与用户信息之间插入帮助入口。 -->
<!-- 顶栏与内容区共享同一个主列，页面滚动只发生在内容区，顶栏保持可见。 -->
<!-- 该结构刻意不引入额外组件，减少布局层与业务页面之间的耦合。 -->
<!-- 若需要在移动端折叠侧栏，只需在全局样式中改变 admin-shell 的列定义。 -->
<!-- 所有可点击元素都使用原生 button 或 router-link，保留键盘可达性。 -->
<!-- 图标按钮均带 aria-label，视觉隐藏文字不影响读屏理解。 -->
<!-- 菜单高亮只依赖 route.path，不维护额外的选中状态，避免路由切换后不同步。 -->
<!-- 角色文案与权限菜单来自同一 auth store，两者不会出现互相矛盾的展示。 -->
<!-- 命令面板、音效、主题三个入口在模板中保持独立，便于单独禁用或替换。 -->
<!-- 本布局不包含任何页脚版权信息，版本与构建信息由各自页面或登录页展示。 -->
<template>
<!-- 背景氛围层：纯装饰元素，无交互，不参与无障碍树。 -->
<!-- 使用绝对定位铺满视口，避免影响 admin-shell 的布局计算。 -->
  <div class="ambient"></div>
  <div class="admin-shell">
<!-- 左侧栏：品牌区、角色过滤后的导航、当前登录人信息与退出入口。 -->
<!-- 侧栏内容不直接请求接口，用户名与角色均来自 auth store。 -->
<!-- 角色标识用于提醒当前操作者权限范围，不参与鉴权判断。 -->
<!-- 退出按钮复用 handleLogout，与顶栏和命令面板保持同一套逻辑。 -->
    <aside class="side">
      <div class="brand">
        <BrandMark :size="34" />
        <div>
          <b>ToolShare</b>
          <span>管理控制台</span>
        </div>
      </div>
<!-- 导航列表：只遍历 visibleMenuItems，因此受限入口不会出现在 DOM 中。 -->
<!-- active 判断使用 startsWith，保证详情页等子路由也能点亮所属一级菜单。 -->
<!-- 消息中心用红点占位，真实未读数在消息中心页面加载后展示。 -->
<!-- 图标由菜单配置中的组件引用动态渲染，新增菜单无需改模板。 -->
      <nav>
        <router-link
          v-for="item in visibleMenuItems"
          :key="item.path"
          :to="item.path"
          class="nav-link"
          :class="{ active: route.path.startsWith(item.path) }"
        >
          <component :is="item.icon" class="ic" />
          <span>{{ item.title }}</span>
          <span v-if="item.path === '/messages'" class="badge-dot"></span>
        </router-link>
      </nav>
<!-- flex 占位：把侧栏底部信息推到可视区域最下方。 -->
      <div class="spacer"></div>
<!-- 侧栏底部：当前登录人首字母头像、用户名、角色文案与退出按钮。 -->
<!-- 用户名缺失时回退为「未登录」，避免模板出现空字符串。 -->
<!-- 角色文案只做展示：SUPER_ADMIN 显示系统管理员，其余显示社区工作人员。 -->
<!-- 首字母取用户名第一个字符并转大写；没有用户名时用 A 兜底。 -->
      <div class="side-foot">
        <div class="who">
          <span class="ava">{{ (auth.username || 'A').slice(0, 1).toUpperCase() }}</span>
          <div>
            <b>{{ auth.username || '未登录' }}</b>
            <span>{{ auth.isSuperAdmin ? '系统管理员' : '社区工作人员' }}</span>
          </div>
        </div>
<!-- 退出按钮：视觉上是导航项，实际调用与顶栏相同的 handleLogout。 -->
        <button class="nav-link" style="width: 100%; border: none; background: none; cursor: pointer; margin-top: 6px" @click="handleLogout">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="M16 17l5-5-5-5M21 12H9" /></svg>
          <span>退出登录</span>
        </button>
      </div>
    </aside>
<!-- 主区域：顶栏 + 路由内容。左侧栏宽度固定，主区域占满剩余空间。 -->
    <div class="admin-main">
<!-- 顶栏：面包屑标题、命令面板入口、音效/主题开关、当前用户信息。 -->
<!-- 面包屑读取 route.meta.title，由路由配置维护，不在页面组件里硬编码。 -->
<!-- data-cmdk 是命令面板的触发标记，点击行为由 fx 模块统一委托。 -->
<!-- 音效与主题按钮保留空内容，图标由 bindSoundToggle / bindThemeToggle 注入。 -->
<!-- 用户信息只做展示，不提供切换账号入口；切换账号需退出后重新登录。 -->
      <header class="topbar">
        <div class="crumb">{{ route.meta.title ?? '' }}</div>
        <div class="grow"></div>
<!-- 命令面板按钮：Ctrl+K 或点击均可打开，条目在 onMounted 中动态注册。 -->
<!-- 使用 aria-label 保证图标按钮对读屏用户可理解。 -->
        <button class="icon-btn" data-cmdk title="命令面板 (Ctrl+K)" aria-label="命令面板">
          <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M18 3a3 3 0 0 0-3 3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3" /></svg>
        </button>
<!-- 音效开关：id 供 bindSoundToggle 查找，按钮内部图标由 fx 模块渲染。 -->
<!-- 布局层不保存开关状态，刷新后由 fx 模块从持久化配置恢复。 -->
        <button class="icon-btn" id="soundToggle" aria-label="音效开关"></button>
        <button class="icon-btn" id="themeToggle" aria-label="主题切换"></button>
<!-- 当前用户胶囊：首字母头像 + 用户名，仅作身份提示。 -->
        <div class="user-chip">
          <span class="ava" style="background: linear-gradient(135deg, #d89b43, #c07e28); color: #fff">
            {{ (auth.username || 'A').slice(0, 1).toUpperCase() }}
          </span>
          <b>{{ auth.username || '工作人员' }}</b>
        </div>
      </header>
<!-- 业务页面出口：租赁、报修、工具、知识库、账号、消息等页面在此渲染。 -->
<!-- 页面切换不会重建布局，因此登录态、主题和命令面板保持连续。 -->
<!-- 各页面自行负责数据加载与权限兜底，布局不重复请求业务接口。 -->
      <div class="admin-content">
        <router-view />
      </div>
    </div>
  </div>
<!-- 模板维护提示：本文件只负责结构与导航，不承载任何业务表格或表单。 -->
<!-- 新增管理页面时，需要同步修改三处：菜单配置、路由表、后端权限矩阵。 -->
<!-- 菜单项的 roles 字段只是前端可见性控制，不能替代后端鉴权。 -->
<!-- 当前布局假定用户已登录；未登录访问会由路由守卫或接口拦截器处理。 -->
<!-- 侧边栏与顶栏共用 auth store，因此角色变更后无需刷新页面即可更新。 -->
<!-- 若未来需要多级菜单，可把 menuItems 改为树结构；当前单级结构更易维护。 -->
<!-- 移动端折叠逻辑由全局样式控制，模板不增加额外的开关状态。 -->
<!-- 品牌区只展示静态标识，不链接首页，避免误触离开当前业务上下文。 -->
<!-- 顶栏标题来自 route.meta.title；新增页面时务必补充该字段。 -->
<!-- 命令面板的菜单项与侧边栏保持同源，避免两处权限配置不一致。 -->
<!-- 音效/主题按钮不在这里维护图标，避免与 fx 模块的渲染逻辑重复。 -->
<!-- 退出按钮在侧栏和命令面板复用同一函数，保证确认文案与清理行为一致。 -->
<!-- 用户首字母头像仅作视觉识别，不请求头像接口，也不展示敏感信息。 -->
<!-- 布局组件不监听窗口尺寸；响应式完全交给 CSS 断点处理。 -->
</template>
