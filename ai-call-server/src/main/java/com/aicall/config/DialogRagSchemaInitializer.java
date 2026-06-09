package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class DialogRagSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void upgrade() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `dialog_training_qa` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `question` varchar(500) NOT NULL COMMENT '用户问题/意图',
                  `standard_answer` varchar(1000) NOT NULL COMMENT '人工标准回复',
                  `data_type` tinyint NOT NULL DEFAULT 1 COMMENT '1人工修正 2优质样本 3负样本',
                  `weight` decimal(4,2) NOT NULL DEFAULT 1.00 COMMENT '检索权重加成',
                  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
                  `source_call_id` int DEFAULT NULL COMMENT '来源通话记录ID',
                  `remark` varchar(200) DEFAULT NULL,
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_status_type` (`status`, `data_type`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话训练标准问答（RAG）'
                """);
        try {
            jdbcTemplate.execute("""
                    ALTER TABLE `dialog_training_qa`
                      MODIFY COLUMN `standard_answer` varchar(2000) NOT NULL COMMENT '人工标准回复'
                    """);
        } catch (Exception e) {
            log.debug("[对话训练RAG] standard_answer 列宽升级跳过: {}", e.getMessage());
        }
        upgradeKbAndTemplateBinding();
        log.info("[对话训练RAG] 表 dialog_training_qa 已就绪");
    }

    private void upgradeKbAndTemplateBinding() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `dialog_knowledge_base` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `kb_name` varchar(100) NOT NULL,
                  `description` varchar(500) DEFAULT NULL,
                  `tenant_id` int DEFAULT NULL,
                  `pack_type` varchar(32) NOT NULL DEFAULT 'custom',
                  `status` tinyint NOT NULL DEFAULT 1,
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_tenant_status` (`tenant_id`, `status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        try {
            jdbcTemplate.update("""
                    INSERT INTO `dialog_knowledge_base` (`id`, `kb_name`, `description`, `tenant_id`, `pack_type`, `status`)
                    SELECT 1, '银行贷款标准话术库', '主线循序+关键词兜底话术包', NULL, 'loan', 1
                    FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `dialog_knowledge_base` WHERE `id` = 1)
                    """);
        } catch (Exception e) {
            log.debug("[对话训练RAG] 默认知识库跳过: {}", e.getMessage());
        }
        tryColumn("dialog_training_qa", "kb_id",
                "ALTER TABLE `dialog_training_qa` ADD COLUMN `kb_id` int NOT NULL DEFAULT 1 COMMENT '所属知识库' AFTER `id`");
        tryColumn("ai_prompt", "prompt_name",
                "ALTER TABLE `ai_prompt` ADD COLUMN `prompt_name` varchar(100) DEFAULT NULL COMMENT '模板名称' AFTER `id`");
        tryColumn("ai_prompt", "kb_id",
                "ALTER TABLE `ai_prompt` ADD COLUMN `kb_id` int DEFAULT NULL COMMENT '绑定训练知识库' AFTER `end_remarks`");
        tryColumn("tenant", "prompt_id",
                "ALTER TABLE `tenant` ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '话术模板ID' AFTER `daily_call_limit`");
        tryColumn("call_task", "prompt_id",
                "ALTER TABLE `call_task` ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '话术模板' AFTER `tenant_id`");
        tryColumn("call_record", "prompt_id",
                "ALTER TABLE `call_record` ADD COLUMN `prompt_id` int DEFAULT NULL COMMENT '快照-模板' AFTER `task_id`");
        tryColumn("call_record", "kb_id",
                "ALTER TABLE `call_record` ADD COLUMN `kb_id` int DEFAULT NULL COMMENT '快照-知识库' AFTER `prompt_id`");
        try {
            jdbcTemplate.update("UPDATE `dialog_training_qa` SET `kb_id` = 1 WHERE `kb_id` IS NULL OR `kb_id` = 0");
            jdbcTemplate.update("UPDATE `ai_prompt` SET `kb_id` = 1 WHERE `kb_id` IS NULL");
        } catch (Exception ignored) {
        }
    }

    private void tryColumn(String table, String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (n == null || n == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception e) {
            log.debug("[对话训练RAG] 列 {}.{} 升级跳过: {}", table, column, e.getMessage());
        }
    }
}
