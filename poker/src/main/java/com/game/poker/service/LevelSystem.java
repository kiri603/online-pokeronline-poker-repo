package com.game.poker.service;

public final class LevelSystem {
    private static final int[] LEVEL_THRESHOLDS = {0, 25, 50, 100, 200, 400, 800, 1600, 3200, 6400};
    private static final int MAX_LEVEL = 10;
    private static final int MAX_EXPERIENCE = LEVEL_THRESHOLDS[LEVEL_THRESHOLDS.length - 1];

    private LevelSystem() {
    }

    public static int maxLevel() {
        return MAX_LEVEL;
    }

    public static int maxExperience() {
        return MAX_EXPERIENCE;
    }

    public static int clampExperience(int experience) {
        return Math.max(0, Math.min(MAX_EXPERIENCE, experience));
    }

    public static int gainBattleExperience(int experience, boolean won) {
        int gained = 1 + (won ? 1 : 0);
        return clampExperience(experience + gained);
    }

    public static int gainDailySignInExperience(int experience) {
        return clampExperience(experience + 3);
    }

    public static int resolveLevel(int experience) {
        int normalized = clampExperience(experience);
        for (int level = MAX_LEVEL; level >= 1; level--) {
            if (normalized >= LEVEL_THRESHOLDS[level - 1]) {
                return level;
            }
        }
        return 1;
    }

    public static int currentLevelBaseExperience(int experience) {
        return LEVEL_THRESHOLDS[resolveLevel(experience) - 1];
    }

    public static int nextLevelRequiredExperience(int experience) {
        int currentLevel = resolveLevel(experience);
        if (currentLevel >= MAX_LEVEL) {
            return MAX_EXPERIENCE;
        }
        return LEVEL_THRESHOLDS[currentLevel];
    }

    public static double progressPercent(int experience) {
        int currentLevel = resolveLevel(experience);
        if (currentLevel >= MAX_LEVEL) {
            return 100D;
        }
        int normalized = clampExperience(experience);
        int levelBase = LEVEL_THRESHOLDS[currentLevel - 1];
        int nextLevel = LEVEL_THRESHOLDS[currentLevel];
        if (nextLevel <= levelBase) {
            return 100D;
        }
        double ratio = (normalized - levelBase) * 100D / (nextLevel - levelBase);
        return Math.max(0D, Math.min(100D, ratio));
    }
}
