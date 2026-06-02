# ADR: Dual Bank Providers (Enable Banking + Powens)

> Date: 2026-03-01
> Status: ⚠️ Revised (2026-04-26) — Powens marked experimental, disabled in 1.0.0

> **What changed (2026-04-26).** The Powens adapter ships in 1.0.0 but has
> NOT been tested end-to-end against a real Powens tenant. The `@Primary`
> annotation was removed from `PowensBankConnector`, so even if
> `POWENS_CLIENT_ID` is set, Enable Banking remains the canonical
> `BankConnectorPort` implementation. The "zero-config switching"
> behaviour described below is therefore inactive in 1.0.0 — re-enabling
> Powens means re-adding `@Primary` once the adapter has been validated.
> The reasoning below is preserved as historical context.

## Context

French banks offer accounts that PSD2 open banking APIs cannot access: LEP (Livret d'Epargne Populaire), PEA (Plan d'Epargne en Actions), livrets regulated by the state, and some savings products. Enable Banking provides PSD2-compliant access but is limited to payment accounts. To achieve full coverage of French banking products, a second provider was needed.

## Decision

Support both Enable Banking (PSD2, open banking) and Powens/Budget Insight (screen scraping) as bank connector implementations behind `BankConnectorPort`. Powens is activated as the primary provider when `POWENS_CLIENT_ID` is set, using `@Primary` + `@ConditionalOnExpression`. Enable Banking remains available as a fallback when Powens is not configured.

Key implementation details:

- `PowensBankConnector` is annotated `@Primary` and `@ConditionalOnExpression("'${app.powens.client-id:}'.length() > 0")` -- it only registers as a bean when the Powens credentials are configured.
- `EnableBankingBankConnector` is always registered (unless its own credentials are missing, in which case it starts with a null private key and throws on first use).
- `SyncService` receives whichever `BankConnectorPort` bean Spring injects -- it never references adapter classes directly.

## Alternatives considered

### Single provider: Enable Banking only

- **Pros**: Simpler; PSD2 is the official standard; free tier available
- **Cons**: Cannot access LEP, PEA, livrets, life insurance, and other non-payment accounts common in France

### Single provider: Powens only

- **Pros**: Full coverage of French account types; screen scraping accesses everything
- **Cons**: Paid service; screen scraping is inherently fragile (breaks when banks change their UI); not PSD2-compliant

### Custom scraping per bank

- **Pros**: Full control; no third-party dependency
- **Cons**: Extremely high maintenance; each bank needs a separate scraper; breaks frequently

## Reasoning

Powens provides the broadest coverage of French account types via screen scraping, making it the best primary choice. Enable Banking remains available as a PSD2-compliant fallback for users who prefer official open banking access or who do not have a Powens subscription. The `@Primary` + `@ConditionalOnExpression` pattern provides zero-config switching: set the Powens env vars and it takes over; unset them and Enable Banking is used.

## Trade-offs accepted

- Two adapters to maintain (double the API changes to track)
- Powens is a paid service (cost factor for self-hosters)
- Screen scraping can break when banks update their websites (provider risk)
- Both providers are optional -- the app starts without either, but bank sync is unavailable

## Consequences

- `BankConnectorPort` has two implementations; Spring injects the primary one
- Users can switch providers by changing environment variables (no code change)
- `SyncService.detectType()` handles both providers' metadata formats (product name, cash account type)
- `PowensBankConnector.mapProduct()` pre-processes Powens account types to feed `detectType()` with recognizable keywords
