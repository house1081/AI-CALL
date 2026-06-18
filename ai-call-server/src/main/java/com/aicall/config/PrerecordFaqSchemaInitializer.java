package com.aicall.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PrerecordFaqSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void upgrade() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `prerecord_faq` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `question_display` varchar(256) NOT NULL COMMENT '展示问题',
                  `question_norm` varchar(256) NOT NULL COMMENT '规范化问题',
                  `category` varchar(32) NOT NULL DEFAULT '其他' COMMENT '问题分类',
                  `keywords` varchar(512) DEFAULT NULL COMMENT '逗号分隔关键词',
                  `answer_text` varchar(512) DEFAULT NULL COMMENT '标准应答文案',
                  `tier` varchar(24) NOT NULL DEFAULT 'high_freq' COMMENT 'high_freq|cold|transfer_only',
                  `hit_count` int NOT NULL DEFAULT 0 COMMENT '历史挖掘频次',
                  `match_count` int NOT NULL DEFAULT 0 COMMENT '运行时匹配次数',
                  `transfer_count` int NOT NULL DEFAULT 0 COMMENT '转顾问次数',
                  `enabled` tinyint NOT NULL DEFAULT 1,
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_prerecord_faq_norm` (`question_norm`),
                  KEY `idx_prerecord_faq_tier` (`tier`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `prerecord_audio_clip` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `faq_id` bigint DEFAULT NULL COMMENT '关联FAQ，null为全局片段',
                  `clip_type` varchar(24) NOT NULL COMMENT 'buffer|answer|closing|transfer等',
                  `text_content` varchar(512) NOT NULL,
                  `wav_path` varchar(512) DEFAULT NULL,
                  `variant_no` int NOT NULL DEFAULT 1,
                  `enabled` tinyint NOT NULL DEFAULT 1,
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_prerecord_clip_faq` (`faq_id`, `clip_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        addVoiceRuntimeColumn();
        log.info("[预录外呼] 表结构就绪");
    }

    private void addVoiceRuntimeColumn() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'voice_runtime_config' AND COLUMN_NAME = 'outbound_dialog_mode'",
                    Integer.class);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute("""
                    ALTER TABLE voice_runtime_config
                    ADD COLUMN outbound_dialog_mode varchar(32) NOT NULL DEFAULT 'ai_realtime'
                    COMMENT 'ai_realtime|smart_prerecord 外呼对话模式'
                    """);
            log.info("voice_runtime_config 已添加列 outbound_dialog_mode");
        } catch (Exception e) {
            log.warn("[预录外呼] 添加 outbound_dialog_mode 列失败: {}", e.getMessage());
        }
    }
}
