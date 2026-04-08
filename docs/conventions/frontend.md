# Convention: Frontend

## Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 19 | UI framework |
| TypeScript | 5.9 (strict) | Type system |
| Vite | 7 | Build tool |
| TanStack Query | v5 | Server state (fetching, caching, syncing) |
| Zustand | v5 | Client state (auth, app settings) |
| shadcn/ui | latest | Component library (Radix primitives) |
| Tailwind CSS | v4 | Styling |
| React Router | v7 | Routing with lazy code splitting |
| Axios | latest | HTTP client with interceptors |
| react-i18next | latest | Internationalization (FR/EN) |
| Recharts | v3 | Charts |
| react-hook-form + Zod | latest | Form handling + validation |
| Sonner | latest | Toast notifications |
| next-themes | latest | Dark/light mode |

## Directory structure

```
src/
  app/              Entry: providers.tsx, routes.tsx
  pages/            Route pages (one file per route, lazy-loaded)
  components/
    layout/         AppSidebar, AppLayout (persistent shell)
    ui/             shadcn/ui generated — DO NOT EDIT
    shared/         App-specific reusable components
  features/         Feature slices: api.ts + hooks.ts per domain
  stores/           Zustand stores (auth-store.ts, app-store.ts)
  lib/              api-client.ts, utils.ts, constants.ts, query-client.ts
  types/            api.ts (mirrors backend DTOs), app.ts (frontend-only types)
  demo/             Demo mode interceptor + mock data
  i18n/             i18next initialization
  main.tsx          Bootstrap + demo mode setup
```

## State management

### Server state — TanStack Query

All remote data lives in TanStack Query. Feature hooks in `features/*/hooks.ts` own query keys and fetch functions.

```typescript
// features/goals/hooks.ts
export function useGoals() {
  return useQuery({ queryKey: ['goals'], queryFn: () => api.get('/goals') })
}
```

- Stale times configured in `lib/constants.ts`.
- No Redux, no Context for server data.

### Client state — Zustand

Only for auth and app-wide UI state (e.g., demo mode toggle).

```typescript
// stores/auth-store.ts
export const useAuthStore = create<AuthState>((set) => ({
  username: sessionStorage.getItem('picsou_user'),
  isAuthenticated: !!sessionStorage.getItem('picsou_user'),
  login: (username) => { sessionStorage.setItem('picsou_user', username); set(...) },
  logout: () => { sessionStorage.removeItem('picsou_user'); set(...) },
}))
```

Auth cookies are HttpOnly — the Zustand store is the JS-readable signal, persisted in `sessionStorage`.

## API client

Single Axios instance in `lib/api-client.ts`:

```typescript
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})
```

### 401 auto-refresh interceptor

On 401, the interceptor calls `POST /api/auth/refresh` and retries the failed request. Concurrent 401s are queued and replayed after a single refresh.

```typescript
api.interceptors.response.use(
  res => res,
  async error => {
    if (error.response?.status === 401 && !originalRequest._retry) {
      await api.post('/auth/refresh')
      return api(originalRequest)
    }
    // redirect to /login on refresh failure
  }
)
```

### Demo mode interceptor

When `VITE_DEMO_MODE=true` (or runtime toggle via `app-store.ts`), a request interceptor short-circuits to mock handlers with simulated 200-600ms delay. Mock data lives in `demo/data/`.

## Routing and code splitting

React Router v7 with `lazy()` per page:

```typescript
const DashboardPage = lazy(() =>
  import('@/pages/dashboard/DashboardPage').then(m => ({ default: m.DashboardPage }))
)
```

- Auth-protected routes wrapped in `<RequireAuth>` guard.
- Public-only routes (login) wrapped in `<PublicOnly>`.
- `SuspensePage` wrapper with `<LoadingSkeleton />` fallback.
- Vite path aliases: `@/` maps to `src/`.

## Styling

### Tailwind CSS v4

- Imported via `@import "tailwindcss"` in `index.css`.
- oklch color tokens for both light and dark themes (defined in `:root` and `.dark`).
- Font: **Geist Variable** (`@fontsource-variable/geist`).
- Radius scale from `--radius` base.

### shadcn/ui

Components in `components/ui/` are **generated** — never edit them directly. Customize via the shadcn CLI or Tailwind theme tokens.

#### Color tokens — always semantic, never raw palette

`shadcn/tailwind.css` remaps Tailwind's `gray` palette to a blue-tinted scale aligned with the primary hue. **Never use raw palette classes** (`text-gray-*`, `bg-gray-*`, etc.) — they will appear blue, not neutral.

| Intent | Use |
|--------|-----|
| Primary text | `text-foreground` |
| Muted / secondary text | `text-muted-foreground` |
| Subtle background | `bg-muted` |
| Card background | `bg-card` |
| Primary action color | `text-primary` / `bg-primary` |
| Destructive / error | `text-destructive` |

**Exception:** intentional status colors (`text-green-*`, `text-red-*`, `text-amber-*`, etc.) are fine when used as semantic UI signals (sync status badges, financial gain/loss indicators). Always include dark-mode variants or use the `dark:` prefix for those.

### Icons

Use icons from `lucide-react` as direct JSX components (e.g., `<Pencil className="size-4" />`). No other icon libraries.

## Internationalization

- react-i18next with FR/EN languages.
- Translation files: `public/locales/{fr,en}/translation.json`.
- Flat keys with feature-based grouping.
- All user-visible text must use `useTranslation()` — no hardcoded English strings.
- Currency formatting via `Intl.NumberFormat`.

## Types

`types/api.ts` mirrors backend DTO records exactly (e.g., `AccountResponse`, `GoalProgressResponse`). When a backend DTO changes, update this file to match.

## Charts

Recharts v3 for all data visualizations. Chart color tokens (`--chart-1` through `--chart-5`) are defined in the Tailwind theme.

## Scripts

```bash
bun run dev          # Dev server on :5173, proxies /api/* to localhost:8080
bun run build        # tsc + vite build (fails on type errors)
bun run typecheck    # TypeScript checking only
bun run lint         # ESLint
bun run format       # Prettier
bun run test:e2e     # Playwright E2E tests
npx vitest run       # Vitest unit tests
```

## Don'ts

- **Never use raw palette classes** (`text-gray-*`, `bg-gray-*`) — always semantic tokens (`text-foreground`, `bg-muted`). The gray palette is remapped to blue.
- **Never create API functions in components** — all API calls go in `features/*/api.ts`.
- **Never create hooks outside `features/`** — domain hooks live in `features/*/hooks.ts`. Only generic UI hooks (like `use-mobile`) go in `hooks/`.
- **Never use Redux, Context, or global state for server data** — TanStack Query only.
- **Never edit files in `components/ui/`** — these are shadcn/ui generated. Customize via theme tokens or the shadcn CLI.
- **Never use icon libraries other than `lucide-react`.**
- **Never hardcode user-visible strings** — always use `useTranslation()`.
- **Never use CSS modules, styled-components, or inline style objects** — Tailwind only.
