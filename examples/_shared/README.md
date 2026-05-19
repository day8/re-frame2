# `examples/_shared/` — per-substrate visual identity assets

Shared stylesheets, favicon, and Open Graph imagery consumed by every
example `index.html`. The orchestrator
(`examples/scripts/serve-and-run-examples-tests.cjs`) stages this whole
tree into each example's `out/examples/<name>/_shared/` directory next to
`main.js`, so every page references assets at the same relative path
(`_shared/css/<substrate>.css`).

## Per-substrate identity (rf2-nfg15)

re-frame2 ships three React adapters — Reagent, UIx, Helix — and each
example tree under `examples/<substrate>/` mounts an app on the
respective adapter. Each substrate carries its own distinctive look so a
visitor can identify the substrate at a glance:

| Substrate | Mood                       | Typography                          | Palette                                            | Atmosphere                                  |
| --------- | -------------------------- | ----------------------------------- | -------------------------------------------------- | ------------------------------------------- |
| Reagent   | Editorial Warm             | Newsreader display serif + Source Sans 3 | warm paper #FAF4EC / ink #1F1A14 / sienna #C4541C | paper-grain radial gradients, soft shadows  |
| UIx       | Swiss Modern               | Space Grotesk + Space Mono labels   | concrete #EFF2F5 / near-black #0A0F14 / cyan #00B4D8 | dotted grid background, hard-offset shadows |
| Helix     | Developer Industrial (dark) | JetBrains Mono UI + IBM Plex Sans body | bg #0D1117 / surface #161B22 / neon green #3FB950 | gradient mesh corners + faint scanlines     |

The three identities are deliberately distinct from the dev tools that
sit alongside the examples — Causa (Inter + JBM + violet) and Story (IBM
Plex on light + cyan-leaning palette) — and from each other on every
axis (light-warm-serif vs. light-cool-grotesque vs. dark-mono-neon).

## Layout

- `css/structure.css` — substrate-agnostic structural baseline (form
  geometry, grid layout, max-widths). `@import`ed by every substrate
  stylesheet.
- `css/reagent.css`, `css/uix.css`, `css/helix.css` — per-substrate
  identity layer. Picks up `structure.css` automatically.
- `img/favicon.svg`, `img/favicon-<substrate>.svg` — shared favicon spine
  with per-substrate accent (rf2-3zibv).
- `img/og.svg`, `img/og-<substrate>.svg` — Open Graph preview images
  (rf2-3zibv).

## Adding a new substrate

1. Drop `css/<substrate>.css` next to the existing three; `@import
   structure.css` first; pick a distinctive typography + palette +
   atmosphere triad (read `frontend-design` skill first).
2. Add `img/favicon-<substrate>.svg` + `img/og-<substrate>.svg`.
3. Link `_shared/css/<substrate>.css` from each
   `examples/<substrate>/<example>/index.html` `<head>`.
4. Update the table above.

The orchestrator picks up new files automatically — `stageShared` copies
the whole `_shared/` tree per example.
