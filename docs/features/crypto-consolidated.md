# Feature: Consolidated Crypto View

> Last updated: 2026-07-17

## Context

Crypto holdings live across several sources — Crypto.com (CSV import), centralized exchanges
(Binance/Kraken), and on-chain wallets (BTC/ETH/SOL). Each source creates its own `CRYPTO` account,
so the same coin held on two platforms showed up as two unrelated positions. This feature pools every
coin across **all** of the member's `CRYPTO` accounts into a single per-coin view (one card per coin,
all platforms & wallets combined), plus a per-account detail view.

It surfaces in two places, no dedicated route:

- **Accounts page, `CRYPTO` filter** — `CryptoPortfolioSection` renders below the account cards. A
  Global ↔ per-exchange selector (persisted in `?account=`) switches between the consolidated view
  and a single account's detail. The import flow can deep-link to a freshly imported account.
- **Account detail page** — for a `CRYPTO` account, `CryptoStatsSection` renders the same per-coin
  cards scoped to that account (rewards detailed by program).

## How it works

Both the single-account (`stats`) and the cross-account (`consolidatedStats`) paths feed the same
`assemble(...)` method, which builds the `CryptoStatsResponse` from an aggregated-holding map keyed by
ticker plus a date-ASC transaction list.

`consolidatedStats(memberId)`:

1. Loads every **crypto-typed** holding across all of the member's accounts in one query
   (`AccountHoldingRepository.findByMemberIdAndAssetType(memberId, CRYPTO)`) — scoping is by the
   asset's `type`, not the account's, so crypto held inside a mixed brokerage account (Trade Republic
   stores its coins as `XF000…` holdings on a `COMPTE_TITRES` account) is pooled too, while that
   account's stock holdings stay out.
2. Aggregates those holding rows by the asset symbol into an `AggHolding`:
   - **quantity** = Σ holding quantities;
   - **average buy-in** = weighted only over the sources that carry a cost basis
     (`Σ(qty×avgBuyIn) / Σ(qty)` over those sources) — Binance reports no cost, so it adds quantity &
     value but not cost;
   - **current price** = the first non-null price seen for the coin;
   - **value-only** = on-chain wallet accounts have **no** `account_holding` (their balance sits in EUR
     on the account, with the coin symbol in `provider`), so their `current_balance` is added under that
     symbol as a value-only contribution (quantity unknown).
3. Pulls every BUY/SELL/REWARD transaction from the accounts that hold crypto (plus any dedicated
   CRYPTO account, to anchor a fully-sold coin's rewards), then **filters** them to the tickers that
   resolve to a CRYPTO asset — so a mixed brokerage account's stock BUY/SELL rows never leak into the
   crypto recap — and groups the rest by ticker.
4. `assemble(...)` iterates the **union** of tickers from holdings and transactions, so a coin held
   without transactions (Binance, wallet, TR) still surfaces with quantity & value, and cost/reward
   timelines exist only where transactions do (Crypto.com today).

The daily market-price series per coin comes from `price_snapshot`, which is keyed by the **asset id**
(FK to `financial_asset` since V85). The tickers seen in the response are resolved to asset ids once
(via `FinancialAssetRepository.findBySymbolIn`) before the per-coin price series are read — no lookup
per coin, and no dependence on any single aggregator.

### Reward yield & per-token donut

- **Reward yield** (`rewardsYieldPct`, per coin and in the totals) reads the free-coin income as an
  interest rate: `totalRewardsEur / totalInvestedEur × 100`. It is `null` when nothing was invested
  (a coin held only from rewards, or a value-only wallet — no denominator).
- **Per-token distribution donut** (frontend-only) splits the portfolio's current value across coins,
  built from the same `AssetStat` list the cards render. It complements the dashboard's *per-account*
  allocation pie, which splits by account rather than by coin. Shown only when ≥ 2 coins carry a
  priced value.

The frontend `CryptoStatsSection` is split into a source-agnostic `CryptoStatsView` (presentational,
takes a `CryptoStatsResponse` — renders totals, the donut, the rewards-by-program bars, and a card per
coin) and the account-bound `CryptoStatsSection` wrapper. Standing coin mappings are **not** managed
here — they live in the Asset registry (`AssetRegistryModal` / `AggregatorLinkCard`), which is
multi-aggregator (CoinGecko / CoinMarketCap / Yahoo), reachable from the Accounts header.

### Key files

- `controller/CryptoController.java` — `GET /api/crypto/stats` (consolidated) + `GET /api/crypto/accounts/{id}/stats` (per-account), both member-scoped
- `crypto/CryptoStatsService.java` — `consolidatedStats()` / `stats()`, shared `assemble()`, `AggHolding`, `rewardYield()`
- `crypto/CryptoStatsResponse.java` — `AssetStat` / `Totals` (both carry `rewardsYieldPct`), event & series records
- `repository/TransactionRepository.java` — `findByAccountIdInAndTxTypeInOrderByDateAsc`
- `pages/accounts/CryptoPortfolioSection.tsx` — Global ↔ per-account selector (`?account=`), rendered under the Accounts `CRYPTO` filter
- `pages/accounts/AccountDetailPage.tsx` — renders `CryptoStatsSection` for a `CRYPTO` account
- `components/shared/CryptoStatsSection.tsx` — `CryptoStatsView` (presentational) + `CryptoStatsSection`; per-token donut + reward-yield display
- `features/crypto/{api,hooks}.ts` — `stats` / `consolidatedStats`, `useCryptoStats` / `useConsolidatedCryptoStats`
- `features/crypto/labels.ts` — reward-program labels & chart colours

### Flow

```
GET /api/crypto/stats (member-scoped)
        |
        v
all CRYPTO accounts ──► aggregate account_holding by asset symbol (AggHolding)
        |                   wallets (no holding) ──► value-only by provider symbol
        v
union BUY/SELL/REWARD transactions ──► group by ticker
        |
        v
resolve tickers → asset ids ──► per-coin price series from price_snapshot (id-keyed)
        |
        v
assemble(): union(tickers) ──► CryptoStatsResponse (totals + per-coin AssetStat + yield)
        |
        v
CryptoStatsView (totals · per-token donut · rewards bars · per-coin cards)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Pool by `asset.type == CRYPTO`, not `account.type == CRYPTO` | Crypto held in a mixed brokerage account (Trade Republic) is real crypto; the generic `financial_asset` registry already carries the type | Scope to CRYPTO accounts (misses TR crypto) |
| Reuse `CryptoStatsResponse` + a shared `assemble()` | One rendering path for per-account and consolidated; no DTO/UI duplication | A separate consolidated DTO + component |
| Weighted average buy-in over cost-bearing sources only | Binance/wallets report no cost basis; weighting only the known costs avoids inventing one | Treat missing cost as 0 (understates the average) |
| Wallet value added as value-only (no quantity) | On-chain wallet accounts store EUR balance, not a per-coin holding | Re-deriving on-chain quantities (out of scope, needs price-at-sync) |
| Price series read by asset id (resolved once) | `price_snapshot` is id-keyed since V85; keeps the service asset-based, not symbol-based | Re-query per ticker string (drifts from the id-based schema) |
| Reward yield in the backend service | Same figure feeds the per-account and consolidated views; front stays a pure renderer | Compute it in the component (duplicated across the two views) |
| Per-token donut on the frontend | The per-coin `currentValueEur` is already in the response; no extra endpoint | A backend distribution DTO |
| Surfaced in the Accounts `CRYPTO` filter (no `/crypto` route) | Keeps the crypto recap next to the accounts it summarises | A dedicated sidebar page |
| No coin-mapping UI here | Mappings are multi-aggregator and live in the Asset registry (F) | Re-introduce the old CoinGecko-only mappings dialog |

## Gotchas / Pitfalls

- **Cost basis is approximate when sources mix.** `costBasis = quantity × weightedAvgBuyIn`, i.e. the
  weighted average is extrapolated to the whole position (including Binance/wallet quantity that had no
  recorded cost). PnL is therefore indicative, not accounting-grade, for mixed-source coins.
- **Wallet quantity is unknown.** A wallet contributes EUR value only, so a coin's consolidated
  `quantity` can understate the true amount while its `currentValueEur` stays correct.
- **Cost / buy-sell / reward timelines need transactions.** Only Crypto.com provides them today;
  exchange/wallet-only coins render with metrics but no cost-vs-price or accumulation chart.
- **Reward yield needs invested capital.** `rewardsYieldPct` is `null` for reward-only coins and
  value-only wallets (division by zero avoided), shown as `—`.
- **No coin logos.** The consolidated view identifies coins by ticker badge; the old CoinGecko logo
  fetch was dropped to keep the service aggregator-agnostic.
- **Scoping is by asset type, not account type.** The consolidated view pools every
  `asset.type == CRYPTO` holding across all accounts, so crypto held inside a mixed brokerage account
  (Trade Republic `XF000…` on a `COMPTE_TITRES` account) is included, while that account's stock
  holdings and stock BUY/SELL rows are filtered out. The correctness of this hinges on
  `financial_asset.type` being set to `CRYPTO` for coins (done at resolution — auto or manual); a coin
  left `UNKNOWN`/`PENDING` won't be pooled until its type is resolved.
- **Per-account crypto detail is CRYPTO-accounts only.** The Global ↔ per-account selector lists
  `AccountType.CRYPTO` accounts, so a mixed brokerage account's crypto shows in the **global** recap
  but has no dedicated per-account crypto tab (its detail page shows the mixed holdings table instead).
- **Demo mode** returns a populated stub (`demo/data/crypto-stats.ts`) for `/crypto/stats` and
  `/crypto/accounts/6/stats`, so the recap (donut, charts, rewards, yield) renders without a backend.
- **i18n.** Top-level `crypto.*` and `cryptoStats.*` namespaces exist in both locales; components also
  pass French inline defaults to `t(key, fallback)`.

## Tests

- `CryptoStatsServiceTest` — consolidation pools the same coin across Crypto.com + Binance + wallet
  (quantity, weighted avg, value incl. wallet EUR); a holding-only coin surfaces without transactions;
  crypto held in a mixed brokerage account is pooled while its stock holdings/BUY rows are excluded;
  single-account `stats` still works through the shared assembler; reward yield = rewards / invested.
- `CryptoStatsSection.test.tsx` — totals, a card per coin, per-program reward badges, `—` for a
  reward-only coin, and the cost-vs-price overlay for coins with a timeline.

## Links

- Related feature: [Crypto tracking](./crypto-tracking.md), [Price service](./price-service.md),
  [Live prices (holdings)](./live-prices-holdings.md)
