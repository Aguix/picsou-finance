package com.picsou.crypto;

import java.util.List;

/**
 * One coin the crypto import preview asks the operator to confirm before committing. Only coins that
 * aren't already settled ({@code USER}/{@code WORTHLESS}) appear here.
 *
 * <p>The choice is offered <b>per aggregator</b>: one {@link AggregatorBlock} for each aggregator
 * available to resolve, carrying its own candidates and its own provisional guess. That shape is what
 * lets the operator map the same coin on several aggregators at once — the only way a price fallback
 * can work later, since an aggregator can only quote an asset it holds an id for. It also means a new
 * aggregator shows up here on its own: the blocks are keyed by {@code aggregatorKey}, so neither this
 * DTO nor the frontend looping over it changes.
 *
 * <p>Confirming sends back an {@link ImportAssetMapping}; nothing here is persisted until the import
 * runs.
 *
 * @param currentStatus registry status today — {@code AUTO} (a prior guess), {@code PENDING}
 *                      (unresolved), or {@code null} (never seen).
 */
public record ImportAssetChoice(
    String symbol,
    String currentStatus,
    List<AggregatorBlock> aggregators
) {
    /**
     * What one aggregator offers for this symbol. An empty {@code candidates} list means it doesn't
     * know the symbol — its ref stays null and it simply won't price the asset.
     *
     * @param suggestedId the aggregator's dominant match, pre-selected; {@code null} when the choice
     *                    is ambiguous or the aggregator doesn't rank its results (Yahoo), where a
     *                    guess would risk quoting the security on the wrong exchange.
     */
    public record AggregatorBlock(
        String aggregatorKey,
        String suggestedId,
        List<Candidate> candidates
    ) {}

    /** One asset an aggregator offers for the symbol; {@code marketCapRank} orders best-first. */
    public record Candidate(String id, String name, String symbol, Integer marketCapRank) {}
}
