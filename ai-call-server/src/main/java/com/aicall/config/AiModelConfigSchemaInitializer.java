package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时自动创建 ai_model_config 表并写入默认配置（兼容未执行 upgrade_ai_model.sql 的库）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConfigSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final OllamaProperties ollamaProperties;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `ai_model_config` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `config_name` varchar(100) NOT NULL COMMENT '配置名称',
                  `provider` varchar(20) NOT NULL COMMENT 'ollama|qwen|wenxin',
                  `base_url` varchar(300) NOT NULL DEFAULT '',
                  `model_name` varchar(100) NOT NULL DEFAULT '',
                  `api_key` varchar(500) DEFAULT '',
                  `secret_key` varchar(500) DEFAULT '',
                  `max_tokens` int NOT NULL DEFAULT 80,
                  `temperature` decimal(3,2) NOT NULL DEFAULT 0.70,
                  `max_history_rounds` int NOT NULL DEFAULT 3,
                  `connect_timeout_ms` int NOT NULL DEFAULT 5000,
                  `read_timeout_ms` int NOT NULL DEFAULT 60000,
                  `is_active` tinyint NOT NULL DEFAULT 0,
                  `remark` varchar(200) DEFAULT '',
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_model_config", Integer.class);
        if (count != null && count > 0) {
            return;
        }

        String ollamaUrl = ollamaProperties.getBaseUrl();
        String ollamaModel = ollamaProperties.getModel();
        jdbcTemplate.update("""
                INSERT INTO ai_model_config
                (config_name, provider, base_url, model_name, api_key, secret_key,
                 max_tokens, temperature, max_history_rounds, is_active, remark)
                VALUES (?, 'ollama', ?, ?, '', '', 80, 0.70, 3, 1, ?)
                """, "本地 Ollama", ollamaUrl, ollamaModel, "默认 Ollama 配置");
        jdbcTemplate.update("""
                INSERT INTO ai_model_config
                (config_name, provider, base_url, model_name, max_tokens, temperature, max_history_rounds, is_active, remark)
                VALUES ('通义千问 DashScope', 'qwen',
                'https://dashscope.aliyuncs.com/compatible-mode/v1', 'qwen-turbo',
                80, 0.70, 3, 0, '填写 API Key 后启用')
                """);
        jdbcTemplate.update("""
                INSERT INTO ai_model_config
                (config_name, provider, base_url, model_name, max_tokens, temperature, max_history_rounds, is_active, remark)
                VALUES ('文心一言 千帆', 'wenxin',
                'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions', 'completions',
                80, 0.70, 3, 0, '填写 API Key 与 Secret Key 后启用')
                """);
        log.warn("已自动初始化 ai_model_config 表及默认模型配置，请重启后访问「模型配置」");
    }
}
