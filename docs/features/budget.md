# Feature: Budget & Cashflow

> Last updated: 2026-06-02

## Context

Picsou tracked *net worth* (balances, goals) but did not pilot *spending*: Enable Banking
synced only account balances, and `Transaction.category` was a free-form string filled by the
Finary import alone. The Budget module turns categorized transactions into the pivot for four
linked views — envelopes, recurring charges, cashflow, and savings allocation — fed by Enable
Banking transaction ingestion with a 100% manual fallback (the module works with no synced bank
at all).

## How it works

Everything hangs off one pivot: the **categorized transaction**. The four views (A/B/C/D) are
aggregations over the `transaction` table sliced by the **pay cycle** and the category
**kind** (`INCOME` / `EXPENSE` / `TRANSFER`).

### Key files

**Foundation (ingestion + categorization)**
- `model/Category.java`, `model/CategorizationRule.java`, `model/BudgetSettings.java`, enum `model/CategoryKind.java`
- `service/budget/CategoryService.java` — CRUD + `seedDefaults(member)`; categories are archived, never deleted
- `service/budget/CategorizationService.java` — `apply(tx)` runs rules on sync; `learnRule(...)` memorizes a rule from a manual categorization; bulk recategorize
- `service/budget/BudgetSettingsService.java` — get/update `cycleStartDay`
- `service/budget/BudgetCycle.java` — pure `CycleRange cycleFor(LocalDate, int cycleStartDay)`, unit-tested on month-edge cases
- `controller/CategoryController.java`, `CategorizationRuleController.java`, `BudgetSettingsController.java`, `TransactionCategorizationController.java`
- Ingestion: `port/BankConnectorPort.java` (`fetchTransactions` + `TransactionData`), `adapter/EnableBankingBankConnector.java`, `service/SyncService.java` (dedup by `(account, externalId)`, then `CategorizationService.apply`)

**A — Envelopes:** `model/Budget.java`, `service/budget/BudgetService.java`, `controller/BudgetController.java`. One monthly cap per category, **no rollover**; `spent`/`remaining`/`percent` computed against the current cycle.

**B — Recurring:** `model/RecurringSeries.java`, `service/budget/RecurringDetectionService.java` (groups by normalized counterparty, ≥3 regular occurrences with stable amount → upsert `SUGGESTED`), `service/budget/RecurringSeriesService.java`, `controller/RecurringController.java` (list, confirm, ignore, manual add, projected calendar).

**C — Cashflow:** no table — `service/budget/CashflowService.java` aggregates by cycle / YTD; `controller/CashflowController.java` `GET /api/cashflow?period=`. `TRANSFER` excluded.

**D — Allocation:** `service/budget/AllocationService.java` — *stock* = current balances grouped by asset class derived from `AccountType`; *flux* = incoming `TRANSFER` transactions per savings/investment account over the period. `controller/AllocationController.java`.

**Frontend:** `features/budget/{api,hooks}.ts` (TanStack Query, cascade invalidations rooted at `['budget']`), `pages/budget/BudgetPage.tsx` + tabs (Overview, Envelopes, Cashflow, Allocation, Recurring, Categorize, Manage). One `Budget` nav item = recap dashboard + drill-down.

### Flow

```
Enable Banking sync ─▶ SyncService.fetchTransactions ─▶ dedup ─▶ persist (isManual=false)
                                                                      │
                                          CategorizationService.apply │ (rule engine)
                                                                      ▼
                              categorized transaction (category_id, counterparty)
                                   │              │              │            │
                              Envelopes        Cashflow      Allocation   Recurring
                              (cycle spent)  (income/exp/net) (stock+flux)  (detect)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Configurable `cycleStartDay` (1–28) | Budgets track the user's pay cycle, not the calendar month | Fixed calendar month |
| Rule engine + learning | Auto-categorize on sync, memorize manual fixes | Manual-only categorization |
| `CategoryKind` pivot (INCOME/EXPENSE/TRANSFER) | Transfers between own accounts must not count as spending/income | Single flat category list |
| Envelopes without rollover | Simpler mental model; one cap per month | Carry-over balances |
| Archive, never delete categories | Preserve history on past transactions | Hard delete |

## Gotchas / Pitfalls

- **Transfers are excluded** from cashflow and envelopes but **feed allocation** — a transfer is not spending, it is a move between your own accounts.
- The cycle is **not** the calendar month. `cycleStartDay` 28 on a short month clamps to the month's last day — see `BudgetCycleTest`.
- Categorization runs at sync time; a category change can trigger a learned `USER` rule, which has priority over `AUTO` rules on the next recategorize.
- Detection needs **≥3 regular occurrences** with a stable amount; one-off charges never become a series.

## Tests

- `BudgetCycleTest` — cycle bounds, short-month clamping
- `CategorizationServiceTest` — rule application + learning
- `RecurringDetectionServiceTest` — true/false positives
- `BudgetServiceTest` — spent vs cap per cycle
- `CashflowServiceTest` — transfer exclusion
- `AllocationServiceTest` — stock + contribution flux
- `SyncService` ingestion tests — dedup + auto-categorization with a mocked `BankConnectorPort`
- Frontend: `features/budget` hooks/utilities via `bunx vitest run`

## Links

- Related ADR: [budget-cycle-and-categorization](../decisions/2026-06-02-budget-cycle-and-categorization.md)
- Updated: [bank-sync](./bank-sync.md) (transaction ingestion now included)
