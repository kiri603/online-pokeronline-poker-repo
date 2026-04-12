package com.game.poker.repository;

import com.game.poker.entity.FriendRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {
    List<FriendRequest> findByToUserIdAndStatusOrderByCreatedAtDesc(String toUserId, String status);

    List<FriendRequest> findByFromUserIdAndStatusOrderByCreatedAtDesc(String fromUserId, String status);

    Optional<FriendRequest> findFirstByFromUserIdAndToUserIdOrderByCreatedAtDesc(String fromUserId, String toUserId);
}
