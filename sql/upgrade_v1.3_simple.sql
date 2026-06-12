-- P1 性能索引（Navicat 直接执行，重复执行若报 Duplicate key 可忽略）
USE `ai-call`;

ALTER TABLE `call_record` ADD INDEX `idx_tenant_call_time` (`tenant_id`, `call_time`);
ALTER TABLE `call_record` ADD INDEX `idx_tenant_phone_id` (`tenant_id`, `customer_phone`, `id`);
ALTER TABLE `call_record` ADD INDEX `idx_profit_abnormal` (`profit_abnormal`, `call_time`);
ALTER TABLE `balance_log` ADD INDEX `idx_tenant_create_time` (`tenant_id`, `create_time`);
ALTER TABLE `customer` ADD INDEX `idx_tenant_level` (`tenant_id`, `level`, `last_call_time`);
