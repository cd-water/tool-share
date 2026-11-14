# ops · 部署与运维

本目录集中所有部署、验证与评测入口，避免部署文件散落在仓库根目录。

| 文件 | 用途 |
| --- | --- |
| `docker-compose.yml` | MySQL / Redis / RabbitMQ / MinIO / Qdrant / Ollama 本地编排 |
| `.env.example` | 中间件与应用环境变量模板，复制为 `.env` 使用 |
| `verify.sh` | 一键验证：后端编译/测试、双前端 lint/构建、主流程 E2E |
| `e2e.sh` / `e2e/` | 启动应用与前端 dev server 后运行 Playwright 三条主流程 |
| `ai-eval.sh` | AI 固定评测集，独立于默认测试门禁 |

## 前置要求

- JDK 21、Maven Wrapper（仓库自带 `./mvnw`）
- Docker Engine + Compose v2
- pnpm（两个前端与 E2E）
- Google Chrome（E2E 使用 `channel: "chrome"`）

## 启动与停止中间件

```bash
cp ops/.env.example ops/.env      # 首次
cd ops
docker compose up -d
docker compose ps
docker compose logs -f            # 跟踪日志
docker compose down               # 停止并保留数据卷
docker compose down -v            # 停止并删除数据卷（不可恢复）
```

首次启动时 `ollama-model-init` 会拉取 `bge-m3` 向量模型，耗时取决于网络。未完成时 AI 问答/推荐会自动降级，不影响租赁主流程。

### 端口映射

| 服务 | 宿主端口 | 用途 |
| --- | --- | --- |
| MySQL | `3806` | 业务数据库 |
| Redis | `6879` | 会话、验证码、提醒队列、单号序列 |
| RabbitMQ | `6172` / `16172` | 预留组件，当前代码未接入 |
| MinIO | `9500` / `9501` | S3 API / 控制台 |
| Qdrant | `6833` / `6834` | REST / gRPC（应用使用 gRPC `6834`） |
| Ollama | `11934` | 向量化 |
| 后端 | `8900` | Spring Boot |
| 居民端 | `5901` | Vite dev server |
| 管理端 | `5902` | Vite dev server |

## 启动应用

```bash
# 1. 注入环境变量（ID_CARD_AES_KEY 必填）
set -a; source ops/.env; set +a

# 2a. 开发运行
./mvnw spring-boot:run

# 2b. 或先打包再运行
./mvnw -DskipTests package
java -jar target/tool-share-0.0.1-SNAPSHOT.jar
```

前端：

```bash
cd frontend/web && pnpm install && pnpm dev      # :5901，/api 代理到 :8900
cd frontend/admin && pnpm install && pnpm dev    # :5902，/api 代理到 :8900
```

生产构建为 `pnpm build`，产物在 `frontend/web/dist` 与 `frontend/admin/dist`。仓库不包含生产反向代理配置：需由静态服务器托管 `dist/`，并把 `/api` 代理到 `:8900`；SSE 接口需关闭代理缓冲。

## 环境变量

完整清单与业务开关见根 [README](../README.md#环境变量)。运维必须关注：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `ID_CARD_AES_KEY` | 是 | 16/24/32 字节；缺失或长度错误时后端拒绝启动 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_DATABASE` | 是 | Compose 与 Spring Boot 共用 |
| `REDIS_PASSWORD` | 是 | Compose 与 Spring Boot 共用 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 是 | MinIO 与应用 S3 凭据 |
| `QDRANT_API_KEY` | 是 | 应用访问 Qdrant |
| `DEEPSEEK_API_KEY` | 否 | 留空时 AI 走降级路径，不影响启动 |
| `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` | 否 | 预留组件，当前业务未使用 |

> **密钥稳定性**：`ID_CARD_AES_KEY` 一旦用于生产数据，不能随意更换；更换后既有 `enc:v2:` 身份证密文将无法解密。轮换密钥需要先解密再以新密钥重加密，属于离线迁移操作。

## 数据持久化与备份

Compose 数据卷（项目名 `tool-share`）：

| 卷 | 内容 | 是否必须备份 |
| --- | --- | --- |
| `tool-share_mysql-data` | 用户、工具、租赁单、费用/支付流水、评价、信用分、消息等全部业务数据 | **是** |
| `tool-share_minio-data` | 工具图片、报修照片、知识库文件 | **是** |
| `tool-share_redis-data` | 会话、验证码、提醒 ZSet、单号序列 | 否；丢失后用户重新登录、序列按库内最大值重新校准 |
| `tool-share_qdrant-data` | 知识库与工具向量索引 | 否；可由数据库内容重建 |
| `tool-share_ollama-data` | `bge-m3` 模型 | 否；可重新拉取 |
| `tool-share_rabbitmq-data` | 预留组件数据 | 否 |

### 备份建议

建议在低峰期停止应用（或至少停止写入）后执行，保证 MySQL 与 MinIO 备份处于同一业务时点。以下命令默认在**仓库根目录**执行。

```bash
# MySQL 逻辑备份（单事务，包含存储过程/触发器）
docker exec tool-share-mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' \
  > mysql-backup-$(date +%F).sql

# MinIO 数据卷打包
docker run --rm -v tool-share_minio-data:/data:ro -v "$PWD":/backup alpine \
  tar czf /backup/minio-backup-$(date +%F).tgz -C /data .
```

恢复：

```bash
# 停止应用后端后执行
docker exec -i tool-share-mysql sh -c \
  'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < mysql-backup-YYYY-MM-DD.sql

docker compose -f ops/docker-compose.yml stop minio
docker run --rm -v tool-share_minio-data:/data -v "$PWD":/backup alpine \
  sh -c 'find /data -mindepth 1 -delete && tar xzf /backup/minio-backup-YYYY-MM-DD.tgz -C /data'
docker compose -f ops/docker-compose.yml start minio
```

建议保留最近 7 份日备份与 4 份周备份，并定期在隔离环境演练恢复。备份文件包含个人信息与身份证密文，应按敏感数据保管。

## 日志与验证

- **后端**：默认输出到标准输出（控制台），由部署方式负责收集；仓库未配置文件日志。
- **中间件**：`cd ops && docker compose logs -f <service>`。
- **E2E**：`ops/e2e.sh` 将后端与两个前端日志写入 `$TMPDIR/tool-share-e2e.*`，失败时打印末尾 40 行；Playwright HTML 报告在 `ops/e2e/playwright-report/`。
- **验证**：

```bash
bash ops/verify.sh              # 全量：编译 + 测试 + 前端 lint/构建 + E2E
SKIP_E2E=1 bash ops/verify.sh   # 跳过 E2E
bash ops/ai-eval.sh             # AI 评测（阈值默认 0.8，可用环境变量覆盖）
```

E2E 会占用 `8900/5901/5902`，执行前请停止本地 dev server。`ops/.env` 缺失、端口被占用或系统没有 Chrome 时脚本会给出明确错误。

## 单实例限制

本系统按单实例部署设计（[ADR-0004](../docs/adr/0004-single-instance-scheduling.md)）：

- `@Scheduled` 负责待支付超时取消、取件/到期/逾期提醒、蹲守通知与索引重试。
- Redis ZSet/Hash 保存提醒到点时间与提前量，Redis Set 保存蹲守集合。
- 没有分布式锁或选主机制；启动多个副本会导致重复提醒、重复扫描与竞态。

横向扩容前必须重新设计这些任务的并发语义，或引入分布式调度锁。

## 故障排查

| 现象 | 排查方向 |
| --- | --- |
| 后端启动失败，提示 `security.id-card-key` | `ID_CARD_AES_KEY` 未注入或长度不是 16/24/32 字节 |
| 后端连不上 MySQL/Redis | 中间件未启动或 `ops/.env` 未 `source`；确认 `docker compose ps` |
| 登录一直提示验证码错误 | 查看后端日志中的 `[MOCK SMS]`；本地联调可临时开启 `TOOLSHARE_AUTH_EXPOSE_CAPTCHA=true` |
| 上传失败 | MinIO 是否健康、桶名/凭据是否与 `.env` 一致；桶会自动创建 |
| AI 问答总是兜底文案 | `DEEPSEEK_API_KEY` 为空、DeepSeek 超时/不可达 |
| AI 推荐 `degraded=true` | Qdrant/Ollama 不可用、无向量命中或大模型重排失败，系统已降级关键词检索 |
| `verify.sh` 在 E2E 失败 | 端口被占用、Chrome 缺失、中间件未启动；按脚本打印的临时日志目录查看 `backend.log` / `web.log` / `admin.log` |
| 启动后工具/知识库检索为空 | 等待 Ollama 模型拉取完成，或检查 Qdrant；工具索引会在启动后重建，知识库可重新上传/索引 |

## 升级与数据库迁移

- Flyway 在后端启动时自动执行 `src/main/resources/db/migration/` 中尚未应用的迁移。
- 已应用的迁移文件不得修改；新增变更请创建下一个版本号（当前最高 V13）。
- 升级前先做 MySQL 备份；涉及破坏性变更时在维护窗口执行。
- 启动时会幂等迁移历史明文/旧版身份证密文为 `enc:v2:`，无需手工处理。

## 安全清单

- 修改 `.env.example` 中的默认密码与示例密钥后再部署。
- 生产保持 `toolshare.auth.expose-captcha=false`，短信使用 Mock 时确认验证码只写入日志。
- 文件桶按公开读取设计（[ADR-0003](../docs/adr/0003-public-bucket-upload-hardening.md)）；如存储敏感照片，需升级私有桶 + 签名 URL。
- 应用与中间件之间当前按本机/内网部署假设连接；对公网暴露时应在反向代理层启用 HTTPS 并限制中间件端口。
