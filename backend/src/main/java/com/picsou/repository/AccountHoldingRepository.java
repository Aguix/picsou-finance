package com.picsou.repository;

import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /** Drops the holdings of {@code accountId} whose asset isn't one of {@code tickers}. */
    @Modifying
    @Query("DELETE FROM AccountHolding h WHERE h.account.id = :accountId AND h.asset.symbol NOT IN :tickers")
    void deleteByAccountIdAndTickerNotIn(
        @Param("accountId") Long accountId, @Param("tickers") Collection<String> tickers);

    /**
     * Drops the holdings of {@code accountIds} that are keyed by one of {@code tickers}.
     *
     * <p>For the ISIN repair pass: {@code HoldingComputeService.recomputeHoldings} rebuilds a
     * holding for every ticker its transactions mention, but leaves alone one whose ticker they no
     * longer mention at all — deliberately, since a synced account owns holdings no transaction
     * backs. Renaming a ticker makes the old key exactly that kind of orphan, so the pass that
     * renames it is the one that has to remove it.
     */
    @Modifying
    @Query("DELETE FROM AccountHolding h WHERE h.account.id IN :accountIds AND h.asset.symbol IN :tickers")
    void deleteByAccountIdInAndTickerIn(
        @Param("accountIds") Collection<Long> accountIds, @Param("tickers") Collection<String> tickers);

    /**
     * Every asset symbol held in a <em>live</em> account.
     *
     * <p>The join and the {@code deletedAt} filter are load-bearing: deleting an account only
     * stamps {@code deleted_at} ({@code AccountService.delete}), and its holdings stay behind.
     * Querying {@code AccountHolding} alone therefore keeps returning them forever — and both
     * callers spend a price-provider request per ticker, hourly, against free tiers that answer
     * bursts with 429s. {@code Account}'s {@code @SQLRestriction} would cover this on its own;
     * the predicate is written out anyway so the intent survives a refactor of that annotation.
     */
    @Query("""
        SELECT DISTINCT h.asset.symbol FROM AccountHolding h
        JOIN h.account a
        WHERE a.deletedAt IS NULL
        """)
    Set<String> findDistinctTickers();

    /** {@link #findDistinctTickers} narrowed to one account type — see {@code SchedulerService}. */
    @Query("""
        SELECT DISTINCT h.asset.symbol FROM AccountHolding h
        JOIN h.account a
        WHERE a.deletedAt IS NULL AND a.type = :type
        """)
    Set<String> findDistinctTickersByAccountType(@Param("type") AccountType type);

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
