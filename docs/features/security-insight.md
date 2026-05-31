# Feature: Security Insight (asset type + ETF composition)

> Last updated: 2026-05-31

## Context

The holding detail modal shows an **Insight** section: the asset type of a security
(ETF / stock / crypto) and, for ETFs, its composition by companies, countries, and
sectors. It answers "what am I actually holding?" without leaving the dashboard.

## How it works

The frontend opens the modal and calls `GET /api/securities/{ticker}/insight?name=…`
(the fund `name` is the issuer hint). The backend classifies the asset type, and for
ETFs fetches and aggregates the issuer's published holdings file, then caches the
result in memory for a few days.

- **Type detection** — `SecurityInsightService.classify()`: crypto if the ticker is
  known to `CoinGeckoPriceProvider`; otherwise map Yahoo `meta.instrumentType`
  (`ETF`/`MUTUALFUND` → `ETF`, `EQUITY` → `STOCK`, `CRYPTOCURRENCY` → `CRYPTO`, else
  `UNKNOWN`). The `instrumentType` comes from the same unauthenticated Yahoo `chart`
  endpoint already used for prices.
- **Composition** — the service iterates the registered `EtfCompositionProvider`s; the
  first whose `supports(ticker, name)` matches and whose `fetch` returns non-empty
  holdings wins. Raw holdings are aggregated: top-N companies by weight, weights summed
  by country and by sector (each top-N, descending). Percentages are rounded to two
  decimals. The backend returns **top-N only**; the frontend adds the `Others`
  remainder so the bars are honest about not being exhaustive.
- **Cache** — `ConcurrentHashMap<ticker, CachedInsight>` with a multi-day TTL (longer
  than `PriceService`, since composition changes slowly).

### Key files

Backend:
- `controller/SecurityController.java` — `GET /api/securities/{ticker}/insight`, optional `name` query param. Not member-scoped (market data, like `PriceController`).
- `service/SecurityInsightService.java` — type classification, provider orchestration, aggregation, in-memory cache.
- `port/EtfCompositionProvider.java` — port: `supports(ticker, name)` + `fetch(...) → RawEtfHoldings`.
- `adapter/IsharesCompositionProvider.java` — **reference adapter** (product screener → CSV holdings → parse).
- `adapter/AmundiCompositionProvider.java`, `VanguardCompositionProvider.java`, `XtrackersCompositionProvider.java` — stubs (return empty until endpoints are discovered).
- `adapter/YahooFinancePriceProvider.java` — `getInstrumentType(ticker)` + `instrumentType` on the `Meta` record.
- `dto/SecurityInsightResponse.java`, `dto/EtfComposition.java`, `dto/WeightedSlice.java`.

Frontend:
- `components/ui/partition-bar.tsx` — hand-ported partition-bar primitive (mobile-friendly: segments shrink, titles truncate).
- `components/shared/HoldingInsightSection.tsx` — type badge + three partition bars (Companies / Countries / Sectors) with an `Others` remainder; fallbacks for stock/crypto/unavailable/loading.
- `components/shared/HoldingDetailModal.tsx` — renders `<HoldingInsightSection>` after the stats grid; gated on the modal being open.
- `features/accounts/api.ts` (`securityInsight`) and `features/accounts/hooks.ts` (`useSecurityInsight`).
- `demo/index.ts` — mock handlers for the demo holdings (stocks, crypto, and two iShares ETFs).

### Flow

```
HoldingDetailModal (open)
  → useSecurityInsight(ticker, name)
    → GET /api/securities/{ticker}/insight?name=…
       → SecurityInsightService.getInsight()
          ├─ cache hit? → return
          ├─ classify(): CoinGecko? → CRYPTO | Yahoo instrumentType → ETF/STOCK/CRYPTO/UNKNOWN
          └─ if ETF: first supporting EtfCompositionProvider.fetch() → aggregate (top-N companies / countries / sectors)
       → cache + return SecurityInsightResponse
  → HoldingInsightSection: type badge (+ 3 partition bars for ETF, Others remainder)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Yahoo `chart` for instrument type | Already called for prices, no auth | Yahoo `quoteSummary` (needs cookie + crumb) |
| Scrape issuer holdings files | Only auth-free source with country + sector | Yahoo `quoteSummary`; third-party datasets |
| One adapter per issuer behind a port | Unresolved issuers degrade gracefully | Single hard-coded provider |
| In-memory cache, multi-day TTL | Composition changes slowly; matches `PriceService` style | Persisting to DB |
| Backend returns top-N, frontend adds `Others` | Bars stay honest about coverage | Backend fabricating a 100% total |

### Per-issuer URL patterns

| Issuer | `supports` match | Holdings source | Status |
|--------|------------------|-----------------|--------|
| iShares (BlackRock) | name contains "ishares" | Product screener JSON → `…{productPageUrl}/1467271812596.ajax?fileType=csv&fileName={ticker}_holdings&dataType=fund` (CSV: Name, Weight %, Sector, Location) | ✅ Implemented |
| Amundi / Lyxor | name contains "amundi"/"lyxor" | TODO — discover endpoint | ⏳ Stub (returns empty) |
| Vanguard | name contains "vanguard" | TODO — discover endpoint | ⏳ Stub (returns empty) |
| Xtrackers (DWS) | name contains "xtrackers"/"x-trackers" | TODO — discover endpoint | ⏳ Stub (returns empty) |

## Gotchas / Pitfalls

- **Issuer hint comes from the fund name**, not the ticker. The frontend passes
  `line.name`; without it, `supports` can't pick an adapter and an ETF degrades to the
  type badge only.
- **Partial coverage is expected.** Only iShares is wired end-to-end. Other issuers'
  ETFs intentionally show the type badge with no bars until their endpoints are added.
- **iShares CSV is quirky**: a leading metadata block before the header row, blank
  lines that terminate the holdings section, quote-aware comma splitting, and several
  `asOf` date formats (`MMM d, yyyy`, `d MMM yyyy`, `d-MMM-yyyy`, …). Parsing is
  defensive; on any failure the adapter returns empty rather than throwing.
- **The query is gated on the modal being open** (`enabled`), so it doesn't fire for
  every rendered (but closed) modal.
- **Demo mode keys on the exact path** (`GET /securities/{ticker}/insight`, query
  stripped); unmatched tickers fall through to `{}`, which the UI treats as "no
  insight" and renders nothing.

## Tests

- `IsharesCompositionProviderTest` — CSV parsing, blank-line termination, missing
  header, quote-aware splitting, screener parsing, `supports`, weight parsing.
- `SecurityInsightServiceTest` — crypto/equity/unknown classification, ETF aggregation,
  null composition when no provider resolves, caching.
- `HoldingInsightSection.test.tsx` — three bars from a mock ETF composition, the
  `Others` remainder, stock fallback (badge only), unavailable ETF note, empty-response
  no-render, loading spinner.

## Links

- Related ADR: [ETF composition from issuer holdings files](../decisions/2026-05-31-etf-composition-issuer-holdings.md)
- Related: [Price service](./price-service.md), [Live prices (holdings)](./live-prices-holdings.md)
