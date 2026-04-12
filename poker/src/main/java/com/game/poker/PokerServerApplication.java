package com.game.poker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.Locale;

@SpringBootApplication
public class PokerServerApplication {
    private static final Logger log = LoggerFactory.getLogger(PokerServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(PokerServerApplication.class, args);
    }

    @Bean
    ApplicationRunner legacySchemaCompatibilityRunner(JdbcTemplate jdbcTemplate) {
        return args -> {
            String databaseProduct;
            try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
                databaseProduct = connection.getMetaData().getDatabaseProductName();
            }
            if (databaseProduct == null || !databaseProduct.toLowerCase(Locale.ROOT).contains("h2")) {
                return;
            }

            ensureColumn(jdbcTemplate, "USER_STATS", "LAST_DAILY_SIGN_IN_AT", "TIMESTAMP");
            ensureColumn(jdbcTemplate, "USER_STATS", "EXPERIENCE", "INTEGER DEFAULT 0");
            jdbcTemplate.execute("UPDATE USER_STATS SET EXPERIENCE = 0 WHERE EXPERIENCE IS NULL");
            jdbcTemplate.execute("ALTER TABLE USER_STATS ALTER COLUMN EXPERIENCE SET DEFAULT 0");
            jdbcTemplate.execute("ALTER TABLE USER_STATS ALTER COLUMN EXPERIENCE SET NOT NULL");
            log.info("已完成 H2 旧版 user_stats 兼容迁移检查");
        };
    }

    private void ensureColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = ?
                          AND COLUMN_NAME = ?
                        """,
                Integer.class,
                tableName,
                columnName
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        log.info("已为表 {} 补充兼容列 {}", tableName, columnName);
    }

}
