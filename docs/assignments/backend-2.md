# 后端 B —— 损坏报修 + 消息提醒(MQ) + 用户管理 + AI 问答

负责人：＿＿＿　｜　分支：`feature/be-repair-notify`　｜　负责包：`repair/`、`notify/`、`user/`、`ai/`

## 现状

controller / DTO / VO / 校验 / 权限注解已生成，service 空实现。登录注册（user 包的
`login/register/me`）已完成**不要动**。公共设施直接用：`SerialNoGenerator.next("SQ-BX-")`、
`PageQuery.toPage()`、`DesensitizeUtil`。

先读：`docs/API.md` 第 4/5/6 节、`docs/schema.sql`（repair_order / repair_progress / message / kb_document）、`docs/demo.html`（工作端报修处理 + 管理端知识库）。

## 任务清单

### repair 包（6 个接口）

1. `create`：单号 `SerialNoGenerator.next("SQ-BX-")`；校验关联租赁单属于当前登录人
2. `page`：居民只看本人，工作人员全量（toolCode 模糊 + repairStatus）
3. `detail`：RepairOrderVO 聚合——关联带出 orderNo/toolCode/toolName/reporterName（脱敏手机号）、核验人/验收人姓名、`progressList` 按时间正序
4. `verify`：写核验三字段；verifyResult=确认损坏时**跨模块调 `ToolService.updateStatus` 把工具转维修中**（这是与后端 A 的唯一耦合点，接口已存在，别直接碰 tool 包的 mapper）
5. `progress`：追加一条 repair_progress 并同步 repair_order.repair_status；维修完成时**给报修人发系统内消息**（调 NotifyService，见下）
6. `accept`：写验收三字段 + remark；按 toolStatus 参数联动 `ToolService.updateStatus`；发「维修完成」消息

### notify 包（5 个接口 + MQ 消费者）

7. `page / read / readAll`：只操作当前登录人的消息
8. `getConfig / updateConfig`：默认提醒方式**需要新建迁移 `V4__notify_config.sql`**（单行配置表：channel 字段，Flyway 只追加不修改——建表语句先给架构师过目）
9. **MQ 消费者**：消费后端 A 发的三类延迟消息（取件/到期/逾期），写 `message` 表存档；channel 取配置值，系统内消息置已发送，短信渠道记「待发送」即可（无短信网关）。与 A 对齐 `RabbitMqConfig` 的交换机/队列声明，**消费者侧容错**：消息体解析失败记日志不抛出（避免死循环重投）

### user 包（3 个接口）

10. `page`：分页 + realName 模糊 + role 筛选 → UserVO（手机号脱敏）
11. `updateRole`：改角色（注意不能把自己降级，校验一下）
12. `updateStatus`：启用/停用（登录处校验：停用用户拒绝登录——顺手补在 login 里）

### ai 包（4 个接口）—— RAG 链路

13. `uploadDocument`：文件存 MinIO（`S3Client` 由后端 A 在 config 建立，直接注入使用）→ Tika `DocumentReader` 读原文 → `TokenTextSegmenter` 切分 → 写入 Qdrant（metadata 带 `documentId` 和 `fileName`）→ 回填 chunk_count、status=完成；失败置 status=失败
14. `documents`：kb_document 分页
15. `deleteDocument`：删 MinIO 对象 + **按 metadata.documentId 过滤删除 Qdrant 向量** + 删记录
16. `chat`：`ChatClient` + `QuestionAnswerAdvisor`（VectorStore 检索）；ChatVO.sources 取检索片段的 content + metadata 里的 fileName；模型配置已在 `application.yml`（DeepSeek），API Key 由使用者放 `application-local.yml`

## 与后端 A 的边界

- 报修/验收联动工具状态**只走 `ToolService` 接口**，不 import tool 包内部类
- MQ 消息体/交换机由 A 的 `RabbitMqConfig` 定义，你只写消费者；字段有变更需双方同步
- MinIO 的 `S3Client` Bean 归 A 建，你只使用

## 完成标准

- [ ] 报修全流程走通：报修 → 核验（工具转维修中）→ 进度 → 验收（工具按参数回状态），每步发对应消息且 message 表有存档
- [ ] 上传一份 PDF → kb_document 显示片段数 → `/ai/chat` 提问能命中该文档来源
- [ ] `V4` 迁移在全新数据库可重放；`mvnw test` 全绿
