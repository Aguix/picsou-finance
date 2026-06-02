# Feature: CORS & Cookie Security

> Last updated: 2026-04-22 (SECURE_COOKIES env var)

## Context

Picsou uses HttpOnly JWT cookies for authentication. The CORS configuration controls which browser origins can make credentialed requests to the API. Getting this wrong either blocks legitimate clients (local network devices, iPhone, MacBook) or produces confusing silent failures.

## How it works

### CORS

`SecurityConfig.corsConfigurationSource()` builds the allowed-origins list from the `ALLOWED_ORIGINS` env var (default: `*`).

Key points:
- Uses `setAllowedOriginPatterns()` (not `setAllowedOrigins()`) — required to support wildcards (`*`, `192.168.1.*`) while keeping `allowCredentials: true`. `setAllowedOrigins("*")` with credentials is illegal and throws at runtime.
- Entries are split by `,`, trimmed, and empty strings filtered — avoids silent mismatches from trailing spaces or empty entries.
- A `CorsFilter` bean is declared explicitly (not left to Spring Security's DSL) so a `LoggingCorsProcessor` can be attached.

`LoggingCorsProcessor` extends `DefaultCorsProcessor` and logs the allowed patterns on every rejection:
```
WARN LoggingCorsProcessor : CORS rejected — origin: 'http://192.168.1.42:5173' | allowed patterns: [*]
```

In dev, `application-dev.yml` adds `TRACE` logging for `org.springframework.web.cors` and `org.springframework.web.filter.CorsFilter`.

### Cookies

Auth tokens are set as HttpOnly cookies via `AuthController.addCookie()`:

```
access_token=...; Max-Age=900; Path=/; HttpOnly; SameSite=Lax[; Secure]
```

- `SameSite=Lax` — allows cookies on normal navigations, blocks cross-site POST. `Strict` caused failures on Safari iOS (cookies dropped on certain navigation patterns).
- `Secure` flag is controlled by the `SECURE_COOKIES` env var (default `true`). Set `SECURE_COOKIES=false` when serving over plain HTTP (no TLS — e.g. local NAS on `http://`). Without this, browsers silently drop the cookies and the app loops between dashboard and `/login`.
- Tokens are re-issued on username change (`PATCH /api/auth/username`) — without this the next request fails because the JWT still carries the old username.

### Key files

| File | Role |
|------|------|
| `config/SecurityConfig.java` | CORS config: allowed origins, methods, headers, credentials |
| `config/LoggingCorsProcessor.java` | Logs allowed patterns on CORS rejection |
| `controller/AuthController.java` | Cookie construction (`addCookie`), token rotation |
| `resources/application.yml` | `app.cors.allowed-origins`, `app.secure-cookies: ${SECURE_COOKIES:true}` |
| `resources/application-dev.yml` | `app.secure-cookies: false`, CORS TRACE logging |
| `docker/.env.example` | Documents `SECURE_COOKIES=true/false` |
| `docker-compose.override.yml` | Dev-only env overrides — **must not hardcode `ALLOWED_ORIGINS`** here |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `setAllowedOriginPatterns` | Supports `*` wildcards with `allowCredentials: true` | `setAllowedOrigins` — wildcards incompatible with credentials |
| Explicit `CorsFilter` bean | Required to attach `LoggingCorsProcessor` | Spring Security DSL only — no way to swap the processor |
| `SameSite=Lax` | Safari iOS compatibility | `SameSite=Strict` — dropped cookies on Safari on certain navigations |
| `ALLOWED_ORIGINS=*` for local use | Simplest config for private home server; no real cross-site threat on LAN | Per-IP patterns — fragile, breaks when device IP changes |

## Gotchas / Pitfalls

- **`docker-compose.override.yml` overrides env_file**: Any `ALLOWED_ORIGINS` in the `environment:` block of the override silently wins over `.env`. If CORS is misbehaving in dev, check the override file first.
- **`setAllowedOrigins("*")` + credentials = runtime error**: Spring rejects this combination. Always use `setAllowedOriginPatterns` when `allowCredentials: true`.
- **Origin header has no path**: Browsers send `http://host:port` with no trailing slash. Patterns like `http://192.168.1.*:*/` (trailing slash) will never match. Use `http://192.168.1.*:*` or just `*`.
- **JWT re-issue on username change is mandatory**: The access and refresh tokens contain the username as JWT subject. Updating the DB row without rotating the cookies causes an immediate 401 on the next request (filter can't find user by old username).
- **`PATCH` must be in allowed methods**: Spring CORS allowed methods don't include PATCH by default. Any `PATCH` endpoint added to the API requires it to be listed explicitly in `SecurityConfig`.
- **`Secure` flag on HTTP = infinite redirect loop**: If `SECURE_COOKIES=true` on a plain HTTP host, the browser silently drops both cookies on `Set-Cookie`. `sessionStorage` is still written (JS-side), so `isAuthenticated` is true, the dashboard loads and fires API calls, gets 401 (no cookie sent), refresh also fails (no cookie), and the 401 handler redirects to `/login?redirect=/`. The login guard sees `sessionStorage` still set, redirects back to `/`. Loop. Fix: `SECURE_COOKIES=false` in `.env`.

## Tests

No dedicated tests for CORS/cookie configuration. Verified manually by checking CORS rejection logs and browser cookie inspection.

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md`
