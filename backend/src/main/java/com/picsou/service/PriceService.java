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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final long CACHE_TTL_SECONDS = 900; // 15 minutes

    /**
     * How stale a {@code price_snapshot} row may be before it stops being an acceptable answer.
     * A day-old crypto price is a slightly wrong number; a month-old one is fiction, and would be
     * worse than the honest "unknown" it replaces.
     */
    private static final int MAX_FALLBACK_AGE_DAYS = 7;

    /**
     * The longest hole a recorded history may contain before the backfill considers it incomplete.
     * Sized for closed markets, not for outages: a weekend is two days, and an Easter or Christmas
     * week can reach five.
     */
    private static final int MAX_HISTORY_GAP_DAYS = 7;

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
     * A throwaway asset for a symbol with no registry row, used by the bare-ticker seams below
     * (currency code, MCP ticker argument, an unregistered symbol in a bulk refresh). Carries
     * {@code yahoo_symbol = symbol} so it still routes to Yahoo verbatim: unlike a registry row —
     * which is deliberately left un-priceable by Yahoo until its {@code yahoo_symbol} is resolved,
     * so an unresolved coin no longer wastes a Yahoo call — these seams take a ticker the caller has
     * explicitly handed us to price, exactly as before {@code canPrice} was gated on {@code
     * yahoo_symbol}. A bare fiat code still gets no Yahoo quote and returns null, as it always did.
     */
    private static FinancialAsset transientAsset(String upperSymbol) {
        return FinancialAsset.builder().symbol(upperSymbol).yahooSymbol(upperSymbol).build();
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
            // A transient asset (bare currency code, unregistered MCP ticker) has no row — no id,
            // nothing to persist to.
            if (asset.getId() != null
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                assetRepository.updateLastPrice(asset.getId(), price, Instant.now());
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
            .orElseGet(() -> transientAsset(upper));
        return getPriceEur(asset);
    }

    /**
     * A EUR price together with how current it is.
     *
     * @param price the EUR price, never null
     * @param asOf  the day the price is for — today for a live quote, the snapshot's date for a
     *              fallback
     * @param live  true when the number came from an aggregator (or its 15-minute cache), false
     *              when it is the last price we ever managed to record
     */
    public record Quote(BigDecimal price, LocalDate asOf, boolean live) {}

    /** {@link #getPriceEur(String)} with the freshness attached. Null when nothing resolves. */
    public Quote getQuote(String ticker) {
        return singleQuote(ticker, false);
    }

    /** {@link #getQuote} for a ticker known to be crypto — see {@link #refreshCryptoPrices}. */
    public Quote getCryptoQuote(String ticker) {
        return singleQuote(ticker, true);
    }

    /**
     * Like {@link #getPriceEur(String)}, for a ticker known to be crypto: an unregistered symbol
     * returns {@code null} instead of being valued at the share price of the equity trading under
     * the same name. See {@link #refreshCryptoPrices}.
     */
    public BigDecimal getCryptoPriceEur(String ticker) {
        Quote quote = getCryptoQuote(ticker);
        return quote == null ? null : quote.price();
    }

    /** Resolve a whole set at once — one batched router pass instead of one per ticker. */
    public Map<String, Quote> getQuotes(Set<String> tickers) {
        return resolve(tickers, false);
    }

    /** {@link #getQuotes} for tickers known to be crypto (see {@link #refreshCryptoPrices}). */
    public Map<String, Quote> getCryptoQuotes(Set<String> tickers) {
        return resolve(tickers, true);
    }

    private Quote singleQuote(String ticker, boolean cryptoOnly) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return new Quote(BigDecimal.ONE, LocalDate.now(), true);
        }
        return resolve(Set.of(ticker), cryptoOnly).get(ticker.toUpperCase(Locale.ROOT));
    }

    /**
     * Resolves EUR prices for {@code tickers}, in order: the in-memory cache, then one batched
     * router pass for whatever is left, then the last recorded {@code price_snapshot}.
     *
     * <p>The third step is what keeps a rate-limited morning from blanking the interface. Every
     * priced ticker already has a daily row in {@code price_snapshot}, so an aggregator outage
     * degrades the number's <em>age</em> rather than its existence — and the callers that used to
     * drop an unpriced asset from a total (and from its daily snapshot) get something to value it
     * with. A ticker absent from that table too — a coin whose mapping is still PENDING, a currency
     * we never priced — still resolves to nothing, which callers must keep handling.
     *
     * <p>{@code cryptoOnly} keeps a coin away from Yahoo: a symbol with no registry row is left
     * unpriced rather than riding a {@link #transientAsset}, which fabricates a {@code yahoo_symbol}
     * and would quote the equity trading under the same name. A <em>registered</em> coin needs no
     * such guard — Yahoo refuses any asset whose {@code yahoo_symbol} is unresolved — and neither
     * does the fallback, which reads history by asset id rather than by symbol.
     */
    private Map<String, Quote> resolve(Set<String> tickers, boolean cryptoOnly) {
        if (tickers.isEmpty()) return Map.of();

        LocalDate today = LocalDate.now();
        Map<String, Quote> resolved = new HashMap<>();
        Set<String> pending = new TreeSet<>();
        Set<String> missCached = new TreeSet<>();

        for (String ticker : tickers) {
            if (ticker == null || ticker.isBlank()) continue;
            String upper = ticker.toUpperCase(Locale.ROOT);

            if ("EUR".equals(upper)) {
                resolved.put(upper, new Quote(BigDecimal.ONE, today, true));
                continue;
            }
            CachedPrice cached = priceCache.get(upper);
            if (cached != null && !cached.isExpired()) {
                if (cached.price() != null) {
                    resolved.put(upper, new Quote(cached.price(), today, true));
                    continue;
                }
                // A cached entry with no price is a remembered miss: the aggregator was asked
                // recently and had nothing. Skip the network and go straight to the fallback.
                missCached.add(upper);
            }
            pending.add(upper);
        }

        if (pending.isEmpty()) return resolved;

        // One batched read for the whole set, as everywhere else in this service.
        Map<String, FinancialAsset> assets = assetsForSymbols(pending);

        List<FinancialAsset> fetchable = new ArrayList<>();
        for (String upper : List.copyOf(pending)) {
            FinancialAsset asset = assets.get(upper);
            if (asset != null && asset.isWorthless()) {
                // Known-zero, not a missing price: no aggregator call, and no fallback either.
                resolved.put(upper, new Quote(BigDecimal.ZERO, today, true));
                pending.remove(upper);
                continue;
            }
            if (missCached.contains(upper)) continue;
            if (asset == null) {
                if (cryptoOnly) {
                    log.warn("No registry entry for crypto ticker {} -- leaving it unpriced rather "
                        + "than valuing it as the stock trading under that symbol", upper);
                    continue;
                }
                asset = transientAsset(upper);
            }
            fetchable.add(asset);
        }

        if (!fetchable.isEmpty()) {
            Map<String, BigDecimal> live = priceRouter.getPricesEur(fetchable);
            Instant fetchedAt = Instant.now();
            for (FinancialAsset asset : fetchable) {
                String upper = asset.getSymbol().toUpperCase(Locale.ROOT);
                BigDecimal price = live.get(upper);
                // Misses are cached too: that null entry *is* the negative cache, and it expires on
                // its own TTL instead of being re-asked on every page render.
                priceCache.put(upper, new CachedPrice(price, fetchedAt));
                if (price != null) resolved.put(upper, new Quote(price, today, true));
            }
        }

        Set<String> unresolved = pending.stream()
            .filter(t -> !resolved.containsKey(t))
            .collect(Collectors.toCollection(TreeSet::new));
        if (unresolved.isEmpty()) return resolved;

        Map<String, PriceSnapshot> lastKnown = lastKnownPrices(unresolved, assets, today);
        lastKnown.forEach((ticker, snapshot) ->
            resolved.put(ticker, new Quote(snapshot.getPriceEur(), snapshot.getDate(), false)));

        // Only trace the attempts that actually reached out: the negative cache means the same
        // outage would otherwise log on every page render for as long as it lasts.
        Set<String> tried = unresolved.stream()
            .filter(t -> !missCached.contains(t))
            .collect(Collectors.toCollection(TreeSet::new));
        if (!tried.isEmpty()) {
            Set<String> stale = tried.stream().filter(lastKnown::containsKey)
                .collect(Collectors.toCollection(TreeSet::new));
            Set<String> unknown = tried.stream().filter(t -> !lastKnown.containsKey(t))
                .collect(Collectors.toCollection(TreeSet::new));
            if (!stale.isEmpty()) {
                log.warn("No live price for {} -- falling back to the last recorded one ({})",
                    stale, stale.stream().map(t -> t + "=" + lastKnown.get(t).getDate()).toList());
            }
            if (!unknown.isEmpty()) {
                log.warn("No price at all for {} -- not from any aggregator, and nothing recorded "
                    + "in the last {} days", unknown, MAX_FALLBACK_AGE_DAYS);
            }
        }

        return resolved;
    }

    /**
     * The most recent {@code price_snapshot} per symbol within {@link #MAX_FALLBACK_AGE_DAYS}, in
     * one query. Looked up by asset id — the snapshot table's own key since V85 — then mapped back
     * to the symbol the caller asked with; a symbol with no registry row has no history either, so
     * it simply doesn't appear. The reduction is order-independent on purpose: relying on the
     * query's {@code ORDER BY} would make the fallback silently pick the wrong row if that clause
     * were ever edited.
     */
    private Map<String, PriceSnapshot> lastKnownPrices(
        Set<String> symbols, Map<String, FinancialAsset> assets, LocalDate today) {

        Map<Long, String> symbolByAssetId = new HashMap<>();
        for (String symbol : symbols) {
            FinancialAsset asset = assets.get(symbol);
            if (asset != null && asset.getId() != null) symbolByAssetId.put(asset.getId(), symbol);
        }
        if (symbolByAssetId.isEmpty()) return Map.of();

        Map<String, PriceSnapshot> latest = new HashMap<>();
        for (PriceSnapshot snapshot : priceSnapshotRepository.findRecentByAssetIds(
            symbolByAssetId.keySet(), today.minusDays(MAX_FALLBACK_AGE_DAYS), today)) {
            String symbol = symbolByAssetId.get(snapshot.getAsset().getId());
            if (symbol == null) continue;
            latest.merge(symbol, snapshot, (a, b) -> a.getDate().isAfter(b.getDate()) ? a : b);
        }
        return latest;
    }

    /**
     * Bulk fetch and refresh cache for tickers known to be crypto.
     *
     * <p>Same as {@link #refreshPrices} except that a symbol with no registry row is left
     * <em>unpriced</em> instead of being handed to Yahoo Finance verbatim. That passthrough is
     * right for a caller-supplied ticker but wrong for an exchange or wallet: dozens of coins share
     * a symbol with a listed equity (SUI, ATOM, TIA…), so an unregistered coin would be valued at
     * the share price of an unrelated company and written into the balance and its daily snapshot,
     * with nothing in the logs to reveal it. Callers already treat a missing price as "not valued
     * this cycle".
     */
    public Map<String, BigDecimal> refreshCryptoPrices(Set<String> tickers) {
        return refreshPrices(tickers, true);
    }

    /**
     * {@link #refreshCryptoPrices} with the last-known-price fallback applied to whatever no
     * aggregator could deliver, and with each result's freshness attached.
     *
     * <p>For sync paths, which both value an account <em>and</em> write that valuation into its
     * daily {@code BalanceSnapshot}. Dropping an asset there does not merely blank a cell: it
     * shrinks a number that is then engraved in the net-worth history, where nothing later corrects
     * it. A day-old price is a far better record of the day than a hole.
     *
     * <p>Only live prices reach {@code price_snapshot} (that write lives in {@link #refreshPrices});
     * re-recording a fallback under today's date would launder a stale price into a fresh-looking
     * one and let the fallback drift forward forever.
     */
    public Map<String, Quote> refreshCryptoQuotes(Set<String> tickers) {
        Map<String, BigDecimal> live = refreshPrices(tickers, true);
        LocalDate today = LocalDate.now();

        Map<String, Quote> quotes = new HashMap<>();
        live.forEach((ticker, price) -> {
            if (price != null) quotes.put(ticker, new Quote(price, today, true));
        });

        Set<String> unresolved = tickers.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.toUpperCase(Locale.ROOT))
            .filter(t -> !quotes.containsKey(t))
            .collect(Collectors.toCollection(TreeSet::new));
        if (unresolved.isEmpty()) return quotes;

        Map<String, PriceSnapshot> lastKnown =
            lastKnownPrices(unresolved, assetsForSymbols(unresolved), today);
        lastKnown.forEach((ticker, snapshot) ->
            quotes.put(ticker, new Quote(snapshot.getPriceEur(), snapshot.getDate(), false)));

        if (!lastKnown.isEmpty()) {
            log.warn("No live price for {} -- valuing from the last recorded price instead ({})",
                lastKnown.keySet(),
                lastKnown.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().getDate()).toList());
        }
        return quotes;
    }

    /** Bulk fetch and refresh cache for all provided tickers. */
    public Map<String, BigDecimal> refreshPrices(Set<String> tickers) {
        return refreshPrices(tickers, false);
    }

    private Map<String, BigDecimal> refreshPrices(Set<String> tickers, boolean cryptoOnly) {
        if (tickers.isEmpty()) return Map.of();

        Map<String, BigDecimal> result = new HashMap<>();

        Set<String> toFetch = new HashSet<>();
        Set<String> worthlessTickers = new HashSet<>();
        Set<String> nonEurTickers = new HashSet<>();

        for (String ticker : tickers) {
            String upper = ticker.toUpperCase(Locale.ROOT);
            if ("EUR".equals(upper)) {
                result.put(upper, BigDecimal.ONE);
                continue;
            }
            // Serve a still-fresh cache entry rather than re-fetching it: GET /prices is polled by
            // the frontend on an interval, so bypassing the TTL here would turn every open
            // dashboard tab into a steady stream of aggregator calls. A cached miss (null price)
            // counts too — it is the negative cache, and re-asking is exactly what it prevents.
            CachedPrice cached = priceCache.get(upper);
            if (cached != null && !cached.isExpired()) {
                if (cached.price() != null) result.put(upper, cached.price());
                continue;
            }
            nonEurTickers.add(upper);
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

        // The router partitions the set across aggregators by which one holds a ref for the asset,
        // and batches each aggregator into a single call.
        Map<String, BigDecimal> fetched = new HashMap<>();
        if (!toFetch.isEmpty()) {
            // Hand the router the assets themselves (already loaded above); a symbol with no
            // registry row rides a transient asset so it still routes to Yahoo verbatim, as before
            // — except under cryptoOnly, where that passthrough is exactly what must not happen.
            List<FinancialAsset> toFetchAssets = new ArrayList<>();
            for (String upper : toFetch) {
                FinancialAsset asset = assets.get(upper);
                if (asset == null) {
                    if (cryptoOnly) {
                        log.warn("No registry entry for crypto ticker {} -- leaving it unpriced "
                            + "rather than valuing it as the stock trading under that symbol", upper);
                        continue;
                    }
                    asset = transientAsset(upper);
                }
                toFetchAssets.add(asset);
            }
            if (!toFetchAssets.isEmpty()) {
                priceRouter.getPricesEur(toFetchAssets).forEach((k, v) -> {
                    priceCache.put(k, new CachedPrice(v, Instant.now()));
                    fetched.put(k, v);
                });
            }
        }

        result.putAll(fetched);
        log.debug("Refreshed prices: {} fetched, {} served from cache", fetched.size(), result.size() - fetched.size());

        // Persist daily price snapshots + the asset's last known price (restart-surviving cache)
        LocalDate today = LocalDate.now();
        for (var entry : fetched.entrySet()) {
            if ("EUR".equals(entry.getKey())) continue;
            if (worthlessTickers.contains(entry.getKey())) continue; // don't snapshot a fixed zero
            if (entry.getValue() == null) continue;
            FinancialAsset asset = assets.computeIfAbsent(entry.getKey(), this::mintAsset);
            assetRepository.updateLastPrice(asset.getId(), entry.getValue(), Instant.now());
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
            // The returned number is now WRONG, not merely missing: an unconverted USD or GBP
            // balance flows into net worth and its snapshots as though it were EUR. ERROR, because
            // unlike a missing crypto price (which the wallet sync refuses to record) this one
            // silently corrupts a figure the user reads as authoritative.
            //
            // Deliberately NOT thrown, and deliberately still returning the raw balance: toEur backs
            // liveBalanceEur, the dashboard and the history charts, so throwing would 500 all of
            // them on one missing FX rate, and substituting zero would understate net worth just as
            // silently. Changing the number either way shifts every user's totals; making the
            // failure loud does not.
            log.error("No EUR rate for {} -- returning the balance UNCONVERTED, so any total "
                + "including it is wrong until the rate is available",
                asset != null ? asset.getSymbol() : currency);
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
                // Covered up to (at least) yesterday — but only skip when the range behind it has
                // no hole either: reading the newest row alone declares an instance that was off
                // for three months up to date forever, since the tail fills in on the first run
                // back and the hole never gets asked for again.
                if (!nextMissing.isBefore(to) && alreadyCovered(asset, from, to)) {
                    upToDate.add(upper);
                    continue;
                }
                if (nextMissing.isAfter(from) && alreadyCovered(asset, from, latest.get().getDate())) {
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

    /**
     * Whether {@code ticker} already has continuous history over the requested range, in which
     * case the backfill has nothing to add and the provider call is pure waste.
     *
     * <p>This runs at every boot, once per held ticker, against providers whose free tiers count
     * requests per IP — and the previous version re-requested twelve months of history for tickers
     * that already had all of it, only to discard every row as a duplicate. On this instance that
     * burned the whole rate-limit budget seconds after startup and left the price cache (which
     * does not survive a restart) with nothing to fill itself from.
     *
     * <p>It scans the whole range rather than probing its two ends. Checking the edges alone
     * declares a ticker covered as soon as it has an old row and a recent one, so an instance that
     * was off for three months — leaving a hole with history on both sides of it — would skip that
     * ticker at every boot and never fill the hole, while {@code HistoryService} flat-lines the
     * chart across it at the last pre-outage price. One query returns the range (at most ~370 rows,
     * one per day by {@code uk_price_snapshot_ticker_date}) and the gaps are measured in memory.
     *
     * <p>Gaps are tolerated up to {@link #MAX_HISTORY_GAP_DAYS} because markets close: a weekend is
     * a two-day hole in every equity series, and an Easter or Christmas week can stretch that to
     * five. A ticker whose history simply starts late — an asset younger than the range — is
     * reported uncovered and re-requested each boot, which is the pre-existing behaviour: we
     * cannot tell "the provider has nothing before this date" from "we never fetched it" without
     * asking.
     */
    private boolean alreadyCovered(FinancialAsset asset, LocalDate from, LocalDate to) {
        if (asset.getId() == null) return false;
        List<PriceSnapshot> rows =
            priceSnapshotRepository.findByAssetIdInAndDateBetween(Set.of(asset.getId()), from, to);
        if (rows.isEmpty()) return false;

        // The query orders by date ascending; walk from the range start so a missing head, a
        // missing tail and an interior hole are all the same check.
        LocalDate cursor = from;
        for (PriceSnapshot row : rows) {
            if (ChronoUnit.DAYS.between(cursor, row.getDate()) > MAX_HISTORY_GAP_DAYS) return false;
            cursor = row.getDate();
        }
        return ChronoUnit.DAYS.between(cursor, to) <= MAX_HISTORY_GAP_DAYS;
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
     * Intraday (hourly) prices for an asset over the given time range — the primary entry point:
     * a caller already holding the {@link FinancialAsset} (a holding's) hands it straight through,
     * no symbol round-trip back to the registry.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(FinancialAsset asset, LocalDateTime from, LocalDateTime to) {
        if (asset == null || "EUR".equalsIgnoreCase(asset.getSymbol())) {
            return Map.of();
        }
        return priceRouter.getIntradayPricesEur(asset, from, to);
    }

    /**
     * Intraday prices for a bare ticker string — the seam for callers with no asset in hand (the
     * REST endpoint's ticker parameter). Resolves the symbol once, or rides a transient asset when
     * unregistered, exactly like {@link #getPriceEur(String)}.
     */
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (ticker == null || ticker.isBlank() || "EUR".equalsIgnoreCase(ticker)) {
            return Map.of();
        }
        String upper = ticker.toUpperCase();
        FinancialAsset asset = assetRepository.findBySymbol(upper)
            .orElseGet(() -> transientAsset(upper));
        return getIntradayPricesEur(asset, from, to);
    }
}
