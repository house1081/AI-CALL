package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 敏感词表与 risk_config 转人工字段自动升级（启动早期执行）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void upgrade() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `sensitive_word` (
                  `id` int NOT NULL AUTO_INCREMENT,
                  `word` varchar(64) NOT NULL,
                  `word_type` tinyint NOT NULL DEFAULT 1 COMMENT '1违规 2敏感',
                  `status` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
                  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `uk_word` (`word`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        addRiskColumnIfMissing("sensitive_monitor_enabled",
                "ALTER TABLE risk_config ADD COLUMN sensitive_monitor_enabled tinyint NOT NULL DEFAULT 1 "
                        + "COMMENT '启用客户话术敏感词监控'");
        addRiskColumnIfMissing("human_transfer_enabled",
                "ALTER TABLE risk_config ADD COLUMN human_transfer_enabled tinyint NOT NULL DEFAULT 1 "
                        + "COMMENT '命中敏感词转人工'");
        addRiskColumnIfMissing("human_transfer_dest",
                "ALTER TABLE risk_config ADD COLUMN human_transfer_dest varchar(200) DEFAULT NULL "
                        + "COMMENT 'FS转接目标'");
        addRiskColumnIfMissing("human_transfer_prompt",
                "ALTER TABLE risk_config ADD COLUMN human_transfer_prompt varchar(200) DEFAULT "
                        + "'我马上为您转接人工坐席，请稍等' COMMENT '转接前播报'");

        seedDefaultWords();
        seedProductionRiskWords();
    }

    /** 量产风控词库：已有词库时补充缺失项 */
    private void seedProductionRiskWords() {
        String[][] production = {
                {"投诉", "2"}, {"举报", "2"}, {"银保监会", "2"}, {"监管", "2"}, {"打官司", "2"}, {"虚假宣传", "2"},
                {"不还", "2"}, {"没钱", "2"}, {"随便告", "2"}, {"拒绝还款", "2"}, {"无力偿还", "2"},
                {"减免", "2"}, {"延期", "2"}, {"停息", "2"}, {"政策申请", "2"}, {"特殊协商", "2"},
                {"辱骂", "2"}, {"纠缠", "2"}, {"拒绝沟通", "2"}, {"过激言论", "2"}
        };
        int added = 0;
        for (String[] row : production) {
            try {
                int n = jdbcTemplate.update(
                        "INSERT IGNORE INTO sensitive_word (word, word_type, status) VALUES (?, ?, 1)",
                        row[0], Integer.parseInt(row[1]));
                if (n > 0) {
                    added++;
                }
            } catch (Exception ignored) {
            }
        }
        if (added > 0) {
            log.info("[敏感词] 已补充量产风控词 {} 条", added);
        }
    }

    private void seedDefaultWords() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensitive_word", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String[][] defaults = {
                {"违法", "1"}, {"色情", "1"}, {"赌博", "1"}, {"毒品", "1"}, {"诈骗电话", "1"},
                {"投诉", "2"}, {"举报", "2"}, {"报警", "2"}, {"12321", "2"}, {"工信部", "2"},
                {"起诉", "2"}, {"律师", "2"}, {"监管", "2"}, {"骚扰", "2"},
                {"银保监会", "2"}, {"打官司", "2"}, {"虚假宣传", "2"},
                {"不还", "2"}, {"没钱", "2"}, {"随便告", "2"}, {"拒绝还款", "2"}, {"无力偿还", "2"},
                {"减免", "2"}, {"延期", "2"}, {"停息", "2"}, {"政策申请", "2"}, {"特殊协商", "2"},
                {"辱骂", "2"}, {"纠缠", "2"}, {"拒绝沟通", "2"}, {"过激言论", "2"}
        };
        for (String[] row : defaults) {
            try {
                jdbcTemplate.update(
                        "INSERT IGNORE INTO sensitive_word (word, word_type, status) VALUES (?, ?, 1)",
                        row[0], Integer.parseInt(row[1]));
            } catch (Exception ignored) {
            }
        }
        log.info("[敏感词] 已初始化默认词库 {} 条", defaults.length);
    }

    private void addRiskColumnIfMissing(String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'risk_config' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute(ddl);
            log.info("risk_config 已添加列 {}", column);
        } catch (Exception e) {
            log.warn("risk_config 列 {} 升级跳过: {}", column, e.getMessage());
        }
    }
}
