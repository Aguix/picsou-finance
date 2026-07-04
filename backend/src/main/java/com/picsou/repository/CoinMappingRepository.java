package com.picsou.repository;

import com.picsou.model.CoinMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistent ticker → CoinGecko coin-id cache. The ticker (uppercase) is the primary key, so a
 * lookup is a single indexed read — cheap enough for the price-routing hot path.
 */
public interface CoinMappingRepository extends JpaRepository<CoinMapping, String> {

    /** Fetch the mapping for an uppercase ticker, if one has been resolved. */
    Optional<CoinMapping> findByTicker(String ticker);

    /** Bulk lookup for a set of uppercase tickers (used when pricing several coins at once). */
    List<CoinMapping> findByTickerIn(Iterable<String> tickers);
}
