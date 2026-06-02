# ADR: ETF composition from issuer holdings files (no auth), pluggable per issuer

> Date: 2026-05-31
> Status: ⚠️ Superseded by [ADR 2026-06-01 — ETF composition via Boursorama](./2026-06-01-etf-composition-via-boursorama.md)

## Context

The holding detail modal gained an **Insight** section that shows a security's
asset type (ETF / stock / crypto) and, for ETFs, its composition broken down by
**companies**, **countries**, and **sectors** (rendered with a partition bar).

Asset-type detection is cheap: the unauthenticated Yahoo `chart` endpoint we
already call for prices exposes `meta.instrumentType`. The hard part is the **ETF
composition**: weights by holding, country, and sector. There is no free,
auth-free, redistributable API that returns full country/sector breakdowns for an
arbitrary European-listed ETF.

## Decision

- **Asset type**: reuse the existing unauthenticated Yahoo `chart` endpoint and read
  `meta.instrumentType` (`ETF`/`MUTUALFUND` → ETF, `EQUITY` → STOCK,
  `CRYPTOCURRENCY` → CRYPTO). Crypto is also inferred when the ticker is known to
  CoinGecko.
- **ETF composition**: scrape the **holdings files the issuers publish themselves**
  (e.g. iShares' CSV holdings export). One adapter per issuer behind a single
  `EtfCompositionProvider` port, selected by matching the fund name. Aggregate the
  raw per-line holdings into top-N companies / summed-by-country / summed-by-sector.
- **Cache**: in-memory `ConcurrentHashMap` with a multi-day TTL, in the same spirit
  as `PriceService` (longer TTL, since composition changes slowly).

## Alternatives considered

### Yahoo `quoteSummary` (`topHoldings`, `fundProfile`)

- **Pros**: single API, returns sector/holding weights directly.
- **Cons**: requires a cookie + crumb handshake (effectively authentication), which
  the user explicitly rejected; brittle and against Yahoo's terms; still no reliable
  country breakdown.

### A third-party / GitHub dataset

- **Pros**: no scraping code to maintain.
- **Cons**: no maintained, free, redistributable dataset covers country + sector for
  arbitrary EU-listed ETFs; staleness and licensing are unclear.

### Issuer holdings files, one adapter per issuer (chosen)

- **Pros**: authoritative source, no auth, country + sector + holdings all present;
  degrades gracefully per issuer.
- **Cons**: each issuer has a different URL pattern and file format; some are
  undocumented and must be reverse-engineered; coverage is partial.

## Reasoning

The issuer files are the only auth-free source that carries all three breakdowns and
comes straight from the fund manager. The port/adapter split means an unresolved or
unsupported issuer simply returns "no composition" and the UI falls back to the type
badge alone — no hard failure, no blocking the whole feature on full issuer coverage.

## Trade-offs accepted

- **Partial coverage.** Only iShares is fully implemented and verified as the
  reference adapter. Amundi, Vanguard, and Xtrackers are stubs that return empty
  until their endpoints are discovered; their ETFs show the type badge only.
- **Scraping fragility.** Issuer URL patterns and CSV layouts can change without
  notice; the adapters parse defensively and fail soft (empty → "unavailable").
- **Top-N only from the backend.** The service returns top-N slices; the frontend
  computes the `Others` remainder, so the bars never claim to be exhaustive.

## Consequences

- New backend port `EtfCompositionProvider` with one `@Component` adapter per issuer;
  new `SecurityInsightService` orchestrates type detection + composition + caching;
  new `SecurityController` exposes `GET /api/securities/{ticker}/insight` (market data,
  not member-scoped, like `PriceController`).
- `YahooFinancePriceProvider` gains `getInstrumentType(ticker)` and an
  `instrumentType` field on its `Meta` record.
- Frontend gets a hand-ported `partition-bar` UI component and a
  `HoldingInsightSection` wired into the holding detail modal.
- Adding an issuer = implement one adapter; nothing else changes.

## Supersedes

None.
