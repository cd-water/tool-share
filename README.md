# tool-share

社区共享工具租赁系统

## 技术栈

**后端**: Spring Boot 4.1.1 / Java 21 / MyBatis-Plus / Redisson / RabbitMQ(delayed-message-exchange) / Spring Security + JJWT / MapStruct / MinIO(S3 SDK) / Lombok

**基础设施**: MySQL 8.0 / Redis 7.4 / RabbitMQ 4.1 / MinIO(Docker Compose 编排)

**前端**: `frontend/` — Vue 3 + Vite + TypeScript + Element Plus + Pinia

## 项目结构

按依赖顺序分模块,后端从下到上依赖:

```
tool-common      公共类(Result / BizException / ResultCode)
tool-domain      实体 / DO 层
tool-core        Service、Mapper、基础设施客户端(Redis / MQ / OSS)
tool-api         Controller、DTO、MapStruct 映射
tool-bootstrap   Spring Boot 启动入口、Security、JWT、全局异常
infra/             Docker Compose、MySQL Schema/Seed、RabbitMQ 插件
frontend/          前端(Vue 3)
```

## 启动

```bash
# 1. 启动基础设施(MySQL / Redis / RabbitMQ / MinIO)
cd infra/docker
cp .env.example .env
docker compose up -d

# 2. 启动后端
./mvnw -pl tool-bootstrap spring-boot:run

# 3. 启动前端
cd frontend && pnpm install && pnpm dev
```

后端默认端口 `8600`,前端 `5600`(通过 `/api` 代理到后端),MySQL 3306 / Redis 6379 / RabbitMQ 5672,15672 / MinIO 9000,9001。
