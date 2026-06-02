# Feature: Live Prices in Holdings

> Last updated: 2026-05-19

## Context

Holdings (PEA, Compte-Titres, Crypto) display prices that are only updated during a full sync. When a user navigates to an account detail page, the displayed prices may be stale — sometimes hours old. The backend already exposes `GET /api/prices?tickers=...` via `PriceController` which refreshes the cache and returns live EUR prices. This feature integrates those live prices into the holdings table on page navigation, and propagates live portfolio values (with PnL) to Goals progress, Dashboard distribution, and the AccountDetail balance card.

## How it works

The frontend fetches holdings and live prices in parallel, then merges them client-side. The backend also computes live values server-side for Goals and Dashboard.

### Frontend data flow (holdings table)

```
User navigates to account detail
        |
        v
useHoldingsWithLivePrices(id)
        |
        +-- GET /api/accounts/{id}/holdings  (DB prices, may be stale)
        |
        +-- GET /api/prices?tickers=BTC,ETH,IWDA.AS  (live prices from providers)
        |
        v
Merge: override currentPrice with live price
Recalculate: currentValueEur, pnlEur, pnlPercent
        |
        v
HoldingsTable renders with live prices
```

If the prices API fails (network error, provider down), the hook falls back to the DB prices gracefully.

### Live-price recompute formula (single source of truth)

Both `usePortfolio` (portfolio view across all accounts) and `useHoldingsWithLivePrices` (single account holdings table) share a single helper, `recomputeWithLivePrice`, in `frontend/src/features/accounts/hooks.ts`:

```
costBasisEur    = quantity * averageBuyIn      (null if averageBuyIn unknown)
currentValueEur = quantity * livePrice
pnlEur          = currentValueEur - costBasisEur
pnlPercent      = pnlEur / costBasisEur * 100  (null if costBasisEur == 0)
```

All four numbers are derived from the same `livePrice` snapshot, so the header badge (`pnlPercent`), Gain/Loss display (`pnlEur`), and total value (`currentValueEur`) cannot drift out of sync with each other.

The previous implementation in `usePortfolio` updated only `valueEur`/`pnlEur` via delta-add (`l.pnlEur + (newVal - oldVal)`) and left `pnlPercent` at the backend's stored ratio, producing badge-vs-display incoherence on every live-price refresh.

### Frontend data flow (AccountDetail balance)

For holding accounts (PEA, COMPTE_TITRES, CRYPTO), the balance card shows the live total from holdings with live prices, not the stored `account.currentBalanceEur`:

```
liveTotal = SUM(holding.currentValueEur)  // from merged holdings with live prices
displayBalance = showHoldings && holdings.length > 0 && liveTotal > 0
    ? liveTotal
    : account.currentBalanceEur           // fallback for non-holding accounts
```

### Backend data flow (Goals & Dashboard)

The backend computes live balance via `AccountService.liveBalanceEur()`:

```
AccountService.liveBalanceEur(account)
        |
        +-- Load holdings for account
        |
        +-- If no holdings: return stored balance converted to EUR
        |
        +-- If holdings: for each holding:
        |       +-- priceService.getPriceEur(ticker)  (live cache, 15-min TTL — EUR-denominated)
        |       +-- if no live price: skip the holding (no fallback to stored native price)
        |       +-- accumulate qty * livePrice
        |
        v
Return live portfolio value in EUR
```

Used by:
- `GoalService.toProgressResponse()` — sums `liveBalanceEur()` across linked accounts for `currentTotal`
- `DashboardService.buildDistribution()` — uses live values from pre-loaded `holdingsByAccount` map for distribution percentages
- `DashboardService.getDashboard()` — already computed live total/invested inline (pre-dates `liveBalanceEur()`)

### Historical net-worth chart (`HistoryService.buildHistory`)

For each past date, both `total` and `invested` are read from `balance_snapshot` and forward-filled per account from the latest row on or before that date. Loans contribute their negative balance to `total` and zero to `invested`. Today's point is replaced with live values from `liveBalanceEur()` and `calculateInvestedAmount()` so intraday changes are visible immediately. The `invested_amount` column (added in V18, `NOT NULL`) is written by both the daily scheduler and every sync path via `AccountService.upsertSnapshot`.

### Key files

- `frontend/src/features/accounts/api.ts` — `prices(tickers)` API function
- `frontend/src/features/accounts/hooks.ts` — `useHoldingsWithLivePrices(id)` hook
- `frontend/src/pages/accounts/AccountDetailPage.tsx` — uses the hook; `displayBalance` for holding accounts
- `backend/src/main/java/com/picsou/service/AccountService.java` — `liveBalanceEur()` method
- `backend/src/main/java/com/picsou/service/GoalService.java` — uses `liveBalanceEur()` in `toProgressResponse()`
- `backend/src/main/java/com/picsou/service/DashboardService.java` — `buildDistribution()` uses live values from `holdingsByAccount`

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two separate API calls (holdings + prices) | Clean separation of concerns; prices API is reusable; backend unchanged | Backend-side price refresh in `getHoldings()` (slower page load, couples concerns) |
| Client-side merge | No backend change needed; works with existing `HoldingResponse` shape | New dedicated endpoint returning enriched holdings (more backend work) |
| Graceful degradation on price failure | Better UX than showing errors for non-critical price data | Throwing error / blocking page render |

## Gotchas / Pitfalls

- **Prices are not persisted**: Live prices are only used for display. The DB `current_price` in `account_holding` is not updated — that still happens during sync.
- **`useAccountHoldings` still exists**: The old hook is kept for the `usePortfolio` hook which fetches holdings for all accounts (portfolio view). Switching it to live prices would trigger many price API calls at once.
- **No polling**: Prices refresh only on navigation (TanStack Query stale time of 2 min). There is no auto-refresh or polling interval.
- **Yahoo Finance is unofficial**: See [price-service.md](./price-service.md) gotchas. If Yahoo is down, the live-price API returns no entry for that ticker and the frontend / `liveBalanceEur` show `null` for the affected rows rather than stale DB values — this is intentional after [ADR 2026-05-19](../decisions/2026-05-19-yahoo-fx-conversion.md): the stored `AccountHolding.currentPrice` has no currency tag and cannot be trusted as EUR.
- **No fallback to stored native price**: `AccountService.toHoldingResponse` returns `currentValueEur = pnlEur = pnlPercent = null` when the live EUR price is missing. The frontend `recomputeWithLivePrice` helper already tolerates `null` live prices.
- **`AccountService.toResponse()` uses stored balance**: This is intentional — it returns `currentBalanceEur` (stale but fast). Use `liveBalanceEur()` when you need the current portfolio value with PnL.
- **`liveBalanceEur()` triggers price lookups**: Each call fetches holdings then queries `PriceService` per ticker. Don't call in tight loops. The Dashboard pre-loads holdings into a map to avoid N+1; Goals calls it per-account in the goal's account list (typically small).
- **`AccountDetailPage.displayBalance` only applies to holding accounts with live data**: If holdings are empty or `liveTotal == 0`, it falls back to `account.currentBalanceEur`. Non-holding accounts always show the stored balance.

## Tests

- Manual: navigate to a PEA/CT/Crypto account, verify `/api/prices` call in network tab and live prices in table
- Manual: navigate to a checking/savings account, verify no `/api/prices` call

## Links

- Related feature: [Price Service](./price-service.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
