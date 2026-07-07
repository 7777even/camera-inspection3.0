# Phase 3: 鹊桥数据同步层 — 设计文档

## Context

环保小脑（enviro-brain）已完成：Phase 1 提供同步接口（`GET /api/v1/sync/watermark` + 三张业务表增量查询），Phase 2 实现每小时定时巡检并写入带 `sync_version` 的数据。当前缺失消费者——鹊桥作为"数据同步层"尚不存在，脑机端即便接入也无数据可查。

本设计在 `queqiao/` 新建一个独立 Spring Boot 服务，作为数据同步层：定时从环保小脑拉取增量数据，幂等写入自有数据库，为 Phase 4 的 MCP 查询工具提供"不穿透环保小脑"的数据底座。

**约束**：严格以 `enviro-brain/` 已实现代码为契约（`SyncResponse{hasMore,nextSince}`、`WatermarkResponse{watermark,serverTime}`、实体字段以实现为准），不依赖 v3.0 方案文档中与实现不符的描述。

## Goals / Non-Goals

**Goals:**
- 新建 `queqiao/` Spring Boot 模块（groupId `com.queqiao`），独立部署、独立数据库。
- 建立 4 张鹊桥自有表（synced_* + sync_watermark），字段 1:1 镜像环保小脑，保留 `sync_version`。
- 实现同步客户端，携带 `X-API-Key` 调用环保小脑 4 个同步接口并正确反序列化。
- 实现定时编排（每 30 分钟）：水位比对 → 增量拉取 → 幂等写入 → 水位推进。
- 实现幂等写入（以环保小脑原始 `id` 为主键，`ON DUPLICATE KEY UPDATE`）。
- 实现可选回调接收端点（受 API Key 保护），收到环保小脑通知后立即触发同步。
- 容错兜底：环保小脑不可达时不更新水位、只记日志，历史数据继续可查。

**Non-Goals:**
- 不实现 MCP 协议封装（Phase 4）。
- 不实现操作转发接口（trigger_inspection / download_ledger_docx，Phase 4）。
- 不修改环保小脑任何代码或契约（仅作为已存在同步接口的消费者）。
- 不实现脑机端对接（Phase 4/5）。

## Decisions

### D1: queqiao 作为独立 Spring Boot 模块
**选择**：在 `queqiao/` 新建与 `enviro-brain/` 同构的 Maven 模块（spring-boot-starter-web / actuator / mybatis / mysql / h2-test / lombok），groupId `com.queqiao`，包名 `com.queqiao.sync`。
**理由**：方案明确鹊桥是独立平台、独立数据库；独立模块便于独立部署与演进，与"脑机端不直接访问环保小脑"的解耦目标一致。
**备选**：在 enviro-brain 内加模块——违背解耦原则，且共享 DB 连接会让"鹊桥自有数据库"失去意义。

### D2: 客户端 DTO 1:1 镜像环保小脑实体
**选择**：在 `queqiao.dto` 包下复制 `InspectionRecordDto` / `CameraResultDto` / `LedgerRecordDto`，字段名与环保小脑 `entity` 完全一致（`batchId`、`recordId`、`cameraCode`、`qualityScore` 等），并复制 `ApiResponse<T>`、`SyncResponse<T>`、`WatermarkResponse`。
**理由**：环保小脑同步接口直接序列化实体返回（`SyncController` → `SyncService.syncInspections` 返回 `SyncResponse<InspectionRecord>`）。Jackson 按字段名反序列化，DTO 必须 1:1 对应，否则字段静默丢失。

### D3: 增量拉取采用 nextSince 游标循环
**选择**：对每个表循环调用同步接口，入参 `since = 本地水位`，取返回 `items` 幂等写入后，将 `since` 推进到返回值的 `nextSince`，直到 `hasMore == false`。
**理由**：与环保小脑 `SyncService.buildResponse` 的游标契约（`nextSince = 末条 sync_version`）完全对齐。
**备选**：基于 watermark 全量分页——浪费带宽，且无法与"只拉增量"的设计原则兼容。

### D4: 幂等写入使用原始 id 作主键 + ON DUPLICATE KEY UPDATE
**选择**：synced_* 表主键 `id` 直接使用环保小脑原始记录 id；写入 SQL 用 `INSERT ... ON DUPLICATE KEY UPDATE`（更新业务字段 + `synced_at`）。
**理由**：环保小脑 id 全局唯一且稳定，以之做主键天然实现幂等——重复同步、中断重跑都不会产生重复行或脏数据，满足方案"数据重复同步（幂等）"验收点。
**备选**：鹊桥自增 id + 业务唯一键——需额外唯一索引与 upsert 逻辑，复杂度更高。

### D5: 水位管理使用 sync_watermark 表（Per-Table）
**选择**：`sync_watermark` 表按 `table_name` 记录每表 `last_sync_version` 与 `last_sync_time`；每轮同步前读取，每轮成功后更新。
**理由**：三张表共享一个 `sync_version` 序列（环保小脑全局递增），但各自独立推进更健壮——任一张表同步失败不影响其他表水位。下次从中断点续拉，天然容错。

### D6: 失败容错不阻断调度
**选择**：单表拉取/写入异常被捕获并记日志，继续下一表；整轮异常不更新水位；调度线程不因异常中断。环保小脑不可达时抛出后统一捕获，记录日志，本轮不更新水位。
**理由**：满足方案"环保小脑宕机 → 鹊桥返回最近历史数据"的容错要求，且 `sync_version` 机制保证恢复后增量补齐。

### D7: 回调接收端点受 API Key 保护（可选）
**选择**：`POST /api/notify/new-data` 接收 `{"syncVersion": long, "type": string}`，API Key 校验通过后立即触发一轮增量同步。
**理由**：将事件驱动延迟从 30min 降到秒级（方案 6.4）。校验失败返回 401，不触发同步。
**备选**：不校验——任意内网请求都能触发同步，存在滥用风险。

### D8: 测试策略（TDD）
**选择**：
- `SyncOrchestrationServiceTest`：用 H2 + MockBean 客户端，验证增量拉取、幂等写入、水位推进、失败容错。
- `EnviroBrainSyncClientTest`：用 Spring `MockRestServiceServer` 模拟环保小脑 HTTP 响应，验证鉴权头、URL、反序列化。
- `Synced*MapperTest`：用 H2 验证 upsert 幂等性（二次写入不新增行）。
- `SyncSchedulerTest`：`@SpyBean` 验证每 30 分钟触发编排（用 `@TestPropertySource` 缩短 cron 或手动调用）。
**理由**：编排逻辑与 HTTP 客户端均可在无真实环保小脑实例下完整测试，符合 TDD 与 CI 可重复要求。

## 数据流向（四条路径）

```
写路径（不变）：环保小脑 @Scheduled 每小时 → 截图 → 入库（带 sync_version）→ 台账 → 飞书

同步路径（本 Phase 实现）：
  鹊桥 SyncScheduler（每 30min，或回调触发）
    ├─① GET /api/v1/sync/watermark → 获取环保小脑 maxSyncVersion
    ├─② 比对 sync_watermark 本地水位 → maxSyncVersion <= 本地 → 跳过本轮
    ├─③ 逐表拉取：GET /api/v1/sync/{inspections|camera-results|ledger-records}?since={本地水位}&limit=N
    │     → 循环直到 hasSince=false，每页幂等写入 synced_* 表
    ├─④ 更新 sync_watermark（每表 last_sync_version + last_sync_time）
    └─⑤ 失败 → 记日志，不更新水位，历史数据仍可读

读路径（Phase 4）：脑机端 → 鹊桥 MCP → 鹊桥自有数据库（不穿透环保小脑）
操作路径（Phase 4）：脑机端 → 鹊桥 MCP → 转发环保小脑
```

## Risks / Trade-offs

| 风险 | 缓解措施 |
|------|----------|
| **[R1] 同 sync_version 分页游标退化**：单轮巡检的 camera_results 共用同一 `sync_version`，若单页超 limit，严格 `>` 游标会跳过/死循环 | D3 游标循环 + 防护：当 `nextSince <= since` 且 `hasMore` 时主动终止该表拉取并告警；实际每轮仅 ~25 路摄像头，远低于默认 limit(1000)，常态不触发 |
| **[R2] 环保小脑不可达**：每 30 分钟同步失败 | D6 捕获异常、不更新水位、记日志；恢复后下一轮自动从断点续拉 |
| **[R3] 双库一致性**：鹊桥与环保小脑短暂不一致 | 增量同步 + 幂等 upsert + 水位机制保障最终一致；MCP 响应附注数据更新时间（Phase 4） |
| **[R4] 回调端被滥用**：内网任意请求触发同步 | D7 API Key 校验，失败 401 |
| **[R5] 大批量首次同步**：历史数据较多时首轮耗时 | 分页拉取避免 OOM；可临时调大每轮 limit 或对历史做一次性全量初始化 |

## Migration Plan

1. 初始化鹊桥自有数据库 `queqiao_sync`，执行 `queqiao/src/main/resources/db/schema.sql` 建 4 张表。
2. 部署 `queqiao/` Spring Boot 服务（独立端口，如 8081）。
3. 配置 `enviro-brain.base-url` + `enviro-brain.api-key`（与环保小脑约定的同步专用 Key）。
4. 启动后首轮同步从 `since=0` 拉取历史全量（仅一次），之后按水位增量。
5. 回滚：停止 queqiao 服务即可，不影响环保小脑运行；重新部署会自 sync_watermark 续拉（若需全量重来，清空 sync_watermark 表）。

## Open Questions

- [ ] 鹊桥自有数据库是否复用环保小脑同一 MySQL 实例（仅不同 schema），还是完全独立实例？本实现用独立 `queqiao_sync` schema，配置可指向任意 MySQL。
- [ ] 回调接收端点的 API Key 与"同步拉取用的 Key"是否同一把？（建议同步拉取用一把，回调接收用另一把，本实现支持分别配置。）
- [ ] 每轮同步 limit 默认 200 是否合适？（默认 200，可在 `queqiao.sync.batch-limit` 调整。）
