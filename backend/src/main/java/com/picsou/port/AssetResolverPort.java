package com.picsou.port;

import com.picsou.model.FinancialAsset;

import java.util.List;
import java.util.Optional;

/**
 * Port for an aggregator's <em>resolution</em> side — finding which of the aggregator's own ids a
 * symbol maps to, and reading/writing that id on the asset. The pricing side is
 * {@link PriceProviderPort}; an aggregator implements both.
 *
 * <p>An aggregator is never categorised by asset class — there is no "crypto aggregator". Whether an
 * aggregator can price a given asset is <b>entirely data-driven</b>: each aggregator owns exactly one
 * ref column on {@link FinancialAsset} (CoinGecko {@code coingecko_id}, Yahoo {@code yahoo_symbol},
 * CoinMarketCap {@code coinmarketcap_id}, …), and a {@code null} ref means "this aggregator can't
 * price this asset". {@link PriceProviderPort#canPrice} is exactly that null check. Resolution is what
 * <em>fills</em> the ref: the engine offers every {@link #isResolutionAvailable() available} resolver
 * for a symbol, each contributes candidates (or nothing), and the operator picks one per aggregator.
 *
 * <p>Each resolver reads and writes <b>only its own</b> ref column ({@link #getRef}/{@link #setRef}),
 * so the resolution engine stays aggregator-agnostic and adding an aggregator touches a minimum of
 * files: one adapter implementing this port + {@link PriceProviderPort}, one migration column, one
 * entity field — the engine, DTOs, and frontend loop over aggregators unchanged.
 */
public interface AssetResolverPort {

    /** Stable aggregator key, matching {@link PriceProviderPort#aggregatorKey()} (e.g. {@code "coingecko"}). */
    String aggregatorKey();

    /**
     * Whether resolution can run right now. Defaults to always-available; an aggregator that needs a
     * key to search at all (CoinMarketCap — no anonymous tier) returns {@code false} when no key is
     * configured, so the engine simply doesn't offer it.
     */
    default boolean isResolutionAvailable() {
        return true;
    }

    /**
     * The candidates this aggregator considers a match for {@code symbol}, best-first when it ranks
     * them. What counts as a match is the aggregator's own call — an exact symbol match for CoinGecko
     * and CoinMarketCap, but Yahoo also lists the exchange-suffixed forms of a ticker ({@code IWDA} →
     * {@code IWDA.AS}, {@code IWDA.L}), which are the symbols it actually quotes. Empty on a miss or a
     * failure (treated as "nothing to offer for this aggregator") — never throws out to fail a batch.
     */
    List<AssetCandidate> searchBySymbol(String symbol);

    /** Look up / validate one candidate by this aggregator's own id; empty when unknown or on failure. */
    Optional<AssetCandidate> fetchById(String id);

    /**
     * Extract this aggregator's id from a pasted link, when it supports that convenience (CoinGecko
     * coin URL). Empty when the aggregator has no URL form or the link isn't one — the id is then
     * taken from a picked candidate instead.
     */
    default Optional<String> extractIdFromUrl(String url) {
        return Optional.empty();
    }

    /** Read this aggregator's ref off the asset (its own column); {@code null} when unset. */
    String getRef(FinancialAsset asset);

    /** Write this aggregator's ref on the asset (its own column); {@code null} clears it. */
    void setRef(FinancialAsset asset, String id);
}
