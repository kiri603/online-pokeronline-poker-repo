package com.game.poker.service;

import com.game.poker.config.DataMigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class DataMigrationService {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationService.class);
    private static final List<String> TABLE_ORDER = List.of(
            "USER_ACCOUNT",
            "USER_STATS",
            "USER_LOGIN_LOG",
            "REMEMBER_LOGIN_TOKEN",
            "FRIEND_RELATION",
            "FRIEND_REQUEST",
            "DIRECT_MESSAGE",
            "ROOM_INVITE",
            "GAME_RECORD",
            "GAME_RECORD_PARTICIPANT"
    );

    private final DataSource targetDataSource;
    private final DataMigrationProperties properties;
    private final ObjectMapper objectMapper;

    public DataMigrationService(DataSource targetDataSource,
                                DataMigrationProperties properties,
                                ObjectMapper objectMapper) {
        this.targetDataSource = targetDataSource;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DataMigrationReport migrateH2ToCurrentDataSource() {
        DataMigrationReport report = new DataMigrationReport();
        report.setMode("h2-to-mysql");
        Path reportFile = resolveReportFile();
        report.setReportFile(reportFile.toAbsolutePath().toString());

        try (Connection source = DriverManager.getConnection(
                properties.getH2().getUrl(),
                properties.getH2().getUsername(),
                properties.getH2().getPassword()
        ); Connection target = targetDataSource.getConnection()) {
            report.setSourceDatabaseProduct(source.getMetaData().getDatabaseProductName());
            report.setTargetDatabaseProduct(target.getMetaData().getDatabaseProductName());
            ensureSourceIsH2(source);
            ensureTargetIsMysql(target);

            collectCounts(source, report.getSourceCounts());
            collectCounts(target, report.getTargetCountsBefore());
            ensureTargetTablesAreEmpty(report.getTargetCountsBefore());

            target.setAutoCommit(false);
            try {
                for (String table : TABLE_ORDER) {
                    migrateTable(source, target, table, report);
                }
                collectCounts(target, report.getTargetCountsAfter());
                validateCounts(report);
                validateRelations(target, report);
                collectSampleUsers(target, report);
                target.commit();
                report.setSuccess(true);
                report.getMessages().add("所有关键表迁移成功，校验通过");
            } catch (Exception exception) {
                target.rollback();
                report.getMessages().add("迁移失败，已回滚: " + exception.getMessage());
                throw exception;
            } finally {
                target.setAutoCommit(true);
            }
            writeReport(reportFile, report);
            log.info("H2 -> MySQL 数据迁移完成，报告已写入 {}", reportFile.toAbsolutePath());
            return report;
        } catch (Exception exception) {
            report.setSuccess(false);
            report.getMessages().add("迁移失败: " + exception.getMessage());
            writeReport(reportFile, report);
            throw new IllegalStateException("H2 -> MySQL 数据迁移失败", exception);
        }
    }

    private void ensureSourceIsH2(Connection source) throws SQLException {
        String product = source.getMetaData().getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("h2")) {
            throw new IllegalStateException("迁移源数据库不是 H2: " + product);
        }
    }

    private void ensureTargetIsMysql(Connection target) throws SQLException {
        String product = target.getMetaData().getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            throw new IllegalStateException("迁移目标数据库不是 MySQL: " + product);
        }
    }

    private void ensureTargetTablesAreEmpty(Map<String, Long> targetCountsBefore) {
        List<String> nonEmptyTables = targetCountsBefore.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0L)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        if (!nonEmptyTables.isEmpty()) {
            throw new IllegalStateException("目标库不是空库，拒绝执行迁移: " + String.join(", ", nonEmptyTables));
        }
    }

    private void collectCounts(Connection connection, Map<String, Long> sink) throws SQLException {
        for (String table : TABLE_ORDER) {
            sink.put(table, queryCount(connection, table));
        }
    }

    private long queryCount(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void migrateTable(Connection source, Connection target, String table, DataMigrationReport report) throws SQLException {
        List<String> columns = resolveColumns(source, table);
        if (columns.isEmpty()) {
            throw new IllegalStateException("表 " + table + " 没有可迁移列");
        }

        StringJoiner columnJoiner = new StringJoiner(", ");
        StringJoiner placeholderJoiner = new StringJoiner(", ");
        for (String column : columns) {
            columnJoiner.add(column);
            placeholderJoiner.add("?");
        }

        String selectSql = "SELECT " + columnJoiner + " FROM " + table;
        String insertSql = "INSERT INTO " + table + " (" + columnJoiner + ") VALUES (" + placeholderJoiner + ")";
        int migratedRows = 0;
        try (PreparedStatement select = source.prepareStatement(selectSql);
             ResultSet resultSet = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            int batchSize = 0;
            while (resultSet.next()) {
                for (int index = 0; index < columns.size(); index++) {
                    insert.setObject(index + 1, resultSet.getObject(index + 1));
                }
                insert.addBatch();
                batchSize++;
                migratedRows++;
                if (batchSize >= 200) {
                    insert.executeBatch();
                    batchSize = 0;
                }
            }
            if (batchSize > 0) {
                insert.executeBatch();
            }
        }
        report.getMessages().add("已迁移 " + table + " " + migratedRows + " 行");
    }

    private List<String> resolveColumns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE 1 = 0");
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metadata = resultSet.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                columns.add(metadata.getColumnLabel(index));
            }
        }
        return columns;
    }

    private void validateCounts(DataMigrationReport report) {
        for (String table : TABLE_ORDER) {
            long sourceCount = report.getSourceCounts().getOrDefault(table, -1L);
            long targetCount = report.getTargetCountsAfter().getOrDefault(table, -2L);
            if (sourceCount != targetCount) {
                throw new IllegalStateException("表 " + table + " 行数不一致，source=" + sourceCount + ", target=" + targetCount);
            }
        }
        long userCount = report.getTargetCountsAfter().getOrDefault("USER_ACCOUNT", 0L);
        long statsCount = report.getTargetCountsAfter().getOrDefault("USER_STATS", 0L);
        if (userCount != statsCount) {
            throw new IllegalStateException("USER_ACCOUNT 与 USER_STATS 数量不一致");
        }
    }

    private void validateRelations(Connection target, DataMigrationReport report) throws SQLException {
        long participantCount = report.getTargetCountsAfter().getOrDefault("GAME_RECORD_PARTICIPANT", 0L);
        try (PreparedStatement statement = target.prepareStatement(
                """
                        SELECT COUNT(*)
                        FROM GAME_RECORD_PARTICIPANT participant
                        JOIN GAME_RECORD record ON record.ID = participant.GAME_RECORD_ID
                        """
        ); ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            long joinedCount = resultSet.getLong(1);
            if (joinedCount != participantCount) {
                throw new IllegalStateException("GAME_RECORD_PARTICIPANT 与 GAME_RECORD 关联数量不一致");
            }
        }
        report.getMessages().add("关键关联校验通过");
    }

    private void collectSampleUsers(Connection target, DataMigrationReport report) throws SQLException {
        try (PreparedStatement statement = target.prepareStatement(
                """
                        SELECT account.ID,
                               account.USERNAME,
                               account.NICKNAME,
                               account.CREATED_AT,
                               stats.TOTAL_GAMES,
                               stats.WINS,
                               stats.LOSSES,
                               stats.EXPERIENCE,
                               (SELECT COUNT(*) FROM FRIEND_RELATION relation
                                 WHERE relation.USER_A_ID = account.USERNAME OR relation.USER_B_ID = account.USERNAME) AS FRIEND_COUNT,
                               (SELECT COUNT(DISTINCT participant.GAME_RECORD_ID)
                                  FROM GAME_RECORD_PARTICIPANT participant
                                 WHERE participant.USER_ID = account.USERNAME) AS RECENT_RECORD_COUNT
                        FROM USER_ACCOUNT account
                        LEFT JOIN USER_STATS stats ON stats.USER_ID = account.ID
                        ORDER BY account.ID
                        LIMIT 3
                        """
        ); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("id", resultSet.getLong("ID"));
                sample.put("username", resultSet.getString("USERNAME"));
                sample.put("nickname", resultSet.getString("NICKNAME"));
                sample.put("createdAt", resultSet.getString("CREATED_AT"));
                sample.put("totalGames", resultSet.getInt("TOTAL_GAMES"));
                sample.put("wins", resultSet.getInt("WINS"));
                sample.put("losses", resultSet.getInt("LOSSES"));
                sample.put("experience", resultSet.getInt("EXPERIENCE"));
                sample.put("friendCount", resultSet.getLong("FRIEND_COUNT"));
                sample.put("recentRecordCount", resultSet.getLong("RECENT_RECORD_COUNT"));
                report.getSampleUsers().add(sample);
            }
        }
    }

    private Path resolveReportFile() {
        String fileName = "h2-to-mysql-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".json";
        Path directory = Path.of(properties.getReportDirectory());
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建迁移报告目录: " + directory, exception);
        }
        return directory.resolve(fileName);
    }

    private void writeReport(Path reportFile, DataMigrationReport report) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        } catch (Exception exception) {
            log.warn("写入迁移报告失败: {}", reportFile, exception);
        }
    }
}
