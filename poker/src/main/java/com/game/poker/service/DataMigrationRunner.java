package com.game.poker.service;

import com.game.poker.config.DataMigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.data-migration", name = "mode", havingValue = "h2-to-mysql")
public class DataMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final DataMigrationService dataMigrationService;
    private final DataMigrationProperties properties;
    private final ConfigurableApplicationContext applicationContext;

    public DataMigrationRunner(DataMigrationService dataMigrationService,
                               DataMigrationProperties properties,
                               ConfigurableApplicationContext applicationContext) {
        this.dataMigrationService = dataMigrationService;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("检测到数据迁移模式 {}，开始执行 H2 -> MySQL 一次性迁移", properties.getMode());
        try {
            DataMigrationReport report = dataMigrationService.migrateH2ToCurrentDataSource();
            log.info("数据迁移完成，success={}, report={}", report.isSuccess(), report.getReportFile());
            shutdown(0);
        } catch (Exception exception) {
            log.error("数据迁移失败", exception);
            shutdown(1);
        }
    }

    private void shutdown(int exitCode) {
        SpringApplication.exit(applicationContext, () -> exitCode);
        System.exit(exitCode);
    }
}
