# 环保小脑 + 鹊桥 部署指南

## 目录

- [环境要求](#环境要求)
- [快速部署](#快速部署)
- [配置项说明](#配置项说明)
- [首次初始化](#首次初始化)
- [启动 / 停止 / 重启 / 升级](#启动--停止--重启--升级)
- [端口参考](#端口参考)
- [使用外部 MySQL](#使用外部-mysql)

---

## 环境要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| Docker | 24.0+ | 容器运行时 |
| Docker Compose | v2.20+ | 多容器编排（`docker compose` 命令，非 `docker-compose`） |
| 内存 | 4 GB | 三容器合计建议 4 GB 以上 |
| 磁盘 | 10 GB | 截图、台账文档、MySQL 数据卷 |
| CPU | 2 核 | Maven 构建阶段需要，运行时 1 核亦可 |

**依赖服务：**

- **MySQL 8.0** — 可由 Docker Compose 自动部署，也可使用外部实例
- **海康威视 Artemis API** — 摄像头截图所需（网络可达 172.168.97.251 及端口 443）
- **飞书 Webhook**（可选）— 巡检报告通知
- **Python 3** — 仅在 enviro-brain 容器内用于摄像头截图脚本（镜像已内置）
- **OpenCV** — 同上，镜像已内置

---

## 快速部署

### 1. 克隆代码

```bash
git clone <仓库地址> /opt/camera-inspection
cd /opt/camera-inspection
```

### 2. 创建 .env 配置文件

```bash
cp .env.template .env
```

> 若仓库中无 `.env.template`，可参考下方 [配置项说明](#配置项说明) 手动创建 `.env` 文件。

### 3. 编辑 .env

根据实际环境填写以下必填项：

```bash
vim .env
```

**必填项示例：**

```ini
MYSQL_ROOT_PASSWORD=YourStrongPassw0rd
HIKVISION_HOST=172.168.97.251
HIKVISION_APP_KEY=your-app-key
HIKVISION_APP_SECRET=your-app-secret
```

### 4. 启动服务

```bash
docker compose up -d
```

等待约 30–60 秒（首次需下载镜像 + Maven 构建）。

### 5. 验证部署

```bash
# 查看所有容器状态
docker compose ps

# 检查健康状态
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

所有容器状态应为 `Up`，健康检查返回 `{"status":"UP"}`。

---

## 配置项说明

`.env` 文件位于项目根目录，所有配置项如下：

### 数据库配置

| 变量 | 必填 | 默认值 | 说明 | 示例 |
|------|------|--------|------|------|
| `MYSQL_ROOT_PASSWORD` | **是** | — | MySQL root 密码 | `MyStr0ng!Pass` |
| `MYSQL_HOST` | 否 | `mysql` | MySQL 主机地址（容器内服务名/IP） | `mysql` / `192.168.1.100` |

### 海康威视 Artemis API 配置

| 变量 | 必填 | 默认值 | 说明 | 示例 |
|------|------|--------|------|------|
| `HIKVISION_HOST` | **是** | — | Artemis API 主机地址 | `172.168.97.251` |
| `HIKVISION_APP_KEY` | **是** | — | 海康开放平台 AppKey | `a1b2c3d4e5f6...` |
| `HIKVISION_APP_SECRET` | **是** | — | 海康开放平台 AppSecret | `x7y8z9...` |

### 飞书通知（可选）

| 变量 | 必填 | 默认值 | 说明 | 示例 |
|------|------|--------|------|------|
| `FEISHU_WEBHOOK_URL` | 否 | 空 | 飞书机器人 Webhook 地址 | `https://open.feishu.cn/open-apis/bot/v2/hook/xxx` |

### 数据同步（可选）

| 变量 | 必填 | 默认值 | 说明 | 示例 |
|------|------|--------|------|------|
| `QUEQIAO_CALLBACK_URL` | 否 | 空 | enviro-brain 回调鹊桥的地址 | `http://queqiao:8081/api/notify/new-data` |
| `ENVIRO_BRAIN_API_KEY` | 否 | `dev-api-key-2026` | 鹊桥调用 enviro-brain 的 API Key | `prod-api-key-xxx` |
| `QUEQIAO_NOTIFY_API_KEY` | 否 | `queqiao-notify-key-2026` | enviro-brain 回调鹊桥时的校验 Key | `notify-key-xxx` |

### 安全（可选）

| 变量 | 必填 | 默认值 | 说明 | 示例 |
|------|------|--------|------|------|
| `ENVIRO_API_KEY` | 否 | `dev-api-key-2026` | enviro-brain API 端点鉴权 Key | `prod-enviro-key` |
| `QUEQIAO_MCP_API_KEY` | 否 | 空 | MCP 端点 Bearer 鉴权 Key（默认关闭） | `mcp-secret-key` |

### .env 完整模板

```ini
# === MySQL ===
MYSQL_ROOT_PASSWORD=YourStrongPassw0rd

# === 海康威视 Artemis API ===
HIKVISION_HOST=172.168.97.251
HIKVISION_APP_KEY=
HIKVISION_APP_SECRET=

# === 飞书通知（可选）===
FEISHU_WEBHOOK_URL=

# === 数据同步（可选）===
QUEQIAO_CALLBACK_URL=http://queqiao:8081/api/notify/new-data
ENVIRO_BRAIN_API_KEY=dev-api-key-2026
QUEQIAO_NOTIFY_API_KEY=queqiao-notify-key-2026

# === 安全（可选）===
ENVIRO_API_KEY=dev-api-key-2026
QUEQIAO_MCP_API_KEY=
```

---

## 首次初始化

Docker Compose 启动时，MySQL 容器会自动执行 `db/init/01-schema.sql` 初始化脚本（映射至 `/docker-entrypoint-initdb.d`），完成以下操作：

1. 创建数据库 `enviro_brain`（enviro-brain 主库）
2. 创建数据库 `queqiao_sync`（鹊桥同步库）
3. 建表：`camera_config`、`inspection_record`、`camera_result`、`ledger_record`、`sync_watermark`
4. 写入 `sync_watermark` 初始水位记录（各表水位为 0）

**无需手动建库建表。**

> 若使用外部 MySQL，需手动执行 `db/init/01-schema.sql` 完成初始化。

---

## 启动 / 停止 / 重启 / 升级

### 启动

```bash
docker compose up -d
```

### 查看状态

```bash
docker compose ps
```

### 查看日志

```bash
# 全部服务
docker compose logs -f --tail=100

# 指定服务
docker compose logs -f --tail=100 enviro-brain
docker compose logs -f --tail=100 queqiao
docker compose logs -f --tail=100 mysql
```

### 停止

```bash
docker compose stop          # 停止所有容器（保留数据卷）
docker compose down          # 停止并删除容器
docker compose down -v       # ⚠️ 停止并删除容器 + 数据卷（数据会丢失！）
```

### 重启

```bash
docker compose restart                     # 重启所有
docker compose restart enviro-brain        # 重启指定服务
```

### 升级

```bash
# 1. 拉取最新代码
git pull

# 2. 重新构建镜像并启动
docker compose up -d --build

# 3. 确认新版本运行
docker compose ps
curl http://localhost:8080/actuator/info
```

升级不会影响已存截图、台账文件和数据卷。

---

## 端口参考

| 端口 | 服务 | 容器名 | 说明 |
|------|------|--------|------|
| 8080 | enviro-brain | `inspection-enviro-brain` | 巡检核心 REST API + 同步拉取接口 |
| 8081 | queqiao | `inspection-queqiao` | MCP SSE 服务端 + 数据同步层 |
| 3306 | MySQL 8.0 | `inspection-mysql` | 数据库（仅内部访问） |

**端点路径参考：**

| 路径 | 服务 | 说明 |
|------|------|------|
| `GET /actuator/health` | 两者 | 健康检查 |
| `POST /api/v1/inspections/trigger` | enviro-brain | 手动触发巡检 |
| `GET /api/v1/sync/inspections` | enviro-brain | 同步拉取巡检记录 |
| `GET /api/v1/sync/camera-results` | enviro-brain | 同步拉取摄像头结果 |
| `GET /api/v1/sync/ledger-records` | enviro-brain | 同步拉取台账记录 |
| `GET /api/v1/sync/watermark` | enviro-brain | 获取当前同步水位 |
| `POST /api/notify/new-data` | queqiao | 接收巡检完成回调 |
| `GET /mcp/sse` | queqiao | MCP SSE 端点 |

---

## 使用外部 MySQL

若已有 MySQL 8.0 实例，可按以下步骤操作：

### 1. 从 docker-compose.yml 中移除 mysql 服务

编辑 `docker-compose.yml`，删除 `services.mysql` 整段。

### 2. 设置 MYSQL_HOST

在 `.env` 中添加：

```ini
MYSQL_HOST=192.168.1.100   # 外部 MySQL IP 或域名
MYSQL_ROOT_PASSWORD=your-password
```

### 3. 手动初始化数据库

在外部 MySQL 上执行：

```bash
mysql -h <host> -u root -p < db/init/01-schema.sql
```

### 4. 启动（不含 MySQL）

```bash
docker compose up -d
```

> 外部 MySQL 用户需拥有建库、建表、读写权限。建议为每个服务创建独立数据库用户（而非使用 root）。
