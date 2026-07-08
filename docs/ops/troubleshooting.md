# 环保小脑 + 鹊桥 故障排查手册

## 目录

- [服务无法启动](#服务无法启动)
- [巡检失败](#巡检失败)
- [数据不同步](#数据不同步)
- [MCP 工具返回空数据](#mcp-工具返回空数据)
- [台账 Word 文档生成失败](#台账-word-文档生成失败)
- [错误代码参考](#错误代码参考)
- [通用排查步骤](#通用排查步骤)

---

## 服务无法启动

### 现象：容器反复重启（STATUS = Restarting）

#### 1. 端口冲突

**症状：** `docker compose ps` 显示端口未绑定，日志包含 `Address already in use`。

**排查：**

```bash
# 检查本地端口占用
netstat -ano | grep -E "8080|8081|3306"

# 找到占用进程
netstat -ano | findstr :8080
```

**解决：**

- 停止占用端口的进程，或更改 docker-compose.yml 中的宿主机端口映射
- 示例：将 enviro-brain 映射到其他端口

```yaml
ports:
  - "8082:8080"   # 宿主机 8082 → 容器 8080
```

#### 2. 数据库连接错误

**症状：** enviro-brain/queqiao 日志包含 `com.mysql.cj.jdbc.exceptions.CommunicationsException` 或 `Access denied`。

**排查：**

```bash
# 检查 MySQL 是否正常运行
docker compose ps mysql

# 若 MySQL 正常，尝试直连
docker exec -it inspection-mysql mysql -uroot -p
```

**解决：**

- 确认 `.env` 中 `MYSQL_ROOT_PASSWORD` 与 docker-compose.yml 一致
- 确认 `MYSQL_HOST` 正确（容器内使用服务名 `mysql`，非 `localhost`）
- 首次启动需等待 MySQL 初始化完成（约 10–20 秒），enviro-brain 会通过 `depends_on.condition: service_healthy` 自动等待

#### 3. Docker 守护进程未运行

**症状：** `docker compose up -d` 返回 `Cannot connect to the Docker daemon`。

**解决：**

```bash
# Windows Docker Desktop
# 检查 Docker Desktop 是否启动

# Linux
systemctl status docker
sudo systemctl restart docker
```

#### 4. Docker 资源不足

**症状：** 日志包含 `Cannot start service: OCI runtime create failed` 或 container 退出码 137（OOMKilled）。

**解决：**

- 确保 Docker Desktop / 宿主机至少分配 4 GB 内存
- 使用 `docker stats` 查看各容器资源占用

```bash
docker stats
```

---

## 巡检失败

### 现象：inspection_record.status = RUNNING 长期未变，或 COMPLETED 但全部摄像头为 offline

#### 1. 海康 API 凭证错误

**症状：** 日志包含 `401 Unauthorized`、`Invalid AppKey`、`signature invalid`。

**排查：**

```bash
# 确认 .env 中 HIKVISION_HOST / APP_KEY / APP_SECRET 已正确填写
docker compose exec enviro-brain env | grep HIKVISION

# 查看截图相关日志
docker compose logs enviro-brain | grep -i "CaptureService\|hikvision\|hik"
```

**解决：**

- 登录海康开放平台确认 AppKey/AppSecret 未过期
- 确认 `HIKVISION_HOST` 指向正确的 Artemis API 地址

#### 2. 网络不可达 172.168.97.251

**症状：** 日志包含 `Connection timed out`、`Connection refused`、`Network is unreachable`。

**排查：**

```bash
# 从 enviro-brain 容器内测试网络连通性
docker compose exec enviro-brain curl -s -o /dev/null -w "%{http_code}" \
  https://172.168.97.251/artemis -k || echo "无法连接"

# 如果容器内无 curl 可用 ping 测试
docker compose exec enviro-brain ping -c 3 172.168.97.251
```

**解决：**

- 确认宿主机可访问海康内网（VPN 或专线）
- 确认防火墙放行 443 端口
- 若海康 API 使用自签名证书，enviro-brain 已配置跳过证书验证

#### 3. 截图脚本异常

**症状：** 日志包含 `Python script failed`、`OpenCV error`、`FFmpeg not found`。

**排查：**

```bash
docker compose logs enviro-brain | grep "camera_capture"
```

**解决：**

- 确认容器镜像已正确安装 Python 3 及 OpenCV（使用预构建镜像）
- 查看 `scripts/camera_capture.py` 是否正确部署

#### 4. Maven 构建失败导致镜像不可用

**症状：** `docker compose up -d --build` 构建阶段报错。

**排查：**

```bash
# 单独构建查看详细日志
docker compose build --no-cache enviro-brain
```

**解决：**

- 确保网络可访问 Maven Central
- 若使用代理，配置 Maven 代理设置

---

## 数据不同步

### 现象：queqiao 的 synced_* 表数据与 enviro-brain 原始表不一致

#### 1. sync_version 水位不一致

**症状：** `sync_watermark` 表中某个表的水位明显低于其他表。

**排查：**

```sql
-- 对比 enviro-brain 原始水位
SELECT table_name, last_sync_version FROM queqiao_sync.sync_watermark;

-- 查看 enviro-brain 侧最高 sync_version
SELECT MAX(sync_version) FROM enviro_brain.inspection_record;
SELECT MAX(sync_version) FROM enviro_brain.camera_result;
SELECT MAX(sync_version) FROM enviro_brain.ledger_record;
```

**解决：**

- 等待下一轮定时同步（30 分钟内自动恢复）
- 或重启 queqiao 立即触发同步

```bash
docker compose restart queqiao
```

#### 2. 回调 URL 配置错误

**症状：** enviro-brain 巡检完成后，queqiao 侧数据未及时更新，日志中有 `[QueqiaoNotify] 回调失败`。

**排查：**

```bash
docker compose logs enviro-brain | grep "QueqiaoNotify"

# 若显示 "回调失败"，检查 callback-url
docker compose exec enviro-brain env | grep QUEQIAO_CALLBACK
```

**解决：**

- 确认 `.env` 中 `QUEQIAO_CALLBACK_URL` 填写正确：

```ini
QUEQIAO_CALLBACK_URL=http://queqiao:8081/api/notify/new-data
```

- 由于 queqiao 和 enviro-brain 在同一 Docker 网络，使用服务名 `queqiao` 而非 IP

#### 3. 同步接口鉴权失败

**症状：** 同步日志包含 `401 Unauthorized`。

**排查：**

```bash
docker compose logs queqiao | grep "sync\|error\|401"
```

**解决：**

- 确认 `ENVIRO_BRAIN_API_KEY` 与 enviro-brain 的 `ENVIRO_API_KEY` 一致
- 或查看 `enviro-brain/src/main/java/com/enviro/brain/config/WebMvcConfig.java` 中的拦截路径

#### 4. 同步服务未启用

**症状：** queqiao 日志无 `[sync]` 相关输出。

**排查：**

```bash
# 确认调度器是否激活
docker compose logs queqiao | grep "scheduler"
```

**解决：**

- 确认 `application-prod.yml` 中存在 `queqiao.sync.cron` 配置
- 若手动停用了调度，检查代码中 `@EnableScheduling` 注解是否存在

---

## MCP 工具返回空数据

### 1. 数据未同步（同步延迟）

**症状：** 巡检已执行完毕，但 `get_inspection_ledger` 返回空。

**排查：**

```sql
-- 检查同步水位
SELECT * FROM queqiao_sync.sync_watermark;
```

**解决：**

- 等待定时同步（最长 30 分钟），或触发回调 / 重启 queqiao 强制同步
- 确认 `QUEQIAO_CALLBACK_URL` 已配置

### 2. inspectId 不存在

**症状：** `download_ledger_docx` 返回 `inspectId not found`。

**排查：**

```bash
# 查看所有巡检记录
docker exec inspection-mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD} \
  -e "SELECT id, inspection_date, status, batch_id FROM enviro_brain.inspection_record ORDER BY id DESC LIMIT 10;"
```

**解决：**

- 传入正确的 `inspectId`
- 刚触发的巡检可能仍在 `RUNNING` 状态，等待完成后再查询

### 3. MCP SSE 连接失败

**症状：** MCP 客户端无法连接端点。

**排查：**

```bash
# 检查 queqiao 端口监听
curl -v http://localhost:8081/mcp/sse

# 预期应返回 200 并开始 SSE 流（text/event-stream）
```

**解决：**

- 确认 queqiao 正常运行：`docker compose ps queqiao`
- 确认宿主机 8081 端口已映射

### 4. 数据库连接丢失

**症状：** MCP 调用返回 `Database error` 或 `Connection refused`。

**排查：**

```bash
docker compose logs queqiao | grep -i "datasource\|hikari\|CommunicationsException"
```

**解决：**

- 检查 MySQL 容器状态：`docker compose ps mysql`
- 若 MySQL 重启，queqiao 的 HikariCP 连接池会自动重连

---

## 台账 Word 文档生成失败

### 1. 模板文件缺失

**症状：** 日志包含 `template not found`、`FileNotFoundException`。

**排查：**

```bash
# 检查容器内模板文件是否存在
docker compose exec enviro-brain ls -la /app/templates/
```

**预期输出：** 应包含 `危废仓库巡查台账_新模版.docx`。

**解决：**

- 确认 `enviro-brain/templates/` 目录存在于项目源码中
- 确认 Dockerfile 中已将模板复制到镜像（`COPY templates ./templates`）
- 若自定义模板路径，修改 `enviro.ledger.template-path`

### 2. 截图路径破损

**症状：** 台账文档中截图显示为空白或红叉，日志包含 `image not found`、`invalid screenshot path`。

**排查：**

```bash
# 检查截图存储卷
docker run --rm -v screenshots-data:/data alpine ls /data/

# 查看截图目录是否可写
docker compose exec enviro-brain ls -la /data/screenshots/
```

**解决：**

- 确保 `screenshots-data` 数据卷已挂载且可写
- 若磁盘空间满，清理旧截图后重新执行巡检

### 3. 磁盘空间不足

**症状：** 日志包含 `No space left on device`、`Disk quota exceeded`。

**排查：**

```bash
docker compose exec enviro-brain df -h /data
```

**解决：**

- 清理旧截图和台账文件：

```bash
# 清理 30 天前的旧截图
docker compose exec enviro-brain find /data/screenshots -type f -mtime +30 -delete
```

- 或扩大 Docker 磁盘配额

---

## 错误代码参考

以下为 enviro-brain 和 queqiao 在运行过程中可能输出的关键错误信息：

| 错误类型 | 日志关键词 | 含义 | 处理方式 |
|---------|-----------|------|---------|
| 数据库连接失败 | `CommunicationsException` | MySQL 不可达 | 检查 MySQL 容器状态和网络 |
| 认证失败 | `Access denied for user` | MySQL 密码错误 | 检查 `MYSQL_ROOT_PASSWORD` |
| 海康鉴权 | `401 Unauthorized` | 海康 API 凭证无效 | 检查 `HIKVISION_APP_KEY/SECRET` |
| 海康请求超时 | `ConnectException: Connection timed out` | 网络无法到达海康 | 检查网络/防火墙 |
| 巡检超时 | `TimeoutException` | 单路截图超 120 秒 | 检查摄像头 RTSP 可用性 |
| 同步失败 | `[sync] 同步过程中发生异常` | 某表同步异常 | 查看具体异常详情 |
| 回调失败 | `[QueqiaoNotify] 回调失败` | enviro-brain 无法回调 queqiao | 检查 `QUEQIAO_CALLBACK_URL` |
| 水位异常 | `游标退化` | queqiao 游标无法推进 | 检查同步 API 返回值 |
| 模板缺失 | `FileNotFoundException` | docx 模板文件未找到 | 检查模板文件路径 |
| MCP 客户端握手失败 | `404 Not Found` | SSE 端点路径错误 | 确认 `/mcp/sse` 路径正确 |
| 版本号冲突 | `Duplicate entry` | 幂等写入异常 | 检查 upsert SQL 语句 |
| 内存不足 | `java.lang.OutOfMemoryError` | JVM 堆内存不足 | 增大 Docker 内存限制 |

---

## 通用排查步骤

当遇到不明问题时，按以下步骤逐步排查：

```bash
# Step 1: 查看整体状态
docker compose ps
docker compose logs --tail=50

# Step 2: 检查数据库连通性
docker compose exec enviro-brain curl -s http://localhost:8080/actuator/health | jq .

# Step 3: 检查关键环境变量
docker compose exec enviro-brain env | grep -E "HIKVISION|MYSQL|FEISHU|QUEQIAO"

# Step 4: 查看最近一次巡检情况（enviro-brain）
docker exec inspection-mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD} \
  -e "SELECT id, inspection_date, status, total_cameras, online_count, offline_count, abnormal_count, sync_version \
      FROM enviro_brain.inspection_record ORDER BY id DESC LIMIT 1;"

# Step 5: 查看同步状态（queqiao）
docker exec inspection-mysql mysql -uroot -p${MYSQL_ROOT_PASSWORD} \
  -e "SELECT * FROM queqiao_sync.sync_watermark;"

# Step 6: 如果仍无法解决，导出全量日志
docker compose logs --tail=500 enviro-brain > /tmp/enviro-brain.log
docker compose logs --tail=500 queqiao > /tmp/queqiao.log
```

获取日志后，可提供给开发团队进一步分析。
