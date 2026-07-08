-- =============================================================
-- 摄像头巡检系统 - 初始化 SQL
-- 创建两个数据库及其所有表
-- =============================================================

-- 创建 enviro_brain 数据库（环保大脑）
CREATE DATABASE IF NOT EXISTS `enviro_brain` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;
USE `enviro_brain`;

-- -----------------------------------------------------------------------
-- 巡检记录主表
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `inspection_records` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `batch_id` varchar(64) NOT NULL COMMENT '批次唯一标识',
  `inspection_date` date NOT NULL COMMENT '巡检日期',
  `total_cameras` int(11) NOT NULL DEFAULT '0' COMMENT '总摄像头数',
  `online_count` int(11) NOT NULL DEFAULT '0' COMMENT '在线数',
  `offline_count` int(11) NOT NULL DEFAULT '0' COMMENT '离线数',
  `abnormal_count` int(11) NOT NULL DEFAULT '0' COMMENT '异常数',
  `status` varchar(20) NOT NULL DEFAULT 'RUNNING' COMMENT '批次状态: RUNNING/COMPLETED/FAILED',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_batch_id` (`batch_id`),
  KEY `idx_inspection_date` (`inspection_date`),
  KEY `idx_sync_version` (`sync_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检记录主表';

-- -----------------------------------------------------------------------
-- 摄像头配置表
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `camera_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `camera_code` varchar(64) NOT NULL COMMENT '摄像头编码（唯一）',
  `camera_name` varchar(128) NOT NULL COMMENT '摄像头名称',
  `rtsp_url` varchar(512) DEFAULT NULL COMMENT 'RTSP流地址',
  `location` varchar(256) DEFAULT NULL COMMENT '安装位置',
  `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用: 1-启用, 0-禁用',
  `ledger_enabled` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否更新到台账 1=是 0=否',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_camera_code` (`camera_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄像头配置表';

-- -----------------------------------------------------------------------
-- 摄像头巡检结果（依赖 inspection_records）
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `camera_results` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `record_id` bigint(20) NOT NULL COMMENT '关联 inspection_records.id',
  `camera_code` varchar(64) NOT NULL COMMENT '摄像头编码',
  `camera_name` varchar(128) DEFAULT NULL COMMENT '摄像头名称',
  `status` varchar(20) NOT NULL COMMENT '状态: ONLINE/OFFLINE/ABNORMAL',
  `quality_score` decimal(5,2) DEFAULT NULL COMMENT '质量评分 0-100',
  `screenshot_path` varchar(512) DEFAULT NULL COMMENT '截图文件路径',
  `error_message` varchar(512) DEFAULT NULL COMMENT '错误信息',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_camera_code` (`camera_code`),
  KEY `idx_status` (`status`),
  KEY `idx_sync_version` (`sync_version`),
  CONSTRAINT `fk_camera_results_record` FOREIGN KEY (`record_id`) REFERENCES `inspection_records` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摄像头巡检结果';

-- -----------------------------------------------------------------------
-- 巡查台账记录（依赖 inspection_records）
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ledger_records` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `record_id` bigint(20) NOT NULL COMMENT '关联 inspection_records.id',
  `inspection_date` date NOT NULL COMMENT '巡检日期',
  `content` text COMMENT '台账内容/Markdown',
  `docx_path` varchar(512) DEFAULT NULL COMMENT '生成的docx文件路径',
  `sync_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '同步版本号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_inspection_date` (`inspection_date`),
  KEY `idx_sync_version` (`sync_version`),
  CONSTRAINT `fk_ledger_records_record` FOREIGN KEY (`record_id`) REFERENCES `inspection_records` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡查台账记录';

-- -----------------------------------------------------------------------
-- 全局同步版本序列
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sync_version_seq` (
  `id` int(11) NOT NULL DEFAULT '1',
  `next_val` bigint(20) NOT NULL DEFAULT '1' COMMENT '下一个版本号',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局同步版本序列';


-- =============================================================
-- queqiao_sync 数据库（鹊桥同步）
-- =============================================================
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
