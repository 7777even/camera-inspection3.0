CREATE DATABASE IF NOT EXISTS queqiao_sync CHARACTER SET utf8mb4;
USE queqiao_sync;

-- 1. synced_inspection_records（同步的巡检记录，镜像环保小脑 inspection_records）
CREATE TABLE synced_inspection_records (
    id               BIGINT PRIMARY KEY COMMENT '环保小脑原始记录 ID',
    batch_id         VARCHAR(64) NOT NULL COMMENT '批次唯一标识',
    inspection_date  DATE NOT NULL COMMENT '巡检日期',
    total_cameras    INT NOT NULL DEFAULT 0 COMMENT '总摄像头数',
    online_count     INT NOT NULL DEFAULT 0 COMMENT '在线数',
    offline_count    INT NOT NULL DEFAULT 0 COMMENT '离线数',
    abnormal_count   INT NOT NULL DEFAULT 0 COMMENT '异常数',
    status           VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT '批次状态',
    sync_version     BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的巡检记录';

-- 2. synced_camera_results（同步的摄像头结果，镜像环保小脑 camera_results）
CREATE TABLE synced_camera_results (
    id               BIGINT PRIMARY KEY COMMENT '环保小脑原始记录 ID',
    record_id        BIGINT NOT NULL COMMENT '关联环保小脑 inspection_records.id',
    camera_code      VARCHAR(64) NOT NULL COMMENT '摄像头编码',
    camera_name      VARCHAR(128) COMMENT '摄像头名称',
    status           VARCHAR(20) NOT NULL COMMENT '状态 ONLINE/OFFLINE/ABNORMAL',
    quality_score    DECIMAL(5,2) COMMENT '质量评分 0-100',
    screenshot_path  VARCHAR(512) COMMENT '截图文件路径',
    error_message    VARCHAR(512) COMMENT '错误信息',
    sync_version     BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
    INDEX idx_record_id (record_id),
    INDEX idx_camera_code (camera_code),
    INDEX idx_status (status),
    INDEX idx_sync_version (sync_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的摄像头结果';

-- 3. synced_ledger_records（同步的台账记录，镜像环保小脑 ledger_records）
CREATE TABLE synced_ledger_records (
    id               BIGINT PRIMARY KEY COMMENT '环保小脑原始记录 ID',
    record_id        BIGINT NOT NULL COMMENT '关联环保小脑 inspection_records.id',
    inspection_date  DATE NOT NULL COMMENT '巡检日期',
    content          TEXT COMMENT '台账内容',
    docx_path        VARCHAR(512) COMMENT '生成的 docx 文件路径',
    sync_version     BIGINT NOT NULL DEFAULT 0 COMMENT '同步版本号',
    synced_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
    INDEX idx_record_id (record_id),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_sync_version (sync_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的台账记录';

-- 4. sync_watermark（同步水位记录，按表记录已同步到的最大 sync_version）
CREATE TABLE sync_watermark (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    table_name        VARCHAR(64) NOT NULL COMMENT '同步的表名',
    last_sync_version BIGINT NOT NULL DEFAULT 0 COMMENT '上次同步到的版本号',
    last_sync_time    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上次同步时间',
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_table_name (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步水位记录';
