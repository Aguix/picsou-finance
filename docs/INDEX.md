# Technical Documentation Index

> Picsou is a self-hosted personal finance dashboard.
> It aggregates bank accounts, brokerage, crypto, and on-chain assets, and tracks net worth over time.
>
> This file is the entry point for technical documentation.
> Read it first to know where to find information.

## Architecture

- [ARCHITECTURE.md](./ARCHITECTURE.md) -- Overview, modules, data flows

## Technical decisions (ADR)

| Date | Decision | Status |
|------|----------|--------|
| 2026-01-01 | [Ports and adapters architecture](./decisions/2026-01-01-ports-and-adapters.md) | Active |
| 2026-01-01 | [Single user with JWT in HttpOnly cookies](./decisions/2026-01-01-single-user-jwt-cookies.md) | Active |
| 2026-01-01 | [Flyway owns the schema](./decisions/2026-01-01-flyway-schema-ownership.md) | Active |
| 2026-03-01 | [Dual bank provider](./decisions/2026-03-01-dual-bank-providers.md) | Active |
| 2026-03-01 | [AES-256-GCM encryption for crypto secrets](./decisions/2026-03-01-aes-gcm-crypto-secrets.md) | Active |
| 2026-04-05 | [Component-local state for UI filters](./decisions/2026-04-05-component-local-state-for-ui-filters.md) | Active |
| 2026-04-08 | [Mandatory encryption key at startup](./decisions/2026-04-08-mandatory-encryption-key.md) | Active |
| 2026-04-08 | [CSS relative color syntax for theme-adaptive brightness](./decisions/2026-04-08-css-relative-color-syntax.md) | Active |

## Feature notes

| Feature | Last updated | Note |
|---------|-------------|------|
| Frontend utilities (lib/utils.ts) | 2026-04-13 | [frontend-utils.md](./features/frontend-utils.md) |
| Demo mode | 2026-04-08 | [demo-mode.md](./features/demo-mode.md) |
| Theme (dark / light / system) | 2026-04-08 | [theme-persistence.md](./features/theme-persistence.md) |
| Dashboard — Time range isolation | 2026-04-13 | [dashboard-time-range-isolation.md](./features/dashboard-time-range-isolation.md) |
| Bank sync | 2026-04-04 | [bank-sync.md](./features/bank-sync.md) |
| Trade Republic | 2026-04-12 | [trade-republic.md](./features/trade-republic.md) |
| Trade Republic — Holdings deduplication | 2026-04-05 | [trade-republic-holding-deduplication.md](./features/trade-republic-holding-deduplication.md) |
| ISIN → Ticker conversion | 2026-04-13 | [ISIN_TO_TICKER_CONVERSION.md](./features/ISIN_TO_TICKER_CONVERSION.md) |
| Encryption at rest | 2026-04-08 | [encryption-at-rest.md](./features/encryption-at-rest.md) |
| Crypto tracking | 2026-04-08 | [crypto-tracking.md](./features/crypto-tracking.md) |
| Savings goals | 2026-04-13 | [goals.md](./features/goals.md) |
| Goals — Grid view (donuts) | 2026-04-08 | [goal-calendar-donut.md](./features/goal-calendar-donut.md) |
| Price service | 2026-04-04 | [price-service.md](./features/price-service.md) |
| Live prices (holdings) | 2026-04-13 | [live-prices-holdings.md](./features/live-prices-holdings.md) |
| Finary import + auto-sync | 2026-04-21 | [finary-import.md](./features/finary-import.md) |
| Manual transactions + holdings derivation | 2026-04-21 | [manual-transactions.md](./features/manual-transactions.md) |
| BoursoBank sync | 2026-04-21 | [bourso-bank.md](./features/bourso-bank.md) |
| Accounts overview (PnL chart + summary card + filters) | 2026-04-13 | [accounts-overview.md](./features/accounts-overview.md) |
| Add Account modal (unified sync + manual) | 2026-04-09 | [add-account-modal.md](./features/add-account-modal.md) |
| Docker deployment (all-in-one image) | 2026-04-16 | [docker-deployment.md](./features/docker-deployment.md) |
| Navigation (sidebar + mobile bottom nav) | 2026-04-13 | [sidebar-navigation.md](./features/sidebar-navigation.md) |
| Multi-account family system | 2026-04-22 | [multi-account-family.md](./features/multi-account-family.md) |
| CORS & cookie security | 2026-04-22 | [security-cors-cookies.md](./features/security-cors-cookies.md) |
| 24H Intraday net worth chart | 2026-04-18 | [intraday-chart.md](./features/intraday-chart.md) |

## Conventions

| Topic | File |
|-------|------|
| REST API | [api-rest.md](./conventions/api-rest.md) |
| Error handling | [error-handling.md](./conventions/error-handling.md) |
| Testing | [testing.md](./conventions/testing.md) |
| Frontend | [frontend.md](./conventions/frontend.md) |
| Database | [database.md](./conventions/database.md) |

## Templates

- [FEATURE.md](./templates/FEATURE.md) -- Feature note template
- [DECISION.md](./templates/DECISION.md) -- Architectural decision record (ADR) template
