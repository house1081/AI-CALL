package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 租户坐席表 + 通话记录点呼字段 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantAgentSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void upgrade() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `tenant_agent` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `tenant_id` int NOT NULL,
                  `name` varchar(64) NOT NULL COMMENT '坐席显示名',
                  `dest_type` varchar(16) NOT NULL DEFAULT 'ext' COMMENT 'ext分机 mobile手机',
                  `fs_dest` varchar(200) NOT NULL COMMENT 'FS目标或手机号',
                  `mobile` varchar(20) DEFAULT NULL COMMENT '展示用手机号',
                  `line_id` int DEFAULT NULL COMMENT '手机坐席外呼线路',
                  `status` tinyint NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
                  `presence` varchar(16) NOT NULL DEFAULT 'offline' COMMENT 'offline/available/busy',
                  `sort_order` int NOT NULL DEFAULT 0,
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_tenant_presence` (`tenant_id`, `status`, `presence`, `sort_order`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户坐席'
                """);
        tryColumn("call_record", "agent_id",
                "ALTER TABLE `call_record` ADD COLUMN `agent_id` int DEFAULT NULL "
                        + "COMMENT '坐席ID' AFTER `kb_id`");
        tryColumn("call_record", "call_mode",
                "ALTER TABLE `call_record` ADD COLUMN `call_mode` varchar(32) DEFAULT NULL "
                        + "COMMENT 'ai_task/click_call/human_transfer' AFTER `agent_id`");
        log.info("[坐席] tenant_agent / call_record 字段检查完成");
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
            log.info("[坐席] 已添加列 {}.{}", table, column);
        } catch (Exception e) {
            log.warn("[坐席] 添加列 {}.{} 跳过: {}", table, column, e.getMessage());
        }
    }
}
