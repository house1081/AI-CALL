package com.aicall.config;

import com.aicall.tools.TableCommentApplier;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 为数据库表/字段补充中文 COMMENT（幂等，可重复执行）。
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class TableCommentSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void applyOnStartup() {
        try {
            apply();
        } catch (Exception e) {
            log.warn("[表注释] 自动补充失败: {}", e.getMessage());
        }
    }

    public void apply() {
        new TableCommentApplier(jdbcTemplate).apply();
        log.info("[表注释] 补充完成");
    }
}
