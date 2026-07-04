# ADR: Multi-exchange crypto CSV import framework

> Date: 2026-07-02
> Status: ✅ Active

## Context

The Crypto.com App CSV import (see the 2026-06-23 reward-classification ADR) proved the pipeline:
CSV → normalized BUY/SELL/REWARD transactions → VWAP holdings → per-program reward stats. But the
implementation was single-source: a `com.picsou.cryptocom` package, `/api/cryptocom/**` endpoints,
a Crypto.com-only import tab, and stats shown only for `provider == "Crypto.com"` accounts.

Users hold crypto on several platforms (Kraken, Binance, Bybit, Bitstack, the Crypto.com Exchange,
hardware wallets via Ledger Live, hot wallets) and want the same import + statistics for each,
plus a global and a per-exchange portfolio view.

## Decision

Generalize the Crypto.com importer into a **parser-per-source framework** in `com.picsou.crypto`:

- A `CryptoCsvParser` interface (`sourceId`, `label`, `provider`, `supports(CsvTable)`,
  `parse(CsvTable)`, `detectionOrder`), one Spring component per source, auto-detected by header
  signature — the user uploads any export without picking a format. A documented **generic**
  format (lowest detection precedence) covers sources without a dedicated parser.
- A shared `ParsedCryptoTx` normalized row consumed by a single `CryptoImportService`
  (preview/execute, price backfill, history reconstruction) and `CryptoStatsService`
  (per-account + consolidated), under `/api/crypto/**`.
- A **valuation-enrichment step** at execute time: exports that carry no fiat value (Kraken
  rewards, crypto-quoted trades, wallet transfers) are valued from the backfilled daily price
  history (`quantity × price(date)`, forward-filled). REWARD rows keep `pricePerUnit = 0` so the
  diluted-VWAP cost-basis rule of the previous ADR is preserved; only their income value is filled.
- One account per source (`externalAccountId = crypto_csv:<sourceId>`, `provider` from the
  parser); cross-platform consolidation stays in the stats layer.

The endpoint/package rename ships without backward compatibility: the `cryptocom` surface only
ever existed on an unmerged WIP branch.

## Alternatives considered

### Per-exchange import services and endpoints

- **Pros**: no abstraction to design; each exchange evolves independently.
- **Cons**: N copies of the preview/execute/backfill/history pipeline; N UI flows; stats and
  consolidation logic duplicated or entangled with source specifics.

### API-based sync per exchange (Kraken/Bybit keys, like the Binance adapter)

- **Pros**: no file handling; always current balances.
- **Cons**: balances only — no transaction history, so no cost basis, no reward attribution, no
  timelines: the headline asks. Key management per exchange. CSV exports carry the full history
  in one shot. The existing Binance API sync stays for live balances; both sources meet in the
  consolidated stats.

### Valuing unvalued rows at 0 (no enrichment)

- **Pros**: no dependency on price backfill.
- **Cons**: Kraken/Binance reward income would read €0 — the per-program totals become useless
  for exactly the exchanges that need them.

## Reasoning

The Crypto.com pipeline was already source-agnostic from `ParsedCryptoTx` onward — the only
Crypto.com-specific code was row classification. Making *classification* the pluggable unit
(one parser class per source) is the smallest abstraction that turns "add an exchange" into
"add one class + tests" while keeping a single code path for holdings, statistics, valuation
and history. Header-signature detection removes a whole class of user error (wrong format
picked) at negligible cost since export headers are mutually distinctive.

## Trade-offs accepted

- Enriched valuations are approximations (daily close, forward-filled) — fine for portfolio
  statistics, not accounting-grade.
- Fees on exchange fills are ignored; cost basis uses the gross fiat leg.
- Bybit/Bitstack export shapes vary across versions; parsers tolerate known header variants and
  fail detection (with a clear error) on unknown ones rather than guessing.
- Kraken/Binance CSVs identify trades by refid/timestamp grouping; two distinct fills in the same
  Binance second would merge — acceptable for personal-portfolio granularity.

## Consequences

- Package `com.picsou.crypto` (+ `crypto.parsers`), `CryptoController` (`/api/crypto/**`);
  `com.picsou.cryptocom` and `/api/cryptocom/**` are gone.
- Frontend: `features/crypto` slice, one generic import tab (auto-detect badge), `/crypto` page
  with a Global / per-exchange selector, stats section on every CRYPTO account detail page.
- New sources are one `CryptoCsvParser` implementation + a test class; `RewardKind` is shared
  across all of them.
