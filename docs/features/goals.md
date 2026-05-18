# Feature: Goals

> Last updated: 2026-05-18 (target trajectory overlay)

## Context

Picsou lets users define savings goals with a target amount and deadline. Goals are linked to one or more accounts (M:N relationship). Progress is computed from the current balances of linked accounts compared to the target. Monthly tracking shows how much has been saved each month versus how much is needed, with optional per-month overrides.

## How it works

### Goal-Account relationship

A `Goal` has a M:N relationship with `Account` via the `goal_account` join table. An account can belong to multiple goals, and a goal can have multiple accounts. When progress is calculated, the balances of all linked accounts are summed.

### Progress calculation

`GoalService.toProgressResponse()` computes:

- **currentTotal**: Sum of `liveBalanceEur()` across all linked accounts. For holding accounts, this uses live prices from `PriceService` (with PnL). For cash accounts, falls back to stored balance converted to EUR.
- **percentComplete**: `(currentTotal / targetAmount) * 100`, rounded to 4 decimal places.
- **monthsLeft**: `ChronoUnit.MONTHS.between(today, deadline)`, minimum 0.
- **monthlyNeeded**: `(target - currentTotal) / monthsLeft`. If deadline has passed, the entire remaining amount is the monthly need.
- **avgMonthlyContribution**: Average monthly balance increase over the last 3 months across all linked accounts, computed from `BalanceSnapshot` history. Returns `null` if no snapshot history exists yet. Displayed in the UI but no longer drives `isOnTrack`.
- **isOnTrack**: `Σ effective(past months) >= Σ objective(past months)`. `effective` = `manualActual ?? snapshot-delta` (months with neither are skipped). `objective` = `override ?? monthlyNeeded`. "Past" = strictly before the current month (current month is in progress). Returns `true` when the goal has no `createdAt`, no past month, or no past month with data (benefit of the doubt).

### Monthly tracking

`GoalService.getMonthlyEntries()` generates a month-by-month breakdown from goal creation to deadline. For each month:

- **objective**: The auto-computed `monthlyNeeded`.
- **actual**: The real balance delta for that month (from snapshots: end-of-month balance minus end-of-previous-month balance). `null` for future months.
- **manualActual**: A manually entered contribution amount (from `GoalManualContribution`). Takes precedence over computed actual.
- **override**: A per-month override for the objective (from `GoalMonthOverride`). Stored but tracked alongside the auto-computed value.
- **effective**: `manualActual` if set, otherwise `actual`.

### Overrides and manual contributions

Two separate override mechanisms:

- **GoalMonthOverride**: Overrides the monthly savings *objective* for a specific month. Useful when the user plans to save more or less than the computed target.
- **GoalManualContribution**: Overrides the monthly savings *actual* for a specific month. Useful when the user wants to track contributions that don't appear in account balances (e.g. cash savings).

### Key files

- `service/GoalService.java` -- Business logic: CRUD, progress calculation, monthly tracking, overrides
- `controller/GoalController.java` -- REST endpoints under `/api/goals/`
- `model/Goal.java` -- JPA entity: name, targetAmount, deadline, M:N accounts
- `model/GoalMonthOverride.java` -- Per-month objective override (goal_id, yearMonth, amount)
- `model/GoalManualContribution.java` -- Per-month actual override (goal_id, yearMonth, amount)
- `repository/GoalRepository.java` -- `findAllWithAccounts()` for eager fetching
- `repository/GoalMonthOverrideRepository.java` -- Override lookup by goal + month
- `repository/GoalManualContributionRepository.java` -- Contribution lookup by goal + month
- `pages/goals/GoalsPage.tsx` -- Goal list with cards, CRUD dialog, status badges, account chips
- `pages/goals/GoalCalendarPage.tsx` -- Monthly calendar view with donut rings, overrides, manual contributions

### Flow

```
Create goal (name, targetAmount, deadline, accountIds)
        |
        v
GoalService.create() --> save Goal with linked accounts
        |
        v
GoalService.toProgressResponse()
        |
        +-- sum liveBalanceEur() per account --> currentTotal
        +-- compute monthsLeft, monthlyNeeded
        +-- calculateAvgMonthlyContribution() from snapshots
        +-- determine isOnTrack
        |
        v
Get monthly entries:
        |
        v
GoalService.getMonthlyEntries(goalId)
        |
        +-- for each month from creation to deadline:
        |       +-- calculateActualForMonth() from snapshots
        |       +-- lookup GoalManualContribution
        |       +-- lookup GoalMonthOverride
        |       +-- build GoalMonthEntryResponse
        |
        v
Set month override:
GoalService.setMonthOverride(goalId, yearMonth, amount)
        --> upsert GoalMonthOverride
        --> return updated entry
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| M:N goal-account relationship | A savings goal often spans multiple accounts (checking + savings + PEA) | One account per goal (too restrictive) |
| Snapshot-based actual calculation | Uses existing BalanceSnapshot data; no need for a separate transaction import | Dedicated savings transaction table |
| Separate override + manual contribution | Different semantics: override changes the target, manual contribution changes the actual | Single override field (loses information) |
| 3-month average for `avgMonthlyContribution` | Short enough to reflect recent behavior, long enough to smooth out noise | 6-month or 12-month average (too slow to reflect changes) |
| `isOnTrack` from cumulative past months | Single source of truth with the `/goals/:id/calendar` view; lets the user influence the badge indirectly via overrides + manual contributions | Snapshot-based 3-month average (decoupled from what the calendar shows; could not be influenced by user adjustments) |
| `null` for no history | Distinguishes "no data yet" from "zero contribution"; `isOnTrack` treats null as "benefit of the doubt" | Return zero (would mark new goals as "not on track") |
| `liveBalanceEur()` for currentTotal | Holding accounts show real portfolio value with PnL, not stale sync-time balance | `AccountResponse.currentBalanceEur` (does not reflect live prices) |
| `<Badge variant="secondary">` for account chips | Uses theme semantic tokens (luma preset); consistent with rest of the UI | Per-account pastel `<span>` with `style={{ background: a.color }}` |
| `<Badge variant="default">` for achieved/on track status | Solid primary color; visible and unambiguous | Pastel `bg-green-500/10` (barely visible, not theme-aware) |

## Gotchas / Pitfalls

- **Accounts can belong to multiple goals**: If an account is linked to two goals, its full balance counts toward both goals' `currentTotal`. There is no "partial allocation."
- **Monthly actual is computed from snapshots, not transactions**: The actual savings for a month is the delta between end-of-month snapshot balances. If snapshots are missing (e.g. new account, no sync), that month will have `null` actual.
- **Override does not recalculate monthlyNeeded**: Setting a month override changes the display value for that month but does not affect the computed `monthlyNeeded`. The auto-computed objective is always based on `(target - current) / monthsLeft`.
- **Goal creation to deadline range**: `getMonthlyEntries()` iterates from the goal's `createdAt` month to the deadline month. If the goal was created mid-month, the first month's actual may be partial.
- **`findAllWithAccounts()` uses a custom query**: Goals are fetched with their accounts eagerly loaded to avoid N+1 queries during progress calculation.

## Tests

- `GoalServiceTest` -- unit tests for progress calculation, monthly entries, overrides, edge cases (deadline passed, no history)

## Frontend notes

- **Account chips** use `<Badge variant="secondary">` — theme-aware, no per-account color. The `ACCOUNT_COLORS` palette is still used elsewhere (ColorPicker, DistributionPie, AccountCard, FinaryTab) but not in goal cards.
- **Status badges**: achieved/on track use `variant="default"` (primary), behind uses `variant="destructive"`, waiting uses `variant="secondary"`.
- **Calendar badges**: "manu." uses `variant="secondary"`, "modif." uses `variant="outline"` — no raw Tailwind color overrides.
- **Icons**: `TrendingUp`/`TrendingDown` come from `lucide-react` (not HugeIcons) in the goals pages.
- **Goal detail chart**: `GoalDetailModal` reuses the shared `NetWorthChart` with the optional `target` prop. The chart draws a dashed `var(--chart-3)` line from `(goal.createdAt, 0)` to `(goal.deadline, goal.targetAmount)`, interpolated linearly per visible point. When the `target` prop is set, history is also cropped on the left to `target.startDate` -- pre-goal account activity isn't "savings progress" and would make the chart misleading. On the `ALL` range, the X axis is stretched on the right up to the deadline so the projection beyond today is visible. The `target` prop is opt-in: Dashboard / Accounts / AccountDetail don't pass it and remain unchanged.

## Links

- Related feature: [Bank sync](./bank-sync.md) (provides balance snapshots)
- Related feature: [Price service](./price-service.md) (EUR conversion for account balances)
