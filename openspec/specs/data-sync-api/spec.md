# Capability: data-sync-api

## Purpose
定义环保小脑对外暴露的增量数据同步 REST 接口，包括全局水位查询与三类业务数据（巡检记录、摄像头结果、台账记录）的按版本号增量、分页查询，统一采用 `SyncResponse<T>` 响应格式。

## Requirements

### Requirement: 同步水位查询

系统 SHALL 提供 `GET /api/v1/sync/watermark` 接口，返回当前全局最大 sync_version（即 sync_version_seq 表中 next_val - 1）。

#### Scenario: 初始状态
- **WHEN** 数据库无任何业务数据，sync_version_seq.next_val 为 1
- **THEN** 返回 `{ "watermark": 0, "serverTime": "<当前时间>" }`

#### Scenario: 有数据后查询水位
- **WHEN** inspection_records 中最大 sync_version 为 15
- **THEN** 返回 `{ "watermark": 15 }`

### Requirement: 增量巡检记录查询

系统 SHALL 提供 `GET /api/v1/sync/inspections?since={version}&limit={n}` 接口，按 sync_version 升序返回 `sync_version > since` 的记录，最多 n 条。

#### Scenario: 首次全量拉取
- **WHEN** 请求 `GET /api/v1/sync/inspections?since=0&limit=100`，数据库有 3 条记录
- **THEN** 返回 3 条记录，`hasMore: false`，`nextSince` 为最后一条的 sync_version

#### Scenario: 增量拉取有新数据
- **WHEN** 数据库有 sync_version 为 1,2,3,4,5 共 5 条，请求 `since=2&limit=100`
- **THEN** 返回 sync_version 为 3,4,5 共 3 条，`hasMore: false`

#### Scenario: 分页拉取
- **WHEN** 数据库有 10 条记录（sync_version 1-10），请求 `since=0&limit=3`
- **THEN** 返回 sync_version 1,2,3 共 3 条，`hasMore: true`，`nextSince: 3`

#### Scenario: 无新数据
- **WHEN** 请求 `since` 大于等于当前最大 sync_version
- **THEN** 返回空数组 `[]`，`hasMore: false`

### Requirement: 增量摄像头结果查询

系统 SHALL 提供 `GET /api/v1/sync/camera-results?since={version}&limit={n}` 接口，行为与 inspections 一致。

#### Scenario: 增量查询 camera_results
- **WHEN** 数据库 camera_results 有 sync_version 1-5 共 5 条，请求 `since=3&limit=10`
- **THEN** 返回 sync_version 4,5 共 2 条

### Requirement: 增量台账记录查询

系统 SHALL 提供 `GET /api/v1/sync/ledger-records?since={version}&limit={n}` 接口，行为与 inspections 一致。

#### Scenario: 增量查询 ledger_records
- **WHEN** 数据库 ledger_records 有 sync_version 1-3 共 3 条，请求 `since=0&limit=10`
- **THEN** 返回 sync_version 1,2,3 共 3 条，`hasMore: false`

### Requirement: 统一 SyncResponse 响应体

系统 SHALL 使用统一的 `SyncResponse<T>` 响应格式：`{ "code": 200, "message": "success", "data": [...], "hasMore": boolean, "nextSince": long }`。

#### Scenario: 响应格式一致
- **WHEN** 任意增量查询接口返回数据
- **THEN** 响应体包含 code、message、data、hasMore、nextSince 五个字段，data 为数组

### Requirement: 同步接口默认分页限制

系统 SHALL 对 `limit` 参数设定默认值 1000，最大值 5000，防止单次拉取过多数据。

#### Scenario: limit 使用默认值
- **WHEN** 请求不传 limit 参数
- **THEN** 使用 limit=1000

#### Scenario: limit 超上限
- **WHEN** 请求 `limit=10000`
- **THEN** 实际使用 limit=5000
