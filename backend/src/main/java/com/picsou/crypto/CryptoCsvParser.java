package com.picsou.crypto;

import java.util.List;

/**
 * One supported CSV source (exchange or wallet software). Implementations are stateless Spring
 * components collected into the {@link CryptoImportService}; the format of an uploaded file is
 * auto-detected by asking each parser whether it {@link #supports} the header.
 *
 * <p>Every parser converts its rows into the same normalized {@link ParsedCryptoTx} stream, so
 * holdings derivation ({@code HoldingComputeService}), statistics ({@code CryptoStatsService})
 * and history reconstruction are shared across all sources.
 */
public interface CryptoCsvParser {

    /** Stable machine id, e.g. {@code kraken}. Also used in the account's external id. */
    String sourceId();

    /** Human label, e.g. {@code Kraken}. */
    String label();

    /** Value stored in {@code Account.provider} for accounts created from this source. */
    String provider();

    /** Header-based format detection. Must not inspect data rows. */
    boolean supports(CsvTable table);

    /** Convert the table into normalized transactions. Never throws on malformed values. */
    List<ParsedCryptoTx> parse(CsvTable table);

    /**
     * Detection precedence — lower runs first. Parsers with generic headers (the user-mapped
     * generic format) must yield to the exchange-specific signatures.
     */
    default int detectionOrder() {
        return 100;
    }
}
