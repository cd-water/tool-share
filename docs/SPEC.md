# 社区共享工具租赁系统 SPEC

## 1. 目标与范围

面向社区居民与社区管理员的工具租赁管理系统，覆盖工具台账、预约租赁、押金沙箱、归还验收、损坏报修、消息提醒、知识库与 AI 问答。角色简化为两类：`USER`（居民）与 `ADMIN`（工作人员 + 管理员）。

本期交付：
- 一个 Spring Boot 后端，两套独立前端（用户端 React、管理端 Vue）。
- MySQL 持久化、Redis 编号/验证码、MinIO 图片、EasyExcel 导出、RabbitMQ 保持待用。
- Spring AI + Qdrant + Ollama bge-m3 的知识库 RAG；外部 AI 不可用时自动降级为本地知识库关键词检索，保证演示可用。

非目标：真实支付、真实短信、身份证反向解密、三级角色权限、评价/捐赠模块。

## 2. 领域模型

### t_tool 工具
`id, code, name, model, category, damage_level, status, deposit, storage_area, usage_guide, image_url, image_urls, purchase_date, supplier, audit_status, rental_count, last_repair_time, created_by, create_time, update_time`

- `category`: `WX` 维修 / `YY` 园艺 / `QJ` 清洁 / `QT` 其他
- `damage_level`: `NEW` 全新 / `LIGHT` 轻微磨损 / `MEDIUM` 中度磨损 / `SEVERE` 严重损坏
- `status`: `AVAILABLE` 可用 / `RENTED` 已租出 / `MAINTENANCE` 维修中 / `SCRAPPED` 已报废
- 编码规则：`SQ-G-{WX|YY|QJ|QT}-{4位序号}`；按类别分别递增，使用 Redis `tool:seq:<category>`。
- 工具状态变更写入 `t_tool_log`。

### t_rental 租赁单（预约 + 记录合一）
`id, order_no, tool_id, tool_code, tool_name, user_id, renter_name, renter_phone, start_time, end_time, pickup_person, deposit_amount, deposit_status, status, actual_return_time, overdue_fee, damage_fee, refund_amount, damage_result, return_remark, cancel_reason, cancel_time, create_time, update_time`

- `deposit_status`: `UNPAID` / `PAID` / `REFUNDED`
- `status`: `PENDING_PAYMENT` / `RESERVED` / `PICKED_UP` / `RETURNED` / `CANCELLED` / `OVERDUE`
- `damage_result`: `INTACT` / `MINOR` / `SEVERE`
- 单号：`SQ-ZY-yyyyMMdd-{4位流水}`，Redis `rental:seq:<yyyyMMdd>`。
- 冲突校验：同一工具下 `PENDING_PAYMENT/RESERVED/PICKED_UP/OVERDUE` 的时段不可重叠。
- 押金：创建时取工具押金；支付为沙箱模拟；取件前 24 小时外可取消并退款。
- 逾期费：每小时 5 元，向上取整，最高不超过押金。严重损坏默认扣 50 元，可由管理员归还时指定。
- 退还金额 = `max(0, 押金 - 逾期费 - 损坏费)`。

### t_damage_report 报修单
`id, report_no, rental_id, tool_id, tool_code, tool_name, user_id, reporter_name, reporter_phone, damage_part, description, images, status, verifier, verify_time, verify_result, repair_person, repair_estimate, repair_start_time, repair_cost, progress_status, progress_update_time, progress_note, acceptor, accept_time, accept_result, tool_status_after, remark, create_time, update_time`

- `status`: `SUBMITTED` / `VERIFIED` / `REJECTED` / `REPAIRING` / `COMPLETED` / `SCRAPPED`
- `progress_status`: `WAITING_PARTS` / `REPAIRING` / `COMPLETED` / `FAILED`
- `accept_result`: `QUALIFIED` / `UNQUALIFIED` / `PARTIAL`
- 单号：`SQ-BX-yyyyMMdd-{4位流水}`，Redis `damage:seq:<yyyyMMdd>`。
- 核验确认后工具转 `MAINTENANCE`；验收合格恢复 `AVAILABLE`，不合格继续维修，报废转 `SCRAPPED`。

### t_message 消息
`id, receiver_type, receiver_id, type, title, content, channel, read_status, biz_key, create_time`

- `receiver_type`: `USER` / `ADMIN`
- `type`: `RESERVATION` / `PICKUP` / `RENT_DUE` / `OVERDUE` / `REPAIR` / `SYSTEM`
- `channel`: `SYSTEM` / `SMS`（短信为 MOCK，仅落库）
- `biz_key` 唯一，用于定时任务去重。

### t_tool_log 工具状态日志
`id, tool_id, action, detail, operator_id, create_time`

### t_knowledge_doc 知识库文档
`id, title, file_name, content, indexed, create_time, update_time`

- `indexed`：是否已建立向量索引；启动时自动为未索引文档补建，外部 AI 服务不可用时保持 0 并在下次启动重试。

### t_user 扩展
新增 `real_name, id_card`；身份证以 `enc:` 前缀 AES 密文保存，展示时脱敏。

## 3. 后端接口

统一返回 `Result<T> = {code,message,data}`；分页返回 `{records,total,page,size}`。用户端鉴权 `/api/user/**`，管理端 `/api/admin/**`；文件读取 `/api/files/**` 公开。

### 用户端
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/user/profile` | 个人资料（手机号/身份证脱敏） |
| PUT | `/api/user/profile` | 更新昵称、头像、真实姓名、身份证 |
| GET | `/api/user/tools` | 工具列表，参数 `keyword,category,status,page,size` |
| GET | `/api/user/tools/{id}` | 工具详情 + 当前可租状态 |
| GET | `/api/user/tools/{id}/availability?startTime&endTime` | 时段可用性 |
| POST | `/api/user/rentals` | 创建预约，body `{toolId,startTime,endTime,pickupPerson}` |
| POST | `/api/user/rentals/{id}/pay` | 沙箱支付押金 |
| POST | `/api/user/rentals/{id}/cancel` | 取消预约，body `{reason}` |
| GET | `/api/user/rentals` | 我的租赁，参数 `status,page,size` |
| GET | `/api/user/rentals/{id}` | 租赁详情 |
| GET | `/api/user/damage-reports` | 我的报修 |
| POST | `/api/user/damage-reports` | 提交报修，body `{rentalId,damagePart,description,images}` |
| GET | `/api/user/damage-reports/{id}` | 报修详情 |
| GET | `/api/user/messages?page&size` | 我的消息 |
| GET | `/api/user/messages/unread-count` | 未读数 |
| POST | `/api/user/messages/{id}/read` | 标记已读 |
| POST | `/api/user/messages/read-all` | 全部已读 |
| POST | `/api/user/ai/chat` | AI 问答，body `{question}`，返回 `{answer,sources}` |
| GET | `/api/user/dashboard` | 用户首页统计 |
| POST | `/api/user/upload` | 图片上传（multipart `file`） |

### 管理端
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/dashboard` | 仪表盘统计 |
| GET | `/api/admin/tools` | 工具筛选 `keyword,category,status,storageArea,page,size` |
| POST | `/api/admin/tools` | 新增工具，自动编码 |
| PUT | `/api/admin/tools/{id}` | 编辑工具 |
| PATCH | `/api/admin/tools/{id}/status` | 更新状态 `{status}` |
| GET | `/api/admin/tools/{id}/logs` | 状态日志 |
| GET | `/api/admin/rentals` | 租赁筛选 `keyword,status,overdue,page,size` |
| GET | `/api/admin/rentals/{id}` | 租赁详情 |
| POST | `/api/admin/rentals/{id}/pickup` | 确认取件 |
| POST | `/api/admin/rentals/{id}/return` | 归还验收，body `{damageResult,overdueFee?,damageFee?,remark?}` |
| POST | `/api/admin/rentals/{id}/cancel` | 管理员取消，body `{reason}` |
| GET | `/api/admin/rentals/export` | 导出 Excel（同筛选参数） |
| GET | `/api/admin/damage-reports` | 报修筛选 |
| GET | `/api/admin/damage-reports/{id}` | 报修详情 |
| POST | `/api/admin/damage-reports/{id}/verify` | 核验，body `{verifyResult,repairPerson?,repairEstimate?,remark?}` |
| POST | `/api/admin/damage-reports/{id}/progress` | 更新进度，body `{progressStatus,progressNote,repairCost?,repairEstimate?,repairPerson?}` |
| POST | `/api/admin/damage-reports/{id}/accept` | 维修验收，body `{acceptResult,toolStatusAfter,remark?,repairCost?}` |
| GET | `/api/admin/knowledge` | 知识库列表 |
| POST | `/api/admin/knowledge` | 上传文档（multipart `file,title`） |
| DELETE | `/api/admin/knowledge/{id}` | 删除知识库文档 |
| POST | `/api/admin/ai/chat` | 管理员 AI 问答 |
| GET | `/api/admin/messages?page&size` | 管理员消息 |
| GET | `/api/admin/messages/unread-count` | 管理员未读数 |
| POST | `/api/admin/messages/{id}/read` | 标记已读 |
| POST | `/api/admin/messages/read-all` | 全部已读 |
| POST | `/api/admin/upload` | 图片/文件上传 |
| GET | `/api/files/{key...}` | 公开读取对象存储文件 |

## 4. 业务规则

1. 创建预约要求用户已填写真实姓名与身份证号；无资料时返回 `C400` 并提示先完善资料。
2. 同一工具时段重叠拒绝，返回最近可用时段建议（在错误消息中给出）。
3. 支付后状态 `RESERVED`，发送预约成功消息；取件由管理员确认，工具转 `RENTED`。
4. 归还由管理员录入验收结果；系统计算逾期费、损坏费、退款并发送消息。
5. 定时任务每 10 分钟检查：取件前 2 小时提醒、到期前 24 小时提醒、逾期提醒；通过 `biz_key` 去重。
6. 报修提交后给所有启用管理员发消息；核验/进度/验收给报修用户发消息。
7. 工具新增、编辑、状态变更写日志；归还验收同步损坏程度与最近维修时间。
8. AI 问答优先向量检索 + LLM 生成；任一外部服务异常时降级为数据库关键词检索并返回来源片段。

## 5. 前端页面

### 用户端 `/`
- 工具广场：搜索、分类/状态筛选、工具卡片、详情弹窗、预约表单。
- 我的租赁：状态筛选、支付、取消、查看详情、发起报修。
- 损坏报修：报修列表、进度时间线、提交报修。
- 消息中心：列表、未读标记、全部已读。
- AI 助手：对话、来源引用、快捷问题。
- 个人中心：昵称/头像/真实姓名/身份证维护、身份证脱敏展示。

### 管理端 `/`
- 仪表盘：工具/租赁/报修/用户统计与快捷入口。
- 工具管理：筛选、新增/编辑、状态更新、日志查看、图片上传。
- 租赁管理：筛选、取件、归还验收、取消、Excel 导出。
- 报修管理：核验、进度更新、维修验收。
- 知识库：上传文档、列表、删除。
- 消息中心：管理员通知。

## 6. 技术约束

- 后端 Spring Boot 4.1.1 / Java 21；Jackson 3（`tools.jackson.*`）。
- 继续使用 Sa-Token 双账号体系，不引入 Spring Security 过滤器链。
- 前端 web 也用 React 19 + TanStack Query + zustand；admin 用 Vue 3 + Element Plus + Pinia。
- 新增依赖需与现有依赖不冲突；优先使用已有 AWS S3 SDK、EasyExcel、Hutool、Spring AI。
- 所有 UI 文案、注释、提交信息使用中文。

## 7. 前端接口字段约定

所有列表分页返回 `{records,total,page,size}`。

- `ToolVO`: `id,code,name,model,category,categoryName,damageLevel,damageLevelName,status,statusName,deposit,storageArea,usageGuide,imageUrl,imageUrls[],purchaseDate,supplier,rentalCount,lastRepairTime,createTime,updateTime`
- `RentalVO`: `id,orderNo,toolId,toolCode,toolName,userId,renterName,renterPhone,startTime,endTime,pickupPerson,depositAmount,depositStatus,depositStatusName,status,statusName,actualReturnTime,overdueFee,damageFee,refundAmount,damageResult,damageResultName,returnRemark,cancelReason,cancelTime,createTime,updateTime`
- `DamageReportVO`: `id,reportNo,rentalId,toolId,toolCode,toolName,userId,reporterName,reporterPhone,damagePart,description,images[],status,statusName,verifier,verifyTime,verifyResult,repairPerson,repairEstimate,repairStartTime,repairCost,progressStatus,progressStatusName,progressUpdateTime,progressNote,acceptor,acceptTime,acceptResult,acceptResultName,toolStatusAfter,remark,createTime,updateTime`
- `MessageVO`: `id,type,typeName,title,content,channel,readStatus,createTime`
- `KnowledgeDocVO`: `id,title,fileName,contentPreview,createTime`
- `AiChatVO`: `answer,sources[]{title,snippet}`
- `UserDashboardVO`: `availableToolCount,pendingPaymentCount,rentingCount,unreadCount`
- `AdminDashboardVO`: `toolTotal,availableToolCount,rentedToolCount,maintenanceToolCount,activeRentalCount,overdueRentalCount,pendingDamageCount,userCount,todayRentalCount`
- 上传返回 `{url,name}`；`ToolSaveDTO.imageUrls` 为字符串数组，后端以逗号分隔存储。
- 时间字段为 ISO 字符串；前端发送 `YYYY-MM-DDTHH:mm:ss` 或 `YYYY-MM-DDTHH:mm`。
