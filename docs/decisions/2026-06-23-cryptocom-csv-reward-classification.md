# ADR: Crypto.com CSV import with reward-program classification

> Date: 2026-06-23
> Status: ✅ Active

## Context

Crypto.com exposes no usable personal-portfolio API, but the App exports a full transaction-history
CSV. Users want per-coin statistics (when bought, average buy-in, current value) **and** a breakdown
of income earned from each Crypto.com program: Earn, staking, Supercharger, Airdrop Arena, cashback.

Picsou already derives crypto positions from `BUY`/`SELL` transactions (`HoldingComputeService`,
VWAP cost basis). The open question was how to model the zero-cost reward payouts so they both feed
the positions and remain attributable per program.

## Decision

Add a first-class `REWARD` transaction type plus a `RewardKind` sub-type
(`EARN, STAKING, SUPERCHARGER, AIRDROP, CASHBACK, REFERRAL, OTHER`). The Crypto.com importer
classifies each CSV row's `Transaction Kind` into BUY / SELL / SWAP(=SELL+BUY) / REWARD / ignored /
unknown. `REWARD` rows add quantity at **zero cost**, diluting the VWAP average buy-in, and carry a
`RewardKind` so a stats endpoint can total gains per program.

## Alternatives considered

### Model rewards as zero-cost `BUY`s

- **Pros**: no schema/enum change; holdings and average buy-in already come out correct.
- **Cons**: indistinguishable from purchases — impossible to total or chart "Earn vs staking vs
  airdrop" income, which is the headline ask.

### Separate `crypto_reward` table

- **Pros**: clean separation of income from trades.
- **Cons**: a second source of truth for quantity; `HoldingComputeService` would need to read two
  tables; more joins, more drift risk. Overkill for a single-user self-hosted app.

## Reasoning

A `REWARD` type + `RewardKind` sub-type is the smallest change that satisfies both requirements:
positions stay derived from a single `transaction` table (one code path shared with manual and
Finary imports), while the sub-type unlocks per-program totals and charts. `tx_type` is a plain
`VARCHAR(20)`, so only a nullable `reward_kind` column is needed (migration V38).

## Trade-offs accepted

- Rewards dilute the average buy-in (standard cost-basis accounting). This is intentional but means
  "average buy-in" reflects acquisition cost, not the price paid on purchases alone.
- The native-fiat `Native Amount` is taken at face value; historical FX is not applied for non-EUR
  exports.
- Only the Crypto.com **App** CSV schema is supported; the Exchange export is out of scope.
- Earn/Supercharger lock & unlock rows are dropped (net-zero on the aggregated account).

## Consequences

- New package `com.picsou.cryptocom` (parser, mapper, import + stats services, DTOs) and
  `CryptoComController` (`/api/cryptocom/**`).
- `HoldingComputeService` folds `REWARD` into the BUY branch (zero numerator, qty in denominator).
- Frontend: a Crypto.com import tab and a per-coin stats section (rewards-by-program bar chart +
  per-coin accumulation chart) on the account detail page.
- Future importers that pay rewards (e.g. other exchanges) can reuse `REWARD` + `RewardKind`.
