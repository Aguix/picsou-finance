package com.picsou.repository;

import com.picsou.model.PriceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PriceSnapshotRepository extends JpaRepository<PriceSnapshot, Long> {

    Optional<PriceSnapshot> findByTickerAndDate(String ticker, LocalDate date);

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.ticker = :ticker AND ps.date <= :date
        ORDER BY ps.date DESC
        LIMIT 1
        """)
    Optional<PriceSnapshot> findLatestByTickerBeforeOrOnDate(
        @Param("ticker") String ticker,
        @Param("date") LocalDate date
    );

    @Query("""
        SELECT ps FROM PriceSnapshot ps
        WHERE ps.ticker IN :tickers AND ps.date BETWEEN :from AND :to
        ORDER BY ps.ticker, ps.date
        """)
    List<PriceSnapshot> findByTickerInAndDateBetween(
        @Param("tickers") Set<String> tickers,
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );

    @Modifying
    @Query("""
        DELETE FROM PriceSnapshot ps
        WHERE ps.ticker = :ticker AND ps.date = :date
        """)
    void deleteByTickerAndDate(@Param("ticker") String ticker, @Param("date") LocalDate date);
}
