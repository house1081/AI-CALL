USE `ai-call`;

DELIMITER $$

DROP PROCEDURE IF EXISTS `proc_upgrade_v12`$$
CREATE PROCEDURE `proc_upgrade_v12`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_record' AND COLUMN_NAME = 'prepaid_amount'
  ) THEN
    ALTER TABLE `call_record`
      ADD COLUMN `prepaid_amount` decimal(10,4) NOT NULL DEFAULT 0.0000 COMMENT 'call realtime prepaid' AFTER `billed_minutes`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_record' AND COLUMN_NAME = 'sell_price_snapshot'
  ) THEN
    ALTER TABLE `call_record`
      ADD COLUMN `sell_price_snapshot` decimal(10,4) DEFAULT NULL COMMENT 'sell price at call time' AFTER `prepaid_amount`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_record' AND COLUMN_NAME = 'cost_price_snapshot'
  ) THEN
    ALTER TABLE `call_record`
      ADD COLUMN `cost_price_snapshot` decimal(10,4) DEFAULT NULL COMMENT 'line cost at call time' AFTER `sell_price_snapshot`;
  END IF;
END$$

DELIMITER ;

CALL `proc_upgrade_v12`();
DROP PROCEDURE IF EXISTS `proc_upgrade_v12`;

CREATE TABLE IF NOT EXISTS `statistics_monthly` (
  `id` int NOT NULL AUTO_INCREMENT,
  `stat_month` varchar(7) NOT NULL COMMENT 'YYYY-MM',
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
  UNIQUE KEY `uk_month_tenant_line` (`stat_month`, `tenant_id`, `line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
