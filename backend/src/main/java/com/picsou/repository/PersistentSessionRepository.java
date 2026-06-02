package com.picsou.repository;

import com.picsou.model.PersistentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersistentSessionRepository extends JpaRepository<PersistentSession, Long> {

    Optional<PersistentSession> findBySeriesId(UUID seriesId);

    List<PersistentSession> findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    int revokeAllByUserId(Long userId, Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.id <> :exceptId AND s.revokedAt IS NULL")
    int revokeAllByUserIdExcept(Long userId, Long exceptId, Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.seriesId = :seriesId AND s.revokedAt IS NULL")
    int revokeBySeriesId(UUID seriesId, Instant now);
}
