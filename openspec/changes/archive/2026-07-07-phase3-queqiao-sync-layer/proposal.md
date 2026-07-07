# Phase 3: 鹊桥数据同步层

> 基于 v3.0 方案第十四节实施路线图 Phase 3，并严格对齐 Phase 1/Phase 2 已实现代码的真实契约。

## Why

Phase 1 已交付环保小脑的同步接口（`GET /api/v1/sync/watermark` + 三张业务表的增量查询），Phase 2 已让环保小脑每小时自动巡检并写入带 `sync_version` 的数据。但目前**没有任何消费者**——鹊桥作为"数据同步层"尚未存在，脑机端即便接入 MCP 也无数据可查。Phase 3 的目标是构建鹊桥自有存储与定时拉取能力，把环保小脑的数据可靠地镜像到鹊桥数据库，为 Phase 4 的 MCP 查询工具提供"不穿透环保小脑"的数据底座。

## What Changes

- **新建 `queqiao/` Spring Boot 模块**（独立 artifact，groupId `com.queqiao`），作为数据同步层独立服务，与 `enviro-brain/` 解耦部署。
- **新建鹊桥自有数据库 DDL**：`synced_inspection_records`、`synced_camera_results`、`synced_ledger_records`（镜像环保小脑三张表 + `synced_at` 同步时间）、`sync_watermark`（水位记录表）。所有同步表以环保小脑原始 `id` 为主键，支持幂等写入。
- **新建同步客户端 `EnviroBrainSyncClient`**：基于 `RestTemplate`，携带 `X-API-Key` 调用环保小脑的 `watermark` + 三个增量接口，反序列化为本地 DTO（字段与环保小脑实体严格一致）。
- **新建 `SyncOrchestrationService` + `SyncScheduler`**：每 30 分钟执行一轮同步——先比对水位，再按 `nextSince` 循环拉取三张表增量数据，幂等写入（`INSERT ... ON DUPLICATE KEY UPDATE`），并更新 `sync_watermark` 水位。
- **新建可选回调接收接口 `NotifyController`**：`POST /api/notify/new-data`，接收环保小脑入库后的回调通知，立即触发一轮增量同步（事件驱动，缩短延迟）。该接口受 API Key 保护。
- **容错兜底**：环保小脑不可达时同步失败只记日志、不更新水位，历史数据可继续服务；幂等写入保证重复/部分同步不产生脏数据。

## Capabilities

### New Capabilities

- `queqiao-sync-schema`: 鹊桥自有数据库表结构（synced_inspection_records / synced_camera_results / synced_ledger_records / sync_watermark），定义字段映射、索引、幂等主键与水位表语义。
- `queqiao-sync-client`: 环保小脑同步接口客户端，封装 watermark 与三个增量查询的调用、鉴权（X-API-Key）、响应反序列化，依赖环保小脑已实现的 `SyncResponse{hasMore,nextSince}` 契约。
- `queqiao-sync-scheduler`: 定时拉取编排（SyncScheduler + SyncOrchestrationService），负责水位比对、分页拉取、幂等写入、水位推进与失败容错。
- `queqiao-notify-receiver`: 可选的环保小脑回调接收端点，受 API Key 保护，收到通知后立即触发增量同步。

### Modified Capabilities

无（Phase 3 不修改环保小脑任何行为或契约；仅作为已存在同步接口的消费者，依赖 Phase 1 的 `data-sync-api` 能力）。

## Impact

- **架构影响**：仅涉及**鹊桥层**（新增服务），环保小脑层与脑机端层不受影响。脑机端仍不直接访问环保小脑；Phase 3 产出的是 Phase 4 MCP 工具的数据底座。
- **数据表变更**：新增 4 张鹊桥表（自有数据库，与环保小脑库分离）。与 `sync_version` 增量同步兼容性——所有同步表保留 `sync_version BIGINT` 字段并以环保小脑原始 `id` 去重，增量拉取严格按 `WHERE sync_version > #{since}` 语义，与 Phase 1 接口契约一致。
- **API 变更**：
  - 同步接口（鹊桥→环保小脑拉取，Phase 1 已提供，本 Phase 消费）：`GET /api/v1/sync/watermark`、`GET /api/v1/sync/inspections`、`GET /api/v1/sync/camera-results`、`GET /api/v1/sync/ledger-records`。
  - 操作转发接口：本 Phase 不涉及（Phase 4 才封装）。
  - 新增鹊桥入站接口（受 API Key 保护）：`POST /api/notify/new-data`（回调接收，可选）。
- **依赖层面**：`queqiao/` 引入 spring-boot-starter-web、spring-boot-starter-actuator、mybatis-spring-boot-starter、mysql-connector-j、lombok、h2（test），与 `enviro-brain/` 同构。
- **配置层面**：`application.yml` 新增 `enviro-brain.base-url`、`enviro-brain.api-key`、`queqiao.sync.cron`（默认每 30 分钟）等配置项。

## 契约对齐说明（重要）

本方案严格以 `enviro-brain/` 已实现代码为契约，而非 v3.0 方案文档（文档与实现存在偏差）。关键契约事实：

- `SyncResponse<T>` 实际字段为 `hasMore:boolean` + `nextSince:long`（**非**文档中的 `watermark`）。
- `WatermarkResponse` 字段为 `watermark:long` + `serverTime:String`。
- 实体字段以实现为准：`InspectionRecord(batchId, inspectionDate, totalCameras, onlineCount, offlineCount, abnormalCount, status, syncVersion, ...)`；`CameraResult(recordId, cameraCode, cameraName, status[ONLINE/OFFLINE/ABNORMAL], qualityScore[0-100], screenshotPath, errorMessage, syncVersion)`；`LedgerRecord(recordId, inspectionDate, content, docxPath, syncVersion)`。
- 客户端 DTO 必须 1:1 镜像这些字段，否则反序列化会丢字段。
