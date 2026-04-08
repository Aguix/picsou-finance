# ADR: CSS relative color syntax for theme-adaptive brightness adjustments

> Date: 2026-04-08
> Status: ✅ Active

## Context

Tailwind v4 defines CSS custom properties in `oklch()` color space (e.g. `--primary: oklch(0.424 0.199 265.638)` in dark mode). Some UI elements — particularly SVG arc strokes — require colors with sufficient lightness to be visible against a dark background. The raw `--primary` value (lightness 0.424) is too dark to read as a thin arc on a near-black background. Hardcoding colors breaks theme consistency (light/dark mode, future theme changes).

## Decision

Use CSS relative color syntax to derive adjusted colors from existing CSS custom properties:

```css
oklch(from var(--primary) calc(l + 0.2) c h)
oklch(from var(--destructive) calc(l + 0.15) c h)
```

This adds a fixed lightness delta to any CSS variable while preserving its chroma and hue, keeping the result visually coherent with the active theme.

## Alternatives considered

### Hardcoded hex values

- **Pros**: Simple, predictable, no browser compatibility concern
- **Cons**: Breaks when the theme changes; duplicates design tokens already defined in the theme; requires manual updates if the theme palette is adjusted

### `color-mix(in oklch, var(--primary) X%, white)`

- **Pros**: Good browser support, intuitive
- **Cons**: Mixing with white desaturates the color noticeably; result looks washed out rather than just brighter

### Separate CSS variables for "bright" variants (e.g. `--primary-bright`)

- **Pros**: Explicit, easy to read at use site
- **Cons**: Adds maintenance burden to the theme definition; overkill for a single use case

### `color-mix(in oklch, var(--primary) X%, transparent)`

- **Cons**: Reduces opacity, not lightness — makes the color fade out rather than brighten. Wrong effect entirely.

## Reasoning

CSS relative color syntax is the only approach that adjusts **lightness only** while keeping chroma and hue identical to the source token. The result is a brighter variant that is visually the same color, just more legible. It is supported in all modern browsers (Chrome 119+, Firefox 128+, Safari 16.4+) which matches the project's target audience (self-hosted personal use).

## Trade-offs accepted

- Requires modern browser support — no IE11 or old mobile browsers.
- The `calc(l + delta)` can theoretically exceed `[0, 1]`; browsers clamp automatically, so no guard is needed in practice.
- Less obvious to read than a hex color for developers unfamiliar with the syntax.

## Consequences

- **Pattern to follow**: whenever a CSS custom property needs a brightness-adjusted variant in SVG strokes or inline styles, prefer `oklch(from var(--token) calc(l ± delta) c h)` over hardcoded colors.
- **Do not use** `hsl(var(--token))` — invalid in Tailwind v4 (vars are oklch, not hsl channels).
- **Do not use** `color-mix(..., transparent)` to brighten — it reduces opacity, not lightness.
- Current uses: `GoalCalendarPage.tsx` — `COLORS.success`, `COLORS.destructive`.
