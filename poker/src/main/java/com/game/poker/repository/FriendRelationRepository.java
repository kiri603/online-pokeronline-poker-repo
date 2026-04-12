package com.game.poker.repository;

import com.game.poker.entity.FriendRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendRelationRepository extends JpaRepository<FriendRelation, Long> {
    @Query("""
            select relation
            from FriendRelation relation
            where relation.userAId = :userId or relation.userBId = :userId
            order by relation.createdAt desc
            """)
    List<FriendRelation> findAllByUserId(@Param("userId") String userId);

    Optional<FriendRelation> findByUserAIdAndUserBId(String userAId, String userBId);
}
