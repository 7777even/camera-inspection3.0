# Capability: camera-config

## Purpose
定义摄像头配置的管理能力，包括活跃摄像头列表查询、按编码单查、Excel 批量导入/更新（UPSERT）以及导入模板下载。

## Requirements

### Requirement: 摄像头信息查询

系统 SHALL 提供 `GET /api/v1/cameras` 接口，返回所有 `status = 'active'` 的摄像头列表，支持分页参数 `page` 和 `size`。

#### Scenario: 查询所有活跃摄像头
- **WHEN** 数据库有 5 条活跃摄像头和 1 条禁用摄像头
- **THEN** 返回 5 条记录，total 为 5，不包含禁用摄像头

#### Scenario: 分页查询
- **WHEN** 请求 `GET /api/v1/cameras?page=1&size=2`
- **THEN** 返回第 1-2 条记录，total 为总记录数

### Requirement: 摄像头单项查询

系统 SHALL 提供 `GET /api/v1/cameras/{cameraCode}` 接口，按 camera_code 查询单路摄像头信息。

#### Scenario: 查询存在的摄像头
- **WHEN** 请求 `GET /api/v1/cameras/CAM001`
- **THEN** 返回摄像头 CAM001 的完整信息（camera_code、camera_name、enterprise、rtsp_url、artemis_device_id、location）

#### Scenario: 查询不存在的摄像头
- **WHEN** 请求 `GET /api/v1/cameras/NONEXIST`
- **THEN** 返回 HTTP 404，message 包含 "摄像头不存在"

### Requirement: Excel 批量导入摄像头

系统 SHALL 提供 `POST /api/v1/cameras/import` 接口，接受 multipart/form-data 上传 Excel 文件（.xlsx 或 .xls），解析后 UPSERT 到 camera_config 表。

#### Scenario: 导入新摄像头
- **WHEN** 上传包含 3 条新摄像头的 Excel，camera_code 均不存在于数据库
- **THEN** 返回 `imported: 3, updated: 0, errors: 0`，数据库新增 3 条记录

#### Scenario: 更新已有摄像头
- **WHEN** 上传 Excel 包含已有 camera_code 但 enterprise 不同的记录
- **THEN** 返回 `imported: 0, updated: 1, errors: 0`，数据库相应记录的 enterprise 已更新

#### Scenario: 文件格式不支持
- **WHEN** 上传非 Excel 文件（如 .txt）
- **THEN** 返回 HTTP 400，message 包含 "仅支持 .xlsx 或 .xls 格式"

#### Scenario: 文件过大
- **WHEN** 上传超过 5MB 的文件
- **THEN** 返回 HTTP 413，message 包含 "文件大小超过限制"

#### Scenario: Excel 缺少必填列
- **WHEN** 上传 Excel 缺少 camera_code 列
- **THEN** 返回 HTTP 400，message 说明缺少的列名

### Requirement: Excel 模板下载

系统 SHALL 提供 `GET /api/v1/cameras/template` 接口，下载空模板 Excel（含表头行：camera_code、camera_name、enterprise、rtsp_url、artemis_device_id、location）。

#### Scenario: 下载模板
- **WHEN** 请求 `GET /api/v1/cameras/template`
- **THEN** 返回 .xlsx 文件，Content-Type 为 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，文件名为 `camera_import_template.xlsx`
