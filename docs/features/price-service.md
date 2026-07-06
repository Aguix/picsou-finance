# Feature: Price Service

> Last updated: 2026-07-06

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
   operator to disambiguate (see below), which persists the choice as `USER`.

Resolution runs at **crypto-import time** (`CryptoImportService`), where the context guarantees the
ticker is a crypto — so a symbol shared with a stock ticker can't be mis-routed on the general price
path. An optional Demo API key (`app.coingecko.demo-api-key`, sent as the `x-cg-demo-api-key` header)
lifts the rate limit; absent, calls fall back to the anonymous free tier.

### Manual disambiguation

Ambiguous or unknown symbols are never guessed — the operator resolves them explicitly:

1. **Preview flags them.** `CryptoImportService.preview()` resolves every imported ticker up front
   and returns the ones still unresolved in `CryptoPreviewResponse.unresolvedTickers`, so the import
   UI can prompt for them *before* the import runs.
2. **Operator supplies the CoinGecko link.** For each unresolved coin the UI shows an input; the
   operator pastes the coin's CoinGecko page URL (e.g. `https://www.coingecko.com/en/coins/loaded-lions`)
   and it's sent to `POST /api/crypto/coin-mappings`.
3. **The backend validates and pins it.** `CoinMappingService.setManualMapping(ticker, url)` extracts
   the coin-id slug from the URL (`/coins/<id>`, tolerating locale prefixes, query strings, and
   fragments), validates it against CoinGecko via `CoinGeckoPriceProvider.fetchCoinById(id)` — a link
   to a non-existent coin is rejected, not cached — then upserts the mapping as `USER`, overriding any
   prior `AUTO` guess.

Disambiguation is **optional**: an unresolved coin simply imports unpriced (holdings still recorded,
value 0 until a mapping exists), so the import is never blocked. `coin_mapping` is a **global** cache
keyed by ticker, not member-scoped — the mapping endpoint only requires an authenticated caller.

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
- `service/CoinMappingService.java` -- Dynamic ticker → CoinGecko id resolution (search + market-cap) and manual disambiguation (`setManualMapping`)
- `controller/CryptoController.java` -- `POST /api/crypto/coin-mappings` disambiguation endpoint
- `model/CoinMapping.java` + `V39__coin_mapping.sql` -- Persistent ticker → coin-id cache
- `adapter/CoinGeckoPriceProvider.java` -- CoinGecko `/simple/price`, `/search`, `/coins/{id}`, `/coins/markets`; reads `coin_mapping`
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
- **CoinGecko rate limits (reactive backoff)**: The free/anonymous tier rate-limits hard — a burst 429s within ~5 calls (the Demo key raises this to 100/min). Rather than pace requests with a blind fixed delay, `CoinGeckoPriceProvider` reacts to the actual `429`: it waits the response's `Retry-After` (observed ~60s, served by CoinGecko's Cloudflare edge) — falling back to `DEFAULT_RETRY_AFTER` (60s) if the header is absent/unparseable — and retries, up to `MAX_RATE_LIMIT_RETRIES` (4). Applies to every CoinGecko call (`/search`, `/simple/price`, `/coins/{id}`, market_chart, `/coins/markets`). Consequence: small imports run at full speed with no artificial wait; a large first-time import is still bounded by the tier's throughput (≈5 coins per ~60s window on anonymous), so it can take minutes — **a Demo key is the real unlock**. `Retry-After` is honoured but not contractually documented by CoinGecko, hence the fallback. Retries exhausted → the caller degrades to an empty result (never fabricated data).
- **Cache is in-memory only**: Prices are lost on restart. The scheduler will repopulate within one hour, but the first few dashboard loads after restart may trigger external API calls.
- **Ticker resolution is dynamic and cached**: `CoinGecko.supports(ticker)` is true only once the ticker has a `coin_mapping` row. Mappings are resolved at crypto-import time (`CoinMappingService`): a single/dominant market-cap symbol match auto-resolves; ambiguous symbols stay unresolved until the operator supplies the CoinGecko link (see *Manual disambiguation*). Consequence: a crypto ticker that was never imported (e.g. a manually-typed holding) won't be priced by CoinGecko until it has a mapping — the resolver only runs where the crypto context is known, to avoid mis-resolving stock tickers that happen to share a symbol.
- **Resolving at preview costs `/search` calls**: `preview()` resolves every ticker up front (to flag `unresolvedTickers`), so a preview of an unseen CSV fans out one CoinGecko `/search` per new coin — the same work the import would do, just moved earlier. These calls share the reactive `Retry-After` backoff above, so a 429 makes preview *slower* (it waits and retries) rather than falsely reporting real coins as unresolved.
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `CoinMappingServiceTest` -- resolution rules: cache hit, single/dominant match, ambiguity left unresolved; manual disambiguation: URL-slug extraction, unknown-coin/invalid-link rejection, `USER` override of an `AUTO` guess
- `YahooFinancePriceProviderTest` -- unit tests for response parsing

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
