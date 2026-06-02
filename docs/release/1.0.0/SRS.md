# Software Requirements Specification (SRS)

> Project: **Picsou** — self-hosted personal-finance dashboard
> Version: **1.0.0**
> Date: 2026-04-26
> Format: IEEE 830-style, condensed for an open-source self-hosted product

---

## 1. Introduction

### 1.1 Purpose

This SRS describes the functional and non-functional requirements of Picsou
1.0.0. It is the contract between the product (this codebase) and its
operators (self-hosters) and end-users (a household — typically 1 to 5
people sharing a Picsou instance).

### 1.2 Scope

Picsou aggregates a household's financial state — bank accounts, brokerage
positions, crypto wallets, loans/debts, and savings goals — into a single
self-hosted dashboard. It synchronises data from external providers
(banks, brokers, price feeds), persists snapshots, computes net-worth and
P&L over time, and exposes a React frontend over a Spring Boot REST API.
It does **not** execute trades, does **not** offer financial advice, and
does **not** transmit data to any third party other than the providers
the operator explicitly configures.

### 1.3 Definitions

| Term | Meaning |
|------|---------|
| AppUser | Authentication identity (username + password + 2FA) |
| FamilyMember | Domain identity owning financial data; one AppUser maps to one FamilyMember |
| Member-scoped | Every query is filtered by `member_id`; cross-member access requires an explicit sharing rule |
| Requisition | A linked bank connection (Enable Banking session) |
| Holding | A position in a brokerage account (ticker, quantity, cost basis) |
| Snapshot | A daily balance record per account |
| Setup wizard | First-launch flow that collects admin credentials, security, and integrations |

### 1.4 References

- [`docs/ARCHITECTURE.md`](../../ARCHITECTURE.md) — system architecture
- [`docs/INDEX.md`](../../INDEX.md) — full doc index (ADRs, features, conventions)
- ADRs in [`docs/decisions/`](../../decisions/)

---

## 2. Overall description

### 2.1 Product perspective

Picsou is a single-instance, single-tenant deployment. Each instance
serves one household. There is no shared SaaS backend, no telemetry, no
account on a Picsou-operated service. The operator owns the database,
the encryption key, and all credentials.

### 2.2 Product functions (high level)

1. Authenticate a user (password + optional TOTP 2FA + optional
   "Remember Me").
2. Manage household members (admin invites, role assignment, sharing).
3. Aggregate accounts (manual entry, bank sync, broker sync, crypto
   exchange sync, on-chain wallet polling).
4. Track holdings and live prices (CoinGecko, Yahoo Finance with OpenFIGI
   ISIN resolution).
5. Track loans/debts with on-the-fly amortisation.
6. Track savings goals with manual and auto contributions.
7. Render a dashboard (net worth, P&L, holdings breakdown, intraday
   chart) with multiple time-range presets.
8. Export the user's data as a GDPR-compliant ZIP archive.
9. Perform schema migrations transparently on startup (Flyway).

### 2.3 User classes

| Class | Capabilities |
|-------|--------------|
| Anonymous | Login, request password reset (via admin), complete first-launch wizard |
| Member | Read/write their own member-scoped data; view shared resources from others; manage their 2FA and persistent sessions |
| Admin (member with `ADMIN` role) | All of the above + invite/activate/deactivate members, reset another member's password or 2FA, impersonate via `?memberId=`, configure integrations and security settings |

### 2.4 Operating environment

- Server: Linux/macOS/Windows with JDK 21 + PostgreSQL 16 (or Docker).
- Client: any modern browser (Chrome 120+, Firefox 120+, Safari 17+,
  including Safari iOS).
- Mobile: the frontend is responsive and must work on phones.

### 2.5 Constraints

- **Self-hosted, single-instance.** No SaaS multi-tenancy.
- **PostgreSQL 16 only.** The schema uses Postgres-specific features
  (Flyway migrations rely on it).
- **HttpOnly cookies for auth.** No bearer tokens; no JS access to JWTs.
- **Documentation language:** all `docs/` content must be English.

### 2.6 Assumptions and dependencies

External services the operator may configure:
Enable Banking (PSD2), Trade Republic (broker), Binance (crypto
exchange), CoinGecko, Yahoo Finance, OpenFIGI, Cloudflare ETH RPC,
Solana public RPC. None are mandatory — the app starts without any.

---

## 3. Functional requirements

Each requirement has an ID `FR-x.y` and a priority (M = mandatory,
S = should, C = could).

### 3.1 Authentication & sessions (FR-1)

- **FR-1.1 (M).** A user shall log in with username + password; on success
  receive an `access_token` (15 min) and `refresh_token` (7 days) as
  HttpOnly, `SameSite=Lax`, `Secure` (configurable) cookies.
- **FR-1.2 (M).** If 2FA is enabled, login shall return an `mfa_challenge`
  cookie and require a TOTP code via `POST /api/mfa/verify` before issuing
  the access/refresh tokens.
- **FR-1.3 (M).** A user shall enrol TOTP 2FA via QR code; recovery codes
  are issued once and stored hashed.
- **FR-1.4 (S).** A user shall opt into "Remember Me"; on that device a
  rotating opaque `persistent_token` (default 90 d) shall silently
  re-issue access tokens on browser return.
- **FR-1.5 (M).** On password change, `tokenVersion` shall be bumped,
  invalidating all outstanding access tokens; persistent sessions are
  revoked; refresh and access cookies are re-issued for the current device.
- **FR-1.6 (M).** A user shall list and revoke their active sessions.
- **FR-1.7 (M).** Login shall be rate-limited to 5 attempts/IP/15 min.
- **FR-1.8 (M).** Logout shall clear all auth cookies.

### 3.2 Family & sharing (FR-2)

- **FR-2.1 (M).** An admin shall create, activate, deactivate, and rename
  family members.
- **FR-2.2 (M).** Each member's data shall be scoped by `member_id`;
  every repository query enforces this.
- **FR-2.3 (M).** A member may share specific resources (accounts,
  holdings, goals) with other members via `SharedResource` rows; the
  level (`SharingLevel`) controls read vs. read+contribute.
- **FR-2.4 (M).** An admin may impersonate another member via
  `?memberId=` query parameter on every endpoint.
- **FR-2.5 (M).** The dashboard supports a "family view" aggregating
  every member the current user can see.

### 3.3 Setup wizard (FR-3)

- **FR-3.1 (M).** On first launch (no `AppUser` exists), all routes
  except `/api/setup/*` shall return 503; the frontend shall redirect to
  `/setup`.
- **FR-3.2 (M).** The wizard shall collect: admin credentials, security
  settings (CORS origins, secure cookies, encryption key), and optional
  per-integration credentials (Enable Banking keypair, Trade Republic,
  Finary, crypto exchanges).
- **FR-3.3 (M).** Setup actions shall be audited in `setup_audit`.
- **FR-3.4 (M).** Once setup is complete, `SetupFilter` becomes inert
  and remains inert until the database is wiped.

### 3.4 Bank sync (FR-4)

- **FR-4.1 (M).** A member shall initiate an Enable Banking connection;
  the response includes an `authLink`.
- **FR-4.2 (M).** On callback, the OAuth code is exchanged, accounts are
  fetched, and a `Requisition` is created with status `LINKED`.
- **FR-4.3 (M).** A daily scheduler at 08:00 shall re-sync every
  `LINKED` requisition.
- **FR-4.4 (M).** Failures shall be retryable via `POST
  /api/sync/{id}/retry`.
- **FR-4.5 (M).** Sync initiation shall be rate-limited.

### 3.5 Brokerage & crypto (FR-5)

- **FR-5.1 (M).** A member shall sync Trade Republic positions (via the
  TR sidecar microservice, WebSocket).
- **FR-5.2 (M).** A member shall sync Binance balances using stored
  encrypted API keys.
- **FR-5.3 (M).** A member shall add Bitcoin (xpub/zpub/descriptor),
  Ethereum, or Solana wallet addresses; balances shall refresh on demand
  and on schedule.
- **FR-5.4 (S).** A member may import historical transactions from a
  Finary export.

### 3.6 Manual data (FR-6)

- **FR-6.1 (M).** A member shall create accounts manually with type,
  currency, and starting balance.
- **FR-6.2 (M).** A member shall create, edit, and delete manual
  transactions, including BUY/SELL transactions that update holdings via
  `HoldingComputeService`.
- **FR-6.3 (M).** Manual transactions shall NOT be overwritten by bank
  re-syncs (`is_manual` flag preserved).

### 3.7 Holdings & prices (FR-7)

- **FR-7.1 (M).** A holding (ticker, quantity, currency) shall be priced
  using Yahoo Finance (with OpenFIGI ISIN→ticker resolution) or
  CoinGecko (crypto).
- **FR-7.2 (M).** Prices shall be cached for 15 minutes in memory.
- **FR-7.3 (M).** A daily scheduler shall snapshot prices and balances
  for historical charts.

### 3.8 Goals & loans (FR-8)

- **FR-8.1 (M).** A member shall create savings goals with target
  amount, target date, and linked accounts; contributions may be auto
  (account balance delta) or manual.
- **FR-8.2 (M).** A member shall create loans (debts) with principal,
  rate, term, and start date; the amortisation schedule is computed on
  the fly (no per-month rows persisted).

### 3.9 Dashboard & history (FR-9)

- **FR-9.1 (M).** The dashboard shall expose net worth, P&L,
  contribution breakdown, holdings, and recent transactions for a
  selected time range (1D, 1W, 1M, 3M, 1Y, ALL, custom).
- **FR-9.2 (M).** Time-range state is component-local (no global store).
- **FR-9.3 (M).** Demo mode shall serve frozen sample data without
  hitting the backend.

### 3.10 Data export (FR-10)

- **FR-10.1 (M).** A member shall export their own data as a ZIP of CSVs
  via `GET /api/me/export`, after re-authenticating with their password.
- **FR-10.2 (M).** Export is rate-limited and runs inside a read-only
  transaction.

### 3.11 Admin & operations (FR-11)

- **FR-11.1 (M).** An admin shall update integrations settings, CORS
  origins, secure-cookie flag, and Enable Banking credentials at runtime.
- **FR-11.2 (M).** An admin shall reset another member's password or
  2FA.
- **FR-11.3 (M).** Schema migrations shall be applied automatically on
  startup via Flyway.

---

## 4. Non-functional requirements

### 4.1 Security (NFR-S)

- **NFR-S.1.** Authentication uses HttpOnly, `SameSite=Lax`, `Secure`
  (when configured) cookies. CSRF is disabled — same-site cookies + JSON
  surface provide equivalent protection.
- **NFR-S.2.** Passwords are hashed with bcrypt cost 12.
- **NFR-S.3.** TOTP secrets, bank session tokens, and exchange API keys
  are encrypted at rest with AES-256-GCM. The key is mandatory at
  startup.
- **NFR-S.4.** Stack traces are never exposed; errors return RFC 7807
  ProblemDetail.
- **NFR-S.5.** All controllers enforce member scoping; bypassing it is
  forbidden.

### 4.2 Performance

- **NFR-P.1.** Dashboard load: < 1 s on a warm cache for a household of
  ≤ 50 accounts.
- **NFR-P.2.** Price cache TTL: 15 min, in-memory.
- **NFR-P.3.** Daily scheduled jobs (price refresh, balance snapshot,
  bank sync) run during off-peak hours and must not block API traffic.

### 4.3 Reliability

- **NFR-R.1.** Flyway migrations run on every startup; failure is fatal.
- **NFR-R.2.** Bank sync failures shall not corrupt account state;
  failed requisitions are marked `FAILED` and retryable.
- **NFR-R.3.** No silent data loss: every destructive operation (delete,
  reset) is explicit.

### 4.4 Usability

- **NFR-U.1.** The frontend is mobile-responsive (mandatory; tested on
  phones).
- **NFR-U.2.** All UI flows must function in dark mode and respect the
  user's theme preference (persisted).
- **NFR-U.3.** Demo mode allows evaluation without any backend
  dependency.

### 4.5 Maintainability

- **NFR-M.1.** External providers sit behind ports
  (`BankConnectorPort`, `PriceProviderPort`, etc.); swapping a provider
  is a one-bean change.
- **NFR-M.2.** All structural decisions are recorded as ADRs in
  `docs/decisions/`.

### 4.6 Portability

- **NFR-PR.1.** Backend runs on JDK 21+; frontend on any modern browser.
- **NFR-PR.2.** Deployable as bare-metal (Maven + Bun) or Docker
  Compose.

---

## 5. Disabled / experimental scope (informative)

These integrations ship in 1.0.0 source but are **off** by default:

- **Powens / Budget Insight** — `@Primary` removed from
  `PowensBankConnector`; not validated against a real tenant.
- **BoursoBank** — Python sidecar commented out in `docker-compose.yml`,
  UI entries hidden.

Re-enabling either requires a code change and is out of scope for 1.0.0.

---

## 6. Acceptance criteria

A 1.0.0 build is acceptable when:

1. All `mvn test` and `bunx vitest run` suites pass.
2. `bun run typecheck` and `bun run lint` are clean.
3. A first-launch wizard flow on a fresh database produces a usable
   dashboard.
4. The full security review (latest in `docs/superpowers/`) reports no
   confirmed high-severity issues.
