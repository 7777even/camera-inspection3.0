# Phase 2: 环保小脑巡检核心 — 设计文档

> 基于 v3.0 方案第十四节 [Phase 2 实施路线](#)，覆盖巡检调度、截图、台账、通知全链路。
> Phase 1 已完成基础设施（Spring Boot + DB + API Key + 同步接口），Phase 2 在此基础上构建巡检执行引擎。

## 1. 整体架构

```
┌─ 触发入口 ─────────────────────────────────────────────────────┐
│  @Scheduled(cron="0 0 * * * ?")  每小时整点                      │
│  POST /api/v1/inspections/trigger  手动触发                      │
└────────────────────┬───────────────────────────────────────────┘
                     ▼
┌──── InspectionService（核心编排）───────────────────────────────┐
│  ① 读 CameraConfig 表 → 获取启用的摄像头列表 (findActive)         │
│  ② nextVersion() → 获取全局同步版本号                            │
│  ③ 创建 InspectionRecord (status=running)                      │
│  ④ ThreadPoolExecutor(4) 并发 → CaptureService.capture() × N   │
│  ⑤ 汇总统计 → 更新 InspectionRecord (status=completed)          │
│  ⑥ LedgerService.generateAndSave() → Apache POI 生成台账 docx   │
│  ⑦ FeishuNotifyService.sendReport() → 飞书卡片推送              │
│  ⑧ (可选) QueqiaoNotifyService.notifyNewData()                  │
└────────────────────────────────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   CaptureService  LedgerService  FeishuNotifyService
   (ProcessBuilder  (Apache POI    (RestTemplate →
    调 Python)      填充模板)      飞书 Webhook)
        │
        ▼
   scripts/camera_capture.py
   (RTSP 截图 + 质量检测 → 返回 JSON)
```

## 2. 组件设计

### 2.1 CaptureService

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.service.CaptureService` |
| **职责** | 封装 ProcessBuilder 调 Python 截图脚本，解析 JSON 输出 |
| **输入** | `CameraConfig` (cameraCode, cameraName, artemisDeviceId) |
| **输出** | `CameraCaptureResult` (内部 DTO: status, qualityScore, screenshotPath, errorMsg, captureTime, retryUsed) |

**调用命令**:
```bash
python3 scripts/camera_capture.py \
  --host ${enviro.hikvision.host} \
  --port ${enviro.hikvision.port} \
  --app-key ${enviro.hikvision.app-key} \
  --app-secret ${enviro.hikvision.app-secret} \
  --camera-code CAM001 \
  --camera-name "危废仓库1" \
  --save-dir ${enviro.screenshots.dir} \
  --timeout ${enviro.hikvision.timeout} \
  --retry-count ${enviro.hikvision.retry-count} \
  --warmup ${enviro.hikvision.warmup-seconds} \
  --json
```

**参数说明**:
| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `enviro.hikvision.host` | (必填) | 海康平台地址 |
| `enviro.hikvision.port` | 443 | 海康平台端口 |
| `enviro.hikvision.app-key` | (必填) | Artemis appKey |
| `enviro.hikvision.app-secret` | (必填) | Artemis appSecret |
| `enviro.hikvision.timeout` | 15 | RTSP 超时(秒) |
| `enviro.hikvision.retry-count` | 3 | 截图重试次数 |
| `enviro.hikvision.warmup-seconds` | 5.0 | OpenCV 预热秒数 |
| `enviro.hikvision.api-path` | /artemis | Artemis API 前缀 |
| `enviro.python.path` | python3 | Python 可执行文件 |
| `enviro.python.script-path` | scripts/camera_capture.py | 截图脚本路径 |
| `enviro.screenshots.dir` | ./screenshots | 截图保存根目录 |

**JSON 输出解析**:
```json
{
  "status": "online" | "offline" | "abnormal",
  "screenshotPath": "screenshots/20260706/CAM001_150123.jpg",
  "qualityScore": 0.82,
  "qualityDetail": {"laplacianScore": 0.75, "brightnessScore": 0.80, "colorDiversityScore": 0.85},
  "errorMsg": null,
  "captureTime": "2026-07-06 15:01:23",
  "retryUsed": 0,
  "rtspUrl": "rtsp://...",
  "totalFrames": 150
}
```

**错误处理**:
- Python 进程超时 60s → `ProcessBuilder.destroyForcibly()` → status=timeout
- Python 返回非0退出码 → 读取 stderr → status=error
- JSON 解析失败 → 包装 errorMsg="截图结果解析失败"

### 2.2 InspectionService

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.service.InspectionService` |
| **职责** | 巡检编排：读配置 → 并发截图 → 汇总入库 → 台账 + 通知 |
| **事务** | `@Transactional` 保护核心写入（第③-⑥步） |

**核心流程 (`executeInspection(triggerType) -> Long inspectId`)**:

```
① cameraConfigService.findActive(0, 10000) → List<CameraConfig>
② syncVersionService.nextVersion() → Long version
③ 创建 InspectionRecord (status=running, syncVersion=version) → insert
④ ThreadPoolExecutor:
     cores=4, max=4, queue=无界LinkedBlockingQueue, timeout=60s/路
     futures = cameras.forEach(c -> pool.submit(() -> captureService.capture(c)))
⑤ 收集结果:
     for each Future: result = future.get(60s, SECONDS)
     batch buildCameraResult(entity, result, version)
     cameraResultMapper.batchInsert(results)
⑥ 汇总统计:
     onlineCount = results.stream().filter("online").count()
     offlineCount = ...
     abnormalCount = ...
     更新 InspectionRecord (status=completed, counts)
⑦ ledgerService.generateAndSave(inspectId, results filtered by registerToLedger, version)
    → 生成 DOCX + 写 ledger_records
⑧ feishuNotifyService.sendInspectionReport(record, results)
⑨ (可选) queqiaoNotifyService.notifyNewData(version)
```

**线程池配置**:
```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    4, 4, 60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(),  // 无界队列，允许所有任务排队
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

**并发超时保护**: 单路 `future.get(60, SECONDS)` 超时后 cancel(true)，该路结果标记为 timeout。

### 2.3 FeishuNotifyService

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.service.FeishuNotifyService` |
| **配置** | `enviro.feishu.webhook-url` (可为空，为空时跳过通知) |

**飞书卡片消息格式**:
```json
{
  "msg_type": "interactive",
  "card": {
    "header": {
      "title": {"tag": "plain_text", "content": "🔔 危废仓库摄像头巡检报告"},
      "template": "red"
    },
    "elements": [
      {"tag": "div", "text": {"tag": "lark_md", "content": "**巡检时间**：2026-07-06 15:00:05"}},
      {"tag": "div", "text": {"tag": "lark_md", "content": "**概况**：总 25 路 | 🟢 在线 22 | 🔴 离线 1 | 🟡 异常 2"}},
      {"tag": "hr"},
      {"tag": "div", "text": {"tag": "lark_md", "content": "**离线/异常详情**：\n- 华达通危废仓3：离线\n- 宙邦危废仓2：异常（质量评分 0.31）"}},
      {"tag": "note", "elements": [{"tag": "plain_text", "content": "台账已生成 18 条记录"}]}
    ]
  }
}
```

### 2.4 LedgerService

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.service.LedgerService` |
| **模板** | `C:\Users\7even\Downloads\危废仓库巡查台账_新模版.docx` |
| **输出目录** | `enviro.ledger.dir` (默认 `./ledger`) |

**生成流程**:
```
① 打开模板: XWPFDocument doc = new XWPFDocument(new FileInputStream(templatePath))
② 定位表格: XWPFTable table = doc.getTables().get(0)
③ 逐行填充 (从第2行开始):
     行.createCell(0).setText(seqNo)       // 序号
     行.createCell(1).setText(enterprise)  // 企业名称
     行.createCell(2).setText(cameraName)  // 摄像头名称
     行.createCell(3).setText(overThreshold) // 是否超阈值
     行.createCell(4).setText(abnormalNote)  // 异常情况
     行.createCell(5): XWPFRun.addPicture(截图) // 嵌入式截图
④ 保存: doc.write(new FileOutputStream(outputPath))
⑤ 写 ledger_records 表: LedgerRecord(inspectId, date, seqNo, ..., docxPath, syncVersion)
```

**写入判断**: `registerToLedger` 条件——status 为 offline 或 abnormal，或 qualityScore < 0.5。

### 2.5 InspectionController

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.controller.InspectionController` |
| **路由** | `/api/v1/inspections` |

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/trigger` | 手动触发巡检（异步执行），返回 `{"taskId": 42, "status": "running"}` |
| GET | `/{id}/ledger/download` | 下载台账 DOCX 文件（Content-Disposition: attachment） |

**手动触发逻辑**:
```
POST /api/v1/inspections/trigger
→ inspectionService.executeInspection("manual")
→ 异步执行（不阻塞 HTTP 响应，202 Accepted）
→ 立即返回 taskId
```

### 2.6 QueqiaoNotifyService（可选）

| 项目 | 内容 |
|------|------|
| **路径** | `com.enviro.brain.service.QueqiaoNotifyService` |
| **配置** | `enviro.queqiao.callback-url` (可为空，为空时跳过) |

当回调 URL 已配置时，巡检完成后 POST 通知鹊桥：
```json
POST {callback-url}/api/notify/new-data
{"syncVersion": 1709280001234, "type": "inspection_completed"}
```

## 3. 数据流

```
巡检开始
  │
  ├── [DB] INSERT inspection_records (status=running, sync_version=xxx)
  │
  ├── [Python × N] ProcessBuilder → camera_capture.py
  │        └── [Disk] 截图保存到 screenshots/{date}/{cameraCode}_{timestamp}.jpg
  │
  ├── [DB] INSERT camera_results × N (sync_version=xxx)
  │
  ├── [DB] UPDATE inspection_records (status=completed, stats)
  │
  ├── [DB] INSERT ledger_records × M (sync_version=xxx)
  │        └── [Disk] 台账 DOCX 保存到 ledger/{date}/台账_{date}.docx
  │
  └── [HTTP] POST 飞书 Webhook (卡片通知)
       └── [HTTP] POST 鹊桥回调 (可选)
```

## 4. 配置清单

在 `application.yml` 中新增以下配置节：

```yaml
enviro:
  hikvision:
    host: ${HIKVISION_HOST:}
    port: 443
    app-key: ${HIKVISION_APP_KEY:}
    app-secret: ${HIKVISION_APP_SECRET:}
    api-path: /artemis
    timeout: 15
    retry-count: 3
    warmup-seconds: 5.0
  python:
    path: python3
    script-path: scripts/camera_capture.py
  screenshots:
    dir: ./screenshots
  ledger:
    dir: ./ledger
    template-path: templates/危废仓库巡查台账_新模版.docx
  feishu:
    webhook-url: ${FEISHU_WEBHOOK_URL:}
  queqiao:
    callback-url: ${QUEQIAO_CALLBACK_URL:}
  inspection:
    concurrency: 4
    capture-timeout-seconds: 60
```

> 带 `${ENV_VAR:default}` 的配置支持环境变量覆盖，便于 K8s/Docker 部署。默认空字符串表示功能禁用（如飞书通知、鹊桥回调）。

## 5. 错误处理矩阵

| 场景 | 处理策略 |
|------|---------|
| camera_capture.py 脚本不存在 | 启动检查（见6节），该路结果 status=error |
| 单路截图超时(60s) | ProcessBuilder.destroyForcibly(), status=timeout, 不阻塞其他路 |
| 单路 Python 进程异常退出 | 捕获 exitCode ≠ 0, status=error, errorMsg=stderr |
| 全部摄像头离线/超时 | InspectionRecord status=completed, 飞书通知 "0在线" |
| 飞书 Webhook 不可达 | catch + log.error, 不阻塞巡检流程 |
| DOCX 模板文件缺失 | catch + log.error, 台账生成跳过, 不影响巡检 |
| 线程池满 (CallerRunsPolicy) | 主线程同步执行该路截图, 降级但不丢失 |
| Transaction 回滚 | 若截图全部失败则不写入任何数据 (全回滚) |

## 6. 启动验证

`EnviroBrainApplication` 中 `@PostConstruct` 验证：

```java
@PostConstruct
public void validateEnvironment() {
    // 检查 Python 可执行文件
    // 检查 camera_capture.py 脚本存在
    // 检查 DOCX 模板存在（warn 级别，不阻止启动）
    // 日志输出各配置项值（脱敏）
}
```

## 7. 测试策略（TDD 执行顺序）

```
Cycle 1 (CaptureService)         — 8 tests
  ├─ 命令构建验证 (mock ProcessBuilder)
  ├─ JSON 输出反序列化
  ├─ 超时处理
  ├─ 异常退出码处理
  └─ JSON 解析失败处理

Cycle 2 (FeishuNotifyService)    — 5 tests
  ├─ 卡片消息格式验证 (mock RestTemplate)
  ├─ Webhook URL 为空 → 跳过
  ├─ Webhook 不可达 → 不抛异常
  └─ 离线/异常摄像头详情格式

Cycle 3 (LedgerService)          — 7 tests
  ├─ DOCX 生成 (mock 模板 + 验证输出文件)
  ├─ 表格填充验证 (行数、单元格内容)
  ├─ 截图嵌入验证
  ├─ 台账记录写入 LEDGER_RECORDS
  ├─ 模板缺失 → 不阻塞
  └─ registerToLedger 筛选逻辑

Cycle 4 (InspectionService)      — 10 tests
  ├─ 正常巡检流程 (mock CaptureService)
  ├─ 并发多路执行验证
  ├─ 部分失败场景 (2/5 offline)
  ├─ 全部失败场景
  ├─ 台账生成验证
  ├─ 飞书通知调用验证
  └─ InspectionRecord 字段正确性

Cycle 5 (InspectionController)   — 6 tests
  ├─ MockMvc POST /trigger → 202
  ├─ API Key 认证
  ├─ GET /{id}/ledger/download → 200 + attachment
  └─ 404 台账不存在
```

预计总计约 36 个新测试，加上 Phase 1 的 91 个 = 127 tests。

## 8. Phase 2 任务清单

| # | 任务 | 预估 |
|---|------|------|
| 2.1 | 复制 Python 脚本到 enviro-brain/scripts/ | 0.1d |
| 2.2 | 复制 DOCX 模板到 enviro-brain/templates/ | 0.1d |
| 2.3 | 新增配置项到 application.yml | 0.1d |
| 2.4 | CameraCaptureResult DTO | 0.1d |
| 2.5 | CaptureService (TDD) | 0.5d |
| 2.6 | FeishuNotifyService (TDD) | 0.3d |
| 2.7 | LedgerService (TDD) | 0.5d |
| 2.8 | InspectionService 核心编排 (TDD) | 0.5d |
| 2.9 | InspectionScheduler (@Scheduled) | 0.1d |
| 2.10 | InspectionController | 0.2d |
| 2.11 | QueqiaoNotifyService (可选) | 0.1d |
| 2.12 | 全量测试 + 打包验证 | 0.1d |

---

**关联文档**:
- v3.0 方案：`环保小脑摄像头巡检_鹊桥MCP方案_v3.0.md` 第四节、第五节
- Phase 1 设计文档：`openspec/changes/phase1-enviro-brain-bootstrap/design.md`
