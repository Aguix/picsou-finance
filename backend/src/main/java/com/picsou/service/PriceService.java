package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    private final CoinGeckoPriceProvider coinGecko;
    private final YahooFinancePriceProvider yahoo;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final FinancialAssetRepository assetRepository;

    // Simple in-memory price cache: ticker → (price, cachedAt)
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public PriceService(CoinGeckoPriceProvider coinGecko, YahooFinancePriceProvider yahoo,
                        PriceSnapshotRepository priceSnapshotRepository,
                        FinancialAssetRepository assetRepository) {
        this.coinGecko = coinGecko;
        this.yahoo = yahoo;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.assetRepository = assetRepository;
    }

    /** A ticker the operator marked WORTHLESS (delisted): priced at a fixed zero, never fetched. */
    private boolean isWorthless(String upperTicker) {
        return assetRepository.findBySymbol(upperTicker).map(FinancialAsset::isWorthless).orElse(false);
    }

    /**
     * Returns EUR price for the given ticker.
     * Returns BigDecimal.ONE if ticker is "EUR" (no conversion needed).
     * Returns null if price unavailable.
     */
    public BigDecimal getPriceEur(String ticker) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return BigDecimal.ONE;
        }

        String upper = ticker.toUpperCase();

        // A ticker marked WORTHLESS is valued at a known zero — no cache, no provider call.
        if (isWorthless(upper)) return BigDecimal.ZERO;

        // Check cache
        CachedPrice cached = priceCache.get(upper);
        if (cached != null && !cached.isExpired()) {
            return cached.price();
        }

        // Fetch from appropriate provider
        Set<String> singleTicker = Set.of(upper);
        Map<String, BigDecimal> prices;

        if (coinGecko.supports(upper)) {
            prices = coinGecko.getPricesEur(singleTicker);
        } else {
            prices = yahoo.getPricesEur(singleTicker);
        }

        BigDecimal price = prices.get(upper);
        if (price != null) {
            priceCache.put(upper, new CachedPrice(price, Instant.now()));
            assetRepository.updateLastPrice(upper, price, Instant.now());
            return price;
        }

        return null;
    }

    /** Bulk fetch and refresh cache for all provided tickers. */
    public Map<String, BigDecimal> refreshPrices(Set<String> tickers) {
        if (tickers.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        Set<String> cryptoTickers = new HashSet<>();
        Set<String> stockTickers = new HashSet<>();
        Set<String> worthlessTickers = new HashSet<>();

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase();
            if ("EUR".equals(upper)) {
                result.put(upper, BigDecimal.ONE);
            } else if (isWorthless(upper)) {
                // Known-zero: prices the holding at 0 without hitting any provider, and (below)
                // without writing a phantom 0 snapshot into the price history.
                worthlessTickers.add(upper);
                result.put(upper, BigDecimal.ZERO);
            } else if (coinGecko.supports(upper)) {
                cryptoTickers.add(upper);
            } else {
                stockTickers.add(upper);
            }
        }

        if (!cryptoTickers.isEmpty()) {
            coinGecko.getPricesEur(cryptoTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                result.put(k, v);
            });
        }

        if (!stockTickers.isEmpty()) {
            yahoo.getPricesEur(stockTickers).forEach((k, v) -> {
                priceCache.put(k, new CachedPrice(v, Instant.now()));
                result.put(k, v);
            });
        }

        log.debug("Refreshed prices for {} tickers", result.size());

        // Persist daily price snapshots + the asset's last known price (restart-surviving cache)
        LocalDate today = LocalDate.now();
        for (var entry : result.entrySet()) {
            if ("EUR".equals(entry.getKey())) continue;
            if (worthlessTickers.contains(entry.getKey())) continue; // don't snapshot a fixed zero
            if (entry.getValue() == null) continue;
            assetRepository.updateLastPrice(entry.getKey(), entry.getValue(), Instant.now());
            Optional<PriceSnapshot> existing = priceSnapshotRepository.findByTickerAndDate(entry.getKey(), today);
            if (existing.isPresent()) {
                existing.get().setPriceEur(entry.getValue());
                priceSnapshotRepository.save(existing.get());
            } else {
                priceSnapshotRepository.save(PriceSnapshot.builder()
                    .ticker(entry.getKey())
                    .date(today)
                    .priceEur(entry.getValue())
                    .build());
            }
        }

        return result;
    }

    /** Convert an account's balance to EUR using its currency/ticker. */
    public BigDecimal toEur(BigDecimal balance, String currency, String ticker) {
        if (balance == null) return BigDecimal.ZERO;

        // Already in EUR
        if ("EUR".equalsIgnoreCase(currency) && (ticker == null || ticker.isBlank())) {
            return balance;
        }

        // Use ticker if available (more specific), else use currency
        String symbol = (ticker != null && !ticker.isBlank()) ? ticker : currency;
        BigDecimal price = getPriceEur(symbol);

        if (price == null) {
            log.warn("No price available for symbol: {}, returning raw balance", symbol);
            return balance;
        }

        return balance.multiply(price);
    }

    /**
     * Backfill historical prices for the given tickers from external APIs.
     * Fetches daily prices from the given start date and saves as PriceSnapshots.
     * Skips dates that already have a snapshot.
     */
    public int backfillHistoricalPrices(Set<String> tickers, LocalDate from) {
        Map<String, LocalDate> byTicker = new HashMap<>();
        for (String ticker : tickers) {
            byTicker.put(ticker.toUpperCase(), from);
        }
        return backfillHistoricalPrices(byTicker);
    }

    /**
     * Backfill each ticker from its own start date (per-coin anchoring), so a coin bought recently
     * isn't fetched from the whole portfolio's earliest date. Gap-aware and idempotent: only the
     * missing tail is fetched, and a coin already current is skipped entirely.
     */
    public int backfillHistoricalPrices(Map<String, LocalDate> firstDateByTicker) {
        LocalDate to = LocalDate.now();
        int saved = 0;
        List<String> noData = new ArrayList<>();
        List<String> upToDate = new ArrayList<>();

        for (Map.Entry<String, LocalDate> e : firstDateByTicker.entrySet()) {
            String upper = e.getKey().toUpperCase();
            if ("EUR".equals(upper)) continue;
            if (isWorthless(upper)) continue;   // fixed-zero: no history to fetch, no provider call
            LocalDate from = e.getValue();

            // Gap-aware: only fetch what's missing. If the latest stored snapshot already reaches
            // yesterday (today's price is the live path's job), the coin is current → fetch nothing.
            // Otherwise fetch just the missing tail. This makes a warm restart's PriceBackfillRunner
            // a no-op instead of re-downloading the whole window and burning the rate limit.
            LocalDate effectiveFrom = from;
            Optional<PriceSnapshot> latest = priceSnapshotRepository.findLatestByTickerBeforeOrOnDate(upper, to);
            if (latest.isPresent()) {
                LocalDate nextMissing = latest.get().getDate().plusDays(1);
                if (!nextMissing.isBefore(to)) {   // covered up to (at least) yesterday
                    upToDate.add(upper);
                    continue;
                }
                if (nextMissing.isAfter(from)) {
                    effectiveFrom = nextMissing;   // fetch only the gap since the last snapshot
                }
            }

            Map<LocalDate, BigDecimal> prices;
            if (coinGecko.supports(upper)) {
                // The provider absorbs the free tier's 429s with its circuit breaker, so this loop
                // just walks the tickers.
                prices = coinGecko.getHistoricalPricesEur(upper, effectiveFrom, to);
            } else {
                prices = yahoo.getHistoricalPricesEur(upper, effectiveFrom, to);
            }

            if (prices.isEmpty()) {
                noData.add(upper);
                continue;
            }

            int added = 0;
            for (var entry : prices.entrySet()) {
                if (priceSnapshotRepository.findByTickerAndDate(upper, entry.getKey()).isEmpty()) {
                    priceSnapshotRepository.save(PriceSnapshot.builder()
                        .ticker(upper)
                        .date(entry.getKey())
                        .priceEur(entry.getValue())
                        .build());
                    saved++;
                    added++;
                }
            }
            log.debug("Backfill {}: {} prices fetched from {}, {} new snapshots",
                upper, prices.size(), effectiveFrom, added);
        }

        // One summary line instead of one per ticker. A non-empty noData list on the free tier
        // usually means a rate-limit (429) rather than genuinely-missing history — surfaced at WARN
        // so it's actionable without the per-ticker flood.
        int attempted = (int) firstDateByTicker.keySet().stream()
            .filter(t -> !"EUR".equalsIgnoreCase(t)).count();
        int fetched = attempted - upToDate.size();
        if (noData.isEmpty()) {
            log.info("Historical backfill: {} new snapshots ({} tickers fetched, {} already up-to-date)",
                saved, fetched, upToDate.size());
        } else {
            log.warn("Historical backfill: {} new snapshots ({} fetched, {} up-to-date); "
                + "{}/{} returned no data (rate-limit or unmapped): {}",
                saved, fetched, upToDate.size(), noData.size(), attempted, noData);
        }

        return saved;
    }

    private record CachedPrice(BigDecimal price, Instant cachedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plusSeconds(CACHE_TTL_SECONDS));
        }
    }

    /** Drop the in-memory price cache. Used by PriceFxCleanupRunner. */
    public void clearPriceCache() {
        priceCache.clear();
    }

    /** Evict one ticker from the in-memory cache — used when its asset mapping changes. */
    public void evictFromCache(String ticker) {
        if (ticker != null) priceCache.remove(ticker.toUpperCase());
    }

    /**
     * Fetch intraday (hourly) prices for a ticker over the given time range.
     * Routes to CoinGecko for crypto, Yahoo Finance for stocks/ETFs.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return Map.of();
        }

        String upper = ticker.toUpperCase();

        if (coinGecko.supports(upper)) {
            return coinGecko.getIntradayPricesEur(upper, from, to);
        } else {
            return yahoo.getIntradayPricesEur(upper, from, to);
        }
    }
}
