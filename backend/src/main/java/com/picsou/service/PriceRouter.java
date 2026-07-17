package com.picsou.service;

import com.picsou.model.FinancialAsset;
import com.picsou.port.PriceProviderPort;
import com.picsou.port.PriceProviderPort.Capability;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes a pricing request to the aggregator that can serve it, over the ordered list of
 * {@link PriceProviderPort} beans (priority = bean {@code @Order}; CoinGecko before Yahoo today).
 *
 * <p>This is the single seam the pricing services ({@link PriceService},
 * {@link SecurityInsightService}) go through instead of naming concrete adapters: adding a new
 * aggregator is a new {@code PriceProviderPort} bean with an {@code @Order}, not an edit here.
 *
 * <p>Routing is a <b>waterfall over the results</b>, not a one-shot assignment: providers are tried
 * in priority order, each one batched into a single call with every still-unpriced asset it declares
 * the needed {@link Capability} for, {@link PriceProviderPort#canPrice(FinancialAsset) can price}
 * (holds a ref for), and is {@link PriceProviderPort#isAvailable() available} to serve — and
 * whatever is <em>still missing from its answer</em> moves on to the next provider holding a ref.
 *
 * <p>That last clause is what makes a second ref a real fallback, in both failure modes: a provider
 * that is <em>known</em> unusable up front (breaker open, switched off in the admin panel) is skipped
 * before the call, and one that fails <em>during</em> the call — a 429 tripping mid-batch, a network
 * error, an id its API didn't answer for — simply doesn't return those prices, so the router hands
 * the leftovers to the next aggregator instead of stranding them until the next refresh. An asset
 * mapped on only one aggregator still goes unpriced when that one fails; the fix for that is mapping
 * it on a second aggregator (the import preview offers exactly that), not routing.
 */
@Component
public class PriceRouter {

    private final List<PriceProviderPort> providers;

    public PriceRouter(List<PriceProviderPort> providers) {
        this.providers = providers;
    }

    /**
     * The provider that would be asked <em>first</em> for this asset's spot price, if any. With the
     * waterfall this is a starting point, not a guarantee — a mid-call failure hands the asset to the
     * next provider holding a ref.
     */
    public Optional<PriceProviderPort> providerFor(FinancialAsset asset) {
        return providers.stream()
            .filter(p -> serves(p, asset, Capability.SPOT))
            .findFirst();
    }

    /**
     * Bulk spot prices in EUR, keyed by uppercase symbol — the waterfall. Each provider (priority
     * order) is called at most once, with every still-unpriced asset it holds a ref for; the assets
     * missing from its answer (mid-call failure, id its API didn't quote) carry over to the next
     * provider. An asset priced upstream is never re-requested downstream.
     *
     * <p>Availability is asked once per provider, not once per asset: it's the same answer for the
     * whole batch, and on a rotating-key provider each call picks (and stamps) a session.
     */
    public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
        if (assets.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();
        Collection<FinancialAsset> remaining = assets;
        for (PriceProviderPort provider : providers) {
            if (remaining.isEmpty()) break;
            if (!provider.capabilities().contains(Capability.SPOT) || !provider.isAvailable()) continue;
            List<FinancialAsset> share = remaining.stream().filter(provider::canPrice).toList();
            if (share.isEmpty()) continue;

            result.putAll(provider.getPricesEur(share));
            remaining = remaining.stream()
                .filter(a -> !result.containsKey(a.getSymbol().toUpperCase()))
                .toList();
        }
        return result;
    }

    /**
     * Daily historical prices for one asset — first non-empty answer wins, walking the providers that
     * serve history and hold a ref. A legitimately-empty range costs one extra (empty) probe on the
     * next provider; a mid-call failure is what the walk exists for.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(FinancialAsset asset, LocalDate from, LocalDate to) {
        for (PriceProviderPort provider : providers) {
            if (!serves(provider, asset, Capability.HISTORY)) continue;
            Map<LocalDate, BigDecimal> prices = provider.getHistoricalPricesEur(asset, from, to);
            if (!prices.isEmpty()) return prices;
        }
        return Map.of();
    }

    /** Intraday (hourly) prices for one asset — same walk as {@link #getHistoricalPricesEur}. */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        for (PriceProviderPort provider : providers) {
            if (!serves(provider, asset, Capability.INTRADAY)) continue;
            Map<LocalDateTime, BigDecimal> prices = provider.getIntradayPricesEur(asset, from, to);
            if (!prices.isEmpty()) return prices;
        }
        return Map.of();
    }

    /** Declares {@code capability}, holds a ref for {@code asset}, and is available right now. */
    private static boolean serves(PriceProviderPort provider, FinancialAsset asset, Capability capability) {
        return provider.capabilities().contains(capability) && provider.canPrice(asset) && provider.isAvailable();
    }
}
