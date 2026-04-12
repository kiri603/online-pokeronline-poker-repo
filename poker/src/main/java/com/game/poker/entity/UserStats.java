package com.game.poker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_stats")
public class UserStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "total_games", nullable = false)
    private int totalGames;

    @Column(nullable = false)
    private int wins;

    @Column(nullable = false)
    private int losses;

    @Column(nullable = false)
    private int experience;

    @Column(name = "last_daily_sign_in_at")
    private LocalDateTime lastDailySignInAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = LocalDateTime.now();
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    public int getTotalGames() {
        return totalGames;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getExperience() {
        return experience;
    }

    public LocalDateTime getLastDailySignInAt() {
        return lastDailySignInAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setTotalGames(int totalGames) {
        this.totalGames = totalGames;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public void setLosses(int losses) {
        this.losses = losses;
    }

    public void setExperience(int experience) {
        this.experience = experience;
    }

    public void setLastDailySignInAt(LocalDateTime lastDailySignInAt) {
        this.lastDailySignInAt = lastDailySignInAt;
    }
}
