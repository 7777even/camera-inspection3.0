# Capability: queqiao-sync-client

环保小脑同步接口客户端，封装 watermark 与三个增量查询的调用、鉴权（X-API-Key）、响应反序列化，依赖环保小脑已实现的 `SyncResponse{hasMore,nextSince}` 契约。

## ADDED Requirements

### Requirement: 同步接口鉴权头
客户端 SHALL 在每次调用环保小脑同步接口时，于请求头携带 `X-API-Key`，其值为配置的 `enviro-brain.api-key`。

#### Scenario: 请求携带 API Key
- **WHEN** 客户端调用 `GET /api/v1/sync/watermark`
- **THEN** 请求头包含 `X-API-Key` 且值等于配置中的 `enviro-brain.api-key`

### Requirement: watermark 查询
客户端 SHALL 调用 `GET /api/v1/sync/watermark` 并反序列化为 `WatermarkResponse`，返回其中的 `watermark` 字段（long）。

#### Scenario: 成功获取水位
- **WHEN** 环保小脑返回 `{"code":200,"message":"success","data":{"watermark":1709280001234,"serverTime":"..."}}`
- **THEN** 客户端返回 `watermark = 1709280001234`

### Requirement: 增量查询反序列化
客户端 SHALL 调用 `GET /api/v1/sync/{inspections|camera-results|ledger-records}?since={v}&limit={n}`，将响应反序列化为 `SyncResponse<*Dto>`，返回 `items` 列表、`hasMore`、`nextSince`。

#### Scenario: 反序列化为 DTO 列表
- **WHEN** 环保小脑返回 `SyncResponse<InspectionRecord>`（含 `data` 列表、`hasMore`、`nextSince`）
- **THEN** 客户端得到对应的 DTO 列表且 `batchId`、`inspectionDate` 等字段被正确填充，`hasMore` 与 `nextSince` 与响应一致

### Requirement: 客户端 DTO 与实体一致
客户端 DTO（`InspectionRecordDto`/`CameraResultDto`/`LedgerRecordDto`）字段名 SHALL 与环保小脑对应实体完全一致，否则反序列化会丢失字段。

#### Scenario: 字段镜像
- **WHEN** 环保小脑返回 `CameraResult` 实体 JSON
- **THEN** `CameraResultDto` 能完整反序列化 `recordId`、`cameraCode`、`cameraName`、`status`、`qualityScore`、`screenshotPath`、`errorMessage`、`syncVersion` 而无需额外注解

### Requirement: 客户端异常隔离
当环保小脑不可达或返回非 2xx 时，客户端 SHALL 抛出异常，由编排层捕获处理（不静默吞掉）。

#### Scenario: 不可达抛异常
- **WHEN** 环保小脑连接超时或返回 500
- **THEN** 客户端抛出异常，交由 `SyncOrchestrationService` 捕获并记录日志，本轮不更新水位
