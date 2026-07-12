package com.picsou.port;

import com.picsou.model.FinancialAsset;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Port for a price aggregator (CoinGecko, Yahoo Finance, CoinMarketCap, …). Implement it to add a
 * new price source; {@link com.picsou.service.PriceRouter} discovers every implementation and routes
 * each request to the right one — a provider is never called directly by the pricing services.
 *
 * <p>Every pricing op takes the {@link FinancialAsset} itself, not a bare ticker string: each
 * provider reads <em>its own</em> external ref off the asset ({@code coingecko_id}, {@code
 * yahoo_symbol}) rather than re-resolving the symbol against the registry. So the adapters carry no
 * repository dependency — the caller, which already holds the asset, hands it straight through.
 *
 * <p>Routing is driven by three declarations rather than the old single {@code supports()} boolean,
 * which was too weak to order a multi-provider fallback:
 * <ul>
 *   <li>{@link #capabilities()} — which operations the provider can serve at all (a free CoinMarketCap
 *       key, for instance, serves live prices but no history), so the router never asks a provider
 *       for an operation it can't do;</li>
 *   <li>{@link #canPrice(FinancialAsset)} — whether this provider can quote a <em>given</em> asset
 *       (has a mapping for it / accepts its symbol shape);</li>
 *   <li>{@link #isAvailable()} — whether the provider is quotable <em>right now</em>, or backing off
 *       after a rate-limit; lets the router skip it and fall through to the next instead of mistaking
 *       an empty rate-limited result for "asset unknown".</li>
 * </ul>
 * Provider priority (which one is tried first when several {@link #canPrice(FinancialAsset) can
 * price} an asset) is the bean order — declare it with {@code @Order} on the implementation.
 */
public interface PriceProviderPort {

    /** An operation a provider may or may not be able to serve; see {@link #capabilities()}. */
    enum Capability {
        /** Live spot price in EUR ({@link #getPricesEur}). */
        SPOT,
        /** Daily historical prices ({@link #getHistoricalPricesEur}). */
        HISTORY,
        /** Intraday (hourly) prices ({@link #getIntradayPricesEur}). */
        INTRADAY
    }

    /** Stable identifier of the aggregator behind this provider, e.g. {@code "coingecko"}, {@code "yahoo"}. */
    String aggregatorKey();

    /** The operations this provider can serve. The router only routes an operation to a provider that declares it. */
    Set<Capability> capabilities();

    /**
     * Whether this provider can quote the given asset — it has a mapping for it (CoinGecko id, Yahoo
     * symbol) or accepts its symbol shape. A pure field read on the asset, so the router can call it
     * once per asset without a database round trip. Independent of {@link #isAvailable()}: a provider
     * that {@code canPrice} an asset may still be temporarily unavailable.
     */
    boolean canPrice(FinancialAsset asset);

    /**
     * Whether the provider can be called right now. Defaults to always-available; a provider that
     * rate-limits itself (circuit breaker) returns {@code false} while paused so the router skips it.
     */
    default boolean isAvailable() {
        return true;
    }

    /** When {@link #isAvailable()} is expected to return true again, if currently unavailable; else empty. */
    default Optional<Instant> pausedUntil() {
        return Optional.empty();
    }

    /**
     * Returns EUR spot prices for the given assets, keyed by uppercase symbol. Assets this provider
     * can't price are simply absent from the result (the router hands them to the next provider).
     */
    Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets);

    /**
     * Daily historical EUR prices for one asset over {@code [from, to]}. Empty when the provider has
     * no {@link Capability#HISTORY} capability or no data — the default returns empty for providers
     * that don't override it.
     */
    default Map<LocalDate, BigDecimal> getHistoricalPricesEur(FinancialAsset asset, LocalDate from, LocalDate to) {
        return Map.of();
    }

    /**
     * Intraday (hourly) EUR prices for one asset over {@code [from, to]}. Empty when the provider has
     * no {@link Capability#INTRADAY} capability or no data.
     */
    default Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        return Map.of();
    }
}
