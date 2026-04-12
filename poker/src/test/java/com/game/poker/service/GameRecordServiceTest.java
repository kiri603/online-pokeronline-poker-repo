package com.game.poker.service;

import com.game.poker.entity.GameRecord;
import com.game.poker.entity.UserAccount;
import com.game.poker.entity.UserStats;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import com.game.poker.repository.GameRecordRepository;
import com.game.poker.repository.UserAccountRepository;
import com.game.poker.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameRecordServiceTest {

    @Test
    void mixedGuestMatchStillRecordsFormalPlayersWithoutSavingGuestStats() {
        GameRecordRepository gameRecordRepository = mock(GameRecordRepository.class);
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        UserStatsRepository userStatsRepository = mock(UserStatsRepository.class);
        GameRecordService service = new GameRecordService(
                gameRecordRepository,
                userAccountRepository,
                userStatsRepository
        );

        UserAccount formalUser = new UserAccount();
        setId(formalUser, 1L);
        formalUser.setUsername("formalA");
        formalUser.setNickname("formalA");
        UserStats stats = new UserStats();
        stats.setUserId(1L);
        stats.setTotalGames(2);
        stats.setWins(1);
        stats.setLosses(1);
        stats.setExperience(5);

        when(gameRecordRepository.save(any(GameRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountRepository.findByUsername("formalA")).thenReturn(Optional.of(formalUser));
        when(userAccountRepository.findByUsername("游客1234")).thenReturn(Optional.empty());
        when(userStatsRepository.findByUserId(1L)).thenReturn(Optional.of(stats));

        GameRoom room = new GameRoom("room-guest");
        room.getSettings().put("game_start_time", System.currentTimeMillis() - 5000);
        Player formalPlayer = new Player("formalA");
        formalPlayer.setStatus("WON");
        Player guestPlayer = new Player("游客1234");
        guestPlayer.setStatus("LOST");
        room.setPlayers(new ArrayList<>(List.of(formalPlayer, guestPlayer)));

        service.recordCompletedGame(room);

        ArgumentCaptor<GameRecord> recordCaptor = ArgumentCaptor.forClass(GameRecord.class);
        verify(gameRecordRepository).save(recordCaptor.capture());
        GameRecord savedRecord = recordCaptor.getValue();
        assertThat(savedRecord.getParticipants()).hasSize(2);
        assertThat(savedRecord.getParticipants())
                .extracting(participant -> participant.getUserId())
                .containsExactlyInAnyOrder("formalA", "游客1234");

        verify(userStatsRepository).save(stats);
        verify(userStatsRepository, org.mockito.Mockito.times(1)).save(any(UserStats.class));
        assertThat(stats.getTotalGames()).isEqualTo(3);
        assertThat(stats.getWins()).isEqualTo(2);
        assertThat(stats.getLosses()).isEqualTo(1);
        assertThat(stats.getExperience()).isEqualTo(7);
    }

    private void setId(UserAccount userAccount, Long id) {
        try {
            Field field = UserAccount.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(userAccount, id);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
