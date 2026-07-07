-- H2 兼容的数据库 Schema（用于测试）
-- 使用 H2 的 MySQL 兼容模式：MODE=MYSQL
-- 注意：H2 要求索引名全局唯一，且不支持 ENGINE/CHARSET 语法

-- 1. synced_inspection_records
CREATE TABLE synced_inspection_records (
    id               BIGINT PRIMARY KEY,
    batch_id         VARCHAR(64) NOT NULL,
    inspection_date  DATE NOT NULL,
    total_cameras    INT NOT NULL DEFAULT 0,
    online_count     INT NOT NULL DEFAULT 0,
    offline_count    INT NOT NULL DEFAULT 0,
    abnormal_count   INT NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    sync_version     BIGINT NOT NULL DEFAULT 0,
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sir_inspection_date ON synced_inspection_records(inspection_date);
CREATE INDEX idx_sir_sync_version ON synced_inspection_records(sync_version);

-- 2. synced_camera_results
CREATE TABLE synced_camera_results (
    id               BIGINT PRIMARY KEY,
    record_id        BIGINT NOT NULL,
    camera_code      VARCHAR(64) NOT NULL,
    camera_name      VARCHAR(128),
    status           VARCHAR(20) NOT NULL,
    quality_score    DECIMAL(5,2),
    screenshot_path  VARCHAR(512),
    error_message    VARCHAR(512),
    sync_version     BIGINT NOT NULL DEFAULT 0,
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scr_record_id ON synced_camera_results(record_id);
CREATE INDEX idx_scr_camera_code ON synced_camera_results(camera_code);
CREATE INDEX idx_scr_status ON synced_camera_results(status);
CREATE INDEX idx_scr_sync_version ON synced_camera_results(sync_version);

-- 3. synced_ledger_records
CREATE TABLE synced_ledger_records (
    id               BIGINT PRIMARY KEY,
    record_id        BIGINT NOT NULL,
    inspection_date  DATE NOT NULL,
    content          TEXT,
    docx_path        VARCHAR(512),
    sync_version     BIGINT NOT NULL DEFAULT 0,
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_slr_record_id ON synced_ledger_records(record_id);
CREATE INDEX idx_slr_inspection_date ON synced_ledger_records(inspection_date);
CREATE INDEX idx_slr_sync_version ON synced_ledger_records(sync_version);

-- 4. sync_watermark
CREATE TABLE sync_watermark (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    table_name        VARCHAR(64) NOT NULL,
    last_sync_version BIGINT NOT NULL DEFAULT 0,
    last_sync_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sw_table_name ON sync_watermark(table_name);
