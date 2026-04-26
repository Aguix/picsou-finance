package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.port.PriceProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.stream.Collectors;

/**
 * Fetches stock/ETF prices from Yahoo Finance (unofficial, no API key needed).
 * Used for PEA/Compte-Titres positions with tickers like "IWDA.AS", "MC.PA", etc.
 *
 * Note: This is an unofficial API. For production use consider Alpha Vantage or similar.
 */
@Component
public class YahooFinancePriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(YahooFinancePriceProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Tickers that are handled by CoinGecko — we skip those
    private static final Set<String> CRYPTO_TICKERS = Set.of(
        "BTC", "ETH", "SOL", "BNB", "ADA", "XRP", "DOGE", "DOT", "MATIC", "AVAX"
    );

    private final WebClient webClient;

    public YahooFinancePriceProvider() {
        this.webClient = WebClient.builder()
            .baseUrl("https://query1.finance.yahoo.com")
            .defaultHeader("Accept", "application/json")
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build();
    }

    @Override
    public boolean supports(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }
        String upper = ticker.toUpperCase();

        // Don't support crypto tickers
        if (CRYPTO_TICKERS.contains(upper)) {
            return false;
        }

        // Don't support plain ISIN codes (12-character alphanumeric starting with 2-letter country code)
        // ISIN format: AA########X (2 letters, 9 digits, 1 check digit)
        if (upper.length() == 12 && upper.matches("[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]")) {
            log.debug("Rejecting unsupported ISIN: {}", ticker);
            return false;
        }

        return true;
    }

    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Set<String> supported = tickers.stream()
            .filter(this::supports)
            .collect(Collectors.toSet());

        if (supported.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        // Yahoo Finance is fetched per-ticker (no batch endpoint for EUR conversion)
        for (String ticker : supported) {
            try {
                BigDecimal price = fetchSinglePrice(ticker);
                if (price != null) result.put(ticker.toUpperCase(), price);
            } catch (Exception ex) {
                log.warn("Yahoo Finance price fetch failed for {}: {}", ticker, ex.getMessage());
            }
        }

        return result;
    }

    private BigDecimal fetchSinglePrice(String ticker) {
        YahooResponse response = webClient.get()
            .uri("/v8/finance/chart/{ticker}?range=1d&interval=1d", ticker)
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

        double price = result.meta().regularMarketPrice();
        if (price <= 0) return null;

        // If ticker already includes EUR currency (e.g. .PA, .AS), price is in EUR
        // If it's a USD-denominated asset, we'd need FX conversion (simplified: use as-is for now)
        return BigDecimal.valueOf(price);
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
    record Meta(double regularMarketPrice, String currency) {}

    /**
     * Fetch hourly prices for a stock/ETF ticker from Yahoo Finance over the last 24H.
     * Uses interval=1h for intraday granularity.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
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

            Map<LocalDateTime, BigDecimal> prices = new LinkedHashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                LocalDateTime dt = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneId.of("Europe/Paris")).toLocalDateTime();
                if (!dt.isBefore(from) && !dt.isAfter(to) && close > 0) {
                    prices.put(dt, BigDecimal.valueOf(close).setScale(8, RoundingMode.HALF_UP));
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
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
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

            Map<LocalDate, BigDecimal> prices = new HashMap<>();
            List<Long> timestamps = result.timestamp();
            List<Double> closes = result.indicators().quote().get(0).close();

            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                Double close = closes.get(i);
                if (close == null) continue;
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i))
                    .atZone(ZoneOffset.UTC).toLocalDate();
                if (!date.isBefore(from) && !date.isAfter(to) && close > 0) {
                    prices.put(date, BigDecimal.valueOf(close).setScale(8, RoundingMode.HALF_UP));
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
