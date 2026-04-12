package com.game.poker.repository;

import com.game.poker.entity.GameRecordParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRecordParticipantRepository extends JpaRepository<GameRecordParticipant, Long> {
}
