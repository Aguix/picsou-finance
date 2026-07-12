package com.picsou.service;

import com.picsou.model.FinancialAsset;
import com.picsou.port.PriceProviderPort;
import com.picsou.port.PriceProviderPort.Capability;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * <p>For each asset the router picks the first provider (in priority order) that both declares the
 * needed {@link Capability} and {@link PriceProviderPort#canPrice(FinancialAsset) can price} the
 * asset. Spot requests are partitioned so each provider is still batched into a single call.
 * Availability ({@link PriceProviderPort#isAvailable()}) is surfaced on the port for the
 * cross-provider fallback that lands with the second crypto aggregator; today's routing preserves
 * the previous one-provider-per-ticker behaviour.
 */
@Component
public class PriceRouter {

    private final List<PriceProviderPort> providers;

    public PriceRouter(List<PriceProviderPort> providers) {
        this.providers = providers;
    }

    /** The provider that would price this asset (first that can, in priority order), if any. */
    public Optional<PriceProviderPort> providerFor(FinancialAsset asset) {
        return providers.stream()
            .filter(p -> p.capabilities().contains(Capability.SPOT) && p.canPrice(asset))
            .findFirst();
    }

    /**
     * Bulk spot prices in EUR, keyed by uppercase symbol. Each asset is assigned to its first
     * (priority-order) provider that declares {@link Capability#SPOT} and can price it, then every
     * provider is called once with its share.
     */
    public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
        if (assets.isEmpty()) return Map.of();

        Map<PriceProviderPort, List<FinancialAsset>> byProvider = new LinkedHashMap<>();
        for (FinancialAsset asset : assets) {
            for (PriceProviderPort provider : providers) {
                if (!provider.capabilities().contains(Capability.SPOT)) continue;
                if (!provider.canPrice(asset)) continue;
                byProvider.computeIfAbsent(provider, p -> new ArrayList<>()).add(asset);
                break;   // first provider (priority order) that can price this asset wins
            }
        }

        Map<String, BigDecimal> result = new HashMap<>();
        byProvider.forEach((provider, share) -> result.putAll(provider.getPricesEur(share)));
        return result;
    }

    /** Daily historical prices for one asset from the first provider that can serve history for it. */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(FinancialAsset asset, LocalDate from, LocalDate to) {
        return providerFor(asset, Capability.HISTORY)
            .map(p -> p.getHistoricalPricesEur(asset, from, to))
            .orElse(Map.of());
    }

    /** Intraday (hourly) prices for one asset from the first provider that can serve intraday for it. */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        return providerFor(asset, Capability.INTRADAY)
            .map(p -> p.getIntradayPricesEur(asset, from, to))
            .orElse(Map.of());
    }

    /** First provider (priority order) that declares {@code capability} and can price {@code asset}. */
    private Optional<PriceProviderPort> providerFor(FinancialAsset asset, Capability capability) {
        return providers.stream()
            .filter(p -> p.capabilities().contains(capability) && p.canPrice(asset))
            .findFirst();
    }
}
