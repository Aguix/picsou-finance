<div align="center">

# Picsou

**Self-hosted personal finance dashboard**

Track bank accounts, brokerage, crypto, and net worth — all in one place.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[Getting started](#getting-started) · [Features](#features) · [Development](#development) · [Security](SECURITY.md)

</div>

---

## Disclaimer

> **Picsou is designed for personal, local use.**
>
> It stores sensitive financial data (balances, transactions, bank session tokens). Authentication is single-user with no 2FA or audit logging. It has not undergone a professional security audit.
>
> **Do not expose it on the public internet.** Use it on your local machine or home network behind a firewall. If you choose to expose it, you do so at your own risk.

---

## Features

- **Account aggregation** — Bank accounts (LEP, PEA, Livret, current), brokerage, crypto wallets, on-chain addresses
- **Bank sync** — Enable Banking (PSD2/OAuth, 2000+ EU banks) or Powens/Budget Insight (scraping)
- **Brokerage sync** — Trade Republic via WebSocket or CSV import
- **Crypto** — Binance exchange sync, on-chain BTC/ETH/SOL address tracking
- **Live prices** — CoinGecko (crypto), Yahoo Finance (stocks/ETFs)
- **Net worth tracking** — Historical snapshots, stacked area charts, per-account breakdown
- **Savings goals** — Targets with deadlines, progress tracking across accounts
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
                      ┌──────────────┼──────────────┐
                      ▼              ▼               ▼
               Enable Banking   CoinGecko      Yahoo Finance
               Powens          Binance API    Trade Republic
               (PSD2/scraping) (crypto)       (WebSocket)
```

- **Ports & Adapters** — `BankConnectorPort`, `PriceProviderPort`, `TradeRepublicPort`, etc. Swap providers without touching business logic.
- **Flyway** — Versioned database migrations
- **JWT auth** — HttpOnly cookies, SameSite=Strict, refresh token rotation
- **AES-256-GCM** — Mandatory encryption for API secrets at rest
- **Rate limiting** — Bucket4j on login and sync endpoints

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

### 2. Configure

```bash
cp docker/.env.example docker/.env
```

Edit `docker/.env` with your credentials:

| Variable | Required | Description |
|----------|----------|-------------|
| `POSTGRES_PASSWORD` | Yes | Strong random password |
| `JWT_SECRET` | Yes | `openssl rand -base64 48` |
| `APP_USERNAME` | Yes | Your login username |
| `APP_PASSWORD_HASH` | Yes | `htpasswd -bnBC 12 "" YOUR_PASSWORD \| tr -d ':\r\n'` |
| `CRYPTO_ENCRYPTION_KEY` | For crypto | `openssl rand -base64 32` |
| `ENABLEBANKING_*` | For bank sync | From your [Enable Banking dashboard](https://enablebanking.com/) |
| `TR_PHONE_NUMBER` / `TR_PIN` | For Trade Republic | Your Trade Republic credentials |

> **Note:** The bcrypt hash contains `$` characters. In the `.env` file, write it as-is without quotes. Never export it in a shell without single quotes: `export APP_PASSWORD_HASH='$2a$12$...'`.

### 3. Run

```bash
docker compose -f docker/docker-compose.yml up --build
```

Open http://localhost:8080 and log in with the credentials you configured.

### Enable Banking key setup (optional)

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
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # Requires PostgreSQL on :5432
./mvnw test                                              # Run tests
```

### Frontend

```bash
cd frontend
bun install        # Install dependencies
bun run dev        # Dev server on :5173 (proxies /api/* → localhost:8080)
bun run build      # TypeScript check + Vite build
npx vitest run     # Unit tests
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

[MIT](LICENSE) — provided as-is, with no warranty. For personal use.
