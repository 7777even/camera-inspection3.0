-- =============================================================
-- 摄像头巡检系统 - MySQL 初始化 SQL（仅 queqiao_sync）
-- 注意：enviro_brain 已迁移至 PostgreSQL（见 db/init-postgres/01-schema.sql），
--       本文件仅供 queqiao 同步层使用，请保留 MySQL 方言。
-- =============================================================

-- queqiao_sync 数据库（鹊桥同步）
CREATE DATABASE IF NOT EXISTS `queqiao_sync` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */;
USE `queqiao_sync`;

-- -----------------------------------------------------------------------
-- 鹊桥同步水位记录
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sync_watermark` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `table_name` varchar(64) NOT NULL COMMENT '同步的表名',
  `last_sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '上次同步到的版本号',
  `last_sync_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上次同步时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_name` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步水位记录';

-- -----------------------------------------------------------------------
-- 鹊桥同步的巡检记录
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `synced_inspection_records` (
  `id` bigint(20) NOT NULL COMMENT '环保大脑原始记录 ID',
  `batch_id` varchar(64) NOT NULL COMMENT '批次唯一标识',
  `inspection_date` date NOT NULL COMMENT '巡检日期',
  `total_cameras` int(11) NOT NULL DEFAULT '0' COMMENT '总摄像头数',
  `online_count` int(11) NOT NULL DEFAULT '0' COMMENT '在线数',
  `offline_count` int(11) NOT NULL DEFAULT '0' COMMENT '离线数',
  `abnormal_count` int(11) NOT NULL DEFAULT '0' COMMENT '异常数',
  `status` varchar(20) NOT NULL DEFAULT 'RUNNING' COMMENT '批次状态',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `synced_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
  PRIMARY KEY (`id`),
  KEY `idx_inspection_date` (`inspection_date`),
  KEY `idx_sync_version` (`sync_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的巡检记录';

-- -----------------------------------------------------------------------
-- 鹊桥同步的摄像头结果
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `synced_camera_results` (
  `id` bigint(20) NOT NULL COMMENT '环保大脑原始记录 ID',
  `record_id` bigint(20) NOT NULL COMMENT '关联环保大脑 inspection_records.id',
  `camera_code` varchar(64) NOT NULL COMMENT '摄像头编码',
  `camera_name` varchar(128) DEFAULT NULL COMMENT '摄像头名称',
  `status` varchar(20) NOT NULL COMMENT '状态: ONLINE/OFFLINE/ABNORMAL',
  `quality_score` decimal(5,2) DEFAULT NULL COMMENT '质量评分 0-100',
  `screenshot_path` varchar(512) DEFAULT NULL COMMENT '截图文件路径',
  `error_message` varchar(512) DEFAULT NULL COMMENT '错误信息',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `synced_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
  PRIMARY KEY (`id`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_camera_code` (`camera_code`),
  KEY `idx_status` (`status`),
  KEY `idx_sync_version` (`sync_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的摄像头结果';

-- -----------------------------------------------------------------------
-- 鹊桥同步的台账记录
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `synced_ledger_records` (
  `id` bigint(20) NOT NULL COMMENT '环保大脑原始记录 ID',
  `record_id` bigint(20) NOT NULL COMMENT '关联环保大脑 inspection_records.id',
  `inspection_date` date NOT NULL COMMENT '巡检日期',
  `content` text COMMENT '台账内容',
  `docx_path` varchar(512) DEFAULT NULL COMMENT '生成的 docx 文件路径',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `synced_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '鹊桥同步时间',
  PRIMARY KEY (`id`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_inspection_date` (`inspection_date`),
  KEY `idx_sync_version` (`sync_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='鹊桥同步的台账记录';
