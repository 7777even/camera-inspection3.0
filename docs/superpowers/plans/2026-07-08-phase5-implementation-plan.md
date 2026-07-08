# Phase 5 验收上线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Phase 1-4 交付件打包为可生产部署的 Docker 服务组，完成端到端联调和操作文档交付。

**Architecture:** 三容器 Docker 编排（MySQL + enviro-brain + queqiao），生产配置通过 `.env` 和环境变量注入，操作文档存放于 `docs/ops/`。

**Tech Stack:** Docker Compose v3, Maven 3.9, Eclipse Temurin 17 JRE, MySQL 8.0

## Global Constraints

- 不修改任何 Java 源代码（不含业务逻辑变更）
- 所有敏感信息通过环境变量注入（不硬编码在配置文件中）
- 两个服务的 `application-prod.yml` 放在各自 `src/main/resources/` 下
- 操作文档使用中文
- Dockerfile 使用 multi-stage 构建

---

### Task 1: 生产配置 application-prod.yml

**Files:**
- Create: `enviro-brain/src/main/resources/application-prod.yml`
- Create: `queqiao/src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: 已有 application.yml 默认配置
- Produces: Task 2/3 依赖的配置文件

- [ ] **Step 1: 创建 enviro-brain 生产配置**

```yaml
# enviro-brain/src/main/resources/application-prod.yml
enviro:
  hikvision:
    host: ${HIKVISION_HOST}
    app-key: ${HIKVISION_APP_KEY}
    app-secret: ${HIKVISION_APP_SECRET}
  feishu:
    webhook-url: ${FEISHU_WEBHOOK_URL}
  inspection:
    concurrency: 12
    capture-timeout-seconds: 120
  template-path: templates/危废仓库巡查台账_新模版.docx
  screenshots:
    dir: /data/screenshots
  ledger:
    dir: /data/ledger
    template-path: templates/危废仓库巡查台账_新模版.docx

spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:mysql}:3306/enviro_brain?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8
    username: root
    password: ${MYSQL_ROOT_PASSWORD}
    hikari:
      maximum-pool-size: 20

server:
  port: 8080

logging:
  level:
    com.enviro: INFO
    org.springframework: WARN
```

- [ ] **Step 2: 创建 queqiao 生产配置**

```yaml
# queqiao/src/main/resources/application-prod.yml
queqiao:
  sync:
    cron: "0 */30 * * * *"
    batch-limit: 200
  mcp:
    auth:
      enabled: false

enviro-brain:
  base-url: http://${ENVIRO_BRAIN_HOST:enviro-brain}:8080
  api-key: ${ENVIRO_BRAIN_API_KEY}

spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:mysql}:3306/queqiao_sync?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8
    username: root
    password: ${MYSQL_ROOT_PASSWORD}
    hikari:
      maximum-pool-size: 10

server:
  port: 8081

logging:
  level:
    com.queqiao: INFO
    org.springframework: WARN

management:
  endpoints:
    web:
      exposure:
        include: health, info, liveness, readiness
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 3: 提交**

```bash
git add enviro-brain/src/main/resources/application-prod.yml
git add queqiao/src/main/resources/application-prod.yml
git commit -m "feat(phase5): 生产配置 application-prod.yml（环境变量注入 + 日志级别 + 连接池）"
```

---

### Task 2: Dockerfile 构建

**Files:**
- Create: `enviro-brain/Dockerfile`
- Create: `queqiao/Dockerfile`

**Interfaces:**
- Consumes: Task 1 配置文件
- Produces: Task 3 依赖的 Docker 镜像

- [ ] **Step 1: 创建 enviro-brain/Dockerfile**

```dockerfile
# Stage 1: 构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 先下载依赖（利用 Docker 缓存层）
COPY pom.xml .
RUN mvn dependency:resolve

# 编译打包
COPY src ./src
COPY templates ./templates
RUN mvn package -DskipTests

# Stage 2: 运行
FROM eclipse-temurin:17-jre
WORKDIR /app

# 创建数据目录
RUN mkdir -p /data/screenshots /data/ledger

# 复制构建产物和模板
COPY --from=build /app/target/enviro-brain-*.jar app.jar
COPY --from=build /app/templates ./templates

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -s http://localhost:8080/actuator/health | grep UP || exit 1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
```

- [ ] **Step 2: 创建 queqiao/Dockerfile**

```dockerfile
# Stage 1: 构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:resolve

COPY src ./src
COPY templates ./templates
RUN mvn package -DskipTests

# Stage 2: 运行
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/queqiao-*.jar app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -s http://localhost:8081/actuator/health | grep UP || exit 1

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
```

- [ ] **Step 3: 验证 Dockerfile 语法**

```bash
cd enviro-brain && docker build -t enviro-brain:test . 2>&1 | tail -5
cd ../queqiao && docker build -t queqiao:test . 2>&1 | tail -5
```

- [ ] **Step 4: 提交**

```bash
git add enviro-brain/Dockerfile queqiao/Dockerfile
git commit -m "feat(phase5): Dockerfile multi-stage 构建（enviro-brain + queqiao）"
```

---

### Task 3: docker-compose 编排

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.template`

**Interfaces:**
- Consumes: Task 1/2 的 Dockerfile 和配置文件
- Produces: 一键部署能力

- [ ] **Step 1: 创建 .env.template**

```bash
# 环境变量模板 — 复制为 .env 并填入实际值
# cp .env.template .env

# MySQL
MYSQL_ROOT_PASSWORD=change_me
MYSQL_HOST=mysql

# 海康威视 Artemis API
HIKVISION_HOST=172.168.97.251
HIKVISION_APP_KEY=your_app_key
HIKVISION_APP_SECRET=your_app_secret

# 飞书机器人 Webhook
FEISHU_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/your_webhook

# 鹊桥 API 密钥
ENVIRO_BRAIN_API_KEY=dev-api-key-2026
```

- [ ] **Step 2: 创建 docker-compose.yml**

```yaml
version: "3.8"

services:
  mysql:
    image: mysql:8.0
    container_name: inspection-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
      - ./db/init:/docker-entrypoint-initdb.d:ro
    ports:
      - "3306:3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 15s
      timeout: 5s
      retries: 5

  enviro-brain:
    build:
      context: ./enviro-brain
      dockerfile: Dockerfile
    container_name: inspection-enviro-brain
    restart: unless-stopped
    env_file: .env
    environment:
      MYSQL_HOST: mysql
    depends_on:
      mysql:
        condition: service_healthy
    ports:
      - "8080:8080"
    volumes:
      - screenshots-data:/data/screenshots
      - ledger-data:/data/ledger
    healthcheck:
      test: ["CMD", "curl", "-s", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  queqiao:
    build:
      context: ./queqiao
      dockerfile: Dockerfile
    container_name: inspection-queqiao
    restart: unless-stopped
    env_file: .env
    environment:
      MYSQL_HOST: mysql
      ENVIRO_BRAIN_HOST: enviro-brain
    depends_on:
      mysql:
        condition: service_healthy
      enviro-brain:
        condition: service_healthy
    ports:
      - "8081:8081"
    healthcheck:
      test: ["CMD", "curl", "-s", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3

volumes:
  mysql-data:
  screenshots-data:
  ledger-data:
```

- [ ] **Step 3: 创建 db/init 初始化脚本目录**

```bash
mkdir -p db/init
```

创建 `db/init/01-schema.sql`，内容为两个数据库（enviro_brain + queqiao_sync）的建库建表语句（从当前 MySQL 导出）。

**关键说明：** 如果生产环境已有独立 MySQL，container 中的 mysql 服务可移除，仅保留两个应用服务，通过 `.env` 的 `MYSQL_HOST` 指向外部 MySQL。

- [ ] **Step 4: 提交**

```bash
git add docker-compose.yml .env.template db/
git commit -m "feat(phase5): docker-compose 三容器编排 + .env 模板 + 初始化 SQL"
```

---

### Task 4: 端到端冒烟脚本 + 容错测试脚本

**Files:**
- Create: `scripts/smoke-test.sh`
- Create: `scripts/fault-test.sh`

**Interfaces:**
- Consumes: Task 3 部署的服务
- Produces: 联调验证报告

- [ ] **Step 1: 创建 smoke-test.sh**

```bash
#!/bin/bash
# 端到端冒烟脚本 — 验证 Phase 5 全链路
# 使用方式: bash scripts/smoke-test.sh

set -euo pipefail
PASS=0
FAIL=0

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
TASK_ID=$(python3 -c "
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
curl -sf "http://localhost:8081/api/notify?syncVersion=$LATEST_SYNC" > /dev/null 2>&1 || true
sleep 5

# 5. MCP 查询验证
echo ""
echo "--- 5. MCP 查询验证 ---"
SUMMARY=$(python3 -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('get_inspection_summary', {
                'start_date': '$(date +%Y-%m-%d)',
                'end_date': '$(date +%Y-%m-%d)'
            })
            print(res.content[0].text[:100])
asyncio.run(t())
" 2>/dev/null)
check "get_inspection_summary 有数据" echo "$SUMMARY" | grep -q "onlineRate\|total\|data"

echo ""
echo "=== 结果: $PASS 通过, $FAIL 失败 ==="
exit $FAIL
```

- [ ] **Step 2: 创建 fault-test.sh**

```bash
#!/bin/bash
# 容错测试脚本 — 验证环保小脑宕机场景
set -euo pipefail

echo "=== Phase 5 容错测试 ==="
echo ""

echo "--- 场景 A: 环保小脑宕机 ---"

# 1. 记录宕机前数据可查
echo "1) 宕机前: MCP 查询正常"
BEFORE=$(python3 -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('get_camera_status', {})
            has_data = len(res.content[0].text) > 50
            print('HAS_DATA' if has_data else 'NO_DATA')
asyncio.run(t())
" 2>/dev/null)
echo "  ${BEFORE}"

# 2. 停止 enviro-brain
echo "2) 停止 enviro-brain..."
docker stop inspection-enviro-brain 2>/dev/null || echo "  (容器未运行，跳过)"
echo "  ✅ 已停止"

# 3. 验证查询类工具仍可用
echo "3) 查询类工具应返回历史数据"
AFTER_STOP=$(python3 -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('get_inspection_ledger', {})
            has_data = 'data' in res.content[0].text
            print('HAS_DATA' if has_data else 'NO_DATA')
asyncio.run(t())
" 2>/dev/null)
echo "  get_inspection_ledger: ${AFTER_STOP}"

# 4. 验证操作类工具返回友好错误
echo "4) trigger_inspection 应返回友好错误"
ERROR_MSG=$(python3 -c "
import asyncio
from mcp.client.sse import sse_client
from mcp.client.session import ClientSession
async def t():
    async with sse_client('http://localhost:8081/mcp/sse') as (r,w):
        async with ClientSession(r,w) as s:
            await s.initialize()
            res = await s.call_tool('trigger_inspection', {'reason': '容错测试'})
            print(res.content[0].text[:100])
asyncio.run(t())
" 2>/dev/null)
echo "  返回: ${ERROR_MSG}"

# 5. 恢复 enviro-brain
echo "5) 恢复 enviro-brain..."
docker start inspection-enviro-brain 2>/dev/null || echo "  (未使用容器部署，跳过)"
echo "  ✅ 完成"

echo ""
echo "=== 容错测试完成 ==="
```

- [ ] **Step 3: 提交**

```bash
chmod +x scripts/smoke-test.sh scripts/fault-test.sh
git add scripts/
git commit -m "feat(phase5): 端到端冒烟脚本 + 容错测试脚本"
```

---

### Task 5: 操作文档交付

**Files:**
- Create: `docs/ops/deployment-guide.md`
- Create: `docs/ops/runbook.md`
- Create: `docs/ops/troubleshooting.md`

- [ ] **Step 1: 创建部署指南**

`docs/ops/deployment-guide.md` — 内容涵盖：
- 环境要求（Docker、Docker Compose 版本）
- 部署步骤（克隆 → 配置 .env → docker-compose up）
- 首次初始化说明（MySQL 初始化 SQL）
- 配置项说明（.env 各字段含义）
- 启动/停止/重启命令
- 升级步骤（重新构建 + 重启）
- 端口说明

- [ ] **Step 2: 创建运维手册**

`docs/ops/runbook.md` — 内容涵盖：
- 日常检查命令（健康检查、日志查看、数据验证）
- 日志查看方法（docker logs -f）
- 重启流程（docker-compose restart）
- 备份恢复（MySQL 备份）
- 巡检自动调度说明（每小时巡检、每30分钟同步）
- MCP 工具列表和使用示例

- [ ] **Step 3: 创建故障排查**

`docs/ops/troubleshooting.md` — 内容涵盖：
- 服务无法启动（端口冲突、配置错误）
- 巡检失败（海康 API 凭证错误、网络不可达）
- 数据不同步（版本号不一致、同步日志）
- MCP 工具返回空数据（同步延时、数据库连接）
- 常见错误码对照表

- [ ] **Step 4: 提交**

```bash
git add docs/ops/
git commit -m "docs(phase5): 操作文档 - 部署指南 + 运维手册 + 故障排查"
```

---

### Task 6: 端到端验证

**Files:** 无（仅执行验证）

- [ ] **Step 1: 本地验证 Docker 构建**

```bash
docker build -t enviro-brain:phase5 ./enviro-brain
docker build -t queqiao:phase5 ./queqiao
```

- [ ] **Step 2: 运行冒烟测试**

```bash
bash scripts/smoke-test.sh
```

- [ ] **Step 3: 运行容错测试（可选，手动执行）**

```bash
bash scripts/fault-test.sh
```

- [ ] **Step 4: 最终提交**

```bash
git add -A
git commit -m "chore(phase5): 验收上线全部交付"
```
