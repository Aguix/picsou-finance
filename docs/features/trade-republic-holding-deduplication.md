# Fix: Trade Republic Holding Deduplication

> Last updated: 2026-04-05

## Problem

When syncing Trade Republic accounts, a `DataIntegrityViolationException` was thrown with the error:
```
duplicate key value violates unique constraint "account_holding_account_id_ticker_key"
```

This occurred because multiple ISIN codes (securities identifiers) could convert to the same Yahoo Finance ticker symbol via the `OpenFigiIsinConverter`. When syncing positions, the code would attempt to insert multiple `AccountHolding` records with the same `(account_id, ticker)` combination, violating the database's unique constraint.

### Example scenario
- Trade Republic account has two positions:
  - ISIN: `US0378691033` (Apple Inc. - US listing)
  - ISIN: `IE00B4L5Y983` (Apple Inc. - ISIN for European fund)
- Both convert to ticker: `AAPL`
- Sync tries to insert two holdings with `(account_id=57, ticker=AAPL)`
- Constraint violation occurs

## Solution

Modified `TradeRepublicSyncService.upsertAccount()` to deduplicate holdings by ticker before persisting:

1. **Collect and deduplicate**: Loop through positions, converting each ISIN to a ticker
2. **Aggregate quantities**: When multiple positions map to the same ticker, combine their quantities
3. **Save deduplicated holdings**: Insert only one holding per ticker with the aggregated quantity

### Implementation

- Added a helper record `HoldingAgg` to hold aggregated holding data
- Used `Map.merge()` to combine quantities when the same ticker appears multiple times
- Positions are deduplicated **in-memory before database writes**, avoiding constraint violations

### Key files

- `backend/src/main/java/com/picsou/service/TradeRepublicSyncService.java:285-313` — deduplication logic

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Deduplicate in-memory before saving | Avoids constraint violations and keeps the database clean | Update existing holdings (more complex, slower) |
| Use `Map.merge()` for aggregation | Concise, handles both first occurrence and merges in one pass | Manual `if-put-get` logic (more verbose) |
| Combine quantities on duplicate | Represents the correct semantic (total position across all ISINs) | Keep only first/last quantity (loses information) |

## Gotchas / Pitfalls

- **ISIN → Ticker conversion is not 1:1**: Multiple ISINs can map to the same ticker (e.g., different listings of the same security)
- **Average buy-in on duplicates**: When combining quantities, we keep the `averageBuyIn` from the first position. This is a simplification—a proper weighted average would require more complex logic, but typically duplicates represent the same security at different stages, so this is acceptable
- **Edge case**: If all positions have empty list, no holdings are deleted or saved, preserving any manually-entered holdings

## Tests

- `GoalServiceTest` — existing unit tests pass
- No regression in existing sync flow

## Related

- `OpenFigiIsinConverter` — responsible for ISIN → Yahoo ticker conversion
- `AccountHolding` — database entity with unique constraint on `(account_id, ticker)`
- Sync flow: `TradeRepublicSyncService.sync()` → `upsertAccount()` → `holdingRepository.save()`
