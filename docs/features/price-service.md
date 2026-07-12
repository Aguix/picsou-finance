# Feature: Price Service

> Last updated: 2026-07-12

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices are fetched from two free providers: CoinGecko for crypto and Yahoo Finance for stocks/ETFs. A 15-minute in-memory cache prevents hammering external APIs. The scheduler refreshes prices hourly for all accounts with tickers.

Since V51, ticker → provider-id resolution is **dynamic**: the `financial_asset` table holds one row per priceable asset (symbol, name, type, status, one nullable ref column per aggregator), replacing the hardcoded ticker registries the providers used to carry. `FinancialAssetService` resolves new crypto symbols against CoinGecko's `/search` at discovery time and persists the mapping; ambiguous symbols stay `PENDING` until the operator links them.

## How it works

### Provider routing

`PriceService` never names a concrete adapter: it asks `PriceRouter`, which owns the ordered list of `PriceProviderPort` beans and routes each request to the aggregator that can serve it. Priority is the bean `@Order` (CoinGecko `@Order(10)` before Yahoo `@Order(20)`); for a given asset the router picks the first provider that both declares the needed `Capability` (`SPOT`/`HISTORY`/`INTRADAY`) and `canPrice(asset)`. Every pricing op takes the `FinancialAsset` itself, not a bare ticker: each provider reads *its own* external ref (`coingecko_id`, `yahoo_symbol`) straight off the asset, so the adapters carry **no registry dependency** and the caller (which already holds the asset) hands it through without re-resolving the symbol. Spot requests are partitioned so each provider is still batched into a single call. Adding a new aggregator (CoinMarketCap, step C/E) is a new `PriceProviderPort` bean with an `@Order` — no edit to `PriceService`/`PriceRouter`.

- **CoinGecko** (`CoinGeckoPriceProvider`, `aggregatorKey() = "coingecko"`): `canPrice` is true for any asset carrying a `coingecko_id` (read straight off the asset — no lookup). Uses the `/simple/price` endpoint with `vs_currencies=eur`, batched (all tickers in one request). Also exposes `/search` and `/coins/{id}` lookups for the resolver, plus coin logo URLs (these resolver-only calls stay off the port — they are CoinGecko-specific, not a generic pricing op). Optional Demo API keys (header `x-cg-demo-api-key`) raise the rate limit (~100 req/min vs a handful anonymous); they come from the `aggregator_session` table (admin panel), picked per request — see below. With none configured the provider runs anonymous.
- **Yahoo Finance** (`YahooFinancePriceProvider`, `aggregatorKey() = "yahoo"`): the catch-all — `canPrice` is true for any asset whose Yahoo symbol (`yahoo_symbol`, falling back to the internal `symbol`) isn't a plain ISIN, so the router reaches it for whatever CoinGecko couldn't price (stocks, ETFs, indices). It queries that Yahoo symbol on the unofficial `/v8/finance/chart/{ticker}` endpoint. `yahoo_symbol` is not populated yet, so the internal symbol is used verbatim exactly as before — gating Yahoo on a populated `yahoo_symbol` (so an unresolved coin no longer falls through to a wasted Yahoo call) is later work. Fetched per-ticker (no batch). Tickers like `IWDA.AS`, `MC.PA` are already EUR-denominated; foreign-currency tickers (USD/JPY/GBp/...) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Both providers implement `PriceProviderPort`: `aggregatorKey()`, `capabilities()`, `canPrice(asset)`, `isAvailable()`/`pausedUntil()`, and the three pricing ops (`getPricesEur(Collection<FinancialAsset>)`, `getHistoricalPricesEur(FinancialAsset, …)`, `getIntradayPricesEur(FinancialAsset, …)`). The old single `supports(ticker)` boolean was too weak to order a fallback; `capabilities()` + `canPrice()` replace it, and `isAvailable()` (false while a provider is rate-limited) exists for the cross-provider cascade that lands with the second crypto aggregator — today's routing preserves the previous one-provider-per-ticker behaviour.

A ticker marked `WORTHLESS` (delisted coin no aggregator can price) is valued at a fixed zero by `PriceService` — no provider call, no phantom snapshot.

### Aggregator credentials (`aggregator` / `aggregator_session`)

Each aggregator has a persistent identity (`aggregator` row, keyed by `aggregatorKey()`) and zero or more credential sessions (`aggregator_session`), so API keys live in the DB rather than in a single env var — several keys per aggregator let rate limits be spread, and `enabled` pauses an aggregator or a single key without deleting it. Keys are **app-global** (no `member_id` — a price key is instance-wide) and **encrypted at rest** (AES-GCM via `CryptoEncryption`, never serialized). `AggregatorService` owns encrypt-on-write / decrypt-on-read and hands decrypted credentials (with the session id) to the adapters. See [ADR 2026-07-10](../decisions/2026-07-10-aggregator-credentials-schema.md).

The operator manages these from the admin panel (**Administration → Price aggregators**, `AdminAggregatorController` under `/api/admin/aggregators`, `ROLE_ADMIN`): toggle an aggregator, add/enable/delete keys. Secrets are write-only — the API only ever reports *whether* a key is set, never its value.

**How the CoinGecko adapter consumes them.** At call time the provider asks `AggregatorService.enabledCredentials("coingecko")` for the enabled keys and picks the **least-recently-used** one whose per-session breaker is closed (ties broken by session id), sending its key as the `x-cg-demo-api-key` header for *that* request. LRU rotation spreads calls across keys — several keys stay below their limit instead of one absorbing every call until it 429s (rotation state is in-memory, not the DB `last_sync_at`, so there's no write on the price read path). A `429` trips a **per-session** breaker: only the key that hit the limit is paused (honouring `Retry-After`, else 60 s), so the next request rolls over to another key; the provider only short-circuits to an empty result once *every* candidate session is paused.

`enabledCredentials` returns an `Optional<List<…>>`: an **empty Optional** means the aggregator is disabled (or unknown) — the adapter then makes no call at all, *not even anonymous*, so the admin on/off toggle truly stops it; a **present but empty list** means the aggregator is enabled with no key, so the adapter uses the anonymous free tier. `isAvailable()` is false only when the aggregator is disabled or every key is paused. The old `COINGECKO_DEMO_API_KEY` env var (`app.coingecko.demo-api-key`) is retired — existing installs move their key into the admin panel.

### Ticker resolution (`FinancialAssetService`)

Resolution order for a crypto symbol (`resolveCrypto`, called from crypto-guaranteed contexts only — e.g. Trade Republic's `XF000…` internal ISINs, crypto imports):

1. registered asset with a CoinGecko id (or `WORTHLESS`) → done;
2. CoinGecko `/search` for coins whose symbol equals the ticker;
3. exactly one match, or one that dominates the runner-up by market-cap rank (factor 5) → persisted as `AUTO`;
4. otherwise a `PENDING` row is kept — visible in the management UI, retried on the next resolve — and the operator disambiguates by supplying a CoinGecko coin URL (`setManualMapping`, persisted as `USER`).

Correcting a mapping to a *different* coin purges the ticker's `price_snapshot` history (fetched under the wrong coin id) and refetches it; `markWorthless` pins a delisted coin to zero and re-values its holdings.

The crypto CSV import confirms this resolution *before* it commits. `previewResolutions` resolves each imported coin **provisionally — persisting nothing** — and returns, for every coin not already settled (`USER`/`WORTHLESS`), the best market-cap guess plus **all** CoinGecko candidates that share the symbol (the ones `resolveCrypto` discards after picking). The preview shows one pre-filled picker per coin; the operator confirms, picks another candidate, marks it worthless, or skips it. The confirmed choices ride the import request and `execute()` applies them as `USER` (via `applyUserMapping`, no extra CoinGecko round-trip) **before** the price backfill — so prices are fetched under the right coin id from the first import. This is what stops a silent `AUTO` mis-match (e.g. a `META` ticker pinned to the wrong dominant coin) from being frozen in the registry unnoticed; skipped coins import unpriced and are re-presented next time.

### Standing mapping / verification (holding detail)

The import preview only surfaces a coin *while* importing. The **standing** counterpart is per-holding and always available: the crypto holding detail (`HoldingDetailModal`) carries an **Aggregator link** card (`AggregatorLinkCard`) that shows the symbol's current resolution status and lets the operator verify or correct it any time — pick a candidate, paste a CoinGecko coin link, mark it worthless, or forget the mapping. The account-detail holdings table badges each crypto row with its status (`AssetStatusBadge`), so an unresolved (`PENDING`) coin is visible at a glance without opening anything.

This is exposed by `AssetController` under `/api/assets`. The registry is **not** member-scoped — `financial_asset` is a global, member-agnostic catalogue (one row per symbol, shared across the family), unlike the account/holding endpoints. Reads are open to any authenticated member; **writes are admin-only** (`SecurityConfig` gates `PUT`/`DELETE /api/assets/**` to `ROLE_ADMIN`), because changing or forgetting a mapping re-values *everyone's* holdings — the frozen permission rule (any member may confirm a `PENDING` from their own import; changing a settled mapping is admin-only). The card hides its editor for non-admins accordingly.

- `GET /api/assets` (any member) → the whole registry (`listAll`), one `AssetResponse` per asset, for the management table.
- `GET /api/assets/{symbol}/candidates` (any member) → the current status, the market-cap suggestion, and every CoinGecko candidate (`previewResolution` — the single-symbol sibling of `previewResolutions` that, unlike it, returns candidates even for a coin already settled, so a standing mapping can always be re-verified). Fetched **lazily** by the card (only once the operator opens the editor) to respect the CoinGecko rate limit.
- `PUT /api/assets/{symbol}/mapping` (admin) → apply a mapping: a pasted link (`setManualMapping`) or a picked candidate id (`applyUserMapping`) lands `USER`; `action=WORTHLESS` calls `markWorthless`.
- `DELETE /api/assets/{symbol}` (admin) → `clearMapping` — un-links the symbol (reverts it to `PENDING`, purges its price history) but **keeps the registry row**, so a holding's `asset_id` FK stays valid. A full row `delete` would fail for any held symbol (the FK has no cascade), and the "forget the link" button always runs on a held coin, so it clears rather than deletes.

Two surfaces consume these. **Per-holding**: the `AggregatorLinkCard` appears both in the crypto holding detail (`HoldingDetailModal`, dashboard) and in the buy-in editor (`EditHoldingModal`, account detail). **Registry-wide**: `AssetRegistryModal` — a table of every asset with one column per aggregator (CoinGecko id, Yahoo symbol), its status, and its last known EUR value (`lastEurValue`, with a freshness dot) as a quick sanity check that a mapping points at the right coin — shown only for a crypto that actually has a CoinGecko id (an unlinked coin's `lastEurValue` may be a spurious Yahoo-fallback quote on the raw symbol, so it's hidden rather than passed off as validation) — opened from `/accounts` (next to "Add account") and from the admin price-aggregators section; admins confirm an `AUTO` guess in one click or expand a row to the same editor. Confirming/editing stays crypto-only (the engine is CoinGecko-bound); the Yahoo column is informational.

These are the *same* `FinancialAssetService` entry points the import path calls, so a mapping made from a holding and one confirmed during an import are identical (both land `USER`/`WORTHLESS`, and re-pinning to a different coin purges + refetches history exactly as above). `HoldingResponse` now carries `assetType`/`assetStatus`/`coingeckoId` (read straight off the already-loaded asset), so the badge and the card render without an extra round-trip. Kept crypto-only for now — the resolution *engine* is still CoinGecko-bound; generalising it (Yahoo-symbol resolution for stocks/ETFs, per-type UI) is later work.
### Caching

`PriceService` maintains a `ConcurrentHashMap<String, CachedPrice>` where the key is the uppercase ticker. Each entry stores the price and the cache timestamp. Entries expire after 900 seconds (15 minutes). On a cache miss, the price is fetched from the provider and cached.

`refreshPrices(Set<String> tickers)` bulk-fetches prices, partitions tickers into crypto and stock sets, calls each provider once, and updates the cache.

Every successful fetch also persists `financial_asset.last_eur_value`/`price_synced_at`, so the latest known price survives a restart (the in-memory cache does not). Nothing reads it back yet — the multi-aggregator fallback chain (step C of the crypto plan) will.

### Currency conversion

`PriceService.toEur(balance, currency, asset)` converts an account balance to EUR:
- If currency is EUR and no asset is set, returns the balance as-is.
- Otherwise, uses the asset (preferred) or the currency code to fetch a price, then multiplies.

The asset path goes straight through `getPriceEur(FinancialAsset)`. A bare currency code (a foreign bank-account currency) and the MCP tool's caller-supplied ticker have no asset in hand, so they go through the `getPriceEur(String)` **seam**: it resolves the symbol to its registered asset — or a transient, non-persisted asset carrying just the symbol when unregistered — then prices it like any other asset. A fiat currency has no registry row, so it rides a transient asset and, absent a Yahoo quote for the raw code, returns `null` → the balance is left unconverted (the pre-existing best-effort behaviour; a dedicated fiat/FX path is future work).

### Scheduler & backfill

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`). It collects all tickers from accounts that have a non-null ticker, then calls `PriceService.refreshPrices()`. This keeps the cache warm for the dashboard.

`PriceBackfillRunner` (boot) backfills each holding ticker's daily history **anchored to its own earliest transaction** (12-month fallback), and the backfill is gap-aware: only the missing tail since the latest stored snapshot is fetched, so a warm restart is a no-op instead of re-downloading whole windows.

### Key files

- `service/PriceService.java` -- Caching, conversion, gap-aware backfill, worthless/EUR handling (routing delegated to `PriceRouter`); `getPriceEur(FinancialAsset)` is the primary entry point, `getPriceEur(String)` a resolve-or-transient seam for callers with only a ticker (currency code, MCP)
- `service/PriceRouter.java` -- Capability-based routing over the ordered `PriceProviderPort` beans
- `service/FinancialAssetService.java` -- Dynamic symbol resolution, manual mapping, worthless pinning, `previewResolution` (standing candidates)
- `controller/AssetController.java` -- Standing mapping/verification endpoints (`/api/assets`): candidates, apply mapping, forget
- `dto/AssetResponse.java` / `AssetCandidatesResponse.java` / `AssetMappingRequest.java` -- Standing-mapping DTOs
- `components/shared/AggregatorLinkCard.tsx` / `AssetStatusBadge.tsx` / `AssetRegistryModal.tsx` + `features/assets/` -- Per-holding mapping card, status badge, registry-wide management table, and query hooks (frontend)
- `model/FinancialAsset.java` / `repository/FinancialAssetRepository.java` -- The registry (V51)
- `model/Aggregator.java` / `model/AggregatorSession.java` -- Aggregator identity + encrypted API credentials (V54)
- `service/AggregatorService.java` -- Credential CRUD, encrypt-on-write / decrypt-on-read, `enabledCredentials()` for the adapters
- `controller/AdminAggregatorController.java` -- Admin panel endpoints (`/api/admin/aggregators`): list, toggle, add/enable/delete keys
- `service/SchedulerService.java` -- Hourly price refresh cron
- `adapter/price/CoinGeckoPriceProvider.java` -- CoinGecko HTTP client (prices, search, logos, circuit breaker)
- `adapter/price/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}`
- `port/PriceProviderPort.java` -- Port interface, asset-typed: `aggregatorKey()`, `capabilities()`, `canPrice(FinancialAsset)`, `isAvailable()`, `getPricesEur(Collection<FinancialAsset>)`/`getHistoricalPricesEur(FinancialAsset,…)`/`getIntradayPricesEur(FinancialAsset,…)`

### Flow

```
Dashboard loads --> needs EUR prices
        |
        v
PriceService.getPriceEur(asset)   // getPriceEur(String) is the seam for callers with only a ticker
        |
        +-- asset.isWorthless() --> return 0
        |
        v
Check cache: CachedPrice for "BTC"
        |
        +-- hit (not expired) --> return cached price
        |
        +-- miss or expired
                |
                v
        PriceRouter picks first provider that canPrice(asset)
        CoinGeckoPriceProvider.canPrice(asset)
        (asset.coingecko_id set?) --> true
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
- **CoinGecko rate limits**: the anonymous tier 429s within ~5-6 requests (Cloudflare serves `Retry-After: 60`). The circuit breaker is now **per session (key)**: a 429 pauses only the key that hit it, so the next request rolls over to another enabled key; all calls stop only once every key is paused. Adding one or more Demo keys from the admin panel (**Administration → Price aggregators**) raises the limit to ~100 req/min per key and is the real fix.
- **CoinGecko free history is age-limited**: `market_chart/range` 401s when `from` is older than ~365 days, so historical requests are clamped to 364 days (`MAX_FREE_HISTORY_DAYS`). Older history would need a paid Pro key.
- **Unresolved symbols price as before, not better**: a `PENDING` product has no aggregator ref, so `canPrice` is false on CoinGecko and the router falls through to Yahoo with the raw symbol (usually a miss → unpriced holding). That is the pre-V51 behaviour for unknown coins; the fix is linking the coin — during a crypto import, or any time afterwards from the holding detail's **Aggregator link** card (a `PENDING`-badged row in the holdings table flags which coins need it).
- **Cache is in-memory only**: prices are lost on restart (the scheduler repopulates within one hour). `financial_asset.last_eur_value` now persists the last known price, but nothing reads it back yet — that lands with the multi-aggregator fallback (step C).
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `FinancialAssetServiceTest` -- resolution rules (dominance, PENDING retry), manual mapping, worthless, purge-and-refetch
- `OpenFigiIsinConverterTest` -- ISIN detection + TR crypto ISINs resolved through the product registry
- `YahooFinancePriceProviderTest` -- unit tests for response parsing
- `CoinGeckoPriceProviderTest` -- per-session key header, least-recently-used key rotation, per-session breaker roll-over, anonymous fallback, disabled-aggregator cutoff, all-paused short-circuit
- `AggregatorServiceTest` -- encrypt-on-write / decrypt-on-read, enabled-session filtering

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [Aggregator credentials schema](../decisions/2026-07-10-aggregator-credentials-schema.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
