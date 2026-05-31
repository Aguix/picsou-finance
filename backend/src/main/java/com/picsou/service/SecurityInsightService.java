package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.port.EtfCompositionProvider;
import com.picsou.port.EtfCompositionProvider.EtfHolding;
import com.picsou.port.EtfCompositionProvider.RawEtfHoldings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Builds the "Insight" payload for a security: its asset type (ETF / Action /
 * Crypto) and, for ETFs, its composition broken down by company, country and
 * sector. Results are cached in memory for a few days (cleared on restart).
 *
 * Asset type comes from the unauthenticated Yahoo chart endpoint already used
 * for prices ({@code instrumentType}); composition comes from issuer holdings
 * files behind {@link EtfCompositionProvider}.
 */
@Service
public class SecurityInsightService {

    private static final Logger log = LoggerFactory.getLogger(SecurityInsightService.class);
    private static final long CACHE_TTL_SECONDS = 3L * 24 * 3600; // 3 days

    private static final int TOP_COMPANIES = 10;
    private static final int TOP_COUNTRIES = 10;
    private static final int TOP_SECTORS = 12;

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

    /** First supporting provider that returns holdings wins; null when none resolves. */
    private EtfComposition resolveComposition(String ticker, String name) {
        for (EtfCompositionProvider provider : compositionProviders) {
            if (!provider.supports(ticker, name)) {
                continue;
            }
            Optional<RawEtfHoldings> raw = provider.fetch(ticker, name);
            if (raw.isPresent() && !raw.get().holdings().isEmpty()) {
                return aggregate(raw.get());
            }
        }
        log.debug("No issuer resolved composition for {} ({})", ticker, name);
        return null;
    }

    private EtfComposition aggregate(RawEtfHoldings raw) {
        List<EtfHolding> holdings = raw.holdings();

        List<WeightedSlice> companies = holdings.stream()
            .filter(h -> h.weightPercent() != null && h.name() != null)
            .sorted(Comparator.comparing(EtfHolding::weightPercent).reversed())
            .limit(TOP_COMPANIES)
            .map(h -> new WeightedSlice(h.name(), scale(h.weightPercent())))
            .toList();

        List<WeightedSlice> countries = groupTop(holdings, EtfHolding::country, TOP_COUNTRIES);
        List<WeightedSlice> sectors = groupTop(holdings, EtfHolding::sector, TOP_SECTORS);

        return new EtfComposition(companies, countries, sectors, raw.source(), raw.asOf());
    }

    /** Sum weights by key, drop blanks, return the heaviest {@code limit} slices. */
    private List<WeightedSlice> groupTop(List<EtfHolding> holdings, Function<EtfHolding, String> key, int limit) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (EtfHolding h : holdings) {
            String k = key.apply(h);
            if (k == null || k.isBlank() || h.weightPercent() == null) {
                continue;
            }
            sums.merge(k.trim(), h.weightPercent(), BigDecimal::add);
        }
        return sums.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(limit)
            .map(e -> new WeightedSlice(e.getKey(), scale(e.getValue())))
            .toList();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
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
