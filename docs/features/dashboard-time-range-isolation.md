# Feature: Dashboard Time Range Isolation

> Last updated: 2026-04-05

## Context

The Dashboard displays net worth history, account distribution, and goals. When a user clicks time range buttons (1D, 7D, 1M, etc.), only the net worth chart should update with the new date range. The distribution pie and goals sections are static data that should never re-render when the user changes the time range. This requires isolating the time range state to prevent unnecessary re-fetches and re-renders across the entire page.

## How it works

The Dashboard page is split into two data fetch scenarios:

1. **Static data** (distribution + goals): Fetched once via `useDashboard()` without a range parameter. Never re-fetches.
2. **Time-range-scoped data** (net worth history): Managed by the `NetWorthCard` component with local `range` state. Re-fetches only when the user clicks a time range button.

### Key files

- `frontend/src/pages/dashboard/DashboardPage.tsx` — Page layout, renders distribution pie, goals, and NetWorthCard
- `frontend/src/features/dashboard/NetWorthCard.tsx` — Isolated component managing net worth chart with local range state
- `frontend/src/features/dashboard/hooks.ts` — `useDashboard(range?)` hook (supports optional range parameter)
- `frontend/src/components/shared/TimeRangeSelector.tsx` — Time range button controls (1D, 7D, 1M, 3M, YTD, 1Y, ALL)
- `frontend/src/components/shared/NetWorthChart.tsx` — Displays net worth vs. invested line chart using recharts

### Flow

```
User clicks "7D" button
  ↓
TimeRangeSelector.onChange fires
  ↓
NetWorthCard.setRange('7D') updates local state
  ↓
useDashboard('7D') called with new range
  ↓
React Query fetches /dashboard?range=7D
  ↓
NetWorthChart re-renders with new data
  ↓
DashboardPage, distribution pie, goals: unaffected (never re-render)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Move time range state to **NetWorthCard component** (local `useState`) | Scope isolation — only the net worth section re-fetches when range changes. Distribution and goals remain static. | Global state (Zustand/Redux) — would cause the entire dashboard to re-render unnecessarily. |
| Use **separate hook calls** in DashboardPage and NetWorthCard | DashboardPage calls `useDashboard()` (no range) for static data. NetWorthCard calls `useDashboard(range)` for scoped data. | Single monolithic query — would require the entire dashboard to re-fetch whenever range changed. |
| Default range to **'1Y'** in NetWorthCard | Matches user expectation ("sur 12 mois" trend label). Consistent with historical behavior. | No default / 'ALL' — would be confusing. |

## Gotchas / Pitfalls

- **Query key isolation**: The `useDashboard()` hook uses `['dashboard', range]` as the React Query key. When DashboardPage calls without range (undefined), it caches separately from NetWorthCard's range-scoped calls. Do not remove the range from the query key.

- **Data shape difference**: DashboardPage expects `distribution` and `goalSummaries` fields. NetWorthCard expects `totalNetWorth`, `previousTotal`, and `netWorthHistory` with `invested` field. The backend must return all these fields in `/dashboard?range=X` responses.

- **NetWorthChart requires `invested` field**: Each entry in `netWorthHistory` must have both `total` and `invested` properties. If the range-scoped API response omits `invested`, the chart will fail to render. Check backend implementation in `DashboardController.java`.

- **No loading state sync**: DashboardPage may finish loading before NetWorthCard (or vice versa). They have separate loading states. The overall page is not "fully loaded" until both queries finish. This is intentional but may look janky on slow connections.

## Tests

No dedicated test files exist yet. Manual verification:

1. Open Dashboard
2. Click "1D" button → net worth chart updates, distribution/goals stay static
3. Click "1Y" button → chart updates again, rest of page unchanged
4. Refresh page → chart should default to 1Y range
5. Verify no console errors about missing `invested` field in chart data

## Links

- **Backend**: `backend/src/main/java/com/picsou/controller/DashboardController.java` — `/dashboard` endpoint (must support `?range=` parameter)
- **Related decision**: [TODO] Consider creating ADR if more complex time range logic is added later (e.g., comparing two ranges, exporting range-scoped data)
