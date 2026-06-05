# `examples/_shared/` — shared examples design system

Shared stylesheet, favicon, and Open Graph imagery consumed by every
example `index.html` across all three substrates (Reagent / UIx / Helix).
The smoke orchestrator
(`examples/scripts/serve-and-run-examples-tests.cjs`) stages this whole
tree into each staged surface's output dir — for the adapter testbeds it
drives, that is
`implementation/out/examples/adapter-testbeds/<name>/_shared/`, next to
the staged `index.html` + `main.js` — so every page references assets at
the same relative path (`_shared/css/style.css`). (The exact output dir is
declared per-entry as `outDir` in
[`examples/scripts/examples-filter.cjs`](../scripts/examples-filter.cjs);
the smoke set is the three adapter testbeds, which do not themselves link
`_shared` — see below.)

**What is actually tested.** The smoke harness stages this tree but the
three adapter testbed pages it serves link none of `_shared`. The
"every example index references the shared assets" contract is enforced
**statically** by
[`examples/scripts/check-examples-assets.cjs`](../scripts/check-examples-assets.cjs)
(wired into `npm run test:script-policy`): for every example `index.html`
it verifies each referenced asset — `_shared/*` css/img plus the
transitive `@import` targets — resolves to a real file, and that every
page carries the required shared assets unless explicitly allowlisted
(TodoMVC opts out of the shared stylesheet; see that scanner's
`ALLOWLIST`).

## Visual identity

One typography stack, one palette, one stylesheet linked by every
`index.html`. Substrate variety is communicated via the substrate selector
+ the substrate-specific examples themselves, NOT via visual identity.

| Mood              | Editorial Warm — established, refined, magazine-leaning  |
| ----------------- | -------------------------------------------------------- |
| Typography pair   | Inter (UI + body) + JetBrains Mono (code, mono labels)   |
| Palette           | warm paper bg #F7F3EC / deep ink #1A1814 / amber #C8741A |
| Atmosphere        | paper-grain radial gradients fixed to the viewport       |

The pairing nods to the rest of the project (Xray uses Inter + JBM,
Story uses IBM Plex on a similarly light surface) so examples, dev
tools, and docs all sit naturally next to one another.

## Files

- `css/style.css` — the shared design system. Linked by
  every `index.html`. Imports `structure.css`.
- `css/structure.css` — substrate-agnostic structural baseline (form
  geometry, grid layout, max-widths).
- `img/favicon.svg` — shared favicon (warm-slate + amber accent).
- `img/og.svg` — shared Open Graph preview card.

## Adding a new example

1. Reference `_shared/css/style.css`, `_shared/img/favicon.svg`, and
   `_shared/img/og.svg` from the new example's `index.html` `<head>`.
2. For per-example layout-only inline CSS, use the `--ex-*` tokens.
3. No need to add new shared assets — substrate variety is no longer
   communicated via the design system.
