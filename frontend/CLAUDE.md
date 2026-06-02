# Frontend — CLAUDE.md

## Commands

```bash
bun run dev          # Dev server :5173, proxies /api/* → localhost:8080
bun run build        # tsc + vite build (fails on type errors)
bun run typecheck    # TypeScript only
bun run lint         # ESLint (zero-warnings)
bunx vitest run      # Unit tests
bun run test:e2e     # Playwright E2E tests
```

## Architecture (quick ref)

```
src/
  app/              providers.tsx, routes.tsx (lazy routing + guards)
  pages/            One dir per route (lazy-loaded)
  features/         Domain slices: api.ts + hooks.ts per feature
  components/
    layout/         AppSidebar, AppLayout
    ui/             shadcn/ui — DO NOT EDIT
    shared/         App-specific reusable (PageHeader, etc.)
  stores/           Zustand (auth-store, app-store)
  lib/              api-client.ts, utils.ts, constants.ts, query-client.ts
  types/            api.ts (mirrors backend DTOs), app.ts (frontend-only)
  demo/             Demo mode interceptor + mock data
  i18n/             react-i18next (FR/EN)
```

## Key patterns

**API layer (`lib/api-client.ts`):** Single Axios instance, `withCredentials: true`. Response interceptor silently refreshes on 401 (queues concurrent retries). Auth calls excluded to avoid loops. Feature-specific API functions live in `features/*/api.ts`.

**Server state:** TanStack Query via hooks in `features/*/hooks.ts`. No Redux, no Context for server data. Query keys co-located in hook files. Stale times in `lib/constants.ts`.

**Auth guard:** `RequireAuth` in `features/auth/guards.tsx` checks `sessionStorage.getItem('picsou_user')`. Cookie is HttpOnly (invisible to JS) — sessionStorage is the JS-readable signal.

**Routing:** React Router v7 in `app/routes.tsx`. Lazy code-splitting per page. `/sync/callback` and `/sync` share SyncPage (OAuth redirect target).

## Conventions

Full conventions (styling, icons, i18n, charts, state management details): see [`docs/conventions/frontend.md`](../docs/conventions/frontend.md)
