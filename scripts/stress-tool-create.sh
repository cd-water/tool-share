#!/usr/bin/env bash
# 并发创建工具，验证工具编号在并发下不重复。
#
# 用法：
#   bash scripts/stress-tool-create.sh
#
# 可选环境变量：
#   COUNT=10                并发数
#   BASE=http://localhost:9090
#   CATEGORY=1              工具类别 1维修 2园艺 3清洁 4其他
#   CLEANUP=1               1=测试后删除本次创建的工具（默认）；0=保留
#   LOGIN_USER=lisi         工作人员账号
#   LOGIN_PASS=Aa123456
#
# 退出码：0 全部成功且编号唯一；1 有失败或重复。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE="${BASE:-http://localhost:9090}"
COUNT="${COUNT:-10}"
CATEGORY="${CATEGORY:-1}"
CLEANUP="${CLEANUP:-1}"
LOGIN_USER="${LOGIN_USER:-lisi}"
LOGIN_PASS="${LOGIN_PASS:-Aa123456}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 读取 docker compose 的 MySQL 配置，用于可选清理
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

# 1. 工作人员登录
LOGIN=$(curl -s -X POST "$BASE/api/user/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$LOGIN_USER\",\"password\":\"$LOGIN_PASS\"}")
TOKEN=$(printf '%s' "$LOGIN" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
    echo "登录失败，请确认后端已启动（make backend）：$LOGIN"
    exit 1
fi

# 2. 并发创建
echo "== 并发创建 $COUNT 个工具（category=$CATEGORY） =="
STAMP="$(date +%s)"
pids=()
for i in $(seq 1 "$COUNT"); do
    curl -s -X POST "$BASE/api/tool" \
        -H "Authorization: $TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"stress-tool-$STAMP-$i\",\"modelSpec\":\"stress\",\"category\":$CATEGORY,\"damageLevel\":1,\"deposit\":10,\"storageLocation\":\"stress\",\"usageInstruction\":\"stress\"}" \
        > "$TMP/tool-$i.json" &
    pids+=("$!")
done
for pid in "${pids[@]}"; do wait "$pid"; done

# 3. 校验
SUCCESS=$(grep -l '"code":"A200"' "$TMP"/tool-*.json 2>/dev/null | wc -l)
CODES=$(grep -h -oE 'SQ-G-[A-Z]{2}-[0-9]{4}' "$TMP"/tool-*.json 2>/dev/null | sort)
TOTAL=$(printf '%s\n' "$CODES" | grep -c .)
UNIQUE=$(printf '%s\n' "$CODES" | sort -u | grep -c .)
DUPES=$(printf '%s\n' "$CODES" | uniq -d)

echo "成功响应: $SUCCESS/$COUNT"
echo "编号总数: $TOTAL，去重后: $UNIQUE"
echo "编号样例: $(printf '%s\n' "$CODES" | head -3 | tr '\n' ' ')"
if [ -n "$DUPES" ]; then
    echo "重复编号:"
    printf '%s\n' "$DUPES"
fi

if [ "$SUCCESS" -ne "$COUNT" ]; then
    echo "--- 失败响应 ---"
    for f in "$TMP"/tool-*.json; do
        if ! grep -q '"code":"A200"' "$f"; then
            echo "[$f] $(cat "$f")"
        fi
    done
fi

# 4. 可选清理（只删除本次脚本创建的 id）
IDS=$(grep -h -o '"id":[0-9]*' "$TMP"/tool-*.json 2>/dev/null \
    | cut -d: -f2 | paste -sd, -)
if [ "$CLEANUP" = "1" ]; then
    if [ -n "$IDS" ]; then
        if docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
            -D "$MYSQL_DATABASE" \
            -e "DELETE FROM tool_status_log WHERE tool_id IN ($IDS); DELETE FROM tool WHERE id IN ($IDS);" \
            >/dev/null 2>&1; then
            echo "已清理本次创建的工具 id: $IDS"
        else
            echo "清理失败，可手动执行："
            echo "  DELETE FROM tool_status_log WHERE tool_id IN ($IDS);"
            echo "  DELETE FROM tool WHERE id IN ($IDS);"
        fi
    fi
elif [ -n "$IDS" ]; then
    echo "CLEANUP=0：测试数据已保留。可在 IDEA 执行："
    echo "  SELECT id, tool_code, name, status FROM tool WHERE id IN ($IDS);"
fi

# 5. 结果
if [ "$SUCCESS" -eq "$COUNT" ] && [ "$TOTAL" -eq "$COUNT" ] && [ "$UNIQUE" -eq "$COUNT" ] && [ -z "$DUPES" ]; then
    echo "PASS：$COUNT 个并发请求全部成功，工具编号无重复"
    exit 0
fi
echo "FAIL：存在失败请求或重复编号"
exit 1
