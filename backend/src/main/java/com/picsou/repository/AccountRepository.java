package com.picsou.repository;

import com.picsou.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findAllByMemberIdOrderByCreatedAtAsc(Long memberId);
    Optional<Account> findByIdAndMemberId(Long id, Long memberId);
    Optional<Account> findByExternalAccountIdAndMemberId(String externalAccountId, Long memberId);
    List<Account> findByTickerIsNotNullAndMemberId(Long memberId);

    /**
     * Returns true if any soft-deleted account exists with this external id for the member.
     * Bypasses {@code @SQLRestriction("deleted_at IS NULL")} on Account.
     * Used by sync upserts to refuse resurrecting accounts the user explicitly removed.
     */
    @Query(value =
        "SELECT EXISTS(SELECT 1 FROM account " +
        "  WHERE external_account_id = :externalId AND member_id = :memberId AND deleted_at IS NOT NULL)",
        nativeQuery = true)
    boolean existsSoftDeletedByExternalAccountIdAndMemberId(
        @Param("externalId") String externalId,
        @Param("memberId") Long memberId
    );
}
