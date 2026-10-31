# 实施计划

> 执行方式：主代理完成后端与集成，前端分别由子代理并行实现，最后统一构建验证并提交。

## 全局约束
- 后端端口 8900；MySQL 3806、Redis 6879、MinIO 9500。
- 统一 `Result<T>`，成功码 `A200`；业务异常 `BizException`。
- 用户端 `/api/user/**`，管理端 `/api/admin/**`，文件 `/api/files/**` 公开。
- 数据库只新增 Flyway `V3__business_modules.sql`，不改已应用迁移。
- 先编译后端，再构建两端前端；提交前跑 `./mvnw -q -DskipTests package`、`pnpm build`。

## Task 1：数据库与基础配置
- 新增 V3：扩展 `t_user`；创建工具、租赁、报修、消息、工具日志、知识库表；插入演示工具与知识库文档。
- 新增 MyBatis-Plus 分页插件、定时任务开关、S3 客户端配置、ID 卡加解密服务。
- 验证：启动上下文、Flyway 迁移成功。

## Task 2：工具与租赁后端
- Tool 实体/Mapper/DTO/VO/Service/Controller：增删改查、筛选、状态更新、日志、编码生成。
- Rental 实体/Mapper/DTO/VO/Service/Controller：创建、支付、取消、取件、归还、列表、Excel 导出。
- 定时任务：取件/到期/逾期提醒。
- 验证：`RentalFlowIntegrationTest` 覆盖创建-支付-取件-归还-取消；工具 CRUD/编码测试。

## Task 3：报修、消息、知识库/AI、文件、统计
- 报修核验/进度/验收，联动工具状态与消息。
- 消息列表/未读/已读。
- 文件上传与公开读取（MinIO/S3）。
- 知识库上传/解析/索引；AI 问答向量检索 + LLM + 本地降级。
- 用户/管理员仪表盘统计。
- 验证：`DamageReportIntegrationTest`、`AiFallbackTest`（无外部服务时仍返回来源）。

## Task 4：用户端 React
- 扩展 `api/`、`types`、路由、布局导航。
- 页面：工具广场、我的租赁、报修、消息、AI、个人中心。
- 使用 TanStack Query、zustand、现有 shadcn/base-ui 组件；响应式。
- 验证：`pnpm build`。

## Task 5：管理端 Vue
- 扩展 `api/`、路由、布局菜单。
- 页面：仪表盘、工具管理、租赁管理、报修管理、知识库、消息。
- Element Plus 表格/表单/弹窗/上传/日期选择；Excel 下载。
- 验证：`pnpm build`。

## Task 6：联调与提交
- 启动 Docker Compose，运行后端测试；构建两端。
- 修复类型、接口、迁移问题。
- `git add` 并提交，消息使用中文。
