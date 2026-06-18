package com.aicall.tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 独立执行：为数据库表/字段补充中文 COMMENT。
 * 用法：mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.aicall.tools.ApplyTableCommentsMain
 */
public class ApplyTableCommentsMain {

    public static void main(String[] args) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/ai-call?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        cfg.setUsername("root");
        cfg.setPassword("root");
        cfg.setMaximumPoolSize(2);
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            TableCommentApplier applier = new TableCommentApplier(jdbc);
            applier.apply();
            System.out.println("表结构注释已更新完成");
        }
    }
}
