#!/usr/bin/env bash
# 并发创建租赁单，验证租赁单号在并发下不重复。
#
# 用法：
#   bash scripts/stress-rental-create.sh
#
# 可选环境变量：
#   COUNT=10                并发数
#   BASE=http://localhost:9090
#   DATE=2026-12-01         预约日期（默认 50 天后）
#   TOOLS="1 4 5 6 7 8 9"   参与压测的可用工具 id
#   CLEANUP=1               1=删除本次订单并清理待投递提醒（默认）；0=保留
#   LOGIN_USER=zhangsan     居民账号
#   LOGIN_PASS=Aa123456
#
# 说明：并发创建的订单都是“待支付”状态，彼此不参与时段冲突校验，
#       因此可以安全地并发压测单号生成。
#
# 退出码：0 全部成功且单号唯一；1 有失败或重复。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE="${BASE:-http://localhost:9090}"
COUNT="${COUNT:-10}"
DATE="${DATE:-$(date -d '+50 days' +%F)}"
TOOLS="${TOOLS:-1 4 5 6 7 8 9}"
CLEANUP="${CLEANUP:-1}"
LOGIN_USER="${LOGIN_USER:-zhangsan}"
LOGIN_PASS="${LOGIN_PASS:-Aa123456}"
RABBITMQ_MGMT="${RABBITMQ_MGMT:-http://localhost:15672}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

read -r -a TOOL_ARR <<< "$TOOLS"
if [ "${#TOOL_ARR[@]}" -eq 0 ]; then
    echo "TOOLS 不能为空"
    exit 1
fi

# 读取 docker compose 配置，用于可选清理
ENV_FILE="$ROOT_DIR/deploy/docker/.env"
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck source=/dev/null
    . "$ENV_FILE"
    set +a
fi
MYSQL_CONTAINER="${MYSQL_CONTAINER:-tool-share-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${MYSQL_ROOT_PASSWORD:-toolmysql}}"
MYSQL_DATABASE="${MYSQL_DATABASE:-tool_share}"

# 1. 居民登录
LOGIN=$(curl -s -X POST "$BASE/api/user/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$LOGIN_USER\",\"password\":\"$LOGIN_PASS\"}")
TOKEN=$(printf '%s' "$LOGIN" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
    echo "登录失败，请确认后端已启动（make backend）：$LOGIN"
    exit 1
fi

# 2. 并发创建
echo "== 并发创建 $COUNT 个租赁单（date=$DATE, tools=$TOOLS） =="
pids=()
for i in $(seq 1 "$COUNT"); do
    TOOL_ID="${TOOL_ARR[$(( (i - 1) % ${#TOOL_ARR[@]} ))]}"
    curl -s -X POST "$BASE/api/rental" \
        -H "Authorization: $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"toolId\":$TOOL_ID,\"startTime\":\"${DATE}T09:00:00\",\"endTime\":\"${DATE}T12:00:00\",\"pickupName\":\"压测-$i\"}" \
        > "$TMP/rental-$i.json" &
    pids+=("$!")
done
for pid in "${pids[@]}"; do wait "$pid"; done

# 3. 校验
SUCCESS=$(grep -l '"code":"A200"' "$TMP"/rental-*.json 2>/dev/null | wc -l)
NOS=$(grep -h -oE 'SQ-ZY-[0-9]{8}-[0-9]{4}' "$TMP"/rental-*.json 2>/dev/null | sort)
TOTAL=$(printf '%s\n' "$NOS" | grep -c .)
UNIQUE=$(printf '%s\n' "$NOS" | sort -u | grep -c .)
DUPES=$(printf '%s\n' "$NOS" | uniq -d)

echo "成功响应: $SUCCESS/$COUNT"
echo "单号总数: $TOTAL，去重后: $UNIQUE"
echo "单号样例: $(printf '%s\n' "$NOS" | head -3 | tr '\n' ' ')"
if [ -n "$DUPES" ]; then
    echo "重复单号:"
    printf '%s\n' "$DUPES"
fi

if [ "$SUCCESS" -ne "$COUNT" ]; then
    echo "--- 失败响应 ---"
    for f in "$TMP"/rental-*.json; do
        if ! grep -q '"code":"A200"' "$f"; then
            echo "[$f] $(cat "$f")"
        fi
    done
fi

# 4. 可选清理
RENTAL_IDS=$(grep -h -o '"id":[0-9]*' "$TMP"/rental-*.json 2>/dev/null \
    | cut -d: -f2 | paste -sd, -)
if [ "$CLEANUP" = "1" ]; then
    if [ -n "$RENTAL_IDS" ]; then
        if docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
            -D "$MYSQL_DATABASE" \
            -e "DELETE FROM rental_order WHERE id IN ($RENTAL_IDS);" >/dev/null 2>&1; then
            echo "已清理本次创建的租赁单 id: $RENTAL_IDS"
        else
            echo "清理失败，可手动执行："
            echo "  DELETE FROM rental_order WHERE id IN ($RENTAL_IDS);"
        fi
    fi

    # 创建订单时投递了取件提醒；删除交换机会清空所有待投递消息
    if [ -n "${RABBITMQ_DEFAULT_USER:-}" ] && [ -n "${RABBITMQ_DEFAULT_PASS:-}" ]; then
        curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
            -X DELETE "$RABBITMQ_MGMT/api/queues/%2F/notify.delay.queue" >/dev/null || true
        curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
            -X DELETE "$RABBITMQ_MGMT/api/exchanges/%2F/notify.delay.exchange" >/dev/null || true
        echo "已清理待投递的提醒消息（延迟交换机）"
    fi
elif [ -n "$RENTAL_IDS" ]; then
    echo "CLEANUP=0：测试数据已保留。可在 IDEA 执行："
    echo "  SELECT id, order_no, tool_id, status, deposit_status, pickup_name FROM rental_order WHERE id IN ($RENTAL_IDS);"
fi

# 5. 结果
if [ "$SUCCESS" -eq "$COUNT" ] && [ "$TOTAL" -eq "$COUNT" ] && [ "$UNIQUE" -eq "$COUNT" ] && [ -z "$DUPES" ]; then
    echo "PASS：$COUNT 个并发请求全部成功，租赁单号无重复"
    exit 0
fi
echo "FAIL：存在失败请求或重复单号"
exit 1
