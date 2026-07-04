# Feature: Price Service

> Last updated: 2026-07-04

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices are fetched from two free providers: CoinGecko for crypto and Yahoo Finance for stocks/ETFs. A 15-minute in-memory cache prevents hammering external APIs. The scheduler refreshes prices hourly for all accounts with tickers.

## How it works

### Provider routing

`PriceService.getPriceEur(ticker)` routes each ticker to the appropriate provider:

- **CoinGecko** (`CoinGeckoPriceProvider`): Handles crypto tickers. Ticker → CoinGecko coin-id
  resolution is **dynamic** — it reads the persistent `coin_mapping` table (see below) rather than a
  hardcoded list, so any coin can be priced once it has been resolved. Uses the `/simple/price`
  endpoint with `vs_currencies=eur`; batch queries send every mapped ticker in one request.
- **Yahoo Finance** (`YahooFinancePriceProvider`): Handles everything CoinGecko does not -- stocks, ETFs, indices. Uses the unofficial `/v8/finance/chart/{ticker}` endpoint. Fetched per-ticker (no batch). Tickers like `IWDA.AS`, `MC.PA` are already EUR-denominated; foreign-currency tickers (USD/JPY/GBp/...) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Both providers implement `PriceProviderPort` with `supports(ticker)` and `getPricesEur(tickers)`.
For CoinGecko, `supports(ticker)` is true once the ticker has a `coin_mapping` row.

### Ticker → coin-id resolution (`CoinMappingService`)

The old `CoinGeckoPriceProvider` carried a hardcoded `TICKER_TO_ID` map of ~20 coins; adding a coin
meant editing code. That map is gone. `coin_mapping` (ticker → CoinGecko id, migration `V39`) is now
a persistent cache filled on demand, and `CoinMappingService.resolve(ticker)` decides its rows:

1. **DB first** — a cached mapping wins immediately.
2. **CoinGecko `/search`** for coins whose symbol equals the ticker.
3. **Single / dominant match** by market-cap rank auto-resolves and is persisted as `AUTO`: exactly
   one symbol match, or a top-ranked one that outranks the runner-up by a safety factor (see
   `DOMINANCE_FACTOR`). A tie between comparable coins is **not** guessed — left unresolved for the
   operator to disambiguate later by supplying the CoinGecko link (persisted as `USER`).

Resolution runs at **crypto-import time** (`CryptoImportService`), where the context guarantees the
ticker is a crypto — so a symbol shared with a stock ticker can't be mis-routed on the general price
path. An optional Demo API key (`app.coingecko.demo-api-key`, sent as the `x-cg-demo-api-key` header)
lifts the rate limit; absent, calls fall back to the anonymous free tier.

`CoinGeckoPriceProvider` also exposes `getLogoUrls(tickers)`, used by `CryptoStatsService` to attach
each coin's real icon (`AssetStat.logoUrl`) to the crypto stats response. It batches every requested
ticker into one `/coins/markets` call per invocation (same batching shape as `getPricesEur`'s
`/simple/price`), and caches resolved URLs in an unbounded, no-TTL `ConcurrentHashMap` — unlike
prices, logos essentially never change, so there's no reason to re-fetch them every 15 minutes. This
isn't on `PriceProviderPort` since it's CoinGecko/crypto-only (Yahoo-sourced stock tickers have no
equivalent), matching how `getHistoricalPricesEur`/`getIntradayPricesEur` are also CoinGecko-specific
public methods rather than port methods.

### Caching

`PriceService` maintains a `ConcurrentHashMap<String, CachedPrice>` where the key is the uppercase ticker. Each entry stores the price and the cache timestamp. Entries expire after 900 seconds (15 minutes). On a cache miss, the price is fetched from the provider and cached.

`refreshPrices(Set<String> tickers)` bulk-fetches prices, partitions tickers into crypto and stock sets, calls each provider once, and updates the cache.

### Currency conversion

`PriceService.toEur(balance, currency, ticker)` converts an account balance to EUR:
- If currency is EUR and no ticker is set, returns the balance as-is.
- Otherwise, uses the ticker (preferred) or currency code to fetch a price, then multiplies.

### Scheduler

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`). It collects all tickers from accounts that have a non-null ticker, then calls `PriceService.refreshPrices()`. This keeps the cache warm for the dashboard.

### Key files

- `service/PriceService.java` -- Price routing, caching, conversion
- `service/SchedulerService.java` -- Hourly price refresh cron
- `service/CoinMappingService.java` -- Dynamic ticker → CoinGecko id resolution (search + market-cap)
- `model/CoinMapping.java` + `V39__coin_mapping.sql` -- Persistent ticker → coin-id cache
- `adapter/CoinGeckoPriceProvider.java` -- CoinGecko `/simple/price`, `/search`, `/coins/markets`; reads `coin_mapping`
- `adapter/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}`
- `port/PriceProviderPort.java` -- Port interface with `supports()` and `getPricesEur()`

### Flow

```
Dashboard loads --> needs EUR prices
        |
        v
PriceService.getPriceEur("BTC")
        |
        v
Check cache: CachedPrice for "BTC"
        |
        +-- hit (not expired) --> return cached price
        |
        +-- miss or expired
                |
                v
        CoinGeckoPriceProvider.supports("BTC") --> true
                |
                v
        GET api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur
                |
                v
        Cache result --> return price

Scheduler (every hour):
        |
        v
SchedulerService.refreshPrices()
        |
        v
Collect all non-null tickers from accounts
        |
        v
PriceService.refreshPrices(tickers)
        |
        v
Partition: crypto --> CoinGecko | stocks --> Yahoo
        |
        v
Bulk fetch, update cache
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| CoinGecko free tier | No API key needed, supports batch queries, reliable | CoinMarketCap (requires key) |
| Yahoo Finance (unofficial) | Free, covers European tickers (.PA, .AS) | Alpha Vantage (key required, limited) |
| 15-minute cache TTL | Balance between freshness and API rate limits | No cache (too many requests) or 1-hour cache (stale prices) |
| Hourly scheduler refresh | Keeps cache warm; ensures dashboard loads fast | Fetch on every dashboard request (slow) |
| Provider partition by `supports()` | Clean separation: CoinGecko gets crypto, Yahoo gets everything else | Hardcoded crypto ticker list (duplicated, harder to maintain) |
| Dynamic `coin_mapping` resolution | Any coin is priceable without code changes; resolved once, cached forever; ambiguity is surfaced, never guessed | Hardcoded `TICKER_TO_ID` map (edit code per new coin, ~20 coins only) |
| Optional CoinGecko Demo key | Higher rate limit when set; graceful anonymous fallback when absent | Requiring a key (blocks zero-config setup) |

## Gotchas / Pitfalls

- **Yahoo Finance is unofficial**: The Yahoo Finance API is undocumented and can break or get rate-limited without notice. FX conversion is now applied inside `YahooFinancePriceProvider` using the `{CURRENCY}EUR=X` chart endpoint; `GBp`/`GBX` is treated as `GBP / 100`. If the FX call fails the ticker is omitted from the result map (no fabricated rate) — downstream consumers must tolerate a missing key.
- **CoinGecko rate limits**: The free tier has rate limits (~30 requests/minute). The batch endpoint mitigates this, but individual cache misses could accumulate. The 15-minute cache is essential.
- **Cache is in-memory only**: Prices are lost on restart. The scheduler will repopulate within one hour, but the first few dashboard loads after restart may trigger external API calls.
- **Ticker resolution is dynamic and cached**: `CoinGecko.supports(ticker)` is true only once the ticker has a `coin_mapping` row. Mappings are resolved at crypto-import time (`CoinMappingService`): a single/dominant market-cap symbol match auto-resolves; ambiguous symbols stay unresolved until the operator supplies the CoinGecko link. Consequence: a crypto ticker that was never imported (e.g. a manually-typed holding) won't be priced by CoinGecko until it has a mapping — the resolver only runs where the crypto context is known, to avoid mis-resolving stock tickers that happen to share a symbol.
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `CoinMappingServiceTest` -- resolution rules: cache hit, single/dominant match, ambiguity left unresolved
- `YahooFinancePriceProviderTest` -- unit tests for response parsing

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
