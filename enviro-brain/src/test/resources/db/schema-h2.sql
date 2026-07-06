-- H2-compatible schema for testing
-- Mode: MySQL compatibility
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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_batch_id (batch_id),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version)
);

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
    INDEX idx_record_id (record_id),
    INDEX idx_camera_code (camera_code),
    INDEX idx_status (status),
    INDEX idx_sync_version (sync_version),
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

CREATE TABLE ledger_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    inspection_date DATE NOT NULL,
    content TEXT,
    docx_path VARCHAR(512),
    sync_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_record_id (record_id),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version),
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

CREATE TABLE camera_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_code VARCHAR(64) NOT NULL,
    camera_name VARCHAR(128) NOT NULL,
    rtsp_url VARCHAR(512),
    location VARCHAR(256),
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (camera_code)
);

CREATE TABLE sync_version_seq (
    id INT PRIMARY KEY DEFAULT 1,
    next_val BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (id = 1)
);
INSERT INTO sync_version_seq (id, next_val) VALUES (1, 1);
