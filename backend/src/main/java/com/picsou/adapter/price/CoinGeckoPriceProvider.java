package com.picsou.adapter.price;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.model.FinancialAsset;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.service.AggregatorService;
import com.picsou.service.AggregatorService.SessionCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Fetches crypto prices and logos from the CoinGecko API.
 *
 * <p>Ticker → coin-id resolution is fully dynamic: it reads the persistent {@code financial_asset}
 * registry (see {@link FinancialAssetRepository} / {@link com.picsou.service.FinancialAssetService})
 * instead of a hardcoded map. A ticker is {@link #supports(String) supported} once its asset has a
 * CoinGecko id — which {@code FinancialAssetService} resolves from CoinGecko at crypto-discovery
 * time. This class stays the low-level HTTP client: it also exposes {@link #searchBySymbol(String)}
 * so the resolver can look candidates up, but never decides or persists a mapping itself.
 *
 * <p>API credentials live in the {@code aggregator_session} table, not in config: at call time the
 * provider asks {@link AggregatorService#enabledCredentials(String) enabledCredentials("coingecko")}
 * for the enabled Demo keys and picks the <em>least-recently-used</em> one whose per-session breaker
 * is closed, sending it as the {@code x-cg-demo-api-key} header for that request — so calls rotate
 * across keys instead of hammering one. With no key configured it falls back to an anonymous session
 * (no header) — the free tier with a stricter rate limit; disabling the aggregator from the admin
 * panel stops all calls (not even anonymous). Adding/rotating keys is done from the admin panel; the
 * old {@code COINGECKO_DEMO_API_KEY} env var is retired.
 *
 * <p>The free tier rate-limits aggressively (a burst 429s within a handful of calls, and a page
 * load prices ~17 coins). To avoid hammering CoinGecko — and flooding the logs — a lightweight
 * <b>per-session circuit breaker</b> reacts to a {@code 429}: it pauses only the <em>session</em>
 * (key) that hit the limit until the response's {@code Retry-After} elapses (falling back to
 * {@link #DEFAULT_RETRY_AFTER}), so the next request rolls over to another enabled key instead of
 * failing. While every candidate session is paused, each method short-circuits to an empty result
 * with no HTTP call, so a dashboard load neither blocks nor spams retries — the 15-minute price
 * cache and the hourly scheduler cover the gap, and configuring more Demo keys spreads the limit.
 */
@Component
@Order(10)   // primary crypto aggregator — tried before Yahoo when both can price a ticker
public class CoinGeckoPriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String AGGREGATOR_KEY = "coingecko";

    // Circuit breaker: how long to pause a session after a 429 when the response carries no usable
    // Retry-After (CoinGecko's free-tier window is ~1 minute).
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    // The anonymous session (no key) is picked when no key is configured. It has no DB id, so it's
    // tracked in the breaker under this sentinel; a real session id is never negative.
    private static final long ANONYMOUS_SESSION = -1L;
    private static final SessionCredentials ANONYMOUS = new SessionCredentials(null, null, null);

    // Per-session breaker: sessionId (or ANONYMOUS_SESSION) -> instant until which that key is paused
    // after a 429. A session absent from the map, or past its instant, is usable.
    private final Map<Long, Instant> breakerUntil = new ConcurrentHashMap<>();

    // Per-session last-used instant, for least-recently-used rotation across usable keys — spreads
    // load so several keys stay below their limit instead of one absorbing every call until it 429s.
    // In-memory (not the DB last_sync_at): this is on the price read path, so no write per call; a
    // restart just resets everyone to "never used", which spreads the first calls anyway.
    private final Map<Long, Instant> lastUsedAt = new ConcurrentHashMap<>();

    // CoinGecko's free/Demo tier only serves market_chart/range for the past ~365 days — an older
    // `from` 401s (empirically 380d fails, 360d works; confirmed by the docs: "Public API (Demo plan)
    // is restricted to the past 365 days"). A paid Pro key lifts this; we don't use one, so clamp
    // historical requests to stay just inside the window rather than failing outright.
    private static final int MAX_FREE_HISTORY_DAYS = 364;

    // Coin logo URLs (CoinGecko's own CDN icons) almost never change, so they're cached for the
    // process lifetime instead of the 15-minute TTL PriceService uses for prices.
    private final Map<String, String> logoCache = new ConcurrentHashMap<>();

    private final FinancialAssetRepository assetRepository;
    private final AggregatorService aggregatorService;
    private final WebClient webClient;

    @Autowired
    public CoinGeckoPriceProvider(FinancialAssetRepository assetRepository,
                                  AggregatorService aggregatorService) {
        this(assetRepository, aggregatorService, WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    CoinGeckoPriceProvider(FinancialAssetRepository assetRepository,
                           AggregatorService aggregatorService,
                           WebClient webClient) {
        this.assetRepository = assetRepository;
        this.aggregatorService = aggregatorService;
        this.webClient = webClient;
    }

    /** Breaker slot for a session (anonymous credentials have no id, so map to the sentinel). */
    private static long slot(SessionCredentials session) {
        return session.sessionId() == null ? ANONYMOUS_SESSION : session.sessionId();
    }

    /** True while this session's breaker is open — a recent 429 on that key. */
    private boolean paused(SessionCredentials session) {
        Instant until = breakerUntil.get(slot(session));
        return until != null && Instant.now().isBefore(until);
    }

    /**
     * The sessions this provider may use right now: the enabled Demo keys, or a single anonymous
     * session when the aggregator is enabled but has no key (the free tier still works, just
     * rate-limits harder). Empty when the aggregator is <em>disabled</em> (or unknown) — then the
     * provider makes no call at all, not even anonymous.
     */
    private List<SessionCredentials> candidates() {
        return aggregatorService.enabledCredentials(AGGREGATOR_KEY)
            .map(sessions -> sessions.isEmpty() ? List.of(ANONYMOUS) : sessions)
            .orElseGet(List::of);
    }

    /**
     * Pick a usable session: among those whose breaker is closed, the least-recently-used one (ties
     * broken by session id), so calls rotate across keys instead of hammering one until it 429s. The
     * chosen session is stamped used immediately — a request that later 429s still consumed its
     * quota. Empty when every candidate is paused (or the aggregator is off).
     */
    private Optional<SessionCredentials> pickSession() {
        Optional<SessionCredentials> chosen = candidates().stream()
            .filter(s -> !paused(s))
            .min(Comparator.<SessionCredentials, Instant>comparing(
                    s -> lastUsedAt.getOrDefault(slot(s), Instant.EPOCH))
                .thenComparingLong(CoinGeckoPriceProvider::slot));
        chosen.ifPresent(s -> lastUsedAt.put(slot(s), Instant.now()));
        return chosen;
    }

    /** Apply the session's Demo key as the {@code x-cg-demo-api-key} header (anonymous adds nothing). */
    private static void applyKey(HttpHeaders headers, SessionCredentials session) {
        String key = session.apiKey();
        if (key != null && !key.isBlank()) {
            headers.set("x-cg-demo-api-key", key);
        }
    }

    /**
     * Trip the breaker for one session after a 429: pause only that key until the response's
     * {@code Retry-After} (or {@link #DEFAULT_RETRY_AFTER}) elapses, so the next request rolls over
     * to another enabled key. Logged once per pause window so a page load that fires ~17 price calls
     * doesn't produce ~17 warnings.
     */
    private void pause(SessionCredentials session, Throwable t) {
        Duration wait = retryAfter(t).orElse(DEFAULT_RETRY_AFTER);
        boolean alreadyPaused = paused(session);
        breakerUntil.put(slot(session), Instant.now().plus(wait));
        if (!alreadyPaused) {
            String label = session.sessionId() == null ? "anonymous" : "key #" + session.sessionId();
            log.warn("CoinGecko rate-limited (429) — pausing {} for {}s", label, wait.toSeconds());
        }
    }

    private static boolean isRateLimited(Throwable t) {
        return t instanceof WebClientResponseException e && e.getStatusCode().value() == 429;
    }

    /** The {@code Retry-After} header (delta-seconds) from a 429, if present and numeric. */
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

    /** CoinGecko coin id for an uppercase ticker, from the persistent asset registry, or null. */
    private String coinId(String ticker) {
        return assetRepository.findBySymbol(ticker.toUpperCase())
            .map(FinancialAsset::getCoingeckoId).orElse(null);
    }

    /**
     * Bulk ticker(upper) → coin-id for a set of tickers; only tickers with a real coin id are
     * present. A {@code WORTHLESS} or {@code PENDING} asset carries no id, so it's skipped here —
     * it must never be sent to CoinGecko.
     */
    private Map<String, String> coinIds(Set<String> tickers) {
        Set<String> upper = tickers.stream().map(String::toUpperCase).collect(Collectors.toSet());
        return assetRepository.findBySymbolIn(upper).stream()
            .filter(a -> a.getCoingeckoId() != null)
            .collect(Collectors.toMap(FinancialAsset::getSymbol, FinancialAsset::getCoingeckoId));
    }

    @Override
    public String aggregatorKey() {
        return AGGREGATOR_KEY;
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SPOT, Capability.HISTORY, Capability.INTRADAY);
    }

    /**
     * True once the ticker has an asset <em>with a coin id</em>. A {@code WORTHLESS} asset (no id)
     * is deliberately not priceable here — its price is a fixed zero handled by {@code PriceService},
     * never a CoinGecko fetch.
     */
    @Override
    public boolean canPrice(String ticker) {
        return assetRepository.findBySymbol(ticker.toUpperCase())
            .map(a -> a.getCoingeckoId() != null).orElse(false);
    }

    /** True while at least one enabled key is usable; false only when every candidate session is paused. */
    @Override
    public boolean isAvailable() {
        return pickSession().isPresent();
    }

    /** Soonest instant a paused key frees up, when every candidate is currently paused; else empty. */
    @Override
    public Optional<Instant> pausedUntil() {
        Optional<Instant> soonest = Optional.empty();
        for (SessionCredentials session : candidates()) {
            Instant until = breakerUntil.get(slot(session));
            if (until == null || !Instant.now().isBefore(until)) {
                return Optional.empty();   // this key is usable now — the provider isn't paused
            }
            soonest = soonest.filter(cur -> cur.isBefore(until)).or(() -> Optional.of(until));
        }
        return soonest;
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Map<String, String> tickerToId = coinIds(tickers);
        if (tickerToId.isEmpty()) return Map.of();

        String ids = String.join(",", new LinkedHashSet<>(tickerToId.values()));
        if (ids.isBlank()) return Map.of();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Map.of();

        try {
            Map<String, PriceData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/simple/price")
                    .queryParam("ids", ids)
                    .queryParam("vs_currencies", "eur")
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, PriceData>>() {})
                .timeout(TIMEOUT)
                .block();

            if (response == null) return Map.of();

            Map<String, BigDecimal> result = new HashMap<>();
            for (Map.Entry<String, String> e : tickerToId.entrySet()) {
                PriceData data = response.get(e.getValue());
                if (data != null && data.eur() != null) {
                    result.put(e.getKey(), data.eur());
                }
            }
            return result;
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko price fetch failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch coin logo URLs (CoinGecko's CDN-hosted icons) for the given tickers, batched into a
     * single {@code /coins/markets} request per call for whatever isn't already cached.
     */
    public Map<String, String> getLogoUrls(Set<String> tickers) {
        Map<String, String> tickerToId = coinIds(tickers);
        if (tickerToId.isEmpty()) return Map.of();

        Map<String, String> result = new HashMap<>();
        Map<String, String> missingIdByTicker = new HashMap<>();
        for (Map.Entry<String, String> e : tickerToId.entrySet()) {
            String cached = logoCache.get(e.getKey());
            if (cached != null) {
                result.put(e.getKey(), cached);
            } else {
                missingIdByTicker.put(e.getKey(), e.getValue());
            }
        }
        if (missingIdByTicker.isEmpty()) return result;

        String ids = String.join(",", new LinkedHashSet<>(missingIdByTicker.values()));
        if (ids.isBlank()) return result;
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return result;

        try {
            List<CoinMarketData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/markets")
                    .queryParam("vs_currency", "eur")
                    .queryParam("ids", ids)
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<CoinMarketData>>() {})
                .timeout(TIMEOUT)
                .block();

            if (response != null) {
                Map<String, String> idToImage = new HashMap<>();
                for (CoinMarketData c : response) {
                    if (c.id != null && c.image != null) idToImage.put(c.id, c.image);
                }
                for (Map.Entry<String, String> e : missingIdByTicker.entrySet()) {
                    String image = idToImage.get(e.getValue());
                    if (image != null) {
                        logoCache.put(e.getKey(), image);
                        result.put(e.getKey(), image);
                    }
                }
            }
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko logo fetch failed: {}", ex.getMessage());
        }
        return result;
    }

    /**
     * Look up candidate coins whose CoinGecko symbol matches {@code ticker}, via {@code /search}.
     * Returns them with their market-cap rank so {@link com.picsou.service.FinancialAssetService}
     * can pick a dominant match. Empty on miss or error — the resolver treats that as "unresolved".
     */
    public List<CoinCandidate> searchBySymbol(String ticker) {
        String symbol = ticker.trim().toLowerCase();
        if (symbol.isEmpty()) return List.of();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return List.of();
        try {
            SearchResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search")
                    .queryParam("query", symbol)
                    .build())
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.coins == null) return List.of();
            return response.coins.stream()
                .filter(c -> c.id != null && c.symbol != null && c.symbol.equalsIgnoreCase(symbol))
                .map(c -> new CoinCandidate(c.id, c.name, c.symbol, c.marketCapRank))
                .toList();
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko symbol search failed for {}: {}", ticker, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch a single coin by its CoinGecko id (via {@code /coins/{id}}), narrowed to
     * {@link CoinCandidate}. Used to validate an operator-supplied disambiguation link and read the
     * coin's canonical name. Empty when the id is unknown or the call fails.
     */
    public Optional<CoinCandidate> fetchCoinById(String id) {
        String coinId = id == null ? "" : id.trim().toLowerCase();
        if (coinId.isEmpty()) return Optional.empty();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Optional.empty();
        try {
            CoinDetail detail = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}")
                    .queryParam("localization", "false")
                    .queryParam("tickers", "false")
                    .queryParam("market_data", "false")
                    .queryParam("community_data", "false")
                    .queryParam("developer_data", "false")
                    .queryParam("sparkline", "false")
                    .build(coinId))
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(CoinDetail.class)
                .timeout(TIMEOUT)
                .block();

            if (detail == null || detail.id == null) return Optional.empty();
            return Optional.of(new CoinCandidate(detail.id, detail.name, detail.symbol, detail.marketCapRank));
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko coin lookup failed for id {}: {}", coinId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch hourly prices for a crypto ticker from CoinGecko over the last 24H.
     * CoinGecko's market_chart/range returns hourly data for ranges < 90 days.
     */
    @Override
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        String coinId = coinId(ticker);
        if (coinId == null) return Map.of();
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Map.of();

        try {
            long fromEpoch = from.atZone(ZoneOffset.UTC).toEpochSecond();
            long toEpoch = to.atZone(ZoneOffset.UTC).toEpochSecond();

            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}/market_chart/range")
                    .queryParam("vs_currency", "eur")
                    .queryParam("from", fromEpoch)
                    .queryParam("to", toEpoch)
                    .build(coinId))
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return Map.of();

            List<?> rawPrices = (List<?>) response.getOrDefault("prices", List.of());
            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();

            for (Object entry : rawPrices) {
                List<?> pair = (List<?>) entry;
                if (pair.size() >= 2) {
                    long timestamp = ((Number) pair.get(0)).longValue();
                    double price = ((Number) pair.get(1)).doubleValue();
                    LocalDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
                    if (!dt.isBefore(from) && !dt.isAfter(to) && price > 0) {
                        prices.put(dt, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                    }
                }
            }

            log.debug("Fetched {} intraday prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko intraday price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        String coinId = coinId(ticker);
        if (coinId == null) return Map.of();

        // The free/Demo tier can't reach data older than ~365 days (an older `from` 401s regardless
        // of the window size), so clamp rather than fail — we return as much recent history as the
        // tier allows. Older history would need a paid Pro key.
        LocalDate floor = LocalDate.now().minusDays(MAX_FREE_HISTORY_DAYS);
        if (from.isBefore(floor)) from = floor;
        SessionCredentials session = pickSession().orElse(null);
        if (session == null) return Map.of();

        try {
            long fromEpoch = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long toEpoch = to.atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/{id}/market_chart/range")
                    .queryParam("vs_currency", "eur")
                    .queryParam("from", fromEpoch)
                    .queryParam("to", toEpoch)
                    .build(coinId))
                .headers(h -> applyKey(h, session))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null) return Map.of();

            List<?> rawPrices = (List<?>) response.getOrDefault("prices", List.of());
            Map<LocalDate, BigDecimal> prices = new HashMap<>();

            for (Object entry : rawPrices) {
                List<?> pair = (List<?>) entry;
                if (pair.size() >= 2) {
                    long timestamp = ((Number) pair.get(0)).longValue();
                    double price = ((Number) pair.get(1)).doubleValue();
                    LocalDate date = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
                    if (!date.isBefore(from) && !date.isAfter(to) && price > 0) {
                        prices.put(date, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                    }
                }
            }

            log.debug("Fetched {} historical prices for {} ({}) from CoinGecko", prices.size(), ticker, coinId);
            return prices;
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko historical price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /** A coin returned by CoinGecko's {@code /search}, narrowed to what the resolver needs. */
    public record CoinCandidate(String id, String name, String symbol, Integer marketCapRank) {}

    static class PriceData {
        private BigDecimal eur;

        @JsonAnySetter
        public void setField(String key, Object value) {
            if ("eur".equals(key) && value instanceof Number n) {
                this.eur = BigDecimal.valueOf(n.doubleValue());
            }
        }

        public BigDecimal eur() { return eur; }
    }

    /** One entry of CoinGecko's {@code /coins/markets} response; only id/image are of interest. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CoinMarketData {
        public String id;
        public String image;
    }

    /** CoinGecko {@code /coins/{id}} response, narrowed to identity + rank. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CoinDetail {
        public String id;
        public String name;
        public String symbol;
        @JsonProperty("market_cap_rank")
        public Integer marketCapRank;
    }

    /** CoinGecko {@code /search} response, narrowed to the coins list. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchResponse {
        public List<SearchCoin> coins;
    }

    /** One coin in a {@code /search} response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SearchCoin {
        public String id;
        public String name;
        public String symbol;
        @JsonProperty("market_cap_rank")
        public Integer marketCapRank;
    }
}
