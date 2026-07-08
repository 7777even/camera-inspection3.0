# 环保小脑 + 鹊桥 运维手册

## 目录

- [日常健康检查](#日常健康检查)
- [日志查看](#日志查看)
- [重启流程](#重启流程)
- [数据备份与恢复](#数据备份与恢复)
- [巡检自动调度说明](#巡检自动调度说明)
- [MCP 工具列表与使用示例](#mcp-工具列表与使用示例)
- [数据同步机制说明](#数据同步机制说明)

---

## 日常健康检查

### 容器状态

```bash
# 查看所有容器运行状态
docker compose ps

# 预期输出示例：
# NAME                       IMAGE                     STATUS        PORTS
# inspection-enviro-brain    enviro-brain:latest       Up 2 days     0.0.0.0:8080->8080/tcp
# inspection-mysql           mysql:5.7                 Up 2 days     0.0.0.0:3306->3307/tcp
# inspection-queqiao         queqiao:latest            Up 2 days     0.0.0.0:8081->8081/tcp
```

所有容器 `STATUS` 应为 `Up`。若某容器反复重启 `Restarting`，参见 [故障排查](troubleshooting.md)。

### HTTP 健康检查

```bash
# enviro-brain
curl -s http://localhost:8080/actuator/health | jq .
# 预期: {"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},...}}

# queqiao
curl -s http://localhost:8081/actuator/health | jq .
# 预期: {"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"},...}}
```

### 磁盘使用量

```bash
# 查看 Docker 数据卷占用
docker system df

# 截图和台账数据存储在命名卷中
docker volume ls
```

### 数据验证

```bash
# 检查 enviro-brain 数据库表记录数
docker exec inspection-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD -e \
  "SELECT COUNT(*) AS inspections FROM enviro_brain.inspection_record;"

# 检查鹊桥同步库记录数
docker exec inspection-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD -e \
  "SELECT COUNT(*) AS synced_inspections FROM queqiao_sync.synced_inspection_record;"

# 检查同步水位
docker exec inspection-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD -e \
  "SELECT * FROM queqiao_sync.sync_watermark;"
```

预期 `synced_inspections` 数量与 `inspections` 一致，`sync_watermark` 中各表水位与 `enviro_brain` 侧的 `sync_version` 最大值对齐。

---

## 日志查看

### 实时跟踪

```bash
# 查看所有服务最新 100 行日志并持续跟踪
docker compose logs -f --tail=100

# 仅查看 enviro-brain
docker compose logs -f --tail=100 enviro-brain

# 仅查看 queqiao
docker compose logs -f --tail=100 queqiao
```

### 按关键词过滤

```bash
# 巡检执行日志
docker compose logs enviro-brain | grep "\[Inspection\]"

# 同步日志
docker compose logs queqiao | grep "\[sync\]"

# 错误日志
docker compose logs --tail=500 enviro-brain | grep -i error

# MCP 调用日志
docker compose logs queqiao | grep "\[mcp\]"
```

### 日志时间范围

```bash
# 查看近 30 分钟的日志
docker compose logs --since=30m enviro-brain

# 从某个时间点开始
docker compose logs --since="2026-07-08T10:00:00" enviro-brain
```

---

## 重启流程

### 重启单个服务

```bash
# 重启 enviro-brain
docker compose restart enviro-brain

# 重启 queqiao
docker compose restart queqiao

# 重启 MySQL
docker compose restart mysql
```

### 重启所有服务

```bash
docker compose restart
```

### 完全重建

```bash
docker compose down
docker compose up -d
```

> 重启不会丢失数据（数据存储在命名卷中）。

---

## 数据备份与恢复

### 备份 MySQL 全部数据库

```bash
# 方式一：使用 docker exec
docker exec inspection-mysql mysqldump -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  --all-databases --single-transaction --routines --triggers \
  > /backup/inspection-$(date +%Y%m%d_%H%M%S).sql
```

### 仅备份业务数据库

```bash
docker exec inspection-mysql mysqldump -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  --databases enviro_brain queqiao_sync --single-transaction \
  > /backup/inspection-db-$(date +%Y%m%d_%H%M%S).sql
```

### 备份截图和台账文件

```bash
# 截图存储卷
docker run --rm -v screenshots-data:/data -v /backup:/backup alpine \
  tar czf /backup/screenshots-$(date +%Y%m%d).tar.gz -C /data .

# 台账存储卷
docker run --rm -v ledger-data:/data -v /backup:/backup alpine \
  tar czf /backup/ledger-$(date +%Y%m%d).tar.gz -C /data .
```

### 恢复数据库

```bash
docker exec -i inspection-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" \
  < /backup/inspection-db-20260708_120000.sql
```

> 建议使用 crontab 定期执行备份脚本。

---

## 巡检自动调度说明

### enviro-brain：定时巡检

**调度表达式：** `0 0 * * * ?`（每小时整点执行）

配置在 `enviro-brain/src/main/java/com/enviro/brain/scheduler/InspectionScheduler.java`：

```java
@Scheduled(cron = "0 0 * * * ?")
public void hourlyInspection() {
    inspectionService.executeInspection("auto");
}
```

**每次巡检流程：**

1. 读取 `camera_config` 表中所有启用（`status = 1`）的摄像头
2. 分配同步版本号（`syncVersion`）
3. 创建 `inspection_record`（状态 `RUNNING`）
4. 并发调用海康 Artemis API 截图（默认并发 12 路，超时 120 秒）
5. 写入 `camera_result`，并统计在线/离线/异常数量
6. 更新 `inspection_record` 为 `COMPLETED`
7. 生成 Word 台账文档（仅含标记"更新到台账"的摄像头）
8. 推送飞书通知（若配置了 `FEISHU_WEBHOOK_URL`）
9. 回调鹊桥（若配置了 `QUEQIAO_CALLBACK_URL`）

### queqiao：定时同步

**调度表达式：** `0 */30 * * * *`（每 30 分钟触发）

配置在 `queqiao/src/main/resources/application-prod.yml`：

```yaml
queqiao:
  sync:
    cron: "0 */30 * * * *"
```

**每次同步流程：**

1. 拉取 enviro-brain 当前水位（`sync_version`）
2. 逐表比对本地 `sync_watermark`：
   - `inspections`
   - `camera_results`
   - `ledger_records`
3. 以游标（since + batchLimit）循环拉取增量数据
4. 幂等写入 `synced_*` 表
5. 更新 `sync_watermark` 水位

### 巡检触发方式

| 方式 | 说明 | 操作 |
|------|------|------|
| 定时自动 | 每小时整点 | 无需操作 |
| 手动触发 | 通过 REST API | `curl -X POST http://localhost:8080/api/v1/inspections/trigger` |
| MCP 触发 | 通过 MCP 工具 | 调用 `trigger_inspection` tool |

---

## MCP 工具列表与使用示例

queqiao 在端口 8081 上通过 SSE 协议暴露 MCP 服务，端点路径：

```
GET  /mcp/sse          # SSE 流（建立连接）
POST /mcp/message      # JSON-RPC 消息（通过 sessionId 关联）
```

### 工具总览

| 工具名 | 类型 | 描述 |
|--------|------|------|
| `get_inspection_ledger` | 查询 | 获取指定日期的危废仓库巡查台账 |
| `get_camera_status` | 查询 | 获取摄像头状态（最新快照 + 历史） |
| `get_inspection_summary` | 查询 | 获取指定时间区间内的巡检汇总 |
| `trigger_inspection` | 操作 | 触发环保小脑执行一次巡检 |
| `download_ledger_docx` | 操作 | 下载指定巡检记录的 Word 台账文档 |

### Python MCP 客户端示例

安装依赖：

```bash
pip install mcp httpx
```

#### 1. get_inspection_ledger — 获取台账

```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.sse import sse_client

async def main():
    async with sse_client("http://localhost:8081/mcp/sse") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()

            result = await session.call_tool("get_inspection_ledger", {
                "date": "2026-07-08",
                "status": None,
                "enterprise": None
            })
            print(result.content)

asyncio.run(main())
```

#### 2. get_camera_status — 获取摄像头状态

```python
async def main():
    async with sse_client("http://localhost:8081/mcp/sse") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()

            # 查询所有摄像头最新状态
            result = await session.call_tool("get_camera_status", {
                "cameraName": None,
                "historyDays": None
            })
            print(result.content)

            # 查询指定摄像头及历史
            result = await session.call_tool("get_camera_status", {
                "cameraName": "危废仓库-东门",
                "historyDays": 7
            })
            print(result.content)

asyncio.run(main())
```

#### 3. get_inspection_summary — 获取巡检汇总

```python
async def main():
    async with sse_client("http://localhost:8081/mcp/sse") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()

            result = await session.call_tool("get_inspection_summary", {
                "start": "2026-07-01",
                "end": "2026-07-08"
            })
            print(result.content)

asyncio.run(main())
```

#### 4. trigger_inspection — 触发巡检

```python
async def main():
    async with sse_client("http://localhost:8081/mcp/sse") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()

            result = await session.call_tool("trigger_inspection", {
                "reason": "运维人员手动触发，检查夜间摄像头"
            })
            print(result.content)

asyncio.run(main())
```

#### 5. download_ledger_docx — 下载台账文档

```python
async def main():
    async with sse_client("http://localhost:8081/mcp/sse") as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            await session.initialize()

            # inspectId 从 get_inspection_ledger 返回值中获取
            result = await session.call_tool("download_ledger_docx", {
                "inspectId": 42
            })
            print(result.content)

asyncio.run(main())
```

### 使用 curl 测试

MCP SSE 协议需要先建立 SSE 连接获取 `sessionId`，再通过 POST 发送 JSON-RPC 消息。完整流程较复杂，建议使用 Python MCP SDK 或 AI 客户端工具进行调用。

---

## 数据同步机制说明

### 架构

```
enviro-brain (8080)                queqiao (8081)
┌─────────────────┐               ┌──────────────────────┐
│  inspection_    │  ────GET───→  │  synced_inspection_  │
│  record         │   (同步拉取)   │  record              │
│                 │               │                      │
│  camera_result  │  ────GET───→  │  synced_camera_      │
│                 │   (同步拉取)   │  result              │
│                 │               │                      │
│  ledger_record  │  ────GET───→  │  synced_ledger_      │
│                 │   (同步拉取)   │  record              │
│                 │               │                      │
│  sync_version   │  ────POST──→  │  /api/notify/new-    │
│  (水位推送)      │   (可选回调)  │  data                │
└─────────────────┘               └──────────────────────┘
```

### 同步触发方式

1. **定时同步**（默认每 30 分钟）— queqiao 自行拉取
2. **回调触发**（可选）— enviro-brain 巡检完成后调用 `QUEQIAO_CALLBACK_URL`，queqiao 立即执行一轮同步
3. **手动触发** — 后续版本可能提供管理接口

### 同步一致性

- 使用 **sync_version** 作为全局单调递增水位
- 逐表独立维护本地水位（`sync_watermark` 表）
- 游标循环拉取，单次批量上限 200 条
- 幂等写入（`upsert`），重复拉取不会产生重复数据
- 单表失败不影响其他表的同步

### 验证同步状态

```sql
-- 查看水位
SELECT * FROM queqiao_sync.sync_watermark;

-- 预期输出：
-- +----------------+------------------+---------------------+
-- | table_name     | last_sync_version | last_sync_time      |
-- +----------------+------------------+---------------------+
-- | inspections    |             1024  | 2026-07-08 10:30:05 |
-- | camera_results |             1024  | 2026-07-08 10:30:05 |
-- | ledger_records |             1024  | 2026-07-08 10:30:05 |
-- +----------------+------------------+---------------------+
```

若某个表的水位明显落后（与另两个表相差较大），参见 [故障排查](troubleshooting.md#数据不同步)。
