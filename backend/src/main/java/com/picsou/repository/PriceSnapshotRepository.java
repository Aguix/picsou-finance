package com.picsou.repository;

import com.picsou.model.PriceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Daily price history, keyed by the asset's id (FK to financial_asset) rather than a ticker string —
 * every lookup takes an {@code assetId}, resolved once from the symbol at the boundary (see V85).
 */
public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, Long> {

    @Query("SELECT ps FROM PriceSnapshot ps WHERE ps.asset.id = :assetId AND ps.date = :date")
    Optional<PriceSnapshot> findByAssetIdAndDate(@Param("assetId") Long assetId, @Param("date") LocalDate date);

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.asset.id = :assetId AND ps.date <= :date
        ORDER BY ps.date DESC
        LIMIT 1
        """)
    Optional<PriceSnapshot> findLatestByAssetIdBeforeOrOnDate(
        @Param("assetId") Long assetId,
        @Param("date") LocalDate date
    );

    /**
     * Rows for {@code assetIds} within a short recent window, newest first per asset.
     *
     * <p>Backs {@code PriceService}'s last-known-price fallback. JPQL has no {@code DISTINCT ON},
     * so the caller reduces to one row per asset in memory — bounded work, since the window is a
     * handful of days and there is at most one row per asset per day
     * ({@code uk_price_snapshot_asset_date}). One query for the whole set is the point: the
     * fallback fires exactly when the price API is rate-limiting us, and a per-asset query would
     * answer a request storm with a query storm.
     *
     * <p>Keyed by asset id rather than by symbol (see V85): the fallback must not let an unresolved
     * coin read the history a stock of the same name wrote — with an id, it structurally cannot.
     */
    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.asset.id IN :assetIds AND ps.date BETWEEN :from AND :to
        ORDER BY ps.asset.id, ps.date DESC
        """)
    List<PriceSnapshot> findRecentByAssetIds(
        @Param("assetIds") Collection<Long> assetIds,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.asset.id IN :assetIds AND ps.date BETWEEN :from AND :to
        ORDER BY ps.asset.id, ps.date
        """)
    List<PriceSnapshot> findByAssetIdInAndDateBetween(
        @Param("assetIds") Collection<Long> assetIds,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    @Modifying
    @Query("DELETE FROM PriceSnapshot ps WHERE ps.asset.id = :assetId AND ps.date = :date")
    void deleteByAssetIdAndDate(@Param("assetId") Long assetId, @Param("date") LocalDate date);

    @Modifying
    @Query("DELETE FROM PriceSnapshot")
    int deleteAllSnapshots();

    /** Purge an asset's whole history — used when its mapping is corrected or removed. */
    @Modifying
    @Query("DELETE FROM PriceSnapshot ps WHERE ps.asset.id = :assetId")
    int deleteByAssetId(@Param("assetId") Long assetId);
}
