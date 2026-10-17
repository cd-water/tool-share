# 开发分工文档（后端 2 人 + 前端 2 人）

> 架构师已完成：项目骨架、数据库与迁移、接口契约（API.md）、认证鉴权、入参校验、
> 前端请求层与路由骨架。四人从「空实现 → 真实现」开始，不用搭任何基础设施。

| 成员 | 文档 | 负责范围 |
| --- | --- | --- |
| 后端 A | [backend-1.md](backend-1.md) | 工具信息 + 租赁 + 文件上传（tool / rental / file 包） |
| 后端 B | [backend-2.md](backend-2.md) | 损坏报修 + 消息提醒(MQ) + 用户管理 + AI 问答（repair / notify / user / ai 包） |
| 前端 A | [frontend-1.md](frontend-1.md) | 居民端全部页面 |
| 前端 B | [frontend-2.md](frontend-2.md) | 工作端与管理端全部页面 |

## 全员约定

1. **API.md 是唯一契约**：接口路径、入参出参、角色都以它为准；要改契约先找架构师，改完同步前后端。
2. **分支与合并**：在各自 feature 分支开发，完成后**本地合并进 main 并推送**（不发 PR），完整命令见 [GIT.md](../GIT.md)；四人分支为 `feature/be-tool-rental`、`feature/be-repair-notify`、`feature/fe-resident`、`feature/fe-admin`。
3. **包边界**：不改他人负责的包；`common/`、`config/`、数据库迁移的改动先过架构师（Flyway 只追加新版本号）。
4. **联调顺序**：后端接口先于对应前端页面；接口自测通过（curl/单元测试）即在群里同步。
5. **环境**：按 [SETUP.md](../SETUP.md) 准备，`make up` 一键起本地全栈。
