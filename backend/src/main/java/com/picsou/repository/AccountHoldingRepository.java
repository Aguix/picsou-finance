package com.picsou.repository;

import com.picsou.model.AccountHolding;
import com.picsou.model.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AccountHoldingRepository extends JpaRepository<AccountHolding, Long> {

    List<AccountHolding> findByAccountIdOrderByCurrentPriceDesc(Long accountId);

    List<AccountHolding> findByAccount_Id(Long accountId);

    /** Lookup by account and the asset's (uppercase) symbol. The ticker argument is uppercased. */
    @Query("SELECT h FROM AccountHolding h WHERE h.account.id = :accountId AND h.asset.symbol = UPPER(:ticker)")
    Optional<AccountHolding> findByAccountIdAndTicker(@Param("accountId") Long accountId, @Param("ticker") String ticker);

    void deleteByAccountId(Long accountId);

    @Query("SELECT DISTINCT h.asset.symbol FROM AccountHolding h")
    Set<String> findDistinctTickers();

    /** Every holding of an asset across all accounts — used to re-value it or guard its deletion. */
    List<AccountHolding> findByAsset_Id(Long assetId);

    /**
     * Every holding of a given asset type across all of the member's accounts — so the consolidated
     * crypto view pools crypto held inside a mixed brokerage account (e.g. Trade Republic XF000 on a
     * COMPTE_TITRES account), not only in dedicated CRYPTO accounts.
     */
    @Query("SELECT h FROM AccountHolding h WHERE h.account.member.id = :memberId AND h.asset.type = :type")
    List<AccountHolding> findByMemberIdAndAssetType(@Param("memberId") Long memberId, @Param("type") AssetType type);
}
