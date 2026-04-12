package com.game.poker.repository;

import com.game.poker.entity.DirectMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DirectMessageRepository extends JpaRepository<DirectMessage, Long> {
    @Query("""
            select message
            from DirectMessage message
            where (message.fromUserId = :leftUserId and message.toUserId = :rightUserId)
               or (message.fromUserId = :rightUserId and message.toUserId = :leftUserId)
            order by message.createdAt desc
            """)
    List<DirectMessage> findConversation(@Param("leftUserId") String leftUserId,
                                         @Param("rightUserId") String rightUserId,
                                         Pageable pageable);

    @Query("""
            select count(message)
            from DirectMessage message
            where message.toUserId = :userId and message.readAt is null
            """)
    long countUnreadByUserId(@Param("userId") String userId);

    @Query("""
            select count(message)
            from DirectMessage message
            where message.toUserId = :userId
              and message.fromUserId = :friendUserId
              and message.readAt is null
            """)
    long countUnreadFromFriend(@Param("userId") String userId, @Param("friendUserId") String friendUserId);

    @Modifying
    @Query("""
            update DirectMessage message
            set message.readAt = :readAt
            where message.fromUserId = :friendUserId
              and message.toUserId = :currentUserId
              and message.readAt is null
            """)
    int markConversationAsRead(@Param("currentUserId") String currentUserId,
                               @Param("friendUserId") String friendUserId,
                               @Param("readAt") LocalDateTime readAt);
}
