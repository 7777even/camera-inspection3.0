# Phase 5 设计：验收上线

> 状态：设计稿（待用户审阅）
> 日期：2026-07-08
> 上游：Phase 4 鹊桥 MCP 封装层（已交付验证完毕）
> 下线：生产环境交付

---

## 1. 目标与定位

将 Phase 1-4 全部交付件打包为可生产部署的服务组，通过端到端联调验证和容错测试，交付操作文档。

### 四个交付物

1. **生产环境部署** — Docker 容器化编排 + 生产配置
2. **端到端联调** — 每小时巡检 → 同步 → MCP → 自然语言查询的全链路冒烟
3. **容错测试** — 环保小脑宕机时鹊桥仍可返回历史数据
4. **操作文档交付** — 部署指南、运维手册、故障排查

---

## 2. 非目标（明确排除）

- 不改造代码架构（不含任何业务逻辑变更）
- 不新增数据库表
- 不新增 MCP 工具
- 不评估 Streamable HTTP 升级（留到独立阶段）

---

## 3. 架构（Docker 容器化）

```
┌─ docker-compose.yml ──────────────────────────────────────┐
│                                                             │
│   ┌──────────────┐    ┌──────────────┐    ┌─────────────┐  │
│   │  MySQL:8.0   │    │ enviro-brain │    │   queqiao   │  │
│   │  (data)      │◄──►│   :8080      │◄──►│   :8081     │  │
│   │              │    │  (巡检核心)   │    │ (MCP+同步)  │  │
│   └──────────────┘    └──────────────┘    └─────────────┘  │
│                                                             │
│   .env 文件: 海康凭证 / 飞书 Webhook / DB 密码              │
└─────────────────────────────────────────────────────────────┘
```

### 容器职责

| 容器 | 基础镜像 | 暴露端口 | 关键配置 |
|------|----------|----------|----------|
| mysql | `mysql:8.0` | 3306 | 持久化卷映射，初始化 SQL |
| enviro-brain | `eclipse-temurin:17-jre` | 8080 | 生产 profile，健康检查 |
| queqiao | `eclipse-temurin:17-jre` | 8081 | 生产 profile，SSE 端点 |

### 网络

- 三个容器在同一 Docker 网络（默认 bridge）
- 服务间通过容器名互访：`enviro-brain:8080`、`queqiao:8081`
- 对外仅暴露 8080 和 8081 端口

---

## 4. 组件设计

### 4.1 Dockerfile — enviro-brain

```dockerfile
# Stage 1: 构建
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: 运行
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/enviro-brain-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
```

### 4.2 Dockerfile — queqiao

```dockerfile
# 结构与上方相似
# --spring.profiles.active=prod
# 依赖 enviro-brain 地址通过环境变量注入
```

### 4.3 docker-compose.yml

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck: ...

  enviro-brain:
    build: ./enviro-brain
    ports: ["8080:8080"]
    env_file: .env
    depends_on: [mysql]
    healthcheck: ...

  queqiao:
    build: ./queqiao
    ports: ["8081:8081"]
    env_file: .env
    depends_on: [mysql, enviro-brain]
    healthcheck: ...
```

### 4.4 application-prod.yml — enviro-brain

```yaml
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

spring:
  datasource:
    url: jdbc:mysql://mysql:3306/enviro_brain?useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_ROOT_PASSWORD}

logging:
  level:
    com.enviro: INFO
```

### 4.5 application-prod.yml — queqiao

```yaml
queqiao:
  sync:
    cron: "0 */30 * * * *"
  mcp:
    auth:
      enabled: false

enviro-brain:
  base-url: http://enviro-brain:8080
  api-key: ${ENVIRO_BRAIN_API_KEY}

spring:
  datasource:
    url: jdbc:mysql://mysql:3306/queqiao_sync?useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_ROOT_PASSWORD}
```

---

## 5. 端到端联调方案

### 验证脚本 `smoke-test.sh`

```
步骤 1: 健康检查
  → curl enviro-brain/actuator/health → UP
  → curl queqiao/actuator/health     → UP

步骤 2: 触发巡检 (MCP)
  → MCP client → trigger_inspection → 返回 taskId

步骤 3: 等待同步 (30分钟或手动触发)
  → 调 queqiao GET /api/notify?syncVersion=xxx 触发增量同步

步骤 4: MCP 查询验证
  → get_inspection_summary → 应有数据
  → get_camera_status     → 应有摄像头列表
  → get_inspection_ledger → 应有台账记录

步骤 5: 输出 PASS/FAIL
```

### 容错测试 A: 环保小脑宕机

```
1. docker stop enviro-brain
2. 调 get_inspection_ledger → 返回历史数据 ✅
3. 调 trigger_inspection  → 返回"环保小脑暂不可用" ✅
4. docker start enviro-brain
5. 等待自动恢复 → 再次 trigger 正常 ✅
```

### 容错测试 B: MySQL 断连（可选）

```
1. 模拟 DB 断连
2. 验证 queqiao 不崩溃
3. 验证服务降级行为
```

---

## 6. 操作文档

| 文档 | 路径 | 内容 |
|------|------|------|
| 部署指南 | `docs/ops/deployment-guide.md` | 环境准备、Docker 部署、配置说明、启动/停止 |
| 运维手册 | `docs/ops/runbook.md` | 日常检查、日志查看、重启流程、备份恢复 |
| 故障排查 | `docs/ops/troubleshooting.md` | 常见问题、诊断步骤、告警处理 |

---

## 7. 交付清单

| # | 交付件 | 说明 |
|---|--------|------|
| 1 | `enviro-brain/Dockerfile` | Multi-stage 构建 |
| 2 | `queqiao/Dockerfile` | Multi-stage 构建 |
| 3 | `docker-compose.yml` | 三容器编排 |
| 4 | `.env.template` | 环境变量模板 |
| 5 | `enviro-brain/application-prod.yml` | 生产配置 |
| 6 | `queqiao/application-prod.yml` | 生产配置 |
| 7 | `scripts/smoke-test.sh` | 端到端冒烟脚本 |
| 8 | `scripts/fault-test.sh` | 容错测试脚本 |
| 9 | `docs/ops/deployment-guide.md` | 部署指南 |
| 10 | `docs/ops/runbook.md` | 运维手册 |
| 11 | `docs/ops/troubleshooting.md` | 故障排查 |

---

## 8. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| MySQL 在生产环境已有部署 | Docker 内 MySQL 与现有冲突 | docker-compose 仅编排服务，MySQL 保留独立部署，通过外部地址连接 |
| 日志丢失 | 容器重启后日志丢失 | 日志卷映射 + Docker 日志驱动 |
| 海康 API 不可达 | 巡检失败 | 已有友好降级（"环保小脑暂不可用"） |
