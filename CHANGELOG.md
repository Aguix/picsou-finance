# Changelog

All notable changes to Picsou are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-04-26

First stable release. Builds on the MVP (commit `37920d1`) by adding multi-member
families, 2FA + persistent sessions, a zero-config setup wizard, BoursoBank,
loan amortization, GDPR data export, and dozens of refinements.

### Added

#### Multi-account family
- **Family members & roles.** New `FamilyMember` entity, `UserRole` enum, sharing
  entities, and per-row `member_id` foreign keys across all financial tables
  (`d95b7b7`, `d7abdf2`).
- **Member-scoped services.** All services, controllers, and repositories now
  scope queries by `memberId`, with admin override for impersonation
  (`68a97ba`, `2dbbeb6`).
- **`UserContext` helper.** Single source of truth for "who is the current user
  and which member are they viewing?" (`050c49d`, `c548b7f`).
- **Family API & UI.** `FamilyController`, `FamilyViewController`, family stores,
  sidebar, and pages for creating members, switching profiles, and viewing
  shared resources (`a5308e8`, `a3ee1c0`).
- **Username change with token rotation.** Settings → Profile lets a user rename
  themselves; access/refresh JWTs are re-issued so the session doesn't drop
  (`7f8c988`, `3454307`).
- **Admin members management.** Admins can invite, activate, reset password, and
  reset 2FA for other members (`6a3c978`, `36ef439`).

#### Two-factor authentication & persistent sessions
- **TOTP 2FA.** `UserMfa`, recovery codes, anti-replay window, full enrolment
  wizard, and verification challenge page (`98243c5`, `ccddd88`, `6dd476a`,
  `a95d752`, `5f60ce5`, `c59a439`).
- **Remember Me / persistent sessions.** Rotating tokens with theft detection,
  silent re-login filter, and admin force-disable (`6c1ae82`, `a481128`,
  `5f724e6`, `7afd7d0`).
- **MFA-aware controllers.** `MfaController` (step-up reauth) and
  `SessionController` (list/revoke active sessions) (`0df1f68`, `7afd7d0`,
  `db40cf6`, `07a4fff`).
- **Stateless JWT invalidation on password change.** New `token_version` claim
  bumped on password change; persistent sessions revoked, refresh+access cookies
  re-issued (`056a900`).

#### Setup wizard (zero-config first launch)
- **Guided first-boot flow.** `SetupController`, `SetupService`, `SetupFilter`,
  audit log, and `AppSetting` key/value store let admins complete admin
  account, security (CORS, secure cookies, encryption key), and integrations
  setup with no env-var editing (`627533a`).
- **Per-integration mini-wizards.** Enable Banking (5-step keypair flow),
  BoursoBank, Trade Republic, Finary, and crypto-exchange setup pages
  (`627533a`).

#### Integrations
- **BoursoBank.** Python sidecar (Selenium-based), `BoursoAdapter`,
  `BoursoController`, sessions table, V23 migration (`b953e55`).
- **Trade Republic compact portfolio.** `compactPortfolio` with `secAccNo` for
  portfolio sync; deduplication when multiple ISINs map to the same ticker
  (`91972b6`, `1b91abf`).
- **Finary.** Full Finary API import + auto-sync, holdings preservation across
  syncs, manual-transaction protection, daily scheduler, proactive TOTP
  detection in the Add Account modal (`6c9777e`, `93c7ee0`, `0328533`,
  `1faa1d8`, `0f4bf07`, `fbeb155`).
- **Powens** adapter (alongside Enable Banking) (`6c9777e`).
- **Crypto exchanges.** Binance integration via `CryptoExchangePort`
  (`b70b2aa`, `aab51c4`).
- **On-chain wallets.** Bitcoin (xpub, zpub, output descriptors), Ethereum, and
  Solana via dedicated adapters (`b70b2aa`, `2eda584`, `4b51f32`).
- **OpenFIGI ISIN → ticker resolver** for Yahoo Finance prices (`0bf43c6`).

#### Loans
- **On-the-fly amortization.** `LoanAmortizationService` derives schedule from
  loan inputs without storing a full row per month; UI components render a
  progress card, monthly breakdown, cost summary, and amortization chart
  (`52aa8e5`).
- **Extra loan fields.** V27 migration adds APR, fees, and supporting columns
  on `Debt` (`52aa8e5`).

#### Manual transactions
- **CRUD endpoints.** `POST` / `DELETE /api/transactions/...`, with the manual
  flag preserved across re-syncs (`969ba10`, `93c7ee0`).
- **Investment transactions.** Holdings auto-fill, BUY/SELL drive position
  derivation via `HoldingComputeService` (`a12321a`, `4f534ac`, `9b144d8`,
  `210d758`).
- **AddTransactionModal** with hooks, error handling, and TransactionsList
  delete action (`429eca0`, `b5b1d41`).

#### Goals
- **Donut year cards.** Year grid view replaced with donut cards using `oklch`
  CSS vars (`f30d77b`, `33bbcf5`, `a3f9168`).

#### GDPR data export
- **Self-service export** of profile, accounts, holdings, transactions, goals,
  debts, wallets, sharing settings, bank connections, and balance snapshots
  in JSON + CSV, gated by re-authentication and rate-limited (`d0933ac`,
  `3f58502`, `9287d47`, `66a274a`, `22dd58b`).

#### History, debts, real estate
- **History/PnL endpoints**, debts, real estate metadata, error pages, mobile
  nav, sync modal (`dad9bea`).

#### Holdings UX
- **Edit & detail modals**, empty-chart state with intraday support
  (`de40df0`).

#### Other
- **Mandatory encryption** of secrets at rest (`6c1ae82`, `6c9777e`).
- **Theme persistence** across sessions (`6c9777e`).
- **All-in-one Docker image** with supervisord, dev hot-reload Dockerfiles,
  slimmer `tr-auth` sidecar (`cd651e6`).

### Changed

- **Frontend rewrite** with shadcn/ui and feature-based architecture
  (`33a384c`).
- **Icon migration** from hugeicons to `lucide-react` (`f3bb858`).
- **Locale-aware formatting** helpers via `getLocale()` (`3b9b0f4`).
- **Add Account modal** unified with embedded sync wizards (`7a96584`).
- **Layout, sidebar, dashboard, account detail** redesign + updated demo data
  (`001551f`).
- **Mobile responsiveness** across the whole app for 1.0.0 (`5017206`).
- **License changed** from MIT to Apache 2.0 + Commons Clause (`e9185c9`,
  `25825e5`).

### Fixed

- **CORS with credentials.** Forbid wildcard origins; default to empty (no
  cross-origin); strip `*` in DB-loaded values; defense-in-depth at parse and
  Spring layers (`f8e92f7`).
- **Goal contributions IDOR.** `/api/family/goals/{id}/contributions` now checks
  ownership and sharing visibility (`7236faa`).
- **Persistent-token MFA awareness** (`5f724e6`).
- **Finary** — `TOTP_REQUIRED` reported correctly when session is flagged
  (`5dca6da`); auto-sync transaction handling (`aaf770d`); BoursoSync filter
  (`aaf770d`); show all bank connections in SyncAllModal (`ab4faa6`).
- **Family** — admin shows "Administrateur" instead of "Compte indépendant"
  (`2d50db4`); lazy serialization, enum mapping, profile switching
  (`f152cb9`).
- **Auth cookies** SameSite=Lax for Safari iOS compatibility (`3e9e140`); CORS
  PATCH method allowed (`e25f57d`); detailed login error reporting + CORS
  trace logs (`2488771`, `4af867a`); local-network CORS via origin patterns
  (`2832da3`, `2859392`).
- **Holdings** — null-quantity test, Spring `@Transactional`, N+1 fix
  (`4f534ac`, `9b144d8`).
- **Wallet** — remove ticker from on-chain wallet accounts to prevent double
  price conversion (`916a128`).
- **Frontend** — TypeScript build errors (`7cb7a3d`); AddTransactionModal
  reset & holdings query (`b5b1d41`); export read-only transaction wrapping
  (`22dd58b`).

### Security

- **CORS hardening** (see *Fixed*): closed wildcard-with-credentials
  vulnerability.
- **JWT invalidation on password change** via `token_version` claim.
- **`SecureCookieProvider`** centralizes environment-aware `Secure` flag for
  cookies (`9570661`).
- **Setup wizard** rejects `*` for CORS allowed origins (`627533a`).
- **Rate-limit buckets** for MFA challenge and data export (`a95d752`,
  `d0933ac`).
- **Persistent token theft detection** (rotating opaque tokens) (`6c1ae82`).

### Documentation

- **README & SECURITY.md** rewritten; license clarified (`e9185c9`,
  `25825e5`, `42ab61b`).
- **CLAUDE.md** files overhauled with conventions and "don'ts"; English-only
  rule enforced (`fb599a5`, `527a0a4`, `5c4d29d`).
- **New ADRs** — first-launch wizard, tr-auth sidecar slim image, loan
  amortization on the fly (`cd651e6`, `52aa8e5`, `627533a`).
- **New feature notes** — bourso-bank, loans, setup-wizard, intraday-chart,
  manual-transactions, security-cors-cookies, accounts overview, ISIN→ticker,
  data-export, MFA design, multi-account-family design (`b953e55`,
  `52aa8e5`, `627533a`, `de40df0`, `631b7f9`, `89f659c`, `671565d`,
  `8edcf5e`, `bc9e094`, `3e4d1cf`, `8c6e702`).
- **Architecture index** initialized (`040ebac`).

### Database migrations

- **V23** — `bourso_session` (`b953e55`)
- **V24** — manual transaction fields (`a33d53c`)
- **V25** — `setup_state` (`627533a`)
- **V26** — `setup_audit` (`627533a`)
- **V27** — loan extra fields (`52aa8e5`)
- **V28** — MFA + persistent sessions (`98243c5`)
- **V29** — `app_user.token_version` (`056a900`)

[1.0.0]: https://github.com/Zoeille/picsou/releases/tag/v1.0.0
