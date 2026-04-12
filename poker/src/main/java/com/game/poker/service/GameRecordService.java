package com.game.poker.service;

import com.game.poker.entity.GameRecord;
import com.game.poker.entity.GameRecordParticipant;
import com.game.poker.entity.UserAccount;
import com.game.poker.entity.UserStats;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import com.game.poker.repository.GameRecordRepository;
import com.game.poker.repository.UserAccountRepository;
import com.game.poker.repository.UserStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GameRecordService {
    private static final String RECORD_MARKER_KEY = "game_recorded";
    private static final String START_TIME_KEY = "game_start_time";

    private final GameRecordRepository gameRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserStatsRepository userStatsRepository;

    public GameRecordService(GameRecordRepository gameRecordRepository,
                             UserAccountRepository userAccountRepository,
                             UserStatsRepository userStatsRepository) {
        this.gameRecordRepository = gameRecordRepository;
        this.userAccountRepository = userAccountRepository;
        this.userStatsRepository = userStatsRepository;
    }

    @Transactional
    public void recordCompletedGame(GameRoom room) {
        if (room == null || room.getPlayers() == null || room.getPlayers().size() < 2) {
            return;
        }

        Map<String, Object> settings = room.getSettings();
        if (Boolean.TRUE.equals(settings.get(RECORD_MARKER_KEY))) {
            return;
        }

        if (room.getPlayers().stream().anyMatch(Player::isBot)) {
            settings.put(RECORD_MARKER_KEY, true);
            return;
        }

        Player winner = room.getPlayers().stream()
                .filter(player -> "WON".equals(player.getStatus()))
                .findFirst()
                .orElse(null);
        if (winner == null) {
            return;
        }

        GameRecord record = new GameRecord();
        record.setRoomId(room.getRoomId());
        record.setMode(resolveMode(settings));
        record.setPlayerCount(room.getPlayers().size());
        record.setWinnerUserId(winner.getUserId());
        record.setStartedAt(resolveStartedAt(settings));
        record.setEndedAt(LocalDateTime.now());

        for (Player player : room.getPlayers()) {
            GameRecordParticipant participant = new GameRecordParticipant();
            participant.setGameRecord(record);
            participant.setUserId(player.getUserId());
            participant.setResult(player.getUserId().equals(winner.getUserId()) ? "WIN" : "LOSE");
            record.getParticipants().add(participant);
        }

        gameRecordRepository.save(record);
        updateUserStats(record);
        settings.put(RECORD_MARKER_KEY, true);
    }

    private void updateUserStats(GameRecord record) {
        for (GameRecordParticipant participant : record.getParticipants()) {
            Optional<UserAccount> accountOptional = userAccountRepository.findByUsername(participant.getUserId());
            if (accountOptional.isEmpty()) {
                continue;
            }

            UserAccount account = accountOptional.get();
            UserStats stats = userStatsRepository.findByUserId(account.getId()).orElseGet(() -> {
                UserStats freshStats = new UserStats();
                freshStats.setUserId(account.getId());
                freshStats.setTotalGames(0);
                freshStats.setWins(0);
                freshStats.setLosses(0);
                return freshStats;
            });

            stats.setTotalGames(stats.getTotalGames() + 1);
            if ("WIN".equals(participant.getResult())) {
                stats.setWins(stats.getWins() + 1);
            } else {
                stats.setLosses(stats.getLosses() + 1);
            }
            stats.setExperience(LevelSystem.gainBattleExperience(
                    stats.getExperience(),
                    "WIN".equals(participant.getResult())
            ));
            userStatsRepository.save(stats);
        }
    }

    private LocalDateTime resolveStartedAt(Map<String, Object> settings) {
        Object raw = settings.get(START_TIME_KEY);
        if (raw instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneId.systemDefault());
        }
        return LocalDateTime.now();
    }

    private String resolveMode(Map<String, Object> settings) {
        boolean scroll = Boolean.TRUE.equals(settings.get("enableScrollCards"));
        boolean skill = Boolean.TRUE.equals(settings.get("enableSkills"));
        if (scroll && skill) {
            return "SCROLL_SKILL";
        }
        if (scroll) {
            return "SCROLL";
        }
        if (skill) {
            return "SKILL";
        }
        return "CLASSIC";
    }
}
