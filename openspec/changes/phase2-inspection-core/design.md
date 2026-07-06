# Phase 2: 环保小脑巡检核心 — 设计文档

## 1. 架构概览

```
@Scheduled(cron="0 0 * * * ?") / POST /api/v1/inspections/trigger
                     │
                     ▼
┌──── InspectionService（核心编排，@Transactional）───────────────────┐
│  ① CameraConfigService.findActive() → 读启用摄像头列表               │
│  ② SyncVersionService.nextVersion() → 全局同步版本号                 │
│  ③ INSERT inspection_records (status=RUNNING)                       │
│  ④ ThreadPoolExecutor(4) 并发 → CaptureService.capture() × N        │
│  ⑤ batchInsert camera_results × N (syncVersion)                     │
│  ⑥ 更新 inspection_records (status=COMPLETED, stats)                │
│  ⑦ LedgerService.generateAndSave() → 台账 + ledger_records           │
│  ⑧ FeishuNotifyService.sendReport() → 飞书卡片                      │
│  ⑨ QueqiaoNotifyService.notifyNewData() → 鹊桥回调（可选）           │
└────────────────────────────────────────────────────────────────────┘
```

## 2. 技术决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 截图调用方式 | Java ProcessBuilder → Python CLI | Python OpenCV 生态成熟，Java 端只需解析 JSON |
| 并发策略 | ThreadPoolExecutor(4, LinkedBlockingQueue, CallerRunsPolicy) | 4 路并发平衡吞吐与资源，CallerRunsPolicy 降级不丢任务 |
| 单路超时 | future.get(60, SECONDS) | 截图通常 5-15s，60s 包含重试 + 预热余量 |
| 台账写入判断 | shouldRegisterToLedger: offline/abnormal 或 qualityScore < 0.5 | 在线且高质量的不入台账 |
| 飞书通知 | fire-and-forget (try-catch) | 通知失败不阻塞巡检主流程 |
| 线程池生命周期 | shutdownNow() 在 finally 中 | 保证巡检结束时释放线程资源 |

## 3. 组件设计

### CaptureService
- 11 个 @Value 注入配置（海康凭据、Python 路径、截图目录等）
- buildCommand() 组装 ProcessBuilder 参数列表（--json 输出）
- parseResult() Jackson ObjectMapper 反序列化 CameraCaptureResult
- capture() 主方法：启动进程 → 等待 exitCode → 读 stdout → 解析 JSON

### InspectionService
- @Transactional + @RequiredArgsConstructor 构造注入 7 个依赖
- executeInspection(triggerType) 返回 inspectId
- 时间统计：online/offline 直接匹配，"error"/"timeout"/"abnormal" 归为 abnormalCount
- 台账目标筛选：调用 ledgerService.shouldRegisterToLedger() 过滤

### FeishuNotifyService
- @Value("${enviro.feishu.webhook-url:}") 默认空
- URL 为空 → 静默跳过
- buildCardJson() 生成飞书 interactive 卡片（header + 概况 + 详情 + note）
- RestTemplate 异常 catch → log.error，不抛

### LedgerService
- generateAndSave(inspectId, targets, syncVersion) → docxPath
- 逐条构建 LedgerRecord（recordId, inspectionDate, content, docxPath, syncVersion）
- content 格式："#N | cameraName | status | quality:N/A | errorMsg"

### InspectionScheduler
- @Component + @EnableScheduling
- @Scheduled(cron="0 0 * * * ?") hourlyInspection()
- 内部调用 inspectionService.executeInspection("auto")

### InspectionController
- POST /api/v1/inspections/trigger → inspectionService.executeInspection("manual")
- 返回 202 Accepted + ApiResponse<Map>(taskId, status="running")

### QueqiaoNotifyService（可选）
- @Value("${enviro.queqiao.callback-url:}") 默认空
- notifyNewData(syncVersion) → POST JSON 到回调 URL
- 异常不抛，只 log.warn

## 4. 配置清单

```yaml
enviro:
  hikvision:          # 海康平台凭据（环境变量注入或直接配置）
  python:             # Python 执行环境
  screenshots:        # 截图保存目录
  ledger:             # 台账模板 + 输出目录
  feishu:             # 飞书 Webhook URL
  queqiao:            # 鹊桥回调 URL
  inspection:         # 巡检参数（并发数、超时）
```
