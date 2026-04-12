package com.game.poker.repository;

import com.game.poker.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    boolean existsByUsername(String username);

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findTop10ByUsernameContainingIgnoreCaseOrderByUsernameAsc(String username);
}
