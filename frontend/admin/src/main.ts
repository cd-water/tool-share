/**
 * 管理端应用入口。
 *
 * <p>负责创建 Vue 应用、安装 Pinia（含持久化）、路由与 Element Plus，
 * 注入中文语言包后挂载根节点，最后启动全局特效。管理端的鉴权状态
 * 由 Pinia 持久化，刷新页面后仍可恢复登录态。</p>
 */
// Vue 应用工厂。
import { createApp } from 'vue'
// Pinia 状态容器；管理端登录态与主题状态依赖它。
import { createPinia } from 'pinia'
// Pinia 持久化插件：把白名单状态写入 localStorage，刷新后恢复。
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
// Element Plus 组件库；管理端表格、弹窗与表单均使用它。
import ElementPlus from 'element-plus'
// 中文语言包，统一分页、日期与空状态文案。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// Element Plus 基础样式与暗色变量；暗色主题由管理端切换。
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 根组件、路由表与全局特效。
import App from '@/App.vue'
import router from '@/router'
import { initFx } from '@/lib/fx'
// 基础样式与设计系统变量。
import '@/styles/index.css'
import '@/styles/design.css'

// 标记当前应用为管理端，design.css 依据该属性切换石墨工坊配色。
document.body.dataset.app = 'admin'

// 创建 Vue 应用实例；根组件只包含路由出口。
const app = createApp(App)

// Pinia 状态容器；持久化插件让登录态在刷新后保留。
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

// 注册状态、路由与 Element Plus；中文语言包统一表格、分页和日期组件文案。
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 挂载到 index.html 的 #app 节点。
app.mount('#app')

// 初始化管理端交互特效；必须在根节点挂载后执行。
initFx()
