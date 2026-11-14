<!-- AccountsView.vue —— 运营端「管理员账号」页面。 -->
<!-- 页面职责：展示管理员列表、按关键词过滤、查看角色统计并修改角色。 -->
<!-- 数据入口：@/api/admins 的 adminApi，list 获取账号，updateRole 修改角色。 -->
<!-- 权限：该页面仅系统管理员可访问，路由与导航负责限制入口。 -->
<!-- 列表数据：adminApi.list 一次返回全部管理员，本页不做服务端分页。 -->
<!-- 关键词过滤：纯客户端过滤，匹配用户名与角色中文名，不请求后端。 -->
<!-- 角色统计：基于全量 admins 计算总数、系统管理员数与工作人员数。 -->
<!-- 修改角色：弹窗中选择角色后调用 updateRole，成功后重新加载列表。 -->
<!-- 即时生效：角色变更后后端立即按新角色鉴权，无需重新登录。 -->
<!-- 修改自己：若目标账号是当前登录账号，会刷新会话信息； -->
<!--           若新角色不再是系统管理员，则跳转离开仅 SUPER_ADMIN 可访问的页面。 -->
<!-- 已知限制：后端 AdminVO 未暴露 status 字段，也没有禁用接口； -->
<!--           列表中的「启用」徽章是固定展示，不代表真实账号状态。 -->
<!-- 维护提示：新增角色时需同步 @/utils/enums 的 ADMIN_ROLE_LABELS。 -->
<!-- 样式：仅补充统计图标色调，其余复用全局表格、徽章与弹窗样式。 -->
<!-- 以下依次为脚本、模板、样式三部分。 -->
<script setup lang="ts">
/**
 * Vue 组合式 API：
 * computed 用于关键词过滤与角色统计；
 * onMounted 用于首次加载账号列表；
 * ref 用于加载态、列表、关键词、弹窗和表单状态。
 * 本页不需要 reactive，所有状态都可用 ref 表达。
 */
import { computed, onMounted, ref } from 'vue'
/**
 * router：修改自己的角色且不再是 SUPER_ADMIN 时，
 * 跳转到 /dashboard 离开仅系统管理员可访问的页面。
 */
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
/**
 * ElMessage：角色修改成功后给出轻量提示。
 * 错误提示由统一请求层处理，本页不额外弹错。
 */
import { User } from '@element-plus/icons-vue'
/**
 * User 图标：用于顶部角色统计卡片与空状态占位；
 * 两处表达的都是「管理员账号」这一业务对象。
 */
import { adminApi } from '@/api/admins'
/**
 * 管理员接口模块：
 * adminApi.list 获取全部管理员账号；
 * adminApi.updateRole 按账号 id 提交新角色。
 * 列表不分页，因此一次请求即可拿到统计与过滤所需的全部数据。
 */
import type { AdminRole, AdminVO } from '@/api/types'
/**
 * 类型：
 * AdminRole 是角色枚举（SUPER_ADMIN / STAFF）；
 * AdminVO 是后端返回的管理员视图对象，当前包含 id、username、role。
 */
import { useAuthStore } from '@/stores/auth'
/**
 * 认证 store：
 * username 用于 isSelf 判断目标是否为自己；
 * fetchInfo 在修改自己的角色后重新拉取会话信息；
 * isSuperAdmin 用于判断是否必须离开当前页面。
 */
import { ADMIN_ROLE_LABELS, enumLabel, optionList } from '@/utils/enums'
/**
 * 枚举展示工具：
 * ADMIN_ROLE_LABELS：角色值到中文文案的映射；
 * enumLabel：安全读取角色文案；
 * optionList：把标签映射转换成下拉框可用的 { value, label } 列表。
 */

/**
 * 路由与认证实例，供角色修改后的会话刷新与页面跳转使用。
 */
const router = useRouter()
const auth = useAuthStore()

/**
 * 表格加载状态。
 * 为 true 时表格主体渲染骨架行，为 false 时按 filtered 结果渲染。
 * 只在 loadList 请求期间变化。
 */
const loading = ref(false)
/**
 * 全量管理员列表，来源是 adminApi.list。
 * 统计与关键词过滤都基于这份原始数据；
 * 列表不分页，因此它就是后端的全部账号。
 */
const admins = ref<AdminVO[]>([])
/**
 * 搜索关键词。
 * 与输入框双向绑定，变化时只触发 filtered 重新计算，
 * 不会发起网络请求。
 */
const keyword = ref('')

/**
 * 角色下拉选项。
 * 由 ADMIN_ROLE_LABELS 经 optionList 转换而来，
 * 顺序与标签表一致，模板用 v-for 渲染 option。
 */
const roleOptions = optionList(ADMIN_ROLE_LABELS)

/**
 * 加载管理员账号列表。
 *
 * 入参：无。
 * API：adminApi.list，一次返回全部管理员。
 * 副作用：改写 loading 与 admins；
 *        admins 变化会连带触发 filtered 与 stats 重算。
 * 边界：try/finally 保证请求失败时 loading 也能复位；
 *       错误提示交给统一请求层，本函数不重复处理。
 * 调用方：onMounted 首次加载；修改角色成功后再次调用。
 */
async function loadList() {
  loading.value = true
  try {
    admins.value = await adminApi.list()
  } finally {
    loading.value = false
  }
}

/**
 * 关键词过滤后的账号列表。
 *
 * 匹配范围：用户名 + 角色中文名，统一转小写后做包含匹配；
 * 关键词为空或只有空白时直接返回原列表。
 * 完全在客户端执行，不触发接口请求；
 * 结果用于表格渲染与底部「共 N 个账号」文案。
 * 边界：角色文案通过 enumLabel 读取，未知角色不会导致报错。
 */
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return admins.value
  return admins.value.filter((row) =>
    `${row.username}${enumLabel(ADMIN_ROLE_LABELS, row.role)}`.toLowerCase().includes(kw),
  )
})

/**
 * 顶部角色统计卡片数据。
 *
 * 当前包含三项：
 *   管理员总数：admins.length；
 *   系统管理员：role === SUPER_ADMIN 的数量；
 *   工作人员：role === STAFF 的数量。
 * 数据范围是全量 admins，不受关键词过滤影响，
 * 因此搜索时统计仍然展示整个账号池的分布。
 * tone 只用于图标底色，不参与业务判断。
 * 返回值直接供模板 v-for 渲染。
 */
const stats = computed(() => [
  { label: '管理员总数', value: admins.value.length, tone: 'brand' },
  {
    label: '系统管理员',
    value: admins.value.filter((row) => row.role === 'SUPER_ADMIN').length,
    tone: 'ok',
  },
  {
    label: '工作人员',
    value: admins.value.filter((row) => row.role === 'STAFF').length,
    tone: 'info',
  },
])

/**
 * 判断某行是否对应当前登录账号。
 *
 * 入参：row，管理员行。
 * 比较字段：用户名与 auth.username。
 * 用途：在列表中标注「当前登录账号」，
 *       并在修改角色后决定是否刷新会话与离开当前页面。
 */
function isSelf(row: AdminVO): boolean {
  return row.username === auth.username
}

/* ---------- 修改角色 ---------- */
/**
 * 修改角色弹窗是否可见。
 * 打开时由 openRole 写入目标账号与当前角色。
 */
const roleVisible = ref(false)
/**
 * 角色保存请求进行中的状态。
 * 请求期间保存按钮禁用并显示「保存中…」。
 */
const roleSaving = ref(false)
/**
 * 当前正在修改的管理员。
 * 为 null 时弹窗不会真正提交；
 * 打开弹窗时由 openRole 指向被点击的行。
 */
const roleTarget = ref<AdminVO | null>(null)
/**
 * 弹窗中选中的目标角色。
 * 打开时初始化为目标账号的当前角色；
 * 提交时与 roleTarget.role 比较，相同时直接关闭弹窗。
 */
const roleValue = ref<AdminRole>('STAFF')

/**
 * 打开修改角色弹窗。
 *
 * 入参：row，被点击的管理员。
 * 行为：把 row 写入 roleTarget，
 *       用 row.role 初始化 roleValue，
 *       再把 roleVisible 置为 true。
 * 副作用：只改写弹窗相关状态，不请求接口。
 */
function openRole(row: AdminVO) {
  roleTarget.value = row
  roleValue.value = row.role
  roleVisible.value = true
}

/**
 * 提交角色修改。
 *
 * 入参：无，读取 roleTarget 与 roleValue。
 * 前置校验：
 *   - roleTarget 为空时不提交；
 *   - 新角色与旧角色相同则不调用接口，直接关闭弹窗。
 * API：adminApi.updateRole(target.id, roleValue)。
 * 成功后：
 *   1. 提示「已将某账号的角色改为某角色」；
 *   2. 关闭弹窗；
 *   3. 若目标是当前登录账号，调用 auth.fetchInfo 刷新会话；
 *      刷新后若不再是系统管理员，则跳转 /dashboard 并直接返回，
 *      因为本页仅 SUPER_ADMIN 可访问；
 *   4. 其他情况重新加载列表，刷新角色徽章与统计。
 * 副作用：会改写 roleSaving、roleVisible、会话信息，可能发生路由跳转。
 * 边界：roleSaving 在 finally 中复位；接口异常由请求层提示。
 */
async function submitRole() {
  const target = roleTarget.value
  if (!target || target.role === roleValue.value) {
    roleVisible.value = false
    return
  }
  roleSaving.value = true
  try {
    await adminApi.updateRole(target.id, roleValue.value)
    ElMessage.success(`已将 ${target.username} 的角色改为${ADMIN_ROLE_LABELS[roleValue.value]}`)
    roleVisible.value = false
    // 角色变更即时生效；改到自己时同步会话并离开仅系统管理员可访问的页面
    if (isSelf(target)) {
      await auth.fetchInfo()
      if (!auth.isSuperAdmin) {
        await router.push('/dashboard')
        return
      }
    }
    await loadList()
  } finally {
    roleSaving.value = false
  }
}

/**
 * 页面首次挂载时加载管理员列表。
 * 统计与关键词过滤会随 admins 自动计算，无需额外请求。
 */
onMounted(loadList)
</script>

<template>
<!-- 页面根容器，复用全局 .page 布局。 -->
  <div class="page">
<!-- 页头：左侧标题与说明。 -->
<!-- 说明强调角色变更即时生效，且权限范围与后端角色模型一致。 -->
<!-- 本页没有右侧操作按钮，新增账号入口不在当前版本范围内。 -->
    <div class="page-head reveal">
      <div>
        <p class="eyebrow">ACCESS CONTROL</p>
        <h1>管理员账号</h1>
        <p class="sub">管理工作人员与系统管理员角色。角色变更即时生效，权限范围与后端角色模型一致。</p>
      </div>
    </div>

<!-- 角色统计卡片区。 -->
<!-- 使用 auto-fit 网格，窄屏时卡片自动换行。 -->
<!-- 每个卡片由图标、数值、标签三部分组成。 -->
<!-- 图标色调来自每项数据的 tone 字段。 -->
<!-- 统计数据全部来自全量 admins，不受搜索关键词影响。 -->
    <div class="kb-stats reveal" style="grid-template-columns: repeat(auto-fit, minmax(180px, 1fr))">
<!-- 遍历 stats 渲染统计卡片。 -->
<!-- key 使用标签文案，当前三项标签互不重复。 -->
<!-- 数值直接展示 item.value。 -->
      <div class="kb-stat" v-for="item in stats" :key="item.label">
        <span class="ks-ic" :class="item.tone">
          <User class="ic" />
        </span>
        <div>
          <b class="num">{{ item.value }}</b>
          <span>{{ item.label }}</span>
        </div>
      </div>
    </div>

<!-- 筛选栏：搜索框 + 弹性占位 + 账号数量。 -->
<!-- 账号数量显示过滤后的数量，而不是全量数量。 -->
<!-- 搜索完全在前端执行，输入时即时生效。 -->
    <div class="filterbar reveal">
      <div class="f-search">
<!-- 搜索图标：内联 SVG，避免额外图标依赖。 -->
<!-- 图标只做装饰，不参与交互。 -->
        <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
<!-- 搜索输入框：与 keyword 双向绑定。 -->
<!-- placeholder 提示可搜索账号或角色。 -->
<!-- 输入变化会触发 filtered 与底部数量同步更新。 -->
        <input v-model="keyword" class="input" placeholder="搜索账号 / 角色…" />
      </div>
      <span class="grow"></span>
      <span class="tiny">共 {{ filtered.length }} 个账号</span>
    </div>

<!-- 表格外层容器：负责滚动与卡片外观。 -->
<!-- reveal 提供入场动画。 -->
<!-- 表格在窄屏下由 .table-scroll 横向滚动。 -->
    <div class="table-wrap reveal">
      <div class="table-scroll">
<!-- 管理员表格。 -->
<!-- 表头固定四列：账号、角色、状态、操作。 -->
        <table class="tbl">
<!-- 表头行。 -->
          <thead>
            <tr>
<!-- 「账号」列：显示用户名与「当前登录账号」标记。 -->
              <th>账号</th>
<!-- 「角色」列：用徽章展示角色中文名。 -->
              <th>角色</th>
<!-- 「状态」列：当前为固定展示，原因见下方行内注释。 -->
              <th>状态</th>
<!-- 「操作」列：固定宽度，容纳「修改角色」按钮。 -->
              <th style="width: 130px">操作</th>
            </tr>
          </thead>
<!-- 加载态表格体：渲染 3 行骨架。 -->
<!-- 骨架行跨全部 4 列，保持表格结构稳定。 -->
<!-- 加载完成后由 v-else 分支替换为真实数据。 -->
          <tbody v-if="loading">
            <tr v-for="i in 3" :key="`sk-${i}`">
              <td colspan="4"><div class="skel" style="height: 16px"></div></td>
            </tr>
          </tbody>
<!-- 空状态表格体：filtered 为空时展示。 -->
<!-- 既覆盖「没有任何账号」，也覆盖「搜索无匹配」。 -->
<!-- 空状态内容跨全部 4 列居中显示。 -->
          <tbody v-else-if="!filtered.length">
            <tr>
              <td colspan="4">
                <div class="empty">
                  <User class="e-ic" />
                  <h4>暂无管理员账号</h4>
                  <p>当前筛选下没有匹配的账号。</p>
                </div>
              </td>
            </tr>
          </tbody>
<!-- 数据表格体：遍历 filtered 渲染管理员行。 -->
<!-- key 使用管理员 id，保证行复用正确。 -->
<!-- filtered 已应用关键词过滤，模板不再重复判断。 -->
<!-- 每行包含账号、角色、状态与操作四个单元格。 -->
          <tbody v-else>
            <tr v-for="row in filtered" :key="row.id">
<!-- 账号单元格：主文本为用户名。 -->
<!-- 若该行是当前登录账号，额外显示「当前登录账号」副文本。 -->
<!-- 副文本只是提示，不影响角色修改能力。 -->
              <td>
                <div class="cell-main">{{ row.username }}</div>
                <div v-if="isSelf(row)" class="cell-sub">当前登录账号</div>
              </td>
<!-- 角色单元格：SUPER_ADMIN 使用品牌色徽章，其他角色使用中性色。 -->
<!-- 文案通过 enumLabel 读取，未知角色回退原值。 -->
              <td>
                <span class="badge" :class="row.role === 'SUPER_ADMIN' ? 'brand' : 'neutral'">
                  {{ enumLabel(ADMIN_ROLE_LABELS, row.role) }}
                </span>
              </td>
<!-- 状态单元格：当前固定展示为「启用」。 -->
<!-- 这是后端 AdminVO 未暴露 status 且没有禁用接口时的已知限制， -->
<!-- 不能据此判断账号是否真的可登录。 -->
<!-- 行内 HTML 注释进一步记录了后续接口补齐后的修改点。 -->
<!-- 徽章使用成功色与圆点，表达「可用」语义。 -->
              <td>
                <!-- ponytail: 后端 AdminVO 未暴露 status 且无禁用接口，列表中的账号均可登录；接口补充 status 后改为读取真实状态 -->
                <span class="badge ok"><span class="dot"></span>启用</span>
              </td>
<!-- 操作单元格：提供「修改角色」入口。 -->
<!-- 点击调用 openRole(row)，并把当前行作为弹窗目标。 -->
<!-- 当前版本没有删除、禁用等其他账号操作。 -->
              <td>
                <div class="row-actions">
                  <button class="link-btn" @click="openRole(row)">修改角色</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

<!-- 修改角色弹窗：v-if 控制挂载，mask show 控制显示。 -->
<!-- 点击遮罩自身时关闭，点击弹窗内部不会误关。 -->
<!-- 弹窗只负责选择角色，实际提交由 submitRole 完成。 -->
<!-- 保存期间按钮禁用，避免重复提交。 -->
<!-- 关闭后 roleTarget 仍保留最后一行，但下次打开会被覆盖。 -->
    <div v-if="roleVisible" class="mask show" @click.self="roleVisible = false">
      <div class="dlg narrow">
<!-- 弹窗头部：标题与目标账号信息。 -->
<!-- 副标题展示目标用户名与当前角色，便于确认修改对象。 -->
<!-- 目标的 role 通过 enumLabel 转中文，缺失时显示占位符。 -->
<!-- 右上角关闭按钮与底部取消按钮作用相同。 -->
        <div class="dlg-head">
          <div>
            <h3>修改角色</h3>
            <p class="d-sub">{{ roleTarget?.username }} · 当前 {{ roleTarget ? enumLabel(ADMIN_ROLE_LABELS, roleTarget.role) : '—' }}</p>
          </div>
          <button class="dlg-x" @click="roleVisible = false" aria-label="关闭">
            <svg class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>
          </button>
        </div>
<!-- 弹窗主体：角色选择。 -->
<!-- 下拉选项来自 roleOptions，值绑定 roleValue。 -->
<!-- 下方说明解释两种角色的权限边界与即时生效特性。 -->
<!-- 说明文案是运营人员理解角色差异的主要提示。 -->
        <div class="dlg-body">
          <label class="tiny" style="display: block; margin-bottom: 8px">角色</label>
          <select v-model="roleValue" class="select" style="width: 100%; height: 40px">
            <option v-for="item in roleOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
          <p class="tiny" style="margin: 12px 0 0; line-height: 1.7">
            系统管理员可管理工具、知识库与管理员账号；工作人员仅处理租赁、报修与消息。变更后立即生效。
          </p>
        </div>
<!-- 弹窗底部：取消与保存。 -->
<!-- 取消直接关闭弹窗，不修改任何数据。 -->
<!-- 保存调用 submitRole；roleSaving 期间按钮禁用并显示「保存中…」。 -->
        <div class="dlg-foot">
          <button class="btn btn-outline" @click="roleVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="roleSaving" @click="submitRole">
            {{ roleSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<!-- ===== 样式部分 ===== -->
<!-- 样式全部 scoped，只影响本组件。 -->
<!-- 仅覆盖统计图标色调，表格与弹窗复用全局样式。 -->
<!-- 颜色使用全局 CSS 变量，保持与其他页面一致。 -->
<!-- ===================== -->
<style scoped>
/*
 * .ks-ic.ok：系统管理员统计图标的成功色调。
 * 使用全局成功色变量，与页面其他成功状态一致。
 */
.ks-ic.ok {
  background: var(--ok-bg);
  color: var(--ok);
}
/*
 * .ks-ic.info：工作人员统计图标的信息色调。
 * 使用全局信息色变量，与系统管理员图标形成区分。
 * 尺寸、圆角等其余样式沿用全局 .ks-ic。
 */
.ks-ic.info {
  background: var(--info-bg);
  color: var(--info);
}
</style>
