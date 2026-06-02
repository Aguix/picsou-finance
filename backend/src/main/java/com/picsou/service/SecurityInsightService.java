package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the "Insight" payload for a security: its asset type (ETF / Action /
 * Crypto) and, for ETFs, its composition broken down by company, country and
 * sector. Results are cached in memory for a few days (cleared on restart).
 *
 * Asset type comes from the unauthenticated Yahoo chart endpoint already used
 * for prices ({@code instrumentType}); composition comes from an external
 * provider behind {@link EtfCompositionProvider}.
 */
@Service
public class SecurityInsightService {

    private static final Logger log = LoggerFactory.getLogger(SecurityInsightService.class);
    private static final long CACHE_TTL_SECONDS = 3L * 24 * 3600; // 3 days

    private final List<EtfCompositionProvider> compositionProviders;
    private final YahooFinancePriceProvider yahoo;
    private final CoinGeckoPriceProvider coinGecko;
    private final Map<String, CachedInsight> cache = new ConcurrentHashMap<>();

    public SecurityInsightService(List<EtfCompositionProvider> compositionProviders,
                                  YahooFinancePriceProvider yahoo,
                                  CoinGeckoPriceProvider coinGecko) {
        this.compositionProviders = compositionProviders;
        this.yahoo = yahoo;
        this.coinGecko = coinGecko;
    }

    public SecurityInsightResponse getInsight(String ticker, String name) {
        if (ticker == null || ticker.isBlank()) {
            return new SecurityInsightResponse(ticker, "UNKNOWN", null);
        }
        String upper = ticker.toUpperCase();

        CachedInsight cached = cache.get(upper);
        if (cached != null && !cached.isExpired()) {
            return cached.response();
        }

        String assetType = classify(upper);
        EtfComposition composition = "ETF".equals(assetType) ? resolveComposition(ticker, name) : null;

        SecurityInsightResponse response = new SecurityInsightResponse(upper, assetType, composition);
        cache.put(upper, new CachedInsight(response, Instant.now()));
        return response;
    }

    /** crypto via CoinGecko, else map Yahoo's instrumentType, else UNKNOWN. */
    private String classify(String upperTicker) {
        if (coinGecko.supports(upperTicker)) {
            return "CRYPTO";
        }
        Optional<String> instrumentType = yahoo.getInstrumentType(upperTicker);
        if (instrumentType.isEmpty()) {
            return "UNKNOWN";
        }
        return switch (instrumentType.get().toUpperCase()) {
            case "ETF", "MUTUALFUND" -> "ETF";
            case "EQUITY" -> "STOCK";
            case "CRYPTOCURRENCY" -> "CRYPTO";
            default -> "UNKNOWN";
        };
    }

    /** First supporting provider that returns a non-empty composition wins; null otherwise. */
    private EtfComposition resolveComposition(String ticker, String name) {
        for (EtfCompositionProvider provider : compositionProviders) {
            if (!provider.supports(ticker, name)) {
                continue;
            }
            Optional<EtfComposition> composition = provider.fetch(ticker, name);
            if (composition.isPresent() && hasAnyData(composition.get())) {
                return composition.get();
            }
        }
        log.debug("No provider resolved composition for {} ({})", ticker, name);
        return null;
    }

    private static boolean hasAnyData(EtfComposition c) {
        return !c.companies().isEmpty() || !c.countries().isEmpty() || !c.sectors().isEmpty();
    }

    /** Drop the in-memory insight cache. */
    public void clearCache() {
        cache.clear();
    }

    private record CachedInsight(SecurityInsightResponse response, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }
}
