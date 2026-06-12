-- 敏感词/违规词监控 + 转人工配置
ALTER TABLE `risk_config`
  ADD COLUMN IF NOT EXISTS `sensitive_monitor_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '启用客户话术敏感词监控',
  ADD COLUMN IF NOT EXISTS `human_transfer_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '命中敏感词转人工',
  ADD COLUMN IF NOT EXISTS `human_transfer_dest` varchar(200) DEFAULT NULL COMMENT 'FS转接目标 user/1001 或 sofia/gateway/xxx/号码',
  ADD COLUMN IF NOT EXISTS `human_transfer_prompt` varchar(200) DEFAULT '检测到需要人工协助，正在为您转接，请稍候。' COMMENT '转接前播报';

CREATE TABLE IF NOT EXISTS `sensitive_word` (
  `id` int NOT NULL AUTO_INCREMENT,
  `word` varchar(64) NOT NULL,
  `word_type` tinyint NOT NULL DEFAULT 1 COMMENT '1违规 2敏感',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
