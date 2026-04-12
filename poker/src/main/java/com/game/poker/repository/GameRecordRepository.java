package com.game.poker.repository;

import com.game.poker.entity.GameRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GameRecordRepository extends JpaRepository<GameRecord, Long> {
    @Query("""
            select distinct gr
            from GameRecord gr
            join gr.participants participant
            where participant.userId = :userId
            order by gr.endedAt desc
            """)
    List<GameRecord> findRecentByUserId(@Param("userId") String userId, Pageable pageable);
}
