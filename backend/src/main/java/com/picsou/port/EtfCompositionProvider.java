package com.picsou.port;

import com.picsou.dto.EtfComposition;

import java.util.Optional;

/**
 * Port for fetching the aggregated composition of an ETF (companies, countries,
 * sectors) from an external source. Implementations resolve a security by
 * ticker and return a ready-to-serve {@link EtfComposition}.
 */
public interface EtfCompositionProvider {

    /** Whether this provider can attempt the given security. */
    boolean supports(String ticker, String name);

    /** Aggregated composition for the ETF, or empty when it cannot be resolved. */
    Optional<EtfComposition> fetch(String ticker, String name);
}
