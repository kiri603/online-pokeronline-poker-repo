package com.game.poker.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataMigrationReport {
    private String mode;
    private String sourceDatabaseProduct;
    private String targetDatabaseProduct;
    private boolean success;
    private String reportFile;
    private final Map<String, Long> sourceCounts = new LinkedHashMap<>();
    private final Map<String, Long> targetCountsBefore = new LinkedHashMap<>();
    private final Map<String, Long> targetCountsAfter = new LinkedHashMap<>();
    private final List<Map<String, Object>> sampleUsers = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSourceDatabaseProduct() {
        return sourceDatabaseProduct;
    }

    public void setSourceDatabaseProduct(String sourceDatabaseProduct) {
        this.sourceDatabaseProduct = sourceDatabaseProduct;
    }

    public String getTargetDatabaseProduct() {
        return targetDatabaseProduct;
    }

    public void setTargetDatabaseProduct(String targetDatabaseProduct) {
        this.targetDatabaseProduct = targetDatabaseProduct;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReportFile() {
        return reportFile;
    }

    public void setReportFile(String reportFile) {
        this.reportFile = reportFile;
    }

    public Map<String, Long> getSourceCounts() {
        return sourceCounts;
    }

    public Map<String, Long> getTargetCountsBefore() {
        return targetCountsBefore;
    }

    public Map<String, Long> getTargetCountsAfter() {
        return targetCountsAfter;
    }

    public List<Map<String, Object>> getSampleUsers() {
        return sampleUsers;
    }

    public List<String> getMessages() {
        return messages;
    }
}
