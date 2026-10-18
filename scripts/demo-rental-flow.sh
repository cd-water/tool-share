#!/usr/bin/env bash
# 居民租赁全流程 curl 演示：预约 -> 缴押金 -> 取件 -> 归还
#
# 用法：
#   bash scripts/demo-rental-flow.sh
#
# 可选环境变量：
#   BASE=http://localhost:9090   后端地址
#   DATE=2026-10-21              预约日期（默认 3 天后）
#   TOOL_ID=1                    工具 id（默认手电钻）
#
# 依赖：curl、python3（用于格式化 JSON）

set -euo pipefail

BASE="${BASE:-http://localhost:9090}"
DATE="${DATE:-$(date -d '+3 days' +%F)}"
TOOL_ID="${TOOL_ID:-1}"

json() { python3 -m json.tool; }

echo "== 预约日期: $DATE, 工具 id: $TOOL_ID =="

# 1. 居民登录（张三）
echo "--- 1. 居民登录 ---"
R=$(curl -s -X POST "$BASE/api/user/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"zhangsan","password":"Aa123456"}' \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$R" ]; then
  echo "居民登录失败，请确认后端已启动（make backend）"
  exit 1
fi
echo "token: $R"

# 2. 查询可约时段
echo "--- 2. 查询可约时段 ---"
curl -s "$BASE/api/rental/available-times?toolId=$TOOL_ID&date=$DATE" \
  -H "Authorization: $R" | json

# 3. 创建预约 09:00-12:00
echo "--- 3. 创建预约 ---"
CREATE=$(curl -s -X POST "$BASE/api/rental" \
  -H "Authorization: $R" \
  -H 'Content-Type: application/json' \
  -d "{\"toolId\":$TOOL_ID,\"startTime\":\"${DATE}T09:00:00\",\"endTime\":\"${DATE}T12:00:00\",\"pickupName\":\"张三（本人）\"}")
echo "$CREATE" | json

ID=$(echo "$CREATE" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2 || true)
if [ -z "$ID" ]; then
  echo "创建预约失败（可能时段冲突），可换 DATE 或 TOOL_ID 重试"
  exit 1
fi
echo "订单 id: $ID"

# 4. 缴纳押金（支付沙箱）
echo "--- 4. 缴纳押金 ---"
curl -s -X POST "$BASE/api/rental/$ID/pay" \
  -H "Authorization: $R" | json

# 5. 工作人员登录（李四）
echo "--- 5. 工作人员登录 ---"
S=$(curl -s -X POST "$BASE/api/user/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"lisi","password":"Aa123456"}' \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$S" ]; then
  echo "工作人员登录失败"
  exit 1
fi
echo "token: $S"

# 6. 取件登记
echo "--- 6. 取件登记 ---"
curl -s -X POST "$BASE/api/rental/$ID/pickup" \
  -H "Authorization: $S" \
  -H 'Content-Type: application/json' \
  -d '{"pickupName":"张三（代取：李四）"}' | json

# 7. 归还验收（完好、无逾期、全额退押金）
echo "--- 7. 归还验收 ---"
DEPOSIT=$(curl -s "$BASE/api/rental/$ID" -H "Authorization: $R" \
  | grep -o '"depositAmount":[0-9.]*' | cut -d: -f2)
echo "押金: $DEPOSIT"
curl -s -X POST "$BASE/api/rental/$ID/return" \
  -H "Authorization: $S" \
  -H 'Content-Type: application/json' \
  -d "{\"acceptResult\":1,\"overdueFee\":0,\"depositRefundAmount\":$DEPOSIT}" | json

# 8. 最终状态
echo "--- 8. 租赁单最终状态 ---"
curl -s "$BASE/api/rental/$ID" \
  -H "Authorization: $R" | json

echo "== 流程完成：预约 -> 缴押金 -> 取件 -> 归还 =="
