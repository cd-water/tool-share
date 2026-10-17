# 后端 A —— 工具信息 + 租赁 + 文件上传

负责人：＿＿＿　｜　分支：`feature/be-tool-rental`　｜　负责包：`tool/`、`rental/`、`file/`

## 现状

controller / DTO / VO / 校验注解 / 权限注解已全部生成，service 是空实现（`return null`）。
公共设施直接用，**不要重复造**：

- 单号：`SerialNoGenerator.next("SQ-ZY-")` → `SQ-ZY-20261017-0001`
- 分页：`query.toPage()` 直转 MyBatis-Plus `Page`
- 脱敏：`DesensitizeUtil.maskPhone(...)`
- 时间填充：`create_time/update_time` 自动填充，不要手动 set

先读：`docs/API.md` 第 2/3/7 节、`docs/schema.sql`、`docs/demo.html`（居民端工具广场/详情/我的租赁 + 工作端工具管理/租赁管理）。

## 任务清单

### tool 包（7 个接口）

1. `page`：分页 + 条件（keyword 模糊 name/model_spec、category、status、storage_location）；`orderBy=rentalCount` 按租赁次数排序、`lastRepairTime` 按最近维修时间排序（子查询/联表实现）
2. `detail`：按 id 查询，不存在抛 `BizException(NOT_FOUND)`
3. `create`：**编号生成**——规则 `SQ-G-{WX|YY|QJ|QT}-YYYY`（按类别 4 位递增，无日期）。`SerialNoGenerator` 是带日期格式的，不适用；参照它在 `tool` 包内写一个基于 Redis INCR 的类别内序号生成（key 如 `serial:tool:WX`），或给 SerialNoGenerator 加重载——二选一，实现前和架构师说一声
4. `update`：更新基础信息（编号不可改）
5. `updateStatus`：更新状态**并同步写一条 `tool_status_log`**（old/new/operator 取当前登录人）
6. `audit`：审核通过/需修改 + 意见
7. `statusLogs`：按工具倒序查日志

### file 包（1 个接口）

8. `upload`：在 `config/` 补一个 `S3Client` Bean（endpoint/accessKey 取 `minio.*` 配置），上传到 `minio.bucket`，返回可访问 URL；限制 5MB、图片类型

### rental 包（9 个接口）—— 本项目最重的模块

9. `availableTimes`：按 1 小时粒度切分当天时段，剔除与已有订单（status ∈ {已预约,已取件}）重叠的
10. `create`：**时段冲突校验**（`a.start < b.end && b.start < a.end`，用 Redisson 锁 `lock:rental:tool:{toolId}` 包住「查冲突 + 落库」）；单号 `SerialNoGenerator.next("SQ-ZY-")`；创建后**发取件提醒延迟消息**（见下方 MQ 契约）
11. `pay`：deposit_status → 已缴（支付沙箱，直接置位）、status → 已预约
12. `cancel`：校验距 start_time ≥ 24h，status → 已取消、记录退押金金额
13. `pickup`：status → 已取件、**联动 tool 状态 → 已借出（写状态日志）**
14. `return`：本模块状态机收口——写 return_time/accept_result、逾期费（封顶押金）、退押金；**联动 tool**：完好 → 可用并按验收重估 damage_level，严重损坏 → 维修中
15. `page`：居民只看本人（`StpUtil.getLoginIdAsLong()`），工作人员全量 + 条件筛选
16. `detail`：RentalOrderVO 聚合（toolName/toolCode/userName，userPhone 走脱敏）
17. `export`：EasyExcel 按筛选导出；**controller 签名从 `Result<Void>` 改为文件流响应**（`HttpServletResponse` + `application/vnd.ms-excel`），这是唯一允许改 controller 的地方

## 与后端 B 的边界（MQ 契约，创建前对齐）

| 消息 | 生产者 | 消费者 | 延迟 |
| --- | --- | --- | --- |
| 取件提醒 | rental.create | notify | 取件时间前 2h（延迟交换机） |
| 到期提醒 | rental.pay（预约生效时） | notify | end_time 前 2h |
| 逾期提醒 | rental.return（有逾期时） | notify | 立即 |

消息体：`{userId, type, bizNo, content}`；交换机/队列声明放 `config/`，类名 `RabbitMqConfig`，与 B 共同评审后再合入。

## 完成标准

- [ ] 上述 service 全部非空，curl 按 demo.html 的居民流程走通：预约 → 缴押金 → 取件 → 归还
- [ ] tool 每次状态变更都有 tool_status_log；编号/单号并发不重（脚本压一下 create）
- [ ] rental 冲突校验至少 1 个集成测试（`@SpringBootTest`，参考 `SerialNoGeneratorTest`）
- [ ] `mvnw test` 全绿，前端 A 联调时接口与 API.md 一致
