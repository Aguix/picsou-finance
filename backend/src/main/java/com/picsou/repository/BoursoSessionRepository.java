package com.picsou.repository;

import com.picsou.model.BoursoSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BoursoSessionRepository extends JpaRepository<BoursoSession, Long> {
    Optional<BoursoSession> findByMemberId(Long memberId);
}
