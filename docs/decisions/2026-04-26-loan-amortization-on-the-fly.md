# ADR: Compute loan amortization schedules on the fly

> Date: 2026-04-26
> Status: ✅ Active

## Context

`LOAN` accounts need a detailed view (Finary-style) that includes a monthly amortization
schedule, a progress bar, total cost breakdown, and an amortization curve. All these values
derive from a small set of inputs: borrowed amount, interest rate, optional monthly payment,
optional insurance/fees, start date, end date.

A typical mortgage runs 240 to 360 monthly installments. We needed to decide whether to persist
the schedule as rows in the database or to recompute it on demand.

## Decision

The amortization schedule is **never persisted**. Each `GET /accounts/{id}/loan-summary` call
recomputes the full schedule from the `Debt` row using the standard French amortization
formula, in `LoanAmortizationService`.

The service also exposes `computeRemainingBalance(debt, asOf)` which is called by
`AccountService.liveBalanceEur(account)` for LOAN accounts. The daily snapshot job thus captures
a fresh remaining capital each day with no extra plumbing.

## Alternatives considered

### Persist the schedule (one row per installment)

- **Pros**: Read-only queries, easy to filter, cheap on subsequent reads.
- **Cons**: 240+ rows per loan, denormalised data that must be rebuilt on every parameter
  change (rate refinance, insurance update, term extension). Two sources of truth (parameters
  vs schedule). Adds a migration and a write path with no real benefit.

### Cache the computed schedule in memory or Redis

- **Pros**: Same denormalisation savings as persistence, but no DB cost.
- **Cons**: Picsou is single-process and self-hosted — there is no Redis. A second cache layer
  (besides the existing 15-min price cache) is overkill given how fast the formula runs (a few
  hundred BigDecimal operations).

### Compute on the fly (chosen)

- **Pros**: Single source of truth = `Debt` row. Any parameter edit immediately reflects in
  the next read. No migration, no cache invalidation. The formula is cheap (< 1 ms for 240
  installments).
- **Cons**: Recomputed on every read. Acceptable: this endpoint is hit only when a user opens
  the loan detail page, not on every dashboard refresh.

## Reasoning

The formula is the source of truth — persisting its output would create a sync problem we do
not have today. The compute cost is negligible compared to the round trip and the JSON payload.
And by reusing `liveBalanceEur` we avoid building a separate scheduler tick for loans.

## Trade-offs accepted

- **No history of "what the schedule used to look like."** If a user reduces the rate next year,
  the past installments shown will reflect the *current* rate, not the rate that was in force
  when the user originally paid them. Finary has the same behaviour and users have not asked
  for the historical view; if they do, we revisit (probably by versioning the `Debt` row).
- **No support for prepayments or rate modulations mid-loan.** The formula assumes a constant
  rate and constant payment. Capturing real prepayments would require a transaction stream
  against the LOAN account; out of scope for this iteration.

## Consequences

- New service `LoanAmortizationService` with three records: `LoanInstallment`, `LoanSummary`,
  `LoanScheduleResponse`. No new repository.
- `AccountService.liveBalanceEur` is branched to call `computeRemainingBalance` for LOAN
  accounts. The daily snapshot job naturally captures the monthly "décote".
- The frontend uses a single TanStack Query hook (`useLoanSummary`) with a 5-minute stale time.
  Cache invalidation only matters when the user edits debt parameters, which already
  invalidates `['loan-summary', id]` in `useUpdateDebtMetadata`.
