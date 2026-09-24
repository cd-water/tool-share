# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

社区共享工具租赁系统 (community tool-sharing / rental system) — tool listing, rental, and return.

- Backend: Spring Boot 4.1.1, Java 21, MyBatis-Plus 3.5.17, Redisson 4.7, RabbitMQ 4.1 (with `rabbitmq_delayed_message_exchange` for rental-order TTL), MinIO via AWS S3 SDK 2.55, Spring Security + JJWT 0.12, MapStruct 1.6, Lombok.
- App port: `8600`.
- Infra (Docker Compose): MySQL 8.0.46, Redis 7.4.11, RabbitMQ 4.1.8-management, MinIO.
- Frontend: `frontend/` — Vue 3 + Vite + TS + Element Plus + Pinia (dev port `5600`, proxies `/api` to the backend).

## Build & Run

Backend uses the Maven wrapper from repo root:

```bash
./mvnw clean install              # build all modules
./mvnw -pl tool-bootstrap spring-boot:run    # run app
./mvnw -pl tool-bootstrap test               # tests for bootstrap
./mvnw -pl tool-api -am test                 # tests for api (+deps)
./mvnw test                                  # all tests
```

Frontend (single app in `frontend/`, pnpm):

```bash
cd frontend && pnpm install && pnpm dev
cd frontend && pnpm build
```

Bring up infra from `infra/docker/`:

```bash
cp .env.example .env   # fill in values
docker compose up -d   # mysql 3306, redis 6379, rabbitmq 5672/15672, minio 9000/9001
```

Schema/seed auto-load via `/docker-entrypoint-initdb.d/`.

## Module Layout

Maven modules, dependency-ordered (do not reverse):

```
tool-common   →  Result, BizException, ResultCode  (no deps)
tool-domain   →  DO / entity layer                  (→ common)
tool-core     →  mapper, service, infra clients    (→ common, domain)
tool-api      →  controller, DTO, MapStruct        (→ common, domain, core)
tool-bootstrap → @SpringBootApplication, security/JWT config, MainClass (→ api)
```

All cross-module code lives under `com.cdwater.toolshare`. The bootstrap module is the assembly point; never add `main` classes elsewhere.

## Conventions (already enforced in code)

- **API envelope**: every controller returns `Result<T>`. Codes live in `ResultCode`:
  - `A200` success, `C4xx` client errors (`C400/C401/C403/C404/C409/C429`), `S500` server. Add codes there — never inline strings.
- **Errors**: throw `BizException(code, msg)` or `BizException(ResultCode.X)`; never return `Result.fail(...)` from controllers. `GlobalExceptionHandler` (`@RestControllerAdvice` in `tool-bootstrap/config/web/`) maps `BizException`, validation (`@Valid` + `@Validated`), missing params, malformed body, type mismatch, and a fallback. If a new exception type needs mapping, add it there.
- **DTO mapping**: MapStruct mappers go in `tool-api`; service-layer methods accept DTOs at the boundary, DOs internally.
- **Validation**: Jakarta Bean Validation (`spring-boot-starter-validation`) on request DTOs.
- **Auth**: Spring Security + JJWT, configured in `tool-bootstrap`. Use the existing filter chain — don't bolt on a new auth path.
- **Redis**: Redisson client (`spring-boot-starter-data-redis` + `redisson-spring-boot-starter`). Distributed locks / rate limiting should use Redisson, not raw Lettuce.
- **Delay queue**: rental-order TTL flows through the delayed-message-exchange plugin. Don't roll a custom scheduler for delays < minutes.
- **Object storage**: AWS S3 SDK pointed at MinIO (`software.amazon.awssdk:s3`). No direct MinIO client calls.

## Working with Claude

- **Never commit, push, or create a PR without explicit user approval.** Claude may stage, build, and run tests, but every `git commit` / `git push` / `gh pr create` must wait for an explicit "yes, commit it" / "push it" / "open the PR" from the user. If the user only says "implement X", that does not authorize committing the change.

## Frontend

- `frontend/` — Vue 3 SPA: Element Plus + Pinia + Axios + VueUse. Pinia owns client state; Vue Router owns routing; Axios unwraps the backend's `Result<T>` envelope and surfaces `code/message` for non-A200 responses.

## Notes

- DB charset/collation is `utf8mb4 / utf8mb4_0900_ai_ci`, server timezone `+08:00` (see `infra/db/mysql.cnf`). Match these in new DDL.
- `infra/docker/.env` is gitignored — never commit credentials. Use `.env.example` as the template.
- `.codegraph/` is a local code-intelligence index — keep it gitignored.
- New tables go in `infra/db/schema.sql` in the order they're referenced and must include `create_time` / `update_time` columns.