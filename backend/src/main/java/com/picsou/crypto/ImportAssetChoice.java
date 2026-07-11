package com.picsou.crypto;

import java.util.List;

/**
 * One coin the crypto import preview asks the operator to confirm before committing. Only coins
 * that aren't already settled ({@code USER}/{@code WORTHLESS}) appear here; each carries the
 * provisional best match ({@code suggestedId}, from a market-cap dominant guess — possibly null)
 * and every CoinGecko candidate that shares the symbol, so a silent mis-match can be corrected
 * rather than frozen. Confirming a choice sends back an {@link ImportAssetMapping}; nothing here is
 * persisted until the import runs.
 *
 * @param currentStatus registry status today — {@code AUTO} (a prior guess), {@code PENDING}
 *                      (unresolved), or {@code null} (never seen).
 */
public record ImportAssetChoice(
    String symbol,
    String currentStatus,
    String suggestedId,
    List<Candidate> candidates
) {
    /** A CoinGecko coin sharing the imported symbol; {@code marketCapRank} orders best-first. */
    public record Candidate(String coingeckoId, String name, String symbol, Integer marketCapRank) {}
}
