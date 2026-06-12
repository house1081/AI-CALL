-- 话术模板 ↔ 训练知识库 ↔ 商户 ↔ 外呼任务 绑定

CREATE TABLE IF NOT EXISTS `dialog_knowledge_base` (
  `id` int NOT NULL AUTO_INCREMENT,
  `kb_name` varchar(100) NOT NULL COMMENT '知识库名称',
  `description` varchar(500) DEFAULT NULL,
  `tenant_id` int DEFAULT NULL COMMENT 'NULL=平台公共库',
  `pack_type` varchar(32) NOT NULL DEFAULT 'custom' COMMENT 'custom|loan',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话训练知识库';

INSERT INTO `dialog_knowledge_base` (`id`, `kb_name`, `description`, `tenant_id`, `pack_type`, `status`)
SELECT 1, '银行贷款标准话术库', '主线循序+关键词兜底话术包', NULL, 'loan', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `dialog_knowledge_base` WHERE `id` = 1);

ALTER TABLE `dialog_training_qa`
  ADD COLUMN `kb_id` int NOT NULL DEFAULT 1 COMMENT '所属知识库' AFTER `id`,
  ADD KEY `idx_kb_status` (`kb_id`, `status`);

UPDATE `dialog_training_qa` SET `kb_id` = 1 WHERE `kb_id` IS NULL OR `kb_id` = 0;

ALTER TABLE `ai_prompt`
  ADD COLUMN `prompt_name` varchar(100) DEFAULT NULL COMMENT '模板名称' AFTER `id`,
  ADD COLUMN `kb_id` int DEFAULT NULL COMMENT '绑定训练知识库' AFTER `end_remarks`;

UPDATE `ai_prompt` SET `kb_id` = 1 WHERE `kb_id` IS NULL;
UPDATE `ai_prompt` SET `prompt_name` = CONCAT('模板', `id`) WHERE `prompt_name` IS NULL OR `prompt_name` = '';

ALTER TABLE `tenant`
  ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '话术模板ID' AFTER `daily_call_limit`;

ALTER TABLE `call_task`
  ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '话术模板(空则继承商户)' AFTER `tenant_id`;

ALTER TABLE `call_record`
  ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '通话快照-模板' AFTER `task_id`,
  ADD COLUMN `kb_id` int DEFAULT NULL COMMENT '通话快照-知识库' AFTER `prompt_id`;
