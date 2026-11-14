#!/usr/bin/env bash
# AI 固定评测集（issue #22）：只跑 @Tag("ai-eval")，不进入默认测试门禁。
# 依赖本机 Docker（Testcontainers 启动 MySQL/Redis）；不访问 DeepSeek/Qdrant/Ollama。
# 阈值可用环境变量覆盖：AI_EVAL_MIN_HIT_RATE、AI_EVAL_MIN_CITATION_ACCURACY（默认 0.8）。
set -euo pipefail

cd "$(dirname "$0")/.."

./mvnw -Pai-eval test -Dtest=AiEvalTest \
  -Dai.eval.min-hit-rate="${AI_EVAL_MIN_HIT_RATE:-0.8}" \
  -Dai.eval.min-citation-accuracy="${AI_EVAL_MIN_CITATION_ACCURACY:-0.8}"
