# `examples/_shared/` — shared examples design system

Shared stylesheet, favicon, and Open Graph imagery consumed by every
example `index.html` across all three substrates (Reagent / UIx / Helix).
The orchestrator
(`examples/scripts/serve-and-run-examples-tests.cjs`) stages this whole
tree into each example's `out/examples/<name>/_shared/` directory next to
`main.js`, so every page references assets at the same relative path
(`_shared/css/style.css`).

## Visual identity (rf2-v4fpe Option 2)

Mike's decision (2026-05-20):

> "I honestly don't think each adaptor needs its own identity."

One typography stack, one palette, one stylesheet linked by every
`index.html`. Substrate variety is communicated via the substrate selector
+ the substrate-specific examples themselves, NOT via visual identity.

| Mood              | Editorial Warm — established, refined, magazine-leaning  |
| ----------------- | -------------------------------------------------------- |
| Typography pair   | Inter (UI + body) + JetBrains Mono (code, mono labels)   |
| Palette           | warm paper bg #F7F3EC / deep ink #1A1814 / amber #C8741A |
| Atmosphere        | paper-grain radial gradients fixed to the viewport       |

The pairing nods to the rest of the project (Causa uses Inter + JBM,
Story uses IBM Plex on a similarly light surface) so examples, dev
tools, and docs all sit naturally next to one another.

## Layout

- `css/style.css` — the shared design system (rf2-v4fpe). Linked by
  every `index.html`. Imports `structure.css`.
- `css/structure.css` — substrate-agnostic structural baseline (form
  geometry, grid layout, max-widths) extracted from the per-example
  inline styles by rf2-jzqs9.
- `img/favicon.svg` — shared favicon (warm-slate + amber accent).
- `img/og.svg` — shared Open Graph preview card.

## Legacy token aliases

The three design-led examples (`reagent/notebook`,
`uix/dashboard_uix`, `helix/process_monitor_helix`) were originally
authored against per-substrate token prefixes (`--rg-*`, `--ux-*`,
`--hx-*`) during the rf2-nfg15 cluster. `style.css` aliases all three
legacy prefixes onto the canonical `--ex-*` tokens, so those inline
styles continue to work without rewrites — and now render identically
across all three pages.

For new code, use the canonical `--ex-*` tokens directly.

## Adding a new example

1. Reference `_shared/css/style.css`, `_shared/img/favicon.svg`, and
   `_shared/img/og.svg` from the new example's `index.html` `<head>`.
2. For per-example layout-only inline CSS, use the `--ex-*` tokens.
3. No need to add new shared assets — substrate variety is no longer
   communicated via the design system.
