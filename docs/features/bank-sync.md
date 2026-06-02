# Feature: Bank Sync

> Last updated: 2026-04-26

> **Status (1.0.0).** Enable Banking is the only enabled provider. The Powens
> adapter ships in the codebase but is **experimental and untested** —
> `@Primary` was removed from `PowensBankConnector` so Enable Banking remains
> injected as the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
> is set. Sections below referring to Powens describe the full design that
> can be re-enabled once the adapter has been validated end-to-end.

## Context

Picsou syncs bank accounts from French banks. In 1.0.0 the active provider is Enable Banking (PSD2, open banking). A second provider — Powens / Budget Insight (screen scraping) — is implemented behind `BankConnectorPort` but disabled because it has not been tested against a real Powens tenant. The scheduler runs daily auto-sync at 08:00 for all linked requisitions.

## How it works

### Provider architecture

Both providers implement the `BankConnectorPort` interface with four operations: `initiateConnection`, `exchangeCode`, `fetchBalances`, and `searchInstitutions`. The service layer (`SyncService`) never imports adapters directly -- it depends only on the port.

**Enable Banking** (`EnableBankingBankConnector`): Uses the PSD2 Bank Account Data API. Auth is JWT-based (RS256 signed with an RSA private key). Sessions are created via OAuth redirect. After the user authorizes, accounts are linked asynchronously and polled up to 3 times with 1.5-second delays (≤ 4.5 s total). If the session still has no accounts, the adapter returns an empty list rather than throwing — the requisition is left LINKED so the user can retry from the UI without losing the session id. The previous 24 s blocking poll caused 502 errors at the reverse proxy.

**Powens** (`PowensBankConnector`) — ⚠ experimental, disabled in 1.0.0. Uses screen scraping via the Budget Insight API. Auth is an OAuth webview that handles bank selection and credential entry. The OAuth code is exchanged for a permanent access token. Gated behind `@ConditionalOnExpression` (so it only registers when `POWENS_CLIENT_ID` is set), but `@Primary` was removed for 1.0.0, so Enable Banking remains injected even when the bean is registered.

### Requisition lifecycle

1. **CREATED** -- `SyncService.initiateConnection()` calls the port and stores a `Requisition` with `authLink`.
2. **LINKED** -- `SyncService.completeConnection()` exchanges the OAuth callback code, fetches balances, upserts accounts, and marks the requisition as LINKED.
3. **FAILED** -- If the code exchange or balance fetch fails, the requisition is marked FAILED and can be retried via `retrySync()`.

### Account type detection

`SyncService.detectType()` maps provider metadata (product name, cash account type) to the `AccountType` enum. Keywords like "pea", "lep", "livret", "titre" in the product string are matched case-insensitively. The `cashAccountType` field (e.g. "SVGS") is used as a fallback. Default is `CHECKING`.

### Key files

- `adapter/EnableBankingBankConnector.java` -- PSD2 adapter (RSA JWT, async account linking)
- `adapter/PowensBankConnector.java` -- Scraping adapter (experimental, OAuth webview; `@Primary` removed in 1.0.0)
- `port/BankConnectorPort.java` -- Port interface with `AccountData`, `InstitutionData` records
- `service/SyncService.java` -- Orchestration: initiate, complete, retry, resync, type detection
- `controller/SyncController.java` -- REST endpoints under `/api/sync/`
- `model/Requisition.java` -- Tracks connection lifecycle (CREATED/LINKED/FAILED)

### Flow

```
User initiates connection
        |
        v
SyncController.initiate() --> SyncService --> BankConnectorPort.initiateConnection()
        |                                         |
        |                          Enable Banking: POST /auth (RSA JWT)
        |                          Powens: build webview URL
        |
        v
User authorizes in browser --> redirect to /api/sync/complete?code=xxx
        |
        v
SyncController.complete() --> SyncService.completeConnection()
        |                         |
        |                         v
        |               BankConnectorPort.exchangeCode() --> session_id
        |                         |
        |                         v
        |               BankConnectorPort.fetchBalances(session_id)
        |                         |
        |                         v
        |               upsertAccount() with detectType()
        |                         |
        |                         v
        |               AccountService.upsertSnapshot()
        |
        v
SchedulerService.dailyBankSync() --> SyncService.resyncAll()
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Dual providers with `@Primary` | PSD2 can't access LEP/PEA/livrets; scraping covers all French account types | Single provider only |
| `@ConditionalOnExpression` for Powens | No-code activation: set env var, adapter appears; unset, it disappears | Feature toggles, profiles |
| Keyword-based type detection | Banks rarely expose a standardized type field; product name is the most reliable signal | Hardcoded institution-to-type mapping |
| Async polling for Enable Banking accounts | EB links accounts asynchronously after OAuth; polling (8x3s) handles the delay | Webhook (EB does not provide one) |
| Permanent access token for Powens | Powens tokens do not expire; stored directly as the requisition ID | Refresh token rotation (not needed) |

## Enable Banking onboarding caveats

Two pitfalls cost real users a lot of time during 1.0.0 testing — both are surfaced in the wizard now (`EBStep1Explain`, `EBStep2Credentials`):

- **PRODUCTION vs SANDBOX**: The Enable Banking developer dashboard defaults to SANDBOX, which only exposes fictitious test banks. A user who creates a SANDBOX application will reach the bank picker, see a list of unfamiliar test banks, and never find their real one. The wizard now shows a warning encart on step 1 and forces the user to tick a "my application is in PRODUCTION mode" checkbox before submitting credentials on step 3.
- **PSD2 scope is current accounts only (`CACC`)**: PSD2 standardises consent for cash accounts. PEA, Assurance Vie, Livret A, and other savings/investment products are out of scope — Enable Banking has no API for them. This is a permanent product limitation, not a Picsou bug. Users should be directed to the dedicated integrations (Trade Republic, BoursoBank sidecar, Finary) or manual entry. The wizard surfaces this on step 1, and `BankSyncTab` repeats the note above the connection list.

## Gotchas / Pitfalls

- **Powens is disabled in 1.0.0**: `@Primary` was removed from `PowensBankConnector`, so even setting `POWENS_CLIENT_ID` will NOT activate Powens — Enable Banking stays injected. To re-enable after validating the adapter, restore `@Primary` on `PowensBankConnector` and set `POWENS_CLIENT_ID`.
- **Enable Banking RSA key**: The private key must be PKCS8 PEM format. The `ENABLEBANKING_PRIVATE_KEY` env var can contain literal `\n` characters -- both formats are handled in `parsePrivateKey()`.
- **Enable Banking redirect URI must be registered**: `ENABLEBANKING_REDIRECT_URI` defaults to `http://localhost:5173/sync/callback` (dev only). In production, set it to `http://<host>:8080/sync/callback` in `.env`. The same URL must be registered in the Enable Banking developer portal under the application's Redirect URIs. A mismatch causes a `REDIRECT_URI_NOT_ALLOWED` 400 error at auth initiation — it surfaces in the Add Account modal bank wizard.
- **ALREADY_AUTHORIZED**: If the OAuth code is reused (e.g. browser back button), `SyncService.completeConnection()` catches the error and falls back to refreshing the latest linked session instead of failing.
- **Type upgrade on resync**: If the user has not customized an account's type, `upsertAccount()` will upgrade it from CHECKING to the detected type on the next sync. Manual user changes are preserved (only CHECKING is auto-upgraded).
- **Both providers are optional**: The app starts fine without either. No `BankConnectorPort` bean is required at startup.

## Tests

- `SyncServiceTest` -- unit tests for type detection, upsert logic, retry flow
- Manual integration testing against real provider APIs

## Links

- Related ADR: [Dual bank providers](../decisions/2026-03-01-dual-bank-providers.md)
- Related ADR: [Ports and adapters](../decisions/2026-01-01-ports-and-adapters.md)
