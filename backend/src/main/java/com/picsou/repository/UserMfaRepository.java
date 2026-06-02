package com.picsou.repository;

import com.picsou.model.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMfaRepository extends JpaRepository<UserMfa, Long> {

    Optional<UserMfa> findByUserId(Long userId);

    boolean existsByUserIdAndEnabledTrue(Long userId);
}
