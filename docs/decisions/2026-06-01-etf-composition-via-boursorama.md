# ADR: ETF composition via Boursorama (single source), replacing per-issuer scraping

> Date: 2026-06-01
> Status: ✅ Active

## Context

The holding detail modal's **Insight** section shows a security's asset type and,
for ETFs, its composition by **companies**, **countries**, and **sectors**.

The previous design ([ADR 2026-05-31](./2026-05-31-etf-composition-issuer-holdings.md))
fetched composition from each **issuer's own holdings file** (one adapter per issuer,
selected by matching the fund name), with iShares as the only implemented adapter and
Amundi/Vanguard/Xtrackers as stubs.

That design is **broken in practice**. Probing the live endpoints on 2026-06-01:

| Dependency | Live status |
|---|---|
| iShares product-screener (`product-screener-v3.1.jsn`) | ❌ HTTP 500 "Error page" for every config variant — endpoint decommissioned |
| iShares holdings CSV (`…/1467271812596.ajax`) | ❌ HTTP 404 — the content id is gone from the product page |
| iShares download link in page HTML | ❌ Now built in JS at runtime; no static URL to scrape |
| Amundi / Vanguard / Xtrackers | ❌ Never implemented (stubs return empty) |

Two real user holdings reproduce the failure:
- **NQSE.DE** — iShares NASDAQ 100 (physical). Classified ETF, screener 500 → composition null.
- **PUST.PA** — Amundi PEA Nasdaq-100 (synthetic/swap). The synced `name` is just `"PUST.PA"`,
  so the issuer-name matcher picks no adapter; even if it did, Amundi is a stub.

The root problem is structural: reverse-engineered, undocumented per-issuer endpoints break
without notice, and the fund-name matcher fails whenever the synced label is poor.

## Decision

Replace the four issuer adapters with a **single `BoursoramaCompositionProvider`** that
resolves a security by **ticker/ISIN** and parses all three breakdowns from Boursorama's
public, server-rendered tracker composition page. Keep Yahoo for the asset-type badge
(unchanged; no authentication).

Boursorama is a strong fit: it is **already the app's holdings sync source**, its pages are
**server-rendered (no JS execution, no auth/crumb)**, labels are in **French** (the app's
primary locale), and — crucially — it covers all three breakdowns, including the **country**
breakdown that no free Yahoo endpoint exposes. It even works for synthetic ETFs (it publishes
the index look-through, not the swap line).

### Resolution + parsing (validated against live data 2026-06-01)

1. **Symbol resolution** (cached ~24h): `GET https://www.boursorama.com/recherche/?query={bareTicker | ISIN}`
   returns a 302 whose `Location: /cours/{SYMBOL}/` header carries Boursorama's internal symbol.
   - `NQSE` → `1zNQSE`; ISIN `IE00BYVQ9F29` → `1zNQSE`
   - `PUST` → `1rTPUST`; ISIN `FR0011871110` → `1rTPUST`
   - The **exchange suffix must be stripped** (`PUST.PA` → no match; `PUST` → match). The
     existing `bareTicker()` helper already does this.
2. **Composition fetch + parse**: `GET /bourse/trackers/cours/composition/{SYMBOL}/`:
   - **Countries** ← inline amCharts JSON block with `"id":"regional"`, field `amChartData`:
     `[{"name":"Etats-Unis","value":97.25}, …]`
   - **Sectors** ← inline amCharts JSON block with `"id":"sector"`:
     `[{"name":"Technologie","value":57.42}, …]`
   - **Companies** ← HTML table `c-table-gauge`: header cell = holding name,
     attribute `data-gauge-current-step` = weight %. Top 10 rows.
     (Synthetic ETFs show only the swap line here → companies omitted.)
   - **asOf** ← "Date du portefeuille : DD/MM/YYYY"; **source** = "Boursorama".

### Verified coverage

| Bar | NQSE.DE (physical iShares) | PUST.PA (synthetic Amundi) |
|-----|----------------------------|-----------------------------|
| Companies | ✅ NVIDIA 8.29%, Apple, Microsoft… | ⏭️ none (synthetic — none exists anywhere) |
| Countries | ✅ Etats-Unis 97.25%, Pays-Bas, Canada | ✅ Etats-Unis 96.88%, Pays-Bas, Canada |
| Sectors | ✅ Technologie 57.42% … (10) | ✅ Technologie 53.65% … (10) |

## Alternatives considered

### Yahoo `quoteSummary` (`topHoldings`) as the source
- **Pros**: one generic endpoint; returns top companies + 11 sector weights for any ETF.
- **Cons**: needs the cookie+crumb handshake the user rejected; **no country breakdown**;
  returns no holdings for synthetic ETFs. Rejected as primary; not needed at all once
  Boursorama covers companies + countries + sectors.

### Rebuild per-issuer scraping against current endpoints
- **Pros**: authoritative source; keeps all three bars.
- **Cons**: the approach just proved it breaks without notice; per-issuer reverse-engineering
  is a maintenance treadmill; some issuers now require JS execution. Rejected.

### Boursorama single source (chosen)
- **Pros**: one provider for all issuers; same vendor the app already trusts for sync;
  server-rendered (no auth/crumb); covers all three bars incl. country; works for synthetic
  ETFs; resolves by ticker/ISIN so the fund-name matching problem disappears.
- **Cons**: still HTML/JSON scraping (can break on a Boursorama redesign); labels are French
  (handled by an i18n mapping, below); bare-ticker resolution could in rare cases hit a ticker
  collision (fail-soft to "unavailable").

## Consequences

**Backend**
- New `adapter/BoursoramaCompositionProvider` implements the `EtfCompositionProvider` port.
- The port's `fetch(...)` returns the **aggregated** `EtfComposition` directly (Boursorama is
  pre-aggregated), so `SecurityInsightService.aggregate()`/`groupTop()` are removed; the
  service keeps `classify()` (Yahoo/CoinGecko) + caching + orchestration.
- `supports(...)` no longer matches on issuer name; the Boursorama provider supports any ETF.
- **Deleted**: `IsharesCompositionProvider` (+ test), `AmundiCompositionProvider`,
  `VanguardCompositionProvider`, `XtrackersCompositionProvider`.
- Asset-type classification (`YahooFinancePriceProvider.getInstrumentType`) is unchanged and
  still requires no authentication.

**Label i18n (decision: translate both sectors and countries)**
- The **backend normalises** Boursorama's French labels to **stable keys** in
  `WeightedSlice.label`: a fixed FR→key map for the ~11 Morningstar sectors (e.g.
  `"Technologie"` → `technology`) and a FR→key map for the common countries (e.g.
  `"Etats-Unis"` → `US`). Any **unmapped** label is passed through **verbatim** (the raw
  French string), so a slice is never blank.
- The **frontend translates** those keys via react-i18next for the Companies-/Countries-/
  Sectors-bar labels, with the received value as the **fallback** when no translation key
  exists (so passthrough French still renders). Company names are real names, not keys, and
  are always rendered verbatim.
- This keeps the API locale-agnostic and the existing `WeightedSlice` shape unchanged.

**"Unavailable" semantics**
- `SecurityInsightService` returns a non-null `EtfComposition` whenever **any** breakdown has
  data; the frontend already renders only non-empty bars. "Composition unavailable" shows only
  when all three are empty (e.g. Boursorama has no page for the security). Synthetic ETFs thus
  show countries + sectors instead of "unavailable".

**Frontend**
- `HoldingInsightSection.tsx`: show the composition block when any bar is present; keep the
  "unavailable" note only for the all-empty ETF case. (Companies-less synthetic ETFs render
  countries + sectors.)
- No API shape change (`SecurityInsightResponse`/`EtfComposition`/`WeightedSlice` unchanged).

**Docs/tests**
- Supersedes [ADR 2026-05-31](./2026-05-31-etf-composition-issuer-holdings.md); update
  `docs/features/security-insight.md`.
- Parser unit tests run against **saved HTML fixtures** (physical NQSE + synthetic PUST):
  regional/sector/holdings extraction, `asOf`, missing-section → empty. Resolution test
  (Location parsing, suffix strip). Service tests (partial → non-null, all-empty → null,
  caching, non-ETF skips provider). Frontend test (partial composition renders available bars).

## Trade-offs accepted

- **Still scraping.** Boursorama HTML/JSON can change. Mitigated by: same vendor already used
  for sync, server-rendered data (no JS/crumb), defensive parsing, fail-soft to "unavailable".
- **Ticker-only resolution in v1.** ISIN is not on the market-data path today; bare-ticker
  resolution is proven for the real holdings. ISIN-based disambiguation (the backend already
  has ISINs from sync + an `OpenFigiIsinConverter`) is a noted future enhancement.
- **French source labels** are normalised via the i18n maps above; unmapped countries fall back
  to French.

## Supersedes

[ADR 2026-05-31 — ETF composition from issuer holdings files](./2026-05-31-etf-composition-issuer-holdings.md)
