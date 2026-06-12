-- P1 性能：组合索引（已有库执行一次即可）
USE `ai-call`;

-- call_record：统计/列表/最新通话
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'call_record' AND index_name = 'idx_tenant_call_time');
SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE `call_record` ADD INDEX `idx_tenant_call_time` (`tenant_id`, `call_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'call_record' AND index_name = 'idx_tenant_phone_id');
SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE `call_record` ADD INDEX `idx_tenant_phone_id` (`tenant_id`, `customer_phone`, `id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'call_record' AND index_name = 'idx_profit_abnormal');
SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE `call_record` ADD INDEX `idx_profit_abnormal` (`profit_abnormal`, `call_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- balance_log：账单导出
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'balance_log' AND index_name = 'idx_tenant_create_time');
SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE `balance_log` ADD INDEX `idx_tenant_create_time` (`tenant_id`, `create_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- customer：意向列表
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'customer' AND index_name = 'idx_tenant_level');
SET @sql = IF(@idx_exists = 0,
  'ALTER TABLE `customer` ADD INDEX `idx_tenant_level` (`tenant_id`, `level`, `last_call_time`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
