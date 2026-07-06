## ADDED Requirements

### Requirement: 巡检记录表

系统 SHALL 在 MySQL `enviro_brain` 数据库中创建 `inspection_records` 表，包含字段：id（BIGINT AUTO_INCREMENT PK）、inspect_date（DATE）、inspect_time（DATETIME）、total_count、online_count、offline_count、abnormal_count、ledger_count、trigger_type（VARCHAR 默认 'auto'）、status（VARCHAR 默认 'completed'）、sync_version（BIGINT 默认 0）、created_at、updated_at。

#### Scenario: 表结构创建
- **WHEN** 执行 DDL 脚本
- **THEN** `inspection_records` 表存在，SHOW CREATE TABLE 输出与定义一致

#### Scenario: sync_version 索引
- **WHEN** 查询 `SHOW INDEX FROM inspection_records`
- **THEN** 包含 `idx_inspect_date`（on inspect_date）和 `idx_sync_version`（on sync_version）两条索引

### Requirement: 摄像头结果表

系统 SHALL 创建 `camera_results` 表，包含字段：id（BIGINT AUTO_INCREMENT PK）、inspect_id（BIGINT FK）、camera_code、camera_name、enterprise、status、quality_score、quality_detail（TEXT JSON）、screenshot_path、error_msg、retry_used、register_to_ledger、capture_time、sync_version、created_at。

#### Scenario: 外键关联
- **WHEN** 向 camera_results 插入 inspect_id 不存在于 inspection_records 的记录
- **THEN** MySQL 拒绝插入，抛出外键约束错误

#### Scenario: sync_version 字段默认值
- **WHEN** 插入一条记录不指定 sync_version
- **THEN** sync_version 自动填充为 0

### Requirement: 台账记录表

系统 SHALL 创建 `ledger_records` 表，包含字段：id（BIGINT AUTO_INCREMENT PK）、inspect_id（BIGINT FK）、camera_result_id（BIGINT FK）、ledger_date（DATE）、seq_no、enterprise、camera_name、over_threshold、abnormal_note、screenshot_file、disposal_status、docx_path、sync_version、created_at。

#### Scenario: ledger_records 表关联
- **WHEN** 向 ledger_records 插入 camera_result_id 不存在于 camera_results 的记录
- **THEN** MySQL 拒绝插入，抛出外键约束错误

### Requirement: 摄像头配置表

系统 SHALL 创建 `camera_config` 表，包含字段：id（BIGINT AUTO_INCREMENT PK）、camera_code（VARCHAR UNIQUE）、camera_name、enterprise、rtsp_url、artemis_device_id、location、status（VARCHAR 默认 'active'）、created_at、updated_at。

#### Scenario: camera_code 唯一约束
- **WHEN** 插入两条 camera_code 相同的记录
- **THEN** 第二条插入失败，抛出 duplicate key 错误

### Requirement: 版本序列表

系统 SHALL 创建 `sync_version_seq` 表，单行单字段：id（INT PRIMARY KEY 固定为 1）、next_val（BIGINT 默认 1）。

#### Scenario: 初始值
- **WHEN** 表创建完成且无任何操作
- **THEN** next_val 为 1

### Requirement: DDL 脚本纳入版本管理

系统 SHALL 将建表 DDL 保存为 `src/main/resources/db/schema.sql`，纳入 Git 版本管理。

#### Scenario: DDL 文件存在
- **WHEN** 检查 `enviro-brain/src/main/resources/db/schema.sql`
- **THEN** 文件存在，包含所有 5 张表的 CREATE TABLE 语句
