package com.aicall.tools;

import com.aicall.config.TableCommentCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 {@link TableCommentCatalog} 中的注释写入 MySQL */
public class TableCommentApplier {

    private final JdbcTemplate jdbcTemplate;

    public TableCommentApplier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void apply() {
        Set<String> tables = queryExistingTables();
        int tableOk = 0;
        int colOk = 0;
        for (Map.Entry<String, String> e : TableCommentCatalog.tableComments().entrySet()) {
            if (!tables.contains(e.getKey())) {
                continue;
            }
            if (applyTableComment(e.getKey(), e.getValue())) {
                tableOk++;
            }
        }
        for (Map.Entry<String, Map<String, String>> tableEntry : TableCommentCatalog.columnComments().entrySet()) {
            String table = tableEntry.getKey();
            if (!tables.contains(table)) {
                continue;
            }
            for (Map.Entry<String, String> colEntry : tableEntry.getValue().entrySet()) {
                if (applyColumnComment(table, colEntry.getKey(), colEntry.getValue())) {
                    colOk++;
                }
            }
        }
        System.out.println("更新表注释 " + tableOk + " 张，字段注释 " + colOk + " 个");
    }

    private Set<String> queryExistingTables() {
        List<String> names = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class);
        return Set.copyOf(names);
    }

    private boolean applyTableComment(String table, String comment) {
        try {
            String current = jdbcTemplate.queryForObject(
                    "SELECT TABLE_COMMENT FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    String.class, table);
            if (comment.equals(current)) {
                return false;
            }
            jdbcTemplate.execute("ALTER TABLE `" + table + "` COMMENT='" + esc(comment) + "'");
            return true;
        } catch (Exception e) {
            System.err.println("表注释跳过 " + table + ": " + e.getMessage());
            return false;
        }
    }

    private boolean applyColumnComment(String table, String column, String comment) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                            + "FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    table, column);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> col = rows.get(0);
            String current = col.get("COLUMN_COMMENT") != null ? col.get("COLUMN_COMMENT").toString() : "";
            if (comment.equals(current)) {
                return false;
            }
            String sql = buildModifyColumnSql(table, column, col, comment);
            jdbcTemplate.execute(sql);
            return true;
        } catch (Exception e) {
            System.err.println("字段注释跳过 " + table + "." + column + ": " + e.getMessage());
            return false;
        }
    }

    private String buildModifyColumnSql(String table, String column, Map<String, Object> col, String comment) {
        String type = String.valueOf(col.get("COLUMN_TYPE"));
        String nullable = "NO".equals(String.valueOf(col.get("IS_NULLABLE"))) ? " NOT NULL" : " NULL";
        String defaultClause = buildDefaultClause(col);
        String extra = col.get("EXTRA") != null ? col.get("EXTRA").toString().trim() : "";
        String extraClause = extra.isEmpty() ? "" : " " + extra;
        return "ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` "
                + type + nullable + defaultClause + extraClause
                + " COMMENT '" + esc(comment) + "'";
    }

    private String buildDefaultClause(Map<String, Object> col) {
        Object def = col.get("COLUMN_DEFAULT");
        if (def == null) {
            return "";
        }
        String defStr = def.toString();
        if ("CURRENT_TIMESTAMP".equalsIgnoreCase(defStr)) {
            return " DEFAULT CURRENT_TIMESTAMP";
        }
        String dataType = String.valueOf(col.get("COLUMN_TYPE")).toLowerCase();
        if (dataType.contains("int") || dataType.contains("decimal") || dataType.contains("float")
                || dataType.contains("double") || dataType.contains("bit") || dataType.startsWith("tinyint")) {
            return " DEFAULT " + defStr;
        }
        return " DEFAULT '" + esc(defStr) + "'";
    }

    private static String esc(String s) {
        return s.replace("'", "''");
    }
}
