package com.picsou.repository;

import com.picsou.model.UserMfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserMfaRecoveryCodeRepository extends JpaRepository<UserMfaRecoveryCode, Long> {

    List<UserMfaRecoveryCode> findByUserMfaIdAndUsedAtIsNull(Long userMfaId);

    long countByUserMfaIdAndUsedAtIsNull(Long userMfaId);

    @Modifying
    @Transactional
    @Query("DELETE FROM UserMfaRecoveryCode c WHERE c.userMfa.id = :userMfaId")
    void deleteByUserMfaId(Long userMfaId);
}
