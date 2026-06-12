-- 外呼任务：自动加微信（加 V）
USE `ai-call`;

ALTER TABLE `call_task`
  ADD COLUMN `auto_add_wechat` tinyint NOT NULL DEFAULT 0 COMMENT '接通后自动加微信' AFTER `task_rules`,
  ADD COLUMN `wechat_add_api_url` varchar(255) DEFAULT NULL COMMENT '加V接口地址' AFTER `auto_add_wechat`,
  ADD COLUMN `wechat_add_message` varchar(500) DEFAULT NULL COMMENT '加V招呼语模板' AFTER `wechat_add_api_url`,
  ADD COLUMN `wechat_add_remark` varchar(200) DEFAULT NULL COMMENT '加V备注模板' AFTER `wechat_add_message`;
