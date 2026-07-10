# Feature: Price Service

> Last updated: 2026-07-10

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices are fetched from two free providers: CoinGecko for crypto and Yahoo Finance for stocks/ETFs. A 15-minute in-memory cache prevents hammering external APIs. The scheduler refreshes prices hourly for all accounts with tickers.

Since V51, ticker → provider-id resolution is **dynamic**: the `financial_asset` table holds one row per priceable asset (symbol, name, type, status, one nullable ref column per aggregator), replacing the hardcoded ticker registries the providers used to carry. `FinancialAssetService` resolves new crypto symbols against CoinGecko's `/search` at discovery time and persists the mapping; ambiguous symbols stay `PENDING` until the operator links them.

## How it works

### Provider routing

`PriceService` never names a concrete adapter: it asks `PriceRouter`, which owns the ordered list of `PriceProviderPort` beans and routes each request to the aggregator that can serve it. Priority is the bean `@Order` (CoinGecko `@Order(10)` before Yahoo `@Order(20)`); for a given ticker the router picks the first provider that both declares the needed `Capability` (`SPOT`/`HISTORY`/`INTRADAY`) and `canPrice(ticker)`. Spot requests are partitioned so each provider is still batched into a single call. Adding a new aggregator (CoinMarketCap, step C/E) is a new `PriceProviderPort` bean with an `@Order` — no edit to `PriceService`/`PriceRouter`.

- **CoinGecko** (`CoinGeckoPriceProvider`, `aggregatorKey() = "coingecko"`): `canPrice` is true for any ticker whose `financial_asset` row has a `coingecko_id`. Uses the `/simple/price` endpoint with `vs_currencies=eur`, batched (all tickers in one request). Also exposes `/search` and `/coins/{id}` lookups for the resolver, plus coin logo URLs (these resolver-only calls stay off the port — they are CoinGecko-specific, not a generic pricing op). An optional Demo API key (`app.coingecko.demo-api-key`, header `x-cg-demo-api-key`) raises the rate limit (~100 req/min vs a handful anonymous).
- **Yahoo Finance** (`YahooFinancePriceProvider`, `aggregatorKey() = "yahoo"`): the catch-all — `canPrice` is true for any ticker whose shape isn't a plain ISIN, so the router reaches it for whatever CoinGecko couldn't price (stocks, ETFs, indices). Uses the unofficial `/v8/finance/chart/{ticker}` endpoint. Fetched per-ticker (no batch). Tickers like `IWDA.AS`, `MC.PA` are already EUR-denominated; foreign-currency tickers (USD/JPY/GBp/...) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Both providers implement `PriceProviderPort`: `aggregatorKey()`, `capabilities()`, `canPrice(ticker)`, `isAvailable()`/`pausedUntil()`, and the three pricing ops (`getPricesEur`, `getHistoricalPricesEur`, `getIntradayPricesEur`). The old single `supports(ticker)` boolean was too weak to order a fallback; `capabilities()` + `canPrice()` replace it, and `isAvailable()` (false while a provider is rate-limited) exists for the cross-provider cascade that lands with the second crypto aggregator — today's routing preserves the previous one-provider-per-ticker behaviour.

A ticker marked `WORTHLESS` (delisted coin no aggregator can price) is valued at a fixed zero by `PriceService` — no provider call, no phantom snapshot.

### Aggregator credentials (`aggregator` / `aggregator_session`)

Each aggregator has a persistent identity (`aggregator` row, keyed by `aggregatorKey()`) and zero or more credential sessions (`aggregator_session`), so API keys live in the DB rather than in a single env var — several keys per aggregator let rate limits be spread, and `enabled` pauses an aggregator or a single key without deleting it. Keys are **app-global** (no `member_id` — a price key is instance-wide) and **encrypted at rest** (AES-GCM via `CryptoEncryption`, never serialized). `AggregatorService` owns encrypt-on-write / decrypt-on-read and hands decrypted credentials (with the session id) to the adapters. See [ADR 2026-07-10](../decisions/2026-07-10-aggregator-credentials-schema.md).

> As of this step the tables + service exist but are not yet consumed at runtime: the CoinGecko adapter still reads its optional key from `app.coingecko.demo-api-key`. Rewiring the adapters to pick a session per request (with per-session rate-limit back-off) and retiring the env var is the next step; that is also when Yahoo's routing fallback starts consulting `isAvailable()`.

### Ticker resolution (`FinancialAssetService`)

Resolution order for a crypto symbol (`resolveCrypto`, called from crypto-guaranteed contexts only — e.g. Trade Republic's `XF000…` internal ISINs, crypto imports):

1. registered asset with a CoinGecko id (or `WORTHLESS`) → done;
2. CoinGecko `/search` for coins whose symbol equals the ticker;
3. exactly one match, or one that dominates the runner-up by market-cap rank (factor 5) → persisted as `AUTO`;
4. otherwise a `PENDING` row is kept — visible in the management UI, retried on the next resolve — and the operator disambiguates by supplying a CoinGecko coin URL (`setManualMapping`, persisted as `USER`).

Correcting a mapping to a *different* coin purges the ticker's `price_snapshot` history (fetched under the wrong coin id) and refetches it; `markWorthless` pins a delisted coin to zero and re-values its holdings.

V51 seeds the registry: the 20 formerly-hardcoded crypto mappings, plus an identity `yahoo_symbol` for every other ticker already present in accounts/holdings — existing positions keep pricing without any user action.

### Caching

`PriceService` maintains a `ConcurrentHashMap<String, CachedPrice>` where the key is the uppercase ticker. Each entry stores the price and the cache timestamp. Entries expire after 900 seconds (15 minutes). On a cache miss, the price is fetched from the provider and cached.

`refreshPrices(Set<String> tickers)` bulk-fetches prices, partitions tickers into crypto and stock sets, calls each provider once, and updates the cache.

Every successful fetch also persists `financial_asset.last_eur_value`/`price_synced_at`, so the latest known price survives a restart (the in-memory cache does not). Nothing reads it back yet — the multi-aggregator fallback chain (step C of the crypto plan) will.

### Currency conversion

`PriceService.toEur(balance, currency, ticker)` converts an account balance to EUR:
- If currency is EUR and no ticker is set, returns the balance as-is.
- Otherwise, uses the ticker (preferred) or currency code to fetch a price, then multiplies.

### Scheduler & backfill

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`). It collects all tickers from accounts that have a non-null ticker, then calls `PriceService.refreshPrices()`. This keeps the cache warm for the dashboard.

`PriceBackfillRunner` (boot) backfills each holding ticker's daily history **anchored to its own earliest transaction** (12-month fallback), and the backfill is gap-aware: only the missing tail since the latest stored snapshot is fetched, so a warm restart is a no-op instead of re-downloading whole windows.

### Key files

- `service/PriceService.java` -- Caching, conversion, gap-aware backfill, worthless/EUR handling (routing delegated to `PriceRouter`)
- `service/PriceRouter.java` -- Capability-based routing over the ordered `PriceProviderPort` beans
- `service/FinancialAssetService.java` -- Dynamic symbol resolution, manual mapping, worthless pinning
- `model/FinancialAsset.java` / `repository/FinancialAssetRepository.java` -- The registry (V51)
- `model/Aggregator.java` / `model/AggregatorSession.java` -- Aggregator identity + encrypted API credentials (V54)
- `service/AggregatorService.java` -- Credential CRUD, encrypt-on-write / decrypt-on-read
- `service/SchedulerService.java` -- Hourly price refresh cron
- `adapter/price/CoinGeckoPriceProvider.java` -- CoinGecko HTTP client (prices, search, logos, circuit breaker)
- `adapter/price/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}`
- `port/PriceProviderPort.java` -- Port interface: `aggregatorKey()`, `capabilities()`, `canPrice()`, `isAvailable()`, `getPricesEur()`/`getHistoricalPricesEur()`/`getIntradayPricesEur()`

### Flow

```
Dashboard loads --> needs EUR prices
        |
        v
PriceService.getPriceEur("BTC")
        |
        +-- financial_asset row says WORTHLESS --> return 0
        |
        v
Check cache: CachedPrice for "BTC"
        |
        +-- hit (not expired) --> return cached price
        |
        +-- miss or expired
                |
                v
        PriceRouter picks first provider that canPrice("BTC")
        CoinGeckoPriceProvider.canPrice("BTC")
        (financial_asset.coingecko_id set?) --> true
                |
                v
        GET api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur
                |
                v
        Cache result + persist last_eur_value --> return price

New crypto symbol discovered (TR sync XF000…, imports):
        |
        v
FinancialAssetService.resolveCrypto("TAO")
        |
        v
GET /search?query=tao --> single/dominant match? --> persist AUTO
                      --> ambiguous/miss?        --> keep PENDING row (operator links it later)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| CoinGecko free tier | No API key needed, supports batch queries, reliable | CoinMarketCap (requires key) — planned as fallback aggregator (step C/E) |
| Yahoo Finance (unofficial) | Free, covers European tickers (.PA, .AS) | Alpha Vantage (key required, limited) |
| Dynamic `financial_asset` registry | New coins resolve without a code change; ambiguity is surfaced, never guessed | Hardcoded ticker→id maps in each provider (drifted: CoinGecko had 20 coins, Yahoo's skip-list 10) |
| One nullable ref column per aggregator | Exactly one external ref per (asset, aggregator); simple queries | Junction table (rejected 2026-07-08 — adding an aggregator needs code anyway) |
| Market-cap dominance factor (×5) for auto-resolution | A #5 coin vs a #300 clone is safe; #10 vs #14 is not — those wait for the operator | Always top-ranked match (mis-maps popular symbol clones) |
| 15-minute cache TTL | Balance between freshness and API rate limits | No cache (too many requests) or 1-hour cache (stale prices) |
| Circuit breaker on 429 (`pausedUntil`, honours `Retry-After`) | One warning per pause window; a dashboard load can't spam a rate-limited API | Retry with backoff (amplifies the burst that caused the 429) |
| Hourly scheduler refresh | Keeps cache warm; ensures dashboard loads fast | Fetch on every dashboard request (slow) |

## Gotchas / Pitfalls

- **Yahoo Finance is unofficial**: The Yahoo Finance API is undocumented and can break or get rate-limited without notice. FX conversion is now applied inside `YahooFinancePriceProvider` using the `{CURRENCY}EUR=X` chart endpoint; `GBp`/`GBX` is treated as `GBP / 100`. If the FX call fails the ticker is omitted from the result map (no fabricated rate) — downstream consumers must tolerate a missing key.
- **CoinGecko rate limits**: the anonymous tier 429s within ~5-6 requests (Cloudflare serves `Retry-After: 60`). The circuit breaker pauses *all* CoinGecko calls until the window elapses; a Demo API key (`COINGECKO_DEMO_API_KEY`) raises the limit to ~100 req/min and is the real fix.
- **CoinGecko free history is age-limited**: `market_chart/range` 401s when `from` is older than ~365 days, so historical requests are clamped to 364 days (`MAX_FREE_HISTORY_DAYS`). Older history would need a paid Pro key.
- **Unresolved symbols price as before, not better**: a `PENDING` product has no aggregator ref, so `canPrice` is false on CoinGecko and the router falls through to Yahoo with the raw symbol (usually a miss → unpriced holding). That is the pre-V51 behaviour for unknown coins; the fix is linking the coin in the management UI.
- **Cache is in-memory only**: prices are lost on restart (the scheduler repopulates within one hour). `financial_asset.last_eur_value` now persists the last known price, but nothing reads it back yet — that lands with the multi-aggregator fallback (step C).
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `FinancialAssetServiceTest` -- resolution rules (dominance, PENDING retry), manual mapping, worthless, purge-and-refetch
- `OpenFigiIsinConverterTest` -- ISIN detection + TR crypto ISINs resolved through the product registry
- `YahooFinancePriceProviderTest` -- unit tests for response parsing

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [Aggregator credentials schema](../decisions/2026-07-10-aggregator-credentials-schema.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
