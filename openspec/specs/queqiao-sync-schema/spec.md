# Capability: queqiao-sync-schema

## Purpose
鹊桥自有数据库表结构，定义 synced_* 三张同步表与 sync_watermark 水位表的字段映射、索引、幂等主键与水位语义。

## Requirements

### Requirement: synced_inspection_records 表结构
鹊桥 SHALL 维护 `synced_inspection_records` 表，字段 1:1 镜像环保小脑 `inspection_records`（`batch_id`、`inspection_date`、`total_cameras`、`online_count`、`offline_count`、`abnormal_count`、`status`、`sync_version`），并以环保小脑原始 `id` 为主键，额外包含 `synced_at` 同步时间字段。

#### Scenario: 表结构与主键
- **WHEN** 执行 `queqiao/src/main/resources/db/schema.sql` 初始化
- **THEN** 存在 `synced_inspection_records` 表，主键为 `id BIGINT`，并包含 `sync_version BIGINT` 索引 `idx_sync_version`

### Requirement: synced_camera_results 表结构
鹊桥 SHALL 维护 `synced_camera_results` 表，字段 1:1 镜像环保小脑 `camera_results`（`record_id`、`camera_code`、`camera_name`、`status`、`quality_score`、`screenshot_path`、`error_message`、`sync_version`），以环保小脑原始 `id` 为主键并含 `synced_at`。

#### Scenario: 表结构与主键
- **WHEN** 执行 schema 初始化
- **THEN** 存在 `synced_camera_results` 表，主键为 `id BIGINT`，并建立 `idx_record_id`、`idx_camera_code`、`idx_status`、`idx_sync_version` 索引

### Requirement: synced_ledger_records 表结构
鹊桥 SHALL 维护 `synced_ledger_records` 表，字段 1:1 镜像环保小脑 `ledger_records`（`record_id`、`inspection_date`、`content`、`docx_path`、`sync_version`），以环保小脑原始 `id` 为主键并含 `synced_at`。

#### Scenario: 表结构与主键
- **WHEN** 执行 schema 初始化
- **THEN** 存在 `synced_ledger_records` 表，主键为 `id BIGINT`，并建立 `idx_record_id`、`idx_inspection_date`、`idx_sync_version` 索引

### Requirement: sync_watermark 水位表
鹊桥 SHALL 维护 `sync_watermark` 表，按 `table_name` 唯一记录每表 `last_sync_version BIGINT` 与 `last_sync_time DATETIME`，用于断点续拉。

#### Scenario: 水位表初始化与写入
- **WHEN** 一轮同步成功后更新水位
- **THEN** `sync_watermark` 中对应 `table_name` 的 `last_sync_version` 被更新且 `last_sync_time` 刷新为当前时间
