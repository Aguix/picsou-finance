package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.picsou.model.CoinMapping;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.CoinMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

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
 * <p>Ticker → coin-id resolution is fully dynamic: it reads the persistent {@code coin_mapping}
 * cache (see {@link CoinMappingRepository} / {@link com.picsou.service.CoinMappingService}) instead
 * of a hardcoded map. A ticker is {@link #supports(String) supported} once it has a mapping — which
 * {@code CoinMappingService} resolves from CoinGecko at crypto-import time. This class stays the
 * low-level HTTP client: it also exposes {@link #searchBySymbol(String)} so the resolver can look
 * candidates up, but never decides or persists a mapping itself.
 *
 * <p>An optional Demo API key ({@code app.coingecko.demo-api-key}) is sent as the
 * {@code x-cg-demo-api-key} header when present; absent, calls fall back to the anonymous free
 * tier with no change in behaviour beyond a stricter rate limit.
 *
 * <p>The free tier rate-limits aggressively (a burst 429s within a handful of calls). Rather than
 * pace requests with a blind fixed delay, every call reacts to the actual {@code 429}: it waits the
 * {@code Retry-After} the response carries (falling back to {@link #DEFAULT_RETRY_AFTER} if absent)
 * and retries, up to {@link #MAX_RATE_LIMIT_RETRIES} times. So imports run at full speed until they
 * actually hit the ceiling, then wait exactly as long as CoinGecko asks — see {@link #rateLimitRetry}.
 */
@Component
public class CoinGeckoPriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Rate-limit backoff: how many times to retry a 429, and how long to wait when the response
    // carries no usable Retry-After (CoinGecko's free-tier window is ~1 minute).
    private static final int MAX_RATE_LIMIT_RETRIES = 4;
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(60);

    // Coin logo URLs (CoinGecko's own CDN icons) almost never change, so they're cached for the
    // process lifetime instead of the 15-minute TTL PriceService uses for prices.
    private final Map<String, String> logoCache = new ConcurrentHashMap<>();

    private final CoinMappingRepository coinMappingRepository;
    private final WebClient webClient;

    public CoinGeckoPriceProvider(CoinMappingRepository coinMappingRepository,
                                  @Value("${app.coingecko.demo-api-key:}") String demoApiKey) {
        this.coinMappingRepository = coinMappingRepository;
        WebClient.Builder builder = WebClient.builder()
            .baseUrl("https://api.coingecko.com/api/v3")
            .defaultHeader("Accept", "application/json");
        if (demoApiKey != null && !demoApiKey.isBlank()) {
            builder.defaultHeader("x-cg-demo-api-key", demoApiKey.trim());
            log.info("CoinGecko Demo API key configured");
        }
        this.webClient = builder.build();
    }

    /**
     * Retry policy for a single CoinGecko call: on a {@code 429}, wait the response's
     * {@code Retry-After} (or {@link #DEFAULT_RETRY_AFTER} when it's missing/unparseable) and retry,
     * up to {@link #MAX_RATE_LIMIT_RETRIES} times. Any non-429 error propagates immediately. When the
     * retries are exhausted the original 429 is re-thrown, so the caller's try/catch degrades to an
     * empty result as before — never fabricating data.
     */
    private Retry rateLimitRetry(String context) {
        return Retry.from(signals -> signals.flatMap(signal -> {
            Throwable failure = signal.failure();
            if (!isRateLimited(failure) || signal.totalRetries() >= MAX_RATE_LIMIT_RETRIES) {
                return Mono.error(failure);
            }
            Duration wait = retryAfter(failure).orElse(DEFAULT_RETRY_AFTER);
            log.warn("CoinGecko rate-limited on {} — waiting {}s before retry {}/{}",
                context, wait.toSeconds(), signal.totalRetries() + 1, MAX_RATE_LIMIT_RETRIES);
            return Mono.delay(wait);
        }));
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

    /** CoinGecko coin id for an uppercase ticker, from the persistent mapping cache, or null. */
    private String coinId(String ticker) {
        return coinMappingRepository.findByTicker(ticker.toUpperCase())
            .map(CoinMapping::getCoingeckoId).orElse(null);
    }

    /** Bulk ticker(upper) → coin-id for a set of tickers; only mapped tickers are present. */
    private Map<String, String> coinIds(Set<String> tickers) {
        Set<String> upper = tickers.stream().map(String::toUpperCase).collect(Collectors.toSet());
        return coinMappingRepository.findByTickerIn(upper).stream()
            .collect(Collectors.toMap(CoinMapping::getTicker, CoinMapping::getCoingeckoId));
    }

    @Override
    public boolean supports(String ticker) {
        return coinMappingRepository.findByTicker(ticker.toUpperCase()).isPresent();
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Map<String, String> tickerToId = coinIds(tickers);
        if (tickerToId.isEmpty()) return Map.of();

        String ids = String.join(",", new LinkedHashSet<>(tickerToId.values()));
        if (ids.isBlank()) return Map.of();

        try {
            Map<String, PriceData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/simple/price")
                    .queryParam("ids", ids)
                    .queryParam("vs_currencies", "eur")
                    .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, PriceData>>() {})
                .timeout(TIMEOUT)
                .retryWhen(rateLimitRetry("simple/price"))
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

        try {
            List<CoinMarketData> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/coins/markets")
                    .queryParam("vs_currency", "eur")
                    .queryParam("ids", ids)
                    .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<CoinMarketData>>() {})
                .timeout(TIMEOUT)
                .retryWhen(rateLimitRetry("coins/markets"))
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
            log.warn("CoinGecko logo fetch failed: {}", ex.getMessage());
        }
        return result;
    }

    /**
     * Look up candidate coins whose CoinGecko symbol matches {@code ticker}, via {@code /search}.
     * Returns them with their market-cap rank so {@link com.picsou.service.CoinMappingService} can
     * pick a dominant match. Empty on miss or error — the resolver treats that as "unresolved".
     */
    public List<CoinCandidate> searchBySymbol(String ticker) {
        String symbol = ticker.trim().toLowerCase();
        if (symbol.isEmpty()) return List.of();
        try {
            SearchResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/search")
                    .queryParam("query", symbol)
                    .build())
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(TIMEOUT)
                .retryWhen(rateLimitRetry("search " + symbol))
                .block();

            if (response == null || response.coins == null) return List.of();
            return response.coins.stream()
                .filter(c -> c.id != null && c.symbol != null && c.symbol.equalsIgnoreCase(symbol))
                .map(c -> new CoinCandidate(c.id, c.name, c.symbol, c.marketCapRank))
                .toList();
        } catch (Exception ex) {
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
                .retrieve()
                .bodyToMono(CoinDetail.class)
                .timeout(TIMEOUT)
                .retryWhen(rateLimitRetry("coins/" + coinId))
                .block();

            if (detail == null || detail.id == null) return Optional.empty();
            return Optional.of(new CoinCandidate(detail.id, detail.name, detail.symbol, detail.marketCapRank));
        } catch (Exception ex) {
            log.warn("CoinGecko coin lookup failed for id {}: {}", coinId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fetch hourly prices for a crypto ticker from CoinGecko over the last 24H.
     * CoinGecko's market_chart/range returns hourly data for ranges < 90 days.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        String coinId = coinId(ticker);
        if (coinId == null) return Map.of();

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
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .retryWhen(rateLimitRetry("intraday " + ticker))
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
            log.warn("CoinGecko intraday price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a crypto ticker from CoinGecko.
     * Returns a map of date -> priceEur.
     */
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        String coinId = coinId(ticker);
        if (coinId == null) return Map.of();

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
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .retryWhen(rateLimitRetry("historical " + ticker))
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
