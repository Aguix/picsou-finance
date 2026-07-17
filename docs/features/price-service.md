# Feature: Price Service

> Last updated: 2026-07-15

## Context

Picsou needs EUR prices for crypto assets (BTC, ETH, SOL, etc.) and stocks/ETFs (PEA/Compte-Titres holdings) to display account balances in a unified currency. Prices come from free aggregators — CoinGecko, CoinMarketCap, Yahoo Finance — behind one port. A 15-minute in-memory cache prevents hammering external APIs. The scheduler refreshes prices hourly for all accounts with tickers.

Since V51, ticker → aggregator-id resolution is **dynamic**: the `financial_asset` table holds one row per priceable asset (symbol, name, type, status, one nullable ref column per aggregator), replacing the hardcoded ticker registries the providers used to carry.

**An aggregator is just an aggregator.** There is no "crypto aggregator" or "stock aggregator", and no asset-type gate anywhere: each aggregator owns exactly one ref column (`coingecko_id`, `coinmarketcap_id`, `yahoo_symbol`), and **a null ref means that aggregator can't price that asset**. That's the entire capability rule — `canPrice(asset)` is that null check. Which assets an aggregator happens to know is a property of *its data*, not of a category we assign it in code. Resolution is what fills those columns; an asset can carry several refs, and that's what gives it a price fallback when one aggregator is rate-limited or switched off.

Adding an aggregator is therefore: **one adapter** (implementing `PriceProviderPort` + `AssetResolverPort`), **one migration** (its ref column + its `aggregator` row), **one entity field**. The engine, DTOs, and frontend loop over aggregators and don't change.

## How it works

### Provider routing

`PriceService` never names a concrete adapter: it asks `PriceRouter`, which owns the ordered list of `PriceProviderPort` beans and routes each request to the aggregator that can serve it. Priority is the bean `@Order` (CoinGecko `10`, CoinMarketCap `15`, Yahoo `20`). Every pricing op takes the `FinancialAsset` itself, not a bare ticker: each provider reads *its own* ref straight off the asset, so the adapters carry **no registry dependency** and the caller (which already holds the asset) hands it through without re-resolving the symbol.

**The fallback is a waterfall over the results**, covering both failure modes. Providers are walked in priority order; each is called at most once, batched with every still-unpriced asset it declares the needed `Capability` (`SPOT`/`HISTORY`/`INTRADAY`) for, `canPrice(asset)` (holds a ref for), and is `isAvailable()` to serve. A provider *known* unusable up front (breaker open, admin toggle off) is skipped before the call; one that fails **during** the call — a 429 tripping mid-batch, a network error, an id its API didn't answer for — simply doesn't return those prices, and the router hands the leftovers to the next aggregator holding a ref **in the same refresh** instead of stranding them until the next one. An asset priced upstream is never re-requested downstream (no wasted quota, and a lower-priority quote can't overwrite a better one). The single-asset series ops (history/intraday) walk the same way, first non-empty answer wins. An asset mapped on only *one* aggregator still goes unpriced when that one fails; the fix is mapping it on a second one (the import preview offers exactly that), not routing. Availability is evaluated once per provider per batch, not per asset — same answer for the whole batch, and on a rotating-key provider each call would otherwise pick and stamp a session.

- **CoinGecko** (`CoinGeckoPriceProvider`, `aggregatorKey() = "coingecko"`): `canPrice` = the asset carries a `coingecko_id`. Prices via `/simple/price?vs_currencies=eur`, batched (all ids in one request); resolves via `/search` and `/coins/{id}`, and recognises a coin-page URL (`/coins/<slug>`) for the "paste a link" path. Also serves coin logo URLs (off the port — CoinGecko-specific, not a generic pricing op). Optional Demo API keys (header `x-cg-demo-api-key`) raise the rate limit (~100 req/min vs a handful anonymous); they come from the `aggregator_session` table (admin panel), picked per request — see below. With none configured it runs anonymous.
- **CoinMarketCap** (`CoinMarketCapPriceProvider`, `aggregatorKey() = "coinmarketcap"`): `canPrice` = the asset carries a `coinmarketcap_id`. Prices via `/v2/cryptocurrency/quotes/latest?id=…&convert=EUR` (batched), resolves via `/v1/cryptocurrency/map?symbol=` and `/v2/cryptocurrency/info?id=`. Everything is addressed **by id, never by symbol**: the symbol endpoint returns every coin sharing a ticker (as a list), which is exactly the ambiguity the registry exists to remove — an id addresses one coin and comes back as a single object. `SPOT` only (no historical series on the free plan), and **no anonymous tier**: with no key it makes no call at all and `isResolutionAvailable()` is false, so the resolution UI doesn't even offer it. No URL form — its id comes from a picked candidate.
- **Yahoo Finance** (`YahooFinancePriceProvider`, `aggregatorKey() = "yahoo"`): `canPrice` = the asset carries a `yahoo_symbol` that isn't a plain ISIN. Yahoo's "id" *is* the symbol it quotes, so `getRef`/`fetchById` deal in the same exchange-suffixed string (`IWDA.AS`). Prices via the unofficial `/v8/finance/chart/{ticker}` endpoint, per-ticker (no batch); resolves via `/v1/finance/search`, whose hits are the ticker's listings across exchanges. Foreign-currency tickers (USD/JPY/GBp/…) are converted to EUR inside the adapter via Yahoo's own `{CURRENCY}EUR=X` chart endpoint, with a 15-minute FX cache mirroring the price cache TTL. See [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md).

Every aggregator implements **both ports**: `PriceProviderPort` (`aggregatorKey()`, `capabilities()`, `canPrice(asset)`, `isAvailable()`/`pausedUntil()`, and the three pricing ops) and `AssetResolverPort` (`isResolutionAvailable()`, `searchBySymbol`, `fetchById`, `extractIdFromUrl`, `getRef`/`setRef`). The two sides are the same aggregator seen from both ends: resolution finds the id, pricing spends it. `getRef`/`setRef` are the *only* place a column name appears, and each adapter touches **only its own** — a provider must never read a sibling's column (gating CoinMarketCap on `coingecko_id`, say, would break it whenever the CoinGecko engine changed, for a reason having nothing to do with CoinMarketCap).

A ticker marked `WORTHLESS` (delisted coin no aggregator can price) is valued at a fixed zero by `PriceService` — no provider call, no phantom snapshot.

### Aggregator credentials (`aggregator` / `aggregator_session`)

Each aggregator has a persistent identity (`aggregator` row, keyed by `aggregatorKey()`) and zero or more credential sessions (`aggregator_session`), so API keys live in the DB rather than in a single env var — several keys per aggregator let rate limits be spread, and `enabled` pauses an aggregator or a single key without deleting it. Keys are **app-global** (no `member_id` — a price key is instance-wide) and **encrypted at rest** (AES-GCM via `CryptoEncryption`, never serialized). `AggregatorService` owns encrypt-on-write / decrypt-on-read and hands decrypted credentials (with the session id) to the adapters. See [ADR 2026-07-10](../decisions/2026-07-10-aggregator-credentials-schema.md).

The operator manages these from the admin panel (**Administration → Price aggregators**, `AdminAggregatorController` under `/api/admin/aggregators`, `ROLE_ADMIN`): toggle an aggregator, add/enable/delete keys. Secrets are write-only — the API only ever reports *whether* a key is set, never its value.

**How the CoinGecko adapter consumes them.** At call time the provider asks `AggregatorService.enabledCredentials("coingecko")` for the enabled keys and picks the **least-recently-used** one whose per-session breaker is closed (ties broken by session id), sending its key as the `x-cg-demo-api-key` header for *that* request. LRU rotation spreads calls across keys — several keys stay below their limit instead of one absorbing every call until it 429s (rotation state is in-memory, not the DB `last_sync_at`, so there's no write on the price read path). A `429` trips a **per-session** breaker: only the key that hit the limit is paused (honouring `Retry-After`, else 60 s), so the next request rolls over to another key; the provider only short-circuits to an empty result once *every* candidate session is paused.

`enabledCredentials` returns an `Optional<List<…>>`: an **empty Optional** means the aggregator is disabled (or unknown) — the adapter then makes no call at all, *not even anonymous*, so the admin on/off toggle truly stops it; a **present but empty list** means the aggregator is enabled with no key, so the adapter uses the anonymous free tier. `isAvailable()` is false only when the aggregator is disabled or every key is paused. The old `COINGECKO_DEMO_API_KEY` env var (`app.coingecko.demo-api-key`) is retired — existing installs move their key into the admin panel.

### Ticker resolution (`FinancialAssetService`)

The engine drives resolution through the injected `List<AssetResolverPort>` and never names an adapter. Two ways a ref gets filled:

**1. Auto, at discovery time** (`resolveCrypto`, called from crypto-guaranteed contexts only — e.g. Trade Republic's `XF000…` internal ISINs, crypto imports). A deliberate single-aggregator shortcut: the context guarantees the symbol is a coin, so it asks the crypto-discovery aggregator (CoinGecko) alone rather than fanning a search out to everyone on every imported coin.

1. registered asset already settled (not `PENDING`) → done;
2. `searchBySymbol` on that aggregator;
3. a **ranked** candidate that dominates the runner-up by market-cap rank (factor 5), or is the only ranked one → persisted as `AUTO`;
4. otherwise a `PENDING` row is kept — visible in the management UI, retried on the next resolve — and the operator disambiguates from the preview or by pasting a coin URL (`setManualMapping`, persisted as `USER`).

A market-cap rank is **required** to auto-resolve, even for a lone match: the rank is the only evidence a symbol collision has an obvious winner. This is also what keeps an aggregator that doesn't rank its results (Yahoo, whose candidates are the same security on different exchanges) from ever auto-suggesting — guessing an exchange would quote the security in the wrong market.

**2. Confirmed by the operator, per aggregator** (`previewResolutions`/`previewResolution` → `applyMappings`). The preview asks **every** `isResolutionAvailable()` aggregator what it has for the symbol and returns one block each (`AggregatorResolution{aggregatorKey, suggested, candidates}`), persisting nothing. An aggregator with nothing to offer still gets a block with an empty list — "this one doesn't know your symbol" is information, and it's the honest answer: its ref stays null and it won't price the asset. One aggregator failing (rate limit) degrades to an empty list rather than costing the operator the others' offers. `applyMappings(symbol, Map<aggregatorKey, id>, name)` then writes each id through its own resolver's `setRef`, lands `USER`, and **ignores an unknown key** rather than failing an import from an older client.

`setManualMapping(symbol, url)` offers the link to each resolver's `extractIdFromUrl`; the one that recognises it owns the id, which is then validated via `fetchById` before being persisted. `clearMapping`/`markWorthless` loop the resolvers to drop **every** ref — a ref left behind on a second aggregator would keep the symbol silently priced after the operator asked to forget it or pinned it to zero.

Re-pinning an aggregator to a *different* id purges the symbol's `price_snapshot` history (some of it was fetched under the wrong id) and refetches it. Filling a ref that was empty — adding CoinMarketCap next to a working CoinGecko id — changes nothing already priced, so the history is kept.

`markWorthless` pins a delisted coin to zero and re-values its holdings.

**Stock/ETF resolution (`getOrCreateStock`)** is the Yahoo-side counterpart, populating `yahoo_symbol` the way `resolveCrypto` populates `coingecko_id`. It's simpler because there's no ambiguity to resolve: `OpenFigiIsinConverter.resolve(isin)` already turns a broker ISIN into a Yahoo ticker (e.g. `IWDA.AS`) via OpenFIGI's exchange mapping, and that ticker *is* the asset's internal `symbol` — so `getOrCreateStock` just mints the row as `STOCK` with `yahoo_symbol = symbol` (or fills it in on an existing row), and never touches a row already typed `CRYPTO` — a stock-context ticker colliding with an existing crypto symbol must not clobber a working coin mapping. It's called wherever an ISIN has just come back from OpenFIGI in a **non-TR-crypto** context (`OpenFigiIsinConverter.isTrCryptoIsin` guards it): Trade Republic sync, Bourso sync (when the position carries an ISIN), and manual PEA/Compte-Titres transaction entry (`ManualTransactionService.applyInstrumentFields`). A raw broker symbol with no ISIN (Bourso) or a plain ticker typed by hand (manual account/transaction entry, MCP) stays on the generic, ambiguous `getOrCreate` — same as before this feature, unpriced by Yahoo until it's linked some other way.

The V57 migration backfills `yahoo_symbol` for pre-existing stock/ETF rows (all typed `UNKNOWN` — no code set `STOCK`/`ETF` before this change). Its scope is deliberately narrow: `type = 'UNKNOWN'` skips every `resolveCrypto` coin (that method types *all* its rows `CRYPTO`, including PENDING/failed and TR-native ones), and an extra exclusion drops any symbol held or referenced by a **`CRYPTO`-type account**. That last clause matters because exchange (Binance) and on-chain wallet holdings bypass `resolveCrypto` entirely (`upsertHolding` → `getOrCreate`), landing as `UNKNOWN`/no-`coingecko_id` — indistinguishable by type alone from a stock, so without the account-type filter a Binance `LINK`/`APE`/`GALA` would wrongly become Yahoo-priceable on its bare symbol (a *wrong* stock quote, not just a wasted call).

**The crypto CSV import is where refs get filled.** It confirms resolution *before* it commits: for every coin not already settled (`USER`/`WORTHLESS`), `CryptoPreviewResponse.assetChoices` carries an `ImportAssetChoice{symbol, currentStatus, aggregators[]}` — **one picker per aggregator**, each pre-filled with that aggregator's own suggestion and a verify link, plus a **paste-a-link field** as the escape hatch for a coin the searches offered nothing for (a symbol no aggregator knows, or a rate-limited preview): the URL is resolved server-side by whichever aggregator recognises it (`resolveLink`, the same routing the standing card uses) and outranks a picked candidate for that aggregator. The operator confirms, picks another candidate, pastes a link, leaves an aggregator unlinked, marks the coin worthless, or skips it. The coin's **display name** is a separate editable field, seeded from the picked candidate but the operator's to correct (it's not re-derived once picked, and `applyMappings` never overwrites an existing name with a blank one — so a re-map from a nameless source like a Yahoo candidate can't wipe a good label). Accepting the defaults links the coin on every aggregator that recognised it, which is precisely how an asset ends up with a working fallback. The choices ride the import request (`ImportAssetMapping{symbol, action, aggregatorIds, url, name}`) and `execute()` applies them as `USER` via `applyMappings` (no extra round-trip for picked ids; a pasted link is validated with one `fetchById`) **before** the price backfill, so prices are fetched under the right ids from the first import. There is deliberately **no "create without a link"**: a skipped coin imports unpriced as `PENDING` and is re-presented at every import until it's linked or marked worthless — an unlinked asset staying visible is the point.

This is what stops a silent `AUTO` mis-match (e.g. a `META` ticker pinned to the wrong dominant coin) from being frozen in the registry unnoticed; skipped coins import unpriced and are re-presented next time. The DTOs are keyed by `aggregatorKey`, so a new aggregator appears in the wizard with no frontend change (only its optional "verify" URL and display label are looked up from a small frontend map — an unknown key just shows the key and no link).

### Standing mapping / verification (holding detail)

The import preview only surfaces a coin *while* importing. The **standing** counterpart is per-holding and always available: the crypto holding detail (`HoldingDetailModal`) carries an **Aggregator link** card (`AggregatorLinkCard`) that shows the symbol's current resolution status and lets the operator verify or correct it any time — pick a candidate, paste a CoinGecko coin link, mark it worthless, or forget the mapping. The account-detail holdings table badges each crypto row with its status (`AssetStatusBadge`), so an unresolved (`PENDING`) coin is visible at a glance without opening anything.

This is exposed by `AssetController` under `/api/assets`. The registry is **not** member-scoped — `financial_asset` is a global, member-agnostic catalogue (one row per symbol, shared across the family), unlike the account/holding endpoints. Reads are open to any authenticated member; **writes are admin-only** (`SecurityConfig` gates `PUT`/`DELETE /api/assets/**` to `ROLE_ADMIN`), because changing or forgetting a mapping re-values *everyone's* holdings — the frozen permission rule (any member may confirm a `PENDING` from their own import; changing a settled mapping is admin-only). The card hides its editor for non-admins accordingly.

- `GET /api/assets` (any member) → the whole registry (`listAll`), one `AssetResponse` per asset, for the management table.
- `GET /api/assets/{symbol}/candidates` (any member) → the current status, the market-cap suggestion, and every CoinGecko candidate (`previewResolution` — the single-symbol sibling of `previewResolutions` that, unlike it, returns candidates even for a coin already settled, so a standing mapping can always be re-verified). The service resolves across every aggregator now, but **this standing surface still speaks CoinGecko only**: the controller narrows the preview to that block, and `AssetMappingRequest`/`applyUserMapping` stay single-id. Generalising the standing editor to one picker per aggregator (as the import wizard already is) is the next pass — until then, an asset's other refs are filled at import time. Fetched **lazily** by the card (only once the operator opens the editor) to respect the CoinGecko rate limit.
- `PUT /api/assets/{symbol}/mapping` (admin) → apply a mapping: a pasted link (`setManualMapping`) or a picked candidate id (`applyUserMapping`) lands `USER`; `action=WORTHLESS` calls `markWorthless`.
- `DELETE /api/assets/{symbol}` (admin) → `clearMapping` — un-links the symbol (reverts it to `PENDING`, purges its price history) but **keeps the registry row**, so a holding's `asset_id` FK stays valid. A full row `delete` would fail for any held symbol (the FK has no cascade), and the "forget the link" button always runs on a held coin, so it clears rather than deletes.

Two surfaces consume these. **Per-holding**: the `AggregatorLinkCard` appears both in the crypto holding detail (`HoldingDetailModal`, dashboard) and in the buy-in editor (`EditHoldingModal`, account detail). **Registry-wide**: `AssetRegistryModal` — a table of every asset with one column per aggregator (CoinGecko id, CoinMarketCap id, Yahoo symbol) — the column tells at a glance which aggregators can quote the asset, and therefore whether it has a fallback — its status, and its last known EUR value (`lastEurValue`, with a freshness dot) as a quick sanity check that a mapping points at the right coin — shown only for a crypto that actually has a CoinGecko id (an unlinked coin's `lastEurValue` may be a spurious Yahoo-fallback quote on the raw symbol, so it's hidden rather than passed off as validation) — opened from `/accounts` (next to "Add account") and from the admin price-aggregators section; admins confirm an `AUTO` guess in one click or expand a row to the same editor. Editing stays CoinGecko-only here; the CoinMarketCap and Yahoo columns are informational (their refs are filled at import time).

These are the *same* `FinancialAssetService` entry points the import path calls, so a mapping made from a holding and one confirmed during an import are identical (both land `USER`/`WORTHLESS`, and re-pinning to a different id purges + refetches history exactly as above). `HoldingResponse` carries `assetType`/`assetStatus`/`coingeckoId` (read straight off the already-loaded asset), so the badge and the card render without an extra round-trip.
### Caching

`PriceService` maintains a `ConcurrentHashMap<String, CachedPrice>` where the key is the uppercase ticker. Each entry stores the price and the cache timestamp. Entries expire after 900 seconds (15 minutes). On a cache miss, the price is fetched from the provider and cached.

`refreshPrices(Set<String> tickers)` bulk-fetches prices, partitions tickers into crypto and stock sets, calls each provider once, and updates the cache.

Every successful fetch also persists `financial_asset.last_eur_value`/`price_synced_at`, so the latest known price survives a restart (the in-memory cache does not). Nothing reads it back yet — the multi-aggregator fallback chain (step C of the crypto plan) will.

### Currency conversion

`PriceService.toEur(balance, currency, asset)` converts an account balance to EUR:
- If currency is EUR and no asset is set, returns the balance as-is.
- Otherwise, uses the asset (preferred) or the currency code to fetch a price, then multiplies.

The asset path goes straight through `getPriceEur(FinancialAsset)`. A bare currency code (a foreign bank-account currency) and the MCP tool's caller-supplied ticker have no asset in hand, so they go through the `getPriceEur(String)` **seam**: it resolves the symbol to its registered asset — or a transient, non-persisted asset carrying the symbol *and* `yahoo_symbol = symbol` when unregistered — then prices it like any other asset. That `yahoo_symbol` matters: since Yahoo's `canPrice` gates on `yahoo_symbol` (not the raw symbol), these explicit-ticker seams set it so a caller-requested ticker still routes to Yahoo verbatim, exactly as before the gate — whereas a *registry* row is deliberately left without one until resolved, so an unresolved coin no longer wastes a Yahoo call. A fiat currency still has no registry row and no Yahoo quote for the raw code, so it returns `null` → the balance is left unconverted (the pre-existing best-effort behaviour; a dedicated fiat/FX path is future work).

### Scheduler & backfill

`SchedulerService.refreshPrices()` runs every hour (`fixedDelay = 3600000`). It collects all tickers from accounts that have a non-null ticker, then calls `PriceService.refreshPrices()`. This keeps the cache warm for the dashboard.

`PriceBackfillRunner` (boot) backfills each holding ticker's daily history **anchored to its own earliest transaction** (12-month fallback), and the backfill is gap-aware: only the missing tail since the latest stored snapshot is fetched, so a warm restart is a no-op instead of re-downloading whole windows.

### Key files

- `service/PriceService.java` -- Caching, conversion, gap-aware backfill, worthless/EUR handling (routing delegated to `PriceRouter`); `getPriceEur(FinancialAsset)` is the primary entry point, `getPriceEur(String)` a resolve-or-transient seam for callers with only a ticker (currency code, MCP)
- `service/PriceRouter.java` -- Capability + availability routing over the ordered `PriceProviderPort` beans (the cross-aggregator fallback)
- `service/FinancialAssetService.java` -- The aggregator-agnostic resolution engine over `List<AssetResolverPort>`: auto-resolution (`resolveCrypto`/`getOrCreateStock`), multi-aggregator preview (`previewResolutions`/`previewResolution`), `applyMappings`, link pasting, worthless pinning, ref clearing
- `port/AssetResolverPort.java` / `port/AssetCandidate.java` -- The resolution side of an aggregator (search/fetch/URL/getRef/setRef) and the aggregator-neutral candidate shape
- `controller/AssetController.java` -- Standing mapping/verification endpoints (`/api/assets`): candidates, apply mapping, forget
- `dto/AssetResponse.java` / `AssetCandidatesResponse.java` / `AssetMappingRequest.java` -- Standing-mapping DTOs
- `components/shared/AggregatorLinkCard.tsx` / `AssetStatusBadge.tsx` / `AssetRegistryModal.tsx` + `features/assets/` -- Per-holding mapping card, status badge, registry-wide management table, and query hooks (frontend)
- `model/FinancialAsset.java` / `repository/FinancialAssetRepository.java` -- The registry (V51)
- `model/Aggregator.java` / `model/AggregatorSession.java` -- Aggregator identity + encrypted API credentials (V54)
- `service/AggregatorService.java` -- Credential CRUD, encrypt-on-write / decrypt-on-read, `enabledCredentials()` for the adapters
- `controller/AdminAggregatorController.java` -- Admin panel endpoints (`/api/admin/aggregators`): list, toggle, add/enable/delete keys
- `service/SchedulerService.java` -- Hourly price refresh cron
- `adapter/price/CoinGeckoPriceProvider.java` -- CoinGecko HTTP client (prices, search, coin URL, logos, circuit breaker); owns `coingecko_id`
- `adapter/price/CoinMarketCapPriceProvider.java` -- CoinMarketCap HTTP client (id-based quotes, symbol map, info); owns `coinmarketcap_id`; key-only, `SPOT` only
- `adapter/price/YahooFinancePriceProvider.java` -- Yahoo Finance `/v8/finance/chart/{ticker}` + `/v1/finance/search`; owns `yahoo_symbol`
- `port/PriceProviderPort.java` -- The pricing side of an aggregator, asset-typed: `aggregatorKey()`, `capabilities()`, `canPrice(FinancialAsset)`, `isAvailable()`, `getPricesEur(Collection<FinancialAsset>)`/`getHistoricalPricesEur(FinancialAsset,…)`/`getIntradayPricesEur(FinancialAsset,…)`
- `crypto/ImportAssetChoice.java` / `ImportAssetMapping.java` -- Import preview/confirm DTOs, keyed by `aggregatorKey` (one block per aggregator)

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
        PriceRouter waterfall: each provider gets the still-unpriced assets it holds a ref for
                |
                +-- CoinGecko (10): coingecko_id set? + not rate-limited
                |       GET api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur
                |       ...call failed / price missing from the answer? --> asset carries over
                |
                +-- CoinMarketCap (15): coinmarketcap_id set? + has a usable key
                |       GET pro-api.coinmarketcap.com/v2/cryptocurrency/quotes/latest?id=1&convert=EUR
                |
                +-- Yahoo (20): yahoo_symbol set?
                |
                +-- no aggregator holds a ref (or every attempt failed) --> unpriced
                |
                v
        Cache result + persist last_eur_value --> return price

New crypto symbol discovered (TR sync XF000…, imports):
        |
        v
FinancialAssetService.resolveCrypto("TAO")   // discovery shortcut: CoinGecko alone
        |
        v
searchBySymbol("TAO") --> ranked dominant match? --> persist AUTO (coingecko_id only)
                      --> ambiguous/unranked/miss? --> keep PENDING row

Crypto CSV import preview (where the other refs get filled):
        |
        v
FinancialAssetService.previewResolutions({TAO, …})
        |
        +-- for each resolver with isResolutionAvailable():
        |       searchBySymbol("TAO") --> block {aggregatorKey, suggested, candidates}
        |       (CoinMarketCap absent when no API key — no anonymous tier)
        |
        v
Operator picks one id per aggregator --> applyMappings("TAO", {coingecko: "bittensor",
                                                              coinmarketcap: "22974"}, name)
        |
        v
each resolver setRef()s its own column --> USER --> TAO now has a price fallback
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| CoinGecko as first choice (`@Order(10)`) | No key needed (anonymous tier works), batch queries, has history | Making the key-only CoinMarketCap primary |
| Yahoo Finance (unofficial) | Free, covers European tickers (.PA, .AS) | Alpha Vantage (key required, limited) |
| Dynamic `financial_asset` registry | New coins resolve without a code change; ambiguity is surfaced, never guessed | Hardcoded ticker→id maps in each provider (drifted: CoinGecko had 20 coins, Yahoo's skip-list 10) |
| One nullable ref column per aggregator | Exactly one external ref per (asset, aggregator); simple queries; column = the capability | Junction table (rejected 2026-07-08 — adding an aggregator needs code anyway) |
| Capability = own ref non-null; no asset-type gate | An aggregator prices whatever it has an id for — "crypto aggregator" isn't a thing, and typing them would freeze in code what is really a property of their data | `canPrice` gated on `AssetType`, or on a *sibling's* column (rejected: coupling CoinMarketCap to `coingecko_id` breaks it whenever the CoinGecko engine changes) |
| Resolution behind `AssetResolverPort`, one adapter per aggregator | Adding an aggregator = 1 adapter + 1 column + 1 field; the engine/DTOs/frontend loop and don't change | Hardcoding each aggregator in `FinancialAssetService` (what it did through V57: `coinGecko.searchBySymbol`, `setCoingeckoId`, a CoinGecko URL regex) |
| One picker per aggregator at import | An aggregator can only quote an asset it has an id for, so a fallback exists only if the operator can fill several ids — the import is where every coin passes | Auto-filling every aggregator (re-introduces the silent mis-match the preview exists to prevent) |
| Market-cap dominance factor (×5), rank required | A #5 coin vs a #300 clone is safe; #10 vs #14 is not — those wait for the operator. Requiring a rank also stops an unranked aggregator (Yahoo) from ever auto-picking an exchange | Always top-ranked match (mis-maps popular symbol clones); accepting a lone unranked match (no evidence it's the right one) |
| CoinMarketCap by id only | An id addresses one coin and returns one object; `?symbol=` returns every namesake as a list — the exact ambiguity the registry removes | Symbol-based quotes (cheaper to wire, re-introduces collisions) |
| 15-minute cache TTL | Balance between freshness and API rate limits | No cache (too many requests) or 1-hour cache (stale prices) |
| Circuit breaker on 429 (`pausedUntil`, honours `Retry-After`) | One warning per pause window; a dashboard load can't spam a rate-limited API | Retry with backoff (amplifies the burst that caused the 429) |
| Hourly scheduler refresh | Keeps cache warm; ensures dashboard loads fast | Fetch on every dashboard request (slow) |

## Gotchas / Pitfalls

- **Yahoo Finance is unofficial**: The Yahoo Finance API is undocumented and can break or get rate-limited without notice. FX conversion is now applied inside `YahooFinancePriceProvider` using the `{CURRENCY}EUR=X` chart endpoint; `GBp`/`GBX` is treated as `GBP / 100`. If the FX call fails the ticker is omitted from the result map (no fabricated rate) — downstream consumers must tolerate a missing key.
- **CoinGecko rate limits**: the anonymous tier 429s within ~5-6 requests (Cloudflare serves `Retry-After: 60`). The circuit breaker is now **per session (key)**: a 429 pauses only the key that hit it, so the next request rolls over to another enabled key; all calls stop only once every key is paused. Adding one or more Demo keys from the admin panel (**Administration → Price aggregators**) raises the limit to ~100 req/min per key and is the real fix.
- **CoinGecko free history is age-limited**: `market_chart/range` 401s when `from` is older than ~365 days, so historical requests are clamped to 364 days (`MAX_FREE_HISTORY_DAYS`). Older history would need a paid Pro key.
- **Unresolved symbols price as zero, not by guessing**: a `PENDING` crypto has no `coingecko_id` and no `yahoo_symbol` (it's never populated for a `CRYPTO`-typed row outside `resolveCrypto`), so both providers reject it via `canPrice` and it's simply unpriced rather than wasting a Yahoo call on the raw ticker. The fix is linking the coin — during a crypto import, or any time afterwards from the holding detail's **Aggregator link** card (a `PENDING`-badged row in the holdings table flags which coins need it).
- **A manually-typed ticker (no ISIN) stays ambiguous**: an account or transaction ticker entered by hand — or a Bourso position with no ISIN — goes through the generic `FinancialAssetService.getOrCreate`, which never sets `yahoo_symbol` (it can't tell a stock ticker from a crypto one). Such a holding is unpriced until it's re-synced through an ISIN-bearing source, resolved as crypto, or linked manually. This mirrors the pre-existing `PENDING`-crypto UX rather than introducing a new gap.
- **Cache is in-memory only**: prices are lost on restart (the scheduler repopulates within one hour). `financial_asset.last_eur_value` persists the last known price and the registry table displays it, but the *pricing path* still doesn't read it back — a restart re-fetches rather than serving a stale value.
- **A fallback only exists where the operator built one**: an asset carrying a single ref has nothing to fall through to when that aggregator is paused — the router can't invent an id. The registry table's per-aggregator columns are how you spot a coin that's linked on only one. Nothing populates `coinmarketcap_id` automatically: it's filled from the import preview's CoinMarketCap picker, which itself only appears once a CoinMarketCap API key exists (no anonymous tier). No key ⟹ no CMC block ⟹ no CMC refs ⟹ CMC never prices anything — silently, by design.
- **Adding an aggregator, checklist**: adapter implementing both ports (reading/writing *only* its own ref), migration for its ref column **plus** its `aggregator` row (without it, `enabledCredentials` returns an empty Optional and the adapter is off — with no error), and the entity field. The frontend needs nothing, though `VERIFY_URL`/`AGGREGATOR_LABEL` in `AddAccountModal` give it a verify link and a display name.
- **`toEur()` returns raw balance on failure**: If no price is available for a symbol, `toEur()` logs a warning and returns the unconverted balance. This can lead to incorrect dashboard values if a price provider is down.
- **Historical/intraday series use today's FX**: `getHistoricalPricesEur` and `getIntradayPricesEur` fetch the FX rate once per call and apply it to every candle in the series. Per-day FX would multiply API calls ~250× for a one-year backfill with marginal accuracy gain — see [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md) for the trade-off.
- **Snapshots from before the FX fix were wiped**: `PriceFxCleanupRunner` purges `price_snapshot` once at boot (guarded by the `price.fx_fix_cleanup_done` app_setting flag from `V31`) so `PriceBackfillRunner` rebuilds 12 months of history with FX-corrected prices.

## Tests

- `FinancialAssetServiceTest` -- driven against **fake aggregators** (the engine must not know CoinGecko from CoinMarketCap): resolution rules (dominance, rank required, PENDING retry), one preview block per available aggregator, an unavailable aggregator omitted, per-aggregator failure degrading alone, `applyMappings` writing each id into its own column (unknown key ignored), purge-on-replace vs keep-on-fill, `clearMapping`/`markWorthless` dropping every ref, link routed to the aggregator that claims it, `getOrCreateStock` (mint/fill-in/never-clobber-crypto)
- `PriceRouterTest` -- the waterfall: an unavailable primary is skipped pre-call; a primary failing *mid-call* hands only its failed assets to the next aggregator (priced ones never re-requested); an asset mapped only on the failing one goes unpriced; per-provider batching; history walks to the next provider on an empty answer and only to one declaring the capability
- `CoinMarketCapPriceProviderTest` -- `canPrice` gated on its own ref (never a sibling's), id-based quotes/lookups (never `?symbol=`), no-key ⟹ no call at all, `setRef` touching only its own column, breaker roll-over
- `OpenFigiIsinConverterTest` -- ISIN detection + TR crypto ISINs resolved through the product registry
- `YahooFinancePriceProviderTest` -- response parsing, `canPrice` gating on `yahoo_symbol`
- `TradeRepublicSyncServiceTest` -- VWAP dedup, and that a TR-native crypto ISIN routes through `getOrCreate` (not `getOrCreateStock`)
- `BoursoSyncServiceTest` -- an ISIN position registers its Yahoo ticker via `getOrCreateStock`; a raw broker symbol (no ISIN) stays on the generic `getOrCreate`
- `PriceServiceTest` -- the bare-ticker seam (`getPriceEur(String)`/`getIntradayPricesEur(String)`) rides a transient asset carrying `yahoo_symbol` so an explicitly-requested ticker still routes to Yahoo, while a registry row stays gated
- `CoinGeckoPriceProviderTest` -- per-session key header, least-recently-used key rotation, per-session breaker roll-over, anonymous fallback, disabled-aggregator cutoff, all-paused short-circuit
- `AggregatorServiceTest` -- encrypt-on-write / decrypt-on-read, enabled-session filtering

## Links

- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
- Related ADR: [Aggregator credentials schema](../decisions/2026-07-10-aggregator-credentials-schema.md)
- Related feature: [Crypto tracking](./crypto-tracking.md)
- Related feature: [Trade Republic](./trade-republic.md)
