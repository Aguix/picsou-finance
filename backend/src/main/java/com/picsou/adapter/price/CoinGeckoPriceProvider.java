package com.picsou.adapter.price;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.AssetResolverPort;
import com.picsou.port.PriceProviderPort;
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
import org.springframework.web.reactive.function.client.WebClientRequestException;
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
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches crypto prices and logos from the CoinGecko API, and resolves symbols to CoinGecko coin ids
 * ({@link AssetResolverPort}) — the two sides of one aggregator: {@link #searchBySymbol} finds the
 * ids, {@link #getPricesEur} spends them.
 *
 * <p>An asset is priceable here once it carries a CoinGecko id, which resolution persists in
 * {@code financial_asset.coingecko_id} — this adapter's own ref column, the only one it reads or
 * writes ({@link #getRef}/{@link #setRef}). The caller hands the asset in, so this adapter reads the
 * id off it directly and carries no registry dependency of its own, and it decides no mapping itself:
 * {@link com.picsou.service.FinancialAssetService} does, from the candidates offered here.
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
@Order(10)   // first choice — tried before the other aggregators when several can price an asset
public class CoinGeckoPriceProvider implements PriceProviderPort, AssetResolverPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** History and intraday pull a whole range, so they get a longer budget than a spot quote. */
    private static final Duration HISTORY_TIMEOUT = Duration.ofSeconds(15);
    private static final String AGGREGATOR_KEY = "coingecko";

    /** Grabs the coin-id slug from a CoinGecko coin URL, e.g. {@code .../en/coins/loaded-lions}. */
    private static final Pattern COIN_URL = Pattern.compile("/coins/([^/?#]+)");

    // Circuit breaker: how long to pause a session after a 429 when the response carries no usable
    // Retry-After (CoinGecko's free-tier window is ~1 minute).
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    // Ceiling on a server-supplied Retry-After. A misconfigured (or hostile) "86400" would otherwise
    // park a key for a day, long after the real limit lifted -- and with the LRU rotation that means
    // the remaining keys absorb the whole load for that day.
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(15);

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

    private final AggregatorService aggregatorService;
    private final WebClient webClient;

    @Autowired
    public CoinGeckoPriceProvider(AggregatorService aggregatorService) {
        this(aggregatorService, WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    CoinGeckoPriceProvider(AggregatorService aggregatorService,
                           WebClient webClient) {
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
                    long seconds = Long.parseLong(header.trim());
                    // A zero or negative delay would put the breaker's deadline in the past, i.e.
                    // silently not pause the key at all -- fall back to the default window instead.
                    if (seconds <= 0) return Optional.empty();
                    return Optional.of(Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds())));
                } catch (NumberFormatException ignored) {
                    // Retry-After can also be an HTTP-date; we don't parse that — fall back to default.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Classifies a failed CoinGecko call, and decides whether it is ours to swallow.
     *
     * <p><b>Expected upstream failures</b> (HTTP error, unreachable API, timeout) are logged and the
     * caller returns no prices. That contract is load-bearing: a missing price means "not valued by
     * this aggregator", never "not held" — {@link com.picsou.service.PriceRouter} hands whatever this
     * provider didn't return to the next one, and {@code WalletSyncService} keys its holdings prune on
     * on-chain balances, so a CoinGecko blip leaves holdings and their cost basis intact. Severity
     * lives in the log, graded by whose problem it is.
     *
     * <p><b>Anything else</b> — an NPE, a {@link ClassCastException}, a parse defect — is
     * <em>rethrown</em>. Swallowing a real bug into an empty map hides it behind data that merely
     * looks unpriced. This is safe because {@code PriceRouter} guards each provider call and drops to
     * the next aggregator on a throw, so a defect here degrades to a fallback instead of aborting a
     * batch.
     *
     * <p>Note it unwraps first: {@code Mono.timeout()} signals a <em>checked</em>
     * {@link TimeoutException}, which {@code block()} wraps in a reactor {@code ReactiveException}.
     * Matching on the declared type without unwrapping would miss timeouts entirely — the most common
     * real CoinGecko failure.
     */
    private static void handleFetchFailure(String operation, Object context, Duration timeout, RuntimeException ex) {
        Throwable cause = reactor.core.Exceptions.unwrap(ex);
        if (cause instanceof WebClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == 429) {
                log.warn("CoinGecko rate-limited (429) fetching {} for {} -- returning no prices", operation, context);
            } else if (http.getStatusCode().is5xxServerError()) {
                // Their outage, not our bug: WARN, matching how the rest of the codebase grades
                // expected external failures. These callers run on a scheduler, so an hours-long
                // outage would otherwise pour ERROR lines (each carrying a full HTML error page)
                // into a self-hosted instance's log.
                log.warn("CoinGecko server error (HTTP {}) fetching {} for {} -- returning no prices: {}",
                    status, operation, context, lazyBody(http));
            } else if (status == 400 || status == 404) {
                // A malformed request or an unknown coin id points at a bad coingecko_id in the
                // registry -- something we can actually fix, so ERROR. The body is decoded lazily
                // via a supplier so a disabled level costs nothing.
                log.error("CoinGecko rejected the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, context, status, lazyBody(http));
            } else {
                // Other 4xx (401/403 free-tier restrictions, 451...) are the provider's access
                // policy, not a bug on our side: WARN like the other outage cases.
                log.warn("CoinGecko refused the {} request for {} with HTTP {} -- returning no prices: {}",
                    operation, context, status, lazyBody(http));
            }
        } else if (cause instanceof TimeoutException) {
            log.warn("CoinGecko {} request for {} timed out after {} -- returning no prices",
                operation, context, timeout);
        } else if (cause instanceof WebClientRequestException) {
            // Never reached the server at all: DNS failure, connection refused/reset, TLS handshake.
            // Same class of expected outage as a 5xx -- WARN, and without the stacktrace, which
            // would otherwise flood the log for the whole outage.
            log.warn("CoinGecko {} request for {} could not reach the API ({}) -- returning no prices",
                operation, context, cause.getMessage());
        } else {
            // Not an upstream failure -- an NPE, ClassCastException or parse defect on our side.
            // Rethrow rather than return an empty map: a bug that presents as "no prices" is
            // indistinguishable from a quiet outage and would never get fixed.
            throw ex;
        }
    }

    /**
     * Walks CoinGecko's {@code prices} field — documented as an array of {@code [epochMillis, price]}
     * pairs — handing each well-formed pair to {@code consumer}.
     *
     * <p>Every step is checked rather than cast. A shape change upstream (an object instead of an
     * array, string-encoded numbers, a short pair) must degrade to a warn and a skip: since
     * {@link #handleFetchFailure} rethrows anything that is not an upstream failure, a blind cast here
     * would turn a CoinGecko format change into a {@link ClassCastException} bouncing the whole
     * aggregator out of the price waterfall.
     */
    private static void forEachPricePoint(
        Map<String, Object> response, String context, java.util.function.BiConsumer<Long, Double> consumer) {

        Object raw = response.getOrDefault("prices", List.of());
        if (!(raw instanceof List<?> rawPrices)) {
            log.warn("CoinGecko returned a non-list 'prices' field ({}) for {} -- returning no prices",
                raw == null ? "null" : raw.getClass().getSimpleName(), context);
            return;
        }

        int skipped = 0;
        for (Object entry : rawPrices) {
            if (!(entry instanceof List<?> pair) || pair.size() < 2
                || !(pair.get(0) instanceof Number timestamp)
                || !(pair.get(1) instanceof Number price)) {
                skipped++;
                continue;
            }
            consumer.accept(timestamp.longValue(), price.doubleValue());
        }

        // Once per call, not per entry: a wholesale format change would otherwise emit one line per
        // data point, thousands of them for a long range.
        if (skipped > 0) {
            log.warn("CoinGecko returned {} malformed price points (of {}) for {} -- skipped",
                skipped, rawPrices.size(), context);
        }
    }

    /**
     * Defers decoding the upstream error body until the log level is known to be enabled — SLF4J only
     * calls {@code toString()} on an argument it actually formats. Also caps it, so one bad gateway's
     * multi-kilobyte HTML page can't fill the log.
     */
    private static Object lazyBody(WebClientResponseException http) {
        return new Object() {
            @Override public String toString() {
                String body = http.getResponseBodyAsString();
                if (body == null || body.isBlank()) return "<empty body>";
                return body.length() <= 200 ? body : body.substring(0, 200) + "... (truncated)";
            }
        };
    }

    /** Uppercase symbol → CoinGecko coin id for the priceable assets in the set (id present). */
    private static Map<String, String> coinIds(Collection<FinancialAsset> assets) {
        Map<String, String> bySymbol = new HashMap<>();
        for (FinancialAsset asset : assets) {
            if (asset.getCoingeckoId() != null) {
                bySymbol.put(asset.getSymbol().toUpperCase(), asset.getCoingeckoId());
            }
        }
        return bySymbol;
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
     * True once the asset carries a coin id. A {@code WORTHLESS} asset (no id) is deliberately not
     * priceable here — its price is a fixed zero handled by {@code PriceService}, never a CoinGecko
     * fetch.
     */
    @Override
    public boolean canPrice(FinancialAsset asset) {
        return asset.getCoingeckoId() != null;
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
    public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
        Map<String, String> tickerToId = coinIds(assets);
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

            if (response == null) {
                log.warn("CoinGecko returned an empty body for spot prices {} -- returning no prices",
                    tickerToId.keySet());
                return Map.of();
            }

            Map<String, BigDecimal> result = new HashMap<>();
            for (Map.Entry<String, String> e : tickerToId.entrySet()) {
                PriceData data = response.get(e.getValue());
                if (data != null && data.eur() != null) {
                    result.put(e.getKey(), data.eur());
                }
            }
            return result;
        } catch (RuntimeException ex) {
            if (isRateLimited(ex)) pause(session, ex);
            handleFetchFailure("spot prices", tickerToId.keySet(), TIMEOUT, ex);
            return Map.of();
        }
    }

    /** This adapter's own ref column — the CoinGecko coin id. */
    @Override
    public String getRef(FinancialAsset asset) {
        return asset.getCoingeckoId();
    }

    @Override
    public void setRef(FinancialAsset asset, String id) {
        asset.setCoingeckoId(id);
    }

    /**
     * Read the coin id out of a CoinGecko coin-page URL slug (e.g. {@code .../en/coins/loaded-lions}),
     * the "paste a link" path to disambiguating a symbol. Empty when the link isn't a coin URL — the
     * caller then reports it rather than resolving something wrong.
     */
    @Override
    public Optional<String> extractIdFromUrl(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        Matcher m = COIN_URL.matcher(url.trim());
        if (!m.find()) return Optional.empty();
        String id = m.group(1).trim().toLowerCase();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    /**
     * Look up candidate coins whose CoinGecko symbol matches {@code ticker}, via {@code /search}.
     * Returns them with their market-cap rank so {@link com.picsou.service.FinancialAssetService}
     * can pick a dominant match. Empty on miss or error — the resolver treats that as "unresolved".
     */
    @Override
    public List<AssetCandidate> searchBySymbol(String ticker) {
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
                .map(c -> new AssetCandidate(c.id, c.name, c.symbol, c.marketCapRank))
                .toList();
        } catch (Exception ex) {
            if (isRateLimited(ex)) pause(session, ex);
            log.warn("CoinGecko symbol search failed for {}: {}", ticker, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Fetch a single coin by its CoinGecko id (via {@code /coins/{id}}), narrowed to
     * {@link AssetCandidate}. Used to validate an operator-supplied disambiguation link and read the
     * coin's canonical name. Empty when the id is unknown or the call fails.
     */
    @Override
    public Optional<AssetCandidate> fetchById(String id) {
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
            return Optional.of(new AssetCandidate(detail.id, detail.name, detail.symbol, detail.marketCapRank));
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
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        String coinId = asset.getCoingeckoId();
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
                .timeout(HISTORY_TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for intraday prices of {} ({})",
                    asset.getSymbol(), coinId);
                return Map.of();
            }

            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();
            forEachPricePoint(response, asset.getSymbol() + " (" + coinId + ")", (timestamp, price) -> {
                LocalDateTime dt = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
                if (!dt.isBefore(from) && !dt.isAfter(to) && price > 0) {
                    prices.put(dt, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                }
            });

            log.debug("Fetched {} intraday prices for {} ({}) from CoinGecko", prices.size(), asset.getSymbol(), coinId);
            return prices;
        } catch (RuntimeException ex) {
            if (isRateLimited(ex)) pause(session, ex);
            handleFetchFailure("intraday prices", asset.getSymbol() + " (" + coinId + ")", HISTORY_TIMEOUT, ex);
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(FinancialAsset asset, LocalDate from, LocalDate to) {
        String coinId = asset.getCoingeckoId();
        if (coinId == null) return Map.of();

        // The free/Demo tier can't reach data older than ~365 days (an older `from` 401s regardless
        // of the window size), so clamp rather than fail — we return as much recent history as the
        // tier allows. Older history would need a paid Pro key.
        LocalDate floor = LocalDate.now().minusDays(MAX_FREE_HISTORY_DAYS);
        if (from.isBefore(floor)) from = floor;
        // Effectively-final copy for the range filter below: the clamp reassigns `from`, so it can't
        // be captured by a lambda.
        final LocalDate rangeStart = from;
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
                .timeout(HISTORY_TIMEOUT)
                .block();

            if (response == null) {
                log.warn("CoinGecko returned an empty body for historical prices of {} ({})",
                    asset.getSymbol(), coinId);
                return Map.of();
            }

            Map<LocalDate, BigDecimal> prices = new HashMap<>();
            forEachPricePoint(response, asset.getSymbol() + " (" + coinId + ")", (timestamp, price) -> {
                LocalDate date = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.isBefore(rangeStart) && !date.isAfter(to) && price > 0) {
                    prices.put(date, BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP));
                }
            });

            log.debug("Fetched {} historical prices for {} ({}) from CoinGecko", prices.size(), asset.getSymbol(), coinId);
            return prices;
        } catch (RuntimeException ex) {
            if (isRateLimited(ex)) pause(session, ex);
            handleFetchFailure("historical prices", asset.getSymbol() + " (" + coinId + ")", HISTORY_TIMEOUT, ex);
            return Map.of();
        }
    }

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
