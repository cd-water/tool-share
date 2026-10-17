# 开发环境准备

## 必装软件

| 软件 | 版本要求 | 用途 | 验证命令 |
| --- | --- | --- | --- |
| JDK | 21+ | 后端编译运行（Spring Boot 4.1 要求） | `java -version` |
| Node.js | 20 LTS+ | 前端构建运行 | `node -v` |
| pnpm | 9+ | 前端包管理 | `pnpm -v` |
| Docker | 含 Docker Compose v2 | 中间件（MySQL/Redis/RabbitMQ/MinIO/Qdrant/Ollama） | `docker compose version` |
| Git | 任意近期版本 | 代码管理 | `git --version` |

## 安装要点

- **pnpm**：装好 Node.js 后执行 `corepack enable` 即可获得，无需单独安装。
- **Windows 用户**：建议在 WSL2 内完成上述安装并克隆仓库；Docker 使用 Docker Desktop 的 WSL 集成即可。
- **IDEA 打开后端**：需启用 Lombok 插件，并在 `Settings → Build → Compiler → Annotation Processors` 勾选 Enable annotation processing（Lombok + MapStruct 需要）。
- **mvnw 无执行权限**（Windows 检出的常见现象）：本项目 Makefile 已用 `sh mvnw` 调用，无需处理；手动执行时用 `sh mvnw ...` 或 `chmod +x mvnw`。
- **中间件配置**：首次启动前在 `deploy/docker/` 执行 `cp .env.example .env`（该文件不入库）。

## 可选

| 项 | 说明 |
| --- | --- |
| DeepSeek API Key | 仅 AI 问答模块需要。写入根目录 `application-local.yml`（已被 gitignore）：`spring.ai.openai.api-key: sk-xxx` |
| Ollama 模型 | `bge-m3` 向量模型由 compose 的一次性任务在首次启动时自动拉取（约 1.2 GB），无需手动装 |
| IDE | 后端 IntelliJ IDEA / 前端 VS Code（装 Volar 扩展） |

## 验证环境就绪

仓库根目录执行：

```bash
make infra      # 中间件全部 Healthy
make up         # 中间件 + 后端(:9090) + 前端(:5050)
```

浏览器打开 <http://localhost:5050> 能看到登录页即环境就绪。
