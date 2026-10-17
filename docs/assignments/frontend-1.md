# 前端 A —— 居民端全部页面

负责人：＿＿＿　｜　分支：`feature/fe-resident`　｜　负责视图：`views/tool/`、`views/rental/`、`views/repair/`、`views/notify/`、`views/ai/`

## 现状

- 请求层**全部就绪**：`src/api/*.ts` 已有类型化函数（如 `pageTools`、`createRental`、`chat`），类型在 `src/api/types.ts`——**禁止绕过 api 层直接写 axios**
- 路由、布局（`layouts/BasicLayout.vue`）、登录页已完成，居民导航 5 项已挂好
- 页面全是占位，你的任务：按 demo.html 的居民端流程填成真页面

先读：`docs/demo.html`（居民端 5 个页面的交互与文案）、`docs/API.md` 第 2/3/4/5/6 节、`src/api/` 下对应文件。

## 任务清单（按联调顺序）

1. **`tool/index.vue` 工具广场**：`pageTools` 卡片网格（类别章/编号/状态/押金），搜索 + 类别筛选；点击进详情
2. **`tool/detail.vue` 工具详情**：`getTool` 基础信息 + 使用说明 + `getAvailableTimes` 时段选择 → `createRental` 预约 → 成功后跳「我的租赁」；冲突（C409）展示推荐时段（后端返回的 message）
3. **`rental/index.vue` 我的租赁**：`pageRentals`；状态驱动操作按钮——待支付 → `payRental`，已预约 → `cancelRental`（超 24h 后端会报错，把报错 toast 出来即可）
4. **`repair/index.vue` 损坏报修**：`pageRepairs` 列表 + `getRepair` 进度时间线
5. **`repair/new.vue` 报修表单**：`el-form` 校验（对齐后端注解：损坏部位/描述必填、图片 ≤3 张）；图片走 `uploadFile`（`api/file.ts`）拿 URL 后提交 `createRepair`
6. **`notify/index.vue` 消息中心**：`pageNotify` + `readNotify`/`readAllNotify`，未读红点
7. **`ai/index.vue` AI 问答**：对话气泡 + `chat`，答案下方渲染 `sources` 来源标签；输入框回车发送

## 通用要求

- 加载态：列表页用 `v-loading`，按钮提交用 `:loading`，防重复提交
- 空状态：列表为空显示引导文案（如「还没有租赁记录，去工具广场看看」）
- 响应式：≤760px 单列（参考 demo.html 的移动端处理）
- 分页统一 `el-pagination`，参数 `page/size` 与 `PageQuery` 对齐
- 错误提示已由 `api/request.ts` 拦截器统一 toast，页面里**不要重复弹错误**

## 与前端 B 的边界

- 你只做居民导航下的 7 个页面；`views/admin/` 归前端 B
- 公共组件（如图片上传、状态标签）如果想抽到 `src/components/`，先和 B 打招呼避免撞车
- AI 问答依赖后端 B 的 `/ai/chat`，联调前可先用 demo.html 的固定问答做视觉验收

## 完成标准

- [ ] 居民全流程在真实后端走通：登录 → 浏览 → 预约 → 缴押金 → 取件后报修 → 看到进度与消息
- [ ] 所有 api 函数至少被一个页面真实调用，无占位页面残留
- [ ] `pnpm build`（vue-tsc）零错误
