# Feature: Bank Logos on Account Cards

> Last updated: 2026-07-01

## Context

Account cards on the Accounts page (`/accounts`) previously showed only a flat color swatch as the account's visual identity. Enable Banking's institution search already returns a real bank logo URL (`InstitutionData.logoUrl`) that was captured but never surfaced anywhere in the UI. This feature threads that logo through to the account and displays it as a circular avatar, falling back to the existing color when no logo is available.

## How it works

### Scope

Only accounts connected via **Enable Banking** get a real logo — it's the sole active `BankConnectorPort` implementation that returns one (see [bank-sync.md](./bank-sync.md)). Powens (disabled, experimental) hardcodes `logoUrl = null`. Manual accounts, crypto exchanges/wallets, Trade Republic, BoursoBank, Finary, real estate, and loans have no logo source and always show the color fallback. There is no manual logo picker — `color` remains the only user-editable visual field (`ColorPicker` / `AccountForm` are unchanged).

### Capture at connection time

1. `BankWizard` (`AddAccountModal.tsx`) already has each institution's `logoUrl` from `GET /sync/institutions`. Selecting a bank now passes it through: `initiateMutation.mutate({ institutionId, institutionName, logoUrl })`.
2. `SyncService.initiateConnection()` stores it on the new `Requisition.logoUrl` column.
3. `SyncService.upsertAccount()` copies `requisition.getLogoUrl()` onto `Account.logoUrl` when creating a new account, and onto an existing account only if its `logoUrl` was still `null` (never overwrites a value once set).

### Backfill for pre-existing connections

Requisitions created before this feature shipped have `logoUrl = null`. `SyncService.ensureLogoUrl()` runs at the top of `resyncAll()` (daily scheduler) and `retrySync()` (manual retry): if the requisition has no logo yet, it re-searches institutions by name via `bankConnector.searchInstitutions(institutionName, null)` and matches by institution id, falling back to a case-insensitive name match. A failed or empty lookup is swallowed (logged as a warning) — the requisition simply stays logo-less and is retried on the next sync. This bounds the extra API call to once per requisition (skipped entirely once a logo is found).

### Rendering

`AccountCard.tsx`'s `AccountAvatar` renders a `size-10` circle: an `<img>` if `account.logoUrl` is set, otherwise a plain circle filled with `account.color`. An `onError` handler on the `<img>` flips to the color fallback if the logo URL 404s or fails to load at render time (tracked via local `useState`, not persisted). The account detail page (`AccountDetailPage.tsx`) and the PnL chart legend (`AccountsStackedChart.tsx`) were intentionally left untouched — they use `account.color` as a small decorative dot/line color, not as the account's primary identity, and are out of scope for this change.

### Key files

- `backend/src/main/java/com/picsou/model/Account.java` — `logoUrl` column
- `backend/src/main/java/com/picsou/model/Requisition.java` — `logoUrl` column (capture + backfill target)
- `backend/src/main/java/com/picsou/service/SyncService.java` — `ensureLogoUrl()`, `upsertAccount()` copy logic
- `backend/src/main/resources/db/migration/V38__account_bank_logo.sql`
- `frontend/src/components/shared/AccountCard.tsx` — `AccountAvatar` sub-component
- `frontend/src/components/shared/AddAccountModal.tsx` — `InstitutionLogo` (bank search list preview)

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Enable Banking only, no manual picker | It's the only connector with real logos; a curated logo library or free-form upload was out of scope for v1 | Static logo library / image upload per account |
| `color` kept as-is on every account | Still used by `AccountsStackedChart` line colors and the detail page dot; removing it would require a chart color strategy | Drop `color` once a logo exists |
| Best-effort backfill via re-search, not a migration | A migration can't make network calls safely; re-searching on the next scheduled/manual sync is free and self-healing | One-off backfill script at deploy time |
| Backfill only overwrites `logoUrl` when it was `null` | Never clobbers a logo the user already got from a real connection | Always refresh from the latest search result |

## Gotchas / Pitfalls

- **Powens never provides a logo.** `PowensBankConnector.searchInstitutions()` hardcodes `logoUrl = null` for every result. If Powens is ever re-enabled, its accounts will always show the color fallback until the adapter is updated.
- **Backfill match is best-effort.** `ensureLogoUrl()` matches by institution id first, then falls back to a case-insensitive name match. A renamed institution on the provider side may never match — the account just keeps showing its color, which degrades gracefully.
- **`onError` fallback is render-only.** A broken logo URL is not written back to the database — the same broken URL is retried on every page load. This is intentional (the URL may become valid again, e.g. a CDN blip) but means a permanently-dead logo silently shows the color fallback forever rather than healing itself.

## Tests

- `backend/src/test/java/com/picsou/service/SyncServiceTest.java` — logo copied from `Requisition` to a new `Account`; backfill sets `Requisition.logoUrl` on resync; a failed backfill lookup doesn't break the sync.
- `frontend/src/components/shared/AccountCard.test.tsx` — renders the logo image when present, the color fallback when absent, and falls back after an `onError` event.

## Links

- Related: [bank-sync.md](./bank-sync.md) — Enable Banking connector and requisition lifecycle
- Related: [accounts-overview.md](./accounts-overview.md) — Accounts page and account card
