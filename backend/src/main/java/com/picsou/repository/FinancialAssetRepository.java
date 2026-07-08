package com.picsou.repository;

import com.picsou.model.FinancialAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistent asset registry (symbol → aggregator refs). The symbol is unique and uppercase, so a
 * lookup is a single indexed read — cheap enough for the price-routing hot path.
 */
public interface FinancialAssetRepository extends JpaRepository<FinancialAsset, Long> {

    /** Fetch the asset for an uppercase symbol, if one has been registered. */
    Optional<FinancialAsset> findBySymbol(String symbol);

    /** Bulk lookup for a set of uppercase symbols (used when pricing several assets at once). */
    List<FinancialAsset> findBySymbolIn(Iterable<String> symbols);

    /**
     * Persist the latest known EUR price without touching the resolution fields (bypasses
     * auditing on purpose — a price tick is not a mapping update). No-op for unknown symbols.
     */
    @Modifying
    @Transactional
    @Query("UPDATE FinancialAsset a SET a.lastEurValue = :price, a.priceSyncedAt = :at WHERE a.symbol = :symbol")
    int updateLastPrice(@Param("symbol") String symbol, @Param("price") BigDecimal price, @Param("at") Instant at);
}
