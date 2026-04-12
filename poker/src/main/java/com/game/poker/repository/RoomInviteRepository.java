package com.game.poker.repository;

import com.game.poker.entity.RoomInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomInviteRepository extends JpaRepository<RoomInvite, Long> {
    List<RoomInvite> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, String status);

    Optional<RoomInvite> findFirstByFromUserIdAndToUserIdOrderByCreatedAtDesc(String fromUserId, String toUserId);
}
