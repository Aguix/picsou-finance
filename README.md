<div align="center">

# Picsou

**Self-hosted personal finance dashboard**

Track bank accounts, brokerage, crypto, and net worth — all in one place.

[![License: Apache 2.0 + Commons Clause](https://img.shields.io/badge/License-Apache%202.0%20%2B%20Commons%20Clause-blue.svg)](LICENSE)

[Getting started](#getting-started) · [Features](#features) · [Development](#development) · [Security](SECURITY.md)

</div>

---

## Disclaimer

> **Picsou is designed for personal, local use.**
>
> It stores sensitive financial data (balances, transactions, bank session tokens). It supports multi-member families, optional TOTP 2FA, and audit logging of setup/admin actions, but it has **not** undergone a professional security audit.
>
> **Do not expose it on the public internet.** Use it on your local machine or home network behind a firewall. If you choose to expose it, you do so at your own risk.

---

## Features

- **Account aggregation** — Bank accounts (LEP, PEA, Livret, current), brokerage, crypto wallets, on-chain addresses, debts/loans
- **Bank sync** — Enable Banking (PSD2/OAuth, 2000+ EU banks).
- **Brokerage sync** — Trade Republic via WebSocket or CSV import
- **Crypto** — Binance exchange sync, on-chain BTC/ETH/SOL address tracking
- **Live prices** — CoinGecko (crypto), Yahoo Finance (stocks/ETFs)
- **Security insight** — Per-holding asset-type detection and ETF composition (top holdings, country & sector breakdowns) in the holding detail modal
- **Net worth tracking** — Historical snapshots, stacked area charts, per-account breakdown
- **Savings goals** — Targets with deadlines, progress tracking across accounts
- **Multi-member family** — One admin manages multiple profiles (children, spouse). Per-resource sharing (`NONE` / `ALL` / `MANUAL`), optional activation links to upgrade a managed profile to a full login.
- **2FA + Remember Me** — Opt-in TOTP per user, 10 single-use recovery codes, 90-day "Remember Me" cookie with rotating tokens, "Trust this device" to skip TOTP, per-session revocation from settings.
- **GDPR data export** — Self-service ZIP export (JSON + per-entity CSV) gated by re-authentication, rate-limited to 5/hour.
- **Finary import** — CSV import or direct API sync
- **i18n** — English and French
- **Dark mode** — System/light/dark with flash-free theme switching

## Architecture

```
┌──────────────────┐     ┌───────────────────────┐     ┌────────────┐
│  React Frontend  │────▶│  Spring Boot Backend   │────▶│ PostgreSQL │
│   (Vite/Bun)     │◀────│     (Tomcat :8080)     │     │  (:5432)   │
└──────────────────┘     └───────────┬────────────┘     └────────────┘
                                     │
                      ┌──────────────┼──────────────┬──────────────┐
                      ▼              ▼               ▼              ▼
               Enable Banking   CoinGecko      Yahoo Finance   Trade Republic
               (PSD2/OAuth)     (crypto)       (stocks/ETF)    (WebSocket)
```

- **Ports & Adapters** — `BankConnectorPort`, `PriceProviderPort`, `TradeRepublicPort`, `BoursoPort`, etc. Swap providers without touching business logic.
- **Two-tier identity** — `AppUser` (auth) → `FamilyMember` (domain). Every entity is scoped by `member_id`; admins can act on behalf of a managed profile via `?memberId=X`.
- **Flyway** — Versioned database migrations
- **JWT auth** — HttpOnly cookies, SameSite=Lax (Safari iOS compatibility), refresh token rotation
- **2FA (TOTP)** — Opt-in, with hashed recovery codes and trusted-device cookies
- **AES-256-GCM** — Mandatory encryption for API secrets at rest (Binance, TOTP secrets, bank session tokens)
- **Rate limiting** — Bucket4j on login, MFA challenge, sync endpoints, and data export

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 3.4, Maven |
| Frontend | React 19, TypeScript 5.9, Vite 7, Tailwind v4, Bun |
| Database | PostgreSQL 16, Flyway |
| Runtime | Docker (Nginx + Spring Boot + supervisor) |

## Getting started

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose v2
- (Optional) An [Enable Banking](https://enablebanking.com/) account for bank sync

### 1. Clone

```bash
git clone https://github.com/Zoeille/picsou-finance.git
cd picsou-finance
```

### 2. Run (zero-config)

```bash
docker compose -f docker/docker-compose.yml up --build
```

On first launch the entrypoint auto-generates `JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`, and `POSTGRES_PASSWORD` (persisted to the `picsou_data` volume under `/data/.secrets/`). Open http://localhost:8080 — the **setup wizard** walks you through admin credentials, CORS, and (optionally) Enable Banking.

### 3. Advanced configuration (optional)

If you prefer to seed everything up front (CI, external secret managers, etc.):

```bash
cp docker/.env.example docker/.env
```

| Variable | When to set | Description |
|----------|-------------|-------------|
| `POSTGRES_PASSWORD` | Override auto-gen | Strong random password |
| `JWT_SECRET` | Override auto-gen | `openssl rand -base64 48` |
| `CRYPTO_ENCRYPTION_KEY` | Override auto-gen | `openssl rand -base64 32` |
| `APP_USERNAME` / `APP_PASSWORD_HASH` | Skip wizard | `htpasswd -bnBC 12 "" YOUR_PASSWORD \| tr -d ':\r\n'` |
| `ALLOWED_ORIGINS` | Non-localhost | e.g. `http://your-nas-ip:8080` |
| `SECURE_COOKIES` | Plain HTTP | `false` if no TLS in front |
| `ENABLEBANKING_*` | Skip wizard | From your [Enable Banking dashboard](https://enablebanking.com/) |
| `BOURSO_AUTH_URL` | Custom sidecar | Defaults to `http://bourso-auth:8001` |

> **Note:** The bcrypt hash contains `$` characters. In `.env`, write it as-is without quotes. Never export it in a shell without single quotes: `export APP_PASSWORD_HASH='$2a$12$...'`.

### 4. Enable Banking key setup (optional)

```bash
mkdir -p docker/secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out docker/secrets/enablebanking.pem
openssl rsa -pubout -in docker/secrets/enablebanking.pem -out enablebanking_public.pem
```

Upload `enablebanking_public.pem` to your Enable Banking dashboard.

## Development

### Backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # Requires PostgreSQL on :5432
mvn test                                              # Run tests
```

### Frontend

```bash
cd frontend
bun install        # Install dependencies
bun run dev        # Dev server on :5173 (proxies /api/* → localhost:8080)
bun run build      # TypeScript check + Vite build
bunx vitest run    # Unit tests
```

## Contributing

Contributions are welcome — bug fixes, features, translations, or documentation.

1. Fork the repository
2. Create a feature branch (`feat/xxx`, `fix/xxx`)
3. Write conventional commits
4. Open a pull request against `main`

Please read the relevant [feature docs](docs/features/) and [conventions](docs/conventions/) before touching existing code.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability reporting policy.

## License

[Apache 2.0 + Commons Clause](LICENSE) — free for personal use and managed hosting. Commercial SaaS use is prohibited without permission.
