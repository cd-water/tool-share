#!/usr/bin/env bash
# 本地一键验证（ADR-0005）：编译后端、检查并构建两个前端。
# 任一步失败即停止，并打印失败的步骤名。
set -euo pipefail

cd "$(dirname "$0")/.."

current_step=""
on_error() {
  echo ""
  echo "✗ 验证失败：${current_step}" >&2
  exit 1
}
trap on_error ERR

run() {
  current_step="$1"
  echo ""
  echo "▶ ${current_step}"
  shift
  "$@"
}

run_in() {
  current_step="$1"
  local dir="$2"
  echo ""
  echo "▶ ${current_step}"
  shift 2
  ( cd "$dir" && "$@" )
}

# 1. 后端编译
run "后端编译" ./mvnw -q -DskipTests compile

# 2. 后端测试（Testcontainers 自动启动 MySQL/Redis，只需本机 Docker）
run "后端测试" ./mvnw -q test

# 3. 用户端：安装、lint、构建
run_in "用户端依赖安装" frontend/web pnpm install --frozen-lockfile
run_in "用户端 lint" frontend/web pnpm lint
run_in "用户端构建" frontend/web pnpm build

# 4. 管理端：安装、构建
run_in "管理端依赖安装" frontend/admin pnpm install --frozen-lockfile
run_in "管理端构建" frontend/admin pnpm build

# 5. 主流程 E2E（启动后端与两个前端 dev server，串行执行三条流程）
if [[ "${SKIP_E2E:-0}" == "1" ]]; then
  echo ""
  echo "▶ 主流程 E2E（SKIP_E2E=1，已跳过）"
else
  run "主流程 E2E" bash ops/e2e.sh
fi

echo ""
echo "✓ 验证通过"
