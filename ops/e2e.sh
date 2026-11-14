#!/usr/bin/env bash
# 主流程 E2E：启动后端 + 两个前端 dev server，串行跑 Playwright，结束后清理全部子进程。
# 依赖：JDK 21、pnpm、Docker 中间件（与 README 快速开始一致）、系统 Chrome。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ops/.env 是本地中间件凭据；缺失时给出明确提示，避免后端以空密钥启动。
if [[ ! -f ops/.env ]]; then
  echo "✗ 缺少 ops/.env，请先 cp ops/.env.example ops/.env" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source ops/.env
set +a

ARTIFACTS="$(mktemp -d "${TMPDIR:-/tmp}/tool-share-e2e.XXXXXX")"
PIDS=()
START_AT=$SECONDS

cleanup() {
  local code=$?
  trap - EXIT INT TERM
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] || continue
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  if (( code != 0 )); then
    echo "" >&2
    echo "✗ E2E 失败，日志目录：$ARTIFACTS" >&2
    for log in backend web admin; do
      [[ -f "$ARTIFACTS/$log.log" ]] || continue
      echo "" >&2
      echo "--- $log.log（末尾 40 行）---" >&2
      tail -n 40 "$ARTIFACTS/$log.log" >&2 || true
    done
  fi
  exit "$code"
}
trap cleanup EXIT INT TERM

port_in_use() {
  ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$1$"
}

require_free_port() {
  if port_in_use "$1"; then
    echo "✗ 端口 $1 已被占用，E2E 不与并行任务争抢端口，请先停止占用进程" >&2
    exit 1
  fi
}

wait_http() {
  local url="$1" name="$2" pid="${3:-}" timeout="${4:-180}"
  local start=$SECONDS
  while ! curl -fsS -o /dev/null "$url" 2>/dev/null; do
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "✗ $name 进程已退出，详见 $ARTIFACTS" >&2
      return 1
    fi
    if (( SECONDS - start > timeout )); then
      echo "✗ 等待 $name 超时（${timeout}s）：$url" >&2
      return 1
    fi
    sleep 2
  done
  echo "✓ $name 就绪（$url）"
}

require_free_port 8900
require_free_port 5901
require_free_port 5902

# 依赖：锁文件锁定版本；仅首次安装，后续是 no-op。
echo "▶ 安装 E2E 依赖"
(cd ops/e2e && pnpm install --frozen-lockfile)
echo "▶ 检查前端依赖"
[[ -d frontend/web/node_modules ]] || (cd frontend/web && pnpm install --frozen-lockfile)
[[ -d frontend/admin/node_modules ]] || (cd frontend/admin && pnpm install --frozen-lockfile)

echo "▶ 启动后端（:8900）"
TOOLSHARE_DEMO_SEED_ENABLED=true \
TOOLSHARE_AI_TOOL_INDEX_ENABLED=false \
TOOLSHARE_AI_RECOMMEND_INDEX_ON_STARTUP=false \
TOOLSHARE_AUTH_RATE_LIMIT_PHONE_PER_MINUTE=100 \
TOOLSHARE_AUTH_RATE_LIMIT_PHONE_PER_HOUR=100 \
TOOLSHARE_AUTH_RATE_LIMIT_IP_PER_HOUR=100 \
  setsid ./mvnw -q -DskipTests spring-boot:run >"$ARTIFACTS/backend.log" 2>&1 &
backend_pid=$!
PIDS+=("$backend_pid")
wait_http "http://127.0.0.1:8900/v3/api-docs" "后端" "$backend_pid" 240

echo "▶ 等待启动种子完成"
seed_ready=0
for _ in $(seq 1 60); do
  counts=$(docker compose -f ops/docker-compose.yml exec -T mysql \
    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B "${MYSQL_DATABASE:-tool_share}" \
    -e "SELECT (SELECT COUNT(*) FROM t_tool), (SELECT COUNT(*) FROM t_knowledge_doc)" \
    2>/dev/null | tr -d '\r' || true)
  tool_count=$(echo "$counts" | awk '{print $1}')
  knowledge_count=$(echo "$counts" | awk '{print $2}')
  if [[ "${tool_count:-0}" -gt 0 && "${knowledge_count:-0}" -gt 0 ]]; then
    seed_ready=1
    break
  fi
  sleep 2
done
if [[ "$seed_ready" != "1" ]]; then
  echo "✗ 演示数据未在 120s 内就绪，详见 $ARTIFACTS/backend.log" >&2
  exit 1
fi
echo "✓ 演示数据就绪（工具 ${tool_count} 件，知识库 ${knowledge_count} 篇）"

echo "▶ 启动用户端（:5901）"
(cd frontend/web && exec ./node_modules/.bin/vite --host 127.0.0.1 --port 5901 --strictPort) \
  >"$ARTIFACTS/web.log" 2>&1 &
web_pid=$!
PIDS+=("$web_pid")
wait_http "http://127.0.0.1:5901/" "用户端" "$web_pid" 120

echo "▶ 启动管理端（:5902）"
(cd frontend/admin && exec ./node_modules/.bin/vite --host 127.0.0.1 --port 5902 --strictPort) \
  >"$ARTIFACTS/admin.log" 2>&1 &
admin_pid=$!
PIDS+=("$admin_pid")
wait_http "http://127.0.0.1:5902/" "管理端" "$admin_pid" 120

echo "▶ 运行 Playwright 主流程 E2E"
TEST_AT=$SECONDS
(cd ops/e2e && pnpm exec playwright test)
TEST_SECONDS=$((SECONDS - TEST_AT))

echo ""
echo "✓ 主流程 E2E 通过（测试耗时 ${TEST_SECONDS}s，总耗时 $((SECONDS - START_AT))s）"
echo "  运行日志：$ARTIFACTS"
