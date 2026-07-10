package com.picsou.service;

import com.picsou.port.PriceProviderPort;
import com.picsou.port.PriceProviderPort.Capability;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes a pricing request to the aggregator that can serve it, over the ordered list of
 * {@link PriceProviderPort} beans (priority = bean {@code @Order}; CoinGecko before Yahoo today).
 *
 * <p>This is the single seam the pricing services ({@link PriceService},
 * {@link SecurityInsightService}) go through instead of naming concrete adapters: adding a new
 * aggregator is a new {@code PriceProviderPort} bean with an {@code @Order}, not an edit here.
 *
 * <p>For each ticker the router picks the first provider (in priority order) that both declares the
 * needed {@link Capability} and {@link PriceProviderPort#canPrice(String) can price} the ticker.
 * Spot requests are partitioned so each provider is still batched into a single call. Availability
 * ({@link PriceProviderPort#isAvailable()}) is surfaced on the port for the cross-provider fallback
 * that lands with the second crypto aggregator; today's routing preserves the previous
 * one-provider-per-ticker behaviour.
 */
@Component
public class PriceRouter {

    private final List<PriceProviderPort> providers;

    public PriceRouter(List<PriceProviderPort> providers) {
        this.providers = providers;
    }

    /** The provider that would price this ticker (first that can, in priority order), if any. */
    public Optional<PriceProviderPort> providerFor(String ticker) {
        return providers.stream()
            .filter(p -> p.capabilities().contains(Capability.SPOT) && p.canPrice(ticker))
            .findFirst();
    }

    /**
     * Bulk spot prices in EUR. Each ticker is assigned to its first canPrice provider, then every
     * provider is called once with its share (preserving the batched single-call-per-provider path).
     */
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        if (tickers.isEmpty()) return Map.of();

        Map<PriceProviderPort, Set<String>> byProvider = new LinkedHashMap<>();
        for (String ticker : tickers) {
            providerFor(ticker).ifPresent(p ->
                byProvider.computeIfAbsent(p, k -> new HashSet<>()).add(ticker));
        }

        Map<String, BigDecimal> result = new HashMap<>();
        byProvider.forEach((provider, share) -> result.putAll(provider.getPricesEur(share)));
        return result;
    }

    /** Daily historical prices for one ticker from the first provider that can serve history for it. */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        return providerFor(ticker, Capability.HISTORY)
            .map(p -> p.getHistoricalPricesEur(ticker, from, to))
            .orElse(Map.of());
    }

    /** Intraday (hourly) prices for one ticker from the first provider that can serve intraday for it. */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        return providerFor(ticker, Capability.INTRADAY)
            .map(p -> p.getIntradayPricesEur(ticker, from, to))
            .orElse(Map.of());
    }

    /** First provider (priority order) that declares {@code capability} and can price {@code ticker}. */
    private Optional<PriceProviderPort> providerFor(String ticker, Capability capability) {
        return providers.stream()
            .filter(p -> p.capabilities().contains(capability) && p.canPrice(ticker))
            .findFirst();
    }
}
