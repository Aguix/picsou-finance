package com.picsou.service;

import com.picsou.model.AssetStatus;
import com.picsou.model.AssetType;
import com.picsou.model.FinancialAsset;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.FinancialAssetRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    private final PriceRouter priceRouter;
    private final PriceSnapshotRepository priceSnapshotRepository;
    private final FinancialAssetRepository assetRepository;

    // Simple in-memory price cache: ticker → (price, cachedAt)
    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    public PriceService(PriceRouter priceRouter,
                        PriceSnapshotRepository priceSnapshotRepository,
                        FinancialAssetRepository assetRepository) {
        this.priceRouter = priceRouter;
        this.priceSnapshotRepository = priceSnapshotRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Resolve every symbol in the set with a single batched read, instead of a {@code findBySymbol}
     * per ticker. {@link #refreshPrices} and {@link #backfillHistoricalPrices} each need the asset
     * both for the WORTHLESS check and for the eventual snapshot/price write — this lets both passes
     * share the one lookup rather than querying the same symbol twice.
     */
    private Map<String, FinancialAsset> assetsForSymbols(Set<String> upperSymbols) {
        if (upperSymbols.isEmpty()) return Map.of();
        return assetRepository.findBySymbolIn(upperSymbols).stream()
            .collect(Collectors.toMap(FinancialAsset::getSymbol, a -> a));
    }

    /**
     * Mint a bare PENDING/UNKNOWN passthrough row for a symbol not yet in the registry (a wallet's
     * first sync can price a coin before it's registered). Mirrors
     * {@link com.picsou.service.FinancialAssetService#getOrCreate} but stays on the repository to
     * avoid the FinancialAssetService ↔ PriceService dependency cycle. Only called for a symbol
     * already known absent from {@link #assetsForSymbols} — never re-queries before inserting.
     */
    private FinancialAsset mintAsset(String upperSymbol) {
        return assetRepository.save(FinancialAsset.builder()
            .symbol(upperSymbol)
            .type(AssetType.UNKNOWN)
            .status(AssetStatus.PENDING)
            .build());
    }

    /**
     * Returns EUR price for an asset, or {@code null} when there's no asset (account with no dedicated
     * holding) or the price is unavailable. The primary entry point: any caller that already holds a
     * {@link FinancialAsset} routes straight through here. {@link #getPriceEur(String)} stays for the
     * two sites that only ever have a bare ticker string (a bank-account currency code, or an MCP
     * tool's caller-supplied ticker argument).
     *
     * <p>Returns {@link BigDecimal#ONE} for EUR (no conversion) and {@link BigDecimal#ZERO} for a
     * WORTHLESS asset (fixed zero, never fetched).
     */
    public BigDecimal getPriceEur(FinancialAsset asset) {
        if (asset == null) return null;
        String upper = asset.getSymbol().toUpperCase();
        if ("EUR".equals(upper)) return BigDecimal.ONE;

        // A WORTHLESS asset is valued at a known zero — no cache, no provider call.
        if (asset.isWorthless()) return BigDecimal.ZERO;

        // Check cache
        CachedPrice cached = priceCache.get(upper);
        if (cached != null && !cached.isExpired()) {
            return cached.price();
        }

        // Fetch from whichever provider the router routes this asset to.
        BigDecimal price = priceRouter.getPricesEur(List.of(asset)).get(upper);
        if (price != null) {
            priceCache.put(upper, new CachedPrice(price, Instant.now()));
            // Opportunistic persistence only: callers like DashboardService run in a read-only
            // transaction the repository @Transactional would join, and Postgres rejects the
            // UPDATE there. The write path (refreshPrices / scheduler) persists it anyway.
            if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                assetRepository.updateLastPrice(upper, price, Instant.now());
            }
            return price;
        }
        return null;
    }

    /**
     * Returns EUR price for a bare ticker string — the seam for the two callers with no asset in hand
     * (a bank-account currency code, an MCP tool's ticker argument). Resolves the symbol to its
     * registered asset, or a transient (non-persisted) one carrying just the symbol when unregistered,
     * then prices it exactly like {@link #getPriceEur(FinancialAsset)}. Returns {@link BigDecimal#ONE}
     * for EUR/blank, {@code null} when unavailable.
     */
    public BigDecimal getPriceEur(String ticker) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return BigDecimal.ONE;
        }
        String upper = ticker.toUpperCase();
        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        return getPriceEur(asset);
    }

    /** Bulk fetch and refresh cache for all provided tickers. */
    public Map<String, BigDecimal> refreshPrices(Set<String> tickers) {
        if (tickers.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        Set<String> toFetch = new HashSet<>();
        Set<String> worthlessTickers = new HashSet<>();
        Set<String> nonEurTickers = new HashSet<>();

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase();
            if ("EUR".equals(upper)) {
                result.put(upper, BigDecimal.ONE);
            } else {
                nonEurTickers.add(upper);
            }
        }

        // One batched read resolves every ticker's asset row up front; reused below for the
        // WORTHLESS check and the snapshot write, instead of a findBySymbol per ticker in each pass.
        Map<String, FinancialAsset> assets = assetsForSymbols(nonEurTickers);

        for (String upper : nonEurTickers) {
            FinancialAsset asset = assets.get(upper);
            if (asset != null && asset.isWorthless()) {
                // Known-zero: prices the holding at 0 without hitting any provider, and (below)
                // without writing a phantom 0 snapshot into the price history.
                worthlessTickers.add(upper);
                result.put(upper, BigDecimal.ZERO);
            } else {
                toFetch.add(upper);
            }
        }

        // The router partitions the set across providers (crypto → CoinGecko, else Yahoo) and batches
        // each provider into a single call.
        if (!toFetch.isEmpty()) {
            // Hand the router the assets themselves (already loaded above); a symbol with no registry
            // row rides a transient asset so it still routes to Yahoo verbatim, as before.
            List<FinancialAsset> toFetchAssets = toFetch.stream()
                .map(upper -> assets.getOrDefault(upper, FinancialAsset.builder().symbol(upper).build()))
                .toList();
            priceRouter.getPricesEur(toFetchAssets).forEach((k, v) -> {
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
            FinancialAsset asset = assets.computeIfAbsent(entry.getKey(), this::mintAsset);
            assetRepository.updateLastPrice(entry.getKey(), entry.getValue(), Instant.now());
            Optional<PriceSnapshot> existing = priceSnapshotRepository.findByAssetIdAndDate(asset.getId(), today);
            if (existing.isPresent()) {
                existing.get().setPriceEur(entry.getValue());
                priceSnapshotRepository.save(existing.get());
            } else {
                priceSnapshotRepository.save(PriceSnapshot.builder()
                    .asset(asset)
                    .date(today)
                    .priceEur(entry.getValue())
                    .build());
            }
        }

        return result;
    }

    /** Convert an account's balance to EUR using its currency, or its asset's price when it has one. */
    public BigDecimal toEur(BigDecimal balance, String currency, FinancialAsset asset) {
        if (balance == null) return BigDecimal.ZERO;

        // Already in EUR
        if ("EUR".equalsIgnoreCase(currency) && asset == null) {
            return balance;
        }

        // Use the asset if there is one (more specific — a single-asset account), else the currency.
        BigDecimal price = asset != null ? getPriceEur(asset) : getPriceEur(currency);

        if (price == null) {
            log.warn("No price available for {}, returning raw balance", asset != null ? asset.getSymbol() : currency);
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

        // One batched read up front (see refreshPrices) instead of isWorthless + assetForSnapshot
        // separately querying the same symbol back to back for every ticker in the loop below.
        Set<String> upperTickers = firstDateByTicker.keySet().stream()
            .map(String::toUpperCase)
            .filter(t -> !"EUR".equals(t))
            .collect(Collectors.toSet());
        Map<String, FinancialAsset> assets = assetsForSymbols(upperTickers);

        for (Map.Entry<String, LocalDate> e : firstDateByTicker.entrySet()) {
            String upper = e.getKey().toUpperCase();
            if ("EUR".equals(upper)) continue;
            FinancialAsset asset = assets.computeIfAbsent(upper, this::mintAsset);
            if (asset.isWorthless()) continue;   // fixed-zero: no history to fetch, no provider call
            LocalDate from = e.getValue();

            // Gap-aware: only fetch what's missing. If the latest stored snapshot already reaches
            // yesterday (today's price is the live path's job), the coin is current → fetch nothing.
            // Otherwise fetch just the missing tail. This makes a warm restart's PriceBackfillRunner
            // a no-op instead of re-downloading the whole window and burning the rate limit.
            LocalDate effectiveFrom = from;
            Optional<PriceSnapshot> latest = priceSnapshotRepository.findLatestByAssetIdBeforeOrOnDate(asset.getId(), to);
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

            // The router picks the history-capable provider for this ticker (CoinGecko's breaker
            // absorbs the free tier's 429s), so this loop just walks the tickers.
            Map<LocalDate, BigDecimal> prices = priceRouter.getHistoricalPricesEur(asset, effectiveFrom, to);

            if (prices.isEmpty()) {
                noData.add(upper);
                continue;
            }

            int added = 0;
            for (var entry : prices.entrySet()) {
                if (priceSnapshotRepository.findByAssetIdAndDate(asset.getId(), entry.getKey()).isEmpty()) {
                    priceSnapshotRepository.save(PriceSnapshot.builder()
                        .asset(asset)
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

    /**
     * Daily snapshots for a symbol over {@code [from, to]} — resolves the symbol to its asset once,
     * then queries the history by asset id. Empty when the symbol isn't registered.
     */
    public List<PriceSnapshot> priceHistory(String ticker, LocalDate from, LocalDate to) {
        if (ticker == null || ticker.isBlank()) return List.of();
        return assetRepository.findBySymbol(ticker.toUpperCase())
            .map(a -> priceSnapshotRepository.findByAssetIdInAndDateBetween(List.of(a.getId()), from, to))
            .orElseGet(List::of);
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
        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> FinancialAsset.builder().symbol(upper).build());
        return priceRouter.getIntradayPricesEur(asset, from, to);
    }
}
