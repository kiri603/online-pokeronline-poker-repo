package com.game.poker.service;

import com.game.poker.auth.SessionUser;
import com.game.poker.entity.UserAccount;
import com.game.poker.entity.UserLoginLog;
import com.game.poker.entity.UserStats;
import com.game.poker.repository.UserAccountRepository;
import com.game.poker.repository.UserLoginLogRepository;
import com.game.poker.repository.UserStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserService {
    private static final int LOGIN_USER_AGENT_MAX_LENGTH = 255;

    private final UserAccountRepository userAccountRepository;
    private final UserLoginLogRepository userLoginLogRepository;
    private final UserStatsRepository userStatsRepository;

    public UserService(UserAccountRepository userAccountRepository,
                       UserLoginLogRepository userLoginLogRepository,
                       UserStatsRepository userStatsRepository) {
        this.userAccountRepository = userAccountRepository;
        this.userLoginLogRepository = userLoginLogRepository;
        this.userStatsRepository = userStatsRepository;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return userAccountRepository.findByUsername(username);
    }

    public Optional<UserAccount> findById(Long id) {
        return userAccountRepository.findById(id);
    }

    public boolean existsByUsername(String username) {
        return userAccountRepository.existsByUsername(username);
    }

    @Transactional
    public UserAccount createUser(String username, String passwordHash) {
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setNickname(username);
        user.setPasswordHash(passwordHash);
        user.setStatus("ACTIVE");
        user.setLastLoginAt(LocalDateTime.now());
        UserAccount savedUser = userAccountRepository.save(user);

        UserStats stats = new UserStats();
        stats.setUserId(savedUser.getId());
        stats.setTotalGames(0);
        stats.setWins(0);
        stats.setLosses(0);
        stats.setExperience(0);
        userStatsRepository.save(stats);
        return savedUser;
    }

    @Transactional
    public void updateLastLogin(UserAccount user) {
        user.setLastLoginAt(LocalDateTime.now());
        userAccountRepository.save(user);
    }

    @Transactional
    public UserStats getOrCreateStats(Long userId) {
        return userStatsRepository.findByUserId(userId).orElseGet(() -> {
            UserStats stats = new UserStats();
            stats.setUserId(userId);
            stats.setTotalGames(0);
            stats.setWins(0);
            stats.setLosses(0);
            stats.setExperience(0);
            return userStatsRepository.save(stats);
        });
    }

    @Transactional
    public boolean hasDailySignInAvailable(Long userId) {
        if (userId == null) {
            return false;
        }
        UserStats stats = getOrCreateStats(userId);
        if (stats.getLastDailySignInAt() == null) {
            return true;
        }
        return !stats.getLastDailySignInAt().toLocalDate().isEqual(LocalDate.now());
    }

    @Transactional
    public UserStats claimDailySignIn(Long userId) {
        UserStats stats = getOrCreateStats(userId);
        if (!hasDailySignInAvailable(userId)) {
            return stats;
        }
        stats.setLastDailySignInAt(LocalDateTime.now());
        stats.setExperience(LevelSystem.gainDailySignInExperience(stats.getExperience()));
        return userStatsRepository.save(stats);
    }

    @Transactional
    public UserAccount save(UserAccount user) {
        return userAccountRepository.save(user);
    }

    @Transactional(readOnly = true)
    public boolean isSessionVersionCurrent(SessionUser sessionUser) {
        if (sessionUser == null) {
            return false;
        }
        if (sessionUser.isGuest()) {
            return true;
        }
        if (sessionUser.getId() == null || sessionUser.getSessionVersion() == null || sessionUser.getSessionVersion().isBlank()) {
            return false;
        }
        return userAccountRepository.findById(sessionUser.getId())
                .map(user -> sessionUser.getSessionVersion().equals(user.getSessionVersion()))
                .orElse(false);
    }

    public void saveLoginLog(UserAccount user, String username, String ip, String userAgent, boolean success) {
        UserLoginLog log = new UserLoginLog();
        log.setUserId(user == null ? null : user.getId());
        log.setUsername(username);
        log.setIp(ip);
        log.setUserAgent(trimToLength(userAgent, LOGIN_USER_AGENT_MAX_LENGTH));
        log.setSuccess(success);
        userLoginLogRepository.save(log);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
