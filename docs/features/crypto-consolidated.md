# Feature: Consolidated Crypto View

> Last updated: 2026-07-02

## Context

Crypto holdings live across several sources — Crypto.com (CSV import), centralized exchanges
(Binance/Kraken), and on-chain wallets (BTC/ETH/SOL). Each source creates its own `CRYPTO` account,
so the same coin held on two platforms showed up as two unrelated positions. This feature pools every
coin across **all** of the member's `CRYPTO` accounts into a single per-coin view (one chart per coin,
all platforms & wallets combined), reachable from a dedicated **Crypto** sidebar page (`/crypto`).

## How it works

The consolidated view reuses the per-account stats pipeline. `CryptoStatsService` was refactored so
both the single-account (`stats`) and the cross-account (`consolidatedStats`) paths feed the same
`assemble(...)` method, which builds the `CryptoStatsResponse` from an aggregated-holding map keyed by
ticker plus a date-ASC transaction list.

`consolidatedStats(memberId)`:

1. Loads all of the member's accounts and keeps `type == CRYPTO`.
2. Aggregates `account_holding` rows by ticker into an `AggHolding`:
   - **quantity** = Σ holding quantities;
   - **average buy-in** = weighted only over the sources that carry a cost basis
     (`Σ(qty×avgBuyIn) / Σ(qty)` over those sources) — Binance reports no cost, so it adds quantity &
     value but not cost;
   - **current price** = the first non-null price seen for the coin;
   - **value-only** = on-chain wallet accounts have **no** `account_holding` (their balance sits in EUR
     on the account, with the coin symbol in `provider`), so their `current_balance` is added under that
     symbol as a value-only contribution (quantity unknown).
3. Pulls every BUY/SELL/REWARD transaction across those accounts in one query and groups by ticker.
4. `assemble(...)` iterates the **union** of tickers from holdings and transactions, so a coin held
   without transactions (Binance, wallet) still surfaces with quantity & value, and cost/reward
   timelines exist only where transactions do (Crypto.com today).

The frontend `CryptoStatsSection` was split into a source-agnostic `CryptoStatsView` (presentational,
takes a `CryptoStatsResponse`) and the existing account-bound `CryptoStatsSection` wrapper. The new
`CryptoOverviewPage` calls `useConsolidatedCryptoStats()` and renders `CryptoStatsView` with a
consolidated title; the per-account detail page is unchanged.

### Key files

- `controller/CryptoController.java` — `GET /api/crypto/stats` (consolidated, member-scoped)
- `crypto/CryptoStatsService.java` — `consolidatedStats()`, shared `assemble()`, `AggHolding`
- `repository/TransactionRepository.java` — `findByAccountIdInAndTxTypeInOrderByDateAsc`
- `pages/crypto/CryptoOverviewPage.tsx` — the `/crypto` page
- `components/shared/CryptoStatsSection.tsx` — `CryptoStatsView` (presentational) + `CryptoStatsSection`
- `features/crypto/{api,hooks}.ts` — `consolidatedStats` / `useConsolidatedCryptoStats`

### Flow

```
GET /api/crypto/stats (member-scoped)
        |
        v
all CRYPTO accounts ──► aggregate account_holding by ticker (AggHolding)
        |                   wallets (no holding) ──► value-only by provider symbol
        v
union BUY/SELL/REWARD transactions ──► group by ticker
        |
        v
assemble(): union(tickers) ──► CryptoStatsResponse (totals + per-coin AssetStat)
        |
        v
CryptoStatsView (same rendering as the per-account section)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Reuse `CryptoStatsResponse` + a shared `assemble()` | One rendering path for per-account and consolidated; no DTO/UI duplication | A separate consolidated DTO + component |
| Weighted average buy-in over cost-bearing sources only | Binance/wallets report no cost basis; weighting only the known costs avoids inventing one | Treat missing cost as 0 (understates the average) |
| Wallet value added as value-only (no quantity) | On-chain wallet accounts store EUR balance, not a per-coin holding | Re-deriving on-chain quantities (out of scope, needs price-at-sync) |
| Dedicated `/crypto` sidebar page | Most visible & extensible home for the consolidated view | A dashboard block / a Sync tab |

## Gotchas / Pitfalls

- **Cost basis is approximate when sources mix.** `costBasis = quantity × weightedAvgBuyIn`, i.e. the
  weighted average is extrapolated to the whole position (including Binance/wallet quantity that had no
  recorded cost). PnL is therefore indicative, not accounting-grade, for mixed-source coins.
- **Wallet quantity is unknown.** A wallet contributes EUR value only, so a coin's consolidated
  `quantity` can understate the true amount while its `currentValueEur` stays correct.
- **Cost / buy-sell / reward timelines need transactions.** Only Crypto.com provides them today;
  exchange/wallet-only coins render with metrics but no cost-vs-price or accumulation chart.
- **Demo mode** returns an empty consolidated payload (`assets: []`) — there is no transaction-backed
  crypto in the demo dataset, so the page shows the empty state.
- **i18n.** New `nav.crypto*` keys exist in both locales; page strings (`crypto.*`) ship with French
  inline defaults and English translations.

## Tests

- `CryptoStatsServiceTest` — consolidation pools the same coin across Crypto.com + Binance + wallet
  (quantity, weighted avg, value incl. wallet EUR); a holding-only coin surfaces without transactions;
  single-account `stats` still works through the shared assembler.
- `CryptoStatsSection.test.tsx` — unchanged; the presentational split keeps the per-account rendering.

## Links

- Related feature: [Multi-exchange crypto CSV import](./crypto-import.md),
  [Crypto tracking](./crypto-tracking.md), [Price service](./price-service.md)
