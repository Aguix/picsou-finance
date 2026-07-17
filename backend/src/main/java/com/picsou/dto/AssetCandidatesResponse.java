package com.picsou.dto;

import com.picsou.service.FinancialAssetService.AssetResolutionPreview;

import java.util.List;

/**
 * The candidates for one symbol, served to the standing mapping UI (holding detail, registry table)
 * so the operator can verify or correct a mapping outside the import flow. Returned even for a coin
 * already settled as {@code USER}/{@code WORTHLESS} — re-verification is always allowed.
 *
 * <p><b>One block per aggregator</b>, mirroring the import preview ({@code ImportAssetChoice}): each
 * aggregator contributes its own candidates and its own dominant guess, so the operator can map the
 * same symbol on several aggregators at once — the only way a price fallback works, since an
 * aggregator can only quote an asset it holds a ref for. The blocks are keyed by {@code aggregatorKey},
 * so a newly deployed aggregator shows up here (and in the UI looping over it) with no code change.
 *
 * <p>Unlike the import block this also carries {@link AggregatorBlock#currentId} — the ref stored for
 * that aggregator right now — so the standing editor can pre-select the existing mapping (the import
 * preview has no "current"; the asset isn't in the registry yet).
 *
 * @param currentStatus registry status today — {@code PENDING}/{@code AUTO}/{@code USER}/
 *                      {@code WORTHLESS}, or {@code null} when the symbol was never seen.
 */
public record AssetCandidatesResponse(
    String symbol,
    String currentStatus,
    List<AggregatorBlock> aggregators
) {
    /**
     * What one aggregator offers for the symbol, plus what it's mapped to today. An empty
     * {@code candidates} list means it doesn't know the symbol — leave it unpicked and it simply won't
     * price the asset.
     *
     * @param suggestedId the aggregator's dominant match, pre-selected on a fresh mapping; {@code null}
     *                    when ambiguous or the aggregator doesn't rank its results (Yahoo).
     * @param currentId   the ref stored for this aggregator right now, or {@code null} when unmapped —
     *                    the editor pre-selects this over {@code suggestedId} when present.
     */
    public record AggregatorBlock(
        String aggregatorKey,
        String suggestedId,
        String currentId,
        List<Candidate> candidates
    ) {}

    /** One asset an aggregator offers for the symbol; {@code marketCapRank} orders best-first. */
    public record Candidate(String id, String name, String symbol, Integer marketCapRank) {}

    /** Project a service-side resolution preview (which already carries each aggregator's current ref). */
    public static AssetCandidatesResponse from(AssetResolutionPreview preview) {
        List<AggregatorBlock> blocks = preview.aggregators().stream()
            .map(a -> new AggregatorBlock(
                a.aggregatorKey(),
                a.suggested() != null ? a.suggested().id() : null,
                a.currentId(),
                a.candidates().stream()
                    .map(c -> new Candidate(c.id(), c.name(), c.symbol(), c.marketCapRank()))
                    .toList()))
            .toList();
        return new AssetCandidatesResponse(
            preview.symbol(),
            preview.currentStatus() != null ? preview.currentStatus().name() : null,
            blocks);
    }
}
