package com.picsou.adapter.price;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.model.FinancialAsset;
import com.picsou.port.AssetCandidate;
import com.picsou.port.AssetResolverPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.port.SymbolCatalogPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches stock/ETF prices from Yahoo Finance (unofficial, no API key needed), and resolves symbols
 * to Yahoo symbols ({@link AssetResolverPort}) — the two sides of one aggregator.
 * Used for PEA/Compte-Titres positions with tickers like "IWDA.AS", "MC.PA", etc.
 *
 * Only prices assets carrying a {@code yahoo_symbol} (see {@code canPrice}) — this adapter's own ref
 * column, the only one it reads or writes. An unresolved asset is rejected rather than queried on its
 * raw internal symbol. Yahoo's "id" <em>is</em> the symbol it quotes, so {@link #getRef} and
 * {@link #fetchById} deal in the same exchange-suffixed string ("IWDA.AS").
 *
 * Prices are converted to EUR using Yahoo's own FX endpoint ({CURRENCY}EUR=X)
 * when the security is quoted in a non-EUR currency. Rates are cached for 15
 * minutes to limit API calls. London pence (GBp/GBX) is handled as GBP/100.
 *
 * Note: This is an unofficial API. For production use consider Alpha Vantage or similar.
 */
@Component
@Order(20)   // last resort — tried once the aggregators ahead of it have no ref for the asset
public class YahooFinancePriceProvider implements PriceProviderPort, AssetResolverPort, SymbolCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(YahooFinancePriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Timeout for the {@link SymbolCatalogPort} calls, shorter than the one a price read gets.
     *
     * <p>The two are not worth the same wait. A price that fails to arrive leaves a holding with no
     * value, so it is worth waiting for. A verification that fails to arrive costs nothing — the
     * caller keeps the ticker it already had — but it is paid on the write path, inside the
     * transaction of a user saving a transaction or importing a CSV. Three seconds is already an
     * order of magnitude above what the chart endpoint answers in.
     */
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(3);

    private static final Duration FX_CACHE_TTL = Duration.ofMinutes(15);

    private static final java.util.regex.Pattern SYMBOL_PATTERN =
        java.util.regex.Pattern.compile("(?:\\^[A-Z0-9][A-Z0-9.=-]{0,18}|[A-Z0-9][A-Z0-9.=-]{0,19})");


    private final WebClient webClient;
    private final Map<String, CachedFx> fxCache = new ConcurrentHashMap<>();

    public YahooFinancePriceProvider() {
        this(WebClient.builder()
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build());
    }

    // Package-private constructor for tests — inject a WebClient backed by an ExchangeFunction.
    YahooFinancePriceProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String aggregatorKey() {
        return "yahoo";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.of(Capability.SPOT, Capability.HISTORY, Capability.INTRADAY);
    }

    /**
     * Yahoo is the catch-all quote source: it accepts any asset carrying a {@code yahoo_symbol}
     * (populated at discovery — see {@code FinancialAssetService.getOrCreateStock}) that isn't a
     * plain ISIN. An asset with no {@code yahoo_symbol} (an unresolved crypto, e.g.) is rejected
     * outright — it no longer falls through to a wasted Yahoo call on the raw internal symbol.
     * The router only reaches Yahoo for an asset CoinGecko couldn't price (no {@code coingecko_id}).
     */
    @Override
    public boolean canPrice(FinancialAsset asset) {
        return supports(yahooSymbol(asset));
    }

    /**
     * Whether Yahoo can be queried with this symbol at all.
     *
     * <p>The string-level half of {@link #canPrice}, kept separate because the
     * {@link SymbolCatalogPort} calls need it before any asset carries the symbol: they verify a
     * <em>candidate</em> ticker, so there is nothing to read a {@code yahoo_symbol} off yet.
     */
    // Package-private, not private: the symbol-catalog tests drive it directly with raw strings.
    boolean supports(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        String upper = ticker.toUpperCase(Locale.ROOT);

        // Don't price plain ISIN codes (12-character alphanumeric starting with a 2-letter country
        // code). ISIN format: AA########X (2 letters, 9 digits, 1 check digit).
        if (upper.length() == 12 && upper.matches("[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]")) {
            log.debug("Rejecting unsupported ISIN: {}", ticker);
            return false;
        }

        if (!SYMBOL_PATTERN.matcher(upper).matches()) {
            log.debug("Rejecting non-symbol ticker: {}", ticker);
            return false;
        }

        return true;
    }

    /** The symbol Yahoo is queried with: the asset's {@code yahoo_symbol}. */
    private static String yahooSymbol(FinancialAsset asset) {
        return asset.getYahooSymbol();
    }

    /** This adapter's own ref column — for Yahoo the ref simply is the symbol it quotes. */
    @Override
    public String getRef(FinancialAsset asset) {
        return asset.getYahooSymbol();
    }

    @Override
    public void setRef(FinancialAsset asset, String id) {
        asset.setYahooSymbol(id);
    }

    /**
     * Look up the symbols Yahoo quotes for a ticker, via its {@code /v1/finance/search} endpoint —
     * the ticker itself plus its exchange-suffixed listings ({@code IWDA} → {@code IWDA.AS},
     * {@code IWDA.L}), which is what an operator has to choose between: the same security on two
     * exchanges is two different Yahoo symbols, quoted in two different currencies.
     *
     * <p>Candidates carry no {@code marketCapRank} — Yahoo doesn't rank, and the ranking is what the
     * resolver's dominant-match auto-suggestion needs. So a Yahoo candidate is never pre-selected:
     * {@code yahoo_symbol} stays null until an operator picks a listing explicitly. That's deliberate
     * — guessing an exchange would silently quote the security in the wrong market.
     */
    @Override
    public List<AssetCandidate> searchBySymbol(String symbol) {
        String query = symbol == null ? "" : symbol.trim();
        if (query.isEmpty()) return List.of();
        try {
            SearchResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/v1/finance/search")
                    .queryParam("q", query)
                    .queryParam("quotesCount", 10)
                    .queryParam("newsCount", 0)
                    .build())
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.quotes() == null) return List.of();
            return response.quotes().stream()
                .filter(q -> q.symbol() != null && matchesTicker(q.symbol(), query))
                .map(q -> new AssetCandidate(q.symbol(), quoteName(q), q.symbol(), null))
                .toList();
        } catch (Exception ex) {
            log.warn("Yahoo symbol search failed for {}: {}", query, ex.getMessage());
            return List.of();
        }
    }

    /**
     * A Yahoo hit is a match when it's the ticker itself or one of its exchange listings
     * ({@code IWDA} matches {@code IWDA} and {@code IWDA.AS}, not {@code IWDAX}) — Yahoo's search is
     * fuzzy and also returns name matches, which would be noise in a symbol picker.
     */
    private static boolean matchesTicker(String candidateSymbol, String query) {
        String candidate = candidateSymbol.toUpperCase(Locale.ROOT);
        String upper = query.toUpperCase(Locale.ROOT);
        return candidate.equals(upper) || candidate.startsWith(upper + ".");
    }

    private static String quoteName(SearchQuote quote) {
        if (quote.longname() != null && !quote.longname().isBlank()) return quote.longname();
        return quote.shortname();
    }

    /**
     * Validate one Yahoo symbol by asking the quote endpoint for it — a symbol Yahoo can't quote
     * yields empty rather than a dead ref. The name comes from the chart metadata when it carries one.
     */
    @Override
    public Optional<AssetCandidate> fetchById(String id) {
        String ticker = id == null ? "" : id.trim();
        if (ticker.isEmpty()) return Optional.empty();
        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{ticker}?range=1d&interval=1d", ticker)
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return Optional.empty();
            }
            Meta meta = response.chart().result().get(0).meta();
            if (meta == null || meta.regularMarketPrice() <= 0) return Optional.empty();
            String name = meta.longName() != null && !meta.longName().isBlank()
                ? meta.longName() : meta.shortName();
            return Optional.of(new AssetCandidate(ticker, name, ticker, null));
        } catch (Exception ex) {
            log.warn("Yahoo symbol lookup failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Collection<FinancialAsset> assets) {
        Map<String, BigDecimal> result = new HashMap<>();

        // Yahoo Finance is fetched per-ticker (no batch endpoint for EUR conversion). The result is
        // keyed by the asset's internal symbol (what callers look prices up by); the request uses the
        // Yahoo symbol.
        for (FinancialAsset asset : assets) {
            if (!canPrice(asset)) continue;
            try {
                BigDecimal price = fetchSinglePrice(yahooSymbol(asset));
                if (price != null) result.put(asset.getSymbol().toUpperCase(Locale.ROOT), price);
            } catch (Exception ex) {
                log.warn("Yahoo Finance price fetch failed for {}: {}", asset.getSymbol(), ex.getMessage());
            }
        }

        return result;
    }

    private BigDecimal fetchSinglePrice(String ticker) {
        Meta meta = fetchMeta(ticker);
        if (meta == null) return null;

        double price = meta.regularMarketPrice();
        if (price <= 0) return null;

        return applyFx(price, meta.currency());
    }

    /**
     * The {@code meta} block of the chart endpoint — quote, currency and instrument type in one
     * response. Null when Yahoo has no data for {@code ticker}. Propagates transport failures to
     * the caller, which decides between logging a price miss and reporting "no such symbol".
     */
    private Meta fetchMeta(String ticker) {
        return fetchMeta(ticker, TIMEOUT);
    }

    private Meta fetchMeta(String ticker, Duration timeout) {
        YahooResponse response = webClient.get()
            .uri("/v8/finance/chart/{ticker}?range=1d&interval=1d", ticker)
            .retrieve()
            .bodyToMono(YahooResponse.class)
            .timeout(timeout)
            .block();

        if (response == null || response.chart() == null || response.chart().result() == null
            || response.chart().result().isEmpty()) {
            return null;
        }
        return response.chart().result().get(0).meta();
    }

    /**
     * Whether Yahoo currently quotes {@code ticker} at all — a symbol check, not a price read.
     *
     * <p>Used by {@link OpenFigiIsinConverter} to verify that the symbol it derived from an ISIN
     * is one Yahoo actually carries, before that symbol is persisted on a holding and every later
     * valuation depends on it. FX is deliberately not applied: an unavailable EUR rate says
     * nothing about whether the symbol exists, and treating it as "no such symbol" would send a
     * perfectly good ticker to the search fallback.
     *
     * <p>False on any failure — a rate-limited or unreachable Yahoo must never be read as
     * "this symbol is dead", since the caller only ever <em>replaces</em> a symbol on a positive
     * quote from a different one.
     */
    @Override
    public boolean hasQuote(String ticker) {
        if (!supports(ticker)) return false;
        try {
            Meta meta = fetchMeta(ticker, VERIFY_TIMEOUT);
            return meta != null && meta.regularMarketPrice() > 0;
        } catch (Exception ex) {
            log.debug("Yahoo quote probe failed for {}: {}", ticker, ex.getMessage());
            return false;
        }
    }

    /**
     * The symbols Yahoo's own search returns for {@code query} — an ISIN, in practice — in Yahoo's
     * relevance order, restricted to entries it indexes itself ({@code isYahooFinance}) and to
     * symbols this provider can request.
     *
     * <p>This is the authority OpenFIGI cannot be: OpenFIGI knows every listing of an instrument,
     * Yahoo knows which of them <em>it</em> quotes. Searching an ISIN that Yahoo does not know
     * returns nothing rather than a fuzzy near-match ({@code enableFuzzyQuery=false}), so a miss
     * stays a miss.
     */
    @Override
    public List<SymbolMatch> searchSymbols(String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            SearchResponse response = webClient.get()
                .uri("/v1/finance/search?q={query}&quotesCount=6&newsCount=0&listsCount=0"
                    + "&enableFuzzyQuery=false", query)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(VERIFY_TIMEOUT)
                .block();

            if (response == null || response.quotes() == null) return List.of();

            return response.quotes().stream()
                .filter(q -> Boolean.TRUE.equals(q.isYahooFinance()))
                .filter(q -> supports(q.symbol()))
                .map(q -> new SymbolMatch(
                    q.symbol().toUpperCase(Locale.ROOT),
                    q.longname() != null ? q.longname() : q.shortname()))
                .toList();
        } catch (Exception ex) {
            log.debug("Yahoo symbol search failed for {}: {}", query, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Returns Yahoo's classification of the instrument ("ETF", "EQUITY",
     * "CRYPTOCURRENCY", "MUTUALFUND"...) read from the same unauthenticated
     * chart endpoint already used for prices. Empty if unavailable.
     */
    public Optional<String> getInstrumentType(String ticker) {
        if (!supports(ticker)) return Optional.empty();
        try {
            Meta meta = fetchMeta(ticker);
            if (meta == null) return Optional.empty();
            return Optional.ofNullable(meta.instrumentType()).filter(s -> !s.isBlank());
        } catch (Exception ex) {
            log.debug("Yahoo instrumentType fetch failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Apply FX conversion to a native-currency price. Returns null if the FX
     * rate cannot be fetched (caller treats that the same way as a missing
     * price — skip the snapshot rather than store a wrong value).
     */
    private BigDecimal applyFx(double rawPrice, String currency) {
        BigDecimal rate = getFxRateToEur(currency);
        if (rate == null) {
            log.warn("Skipping price {} in {}: FX rate unavailable", rawPrice, currency);
            return null;
        }
        return BigDecimal.valueOf(rawPrice).multiply(rate);
    }

    /**
     * Resolve the FX rate from `currency` to EUR. Cached for 15 minutes.
     * Returns BigDecimal.ONE when the price is already in EUR (or currency
     * is unknown — preserves the pre-fix behavior for cassé payloads).
     * Returns null when a real fetch fails — caller must handle.
     */
    BigDecimal getFxRateToEur(String currency) {
        if (currency == null || currency.isBlank() || "EUR".equalsIgnoreCase(currency)) {
            return BigDecimal.ONE;
        }

        // London pence: 1 GBp = 0.01 GBP. Yahoo returns the exact string "GBp"
        // (case-sensitive) for LSE-listed stocks like LLOY.L. GBX is the
        // alternative ISO-4217 code used by some feeds.
        if ("GBp".equals(currency) || "GBX".equalsIgnoreCase(currency)) {
            BigDecimal gbpRate = getFxRateToEur("GBP");
            if (gbpRate == null) return null;
            return gbpRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        }

        String upper = currency.toUpperCase(Locale.ROOT);
        CachedFx cached = fxCache.get(upper);
        if (cached != null && cached.isFresh()) {
            return cached.rate();
        }

        BigDecimal rate = fetchFxRate(upper);
        if (rate != null) {
            fxCache.put(upper, new CachedFx(rate, Instant.now()));
        }
        return rate;
    }

    BigDecimal fetchFxRate(String currency) {
        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{pair}?range=1d&interval=1d", currency + "EUR=X")
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return null;
            }
            var result = response.chart().result().get(0);
            if (result.meta() == null) return null;
            double rate = result.meta().regularMarketPrice();
            if (rate <= 0) return null;
            return BigDecimal.valueOf(rate);
        } catch (Exception ex) {
            log.debug("FX fetch failed for {}EUR=X: {}", currency, ex.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record YahooResponse(Chart chart) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chart(List<ChartResult> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChartResult(Meta meta, List<Long> timestamp, Indicators indicators) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Indicators(List<Quote> quote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Quote(List<Double> close) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(double regularMarketPrice, String currency, String instrumentType,
                String longName, String shortName) {}

    /** Yahoo {@code /v1/finance/search} response, narrowed to the quotes list. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchQuote> quotes) {}

    /**
     * One hit in a {@code /v1/finance/search} response.
     *
     * <p>{@code quoteType} tells an equity from an ETF or a currency pair (the resolver's asset
     * typing); {@code isYahooFinance} is Yahoo's own flag for "I index this one", which the symbol
     * catalog filters on. Both fields come from the same payload — Jackson binds by name.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchQuote(String symbol, String shortname, String longname, String quoteType,
                       Boolean isYahooFinance) {}

    private record CachedFx(BigDecimal rate, Instant cachedAt) {
        boolean isFresh() { return Instant.now().isBefore(cachedAt.plus(FX_CACHE_TTL)); }
    }

    /**
     * Fetch hourly prices for a stock/ETF ticker from Yahoo Finance over the last 24H.
     * Uses interval=1h for intraday granularity.
     */
    @Override
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        String ticker = yahooSymbol(asset);
        // The router gates on canPrice, but a direct caller does not: a non-symbol string (an
        // unresolved ISIN, a bond description) must never become a Yahoo request.
        if (!supports(ticker)) return Map.of();
        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{ticker}?range=1d&interval=1h", ticker)
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return Map.of();
            }

            var result = response.chart().result().get(0);
            if (result.timestamp() == null
                || result.indicators() == null
                || result.indicators().quote() == null
                || result.indicators().quote().isEmpty()
                || result.indicators().quote().get(0).close() == null) return Map.of();

            // Series use today's FX rate for all historical points; per-day FX
            // would multiply API calls 250× for marginal accuracy on a personal
            // finance app.
            BigDecimal fx = result.meta() != null
                ? getFxRateToEur(result.meta().currency())
                : BigDecimal.ONE;
            if (fx == null) {
                log.warn("Skipping intraday series for {}: FX rate unavailable for {}",
                        ticker, result.meta() != null ? result.meta().currency() : "null");
                return Map.of();
            }

            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                LocalDateTime dt = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneId.of("Europe/Paris")).toLocalDateTime();
                if (!dt.isBefore(from) && !dt.isAfter(to) && close > 0) {
                    prices.put(dt, BigDecimal.valueOf(close).multiply(fx).setScale(8, RoundingMode.HALF_UP));
                }
            }

            log.debug("Fetched {} intraday prices for {} from Yahoo", prices.size(), ticker);
            return prices;
        } catch (Exception ex) {
            log.warn("Yahoo intraday price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Fetch historical daily prices for a single ticker from Yahoo Finance.
     * Returns a map of date -> priceEur.
     */
    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(FinancialAsset asset, LocalDate from, LocalDate to) {
        String ticker = yahooSymbol(asset);
        if (!supports(ticker)) return Map.of();
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        String range = days <= 7 ? "5d" : days <= 30 ? "1mo" : days <= 90 ? "3mo" : days <= 365 ? "1y" : "5y";

        try {
            YahooResponse response = webClient.get()
                .uri("/v8/finance/chart/{ticker}?range={range}&interval=1d", ticker, range)
                .retrieve()
                .bodyToMono(YahooResponse.class)
                .timeout(Duration.ofSeconds(15))
                .block();

            if (response == null || response.chart() == null || response.chart().result() == null
                || response.chart().result().isEmpty()) {
                return Map.of();
            }

            var result = response.chart().result().get(0);
            if (result.timestamp() == null
                || result.indicators() == null
                || result.indicators().quote() == null
                || result.indicators().quote().isEmpty()
                || result.indicators().quote().get(0).close() == null) return Map.of();

            // Series use today's FX rate for all historical points; per-day FX
            // would multiply API calls 250× for marginal accuracy on a personal
            // finance app.
            BigDecimal fx = result.meta() != null
                ? getFxRateToEur(result.meta().currency())
                : BigDecimal.ONE;
            if (fx == null) {
                log.warn("Skipping historical series for {}: FX rate unavailable for {}",
                        ticker, result.meta() != null ? result.meta().currency() : "null");
                return Map.of();
            }

            Map<LocalDate, BigDecimal> prices = new HashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.isBefore(from) && !date.isAfter(to) && close > 0) {
                    prices.put(date, BigDecimal.valueOf(close).multiply(fx).setScale(8, RoundingMode.HALF_UP));
                }
            }

            log.debug("Fetched {} historical prices for {} from Yahoo", prices.size(), ticker);
            return prices;
        } catch (Exception ex) {
            log.warn("Yahoo historical price fetch failed for {}: {}", ticker, ex.getMessage());
            return Map.of();
        }
    }
}
