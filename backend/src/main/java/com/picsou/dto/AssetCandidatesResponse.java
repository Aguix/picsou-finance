package com.picsou.dto;

import java.util.List;

/**
 * The candidate coins for one symbol, served to the standing mapping UI (holding detail) so the
 * operator can verify or correct a mapping outside the import flow. Returned even for a coin already
 * settled as {@code USER}/{@code WORTHLESS} — re-verification is always allowed.
 *
 * <p><b>Single-aggregator, deliberately.</b> The resolution engine now offers one block per
 * aggregator ({@code FinancialAssetService.AggregatorResolution}) and the import preview
 * ({@code ImportAssetChoice}) carries them all; this standing surface still speaks CoinGecko only,
 * so {@code AssetController} narrows the preview to that block. Generalising it is the next pass —
 * and that's when this record and {@code ImportAssetChoice.AggregatorBlock} should share one type
 * rather than grow a second copy of the same shape.
 *
 * @param currentStatus registry status today — {@code PENDING}/{@code AUTO}/{@code USER}/
 *                      {@code WORTHLESS}, or {@code null} when the symbol was never seen.
 * @param suggestedId   the market-cap dominant match, pre-selected; {@code null} when ambiguous.
 */
public record AssetCandidatesResponse(
    String symbol,
    String currentStatus,
    String suggestedId,
    List<Candidate> candidates
) {
    /** A CoinGecko coin sharing the symbol; {@code marketCapRank} orders best-first. */
    public record Candidate(String coingeckoId, String name, String symbol, Integer marketCapRank) {}
}
