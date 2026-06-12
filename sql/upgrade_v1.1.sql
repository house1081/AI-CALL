-- MySQL 5.7/8.0 compatible upgrade (does not support ADD COLUMN IF NOT EXISTS)
USE `ai-call`;

DELIMITER $$

DROP PROCEDURE IF EXISTS `proc_upgrade_v11`$$
CREATE PROCEDURE `proc_upgrade_v11`()
BEGIN
  -- tenant.pending_deduct
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tenant' AND COLUMN_NAME = 'pending_deduct'
  ) THEN
    ALTER TABLE `tenant`
      ADD COLUMN `pending_deduct` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT 'pending deduct amount' AFTER `balance`;
  END IF;

  -- call_record.profit_abnormal
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_record' AND COLUMN_NAME = 'profit_abnormal'
  ) THEN
    ALTER TABLE `call_record`
      ADD COLUMN `profit_abnormal` tinyint NOT NULL DEFAULT 0 COMMENT 'negative profit flag' AFTER `profit`;
  END IF;

  -- call_record.billed_minutes
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_record' AND COLUMN_NAME = 'billed_minutes'
  ) THEN
    ALTER TABLE `call_record`
      ADD COLUMN `billed_minutes` int NOT NULL DEFAULT 0 COMMENT 'billed minutes' AFTER `call_duration`;
  END IF;

  -- customer.province
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer' AND COLUMN_NAME = 'province'
  ) THEN
    ALTER TABLE `customer`
      ADD COLUMN `province` varchar(20) DEFAULT NULL COMMENT 'province' AFTER `name`;
  END IF;
END$$

DELIMITER ;

CALL `proc_upgrade_v11`();
DROP PROCEDURE IF EXISTS `proc_upgrade_v11`;

-- New tables (safe to re-run)
CREATE TABLE IF NOT EXISTS `risk_log` (
  `id` int NOT NULL AUTO_INCREMENT,
  `line_id` int DEFAULT NULL,
  `tenant_id` int DEFAULT NULL,
  `phone` varchar(11) DEFAULT NULL,
  `risk_type` varchar(50) NOT NULL COMMENT 'short_call/line_limit/area_block',
  `remark` varchar(200) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `system_notice` (
  `id` int NOT NULL AUTO_INCREMENT,
  `target_role` varchar(20) NOT NULL COMMENT 'admin/tenant',
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
