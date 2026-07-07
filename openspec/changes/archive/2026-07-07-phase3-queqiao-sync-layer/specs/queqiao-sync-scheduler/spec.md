# Capability: queqiao-sync-scheduler

定时拉取编排（SyncScheduler + SyncOrchestrationService），负责水位比对、分页拉取、幂等写入、水位推进与失败容错。

## ADDED Requirements

### Requirement: 定时触发
鹊桥 SHALL 每 30 分钟（可通过 `queqiao.sync.cron` 配置）触发一轮增量同步，且可被回调接口立即触发。

#### Scenario: 周期调度
- **WHEN** 系统运行且 cron 配置为每 30 分钟
- **THEN** 每 30 分钟自动执行一次 `SyncOrchestrationService.syncOnce()`

### Requirement: 水位比对跳过
每轮同步开始 SHALL 先比对环保小脑 `watermark` 与本地 `sync_watermark`；若远程水位不高于本地，则跳过本轮拉取。

#### Scenario: 无新数据跳过
- **WHEN** 环保小脑 `watermark = 100`，本地三表水位均 >= 100
- **THEN** 本轮不调用任何增量查询接口，不更新水位

### Requirement: 增量分页拉取
编排层 SHALL 按 `nextSince` 游标循环拉取每个表，直到 `hasMore == false`，每页写入本地库。

#### Scenario: 多页拉取
- **WHEN** 某表返回 `hasMore=true, nextSince=50`，下一页返回 `hasMore=false`
- **THEN** 编排层拉取两页并将所有增量项写入 `synced_*` 表

### Requirement: 幂等写入
写入 `synced_*` 表 SHALL 使用以环保小脑原始 `id` 为主键的 `INSERT ... ON DUPLICATE KEY UPDATE`，重复同步同一记录不产生重复行。

#### Scenario: 重复同步同一条记录
- **WHEN** 同一环保小脑记录（相同 `id` 与 `sync_version`）被同步两次
- **THEN** `synced_*` 表中仅存在一行该 `id`，第二次写入仅更新业务字段与 `synced_at`，行数不增加

### Requirement: 水位推进
每轮成功同步后 SHALL 更新 `sync_watermark` 中三表对应记录的 `last_sync_version` 与 `last_sync_time`。

#### Scenario: 跨表独立推进
- **WHEN** 本轮 inspections 拉到水位 100、camera-results 拉到 80
- **THEN** `sync_watermark` 中 `inspections` 行 `last_sync_version=100`，`camera_results` 行 `last_sync_version=80`

### Requirement: 失败容错不阻断
单表拉取或写入异常 SHALL 被捕获并记日志，继续处理其他表；整轮异常不更新水位，调度不被中断。

#### Scenario: 一张表失败不影响其他表
- **WHEN** `camera-results` 同步抛异常
- **THEN** `inspections` 与 `ledger-records` 仍完成同步，异常被记录日志，调度在下个周期继续

### Requirement: 游标防护避免死循环
当某表返回 `hasMore=true` 但 `nextSince <= since`（同 sync_version 分页退化）时，编排层 SHALL 终止该表拉取并记录告警，避免无限循环。

#### Scenario: 游标无法推进
- **WHEN** 某表返回 `hasMore=true` 且 `nextSince` 等于当前 `since`
- **THEN** 编排层停止该表继续拉取，记录告警日志，本轮其他表不受影响
