# 前端 B —— 工作端与管理端全部页面

负责人：＿＿＿　｜　分支：`feature/fe-admin`　｜　负责视图：`views/admin/`（6 个占位页 + 你需要新增的工具录入表单页）

## 现状

- 请求层就绪：管理端所需函数已在 `src/api/`（`pageUsers/updateUserRole/updateUserStatus`、`auditTool/updateToolStatus/getToolStatusLogs`、`pickupRental/returnRental/exportRentals`、`verifyRepair/updateRepairProgress/acceptRepair`、`uploadKbDocument/pageKbDocuments/deleteKbDocument`、`uploadFile`）
- 路由已注册且带 `meta.roles`：工作人员页 `[1,2]`、管理员页 `[2]`，导航按登录角色自动出现——**路由和守卫不要动**
- 页面全是占位。参照物：`docs/demo.html` 的工作端（工具管理/租赁管理/报修处理）与管理端（用户管理/工具审核/知识库管理）三屏

## 任务清单（按联调顺序）

1. **`admin/tools.vue` 工具管理**：`pageTools` 表格（编号/名称/损坏程度/押金/状态）+ 状态筛选；行操作「下架」走 `updateToolStatus`；「录入新工具」跳你新增的表单页
2. **新增 `admin/tool-form.vue` 录入/编辑表单**（记得在 `router/index.ts` 加路由，`meta.roles: [1,2]`）：字段对齐 `ToolDTO`（类别/损坏程度用下拉，枚举注释在 types.ts）；图片多张上传走 `uploadFile`；提交 `createTool` 后展示返回的自动编号
3. **`admin/rentals.vue` 租赁管理**：全量 `pageRentals` + 筛选（租赁人/工具编号/逾期）；「取件登记」`pickupRental`、「归还验收」`returnRental`（验收结果/逾期费/实退金额弹窗表单）；「导出 Excel」用 `exportRentals`（已封装 blob 下载）
4. **`admin/repairs.vue` 报修处理**：列表 + 详情抽屉（进度时间线）；「核验」「更新进度」「验收」三个操作分别调 `verifyRepair / updateRepairProgress / acceptRepair`
5. **`admin/users.vue` 用户管理**（管理员）：`pageUsers` + 行内改角色 `updateUserRole` + 启停 `updateUserStatus`
6. **`admin/audit.vue` 工具审核**（管理员）：待审核/需修改列表，「通过」「退回修改（填意见）」走 `auditTool`
7. **`admin/kb.vue` 知识库管理**：上传（`uploadKbDocument`，FormData）+ 列表（解析中/完成状态）+ 删除

## 通用要求

- 表格页统一：`el-table` + `el-pagination` + 顶部筛选栏；参考 demo.html 的列设计
- 危险操作（退回、停用）加 `ElMessageBox.confirm`
- 后端角色注解是最终防线，前端只需按现有路由 `meta.roles` 控制入口，无需重复写权限判断
- 导出/上传不要手写 axios，`api/rental.ts` 与 `api/file.ts` 已封装

## 与前端 A 的边界

- 你只做 `/admin/*` 下的页面 + 工具表单页；居民端 7 页归前端 A
- 若抽出公共表格/上传组件放 `src/components/`，命名先和 A 对齐
- 工具审核页依赖后端 B 没有专门接口——列表复用 `pageTools?status=...`，审核动作是 `auditTool`（管理员权限）

## 完成标准

- [ ] 工作人员全流程走通：录入工具（含图片）→ 取件登记 → 归还验收 → 报修处理三步
- [ ] 管理员全流程走通：审核工具 → 用户改角色/停用 → 上传知识库文档并删除
- [ ] `pnpm build`（vue-tsc）零错误，admin 下无占位残留
