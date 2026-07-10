# ADR: Dedicated, app-global tables for price-aggregator API credentials

> Date: 2026-07-10
> Status: ✅ Active

## Context

Moving from a single price source to a multi-aggregator model (CoinGecko, Yahoo, later
CoinMarketCap) means the app needs somewhere to keep each aggregator's API credentials. The goal is
to spread rate limits over several keys (CoinGecko's free tier 429s within a handful of calls) and to
let the operator add/rotate keys at runtime — neither of which the current single
`COINGECKO_DEMO_API_KEY` env var supports.

The app already stores credentials for *other* external sources in separate typed tables:
`bourso_session` (encrypted cookies), `trade_republic_session` (tokens), `finary_session`,
`crypto_exchange_session` (api_key/api_secret). So the question was **where** price-aggregator
credentials live, and whether to reuse/merge those existing tables.

## Decision

Two new tables, `aggregator` and `aggregator_session(aggregator_id, api_key, api_secret,
last_sync_at, enabled, …)`:

- **Dedicated** to price aggregators — *not* merged with the bank/broker session tables.
- **App-global**: no `member_id`. A price API key is instance-wide, set by the admin.
- **Encrypted at rest**: `api_key`/`api_secret` hold AES-GCM ciphertext via `CryptoEncryption`
  (see [AES-256-GCM for crypto secrets](./2026-03-01-aes-gcm-crypto-secrets.md)); nullable (Yahoo
  needs none). Never serialized (`@JsonIgnore`), never logged (`@ToString` exclude).
- **Several sessions per aggregator** so one aggregator can rotate over multiple keys; `enabled`
  pauses an aggregator or a single key without deleting it.

## Alternatives considered

### A. One unified table for every external session (bank + broker + price)

- **Pros**: one place for all credentials; a single encryption/rotation code path.
- **Cons**: credential shapes are wildly heterogeneous (a CoinGecko key vs Boursorama cookies vs a
  TR token vs an open-banking OAuth consent) → a wide sparse table or an untyped JSON blob on
  security-sensitive data. Worse, price keys are **app-global** while bank/broker sessions are
  **per-member** — mixing both in one table breaks the member-scoping invariant the whole app relies
  on ("never query a repo without a member filter"). Different lifecycles and risk levels too.

### B. Member-scoped price keys

- **Pros**: symmetric with bank/broker sessions.
- **Cons**: a price is a shared, objective fact for the whole family; per-member keys would duplicate
  quota and complicate the router for no benefit.

### C. Keep the single `COINGECKO_DEMO_API_KEY` env var

- **Pros**: zero schema.
- **Cons**: one key only (no rate-limit spreading), no runtime add/rotate, no per-key back-off, and
  no home for a second aggregator's credentials.

## Reasoning

The only real DRY win of a unified table — shared encryption — is **already** centralized in
`CryptoEncryption`, so merging the tables buys almost nothing while costing type-safety and the
global-vs-member scoping clarity. Typed, purpose-scoped tables keep each integration's credential
shape explicit and keep the app-global price config cleanly separate from per-member bank data.

## Trade-offs accepted

- Adding a new aggregator is a migration + adapter (consistent with the columns-per-aggregator choice
  in `financial_asset`), not a config-only change.
- Two more tables instead of one; the encryption logic pattern is repeated (not the code) across the
  session tables.

## Consequences

- `V54__aggregator.sql` creates both tables and seeds the `coingecko` / `yahoo` rows (no sessions —
  the admin adds keys from the admin panel; until then CoinGecko runs anonymous, Yahoo needs no key).
- `AggregatorService` owns encrypt-on-write / decrypt-on-read and exposes decrypted credentials
  (with the session id) to the adapters.
- The price adapters stay Spring singletons: at call time they pick an enabled session and apply its
  key per request, tracking rate-limit back-off per session (wired in the follow-up adapter change).
- The `COINGECKO_DEMO_API_KEY` env var is retired when the CoinGecko adapter is rewired to read keys
  from `aggregator_session`.
