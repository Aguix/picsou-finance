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
 * every lookup takes an {@code assetId}, resolved once from the symbol at the boundary (see V56).
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
