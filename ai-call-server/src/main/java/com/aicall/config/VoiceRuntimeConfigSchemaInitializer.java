package com.aicall.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 外呼语音运行时配置表（句末档位、CosyVoice 音色、接通播报等）。
 * 使用 @PostConstruct 在启动早期完成 DDL，避免 MyBatis 查询缺列。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class VoiceRuntimeConfigSchemaInitializer {

    private static final String TURN_BASED = "turn-based";

    private final JdbcTemplate jdbcTemplate;
    private final AiVoiceProperties aiVoiceProperties;

    @PostConstruct
    public void upgrade() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `voice_runtime_config` (
                  `id` int NOT NULL COMMENT '固定为1',
                  `dialog_pipeline_mode` varchar(32) NOT NULL DEFAULT 'turn-based'
                    COMMENT 'legacy，固定 turn-based',
                  `omni_realtime_voice` varchar(128) NOT NULL DEFAULT 'Ethan' COMMENT 'legacy',
                  `omni_play_traditional_opening` tinyint NOT NULL DEFAULT 1 COMMENT 'legacy',
                  `play_opening_on_answer` tinyint NOT NULL DEFAULT 1 COMMENT '接通后播报开场白',
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        addColumnIfMissing("play_opening_on_answer",
                "ALTER TABLE voice_runtime_config ADD COLUMN play_opening_on_answer tinyint NOT NULL DEFAULT 1 "
                        + "COMMENT '接通后播报开场白'");

        addColumnIfMissing("cosyvoice_clone_voice_id",
                "ALTER TABLE voice_runtime_config ADD COLUMN cosyvoice_clone_voice_id varchar(128) DEFAULT NULL "
                        + "COMMENT 'CosyVoice复刻voice_id'");

        addColumnIfMissing("tts_voice_mode",
                "ALTER TABLE voice_runtime_config ADD COLUMN tts_voice_mode varchar(16) NOT NULL DEFAULT 'clone' "
                        + "COMMENT 'clone|system 音色来源'");

        addColumnIfMissing("cosyvoice_system_voice",
                "ALTER TABLE voice_runtime_config ADD COLUMN cosyvoice_system_voice varchar(64) DEFAULT 'longanyang' "
                        + "COMMENT '系统预置音色 voice 参数'");

        addColumnIfMissing("silence_profile",
                "ALTER TABLE voice_runtime_config ADD COLUMN silence_profile varchar(16) NOT NULL DEFAULT 'balanced' "
                        + "COMMENT 'balanced|stable|fast 句末档位'");

        seedCosyvoiceFromYmlIfEmpty();
        ensurePlayOpeningOnAnswerEnabled();
        migrateLegacyPipelineMode();
        syncSilenceProfileFromYml();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM voice_runtime_config WHERE id = 1", Integer.class);
        if (count == null || count == 0) {
            String silenceProfile = com.aicall.common.SilenceProfile.normalize(
                    aiVoiceProperties.getSilenceProfile());
            int opening = aiVoiceProperties.isPlayOpeningOnAnswer() ? 1 : 0;
            jdbcTemplate.update("""
                    INSERT INTO voice_runtime_config
                    (id, dialog_pipeline_mode, silence_profile, omni_realtime_voice,
                     omni_play_traditional_opening, play_opening_on_answer)
                    VALUES (1, ?, ?, 'Ethan', ?, ?)
                    """, TURN_BASED, silenceProfile, opening, opening);
            log.info("已初始化 voice_runtime_config 默认行 silenceProfile={} playOpening={}",
                    silenceProfile, opening);
        } else {
            jdbcTemplate.update("""
                    UPDATE voice_runtime_config SET play_opening_on_answer = omni_play_traditional_opening
                    WHERE id = 1 AND play_opening_on_answer IS NULL
                    """);
        }
    }

    /** 接通后 AI 先说：若库中误关则恢复为开启（与 application.yml 默认一致） */
    private void ensurePlayOpeningOnAnswerEnabled() {
        try {
            Integer flag = jdbcTemplate.queryForObject(
                    "SELECT play_opening_on_answer FROM voice_runtime_config WHERE id = 1", Integer.class);
            if (flag != null && flag == 0) {
                jdbcTemplate.update("""
                        UPDATE voice_runtime_config
                        SET play_opening_on_answer = 1, omni_play_traditional_opening = 1
                        WHERE id = 1
                        """);
                log.warn("[语音配置] 已开启接通播报（接通后 AI 先打招呼）");
            }
        } catch (Exception e) {
            log.trace("ensurePlayOpeningOnAnswerEnabled 跳过: {}", e.getMessage());
        }
    }

    private void syncSilenceProfileFromYml() {
        String profile = aiVoiceProperties.getSilenceProfile();
        if (!com.aicall.common.SilenceProfile.isValid(profile)) {
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM voice_runtime_config WHERE id = 1", Integer.class);
            if (count == null || count == 0) {
                return;
            }
            jdbcTemplate.update(
                    "UPDATE voice_runtime_config SET silence_profile = ? WHERE id = 1",
                    com.aicall.common.SilenceProfile.normalize(profile));
            log.info("[语音配置] 已从 application.yml 同步句末档位 silenceProfile={}",
                    com.aicall.common.SilenceProfile.normalize(profile));
        } catch (Exception e) {
            log.warn("[语音配置] 同步 silence-profile 失败: {}", e.getMessage());
        }
    }

    /** 历史 Omni 模式数据迁移为分段 */
    private void migrateLegacyPipelineMode() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM voice_runtime_config WHERE id = 1", Integer.class);
            if (count != null && count > 0) {
                jdbcTemplate.update(
                        "UPDATE voice_runtime_config SET dialog_pipeline_mode = ? WHERE id = 1",
                        TURN_BASED);
            }
            jdbcTemplate.update(
                    "UPDATE call_task SET dialog_pipeline_mode = ? WHERE dialog_pipeline_mode = 'omni-realtime'",
                    TURN_BASED);
        } catch (Exception e) {
            log.warn("[语音配置] 迁移 legacy 对话模式失败: {}", e.getMessage());
        }
    }

    private void seedCosyvoiceFromYmlIfEmpty() {
        String ymlVoice = aiVoiceProperties.getTtsCloneVoiceId();
        if (!org.springframework.util.StringUtils.hasText(ymlVoice)) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    UPDATE voice_runtime_config SET cosyvoice_clone_voice_id = ?
                    WHERE id = 1 AND (cosyvoice_clone_voice_id IS NULL OR cosyvoice_clone_voice_id = '')
                    """, ymlVoice.trim());
        } catch (Exception e) {
            log.trace("voice_runtime_config cosyvoice 种子跳过: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'voice_runtime_config' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute(ddl);
            log.info("voice_runtime_config 已添加列 {}", column);
        } catch (Exception e) {
            log.error("voice_runtime_config 列 {} 升级失败: {}", column, e.getMessage());
        }
    }
}
