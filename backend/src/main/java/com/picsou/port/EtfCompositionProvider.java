package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port for fetching the raw constituent holdings of an ETF from an issuer.
 * Implement this interface to add a new issuer (iShares, Amundi, Vanguard...).
 *
 * Providers return raw per-holding rows; the service is responsible for
 * aggregating them into company / country / sector breakdowns so that the
 * aggregation rules stay consistent across all issuers.
 */
public interface EtfCompositionProvider {

    /** Whether this issuer's provider recognises the given ticker/name (e.g. name contains "iShares"). */
    boolean supports(String ticker, String name);

    /** Fetch raw holdings for the ETF, or empty when it cannot be resolved. */
    Optional<RawEtfHoldings> fetch(String ticker, String name);

    /** A single constituent of the fund. weightPercent is 0–100; country/sector may be null. */
    record EtfHolding(String name, BigDecimal weightPercent, String country, String sector) {}

    /** Raw holdings plus provenance, before aggregation. */
    record RawEtfHoldings(List<EtfHolding> holdings, String source, LocalDate asOf) {}
}
