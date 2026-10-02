# 社区共享工具租赁系统（tool-share）

## 项目介绍

为社区居民提供工具（维修、园艺、清洁等）共享租赁服务，解决"偶尔用、购买贵"的痛点。系统覆盖 **工具录入 → 租赁预约 → 使用跟踪 → 归还验收 → 损坏报修** 全流程，并配套消息提醒与 AI 问答助手（RAG）。

核心能力：

- 工具编码规则（SQ-G-类别-序号）、分类标签、状态机管理，状态变更全程留痕
- 预约时段实时冲突校验（Redisson 分布式锁）、押金/维修费模拟支付、取件前 24 小时取消退押金
- 工作人员多条件筛选租赁记录、导出 Excel
- 损坏报修、维修进度时间线、修后验收重估损坏程度
- 到期 / 逾期 / 取件 / 维修完成四类提醒，站内信 + 短信双通道，全部存档
- 居民 / 社区工作人员 / 系统管理员三级权限；手机号加密存储、敏感数据脱敏

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21 · Spring Boot 3.5.16 · MyBatis-Plus 3.5.17 · Sa-Token 1.46（Redis 会话）· Redisson · Spring AMQP（延迟消息）· AWS S3 SDK（MinIO）· EasyExcel · Hutool |
| 数据 | PostgreSQL 16 + pgvector（向量检索预留）· Redis 7 |
| 前端 | Vue 3 · Vite · TypeScript · Element Plus |
| 基础设施 | Docker Compose：PostgreSQL(6432) · Redis(7379) · RabbitMQ(6672/管理台 16672) · MinIO(API 10000/控制台 10001) |

> 基础设施端口统一 = 默认端口 +1000，避免与本机已有服务冲突。

## 项目结构

单体多模块，按业务功能划分，依赖关系单向（业务模块 → common → boot 聚合）：

```
tool-share
├── docs/                      # PRD、ER 图（docs/diagrams）
├── infra/
│   ├── db/                    # schema.sql / seed.sql（容器首次启动自动执行）
│   ├── docker/                # docker-compose.yml（.env 从 .env.example 复制）
│   └── rabbitmq/              # 延迟消息交换机插件
├── frontend/                  # Vue 3 前端（dev 端口 5800，/api 代理 → 8800）
├── toolshare-common/          # 技术底座：公共类 + 全量基础依赖 + 全部实体类
├── toolshare-system/          # 用户 / 登录认证 / 三级权限
├── toolshare-tool/            # 工具信息 / 分类 / 编码规则 / 状态日志
├── toolshare-rental/          # 预约校验 / 押金 / 租赁记录 / 支付流水
├── toolshare-repair/          # 损坏报修 / 维修进度 / 修后验收
├── toolshare-message/         # 提醒触发 / 站内信·短信 / 提醒存档
├── toolshare-assistant/       # AI 问答助手（RAG 知识库）
├── toolshare-boot/            # 启动模块：application.yml / 全局配置 / 全局异常
└── pom.xml                    # 父 POM：模块聚合 + 三方版本 dependencyManagement
```

业务模块内包结构统一：`controller / service / mapper / dto`，实体集中在 common。

## 项目启动

前置：JDK 21、Docker、Node.js ≥ 20、pnpm

**1. 启动基础设施**

```bash
cd infra/docker
cp .env.example .env
docker compose up -d          # 首次启动自动建表并写入种子数据
# 重置数据：docker compose down -v && docker compose up -d
```

**2. 启动后端（端口 8800）**

```bash
./mvnw -DskipTests package
java -jar toolshare-boot/target/toolshare-boot-0.0.1-SNAPSHOT.jar
# 或 IDEA 直接运行 toolshare-boot 的 ToolShareApplication
```

**3. 启动前端（端口 5800）**

```bash
cd frontend
pnpm install
pnpm dev
```

**4. 验证**

- 前端：<http://localhost:5800>
- 后端健康：<http://localhost:8800>
- 种子账号（密码均为 `Aa123456`）：`admin`(管理员) / `zhangwei`、`lina`(工作人员) / `chenxm`、`liufang`、`zhaolei`(居民)
- RabbitMQ 管理台 <http://localhost:16672> · MinIO 控制台 <http://localhost:10001>（账号见 `infra/docker/.env`）
