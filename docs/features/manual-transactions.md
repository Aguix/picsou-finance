# Feature: Manual Transactions

> Last updated: 2026-04-21

## Context

Picsou supports syncing transactions from external sources (Finary, bank connectors, Trade Republic, crypto exchanges). This feature adds the ability to manually record any transaction — including on accounts that are also synced — and derives balance/holdings from those transactions automatically.

## How it works

### DB schema (V24 migration)

Five new columns on the `transaction` table (all nullable or defaulted, backward-compatible):

```sql
ALTER TABLE transaction
  ADD COLUMN is_manual      BOOLEAN       NOT NULL DEFAULT FALSE,
  ADD COLUMN tx_type        VARCHAR(20)   NULL,  -- DEPOSIT | WITHDRAWAL | BUY | SELL | DIVIDEND | FEE
  ADD COLUMN ticker         VARCHAR(30)   NULL,
  ADD COLUMN quantity       NUMERIC(20,8) NULL,
  ADD COLUMN price_per_unit NUMERIC(20,8) NULL;

CREATE INDEX idx_transaction_account_manual ON transaction(account_id, is_manual);
```

Existing synced transactions have `is_manual = false`. The original `type` column (Finary raw category string) is untouched.

### Transaction types

`TransactionType` enum: `DEPOSIT`, `WITHDRAWAL`, `BUY`, `SELL`, `DIVIDEND`, `FEE`.

- Cash accounts (CHECKING, SAVINGS, LEP, OTHER): use DEPOSIT / WITHDRAWAL. The `amount` field is signed (positive = deposit, negative = withdrawal).
- Investment accounts (PEA, COMPTE_TITRES, CRYPTO): use BUY / SELL. The `amount` is signed (negative for BUY, positive for SELL, reflecting cash flow).

### Balance derivation (cash accounts)

When a manual transaction is added or deleted on a cash account, `ManualTransactionService` recomputes `account.currentBalance` as the sum of all transaction amounts via a single aggregate query (`sumAmountByAccountId`). It then calls `FinaryPersistenceHelper.reconstructSnapshotsFromDb()` to rebuild the balance history from scratch.

### Holdings derivation (investment accounts)

`HoldingComputeService.recomputeHoldings(account)` is called after every manual transaction add/delete on a PEA, COMPTE_TITRES, or CRYPTO account:

1. Fetches all BUY/SELL transactions for the account, ordered by date ASC.
2. Groups by ticker: `net quantity = Σ(BUY qty) − Σ(SELL qty)`.
3. Computes `averageBuyIn` as VWAP across all BUY transactions for each ticker.
4. Upserts `AccountHolding` for tickers where `qty > 0`.
5. Deletes `AccountHolding` for tickers where `qty ≤ 0`.

All existing holdings are loaded upfront (one query) to avoid N+1 lookups.

### Manual transactions survive re-syncs

All sync services (`FinaryPersistenceHelper`, `BoursoSyncService`) now call `transactionRepository.deleteByAccountIdAndIsManualFalse(accountId)` instead of `deleteByAccountId(accountId)`. Manual transactions are preserved across any sync.

### REST endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/accounts/{id}/transactions` | Add a manual transaction (returns 201) |
| `DELETE` | `/api/accounts/{id}/transactions/{txId}` | Delete a manual transaction (returns 204) |

`DELETE` validates that the transaction is manual (`isManual = true`). Synced transactions cannot be deleted via this endpoint.

### Frontend

`AddTransactionModal` is account-type-aware:

**Cash accounts (CHECKING, SAVINGS, LEP, OTHER):**
- Date, DEPOSIT/WITHDRAWAL toggle, Description, Amount (always positive — toggle sets sign)

**Investment accounts (PEA, COMPTE_TITRES, CRYPTO):**
- Date, BUY/SELL toggle, Ticker, Name (auto-filled from existing holdings if ticker matches), Quantity, Price per unit, Total (read-only)

The Transactions list shows a "Manuel" badge on manual entries and a delete button (only for manual entries).

After submit, `useAddTransaction` / `useDeleteTransaction` hooks invalidate the `transactions`, `history`, `account`, and `dashboard` queries.

### Key files

| File | Role |
|------|------|
| `db/migration/V24__manual_transactions.sql` | Schema extension |
| `model/TransactionType.java` | Enum (DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE) |
| `service/HoldingComputeService.java` | Derives holdings from BUY/SELL transactions |
| `service/ManualTransactionService.java` | Orchestrates add/delete + re-derivation |
| `controller/AccountController.java` | POST/DELETE `/accounts/{id}/transactions` |
| `repository/TransactionRepository.java` | `deleteByAccountIdAndIsManualFalse`, `sumAmountByAccountId`, `findByAccountIdAndTxTypeInOrderByDateAsc` |
| `frontend/src/components/shared/AddTransactionModal.tsx` | Account-type-aware form modal |
| `frontend/src/components/shared/TransactionsList.tsx` | Manuel badge + delete button |
| `frontend/src/features/accounts/hooks.ts` | `useAddTransaction`, `useDeleteTransaction` |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Derive balance from transactions | Keeps single source of truth; avoids divergence between manual entries and computed balance | Letting users set balance directly (leads to inconsistency with transactions) |
| `is_manual` flag on transaction | Minimal schema change; syncs can cleanly skip manual rows | Separate `manual_transaction` table (more joins, more complex) |
| Delete-synced-rows-except-manual on re-sync | Manual data survives any number of re-syncs | Timestamped merge (more complex, edge cases with overlapping date ranges) |
| VWAP for averageBuyIn | Standard financial convention for cost basis | FIFO (more complex, requires ordered lot tracking) |

## Gotchas / Pitfalls

- **Investment account balance is NOT recomputed** from manual transactions. Only the holdings (positions) are derived. The account's `currentBalance` is set by the price scheduler (qty × live price). This is intentional for investment accounts.
- **Synced transactions cannot be deleted**: The DELETE endpoint checks `isManual`. Attempting to delete a synced transaction returns 403.
- **Holdings recomputation is full**: Every add/delete triggers a full re-derivation for that account (all tickers). This is fast in practice since investment accounts rarely have hundreds of tickers.
- **Auto-generated description for investment transactions**: If the user leaves the Name field blank, the description defaults to "Achat {TICKER}" or "Vente {TICKER}".

## Tests

- `HoldingComputeServiceTest` — 9 unit tests covering BUY-only, multi-BUY VWAP, BUY+SELL, fully-sold position, null ticker/quantity skipping, multiple tickers, existing holding update.
- `ManualTransactionServiceTest` — 6 unit tests covering cash add, investment add (holdings recomputed), non-owned account rejection, manual delete, synced-transaction delete rejection, not-found rejection.
