# ADR: Admin page reuses `SetupService` writers behind a role-gated controller

> Date: 2026-04-25
> Status: ✅ Active

## Context

After the [first-launch Setup Wizard](../features/setup-wizard.md) completes, several
values it wrote into `app_setting` (`cors.allowed-origins`, `app.secure-cookies`, the
three `enablebanking.*` keys, the per-integration enabled flags) become impossible
for the admin to change without editing rows in the database by hand. Self-host
operators kept asking how to rotate Enable Banking credentials, add a new LAN origin
when their phone got a new IP, or temporarily disable an integration without losing
its stored secrets.

We needed a post-setup re-configuration surface. The architectural question was:
*does this surface get its own service layer, or does it reuse the wizard's writers?*

## Decision

**Reuse the wizard's `SetupService` writers from a thin `AdminController`, gated by
URL-level `hasRole("ADMIN")` in `SecurityConfig`.**

- `AdminController` (`/api/admin/**`) only marshals DTOs and delegates to existing
  `setupService.writeSecurity(...)`, `setupService.writeEnableBankingConfig(...)`,
  and `integrationsService.enable / disable(...)`.
- `GET /api/admin/settings` aggregates everything the page needs into one
  `AdminSettingsResponse`.
- The role gate is a single `requestMatchers("/api/admin/**").hasRole("ADMIN")` line
  rather than per-method `@PreAuthorize`.
- Frontend mirrors the gate with a `RequireAdmin` guard that redirects non-admins
  to `/error/403`; this is purely UX and has no security weight.

## Alternatives considered

### Alternative A: dedicated `AdminService` with its own writers

- **Pros**: Stronger boundary between "first-time setup" and "ongoing
  reconfiguration"; admin can later diverge (e.g. richer validation, change-audit
  events) without touching wizard code.
- **Cons**: Duplicates the upsert + CSV-formatting logic for `cors.allowed-origins`,
  the join-on-comma + trim semantics, and the EB key naming. Two writers for the
  same `app_setting` keys is a drift bomb — the moment one of them gains a `.trim()`
  or a normalisation step the other forgets, the wizard and the admin page produce
  rows that read back differently.

### Alternative B: PUT a single full-settings document

- **Pros**: One endpoint, one mutation; no per-section state to keep in sync.
- **Cons**: Concurrent admin sessions or stale UI state would clobber each other —
  toggling integration X off in tab A and saving CORS origins in tab B would also
  re-enable X. Also forces the page to re-validate every section to save any one of
  them, which kills inline UX.

### Alternative C: per-method `@PreAuthorize("hasRole('ADMIN')")` instead of a URL matcher

- **Pros**: Co-locates the rule with the endpoint; harder to overlook when reading a
  single method.
- **Cons**: Easy to forget on a *new* endpoint added under `/api/admin`. Spring
  silently treats a missing annotation as "permitAll" for an authenticated user,
  which means a new admin endpoint would be exposed to every logged-in user by
  default. The URL matcher is fail-closed at the path level.

### Alternative D: write a real RBAC layer (roles → permissions → resources)

- **Pros**: Future-proof for "viewer", "editor", "billing-admin", etc.
- **Cons**: Picsou is single-user-with-an-optional-family; the only role distinction
  today is `ADMIN` vs `USER`. Building RBAC on speculation is exactly the kind of
  premature abstraction CLAUDE.md warns against.

## Reasoning

The wizard's writers already encode every invariant we care about (CSV format,
boolean stringification, key naming, the contract that `app.secure-cookies` is
read on every response). Re-using them keeps `app_setting` semantics in one place
and makes the admin page's behavioural contract identical to the wizard's by
construction.

The URL-level matcher won on a fail-closed argument: it is structurally impossible
to add a new admin endpoint that bypasses authorization without explicitly editing
`SecurityConfig`, whereas a forgotten `@PreAuthorize` annotation would be invisible
in code review.

## Trade-offs accepted

- **`SetupService` is now a dependency of two unrelated controllers.** That's
  acceptable because the writers are already public API of the service and the
  semantics are intentionally identical. If the wizard's writers ever need to
  diverge from the admin page's (e.g. wizard-only audit events) we'll factor a
  shared `SettingsWriter` rather than fork them.
- **No `AdminService` to test in isolation.** Behaviour is exercised at the
  controller boundary (`AdminControllerTest`) plus the existing
  `SetupServiceTest` coverage of the writers. We accept the loss of a "pure admin
  business logic" test surface because there isn't any pure admin business logic.
- **Coupling between `JwtAuthenticationFilter` and `hasRole("ADMIN")`.** The
  matcher relies on the filter prefixing role names with `"ROLE_"`. A refactor to
  store bare names would silently turn every admin endpoint into a 403. This is
  documented as a Gotcha in `docs/features/admin-page.md` and would be caught by a
  future MockMvc slice test (currently a TODO).

## Consequences

- New endpoints under `/api/admin/**` are role-gated by default; nothing else
  needs to be done at the auth layer when adding one.
- Changes to `cors.allowed-origins` and `app.secure-cookies` from the admin page
  take effect without restart, because `DynamicCorsConfigurationSource` and the
  cookie helper read from `app_setting` per request — same path the wizard relies
  on. If anyone reverts to static `@Value` config, the admin page will silently
  no-op until the container restarts.
- Toggling an integration off via `PATCH
  /api/admin/settings/integrations/{key}?enabled=false` deliberately does not
  delete its stored credentials (TR session, Bourso session, EB requisitions,
  encrypted exchange API keys). Re-enabling restores prior connectivity. This is
  documented and the only way to nuke credentials is per-integration delete
  endpoints.
- `PATCH` must remain in `SecurityConfig.corsConfigurationSource`'s allowed
  methods, because the integrations toggle uses it.

## Supersedes

None. This is the first ADR on post-setup reconfiguration; the wizard ADR
[`2026-04-23-first-launch-wizard.md`](./2026-04-23-first-launch-wizard.md)
explicitly scoped itself to first-launch, leaving ongoing config to a later
decision — this one.
