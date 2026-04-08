# Feature: Goal Calendar — Grid View (donuts)

> Last updated: 2026-04-08

## Context

The "Grid" view of `GoalCalendarPage` displays past months as interactive donut cards. Each donut shows the effective amount and the percentage of the monthly savings objective achieved. The original 52px SVG rings (6-column grid, percentage only) were replaced with 80px cards showing amount + percentage, overflow support (>100%), and compact formatting for large amounts.

## How it works

### Key files

- `frontend/src/pages/goals/GoalCalendarPage.tsx` — entire component: `ProgressRing`, `formatCompact`, `getProgressColor`, `YearGridView`, skeleton, `MonthDetailPanel`

### Components

**`ProgressRing`** — 80px SVG, stroke 9px, rotated -90° so the arc starts at the top:
- Background track: `var(--muted)`, stroke 9px
- Base arc: `min(pct, 1) × circumference`, `stroke-linecap="butt"` on overflow, `"round"` otherwise
- Bonus arc (overflow > 100%): color `#818cf8` (light indigo), stroke 12px, `min(pct−1, 1) × circumference`, `stroke-linecap="round"` except when full circle → `"butt"` (avoids bump at junction)

**`getProgressColor`** — returns `color` (arc), `textColor` (inner text), `pct` (raw ratio):

| Condition | Arc color |
|-----------|-----------|
| No data | `--muted` |
| ratio < 60% | `oklch(from var(--destructive) calc(l + 0.15) c h)` |
| 60% ≤ ratio < 100% | `#f59e0b` (amber) |
| ratio ≥ 100% | `oklch(from var(--primary) calc(l + 0.2) c h)` |

**`formatCompact`** — formats amounts to fit inside the ring (max ~6 chars):

| Value | Output |
|-------|--------|
| < 1 000 € | `475 €` |
| ≥ 1 000 € | `1 k€`, `1,5 k€` |
| ≥ 10 000 € | `10 k€`, `200 k€` |
| ≥ 1 000 000 € | `1,5 M€` |

**`YearGridView`** — one `<Card>` per year, `overflow-x-auto` + `flex gap-3 min-w-max` so all 12 months stay on one line (horizontal scroll on narrow viewports). Each month is a `<button>` `min-w-[90px]` with:
1. Month label (10px uppercase)
2. `ProgressRing` + absolute text overlay (11px/9px)
3. `obj. XXX` (10px muted)

### Inner text — cases

| Situation | Line 1 | Line 2 |
|-----------|--------|--------|
| No data | `–` (13px muted, centered) | — |
| 0–100% | `formatCompact(effective)` | `XX%` |
| > 100% | `formatCompact(effective)` | `+XX%` |

### Indicator dots

- Violet dot (`bg-violet-600`) at `top-[3px] right-[3px]`: monthly objective manually overridden (`entry.override != null`)
- Blue dot (`bg-blue-500`): manually declared contribution (`entry.manualActual != null`). Takes priority over violet when both are set.

### Selection state

- Selected: `border-primary bg-accent`
- Unselected: `border-border hover:bg-accent/50`

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Custom SVG (two `<circle>`) | Full control over the bonus arc, no dependency | `recharts` (no access to `stroke-dasharray` for overflow) |
| `oklch(from var(--primary) calc(l + 0.2) c h)` | `var(--primary)` in dark mode is too dark (lightness 0.42); CSS relative color syntax adds 0.2 lightness while staying theme-consistent | Hardcoded `#6366f1` (doesn't follow the theme) |
| `overflow-x-auto` + `flex min-w-max` | 12 cards × ~96px ≈ 1200px min; horizontal scroll preserves 80px proportions | `grid-cols-12` with smaller cards (too small to read inner text) |
| `formatCompact` local (not in `lib/utils.ts`) | Very specific to this component (6-char constraint to fit in 80px ring) | Global utility (too specialized to reuse) |
| Bonus arc capped at 100% bonus | Beyond 200% total, the visual doesn't change; the real % stays readable in the text | Multi-turn arc (complex and unreadable) |

## Gotchas / Pitfalls

- **Tailwind v4 + CSS vars**: `hsl(var(--primary))` is invalid in Tailwind v4 (vars are in `oklch`). Use `var(--primary)` directly in SVG `stroke` attributes or inline styles. `color-mix(in oklch, ...)` works; wrapping oklch vars in `hsl()` renders as transparent.
- **`stroke-linecap="butt"` at 100% bonus**: if the bonus arc reaches exactly the circumference with `"round"`, the rounded endpoints overlap and create a visible bump at the top. Detect `bonusFilled >= circ - 0.1` to switch to `"butt"`.
- **`var(--border)` in dark mode** = `oklch(1 0 0 / 10%)` (white at 10% opacity) — nearly invisible as a background track. Use `var(--muted)` instead.
- **`textColor` === `color`**: inner text color is the same as the arc color. Colors brightened via CSS relative color syntax read well on dark backgrounds.
- **Do not touch** `TimelineView`, `CalendarGridView`, `MonthDetailPanel` — these views are independent and do not share `formatCompact` / `ProgressRing`.

## Tests

No dedicated frontend unit tests for this component. `getProgressColor` and `formatCompact` are pure functions and straightforward to unit-test if needed.

## Links

- Feature backend: [goals.md](./goals.md)
- ADR: [2026-04-08-css-relative-color-syntax.md](../decisions/2026-04-08-css-relative-color-syntax.md)
