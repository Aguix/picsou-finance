# Feature: Demo mode

> Last updated: 2026-04-08

## Context

Allows running the frontend without a backend or authentication. Used for local UI testing or demonstrations. All API requests are intercepted and return mock data.

## How it works

### Activation

The `VITE_DEMO_MODE=true` environment variable in `frontend/.env` enables the mode. It is a build-time value read via `import.meta.env`.

### Request interception

At startup, if `VITE_DEMO_MODE === 'true'`, a custom Axios adapter replaces the real HTTP transport:

```
VITE_DEMO_MODE=true
  → api-client.ts injects createDemoAdapter() on api.defaults.adapter
  → every Axios request is intercepted, resolved with mock data after a random delay (200–600 ms)
```

### Auth guard

`RequireAuth` (in `features/auth/guards.tsx`) reads `demoMode` from the store. In demo mode, it passes through without checking `isAuthenticated`. `PublicOnly` (login) redirects to `/` if `demoMode` is active.

### State management

`useAppStore` (zustand) exposes `demoMode`. The value is initialized from the env var and **not persisted** in localStorage (via `partialize`) — ensuring the env variable always takes precedence on reload.

### Key files

- `frontend/.env` — `VITE_DEMO_MODE=true`
- `frontend/src/lib/api-client.ts` — mock adapter injection
- `frontend/src/demo/index.ts` — `createDemoAdapter()`, route handler table
- `frontend/src/demo/data/` — mock data (accounts, goals, dashboard, transactions, holdings, sync-status)
- `frontend/src/stores/app-store.ts` — `demoMode` state, not persisted
- `frontend/src/features/auth/guards.tsx` — auth bypass in demo mode

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Axios adapter (not an interceptor) | Completely replaces the transport — no network request is ever sent | Response interceptor: the request is still dispatched, risking network errors |
| `partialize` to exclude `demoMode` from persist | The env var must always win; without this, a stale localStorage value overwrites `VITE_DEMO_MODE` | Persisting `demoMode`: broke demo mode if the app had previously run with `false` |

## Gotchas / Pitfalls

- **`/login` redirects to `/` in demo mode** — `PublicOnly` redirects immediately. To see the login page, disable `VITE_DEMO_MODE`.
- **Mock handlers are keyed by exact route** — the key is `METHOD /path` with no query string or trailing slash. Any new API route needs a handler added in `demo/index.ts`, otherwise the call returns `{}` silently.
- **Artificial delay of 200–600 ms** — intentional, to simulate network latency. Do not remove it for visual testing.
- **`demoMode` is not persisted** — intentional. Do not add it to `partialize` without understanding the implications (see above).

## Tests

No dedicated unit tests. Manual verification via `bun run dev` with `VITE_DEMO_MODE=true`.
