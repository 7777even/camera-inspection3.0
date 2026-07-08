#!/bin/bash
# 端到端冒烟脚本 — 验证 Phase 5 全链路
# 使用方式: bash scripts/smoke-test.sh

set -euo pipefail
PASS=0
FAIL=0

PYTHON="/c/Users/7even/.workbuddy/binaries/python/versions/3.13.12/python.exe"

check() {
  local desc="$1"
  shift
  if "$@"; then
    echo "  ✅ PASS: $desc"
    ((PASS++))
  else
    echo "  ❌ FAIL: $desc"
    ((FAIL++))
  fi
}

echo "=== Phase 5 端到端冒烟测试 ==="
echo ""

# 1. 健康检查
echo "--- 1. 服务健康检查 ---"
check "enviro-brain UP" curl -sf http://localhost:8080/actuator/health | grep -q UP
check "queqiao UP" curl -sf http://localhost:8081/actuator/health | grep -q UP

# 2. 触发巡检 (通过 MCP)
echo ""
echo "--- 2. 触发巡检 ---"
TASK_ID=$($PYTHON -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('trigger_inspection', {'reason': 'Phase5 冒烟测试'})
            import json
            data = json.loads(res.content[0].text)
            print(data.get('data',{}).get('taskId',''))
asyncio.run(t())
" 2>/dev/null)
check "trigger_inspection 返回 taskId" [ -n "$TASK_ID" ]
echo "  taskId=$TASK_ID"

# 3. 等待巡检完成 (最多等 5 分钟)
echo ""
echo "--- 3. 等待巡检完成 ---"
MAX_WAIT=300
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
  RESULT=$(mysql -uroot -proot -h127.0.0.1 -P3306 -e \
    "SELECT status FROM enviro_brain.inspection_records WHERE id=$TASK_ID" 2>/dev/null | tail -1)
  if [ "$RESULT" = "COMPLETED" ]; then
    echo "  ✅ 巡检完成 (耗时 ${WAITED}s)"
    break
  fi
  sleep 10
  WAITED=$((WAITED + 10))
done
check "巡检在 5 分钟内完成" [ $WAITED -lt $MAX_WAIT ]

# 4. 触发同步
echo ""
echo "--- 4. 触发数据同步 ---"
LATEST_SYNC=$(mysql -uroot -proot -h127.0.0.1 -P3306 -e \
  "SELECT MAX(sync_version) FROM enviro_brain.inspection_records" 2>/dev/null | tail -1)
curl -sf -X POST "http://localhost:8081/api/notify/new-data" \
  -H "X-API-Key: queqiao-notify-key-2026" \
  -H "Content-Type: application/json" \
  -d "{\"syncVersion\": $LATEST_SYNC, \"type\": \"inspection_completed\"}" > /dev/null 2>&1 || true
sleep 5

# 5. MCP 查询验证
echo ""
echo "--- 5. MCP 查询验证 ---"
SUMMARY=$($PYTHON -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('get_inspection_summary', {
                'start': '$(date +%Y-%m-%d)',
                'end': '$(date +%Y-%m-%d)'
            })
            print(res.content[0].text[:100])
asyncio.run(t())
" 2>/dev/null)
check "get_inspection_summary 有数据" echo "$SUMMARY" | grep -q "onlineRate\|total\|data"

echo ""
echo "=== 结果: $PASS 通过, $FAIL 失败 ==="
exit $FAIL
