-- H2 兼容的数据库 Schema（用于测试）
-- 使用 H2 的 MySQL 兼容模式：MODE=MYSQL

-- 1. inspection_records（巡检记录主表）
CREATE TABLE inspection_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL COMMENT '批次唯一标识',
    inspection_date DATE NOT NULL COMMENT '巡检日期',
    total_cameras INT NOT NULL DEFAULT 0 COMMENT '总摄像头数',
    online_count INT NOT NULL DEFAULT 0 COMMENT '在线数',
    offline_count INT NOT NULL DEFAULT 0 COMMENT '离线数',
    abnormal_count INT NOT NULL DEFAULT 0 COMMENT '异常数',
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT '批次状态: RUNNING/COMPLETED/FAILED',
    sync_version BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_batch_id (batch_id),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version)
);

-- 2. camera_results（摄像头巡检结果）
CREATE TABLE camera_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL COMMENT '关联 inspection_records.id',
    camera_code VARCHAR(64) NOT NULL COMMENT '摄像头编码',
    camera_name VARCHAR(128) COMMENT '摄像头名称',
    status VARCHAR(20) NOT NULL COMMENT '状态: ONLINE/OFFLINE/ABNORMAL',
    quality_score DECIMAL(5,2) COMMENT '质量评分 0-100',
    screenshot_path VARCHAR(512) COMMENT '截图文件路径',
    error_message VARCHAR(512) COMMENT '错误信息',
    sync_version BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_record_id (record_id),
    INDEX idx_camera_code (camera_code),
    INDEX idx_status (status),
    INDEX idx_sync_version (sync_version),
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

-- 3. ledger_records（台账记录）
CREATE TABLE ledger_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL COMMENT '关联 inspection_records.id',
    inspection_date DATE NOT NULL COMMENT '巡检日期',
    content TEXT COMMENT '台账内容/Markdown',
    docx_path VARCHAR(512) COMMENT '生成的docx文件路径',
    sync_version BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_record_id (record_id),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version),
    FOREIGN KEY (record_id) REFERENCES inspection_records(id)
);

-- 4. camera_config（摄像头配置）
CREATE TABLE camera_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    camera_code VARCHAR(64) NOT NULL COMMENT '摄像头编码（唯一）',
    camera_name VARCHAR(128) NOT NULL COMMENT '摄像头名称',
    rtsp_url VARCHAR(512) COMMENT 'RTSP流地址',
    location VARCHAR(256) COMMENT '安装位置',
    enabled INT NOT NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_camera_code (camera_code)
);

-- 5. sync_version_seq（全局同步版本序列）
CREATE TABLE sync_version_seq (
    id INT PRIMARY KEY DEFAULT 1,
    next_val BIGINT NOT NULL DEFAULT 1 COMMENT '下一个版本号',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sync_version_seq (id, next_val) VALUES (1, 1);
