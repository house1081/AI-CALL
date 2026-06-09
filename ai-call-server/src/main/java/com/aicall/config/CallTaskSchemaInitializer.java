package com.aicall.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** call_task 表结构升级（任务级句末档位覆盖等）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallTaskSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfMissing("silence_profile",
                "ALTER TABLE call_task ADD COLUMN silence_profile varchar(16) DEFAULT NULL "
                        + "COMMENT 'stable|fast|null=跟随全局' AFTER task_rules");
    }

    private void addColumnIfMissing(String column, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'call_task' AND COLUMN_NAME = ?",
                    Integer.class, column);
            if (n != null && n > 0) {
                return;
            }
            jdbcTemplate.execute(ddl);
            log.info("call_task 已添加列 {}", column);
        } catch (Exception e) {
            log.warn("call_task 列 {} 升级跳过: {}", column, e.getMessage());
        }
    }
}
