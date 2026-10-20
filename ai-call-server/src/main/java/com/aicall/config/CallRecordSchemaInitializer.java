package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 通话记录商业级字段升级（exit_reason 等） */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallRecordSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void upgrade() {
        tryColumn("call_record", "exit_reason",
                "ALTER TABLE `call_record` ADD COLUMN `exit_reason` varchar(64) DEFAULT NULL "
                        + "COMMENT '技术退出原因：customer_hangup_late/ai_hangup/...' AFTER `hangup_type`");
        tryColumn("call_record", "dialog_rounds",
                "ALTER TABLE `call_record` ADD COLUMN `dialog_rounds` int DEFAULT NULL "
                        + "COMMENT '对话轮次' AFTER `exit_reason`");
        tryColumn("call_record", "ai_hangup",
                "ALTER TABLE `call_record` ADD COLUMN `ai_hangup` tinyint DEFAULT NULL "
                        + "COMMENT '是否AI主动挂机：1是0否' AFTER `dialog_rounds`");
        tryColumn("call_record", "agent_id",
                "ALTER TABLE `call_record` ADD COLUMN `agent_id` int DEFAULT NULL "
                        + "COMMENT '坐席ID' AFTER `kb_id`");
        tryColumn("call_record", "call_mode",
                "ALTER TABLE `call_record` ADD COLUMN `call_mode` varchar(32) DEFAULT NULL "
                        + "COMMENT 'ai_task/click_call/human_transfer' AFTER `agent_id`");
    }

    private void tryColumn(String table, String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute(ddl);
            log.info("[通话记录] 已添加列 {}.{}", table, column);
        } catch (Exception e) {
            log.warn("[通话记录] 添加列 {}.{} 跳过: {}", table, column, e.getMessage());
        }
    }
}
