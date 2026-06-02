# Feature: ISIN to Yahoo Finance Ticker Conversion

> Last updated: 2026-04-13

## Context

Trade Republic returns account holdings with ISIN codes (e.g., `IE00BYVQ9F29`), but Yahoo Finance expects ticker symbols (e.g., `IWDA.AS`). This feature converts ISINs to Yahoo-compatible tickers via the OpenFIGI API, and also resolves the display name (e.g., "ISHARES CORE MSCI WORLD") for the frontend.

## How it works

### Key files

- `adapter/OpenFigiIsinConverter.java` — ISIN→ticker+name conversion via OpenFIGI `/v3/mapping` API
- `service/TradeRepublicSyncService.java` — calls `resolve()` during sync, stores ticker and name
- `adapter/YahooFinancePriceProvider.java` — rejects unconvertible ISINs via regex in `supports()`
- `frontend/src/components/shared/HoldingsCard.tsx` — displays name in title, ticker in square badge

### Flow

```
TR WebSocket → TrPosition(isin)
    ↓
TradeRepublicSyncService.upsertAccount()
    ↓
openFigiIsinConverter.resolve(isin)
    ↓
POST /v3/mapping  body: [{"idType":"ID_ISIN","idValue":"IE00BYVQ9F29"}]
    ↓
OpenFIGI returns array of results with ticker + exchCode + name
    ↓
pickBest() selects best exchange → composes ticker + Yahoo suffix
    ↓
Returns TickerResult(ticker="IWDA.AS", name="ISHARES CORE MSCI WORLD")
    ↓
Stored as AccountHolding.ticker + AccountHolding.name
    ↓
Frontend: h.name ?? h.ticker → shows name, falls back to ticker
```

## Exchange selection logic

`pickBest()` selects the Yahoo Finance ticker from multiple OpenFIGI results:

1. **Home exchange** — based on ISIN country prefix (`US`→US, `HK`→HK, `DE`→GY, etc.)
2. **US OTC/ADR** — for non-US ISINs, US listings often have best Yahoo coverage
3. **EU exchanges** — NA (Amsterdam), FP (Paris), GY/GR (Germany), LN (London)
4. **Any known exchange** — fallback

OpenFIGI `exchCode` is mapped to Yahoo suffix (e.g., `GY`→`.DE`, `NA`→`.AS`, `FP`→`.PA`, `HK`→`.HK`).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| OpenFIGI `/v3/mapping` endpoint | Direct identifier lookup, returns structured results with exchCode and name | `/v3/search` (keyword-based, different request/response format) |
| `TickerResult` record (ticker + name) | Frontend needs display name; avoids a second API call | Separate name lookup endpoint |
| Home exchange preference by ISIN country | US stocks get US tickers (better Yahoo coverage), HK stocks get `.HK`, etc. | Always EU exchanges → `NVD.DE` for NVIDIA works but less reliable |
| In-memory `ConcurrentHashMap` cache | Avoids repeated API calls during bulk sync | Database caching (adds complexity for ephemeral data) |
| Sentinel-free caching (store `TickerResult` or null via `Map.get()`) | Clean null check | Sentinel string `"ISIN"` (used previously, more error-prone) |

## Gotchas / Pitfalls

- **Wrong endpoint = silent failure**. The old code used `/v3/search` with a malformed body. OpenFIGI returns `400` but WebClient doesn't throw — it returns null data. Always verify with `curl` against the API when debugging.
- **`Map.of()` has a 10-entry limit**. Use `Map.ofEntries()` for the exchange suffix maps (30+ entries).
- **`useMemo` before conditional return**. React hooks must not be after `if (!data) return`. In `DashboardPage`, the `historyForRange` memo must be computed before the early return.
- **Yahoo Finance rejects ISIN-format strings**. `YahooFinancePriceProvider.supports()` uses regex `[A-Z]{2}[A-Z0-9]{9}[A-Z0-9]` to detect 12-char ISINs and returns false. Unconverted ISINs never get price data.
- **Deduplication aggregates by ticker**. Multiple ISINs mapping to the same ticker are merged in `TradeRepublicSyncService` via `Map.merge()`. The name from the first ISIN wins.
- **Some tickers may not exist on Yahoo Finance**. German-listed tickers like `6RJ0.DE` (internal Bloomberg ID) may not resolve. The home-exchange-first strategy mitigates this.

## Tests

- No dedicated unit tests for `OpenFigiIsinConverter` (WebClient mock setup is complex)
- Manual verification with `curl` against OpenFIGI API:
  - `US0378331005` (Apple) → `AAPL`
  - `IE00B4L5Y983` (iShares MSCI World) → `IWDA.AS`
  - `KYG9830T1067` (Xiaomi) → `1810.HK`
  - `DE0007100000` (Mercedes-Benz) → `MBG.DE`
- Backend tests: `mvn test` passes (`GoalServiceTest`)

## Links

- Related feature: [price-service.md](./price-service.md) (price lookups)
- Related feature: [trade-republic.md](./trade-republic.md) (TR sync)
- No ADR needed — this is an adapter for external data transformation
