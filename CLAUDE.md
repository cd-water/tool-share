# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本仓库为中文项目（README / PRD / 代码注释均为中文），代码注释、提交信息请用中文。

## 常用命令

### 基础设施（Docker Compose，端口 = 默认端口 + 1000）

```bash
cd infra/docker
cp .env.example .env
docker compose up -d        # PostgreSQL(6432) Redis(7379) RabbitMQ(6672/16672) MinIO(10000/10001)
# 重置数据：schema.sql / seed.sql 仅在数据卷首次初始化时执行
docker compose down -v && docker compose up -d
```

### 后端（Java 21 · Spring Boot 3.5，端口 8800）

```bash
./mvnw -DskipTests package
java -jar toolshare-boot/target/toolshare-boot-0.0.1-SNAPSHOT.jar
./mvnw test                                                   # 全部测试
./mvnw test -pl toolshare-boot -Dtest=ToolShareApplicationTests          # 单个测试类
./mvnw test -pl toolshare-boot -Dtest=ToolShareApplicationTests#方法名    # 单个测试方法
```

也可在 IDEA 直接运行 `toolshare-boot` 的 `ToolShareApplication`。

### 前端（Vue 3 + Vite + pnpm，端口 5800）

```bash
cd frontend
pnpm install
pnpm dev        # /api 代理 → localhost:8800
pnpm build      # vue-tsc 类型检查 + vite 构建
```

验证：前端 <http://localhost:5800>，后端健康检查 <http://localhost:8800>。种子账号密码均为 `Aa123456`（见 README）。

## 架构

单体多模块 Maven，按业务功能划分，依赖关系单向：**业务模块 → toolshare-common → toolshare-boot 聚合**。业务模块之间互不依赖。

- **toolshare-common**：技术底座。全部 MyBatis-Plus 实体集中在 `common/entity`（`type-aliases-package` 只扫这里），外加 `enums` / `exception` / `result` / `config`；持有全量技术依赖（MyBatis-Plus、Sa-Token、Redisson、AMQP、S3 SDK、EasyExcel、Hutool），业务模块因此无需自己引依赖。
- **toolshare-system / -tool / -rental / -repair / -message / -assistant**：业务模块，包结构统一为 `controller / service / mapper / dto`，MyBatis XML 放各自 `resources/mapper/`。
- **toolshare-boot**：唯一启动模块（`ToolShareApplication`），聚合全部业务模块；`application.yml`、全局配置、`GlobalExceptionHandler` 都在这里。
- **父 POM**：只做模块聚合 + `dependencyManagement` 统一三方版本；子模块声明依赖不写版本号。根 POM 已给所有模块提供 lombok（provided）和 spring-boot-starter-test（test）。

### 后端约定

- 统一响应 `Result<T>`，code 形如 `A200` / `C401` / `S500`（见 `ResultCode`）；业务错误抛 `BizException`，由 boot 的 `GlobalExceptionHandler` 统一转成 `Result`，不要在 controller 里 try-catch；分页用 `PageResult`。
- 认证用 Sa-Token：token 走 `Authorization` 请求头，会话存 Redis，前后端分离不走 cookie。
- MyBatis-Plus：主键 `id-type: auto`（数据库自增）、`map-underscore-to-camel-case`。
- S3（MinIO）SDK 无自动装配：配置在 `application.yml` 的 `s3:` 段，需手动写 Config 类构建 `S3Client`。

### 数据库

`infra/db/schema.sql` 与 `seed.sql` 是表结构 / 种子数据的唯一来源（容器首次初始化自动执行）——改表结构必须同步改这两个文件，本地验证需 `down -v` 重建数据卷。

## 关键文档

- `docs/PRD.md`：需求来源，实现功能前先对照
- `docs/diagrams/`：ER 图
- `README.md`：技术栈与启动说明
