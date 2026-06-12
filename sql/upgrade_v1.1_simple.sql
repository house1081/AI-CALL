-- Simple version for Navicat / tools that do not support DELIMITER
-- If a column already exists, ignore "Duplicate column name" error and continue
USE `ai-call`;

ALTER TABLE `tenant`
  ADD COLUMN `pending_deduct` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT 'pending deduct' AFTER `balance`;

ALTER TABLE `call_record`
  ADD COLUMN `profit_abnormal` tinyint NOT NULL DEFAULT 0 COMMENT 'negative profit' AFTER `profit`;

ALTER TABLE `call_record`
  ADD COLUMN `billed_minutes` int NOT NULL DEFAULT 0 COMMENT 'billed minutes' AFTER `call_duration`;

ALTER TABLE `customer`
  ADD COLUMN `province` varchar(20) DEFAULT NULL COMMENT 'province' AFTER `name`;

CREATE TABLE IF NOT EXISTS `risk_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `line_id` int DEFAULT NULL,
  `tenant_id` int DEFAULT NULL,
  `phone` varchar(11) DEFAULT NULL,
  `risk_type` varchar(50) NOT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `system_notice` (
  `id` int NOT NULL AUTO_INCREMENT,
  `target_role` varchar(20) NOT NULL,
  `target_id` int DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `content` varchar(500) NOT NULL,
  `is_read` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_role`, `target_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `statistics_daily` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stat_date` date NOT NULL,
  `tenant_id` int DEFAULT NULL,
  `line_id` int DEFAULT NULL,
  `total_calls` int NOT NULL DEFAULT 0,
  `connected_calls` int NOT NULL DEFAULT 0,
  `total_duration_sec` bigint NOT NULL DEFAULT 0,
  `total_deduct` decimal(12,4) NOT NULL DEFAULT 0,
  `total_cost` decimal(12,4) NOT NULL DEFAULT 0,
  `total_profit` decimal(12,4) NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_tenant_line` (`stat_date`, `tenant_id`, `line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
