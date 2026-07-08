package com.picsou.repository;

import com.picsou.model.AccountHolding;
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

    /** Every holding of a ticker across all accounts (case-insensitive) — used to re-value it. */
    @Query("SELECT h FROM AccountHolding h WHERE h.asset.symbol = UPPER(:ticker)")
    List<AccountHolding> findByTickerIgnoreCase(@Param("ticker") String ticker);
}
