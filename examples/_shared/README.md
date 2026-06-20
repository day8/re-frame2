# `examples/_shared/` — shared examples design system

Shared stylesheet, favicon, and Open Graph imagery consumed by every
example `index.html` across every example substrate (Reagent, Reagent
Slim, UIx, Helix).
The smoke orchestrator
(`examples/scripts/serve-and-run-adapter-smokes.cjs`) stages this whole
tree into each staged surface's output dir — for the adapter testbeds it
drives, that is
`implementation/out/examples/adapter-testbeds/<name>/_shared/`, next to
the staged `index.html` + `main.js` — so every page references assets at
the same relative path (`_shared/css/style.css`). (The exact output dir is
declared per-entry as `outDir` in
[`examples/scripts/adapter-smoke-filter.cjs`](../scripts/adapter-smoke-filter.cjs);
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

**No remote fonts.** The stylesheet states Inter / JetBrains Mono as the
first-preference families but loads **no** web fonts — there is no
`@import` of Google Fonts (or any other host). The font stacks resolve
through their declared system fallbacks (`system-ui` / `Segoe UI` /
`-apple-system`; `ui-monospace` / `SF Mono` / `Menlo`), so a staged
example makes **zero** third-party network requests for styling. Offline,
firewalled, and privacy-sensitive local runs render the same, and
screenshots stay reproducible. (rf2-byf7y)

## Files

- `css/style.css` — the shared design system. Linked by
  every `index.html`. Imports `structure.css`.
- `css/structure.css` — substrate-agnostic structural baseline (form
  geometry, grid layout, max-widths).
- `img/favicon.svg` — shared favicon (warm-slate + amber accent). SVG is a
  valid favicon format (browsers render it), so it ships as-is.
- `img/og.png` — shared Open Graph preview card, a 1200×630 raster. This is
  the asset every `index.html` references and the one the asset gate requires:
  link-preview scrapers (Facebook / X / LinkedIn / Slack / Discord) do **not**
  render an SVG `og:image`, so the social card must be a raster (PNG/JPG).
- `img/og.svg` — editable SOURCE ART for the card above; not referenced by any
  page. Re-export `og.png` from it when the design changes (render the SVG at
  exactly 1200×630 — e.g. open it in a headless browser sized 1200×630 and
  screenshot, or any SVG→PNG rasteriser at that size). Its colour literals
  intentionally mirror the `--ex-*` tokens in `style.css`; the asset gate
  rejects re-introducing a retired/sub-AA palette value here (e.g. the old
  `#8A8270` muted ink, darkened to `#6E6654` for AA — rf2-y82dk9), so the
  social card keeps the shared palette's accessibility decisions.

## Responsive Xray-host shell

The inline-Xray examples (`counter`, `flows`) wrap their app + Xray panel in a
`.rf2-testbed-shell`:

```html
<div class="rf2-testbed-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

On the desktop this is a two-column flex — the app on the left, the inline Xray
host on the right at `--rf-xray-inline-width` (default 560px). Because the host
is `flex-shrink: 0` with a 320px `min-width`, the side-by-side layout needs
~624px before any app content shows, so on a phone it would overflow
horizontally even though the pages declare a responsive viewport.

`structure.css` therefore **stacks** the shell below a 900px breakpoint: the
columns become rows (app on top, Xray host below), the host drops its fixed
width / min-width and goes
full-bleed, and its height is capped (`max-height: 60vh` + internal scroll) so
the panel never crowds the app off-screen. The host/app DOM contract
(`.rf2-testbed-shell > #app` + `[data-rf-xray-host]`) is **unchanged**, so Xray
auto-mounting and both examples keep working at every width.

The asset gate (`check-examples-assets.cjs`) enforces this: a `structure.css`
with no `@media (max-width: …)` rule that stacks `.rf2-testbed-shell` to a
column turns the gate RED, so the shell cannot silently regress to an unbounded
horizontal layout (rf2-y82dk9).

## Adding a new example

1. Reference `_shared/css/style.css`, `_shared/img/favicon.svg`, and
   `_shared/img/og.png` (the raster social card — **not** the `.svg` source
   art) from the new example's `index.html` `<head>`.
2. For per-example layout-only inline CSS, use the `--ex-*` tokens.
3. No need to add new shared assets — substrate variety is no longer
   communicated via the design system.
