package com.picsou.adapter.price;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.AssetResolverPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.service.AggregatorService;
import com.picsou.service.AggregatorService.SessionCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches prices from CoinMarketCap, and resolves symbols to CoinMarketCap ids
 * ({@link AssetResolverPort}) — the two sides of one aggregator.
 *
 * <p>Ordered behind CoinGecko ({@code @Order(10)}) and ahead of Yahoo, so it serves an asset when the
 * aggregator ahead of it can't: either it has no id for that asset, or it's backing off a rate limit
 * ({@link PriceProviderPort#isAvailable()}). That fallback is the point of this adapter — it needs
 * both aggregators to hold an id for the same asset, which is what per-aggregator resolution
 * produces.
 *
 * <p>Priceable assets are exactly those carrying {@code financial_asset.coinmarketcap_id} — this
 * adapter's own ref column, the only one it reads or writes. Lookups are <b>by id</b>, never by
 * symbol: CoinMarketCap's symbol endpoint returns <em>every</em> coin sharing a ticker (the namesake
 * problem this whole registry exists to solve), while an id addresses one coin unambiguously and the
 * response is a single object rather than a list to disambiguate after the fact.
 *
 * <p>Credentials live in {@code aggregator_session} like every aggregator's, with the same
 * least-recently-used rotation and per-session circuit breaker as
 * {@link CoinGeckoPriceProvider} (sent as the {@code X-CMC_PRO_API_KEY} header). One difference
 * matters: <b>CoinMarketCap has no anonymous tier</b>. With no key configured this provider makes no
 * call at all — it neither prices nor resolves, and {@link #isResolutionAvailable()} is false so the
 * resolution UI doesn't offer a CoinMarketCap picker whose every search would come back empty.
 *
 * <p>The free plan serves live quotes but no historical series, hence {@link Capability#SPOT} only:
 * the router keeps routing history to an aggregator that has it.
 */
@Component
@Order(15)   // fallback — after CoinGecko (10), before Yahoo (20)
public class CoinMarketCapPriceProvider implements PriceProviderPort, AssetResolverPort {

    private static final Logger log = LoggerFactory.getLogger(CoinMarketCapPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AGGREGATOR_KEY = "coinmarketcap";

    /** Pause a key this long after a 429 that carries no usable {@code Retry-After}. */
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    // Per-session breaker / last-used stamps, keyed by session id. Unlike CoinGecko there's no
    // anonymous sentinel: with no key there's no session at all, so nothing to track.
    private final Map<Long, Instant> breakerUntil = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastUsedAt = new ConcurrentHashMap<>();

    private final AggregatorService aggregatorService;
    private final WebClient webClient;

    @Autowired
    public CoinMarketCapPriceProvider(AggregatorService aggregatorService) {
        this(aggregatorService, WebClient.builder()
            .baseUrl("https://pro-api.coinmarketcap.com")
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    CoinMarketCapPriceProvider(AggregatorService aggregatorService, WebClient webClient) {
        this.aggregatorService = aggregatorService;
        this.webClient = webClient;
    }

    @Override
    public String aggregatorKey() {
        return AGGREGATOR_KEY;
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SPOT);   // no historical series on the free plan
    }

    /** True once the asset carries a CoinMarketCap id — this adapter's ref, nothing else. */
    @Override
    public boolean canPrice(FinancialAsset asset) {
        return asset.getCoinmarketcapId() != null;
    }

    @Override
    public String getRef(FinancialAsset asset) {
        return asset.getCoinmarketcapId();
    }

    @Override
    public void setRef(FinancialAsset asset, String id) {
        asset.setCoinmarketcapId(id);
    }

    /** True while at least one enabled key is usable; false with no key at all, or all keys paused. */
    @Override
    public boolean isAvailable() {
        return pickSession().isPresent();
    }

    /**
     * No key ⟹ no search: every CoinMarketCap endpoint is authenticated, so the resolution UI omits
     * the CoinMarketCap picker entirely rather than showing one that can only ever come back empty.
     */
    @Override
    public boolean isResolutionAvailable() {
        return isAvailable();
    }

    /** Soonest instant a paused key frees up, when every key is currently paused; else empty. */
    @Override
    public Optional<Instant> pausedUntil() {
        Optional<Instant> soonest = Optional.empty();
        for (SessionCredentials session : candidates()) {
            Instant until = breakerUntil.get(session.sessionId());
            if (until == null || !Instant.now().isBefore(until)) {
                return Optional.empty();   // this key is usable now — the provider isn't paused
            }
            soonest = soonest.filter(cur -> cur.isBefore(until)).or(() -> Optional.of(until));
        }
        return soonest;
    }

    /**
     * The keys this provider may use right now. Empty when the aggregator is disabled <em>or</em> has
     * no key configured — CoinMarketCap has no anonymous tier, so both cases mean "make no call".
     */
    private List<SessionCredentials> candidates() {
        return aggregatorService.enabledCredentials(AGGREGATOR_KEY)
            .orElseGet(List::of).stream()
            .filter(s -> s.sessionId() != null && s.apiKey() != null && !s.apiKey().isBlank())
            .toList();
    }

    /**
     * Pick a usable key: among those whose breaker is closed, the least-recently-used one (ties broken
     * by session id), stamped used at the moment of the choice — a request that later 429s still
     * consumed its quota. Empty when every key is paused, or there's no key.
     */
    private Optional<SessionCredentials> pickSession() {
        Optional<SessionCredentials> chosen = candidates().stream()
            .filter(s -> !paused(s))
            .min(Comparator.<SessionCredentials, Instant>comparing(
                    s -> lastUsedAt.getOrDefault(s.sessionId(), Instant.EPOCH))
                .thenComparingLong(SessionCredentials::sessionId));
        chosen.ifPresent(s -> lastUsedAt.put(s.sessionId(), Instant.now()));
        return chosen;
    }

    private boolean paused(SessionCredentials session) {
        Instant until = breakerUntil.get(session.sessionId());
        return until != null && Instant.now().isBefore(until);
    }

    private static void applyKey(HttpHeaders headers, SessionCredentials session) {
        headers.set("X-CMC_PRO_API_KEY", session.apiKey());
    }

    /** Trip the breaker for one key after a 429, so the next call rolls over to another key. */
    private void pause(SessionCredentials session, Throwable t) {
        Duration wait = retryAfter(t).orElse(DEFAULT_RETRY_AFTER);
        boolean alreadyPaused = paused(session);
        breakerUntil.put(session.sessionId(), Instant.now().plus(wait));
        if (!alreadyPaused) {
            log.warn("CoinMarketCap rate-limited (429) — pausing key #{} for {}s",
                session.sessionId(), wait.toSeconds());
        }
    }

    private static boolean isRateLimited(Throwable t) {
        return t instanceof WebClientResponseException e && e.getStatusCode().value() == 429;
    }

    private static Optional<Duration> retryAfter(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            String header = e.getHeaders().getFirst("Retry-After");
            if (header != null && !header.isBlank()) {
                try {
                    return Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
                } catch (NumberFormatException ignored) {
                    // Retry-After can also be an HTTP-date; we don't parse that — fall back to default.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Live EUR prices for the assets carrying a CoinMarketCap id, keyed by uppercase internal symbol.
     * One batched call: the ids go in as a comma-separated list and come back keyed by id, which is
     * how the quotes are matched back to our symbols — the symbol CoinMarketCap reports is never used
     * for matching.
     */
    @Override
    public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
        Map<String, String> symbolToId = new LinkedHashMap<>();
        for (FinancialAsset asset : assets) {
            if (asset.getCoinmarketcapId() != null) {
                symbolToId.put(asset.getSymbol().toUpperCase(), asset.getCoinmarketcapId());
            }
        }
        if (symbolToId.isEmpty()) return Map.of();

        String ids = String.join(",", new LinkedHashSet<>(symbolToId.values()));
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Map.of();

        try {
            QuotesResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v2/cryptocurrency/quotes/latest")
                    .queryParam("id", ids)
                    .queryParam("convert", "EUR")
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(QuotesResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.data() == null) return Map.of();

            Map<String, BigDecimal> result = new HashMap<>();
            for (Map.Entry<String, String> e : symbolToId.entrySet()) {
                QuoteEntry entry = response.data().get(e.getValue());
                if (entry == null || entry.quote() == null) continue;
                Quote eur = entry.quote().get("EUR");
                if (eur != null && eur.price() != null) {
                    result.put(e.getKey(), eur.price());
                }
            }
            return result;
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinMarketCap price fetch failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Candidate coins whose CoinMarketCap symbol matches {@code symbol}, via {@code /v1/cryptocurrency/map}
     * — every coin sharing the ticker, with its CoinMarketCap rank so the resolver can spot a dominant
     * match. Empty on a miss, a failure, or with no key.
     */
    @Override
    public List<AssetCandidate> searchBySymbol(String symbol) {
        String query = symbol == null ? "" : symbol.trim().toUpperCase();
        if (query.isEmpty()) return List.of();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return List.of();

        try {
            MapResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v1/cryptocurrency/map")
                    .queryParam("symbol", query)
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(MapResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.data() == null) return List.of();
            return response.data().stream()
                .filter(c -> c.id() != null && c.symbol() != null && c.symbol().equalsIgnoreCase(query))
                .map(c -> new AssetCandidate(String.valueOf(c.id()), c.name(), c.symbol(), c.rank()))
                .toList();
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinMarketCap symbol search failed for {}: {}", query, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Validate one CoinMarketCap id via {@code /v2/cryptocurrency/info}, reading back the coin's
     * canonical name. Empty when the id is unknown, the call fails, or there's no key.
     */
    @Override
    public Optional<AssetCandidate> fetchById(String id) {
        String coinId = id == null ? "" : id.trim();
        if (coinId.isEmpty()) return Optional.empty();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Optional.empty();

        try {
            InfoResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v2/cryptocurrency/info")
                    .queryParam("id", coinId)
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(InfoResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.data() == null) return Optional.empty();
            InfoEntry entry = response.data().get(coinId);
            if (entry == null || entry.id() == null) return Optional.empty();
            return Optional.of(new AssetCandidate(
                String.valueOf(entry.id()), entry.name(), entry.symbol(), null));
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinMarketCap coin lookup failed for id {}: {}", coinId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** {@code /v2/cryptocurrency/quotes/latest?id=…} — data keyed by id, one object per id. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuotesResponse(Map<String, QuoteEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteEntry(Integer id, String name, String symbol, Map<String, Quote> quote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(BigDecimal price) {}

    /** {@code /v1/cryptocurrency/map?symbol=…} — every coin sharing the ticker. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MapResponse(List<MapEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MapEntry(Integer id, String name, String symbol, Integer rank) {}

    /** {@code /v2/cryptocurrency/info?id=…} — data keyed by id. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfoResponse(Map<String, InfoEntry> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfoEntry(Integer id, String name, String symbol) {}
}
