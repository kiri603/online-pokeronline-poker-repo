package com.game.poker.repository;

import com.game.poker.entity.RememberLoginToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RememberLoginTokenRepository extends JpaRepository<RememberLoginToken, Long> {
    Optional<RememberLoginToken> findByTokenHashAndRevokedFalse(String tokenHash);

    List<RememberLoginToken> findAllByUserIdAndRevokedFalse(Long userId);
}
