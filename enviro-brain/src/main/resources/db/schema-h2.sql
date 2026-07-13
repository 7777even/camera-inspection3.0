-- H2 兼容的数据库 Schema（用于测试）
-- 使用 H2 的 MySQL 兼容模式：MODE=MYSQL
-- 注意：H2 要求索引名全局唯一

-- 1. inspection_records（巡检记录主表）
CREATE TABLE inspection_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    inspection_date DATE NOT NULL,
    total_cameras INT NOT NULL DEFAULT 0,
    online_count INT NOT NULL DEFAULT 0,
    offline_count INT NOT NULL DEFAULT 0,
    abnormal_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    sync_version BIGINT NOT NULL DEFAULT 0,
    scenario VARCHAR(32) DEFAULT 'enviro',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ir_batch_id ON inspection_records(batch_id);
CREATE INDEX idx_ir_inspection_date ON inspection_records(inspection_date);
CREATE INDEX idx_ir_sync_version ON inspection_records(sync_version);

-- 2. camera_results（摄像头巡检结果）
CREATE TABLE camera_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    camera_code VARCHAR(64) NOT NULL,
    camera_name VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    quality_score DECIMAL(5,2),
    screenshot_path VARCHAR(512),
    error_message VARCHAR(512),
    sync_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

CREATE INDEX idx_cr_record_id ON camera_results(record_id);
CREATE INDEX idx_cr_camera_code ON camera_results(camera_code);
CREATE INDEX idx_cr_status ON camera_results(status);
CREATE INDEX idx_cr_sync_version ON camera_results(sync_version);

-- 3. ledger_records（台账记录）
CREATE TABLE ledger_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    inspection_date DATE NOT NULL,
    content TEXT,
    docx_path VARCHAR(512),
    sync_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

CREATE INDEX idx_lr_record_id ON ledger_records(record_id);
CREATE INDEX idx_lr_inspection_date ON ledger_records(inspection_date);
CREATE INDEX idx_lr_sync_version ON ledger_records(sync_version);

-- 4. camera_config（摄像头配置）
CREATE TABLE camera_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_code VARCHAR(64) NOT NULL,
    camera_name VARCHAR(128) NOT NULL,
    enterprise VARCHAR(128),
    artemis_device_id VARCHAR(128),
    rtsp_url VARCHAR(512),
    location VARCHAR(256),
    enabled INT NOT NULL DEFAULT 1,
    scenario VARCHAR(32) DEFAULT 'enviro',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_camera_code ON camera_config(camera_code);

-- 5. sync_version_seq（全局同步版本序列）
CREATE TABLE sync_version_seq (
    id INT DEFAULT 1 PRIMARY KEY,
    next_val BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sync_version_seq (id, next_val) VALUES (1, 0);
