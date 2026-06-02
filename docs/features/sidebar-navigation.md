# Feature: Navigation (Sidebar + Mobile Bottom Nav)

> Last updated: 2026-04-13

## Context

Navigation adapts to screen size: a vertical sidebar on desktop (>=768px) and a horizontal bottom navbar on mobile (<768px). Both share the same 4 nav items and active state logic.

## How it works

### Desktop: `AppSidebar` (hidden on mobile via `hidden md:flex`)

The sidebar lives in `AppSidebar.tsx` with two visual sections:

1. **Nav items** — rendered by the `NavItem` internal component, one per route. Uses `react-router-dom`'s `NavLink` with `useLocation` for active detection.
2. **User account dropdown** — a `DropdownMenu` at the bottom with language toggle and logout actions.

### Mobile: `MobileBottomNav` (hidden on desktop via `md:hidden`)

A fixed bottom bar with the Picsou logo centered and 2 nav items on each side:

```
[Dashboard] [Accounts] [LOGO] [Goals] [Settings]
```

- Same icon styling as the sidebar: `size-10 rounded-lg bg-muted text-muted-foreground`
- Active state: `ring-1 ring-border` + filled icon (same visual language)
- Safe area padding for iOS notch: `env(safe-area-inset-bottom)`
- `AppLayout` adds `pb-20 md:pb-0` on main content to avoid overlap

### Active state (shared pattern)

Active nav items use `fill="currentColor"` on the Lucide icon (filled variant). The icon box stays `bg-muted text-muted-foreground` in both states — only the icon fill changes. The item gets `ring-1 ring-border` when active.

### Key files

- `frontend/src/components/layout/AppSidebar.tsx` — desktop sidebar with `NavItem` and user dropdown
- `frontend/src/components/layout/MobileBottomNav.tsx` — mobile bottom navbar
- `frontend/src/components/layout/AppLayout.tsx` — renders sidebar (desktop) + bottom nav (mobile)
- `frontend/src/components/ui/item.tsx` — `Item` / `ItemMedia` / `ItemContent` primitives (do not edit)
- `frontend/src/i18n/locales/{fr,en}.json` — `nav.*` keys for labels
- `frontend/src/assets/picsou_logo_white.svg` — icon-only logo used in mobile nav
- `frontend/src/assets/horizontal-white-picsou.svg` — horizontal text logo used in sidebar

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `fill="currentColor"` for active state | Subtle differentiation without color change | Primary color highlight (`bg-primary/10 text-primary`) — too visually heavy |
| Standalone `Avatar` instead of `ItemMedia` wrapper | Avoids nested box-in-box (avatar inside icon container) | Wrapping avatar in `ItemMedia` adds redundant sizing layer |
| `fill={isActive ? 'currentColor' : 'none'}` | Babel parser fails on spread syntax `{...(condition && props)}` in JSX | `{...(isActive && { fill: 'currentColor' })}` — causes Vite 500 error |
| Separate `MobileBottomNav` component | Keeps mobile/desktop nav logic isolated; each can evolve independently | One component with responsive internals — harder to read and maintain |
| 768px breakpoint (`md:`) | Consistent with existing `useIsMobile` hook and shadcn UI sidebar pattern | Custom breakpoint — would diverge from the rest of the codebase |

## Gotchas / Pitfalls

- **Babel spread in JSX props**: `{...(condition && { prop: value })}` causes a Vite/Babel parse error (500) in this project's config. Use a ternary `prop={condition ? value : fallback}` instead.
- **File encoding**: Editing `AppSidebar.tsx` with the Edit tool introduced invisible characters that broke Babel parsing. If the file breaks after edits, rewrite it entirely with the Write tool.
- **`ItemMedia` import**: The component is imported even though the user dropdown no longer uses it — it's still used by `NavItem`. Don't remove the import.
- **Mobile bottom nav padding**: `AppLayout` adds `pb-20` on mobile to prevent content from being hidden behind the fixed bottom nav. If the bottom nav height changes, update this value.
- **User dropdown not available on mobile**: The mobile bottom nav has no user dropdown (logout, language toggle). These are accessible via the Settings page instead.
- **`hidden md:flex` on sidebar**: The sidebar nav element uses `hidden md:flex` — not `md:block` — because it needs flexbox for its internal layout. Changing to `md:block` will break the sidebar layout.

## Tests

No dedicated tests. Sidebar is covered by E2E via Playwright (`bun run test:e2e`).

## Links

- Related: `docs/features/demo-mode.md` — the dropdown shows "Demo" in demo mode
