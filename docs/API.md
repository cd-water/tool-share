# API 对接文档

> 依据：`docs/技术方案.md` §3.4 ｜ 本文是前后端对接契约，实现时后端按此落地、前端按此封装。
> 约定：响应仅描述信封中的 `data` 部分；未标注角色的接口均需登录。

## 0. 全局约定

- **Base URL**：`http://localhost:8800/api`（前端 dev 经 Vite 代理 `/api`）。
- **认证**：登录后拿 `token`，后续请求带请求头 `Authorization: <token>`（Sa-Token，30 天有效）。
- **信封**：所有响应为 `{"code": "A200", "message": "Success", "data": ...}`；`code !== 'A200'` 即失败，前端统一 toast `message`。
- **错误码**：`C400` 参数错误 ｜ `C401` 未登录/token 失效（前端跳登录）｜ `C403` 无权限 ｜ `C404` 资源不存在 ｜ `C409` 业务冲突（时段被抢约、24h 内取消等）｜ `S500` 服务器错误（含 AI 未配置等降级提示）。
- **分页请求**：`page`（默认 1）、`size`（默认 10）；**分页响应**：`{"total": 12, "page": 1, "size": 10, "records": [...]}`。
- **格式**：日期时间 `yyyy-MM-dd HH:mm:ss`，日期 `yyyy-MM-dd`（后端 Jackson 全局配置）；金额为两位小数的数字；ID 均为数字。
- **脱敏**：所有接口返回的 `phone` / `idCard` 已脱敏（如 `138****0004`、`310101********0011`），前端不处理明文。

示例：

```bash
curl -X POST http://localhost:8800/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"chenxm","password":"Aa123456"}'
curl http://localhost:8800/api/tool/tools -H 'Authorization: <token>'
```

## 1. 枚举字典（前端 StatusTag 映射依据）

| 枚举 | 取值 |
|---|---|
| 角色 role | `RESIDENT` 居民 / `STAFF` 工作人员 / `ADMIN` 管理员 |
| 用户 status | `ACTIVE` / `DISABLED` |
| 损坏程度 damageLevel | `NONE` 全新 / `LIGHT` 轻微 / `MEDIUM` 中度 / `SEVERE` 严重 |
| 工具状态 status | `AVAILABLE` / `RESERVED` / `IN_USE` / `REPAIRING` / `DISCARDED` |
| 工具审核 auditStatus | `PENDING` 待审核 / `APPROVED` 已通过 / `REJECTED` 需修改 |
| 租赁状态 | `PENDING_PAYMENT` 待支付 / `RESERVED` 已预约 / `PICKED_UP` 使用中 / `RETURNED` 已归还 / `CANCELLED` 已取消 / `OVERDUE` 逾期中 |
| 押金状态 depositStatus | `UNPAID` / `PAID` / `REFUNDING` / `REFUNDED` |
| 归还验收 acceptResult | `NORMAL` 完好 / `MINOR_DAMAGED` 轻微损坏（无需赔偿） / `SEVERE_DAMAGED` 严重损坏（需赔偿） |
| 报修状态 | `SUBMITTED` 已提交 / `CHECKING` 疑似待检测 / `CONFIRMED` 确认损坏 / `WAITING_PARTS` 待配件 / `REPAIRING` 维修中 / `FINISHED` 维修完成 / `ACCEPTED` 已验收 / `REJECTED` 已驳回 |
| 修后验收 acceptResult | `PASS` 合格 / `FAIL` 不合格（二次维修或报废） / `PARTIAL` 部分合格（限用） |
| 提醒类型 type | `PICKUP` 取件 / `DUE_SOON` 到期 / `OVERDUE` 逾期 / `REPAIR_DONE` 维修完成 |
| 提醒渠道 channel | `IN_APP` 站内信 / `SMS` 短信；发送状态 `PENDING` / `SENT` / `FAILED` |
| 知识库 docType | `MANUAL` 工具手册 / `WARNING` 安全警示 / `RULE` 租赁规则；解析状态 `PENDING` / `PROCESSING` / `READY` / `FAILED` |
| 支付 bizType | `DEPOSIT` 押金 / `REPAIR_FEE` 维修费 / `REFUND` 退款；状态 `PENDING` / `SUCCESS` / `FAILED` / `REFUNDED` |

## 2. 认证与用户

### POST /api/auth/login（匿名）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| username | string | 是 | |
| password | string | 是 | |

```json
// data
{ "token": "b3f1c2…uuid", "user": { "id": 4, "username": "chenxm", "realName": "陈晓明", "role": "RESIDENT" } }
```

错误：`C400` 用户名或密码错误 / 账号已停用。

### POST /api/auth/logout → `data: null`

### GET /api/auth/me

```json
{ "id": 4, "username": "chenxm", "realName": "陈晓明", "phone": "138****0004", "role": "RESIDENT", "status": "ACTIVE" }
```

### GET /api/system/users　`ADMIN`　分页

query：`role?`、`keyword?`（用户名/姓名模糊）

```json
{ "total": 6, "page": 1, "size": 10, "records": [ { "id": 4, "username": "chenxm", "realName": "陈晓明", "phone": "138****0004", "role": "RESIDENT", "status": "ACTIVE", "createTime": "2026-09-01 10:00:00" } ] }
```

### PUT /api/system/users/{id}/status　`ADMIN`

body：`{ "status": "ACTIVE" | "DISABLED" }` → `data: null`。停用后该用户 token 立即失效。

### GET /api/system/configs　`ADMIN`

```json
[ { "configKey": "overdue_fee_per_day", "configValue": "5.00", "remark": "逾期费率（元/天）" } ]
```

### PUT /api/system/configs/{key}　`ADMIN`　body：`{ "configValue": "5.00" }` → `data: null`

## 3. 工具

### GET /api/tool/categories（登录）

```json
[ { "id": 1, "code": "REPAIR", "name": "维修" } ]
```

### GET /api/tool/tools　分页

query：`keyword?`（名称/型号/编码模糊）、`categoryId?`、`damageLevel?`、`status?`、`storageArea?`（员工端用）

```json
{ "total": 10, "page": 1, "size": 10, "records": [ {
  "id": 1, "toolCode": "SQ-G-REPAIR-0001", "name": "电钻", "model": "博世 GSB 600",
  "categoryId": 1, "categoryName": "维修", "coverImage": "http://localhost:10000/tool-share/tool/a.jpg",
  "damageLevel": "NONE", "depositAmount": 100.00, "status": "AVAILABLE",
  "rentalCount": 12, "lastRepairTime": null, "auditStatus": "APPROVED" } ] }
```

说明：居民端仅见 `auditStatus=APPROVED` 且状态非 `DISCARDED/REPAIRING` 的工具（服务端过滤）。

### GET /api/tool/tools/{id}

```json
{ "id": 1, "toolCode": "SQ-G-REPAIR-0001", "name": "电钻", "model": "博世 GSB 600",
  "categoryId": 1, "categoryName": "维修", "description": "1.操作前检查电源线…",
  "damageLevel": "NONE", "depositAmount": 100.00, "storageArea": "A区-1号架",
  "status": "AVAILABLE", "rentalCount": 12, "lastRepairTime": null,
  "auditStatus": "APPROVED", "auditRemark": null,
  "images": [ { "id": 1, "url": "http://…/main.jpg", "sort": 0 } ] }
```

`purchaseDate` / `supplier` 仅 `STAFF/ADMIN` 返回（附录·附加管理信息，含供应商电话，不对居民暴露）。

### GET /api/tool/tools/{id}/availability　query：`date=2026-10-05`

```json
{ "date": "2026-10-05", "busy": [ { "start": "2026-10-05 09:00:00", "end": "2026-10-05 12:00:00" } ] }
```

### POST /api/tool/tools　`STAFF/ADMIN`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| name / model / categoryId / storageArea | - | 是 | |
| description | string | 是 | 使用说明（图文中的图片走 §8 上传后拼 URL） |
| damageLevel | enum | 是 | 初始损坏程度 |
| depositAmount | number | 是 | 押金，>0 |
| purchaseDate / supplier | string | 否 | 采购日期 `yyyy-MM-dd` / 供应商信息 |

```json
// data
{ "id": 11, "toolCode": "SQ-G-REPAIR-0011" }   // auditStatus 置为 PENDING
```

### POST /api/tool/tools/{id}/audit　`ADMIN`

body：`{ "result": "APPROVED" | "REJECTED", "auditRemark": "需补充操作图" }` → `data: null`。`REJECTED` 时 `auditRemark` 必填；通过后工具对居民可见。

### POST /api/tool/tools/{id}/images　`STAFF/ADMIN`

body：`{ "urls": ["http://…"] }`（≤5 张，URL 来自 §8 上传）→ `data: [{ "id": 12, "url": "…", "sort": 0 }]`
删除单张：DELETE /api/tool/images/{id}　`STAFF/ADMIN` → `data: null`

### PUT /api/tool/tools/{id}/status　`STAFF/ADMIN`

body：`{ "status": "REPAIRING", "reason": "居民报修核验属实" }` → `data: null`。写状态日志；`DISCARDED` 仅 `ADMIN` 可设。

## 4. 租赁

### POST /api/rental/orders　`RESIDENT`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| toolId | number | 是 | |
| startTime / endTime | datetime | 是 | 30 分钟粒度，end > start，且不早于当前 |
| pickupPerson | string | 否 | 默认本人 |

```json
// data（成功）
{ "id": 10, "orderCode": "SQ-ZY-20261002-0001", "depositAmount": 100.00, "status": "PENDING_PAYMENT" }
```

错误：`C409` 时段冲突（message 提示）。冲突后调下方推荐接口。

### GET /api/rental/orders/suggestion　`RESIDENT`　query：`toolId`、`startTime`、`endTime`

```json
{ "suggested": true, "startTime": "2026-10-05 13:00:00", "endTime": "2026-10-05 18:00:00" }
// 或
{ "suggested": false }   // 当日无可用时段
```

### POST /api/rental/orders/{id}/pay　`RESIDENT`（本人）　模拟支付押金

```json
{ "payCode": "SQ-PAY-20261002-0001", "amount": 100.00, "payTime": "2026-10-02 15:30:00" }
```

支付成功后订单 `PAID`/`RESERVED`，并触发取件/到期/逾期三类延迟提醒。

### POST /api/rental/orders/{id}/cancel　`RESIDENT`（本人）→ `data: null`

错误：`C409` 距取件不足 24 小时，不可取消。取消成功自动退押金（`REFUNDING → REFUNDED`）。

### POST /api/rental/orders/{id}/pickup　`STAFF`　body：`{ "pickupPerson": "王建国" }` → `data: null`

### POST /api/rental/orders/{id}/return　`STAFF`　归还验收 + 结算

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| acceptResult | enum | 是 | `NORMAL / MINOR_DAMAGED / SEVERE_DAMAGED` |
| damageLevel | enum | 是 | 重估后的损坏程度（写回 tool_info） |
| compensationFee | number | 否 | 赔偿金额；`SEVERE_DAMAGED` 时必填 >0 |

```json
// data：结算结果（退款明细）
{ "overdueFee": 15.00, "compensationFee": 0.00, "refundAmount": 85.00 }
```

规则：`overdueFee = ⌈逾期小时/24⌉ × 逾期费率`，上限=押金；`refundAmount = 押金 − overdueFee − compensationFee`（下限 0）。

### GET /api/rental/orders/my　`RESIDENT`　分页　query：`status?`

### GET /api/rental/orders　`STAFF/ADMIN`　分页

query：`userName?`、`toolCode?`、`acceptResult?`、`overdue=true?`（仅逾期）、`status?`

```json
// records 元素（居民版无 userName/userPhone）
{ "id": 5, "orderCode": "SQ-ZY-20260922-0005", "toolId": 2, "toolCode": "SQ-G-REPAIR-0002",
  "toolName": "圆锯", "userName": "赵磊", "userPhone": "138****0006",
  "startTime": "…", "endTime": "…", "pickupPerson": "赵磊",
  "depositAmount": 150.00, "depositStatus": "REFUNDED", "status": "RETURNED",
  "pickupTime": "…", "returnTime": "…", "acceptResult": "NORMAL",
  "overdueFee": 15.00, "compensationFee": 0.00, "cancelReason": null,
  "createTime": "…" }
```

### GET /api/rental/orders/export　`STAFF/ADMIN`

query 同上（不含分页）。响应为 `xlsx` 二进制流（`Content-Disposition: attachment`），前端用 `responseType: 'blob'` 接收；表头对齐附录·租赁记录单。

## 5. 报修

### POST /api/repair/orders　`RESIDENT`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| toolId | number | 是 | |
| orderId | number | 否 | 关联租赁单（租赁期间损坏） |
| damagePart / description | string | 是 | 损坏部位 / 描述（附录口径） |
| imageUrls | string[] | 否 | 凭证图/视频，1-3 个（§8 上传所得） |

```json
// data
{ "id": 5, "repairCode": "SQ-BX-20261002-0001" }   // status 置为 SUBMITTED
```

### GET /api/repair/orders/my　`RESIDENT`　分页　query：`status?`

### GET /api/repair/orders　`STAFF/ADMIN`　分页　query：`status?`、`toolCode?`

records 元素：`{ "id": 5, "repairCode": "SQ-BX-…", "toolId": 3, "toolName": "热风枪", "reporterName": "陈晓明", "damagePart": "出风口", "description": "…", "status": "REPAIRING", "createTime": "…" }`

### GET /api/repair/orders/{id}

```json
{ "id": 5, "repairCode": "SQ-BX-20261002-0001", "toolId": 3, "toolName": "热风枪",
  "orderId": 3, "orderCode": "SQ-ZY-20260924-0003", "reporterId": 4,
  "damagePart": "出风口", "description": "开机有异响…",
  "status": "REPAIRING", "handlerName": "张伟", "estimatedDuration": "3 个工作日",
  "repairFee": null, "repairDescription": null, "acceptResult": null,
  "rejectReason": null,
  "images": [ "http://…/outlet.jpg" ],
  "progress": [ { "id": 3, "content": "维修中，已更换出风口部件", "operatorName": "张伟", "createTime": "…" } ] }
```

居民调本人单据；`STAFF/ADMIN` 可查任意单。

### POST /api/repair/orders/{id}/verify　`STAFF`

body：`{ "result": "CONFIRMED" | "CHECKING" | "REJECTED", "rejectReason": "现场核验非损坏" }` → `data: null`
`CONFIRMED` 联动工具转 `REPAIRING`（写状态日志）并向居民发"报修确认通知"；`REJECTED` 必填 rejectReason。

### POST /api/repair/orders/{id}/repair　`STAFF`

body：`{ "handlerId": 2, "estimatedDuration": "3 个工作日" }` → `data: null`（status → `REPAIRING`；配件待料时再传 `{ "status": "WAITING_PARTS" }` 可切回）。

### POST /api/repair/orders/{id}/progress　`STAFF`　body：`{ "content": "配件已到货，完成接口更换" }` → `data: null`

### POST /api/repair/orders/{id}/accept　`STAFF`　修后验收

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| result | enum | 是 | `PASS / FAIL / PARTIAL` |
| repairDescription | string | 是 | 维修说明 |
| repairFee | number | 否 | 维修费台账（非人为损坏填 0 或不填） |
| damageLevel | enum | 是 | 重估损坏程度（写回 tool_info） |

→ `data: null`。联动：`PASS` → 工具 `AVAILABLE` 重新可约；`PARTIAL` → 工具 `AVAILABLE` 但损坏程度按新值；`FAIL` → 维修单回到 `REPAIRING`（或由 `ADMIN` 走工具报废）。向原报修居民与处理员工发"维修完成提醒"。

## 6. 消息

### GET /api/message/records/my　分页　query：`type?`（提醒类型）

```json
{ "total": 2, "page": 1, "size": 10, "records": [ { "id": 1, "type": "PICKUP",
  "title": "取件提醒", "content": "您预约的割草机将于 2 天后可取件…",
  "channel": "IN_APP", "sendStatus": "SENT", "sendTime": "…",
  "bizCode": "SQ-ZY-20261004-0001", "createTime": "…" } ] }
```

### GET /api/message/records　`ADMIN`　同上 + query：`userId?`（全量存档审计）

提醒由系统触发（延迟消息/业务事件），无手动发送接口。

## 7. 知识库与 AI 问答

### POST /api/assistant/documents　`ADMIN`　`multipart/form-data`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| file | file | 是 | pdf / docx / txt |
| docType | enum | 是 | `MANUAL / WARNING / RULE` |
| toolId | number | docType=MANUAL 时必填 | 手册归属工具 |

```json
{ "id": 3, "docName": "割草机手册.pdf", "status": "PENDING" }   // 异步解析，前端轮询列表看状态
```

### GET /api/assistant/documents　`ADMIN`　分页

records 元素：`{ "id": 3, "docName": "…", "docType": "MANUAL", "toolId": 4, "toolName": "割草机", "status": "READY", "chunkCount": 12, "createTime": "…" }`；`FAILED` 可重新上传覆盖。

### POST /api/assistant/chat（登录）

body：`{ "question": "电钻可以钻石材吗？" }`

```json
{ "answer": "不可以。根据《电钻安全操作手册》，禁止用于金属以外的坚硬材质（如石材）……",
  "sources": [ { "docName": "电钻安全操作手册.pdf", "content": "3.禁止用于金属以外的坚硬材质（如石材）" } ] }
```

错误：`S500` message=「AI 服务未配置」（服务端未配置 ai.endpoint 时降级）。

## 8. 通用文件上传

### POST /api/file/upload　`登录`　`multipart/form-data`：`file`、`dir`（`tool` / `repair` / `kb`）

```json
{ "url": "http://localhost:10000/tool-share/tool/8f3a…jpg" }
```

约束：图片 jpg/png ≤5MB；报修凭证视频 mp4 ≤20MB。URL 拿到后按各业务接口的 `urls` / `imageUrls` 字段关联。
