# 社区共享工具租赁系统 一键启停
# 中间件: deploy/docker/docker-compose.yml（MySQL/Redis/RabbitMQ/MinIO/Qdrant/Ollama）
# 后端: :9090  前端: :5050  日志: logs/

LOG_DIR := logs
BACKEND_LOG := $(LOG_DIR)/backend.log
FRONTEND_LOG := $(LOG_DIR)/frontend.log

.PHONY: up down backend backend-down frontend frontend-down infra infra-down infra-clean logs

up: infra backend frontend ## 启动中间件 + 前后端

down: backend-down frontend-down infra-down ## 停止中间件 + 前后端（数据卷保留）

infra: ## 启动中间件（等待健康检查通过）
	cd deploy/docker && docker compose up -d --wait mysql redis rabbitmq minio qdrant ollama

infra-down: ## 停止中间件（数据卷保留）
	cd deploy/docker && docker compose stop

infra-clean: ## 停止并删除中间件容器与数据卷（危险：数据清空）
	cd deploy/docker && docker compose down -v

backend: ## 启动后端 :9090（日志 logs/backend.log）
	@mkdir -p $(LOG_DIR)
	@if ss -tln 2>/dev/null | grep -q ':9090 '; then \
		echo "后端已在运行（:9090）"; \
	else \
		echo "后端启动中，日志：$(BACKEND_LOG)"; \
		nohup sh mvnw spring-boot:run > $(BACKEND_LOG) 2>&1 & \
	fi
	@echo "等待后端就绪（最长 90 秒）..."; \
	ok=0; \
	for i in $$(seq 1 45); do \
		curl -s -o /dev/null --max-time 2 http://localhost:9090/api/user/me && { ok=1; break; }; \
		sleep 2; \
	done; \
	if [ $$ok -eq 1 ]; then echo "后端已就绪：http://localhost:9090"; else echo "后端未就绪，请执行 make logs 查看日志"; fi

backend-down: ## 停止后端
	@pid=$$(ss -tlnp 2>/dev/null | grep ':9090 ' | grep -oP '(?<=pid=)[0-9]+' | head -1); \
	if [ -n "$$pid" ]; then \
		kill $$pid && echo "后端已停止（PID $$pid）"; \
	else \
		echo "后端未在运行"; \
	fi

frontend: ## 启动前端 :5050（日志 logs/frontend.log）
	@mkdir -p $(LOG_DIR)
	@if ss -tln 2>/dev/null | grep -q ':5050 '; then \
		echo "前端已在运行（:5050）"; \
	else \
		echo "前端启动中，日志：$(FRONTEND_LOG)"; \
		cd frontend && nohup pnpm dev > ../$(FRONTEND_LOG) 2>&1 & \
	fi
	@sleep 3; \
	if ss -tln 2>/dev/null | grep -q ':5050 '; then \
		echo "前端已就绪：http://localhost:5050"; \
	else \
		echo "前端未就绪，请查看 $(FRONTEND_LOG)"; \
	fi

frontend-down: ## 停止前端
	@pid=$$(ss -tlnp 2>/dev/null | grep ':5050 ' | grep -oP '(?<=pid=)[0-9]+' | head -1); \
	if [ -n "$$pid" ]; then \
		kill $$pid && echo "前端已停止（PID $$pid）"; \
	else \
		echo "前端未在运行"; \
	fi

logs: ## 查看后端日志（logs/backend.log）
	tail -n 100 -f $(BACKEND_LOG)
