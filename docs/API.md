# Tool Share 接口文档

> 版本：v0.1（空实现阶段）· 后端端口 `9090` · 所有接口前缀 `/api`
>
> 前端开发环境经 vite 代理 `/api → http://localhost:9090`，路径全链路一致。

## 通用约定

### 统一响应

```json
// Result<T>
{ "code": "A200", "message": "Success", "data": null }

// 分页 Result<PageResult<T>>
{ "code": "A200", "message": "Success", "data": { "total": 10, "records": [], "page": 1, "size": 10 } }
```

错误码见 `common/enums/ResultCode`：`A200` 成功；`C4xx` 客户端错误（`C401` 未登录、`C403` 无权限、`C409` 冲突如时段已被预约）；`S500` 服务端错误。

### 认证

- 请求头 `Authorization: <token>`（sa-token，登录后颁发，30 天有效）。
- 当前为空实现阶段，后端暂未启用拦截器；下表"角色"列为最终要求：
  `公开` 无需登录 · `登录` 任意角色 · `居民` / `工作人员` / `管理员` 需对应角色。

---

## 1. 用户模块 `/api/user`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| POST | /api/user/login | 登录，返回 token | 公开 | body: `{username, password}` | `{token, userId, realName, role}` |
| POST | /api/user/register | 居民注册 | 公开 | body: `{username, password, realName, phone, idCard}` | null |
| GET | /api/user/me | 当前登录用户信息 | 登录 | - | `{id, username, realName, phone, role}` |
| GET | /api/user/page | 用户分页 | 工作人员 | query: `page, size, realName?, role?` | PageResult |
| PUT | /api/user/{id}/role | 修改用户角色 | 管理员 | body: `{role}` | null |
| PUT | /api/user/{id}/status | 启用/停用用户 | 工作人员 | body: `{status}` | null |

## 2. 工具信息模块 `/api/tool`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| GET | /api/tool/page | 分页筛选（名称/型号/类别/状态/存放区域；工作人员端可按租赁次数、最近维修时间排序） | 居民/工作人员 | query: `page, size, keyword?, category?, status?, storageLocation?, orderBy?` | PageResult |
| GET | /api/tool/{id} | 工具详情（含使用说明、图片） | 居民/工作人员 | - | `ToolVO` |
| POST | /api/tool | 创建工具（编号按 SQ-G-XX-YYYY 自动生成，见附录录入单字段） | 工作人员 | body: `{name, modelSpec, category, damageLevel, deposit, storageLocation, usageInstruction, images, purchaseDate?, supplierInfo?}` | `{id, toolCode}` |
| PUT | /api/tool/{id} | 更新工具信息 | 工作人员 | body 同创建 | null |
| PUT | /api/tool/{id}/status | 更新工具状态（自动记录状态日志） | 工作人员 | body: `{status, remark?}` | null |
| PUT | /api/tool/{id}/audit | 录入审核（通过/需修改） | 管理员 | body: `{auditStatus, auditOpinion?}` | null |
| GET | /api/tool/{id}/status-logs | 工具状态变更日志 | 工作人员 | - | List |

## 3. 租赁模块 `/api/rental`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| GET | /api/rental/available-times | 查询工具可预约时段 | 居民 | query: `toolId, date` | List<时段> |
| POST | /api/rental | 创建预约（实时校验时段冲突，冲突返回 C409 并推荐最近可约时段） | 居民 | body: `{toolId, startTime, endTime, pickupName}` | `{id, orderNo}` |
| POST | /api/rental/{id}/pay | 押金缴纳（支付沙箱） | 居民 | - | null |
| POST | /api/rental/{id}/cancel | 取消预约（取件前 24 小时），记录押金退还状态 | 居民 | - | null |
| POST | /api/rental/{id}/pickup | 取件登记（可代取） | 工作人员 | body: `{pickupName?}` | null |
| POST | /api/rental/{id}/return | 归还验收（重估工具损坏程度、计逾期费用、退押金） | 工作人员 | body: `{acceptResult, overdueFee?, depositRefundAmount}` | null |
| GET | /api/rental/page | 租赁记录分页（居民=本人；工作人员可按租赁人姓名/工具编号/验收结果/逾期状态筛选） | 居民/工作人员 | query: `page, size, userName?, toolCode?, acceptResult?, overdue?` | PageResult |
| GET | /api/rental/{id} | 租赁单详情 | 居民(本人)/工作人员 | - | `RentalOrderVO` |
| GET | /api/rental/export | 导出租赁记录 Excel | 工作人员 | query 同分页 | Excel 文件流 |

## 4. 损坏报修模块 `/api/repair`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| POST | /api/repair | 提交报修申请（编号 SQ-BX-YYYYMMDD-XXXX） | 居民 | body: `{rentalOrderId, toolId, damagePart, damageDesc, images?}` | `{id, repairNo}` |
| GET | /api/repair/page | 报修单分页（居民=本人；工作人员全量） | 居民/工作人员 | query: `page, size, toolCode?, repairStatus?` | PageResult |
| GET | /api/repair/{id} | 报修单详情（含维修进度列表） | 居民(本人)/工作人员 | - | `RepairOrderVO`（含 `progressList`） |
| POST | /api/repair/{id}/verify | 现场核验（确认/疑似/误报），确认后工具转"维修中" | 工作人员 | body: `{verifyResult, remark?}` | null |
| POST | /api/repair/{id}/progress | 更新维修进度（负责人/预计时长/进度说明） | 工作人员 | body: `{status, description?}` | null |
| POST | /api/repair/{id}/accept | 维修验收，更新工具状态并重新上架 | 工作人员 | body: `{acceptResult, toolStatus, remark?}` | null |

## 5. 消息提醒模块 `/api/notify`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| GET | /api/notify/page | 我的消息分档（类型/已读筛选） | 登录 | query: `page, size, type?, readStatus?` | PageResult |
| PUT | /api/notify/{id}/read | 标记已读 | 登录 | - | null |
| PUT | /api/notify/read-all | 全部标记已读 | 登录 | - | null |
| GET | /api/notify/config | 查询默认提醒方式配置 | 工作人员 | - | `{channel}` |
| PUT | /api/notify/config | 修改默认提醒方式 | 工作人员 | body: `{channel}` | null |

## 6. AI 问答模块 `/api/ai`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| POST | /api/ai/documents | 上传知识库文档（Tika 解析后入 Qdrant） | 管理员 | multipart: `file` | `KbDocument`（新记录） |
| GET | /api/ai/documents | 知识库文档分页 | 工作人员 | query: `page, size` | PageResult |
| DELETE | /api/ai/documents/{id} | 删除文档（同步删 Qdrant 向量） | 管理员 | - | null |
| POST | /api/ai/chat | RAG 问答，返回答案与知识库来源片段 | 居民/工作人员 | body: `{question}` | `{answer, sources: [{content, documentName}]}` |
| POST | /api/ai/chat/stream | RAG 流式问答（SSE，`text/event-stream`） | 居民/工作人员 | body: `{question}` | 帧序列：`sources`（JSON 数组，首帧）→ `delta`（JSON 字符串，多帧增量）→ `done`（`[DONE]`）；流中途异常发 `error`（JSON 字符串） |

## 7. 通用文件模块 `/api/file`

| 方法 | 路径 | 说明 | 角色 | 入参 | 出参 data |
|---|---|---|---|---|---|
| POST | /api/file/upload | 通用文件上传（工具图片、报修材料等，入 MinIO，单张 ≤5MB） | 登录 | multipart: `file` | `{url}` |

---

## 附：空实现说明

当前所有接口为**空实现**：Controller 返回 `Result.success(null)`，Service 为空方法体。入参/出参结构的 DTO/VO 将随各接口真实实现逐步补充；文档中结构即实现目标。
