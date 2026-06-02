# ADR: Apply FX conversion inside the Yahoo price provider

> Date: 2026-05-19
> Status: ✅ Active

## Context

`YahooFinancePriceProvider` was returning `Meta.regularMarketPrice` verbatim
under the name "EUR price". For tickers like `AAPL` (USD), `8729.T` (JPY) or
`LLOY.L` (GBp / London pence), the raw number is in the asset's native
currency. Downstream code (`PriceService`, `AccountService.liveBalanceEur`,
the holdings table) treated the value as already-EUR and multiplied it by
quantity.

The visible symptom was a Trade Republic account displaying ~93 000 € for
real holdings of ~3 282 €. The math lined up: a Sony Japan position of
~3 000 ¥ per share read as 3 000 € per share inflated the portfolio by the
JPY→EUR factor (~170×).

The same class of bug existed in `getHistoricalPricesEur` and
`getIntradayPricesEur` — both passed each candle's `close` straight into a
`BigDecimal` named `priceEur` with no FX step.

We had to decide:
- **Where** to apply the FX (in the provider vs. at the call sites in
  `PriceService` / `AccountService`).
- **What FX rate** to use for historical and intraday series (per-day
  vs. snapshot).
- **How** to behave when the FX lookup itself fails.

## Decision

FX conversion is the responsibility of the **provider**. The port
contract (`PriceProviderPort.getPricesEur`) returns EUR-denominated
prices; any provider that fetches in a foreign currency must convert
before returning.

Concretely in `YahooFinancePriceProvider`:

- A `getFxRateToEur(currency)` helper hits Yahoo's own
  `/v8/finance/chart/{currency}EUR=X` endpoint (no new dependency, same
  `WebClient`, same `Meta` record).
- The result is cached in-process for 15 minutes (`fxCache`,
  `ConcurrentHashMap`). The TTL matches the existing price cache so the
  freshness contract for the whole pipeline is consistent.
- `EUR` short-circuits to `BigDecimal.ONE`. `null`/blank currency on the
  payload also short-circuits to `ONE` (preserves pre-fix behaviour for
  broken Yahoo responses).
- `GBp` / `GBX` (London pence) is resolved as `GBP / 100`. This is a
  market-data convention, not a real currency.
- If the FX fetch fails, the helper returns `null` and the price is
  **skipped** — the map simply does not contain that ticker. We never
  fabricate a rate.

For historical and intraday series, **today's FX rate is applied to all
points in the series**. We do not per-day-FX each candle.

To remove the related fallback that re-introduced the same bug at the
read side, `AccountService.toHoldingResponse` no longer falls back to the
stored `AccountHolding.currentPrice` when the live EUR price is missing —
`currentValueEur`, `pnlEur`, `pnlPercent` are all returned as `null`. The
DTO already advertised `currentValueEur` as nullable.

A one-shot `PriceFxCleanupRunner` (`@Order(0)`, gated by an
`app_setting` flag created in `V31`) purges `price_snapshot` at the
first boot after deploy so the 12-month backfill (`PriceBackfillRunner`)
rebuilds the history with FX applied.

## Alternatives considered

### Convert at the call site (in `PriceService.toEur` or `AccountService`)

- **Pros**: Provider keeps the literal "what Yahoo returned" shape; FX
  logic stays close to the consumer.
- **Cons**: Every consumer would need to know the response's native
  currency, which means leaking `Meta.currency` past the port. That
  contradicts the ports-and-adapters contract (the port returns EUR);
  it also duplicates the FX call everywhere the provider is used
  (`PriceService.getPriceEur`, `refreshPrices`, historical, intraday).
  CoinGecko already returns EUR — there is nothing to harmonise on the
  call side.

### Per-day FX for historical series

- **Pros**: A 250-day chart of a USD asset is more accurate — each
  candle is converted with its own day's rate.
- **Cons**: 250× more FX calls per asset on backfill. Yahoo's chart
  endpoint for an FX pair returns the same series shape — we *could*
  pull a single series and align timestamps — but the alignment logic
  (holidays, missing closes, weekend skip on FX vs. equities) is
  fragile and not worth the precision for a personal-finance tool.
  The display lives at a daily granularity and on a chart the user
  reads to ±5 %.

### Persisted FX rates table

- **Pros**: Auditable, reproducible historical valuations.
- **Cons**: Brings a new entity, a new repository, a new scheduler tick
  and a new backfill — for a single-user self-hosted app. The
  in-process 15-minute cache is sufficient given the load pattern
  (one user, hourly scheduler, on-demand reads).

### Keep the native-price fallback in `AccountService`

- **Pros**: Holdings never display blank columns.
- **Cons**: The fallback reads `AccountHolding.currentPrice` — a column
  populated by the broker sync with whatever currency the broker
  exposes (TR is EUR today on FR accounts, but the field has no
  currency tag and there is nothing to keep that invariant in the
  future). Treating it as EUR is exactly the bug we just fixed,
  one level higher. The DTO already supported `null`.

## Reasoning

The port boundary is the right place to enforce "prices are EUR". Once
that contract holds, every consumer is correct by construction: no
fallback gymnastics, no per-call-site FX awareness, no risk of
re-introducing the bug in a new code path that forgets to call
`toEur`.

Today's FX for historical series is a pragmatic call: the precision
gain from per-day FX is invisible on the curve we draw, and the
operational cost (250× more calls, alignment edge cases) is real.
The choice is documented as a comment above each loop so the next
reader doesn't "fix" it.

Refusing to fabricate a rate when the FX endpoint fails is the same
discipline as refusing to fabricate a price: an empty entry surfaces
the failure (the holding shows `null` / blank), whereas an invented
number silently lies. The previous bug had exactly that shape.

## Trade-offs accepted

- **Historical series use today's FX**, not the day's FX. The drift
  is small on stable pairs (EUR/USD typically moves <10 % per year)
  and invisible on the time-range chart. If we ever need true
  reconstruction (e.g. for tax reports), revisit by pulling the FX
  series alongside the price series.
- **The 12-month snapshot history is wiped once on deploy** by
  `PriceFxCleanupRunner`. Every existing row predates the fix and is
  unsafe to keep; rebuilding is cheap and idempotent.
- **A failed FX fetch hides the price entirely.** The holding row
  shows `null` instead of an inflated number — correct but uglier. The
  frontend `recomputeWithLivePrice` helper already tolerates
  `null` live prices.
- **In-process FX cache only.** Restarts re-fetch each currency once.
  With Picsou's load that is a non-event (one hit per currency per
  process lifetime).

## Consequences

- `YahooFinancePriceProvider` grows `fxCache`, `getFxRateToEur`,
  `fetchFxRate`, `applyFx`. Constructor is package-private so tests
  can inject a stub `WebClient` via `ExchangeFunction`.
- `PriceProviderPort` contract is now strictly EUR — documented in
  this ADR; the existing CoinGecko adapter already complied.
- `AccountService.toHoldingResponse` returns `null` for value/pnl
  when the live price is missing. `AccountService.liveBalanceEur`
  skips holdings without a live price instead of falling back to
  `holding.currentPrice`.
- New migration `V31__price_cleanup_gate.sql` and new
  `PriceFxCleanupRunner` (`@Order(0)`, runs before
  `PriceBackfillRunner` which is `LOWEST_PRECEDENCE`). The runner is
  idempotent via the `price.fx_fix_cleanup_done` flag plus the
  existing `AppSettingRepository.compareAndSet` CAS.
- `PriceSnapshotRepository.deleteAllSnapshots()` and
  `PriceService.clearPriceCache()` are introduced for the runner.
- New tests: `YahooFinancePriceProviderTest` (10 cases covering EUR
  passthrough, USD/JPY/GBp conversion, FX 404 → empty map, currency
  null → raw price, cross-ticker FX cache hit, FX TTL not exercised
  in tests, FX applied across historical and intraday series) and
  two new cases in `AccountServiceTest` covering the removed
  fallback.
