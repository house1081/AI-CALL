-- AI 模型统一配置（管理后台切换，所有对话/摘要共用）
USE `ai-call`;

CREATE TABLE IF NOT EXISTS `ai_model_config` (
  `id` int NOT NULL AUTO_INCREMENT,
  `config_name` varchar(100) NOT NULL COMMENT '配置名称',
  `provider` varchar(20) NOT NULL COMMENT 'ollama|qwen|wenxin',
  `base_url` varchar(300) NOT NULL DEFAULT '',
  `model_name` varchar(100) NOT NULL DEFAULT '',
  `api_key` varchar(500) DEFAULT '',
  `secret_key` varchar(500) DEFAULT '' COMMENT '文心 secret',
  `max_tokens` int NOT NULL DEFAULT 80,
  `temperature` decimal(3,2) NOT NULL DEFAULT 0.70,
  `max_history_rounds` int NOT NULL DEFAULT 3,
  `connect_timeout_ms` int NOT NULL DEFAULT 5000,
  `read_timeout_ms` int NOT NULL DEFAULT 60000,
  `is_active` tinyint NOT NULL DEFAULT 0,
  `remark` varchar(200) DEFAULT '',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_model_config` (`config_name`, `provider`, `base_url`, `model_name`, `api_key`, `secret_key`,
  `max_tokens`, `temperature`, `max_history_rounds`, `is_active`, `remark`) VALUES
('本地 Ollama', 'ollama', 'http://192.168.60.28:11434', 'qwen:4b', '', '', 80, 0.70, 3, 1, '内网 Ollama，与 application.yml 默认一致'),
('通义千问 DashScope', 'qwen', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-turbo', '', '', 80, 0.70, 3, 0, '填写 API Key 后启用'),
('文心一言 千帆', 'wenxin', 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions', 'completions', '', '', 80, 0.70, 3, 0, '填写 API Key 与 Secret Key 后启用');
